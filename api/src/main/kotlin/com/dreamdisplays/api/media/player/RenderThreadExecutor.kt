package com.dreamdisplays.api.media.player

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Runs a task on the platform's render thread. The Minecraft client backs this with
 * `Minecraft.getInstance().execute { ... }`.
 */
@DreamDisplaysUnstableApi fun interface RenderThreadExecutor {
    fun execute(task: () -> Unit)
}
