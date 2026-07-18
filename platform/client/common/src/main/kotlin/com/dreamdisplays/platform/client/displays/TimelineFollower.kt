package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.core.protocol.DisplaySync
import com.dreamdisplays.platform.client.displays.TimelineFollower.Companion.SEEK_COOLDOWN_MS
import com.dreamdisplays.platform.client.displays.TimelineFollower.Companion.SEEK_LEAD_MS

/**
 * Follows the server-authoritative timeline for a [DisplayScreen]. The server is the single source
 * of truth: every [DisplaySync] (and watch-party snapshot) reports the
 * intended position *as of* send time, so the follower matches pause state and re-seeks only when
 * local drift leaves a tolerance band.
 *
 * The hard constraint shaping this class is that a corrective seek is not free: it restarts the
 * decoder (`FFmpeg`) from the new position, stalling playback for up to a few seconds while the stream
 * re-buffers. Naively re-seeking on every broadcast therefore creates a runaway loop — the seek
 * stalls, the server clock advances past the player during the stall, the next broadcast sees the
 * player "behind", and it seeks again, restarting forever. Three rules keep it stable:
 *  - Don't measure during a stall. While playing, drift is only evaluated once the player clock
 *    is actually running again ([DisplayScreen.isClockRunning]); a restarting stream is ignored.
 *  - Cool down between seeks. After a corrective seek we leave the stream alone for the full
 *    [SEEK_COOLDOWN_MS] — unconditionally, even when far behind.
 *  - Overshoot when catching up. A forward catch-up seek aims [SEEK_LEAD_MS] ahead of the live
 *    target so that, by the time the restart stall ends, the player lands on the live point instead
 *    of permanently behind. The band is also asymmetric: one-way network latency makes a synced
 *    player read slightly ahead, so we tolerate "ahead" generously and only catch up when behind.
 */
internal class TimelineFollower(private val screen: DisplayScreen) {
    /** [System.nanoTime] of the last corrective seek; gates the re-seek cooldown. 0 = never. */
    private var lastSeekNanos = 0L

    /** Server timestamp of the most recently applied packet; older packets are dropped. `MIN_VALUE` = none yet. */
    private var lastServerTimeMs = Long.MIN_VALUE

    /** Monotonic sequence stamped on each pending packet so stale deferred applies can be discarded. */
    private var nextSeq = 0L

    /** The latest timeline packet awaiting (re)application, or `null` when none is pending. */
    private var pending: Pending? = null

    /** A buffered timeline packet, captured at receive time so its target can be projected forward. */
    private data class Pending(
        val seq: Long,
        val targetMs: Long,
        val serverTimeMs: Long,
        val paused: Boolean,
        val loop: Boolean,
        val receivedAtNanos: Long,
    )

    /** Clears follow state when a new video loads. */
    fun reset() {
        lastSeekNanos = 0L
        lastServerTimeMs = Long.MIN_VALUE
        pending = null
    }

    /** Re-applies a timeline packet that arrived before the media player existed or finished init. */
    fun onPlayerCreated() {
        pending?.let(::applyPending)
    }

    /**
     * Matches the server's [targetMs] / [paused] for the current media, wrapping by the locally known
     * duration when [loop] (Broadcast). Defers until the player is initialized, projecting the target
     * forward by the time elapsed across that defer so the comparison uses an up-to-date position.
     */
    fun apply(targetMs: Long, serverTimeMs: Long, paused: Boolean, loop: Boolean) {
        if (serverTimeMs in 1..<lastServerTimeMs) return
        if (serverTimeMs > 0L) lastServerTimeMs = serverTimeMs
        val packet = Pending(++nextSeq, targetMs, serverTimeMs, paused, loop, System.nanoTime())
        pending = packet
        applyPending(packet)
    }

