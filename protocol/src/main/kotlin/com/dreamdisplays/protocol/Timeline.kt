package com.dreamdisplays.protocol

/**
 * An authoritative playback clock. [positionMs] is the playback position *as of* [serverTimeMs];
 * a running timeline advances in real time from that anchor. Pure value type shared by the server
 * (which owns it) and the client (which follows it via [DisplaySync]).
 */
data class Timeline(
    val positionMs: Long,
    val serverTimeMs: Long,
    val paused: Boolean,

    /** Total media duration in ms; 0 means live / unknown. */
    val durationMs: Long = 0,

    /** When true, [positionAt] wraps around [durationMs] forever (Broadcast). */
    val loop: Boolean = false,
) {
    /** Position at server-time [nowMs]: advances when running, wraps by [durationMs] when [loop]. */
    fun positionAt(nowMs: Long): Long {
        if (paused) return positionMs
        val raw = positionMs + (nowMs - serverTimeMs)
        if (raw <= 0) return 0
        return if (loop && durationMs > 0) raw % durationMs else raw
    }

    /** Re-anchors so the clock is continuous at [nowMs] (used before pause / seek / duration changes). */
    fun anchoredAt(nowMs: Long): Timeline = copy(positionMs = positionAt(nowMs), serverTimeMs = nowMs)

    /** A copy paused (or resumed) at [nowMs] without jumping position. */
    fun withPaused(paused: Boolean, nowMs: Long): Timeline = anchoredAt(nowMs).copy(paused = paused)

    /** A copy seeked to [positionMs] at [nowMs], keeping the current pause state. */
    fun seekedTo(positionMs: Long, nowMs: Long): Timeline =
        copy(positionMs = positionMs.coerceAtLeast(0), serverTimeMs = nowMs)

    /** Builds the wire [DisplaySync] for [id] in [mode], stamped with the current [nowMs] anchor. */
    fun toSync(id: ProtoUuid, mode: PlaybackMode, nowMs: Long): DisplaySync {
        val anchored = anchoredAt(nowMs)
        return DisplaySync(
            id = id,
            isSync = mode == PlaybackMode.SYNCED,
            isPaused = anchored.paused,
            currentTimeMs = anchored.positionMs,
            durationMs = anchored.durationMs,
            serverTimeMs = anchored.serverTimeMs,
            loop = anchored.loop,
            mode = mode.wire,
        )
    }

    companion object {
        /** A fresh timeline anchored at [nowMs], position 0. */
        fun start(nowMs: Long, paused: Boolean = false, durationMs: Long = 0, loop: Boolean = false) =
            Timeline(0, nowMs, paused, durationMs, loop)

        /** Reconstructs the client-side timeline from an incoming [sync] packet. */
        fun fromSync(sync: DisplaySync): Timeline = Timeline(
            positionMs = sync.currentTimeMs,
            serverTimeMs = sync.serverTimeMs,
            paused = sync.isPaused,
            durationMs = sync.durationMs,
            loop = sync.loop,
        )
    }
}
