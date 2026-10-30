package com.dreamdisplays.api.server

import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.display.data.MutableDisplayCustomData
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackMode
import java.util.UUID

/**
 * A display as the server knows it: who owns it, where it is and what it shows.
 *
 * The server does not play video; playback happens on the players' clients.
 *
 * @since 1.10.x
 */
interface ServerDisplay {
    /** Persistent ID. */
    val id: UUID

    /** The player who created the display. */
    val ownerId: UUID

    /** `false` once the display has been removed. */
    val isAlive: Boolean

    /** Name of the world the display is in. */
    val world: String

    /** [x] coordinate in the world. */
    val x: Double

    /** [y] coordinate in the world. */
    val y: Double

    /** [z] coordinate in the world. */
    val z: Double

    /** Width of display. */
    val width: Int

    /** Height of display. */
    val height: Int

    /** Optional short name, or `null`. */
    val name: String?

    /** The video link, or empty when none is set. */
    val url: String

    /** Preferred audio language code of the video. */
    val lang: String

    /** Persistent base playback mode. */
    val mode: PlaybackMode

    /** Who may change this display. */
    val access: DisplayAccess

    /**
     * Events of this display only.
     *
     * Subscriptions end by themselves when the display is removed;
     * close a returned handle to end one earlier.
     */
    val events: ServerEvents

    /** Data addons keep on this display: saved with it and sent to every client that sees it. */
    val customData: MutableDisplayCustomData

    /**
     * Moves the display (and resizes it to fit) to the area between two opposite corner blocks in [world], facing
     * [facing].
     *
     * The area must be free of other displays ([CommandResult.REFUSED] when it overlaps one or the world is
     * unknown).
     *
     * Nearby players are told.
     */
    fun moveTo(world: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, facing: DisplayFacing): CommandResult

    /** Same as the world version, staying in this display's [world]. */
    fun moveTo(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, facing: DisplayFacing): CommandResult

    /**
     * Changes the video.
     *
     * The link goes through the same rules as a player's request (allowed hosts, custom-link policy);
     * a refused link answers [CommandResult.REFUSED].
     *
     * Saves, tells nearby players and restarts the timeline.
     */
    fun setUrl(url: String, lang: String = ""): CommandResult

    /** Changes who may use the display. */
    fun setAccess(access: DisplayAccess): CommandResult

    /** Removes the display and tells nearby players. */
    fun delete(): CommandResult
}
