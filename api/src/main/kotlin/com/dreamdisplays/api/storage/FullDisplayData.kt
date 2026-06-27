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
class FullDisplayData(
    @Serializable(with = UuidStringSerializer::class)
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
    @Serializable(with = UuidStringSerializer::class)
    var ownerUuid: UUID,
    var renderDistance: Int = 96,
    var currentTimeNanos: Long = 0,
    var rotation: Int = 0,
    var qualityCap: Int = 0,
)
