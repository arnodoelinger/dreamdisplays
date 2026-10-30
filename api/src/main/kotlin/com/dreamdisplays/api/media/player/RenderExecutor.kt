package com.dreamdisplays.api.media.player

/**
 * Runs a task on the platform's render thread (e.g. `Minecraft.getInstance().execute`).
 *
 * @since 1.8.x
 */
fun interface RenderExecutor {
    fun execute(task: () -> Unit)
}
