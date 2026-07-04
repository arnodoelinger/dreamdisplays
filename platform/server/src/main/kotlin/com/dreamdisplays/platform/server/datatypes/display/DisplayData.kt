package com.dreamdisplays.platform.server.datatypes.display

import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.display.model.ContentRotation
import com.dreamdisplays.api.playback.PlaybackPermissions
import java.util.*

/**
 * Shared display data.
 *
 * Per-platform concrete classes ([PaperDisplayData], [VanillaDisplayData]) carry platform-specific
 * position / box types; shared state and identity live on this interface.
 */
interface DisplayData {
    /** Identifier of the display. */
    val id: UUID

    /** Identifier of the display owner. */
    val ownerId: UUID

    /** Display [width] in blocks. */
    val width: Int

    /** Display [height] in blocks. */
    val height: Int

    /** Content rotation; only meaningful for floor / ceiling (`UP` / `DOWN`) facings. */
    val rotation: ContentRotation

    /** The display's URL. */
    var url: String

    /** Video's language code. */
    var lang: String

    /** The persistent base playback mode. Source of truth; never [PlaybackMode.WATCH_PARTY]. */
    var mode: PlaybackMode

    /** Whether the display is locked to its owner. */
    var isLocked: Boolean

    /** Duration of the video. */
    var duration: Long?

    /** Legacy mirror of [mode] for frozen-v1 peers; true only when the mode is [PlaybackMode.SYNCED]. */
    val isSync: Boolean get() = mode == PlaybackMode.SYNCED

    /** Max video height clients must not exceed (0 = uncapped, 360 for [PlaybackMode.BROADCAST]). */
    val qualityCap: Int; get() = if (mode == PlaybackMode.BROADCAST) PlaybackPermissions.BROADCAST_QUALITY_CAP else 0
}

/**
 * Base shared by [PaperDisplayData] and [VanillaDisplayData], holding the mutable playback /
 * content fields common to both so platform subclasses only add their position types.
 */
abstract class BaseDisplayData : DisplayData {
    /** The display's URL. */
    override var url: String = ""

    /** Video's language code. */
    override var lang: String = ""

    /** The persistent base playback mode. */
    override var mode: PlaybackMode = PlaybackMode.LOCAL

    /** Is the display locked to its owner. */
    override var isLocked: Boolean = true

    /** Duration of the video. */
    override var duration: Long? = null
}
