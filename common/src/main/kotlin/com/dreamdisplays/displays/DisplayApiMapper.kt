package com.dreamdisplays.displays

import com.dreamdisplays.api.Display
import com.dreamdisplays.api.DisplayBounds
import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.api.DisplayRuntimeState
import com.dreamdisplays.api.DisplaySettings as ApiDisplaySettings
import com.dreamdisplays.displays.store.FullDisplayData

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
        muted = muted, paused = isPaused, renderDistance = renderDistance,
        syncEnabled = isSync, urlOverride = null, audioTrackName = lang,
    ),
    url = videoUrl,
    state = toRuntimeState(),
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
    uuid, pos.x, pos.y, pos.z, facing, width, height,
    videoUrl ?: "", lang ?: "", volume, quality.serialize(), brightness,
    muted, isSync, ownerUuid, renderDistance, currentTimeNanos,
)
