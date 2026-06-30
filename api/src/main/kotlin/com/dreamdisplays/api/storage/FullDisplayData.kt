package com.dreamdisplays.api.storage

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.display.model.DisplayFacing
import com.dreamdisplays.api.playback.PlaybackMode
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Full persisted snapshot of a single display on a server.
 *
 * Holds everything needed to recreate a display.
 *
 * Render distance here is the distance at which the display is rendered. Can be replaced with a different
 * approach or removed entirely.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
@Serializable
data class FullDisplayData(
    @Serializable(with = UuidStringSerializer::class)
    var uuid: UUID = UUID(0L, 0L),
    var x: Int = 0,
    var y: Int = 0,
    var z: Int = 0,
    var facing: DisplayFacing = DisplayFacing.NORTH,
    var width: Int = 1,
    var height: Int = 1,
    var videoUrl: String = "",
    var lang: String = "",
    var volume: Float = 0.5f,
    var quality: String = "720",
    var brightness: Float = 1.0f,
    var muted: Boolean = false,
    var mode: PlaybackMode? = PlaybackMode.LOCAL,
    @Serializable(with = UuidStringSerializer::class)
    var ownerUuid: UUID = uuid,
    var renderDistance: Int = 96,
    var currentTimeNanos: Long = 0,
    var rotation: Int = 0,
    var qualityCap: Int = 0,
)
