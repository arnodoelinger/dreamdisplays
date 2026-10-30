package com.dreamdisplays.core.runtime

import com.dreamdisplays.api.Display
import com.dreamdisplays.api.DisplayBudget
import com.dreamdisplays.api.Displays
import com.dreamdisplays.api.internal.ApiBackend
import com.dreamdisplays.api.Session
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.core.services.DisplayService
import com.dreamdisplays.api.media.search.MediaSearchService
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.platform.identity.Platform
import com.dreamdisplays.core.services.PlaybackService
import com.dreamdisplays.api.watchparty.WatchPartyService
import com.dreamdisplays.api.display.placement.DisplayPlacements
import com.dreamdisplays.core.addon.AddonManager
import com.dreamdisplays.core.display.PlacementHub
import com.dreamdisplays.api.display.event.Events
import com.dreamdisplays.core.api.DefaultDisplays
import com.dreamdisplays.core.api.DefaultEvents
import com.dreamdisplays.core.api.DefaultSession
import com.dreamdisplays.core.api.LiveDisplay
import com.dreamdisplays.core.services.keys.DisplayServices
import com.dreamdisplays.core.services.keys.MediaServices
import com.dreamdisplays.core.services.keys.PlatformServices
import com.dreamdisplays.core.services.keys.PlaybackServices
import com.dreamdisplays.core.services.keys.WatchPartyServices

/** Public [ApiBackend] backed by the internal [ServiceRegistry]; [budget] comes from the platform. */
class DefaultApiBackend(
    private val registry: ServiceRegistry,
    override val budget: DisplayBudget,
    private val addons: AddonManager,
) : ApiBackend {
    private val displayService: DisplayService get() = registry.get(DisplayServices.DISPLAY)
    private val playbackService: PlaybackService get() = registry.get(PlaybackServices.PLAYBACK)

    override fun registerAddon(addon: Any) = addons.register(addon)

    override val placements: DisplayPlacements get() = PlacementHub

    override fun display(id: DisplayId): Display? =
        displayService.getDisplay(id)?.let { LiveDisplay(id, displayService, playbackService) }

    override val displays: Displays get() = DefaultDisplays(displayService, playbackService, owner = null)
    override val events: Events get() = DefaultEvents(displayService, owner = null, displayId = null)
    override fun session(name: String): Session = DefaultSession(name, displayService, playbackService)
    override val watchParty: WatchPartyService get() = registry.get(WatchPartyServices.WATCH_PARTY)
    override val search: MediaSearchService get() = registry.get(MediaServices.SEARCH)
    override val resolvers: MediaResolverRegistry get() = registry.get(MediaServices.RESOLVER_REGISTRY)
    override val platform: Platform get() = registry.get(PlatformServices.PLATFORM)
}
