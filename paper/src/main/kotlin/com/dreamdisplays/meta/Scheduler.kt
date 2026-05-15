package com.dreamdisplays.meta

import com.dreamdisplays.utils.PlatformUtil.isFolia
import org.bukkit.plugin.Plugin
import org.jspecify.annotations.NullMarked
import java.lang.reflect.Proxy

@NullMarked
object Scheduler {

    private lateinit var plugin: Plugin

    fun init(plugin: Plugin) {
        this.plugin = plugin
    }

    fun runAsync(task: Runnable) {
        if (isFolia) foliaRunAsync(task)
        else plugin.server.scheduler.runTaskAsynchronously(plugin, task)
    }

    fun runSync(task: Runnable) {
        if (isFolia) foliaRunSync(task)
        else plugin.server.scheduler.runTask(plugin, task)
    }

    fun runLater(ticks: Long, task: Runnable) {
        if (isFolia) foliaRunGlobalLater(ticks, task)
        else plugin.server.scheduler.runTaskLater(plugin, task, ticks)
    }

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

    private fun consumer(task: Runnable): Any =
        Proxy.newProxyInstance(
            consumerClass.classLoader,
            arrayOf(consumerClass)
        ) { _, _, _ ->
            task.run()
            null
        }
}
