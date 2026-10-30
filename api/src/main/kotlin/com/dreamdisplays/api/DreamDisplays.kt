package com.dreamdisplays.api

import com.dreamdisplays.api.display.event.Events
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.placement.DisplayPlacements
import com.dreamdisplays.api.internal.ApiBackend
import com.dreamdisplays.api.internal.ApiHolder
import com.dreamdisplays.api.media.search.MediaSearchService
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.api.watchparty.WatchPartyService
import com.dreamdisplays.api.addon.AddonLifecycle

/**
 * Entry point of the `Dream Displays` API.
 *
 * Example:
 * ```
 * val display = DreamDisplays.display(id) ?: return
 * display.play()
 * display.volume = 0.5f
 * ```
 *
 * It is available on the client once `Dream Displays` has finished loading;
 * use [onReady] if your code may run earlier.
 *
 * @since 1.10.x
 */
@Unstable
object DreamDisplays {
    private val backend: ApiBackend
        get() = ApiHolder.current
            ?: error("Dream Displays API is not available yet. Use DreamDisplays.onReady { ... } or check \"isAvailable\".")

    /** True once `Dream Displays` has finished loading and the API can be used. */
    @JvmStatic
    val isAvailable: Boolean get() = ApiHolder.current != null

    /** Runs [action] as soon as the API is available: right away if it already is, otherwise once it loads. */
    @JvmStatic
    fun onReady(action: Runnable): Unit = ApiHolder.whenReady { action.run() }

    /**
     * Registers an addon: an instance of a class annotated with `@Addon` (and optionally implementing
     * [AddonLifecycle]).
     *
     * It is enabled when the API is ready and disabled when `Dream Displays` shuts down.
     */
    @JvmStatic
    fun register(addon: Any): Unit = ApiHolder.whenReady { it.registerAddon(addon) }

    /** The display with [id], or `null` when there is no such display right now. */
    @JvmStatic
    fun display(id: DisplayId): Display? = backend.display(id)

    /** The display with the given id text (a UUID), or `null` when it does not exist or the text is not a UUID. */
    @JvmStatic
    fun display(id: String): Display? = runCatching { DisplayId.from(id) }.getOrNull()?.let(::display)

    /** All displays, as a view you can narrow and act on as a group. */
    @JvmStatic
    val displays: Displays get() = backend.displays

    /** Events of every display. Narrower scopes are `Display.events` and `Session.events`. */
    @JvmStatic
    val events: Events get() = backend.events

    /** Starts a session that owns everything you subscribe to; close it (or use try-with-resources) when done. */
    @JvmStatic
    fun session(name: String): Session = backend.session(name)

    /** Runs [block] inside a session and closes it afterwards. */
    inline fun <T> session(name: String, block: Session.() -> T): T = session(name).use(block)

    /** Tell `Dream Displays` where a display really is when it moves. */
    @JvmStatic
    val placements: DisplayPlacements get() = backend.placements

    /** Limits for how many displays play at once. */
    @JvmStatic
    val budget: DisplayBudget get() = backend.budget

    /** `Watch party`: shared playback with other players. */
    @JvmStatic
    val watchParty: WatchPartyService get() = backend.watchParty

    /** Searches videos on the supported platforms. */
    @JvmStatic
    val search: MediaSearchService get() = backend.search

    /** Turns a pasted link into a playable video. */
    @JvmStatic
    val resolvers: MediaResolverRegistry get() = backend.resolvers

    /** The loader and Minecraft version `Dream Displays` is running on. */
    @JvmStatic
    val platform: Platform get() = backend.platform
}
