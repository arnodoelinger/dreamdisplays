package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.platform.server.meta.ServerCoroutines
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.server.MinecraftServer
import kotlin.time.Duration.Companion.milliseconds

/** Simple scheduler helper for `Fabric` / `NeoForge` server-side delayed tasks. */
object VanillaServerScheduler {
    fun runLater(server: MinecraftServer, delayTicks: Long, task: Runnable) {
        if (delayTicks <= 0L) {
            server.execute(task)
            return
        }
        ServerCoroutines.io.launch {
            delay((delayTicks * 50L).milliseconds)
            server.execute(task)
        }
    }
}
