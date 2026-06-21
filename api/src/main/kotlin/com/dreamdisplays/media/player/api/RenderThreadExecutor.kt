package com.dreamdisplays.media.player.api

/**
 * Runs a task on the platform's render thread. The Minecraft client backs this with
 * `Minecraft.getInstance().execute { ... }`.
 */
fun interface RenderThreadExecutor {
    fun execute(task: () -> Unit)
}
