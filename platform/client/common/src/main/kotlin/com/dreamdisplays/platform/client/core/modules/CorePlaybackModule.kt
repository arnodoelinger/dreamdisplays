package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.api.display.service.DisplayServices
import com.dreamdisplays.api.playback.PlaybackPort
import com.dreamdisplays.api.playback.PlaybackServices
import com.dreamdisplays.api.runtime.DreamDisplaysModule
import com.dreamdisplays.api.runtime.ModuleContext
import com.dreamdisplays.api.runtime.get
import com.dreamdisplays.api.runtime.register
import com.dreamdisplays.api.watchparty.WatchPartyPort
import com.dreamdisplays.api.watchparty.WatchPartyServices
import com.dreamdisplays.core.playback.DefaultPlaybackService
import com.dreamdisplays.core.watchparty.DefaultWatchPartyService
import com.dreamdisplays.media.runtime.DefaultMediaSessionManager
import com.dreamdisplays.media.runtime.MediaSessionManager

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
