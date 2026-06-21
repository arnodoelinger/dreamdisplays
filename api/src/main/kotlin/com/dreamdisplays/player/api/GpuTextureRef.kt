package com.dreamdisplays.player.api

/**
 * Opaque, render-thread handle to a GPU texture owned by the platform layer.
 *
 * The media player never inspects it: it only forwards the handle to a [FrameUploader], whose
 * platform implementation casts it back to the concrete texture type. This keeps Minecraft's
 * `GpuTexture` out of the platform-agnostic player module.
 */
interface GpuTextureRef
