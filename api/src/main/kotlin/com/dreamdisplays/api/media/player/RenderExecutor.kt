package com.dreamdisplays.api.media.player

import com.dreamdisplays.api.Unstable

/**
 * Runs a task on the platform's render thread (e.g. `Minecraft.getInstance().execute`).
 *
 * @since 1.8.x
 */
@Unstable
fun interface RenderExecutor {
    fun execute(task: () -> Unit)
}
