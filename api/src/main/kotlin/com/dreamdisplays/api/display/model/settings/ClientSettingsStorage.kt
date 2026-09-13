package com.dreamdisplays.api.display.model.settings

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.model.VideoQuality
import java.util.*

/**
 * Persistence for per-display, client-local [ClientDisplaySettings].
 *
 * @since 1.8.x
 */
@Unstable
interface ClientSettingsStorage {
    /** Loads all client display settings from disk into memory, replacing any current state. */
    fun load()

    /** Persists the in-memory settings to disk. */
    fun save()

    /** Returns the settings for [displayUuid], creating a default entry if absent. */
    fun getSettings(
        displayUuid: UUID,
        defaultVolume: Float = ClientDisplaySettings.DEFAULT_VOLUME,
    ): ClientDisplaySettings

    /** Updates all playback settings for [displayUuid] and immediately persists them to disk. */
    fun updateSettings(
        displayUuid: UUID,
        volume: Float,
        quality: VideoQuality,
        brightness: Float,
        muted: Boolean,
        paused: Boolean,
    )

    /** Sets the client-side URL and language override for [displayUuid] and saves. */
    fun setUrlOverride(displayUuid: UUID, url: String?, lang: String?)

    /** Sets the viewer-picked audio track language for [displayUuid] and saves. */
    fun setAudioTrackLang(displayUuid: UUID, lang: String?)

    /** Sets the viewer's subtitle track language for [displayUuid] (null disables subtitles) and saves. */
    fun setSubtitleTrackLang(displayUuid: UUID, lang: String?)

    /** Sets the last known playback position for [displayUuid] and saves. */
    fun setSavedTimeNanos(displayUuid: UUID, nanos: Long)

    /** Sets whether [displayUuid] is pinned to a Picture-in-Picture overlay and saves. */
    fun setPipOpen(displayUuid: UUID, open: Boolean)

    /** Sets whether the 3D acoustics engine applies to [displayUuid] and saves. */
    fun setAcousticsEnabled(displayUuid: UUID, enabled: Boolean)

    /** Removes the settings for [displayUuid], persisting only if an entry existed. Returns whether anything was removed. */
    fun remove(displayUuid: UUID): Boolean
}