    /** Applies [packet] once the player is initialized: matches pause state and seeks only when drift leaves the tolerance band. */
    private fun applyPending(packet: Pending) {
        screen.primeTimelineStart(projectTargetMs(packet) * 1_000_000L)
        val generation = screen.mediaGeneration
        screen.waitForMFInit {
            if (generation != screen.mediaGeneration || pending?.seq != packet.seq) return@waitForMFInit

            val initialTimeline = screen.isWaitingForInitialTimeline
            if (!screen.videoStarted) screen.beginPlaybackPaused(packet.paused)

            // Pause-state matching is cheap and must never be gated by the seek cooldown
            if (packet.paused && !screen.isPaused) screen.applyServerPaused(true)
            if (!packet.paused && screen.isPaused) screen.applyServerPaused(false)

            if (!screen.canSeek()) {
                screen.markInitialTimelineReady()
                pending = null
                return@waitForMFInit
            }
            // While playing, a not-running clock means the stream is mid-restart; its position is
            // meaningless, so skip rather than read it as huge drift and seek again.
            if (!packet.paused && !screen.isClockRunning() && !initialTimeline) return@waitForMFInit

            val durationMs = screen.mediaPlayerDurationNanos / 1_000_000L
            val target = wrap(projectTargetMs(packet), packet.loop, durationMs)

            val localMs = screen.currentTimeNanos / 1_000_000L
            val driftMs = signedDrift(target - localMs, packet.loop, durationMs)
            // driftMs > 0 => player is behind the server; < 0 => player is ahead

            val sinceSeekMs = if (lastSeekNanos == 0L) Long.MAX_VALUE
            else (System.nanoTime() - lastSeekNanos) / 1_000_000L
            // Always honor the cooldown: re-seeking while a previous correction is still warming up is
            // exactly what makes a cold rejoin thrash. The first correction after a load is exempt
            // (lastSeekNanos == 0), so joining still snaps to live immediately.
            if (!initialTimeline && sinceSeekMs < SEEK_COOLDOWN_MS) return@waitForMFInit

            val needsSeek = driftMs > CATCH_UP_TOLERANCE_MS || -driftMs > AHEAD_TOLERANCE_MS
            if (!needsSeek) {
                screen.markInitialTimelineReady()
                pending = null
                return@waitForMFInit
            }

            // When catching up forward, overshoot by the restart cost so the post-stall position lands
            // on the live point. Clamp shy of the end so we never seek past EOS (which would loop to 0).
            val seekMs = if (driftMs > 0 && !packet.paused) {
                val lead = target + SEEK_LEAD_MS
                wrap(
                    if (!packet.loop && durationMs > 0) lead.coerceAtMost(durationMs - END_MARGIN_MS) else lead,
                    packet.loop,
                    durationMs
                )
            } else target

            screen.clearRenderedFrameForTimeline()
            screen.seekVideoTo(seekMs * 1_000_000L)
            lastSeekNanos = System.nanoTime()
            screen.markInitialTimelineReady()
            pending = null
        }
    }

    /** Projects [packet]'s target forward by the time elapsed since it was received (unchanged while paused). */
    private fun projectTargetMs(packet: Pending): Long {
        val elapsedMs = if (packet.paused) 0L else (System.nanoTime() - packet.receivedAtNanos) / 1_000_000L
        return packet.targetMs + elapsedMs
    }

    /** Wraps [ms] into [0, durationMs) for [loop] (Broadcast); otherwise just clamps to >= 0. */
    private fun wrap(ms: Long, loop: Boolean, durationMs: Long): Long =
        if (loop && durationMs > 0) ((ms % durationMs) + durationMs) % durationMs else ms.coerceAtLeast(0)

    /** Collapses a raw target-local difference to the shortest signed distance, wrapping for [loop]. */
    private fun signedDrift(rawDiff: Long, loop: Boolean, durationMs: Long): Long {
        if (!loop || durationMs <= 0) return rawDiff
        val half = durationMs / 2
        return ((rawDiff % durationMs) + durationMs + half) % durationMs - half
    }

    companion object {
        /** The player is behind the server by more than this: seek forward to catch up. */
        private const val CATCH_UP_TOLERANCE_MS = 1_500L

        /** The player is ahead by more than this (beyond plausible latency): real desync, hard-correct. */
        private const val AHEAD_TOLERANCE_MS = 3_500L

        /** A forward catch-up seek overshoots by this much to absorb the decoder restart stall. */
        private const val SEEK_LEAD_MS = 2_500L

        /** After a corrective seek, leave the stream alone this long so it can re-buffer before re-measuring. */
        private const val SEEK_COOLDOWN_MS = 7_000L

        /** Keep catch-up overshoot this far shy of the end so we never seek past EOS. */
        private const val END_MARGIN_MS = 500L
    }
}
