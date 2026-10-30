package com.dreamdisplays.api.internal

import com.dreamdisplays.api.Display
import com.dreamdisplays.api.DisplayBudget
import com.dreamdisplays.api.Displays
import com.dreamdisplays.api.display.event.Events
import com.dreamdisplays.api.display.placement.DisplayPlacements
import com.dreamdisplays.api.Session

import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.media.search.MediaSearchService
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.api.watchparty.WatchPartyService

/**
 * What `Dream Displays` plugs in behind itself. Implemented by the mod itself;
 * integrations never use this, they call `DreamDisplays` directly.
 *
 * @since 1.10.x
 */
interface ApiBackend {
    /** Registers and enables an addon. */
    fun registerAddon(addon: Any)

    /** The display with [id], or null when there is no such display right now. */
    fun display(id: DisplayId): Display?

    /** All displays, as a view you can narrow and act on as a group. */
    val displays: Displays

    /** Events of every display. */
    val events: Events

    /** Starts a session that owns everything you subscribe to; close it when you are done. */
    fun session(name: String): Session

    /** Where displays that are not at their stored coordinates are described. */
    val placements: DisplayPlacements

    /** Limits for how many displays play at once. */
    val budget: DisplayBudget

    /** `Watch party`: shared playback with other players. */
    val watchParty: WatchPartyService

    /** Searches videos on the supported platforms. */
    val search: MediaSearchService

    /** Turns a pasted link into a playable video. */
    val resolvers: MediaResolverRegistry

    /** The loader and Minecraft version Dream Displays is running on. */
    val platform: Platform
}
