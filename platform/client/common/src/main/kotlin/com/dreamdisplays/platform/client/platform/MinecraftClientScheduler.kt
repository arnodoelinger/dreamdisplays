package com.dreamdisplays.platform.client.platform

import com.dreamdisplays.platform.api.PlatformScheduler
import com.dreamdisplays.platform.api.TaskHandle
import net.minecraft.client.Minecraft
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Client [PlatformScheduler]. Main-thread work goes through [Minecraft]'s task queue; async and
 * timed work runs on a shared daemon [ScheduledExecutorService], with tick counts converted at the
 * fixed 50 ms/tick rate. Timed tasks hop back to the main thread before running, matching the
 * "ticks" vocabulary of the contract.
 */
object MinecraftClientScheduler : PlatformScheduler {

    private const val MS_PER_TICK = 50L

    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(1) { r ->
        Thread(r, "DD-Scheduler").apply { isDaemon = true }
    }

    override val isOnMainThread: Boolean
        get() = Minecraft.getInstance().isSameThread

    /** Runs [task] on the render/main thread; immediately if already there. */
    override fun runOnMainThread(task: () -> Unit) {
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) task() else mc.execute(task)
    }

    /** Runs [task] on the shared background pool. */
    override fun runAsync(task: () -> Unit): TaskHandle {
        val future = executor.submit(task)
        return TaskHandle { future.cancel(false) }
    }

    /** Runs [task] on the main thread every [intervalTicks] ticks (50 ms each). */
    override fun runRepeating(intervalTicks: Long, task: () -> Unit): TaskHandle {
        val intervalMs = intervalTicks.coerceAtLeast(1L) * MS_PER_TICK
        val future = executor.scheduleAtFixedRate(
            { runOnMainThread(task) }, intervalMs, intervalMs, TimeUnit.MILLISECONDS,
        )
        return TaskHandle { future.cancel(false) }
    }

    /** Runs [task] on the main thread once, after [delayTicks] ticks (50 ms each). */
    override fun runDelayed(delayTicks: Long, task: () -> Unit): TaskHandle {
        val delayMs = delayTicks.coerceAtLeast(0L) * MS_PER_TICK
        val future = executor.schedule({ runOnMainThread(task) }, delayMs, TimeUnit.MILLISECONDS)
        return TaskHandle { future.cancel(false) }
    }
}
