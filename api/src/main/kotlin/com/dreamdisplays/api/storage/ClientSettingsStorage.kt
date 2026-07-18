package com.dreamdisplays.api.storage

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.media.VideoQuality
import java.util.*

/**
 * Persistence for per-display, client-local [ClientDisplaySettings].
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
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

    /** Sets the last known playback position for [displayUuid] and saves. */
    fun setSavedTimeNanos(displayUuid: UUID, nanos: Long)

    /** Sets the viewer-chosen render distance (in blocks) for [displayUuid] and saves. */
    fun setRenderDistance(displayUuid: UUID, blocks: Int)

    /** Sets whether [displayUuid] is pinned to a Picture-in-Picture overlay and saves. */
    fun setPipOpen(displayUuid: UUID, open: Boolean)

    /** Sets whether the 3D acoustics engine applies to [displayUuid] and saves. */
    fun setAcousticsEnabled(displayUuid: UUID, enabled: Boolean)

    /** Removes the settings for [displayUuid], persisting only if an entry existed. Returns whether anything was removed. */
    fun remove(displayUuid: UUID): Boolean
}
