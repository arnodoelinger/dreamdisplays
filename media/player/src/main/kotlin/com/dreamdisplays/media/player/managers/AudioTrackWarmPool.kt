package com.dreamdisplays.media.player.managers

import com.dreamdisplays.media.player.pipeline.AudioSink
import com.dreamdisplays.media.player.process.FFmpegBinary
import com.dreamdisplays.media.player.process.MediaProcess
import com.dreamdisplays.media.player.util.daemon
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** An audio track eligible for pre-warming, and how its process has to reach a position. */
internal data class WarmTrack(val url: String, val seekByDecoding: Boolean)

/**
 * Keeps `FFmpeg` processes running for the audio tracks the viewer has not selected, so picking a
 * different dub promotes an already-decoding line.
 */
internal class AudioTrackWarmPool(
    private val debugLabel: String,
    private val terminated: AtomicBoolean,

    /** Current content position; shadows are (re)spawned here. */
    private val positionNanos: () -> Long,

    /** True only while an ordinary, un-parked VOD session is playing. */
    private val eligible: () -> Boolean,
) {
    /** A shadow process, holding PCM that begins at [contentStartNanos]. */
    class Warm(
        val url: String,
        val process: Process,
        val stop: AtomicBoolean,
        val contentStartNanos: Long,
    )

    private val logger = LoggerFactory.getLogger(javaClass)

    private val lock = Any()
    private val warm = LinkedHashMap<String, Warm>()
    private var wanted: List<WarmTrack> = emptyList()
    private var refresher: Thread? = null
    private val closed = AtomicBoolean(false)

    /** Declares the tracks to keep warm; the one currently playing must not be among them. */
    fun setTracks(tracks: List<WarmTrack>) {
        if (closed.get()) return
        val capped = tracks.take(MAX_WARM)
        synchronized(lock) {
            wanted = capped
            if (capped.isNotEmpty() && refresher == null) {
                refresher = daemon(::runRefreshLoop, "MediaPlayer-audio-warm").also { it.start() }
            }
        }
        if (capped.isEmpty()) dropAll()
    }

    /**
     * Hands the warm line for [url] over to the caller, which takes ownership of its process and stop
     * flag, or returns null when nothing usable is pooled. A shadow that died, has not produced any PCM
     * yet, or sits on the wrong side of a seek is dropped instead of returned.
     */
    fun take(url: String): Warm? {
        val w = synchronized(lock) { warm.remove(url) } ?: return null
        val drift = positionNanos() - w.contentStartNanos
        val usable = w.process.isAlive &&
                drift >= 0L && drift <= MAX_TAKE_DRIFT_NANOS &&
                runCatching { w.process.inputStream.available() > 0 }.getOrDefault(false)
        if (!usable) {
            discardAsync(listOf(w))
            return null
        }
        return w
    }

    /** Drops every shadow; call whenever the playhead jumps or the session stops or parks. */
    fun invalidateAll() = dropAll()

    /** Permanently shuts the pool down. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val t = synchronized(lock) {
            wanted = emptyList()
            refresher.also { refresher = null }
        }
        t?.interrupt()
        dropAll()
    }

    /** Periodically reconciles the pool until the pool is closed or the player is torn down. */
    private fun runRefreshLoop() {
        while (!closed.get() && !terminated.get()) {
            try {
                Thread.sleep(TICK_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            runCatching { reconcile() }
                .onFailure { logger.debug("$debugLabel [audio-warm] refresh pass failed: ${it.message}") }
        }
        dropAll()
    }

    /** Drops shadows that went stale or are no longer wanted, then spawns whatever is missing. */
    private fun reconcile() {
        if (!eligible()) {
            dropAll()
            return
        }
        val tracks = synchronized(lock) { wanted }
        if (tracks.isEmpty()) {
            dropAll()
            return
        }
        val ffmpeg = FFmpegBinary.getPath() ?: return
        val keep = tracks.mapTo(HashSet()) { it.url }
        val now = positionNanos()

        val drop = ArrayList<Warm>()
        synchronized(lock) {
            val entries = warm.entries.iterator()
            while (entries.hasNext()) {
                val w = entries.next().value
                val drift = now - w.contentStartNanos
                // A negative drift means the viewer seeked back behind this shadow's own start, so its
                // PCM is ahead of the playhead and no forward catch-up could ever line it up again.
                if (w.url !in keep || !w.process.isAlive || drift < 0L || drift > MAX_STALENESS_NANOS) {
                    drop += w
                    entries.remove()
                }
            }
        }
        discardAsync(drop)

        for (track in tracks) {
            if (closed.get() || terminated.get() || !eligible()) return
            if (synchronized(lock) { warm.containsKey(track.url) }) continue
            spawn(ffmpeg, track)
        }
    }

    /** Starts one shadow at the current playhead and files it under its track URL. */
    private fun spawn(ffmpeg: String, track: WarmTrack) {
        val at = positionNanos().coerceAtLeast(0L)
        val stop = AtomicBoolean()
        val proc = runCatching {
            MediaProcess.buildAudio(
                ffmpeg, track.url, at, AudioSink.SAMPLE_RATE, seekByDecoding = track.seekByDecoding,
            )
        }.getOrElse {
            logger.debug("$debugLabel [audio-warm] could not pre-warm a track: ${it.message}")
            return
        }
        val warmed = Warm(track.url, proc, stop, at)
        // The pool can close, and a racing pass can file its own shadow, while the spawn above runs;
        // either way exactly one process per track survives and the loser is torn down here.
        val discard = ArrayList<Warm>(1)
        synchronized(lock) {
            if (closed.get()) discard += warmed else warm.put(track.url, warmed)?.let { discard += it }
        }
        discardAsync(discard)
    }

    /** Empties the pool, tearing the shadows down off-thread. */
    private fun dropAll() {
        val all = synchronized(lock) { warm.values.toList().also { warm.clear() } }
        discardAsync(all)
    }

    /** Stops and destroys [items] on a throwaway thread, so no caller waits on process teardown. */
    private fun discardAsync(items: List<Warm>) {
        if (items.isEmpty()) return
        daemon({
            items.forEach {
                it.stop.set(true)
                MediaProcess.gracefulDestroy(it.process)
            }
        }, "MediaPlayer-audio-warm-drop").start()
    }

    private companion object {
        /** Most shadows held at once; each is an idle FFmpeg holding a socket and a pipe buffer. */
        const val MAX_WARM = 3

        /** How often the refresh pass runs. */
        const val TICK_MS = 2_000L

        /** Drift past which a shadow is respawned at the current playhead. */
        const val MAX_STALENESS_NANOS = 10_000_000_000L

        /** Drift a switch still accepts; past it burning through the gap costs more than a cold spawn. */
        const val MAX_TAKE_DRIFT_NANOS = 20_000_000_000L
    }
}
