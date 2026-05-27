package com.dreamdisplays.server.scheduler

import io.github.arsmotorin.ofrat.PaperOnly

import org.bukkit.plugin.Plugin
import org.jspecify.annotations.NullMarked
import java.lang.reflect.Proxy

/**
 * A `Folia` implementation of the `AdapterScheduler` for scheduling asynchronous tasks.
 */
@PaperOnly @NullMarked object FoliaScheduler : AdapterScheduler {
    private const val TICK_MILLIS = 50L
    private val asyncScheduler = Class.forName("org.bukkit.Bukkit")
        .getMethod("getAsyncScheduler").invoke(null)

    private val pluginClass = Class.forName("org.bukkit.plugin.Plugin")
    private val consumerClass = Class.forName("java.util.function.Consumer")
    private val timeUnitClass = Class.forName("java.util.concurrent.TimeUnit")
    private val milliseconds = timeUnitClass.getDeclaredField("MILLISECONDS").get(null)

    /**
     * Schedules a repeating async task via `Folia`'s async scheduler using reflection,
     * so the plugin still compiles against vanilla `Paper` which lacks the API.
     */
    override fun runRepeatingAsync(
        plugin: Plugin,
        delayTicks: Long,
        intervalTicks: Long,
        task: Runnable,
    ) {
        val delayMillis = delayTicks.coerceAtLeast(0L) * TICK_MILLIS
        val intervalMillis = intervalTicks.coerceAtLeast(1L) * TICK_MILLIS

        val consumer = Proxy.newProxyInstance(
            consumerClass.classLoader,
            arrayOf(consumerClass)
        ) { _, method, _ ->
            if (method?.name == "accept") task.run()
            null
        }

        asyncScheduler.javaClass.getMethod(
            "runAtFixedRate",
            pluginClass,
            consumerClass,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            timeUnitClass
        ).invoke(
            asyncScheduler,
            plugin,
            consumer,
            delayMillis,
            intervalMillis,
            milliseconds
        )
    }
}
