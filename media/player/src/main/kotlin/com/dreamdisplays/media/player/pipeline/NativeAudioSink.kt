package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.api.media.audio.AudioDspStage
import com.dreamdisplays.api.media.audio.SourceAcousticState
import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.media.player.util.daemon
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the in-process (Rust `dreamdisplays_lav`) audio session for one playback session: no PCM ever
 * crosses back into the JVM, decode AND `cpal` playback both run natively. This is the audio master
 * clock's source (see [sampleClock]) and the acoustics DSP publish point (see the watcher thread).
 */
internal class NativeAudioSink(
    private val debugLabel: String,
    private val terminated: AtomicBoolean,
    private val audioStage: AudioDspStage?,
) {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/NativeAudioSink")

    companion object {
        /** Cadence of the acoustics-publish / liveness-check watcher. */
        private const val WATCH_INTERVAL_MS = 50L

        /** How long a track-switch replacement session is given to buffer ahead before promotion. */
        private const val SWITCH_PRIME_DELAY_MS = 250L
    }

    /**
     * Sample of the audio master clock; see [sampleClock]. [originKnown] is always true for a native
     * session (its start position is always given explicitly to `dd_lav_audio_open*`), but the field is
     * kept so [AudioMasterClock]'s exact-PTS-anchor / wall-anchor fallback stays available generically.
     */
    class ClockSample(@JvmField val nanos: Long, @JvmField val epoch: Int, @JvmField val originKnown: Boolean = true) {
        companion object {
            /** No line clock available. */
            val NONE = ClockSample(-1L, 0, false)
        }
    }

    /** One native audio session. Mutated only by the owning thread except where marked `@Volatile`. */
    private class Session(
        @JvmField val epoch: Int,
        @JvmField val handle: Long,
        @JvmField val isHls: Boolean,
        @JvmField val contentStartNanos: Long,
        @JvmField val preludeNanos: Long = 0L,
    ) {
        @Volatile
        @JvmField
        var exposeLiveClock = preludeNanos == 0L

        @Volatile
        @JvmField
        var closed = false

        @Volatile
        @JvmField
        var lastPushedAcoustics: SourceAcousticState? = null

        @Volatile
        @JvmField
        var failureReported = false
    }

    /** Current volume multiplier, applied as the acoustics chain's legacy-gain bypass value. */
    @Volatile
    var currentVolume: Double = 1.0
        set(value) {
            field = value
            current?.let { s -> if (!s.closed) NativeMedia.lavAudioSetVolume(s.handle, value.toFloat()) }
        }

    private val sessionLock = Any()
    private var epochCounter = 0

    @Volatile
    private var current: Session? = null

    @Volatile
    private var liveGate: CountDownLatch? = null

    private val watcherRunning = AtomicBoolean(true)
    private val watcherCallbacks = HashMap<Session, (String) -> Unit>()
    private val unavailableWarned = AtomicBoolean(false)

    // Started last, in an init block rather than a property initializer, so every property this
    // sink's own methods touch (watcherCallbacks, unavailableWarned, ...) is guaranteed assigned
    // before the watcher thread's first loop iteration can possibly run.
    private val watcher = daemon(::runWatcher, "MediaPlayer-audio-watch")

    init {
        watcher.start()
    }

    /**
     * Opens a session immediately at [contentStartNanos]. If [startGate] is given, the session decodes
     * but plays silently (paused) until the gate opens (used to hold audio until video's first frame).
     * Never throws: any failure here degrades to "no audio" (returns false) rather than risking the
     * caller's video session — matches how a missing/failed audio process was always non-fatal here.
     */
    fun start(
        url: String, isHls: Boolean, contentStartNanos: Long,
        startGate: CountDownLatch? = null, onUnexpectedEnd: (String) -> Unit = {},
    ): Boolean = runCatching {
        val handle = open(url, isHls, contentStartNanos)
        if (handle == 0L) return@runCatching false
        val session = beginSession(handle, isHls, contentStartNanos)
        onUnexpectedEndFor(session, onUnexpectedEnd)
        if (startGate != null && startGate.count > 0L) {
            NativeMedia.lavAudioPause(handle)
            daemon({
                if (awaitGate(startGate) && owns(session)) NativeMedia.lavAudioResume(handle)
            }, "MediaPlayer-audio-startgate").start()
        }
        true
    }.getOrElse { e ->
        logger.warn("$debugLabel [audio] failed to open a session: ${e.message}.")
        false
    }

    /**
     * Opens a replacement session paused, primes it briefly, then flips it in if [shouldPromote] still
     * holds — the seamless audio-track switch. Runs entirely on a background thread.
     */
    fun startSwitch(
        url: String, isHls: Boolean, contentStartNanos: Long,
        shouldPromote: () -> Boolean, onPromoted: () -> Unit, onAborted: () -> Unit = {},
        onUnexpectedEnd: (String) -> Unit = {},
    ) {
        daemon({
            val handle = open(url, isHls, contentStartNanos)
            if (handle == 0L) {
                onAborted(); return@daemon
            }
            NativeMedia.lavAudioPause(handle)
            NativeMedia.lavAudioSetVolume(handle, currentVolume.toFloat())
            var waited = 0L
            while (waited < SWITCH_PRIME_DELAY_MS && !terminated.get()) {
                Thread.sleep(20L); waited += 20L
            }
            if (terminated.get() || !shouldPromote()) {
                NativeMedia.lavAudioKill(handle)
                NativeMedia.lavAudioClose(handle)
                onAborted()
                return@daemon
            }
            promote(handle, isHls, contentStartNanos, onUnexpectedEnd)
            logger.debug("$debugLabel [audio] switch session promoted — new track playing.")
            onPromoted()
        }, "MediaPlayer-audio-switch-line").start()
    }

    /**
     * Adopts an already-open, paused native handle (typically from [com.dreamdisplays.media.player.managers.AudioTrackWarmPool])
     * as the current session: resumes it and retires whatever was current. No priming wait — the
     * handle is assumed to already be decoding ahead.
     */
    fun adopt(handle: Long, isHls: Boolean, contentStartNanos: Long, onUnexpectedEnd: (String) -> Unit = {}) {
        promote(handle, isHls, contentStartNanos, onUnexpectedEnd)
    }

    /** Swaps [handle] in as the current session and resumes it, retiring whatever was current. */
    private fun promote(handle: Long, isHls: Boolean, contentStartNanos: Long, onUnexpectedEnd: (String) -> Unit) {
        val old = current
        val session = beginSession(handle, isHls, contentStartNanos)
        NativeMedia.lavAudioResume(handle)
        onUnexpectedEndFor(session, onUnexpectedEnd)
        old?.let { closeSession(it) }
    }

    /**
     * Starts a reappearance bridge: plays the cached [prelude] immediately, then — on the same native
     * session — waits for [provideLiveInput] to supply the live URL and continues on it, sample-continuous.
     */
    fun startBridge(
        prelude: ByteArray, preludeSampleRate: Int, preludeChannels: Int, liveEdgeNanos: Long,
        onUnexpectedEnd: (String) -> Unit = {},
    ): Boolean = runCatching {
        if (!NativeMedia.lavAudioAvailable) return@runCatching false
        liveGate = CountDownLatch(1)
        val handle = NativeMedia.lavAudioOpenBridge(prelude, preludeSampleRate, preludeChannels, liveEdgeNanos)
        if (handle == 0L) {
            liveGate = null
            return@runCatching false
        }
        val preludeNanos = if (preludeChannels > 0 && preludeSampleRate > 0) {
            (prelude.size / 4L / preludeChannels) * 1_000_000_000L / preludeSampleRate
        } else 0L
        val session = beginSession(handle, isHls = false, contentStartNanos = liveEdgeNanos, preludeNanos = preludeNanos)
        onUnexpectedEndFor(session, onUnexpectedEnd)
        val cachedSec = preludeNanos / 1_000_000_000.0
        logger.debug("$debugLabel [audio] bridge session opened; playing ${"%.2f".format(cachedSec)} s cached prelude.")
        true
    }.getOrElse { e ->
        liveGate = null
        logger.warn("$debugLabel [audio] failed to open a bridge session: ${e.message}.")
        false
    }

    /** Supplies the live URL to an in-flight bridge session (see [startBridge]). */
    fun provideLiveInput(url: String): Boolean {
        val s = current ?: return false
        val ok = NativeMedia.lavAudioProvideLive(s.handle, url)
        liveGate?.countDown()
        return ok
    }

    /** Marks the replay -> live handoff: the master clock may now read the (live-relative) native clock. */
    fun onBridgeHandoff() {
        current?.exposeLiveClock = true
    }

    /** Samples the audio master clock; [ClockSample.NONE] while unavailable (caller falls back to wall time). */
    fun sampleClock(): ClockSample {
        val s = current ?: return ClockSample.NONE
        if (s.closed || !s.exposeLiveClock) return ClockSample.NONE
        val raw = NativeMedia.lavAudioPositionNanos(s.handle)
        val live = raw - s.preludeNanos
        if (live < 0L) return ClockSample.NONE
        return ClockSample(s.contentStartNanos + live, s.epoch, originKnown = true)
    }

    /** Asks the current session to skip ahead by [nanos] of content (re-sync after a stall takeover). */
    fun requestResync(nanos: Long) {
        if (nanos <= 0L) return
        val s = current ?: return
        if (s.closed) return
        val raw = NativeMedia.lavAudioPositionNanos(s.handle)
        val live = (raw - s.preludeNanos).coerceAtLeast(0L)
        val targetNanos = s.contentStartNanos + live + nanos
        val target = if (s.isHls) targetNanos else targetNanos / 1000L
        if (NativeMedia.lavAudioSeek(s.handle, target)) {
            logger.debug("$debugLabel [audio] re-synced by seeking ahead ${nanos / 1_000_000} ms.")
        }
    }

    /** Captures up to [maxBytes] of cached PCM for the reappearance bridge, or null. */
    fun snapshotPcm(maxBytes: Int): NativeMedia.PcmSnapshot? {
        val s = current ?: return null
        if (s.closed) return null
        return NativeMedia.lavAudioSnapshotPcm(s.handle, maxBytes)
    }

    /** Closes the current session immediately, retiring the clock. Safe to call when idle. */
    fun stop() {
        liveGate?.countDown() // Release a bridge thread still waiting for its live input
        val s = synchronized(sessionLock) { current.also { current = null } } ?: return
        closeSession(s)
    }

    /** Pauses playback for a warm park without closing or losing decode progress. */
    fun pauseForPark() {
        current?.let { if (!it.closed) NativeMedia.lavAudioPause(it.handle) }
    }

    /** Resumes a session paused by [pauseForPark]. */
    fun resumeFromPark() {
        current?.let { if (!it.closed) NativeMedia.lavAudioResume(it.handle) }
    }

    /** Permanently stops the watcher thread. Call once when this sink is discarded for good. */
    fun close() {
        watcherRunning.set(false)
        stop()
    }

    /** Opens a native session for [url], routing through the HLS (segment-aware, fMP4-safe) demuxer when [isHls]. */
    private fun open(url: String, isHls: Boolean, contentStartNanos: Long): Long {
        if (!NativeMedia.lavAudioAvailable) {
            if (unavailableWarned.compareAndSet(false, true)) {
                logger.warn("$debugLabel [audio] native audio engine unavailable — playing silently.")
            }
            return 0L
        }
        return if (isHls) NativeMedia.lavAudioOpenHls(url, contentStartNanos)
        else NativeMedia.lavAudioOpen(url, contentStartNanos / 1000L)
    }

    /** Publishes a fresh session as the clock / acoustics target, superseding whatever was current. */
    private fun beginSession(
        handle: Long, isHls: Boolean, contentStartNanos: Long, preludeNanos: Long = 0L,
    ): Session {
        val session = synchronized(sessionLock) {
            Session(++epochCounter, handle, isHls, contentStartNanos, preludeNanos).also { current = it }
        }
        NativeMedia.lavAudioSetVolume(handle, currentVolume.toFloat())
        pushAcoustics(session)
        return session
    }

    /** True while [session] is the one driving playback. */
    private fun owns(session: Session): Boolean = current === session

    /** Kills and closes [session]'s native handle. */
    private fun closeSession(session: Session) {
        if (session.closed) return
        session.closed = true
        NativeMedia.lavAudioKill(session.handle)
        NativeMedia.lavAudioClose(session.handle)
    }

    /** Pushes [audioStage]'s latest state to [session]'s handle if it changed since the last push. */
    private fun pushAcoustics(session: Session) {
        val stage = audioStage ?: return
        val state = stage.latestState()
        if (state == session.lastPushedAcoustics) return
        session.lastPushedAcoustics = state
        NativeMedia.lavAudioSetAcoustics(session.handle, state)
    }

    /** Background watcher: republishes acoustics state and reports a session's terminal error once. */
    private fun runWatcher() {
        while (watcherRunning.get()) {
            try {
                Thread.sleep(WATCH_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return
            }
            val s = current ?: continue
            if (s.closed) continue
            pushAcoustics(s)
            if (!s.failureReported) {
                val err = NativeMedia.lavAudioError(s.handle)
                if (err.isNotEmpty()) {
                    s.failureReported = true
                    synchronized(watcherCallbacks) { watcherCallbacks.remove(s) }?.invoke(err)
                }
            }
        }
    }

    /** Registers [onUnexpectedEnd] to fire (once) the first time [session]'s handle reports an error. */
    private fun onUnexpectedEndFor(session: Session, onUnexpectedEnd: (String) -> Unit) {
        synchronized(watcherCallbacks) { watcherCallbacks[session] = onUnexpectedEnd }
    }

    /** Waits for [gate] to open, polling so [terminated] can interrupt quickly. Returns false if aborted. */
    private fun awaitGate(gate: CountDownLatch): Boolean {
        while (!terminated.get()) {
            try {
                if (gate.await(50L, TimeUnit.MILLISECONDS)) return true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            }
        }
        return false
    }
}
