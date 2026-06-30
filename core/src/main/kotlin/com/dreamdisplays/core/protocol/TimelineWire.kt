package com.dreamdisplays.core.protocol

import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.playback.Timeline
import kotlinx.serialization.Serializable
import java.util.UUID

/** Builds the wire [DisplaySync] for [id] in [mode], stamped with the current [nowMs] anchor. */
fun Timeline.toSync(id: @Serializable(UuidSerializer::class) UUID, mode: PlaybackMode, nowMs: Long): DisplaySync {
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

/** Reconstructs a core [Timeline] from an incoming wire [DisplaySync] packet. */
fun DisplaySync.toTimeline(): Timeline = Timeline(
    positionMs = currentTimeMs,
    serverTimeMs = serverTimeMs,
    paused = isPaused,
    durationMs = durationMs,
    loop = loop,
)
