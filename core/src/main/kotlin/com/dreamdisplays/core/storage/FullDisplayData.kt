package com.dreamdisplays.core.storage

import com.dreamdisplays.core.display.DisplayFacing
import com.dreamdisplays.core.playback.PlaybackMode
import java.util.UUID

/**
 * Full persisted snapshot of a single display on a server, stored in `server-{serverId}-displays.json`.
 *
 * Holds everything needed to recreate a [com.dreamdisplays.platform.client.displays.DisplayScreen] after a reconnect:
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
    /** Content quarter-turn rotation (0-3); only used for floor/ceiling (`UP`/`DOWN`) screens. */

    var rotation: Int = 0,

    /** Broadcast quality clamp (0 = unclamped); see [com.dreamdisplays.platform.client.displays.DisplayScreen.qualityCap]. */
    var qualityCap: Int = 0,
)
