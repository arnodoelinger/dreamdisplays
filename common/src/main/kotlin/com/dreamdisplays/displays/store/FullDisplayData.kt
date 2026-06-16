package com.dreamdisplays.displays.store

import com.dreamdisplays.api.DisplayFacing
import com.dreamdisplays.protocol.PlaybackMode
import java.util.UUID

/**
 * Full persisted snapshot of a single display on a server, stored in `server-{serverId}-displays.json`.
 *
 * Holds everything needed to recreate a [com.dreamdisplays.displays.DisplayScreen] after a reconnect:
 * world placement, dimensions, the resolved video, and the last-known playback settings.
 */
class FullDisplayData(
    var uuid: UUID,
    var x: Int,
    var y: Int,
    var z: Int,
    var facing: DisplayFacing,
    var width: Int,
    var height: Int,
    var videoUrl: String,
    var lang: String,
    var volume: Float,
    var quality: String,
    var brightness: Float,
    var muted: Boolean,
    var mode: PlaybackMode?,
    var ownerUuid: UUID,
    var renderDistance: Int = 96,
    var currentTimeNanos: Long = 0,
)
