package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.property.DisplayBounds
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.property.DisplayState
import com.dreamdisplays.api.storage.model.FullDisplayData
import com.dreamdisplays.api.display.model.settings.DisplaySettings as ApiDisplaySettings

/**
 * Mapping between the internal mutable [DisplayScreen] and the immutable public API / persistence types
 * ([Display], [DisplayState], [FullDisplayData]). Kept separate from [DisplayRegistry] so the manager
 * stays focused on registry and event concerns.
 */

/** Projects this screen into the immutable public [Display] snapshot, including its current runtime state. */
internal fun DisplayScreen.toDisplay(): Display = Display(
    id = DisplayId(uuid),
    bounds = DisplayBounds(
        x = pos.x.toDouble(), y = pos.y.toDouble(), z = pos.z.toDouble(),
        width = width, height = height,
        facing = facing,
    ),
    settings = ApiDisplaySettings(
        volume = volume, quality = quality, brightness = brightness,
        muted = muted, paused = isPaused,
        urlOverride = null, audioTrackName = lang,
    ),
    url = videoUrl,
    state = toRuntimeState(),
    mode = effectiveMode,
    watchParty = watchParty,
)

/** Derives the current [DisplayState] from the screen's media / error / playback state. */
internal fun DisplayScreen.toRuntimeState(): DisplayState = when {
    mediaError != null -> DisplayState.Failed(mediaError!!)
    videoUrl.isNullOrEmpty() -> DisplayState.Idle
    !isVideoStarted -> DisplayState.Preparing
    isPaused -> DisplayState.Paused(uuid.toString(), currentTimeNanos / 1_000_000L)
    else -> DisplayState.Playing(
        sessionId = uuid.toString(),
        positionMs = currentTimeNanos / 1_000_000L,
        durationMs = mediaPlayerDurationNanos.takeIf { it > 0L }?.let { it / 1_000_000L },
    )
}

/** Captures the full persistable snapshot of this screen for the server display registry. */
internal fun DisplayScreen.toFullDisplayData(): FullDisplayData = FullDisplayData(
    uuid = uuid,
    x = pos.x,
    y = pos.y,
    z = pos.z,
    facing = facing,
    width = width,
    height = height,
    videoUrl = videoUrl ?: "",
    lang = lang ?: "",
    volume = volume,
    quality = quality.serialize(),
    brightness = brightness,
    muted = muted,
    mode = mode,
    ownerUuid = ownerUuid,
    currentTimeNanos = currentTimeNanos,
    rotation = rotation.quarterTurns,
    qualityCap = qualityCap,
    dimensionKey = dimensionKey,
)
