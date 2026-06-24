package com.dreamdisplays.api.platform

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Loader-neutral facts and services supplied by the active Minecraft platform adapter.
 *
 * @since 1.8.0
 */
@DreamDisplaysUnstableApi
interface Platform {
    /** Stable platform id, e.g. `fabric`, `neoforge`, or `paper`. */
    val id: String

    /** Logical side this platform instance runs on. */
    val side: PlatformSide

    /** Minecraft version reported by the loader / server runtime. */
    val minecraftVersion: String

    /** Dream Displays mod or plugin version. */
    val modVersion: String

    /** Scheduler used to marshal work onto the platform's main thread or background executors. */
    val scheduler: PlatformScheduler

    /** Root logger for platform-neutral modules. */
    val logger: PlatformLogger

    /** Platform-specific config, cache, data, and mod paths. */
    val paths: PlatformPaths

    /** True when running in a development environment. */
    val isDevEnvironment: Boolean
}
