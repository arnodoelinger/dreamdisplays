package com.dreamdisplays.core.services

import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.property.DisplayState
import com.dreamdisplays.api.display.model.settings.DisplaySettings
import com.dreamdisplays.api.display.service.DisplayLookup
import com.dreamdisplays.api.display.service.DisplayMutationPort
import com.dreamdisplays.api.display.service.DisplayService
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.service.PlaybackPort
import com.dreamdisplays.api.playback.service.PlaybackService
import com.dreamdisplays.api.watchparty.service.WatchPartyPort
import com.dreamdisplays.api.watchparty.service.WatchPartyService
import com.dreamdisplays.api.watchparty.model.WatchPartySession
import kotlin.time.Duration

/**
 * Default core implementation of [DisplayService].
 */
class DefaultDisplayService(
    private val lookup: DisplayLookup,
    private val mutations: DisplayMutationPort,
) : DisplayService {
    override fun getDisplay(id: DisplayId): Display? = lookup.getDisplay(id)
    override fun listDisplays(): List<Display> = lookup.listDisplays()
    override fun updateSettings(id: DisplayId, settings: DisplaySettings) = mutations.updateSettings(id, settings)
    override fun setUrl(id: DisplayId, url: String?, lang: String?) = mutations.setUrl(id, url, lang)
    override fun setAccess(id: DisplayId, access: DisplayAccess) = mutations.setAccess(id, access)
    override fun delete(id: DisplayId) = mutations.delete(id)
    override fun report(id: DisplayId) = mutations.report(id)
    override fun on(listener: (DisplayEvent) -> Unit): AutoCloseable = lookup.onDisplayEvent(listener)
}

/** Default core implementation of [PlaybackService]. */
class DefaultPlaybackService(
    private val playback: PlaybackPort,
) : PlaybackService {
    override fun play(displayId: DisplayId) = playback.play(displayId)
    override fun pause(displayId: DisplayId) = playback.pause(displayId)
    override fun stop(displayId: DisplayId) = playback.stop(displayId)
    override fun seek(displayId: DisplayId, position: Duration) = playback.seek(displayId, position)
    override fun seekRelative(displayId: DisplayId, delta: Duration) = playback.seekRelative(displayId, delta)
    override fun setVolume(displayId: DisplayId, volume: Float) = playback.setVolume(displayId, volume)
    override fun setQuality(displayId: DisplayId, quality: VideoQuality) = playback.setQuality(displayId, quality)
    override fun setAudioTrack(displayId: DisplayId, trackUrl: String) = playback.setAudioTrack(displayId, trackUrl)
    override fun setSubtitleTrack(displayId: DisplayId, lang: String?) = playback.setSubtitleTrack(displayId, lang)
    override fun setBrightness(displayId: DisplayId, brightness: Float) = playback.setBrightness(displayId, brightness)
    override fun mute(displayId: DisplayId, muted: Boolean) = playback.mute(displayId, muted)
    override fun getState(displayId: DisplayId): DisplayState = playback.getState(displayId)
    override fun restart(displayId: DisplayId) = playback.restart(displayId)
    override fun getMode(displayId: DisplayId): PlaybackMode = playback.getMode(displayId)
    override fun setMode(displayId: DisplayId, mode: PlaybackMode) = playback.setMode(displayId, mode)
    override fun retry(displayId: DisplayId) = playback.retry(displayId)
}

/** Default core implementation of [WatchPartyService]. */
class DefaultWatchPartyService(
    private val watchParty: WatchPartyPort,
) : WatchPartyService {
    override fun start(displayId: DisplayId, url: String?): Boolean = watchParty.start(displayId, url)
    override fun setReady(displayId: DisplayId, ready: Boolean) = watchParty.setReady(displayId, ready)
    override fun begin(displayId: DisplayId) = watchParty.begin(displayId)
    override fun end(displayId: DisplayId) = watchParty.end(displayId)
    override fun restart(displayId: DisplayId) = watchParty.restartSession(displayId)
    override fun close(displayId: DisplayId) = watchParty.close(displayId)
    override fun getSession(displayId: DisplayId): WatchPartySession? = watchParty.getSession(displayId)
}
