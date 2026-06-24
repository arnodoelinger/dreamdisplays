package com.dreamdisplays.api.media.player

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Runs a task on the platform's render thread. The Minecraft client backs this with
 * `Minecraft.getInstance().execute { ... }`.
 *
 * @since 1.8.0
 */
@DreamDisplaysUnstableApi
fun interface RenderThreadExecutor {
    fun execute(task: () -> Unit)
}
