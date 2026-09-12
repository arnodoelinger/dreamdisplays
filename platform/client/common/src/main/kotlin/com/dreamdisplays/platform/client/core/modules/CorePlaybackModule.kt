package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.api.display.service.keys.DisplayServices
import com.dreamdisplays.api.playback.service.PlaybackPort
import com.dreamdisplays.api.playback.service.keys.PlaybackServices
import com.dreamdisplays.api.runtime.module.DreamDisplaysModule
import com.dreamdisplays.api.runtime.module.ModuleContext
import com.dreamdisplays.api.runtime.registry.service.get
import com.dreamdisplays.api.runtime.registry.service.register
import com.dreamdisplays.api.watchparty.service.WatchPartyPort
import com.dreamdisplays.api.watchparty.service.keys.WatchPartyServices
import com.dreamdisplays.core.services.DefaultPlaybackService
import com.dreamdisplays.core.services.DefaultWatchPartyService
import com.dreamdisplays.media.runtime.session.DefaultMediaSessionManager
import com.dreamdisplays.media.runtime.session.MediaSessionManager

/** Installs playback, media-session, and watch-party services backed by the core display ports. */
object CorePlaybackModule : DreamDisplaysModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplays:core_playback"

    /** Dependencies of this module. */
    override val dependencies: List<String> = listOf(CoreDisplayModule.id)

    /** Installs the playback service, media-session manager, and watch-party service. */
    override fun install(context: ModuleContext) {
        val services = context.services
        val playbackService = DefaultPlaybackService(services.get<PlaybackPort>())

        services.register(PlaybackServices.PLAYBACK, playbackService)
        services.register<MediaSessionManager>(
            DefaultMediaSessionManager(playbackService, services.get(DisplayServices.DISPLAY)),
        )
        services.register(WatchPartyServices.WATCH_PARTY, DefaultWatchPartyService(services.get<WatchPartyPort>()))
    }
}
