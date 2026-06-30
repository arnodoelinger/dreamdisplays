package com.dreamdisplays.platform.client.displays

import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.DisplayBounds
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplayRuntimeState
import com.dreamdisplays.api.display.model.DisplaySettings as ApiDisplaySettings
import com.dreamdisplays.api.storage.FullDisplayData

/**
 * Mapping between the internal mutable [DisplayScreen] and the immutable public API / persistence types
 * ([Display], [DisplayRuntimeState], [FullDisplayData]). Kept separate from [DisplayRegistry] so the manager
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
        muted = muted, paused = isPaused, renderDistance = renderDistance.coerceIn(32, 192),
        urlOverride = null, audioTrackName = lang,
    ),
    url = videoUrl,
    state = toRuntimeState(),
    mode = effectiveMode,
    watchParty = watchParty,
)

/** Derives the current [DisplayRuntimeState] from the screen's media / error / playback state. */
internal fun DisplayScreen.toRuntimeState(): DisplayRuntimeState = when {
    mediaError != null -> DisplayRuntimeState.Failed(mediaError!!)
    videoUrl.isNullOrEmpty() -> DisplayRuntimeState.Idle
    !isVideoStarted -> DisplayRuntimeState.Preparing
    isPaused -> DisplayRuntimeState.Paused(uuid.toString(), currentTimeNanos / 1_000_000L)
    else -> DisplayRuntimeState.Playing(
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
    renderDistance = renderDistance,
    currentTimeNanos = currentTimeNanos,
    rotation = rotation.quarterTurns,
    qualityCap = qualityCap,
)
