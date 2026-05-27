package com.dreamdisplays.server.meta

import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.server.utils.PlatformUtil.isFolia
import org.bukkit.plugin.Plugin
import org.jspecify.annotations.NullMarked
import java.lang.reflect.Proxy

/**
 * `Paper` plugin scheduler. Provides methods for running tasks asynchronously, synchronously, or with a delay, transparently
 * dispatching to `Folia`'s region-aware schedulers when present.
 */
@PaperOnly @NullMarked object Scheduler {

    private lateinit var plugin: Plugin

    /** Binds the scheduler to its owning [plugin]. Must be called before any scheduling helper. */
    fun init(plugin: Plugin) {
        this.plugin = plugin
    }

    /** Runs [task] off the main thread, transparently dispatching to `Folia` when present. */
    fun runAsync(task: Runnable) {
        if (isFolia) foliaRunAsync(task)
        else plugin.server.scheduler.runTaskAsynchronously(plugin, task)
    }

    /** Runs [task] on the main / global region thread, transparently dispatching to `Folia` when present. */
    fun runSync(task: Runnable) {
        if (isFolia) foliaRunSync(task)
        else plugin.server.scheduler.runTask(plugin, task)
    }

    /** Runs [task] after [ticks] ticks on the main/global region thread (`Folia`-aware). */
    fun runLater(ticks: Long, task: Runnable) {
        if (isFolia) foliaRunGlobalLater(ticks, task)
        else plugin.server.scheduler.runTaskLater(plugin, task, ticks)
    }

    /** `Folia` path for [runAsync]: reflects into the async scheduler and falls back to inline run. */
    private fun foliaRunAsync(task: Runnable) {
        runCatching {
            val scheduler = Class.forName("org.bukkit.Bukkit")
                .getMethod("getAsyncScheduler")
                .invoke(null)
            scheduler.javaClass
                .getMethod("runNow", Plugin::class.java, consumerClass)
                .invoke(scheduler, plugin, consumer(task))
        }.getOrElse { task.run() }
    }

    /** Folia path for [runSync]: reflects into the global region scheduler and falls back inline. */
    private fun foliaRunSync(task: Runnable) {
        runCatching {
            val scheduler = Class.forName("org.bukkit.Bukkit")
                .getMethod("getGlobalRegionScheduler")
                .invoke(null)
            scheduler.javaClass
                .getMethod("run", Plugin::class.java, consumerClass)
                .invoke(scheduler, plugin, consumer(task))
        }.getOrElse { task.run() }
    }

    /** `Folia` path for [runLater]: schedules via the global region scheduler with a tick delay. */
    private fun foliaRunGlobalLater(ticks: Long, task: Runnable) {
        runCatching {
            val scheduler = Class.forName("org.bukkit.Bukkit")
                .getMethod("getGlobalRegionScheduler")
                .invoke(null)
            scheduler.javaClass
                .getMethod("runDelayed", Plugin::class.java, consumerClass, Long::class.javaPrimitiveType)
                .invoke(scheduler, plugin, consumer(task), ticks)
        }.getOrElse { task.run() }
    }

    private val consumerClass = Class.forName("java.util.function.Consumer")

    /** Wraps [task] in a `java.util.function.Consumer` proxy required by `Folia`'s scheduler API. */
    private fun consumer(task: Runnable): Any =
        Proxy.newProxyInstance(
            consumerClass.classLoader,
            arrayOf(consumerClass)
        ) { _, _, _ ->
            task.run()
            null
        }
}
