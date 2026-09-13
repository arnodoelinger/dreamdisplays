package com.dreamdisplays.api.platform.identity

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.platform.capability.PlatformLogger
import com.dreamdisplays.api.platform.capability.PlatformPaths
import com.dreamdisplays.api.platform.capability.PlatformScheduler

/**
 * Loader-neutral facts and services supplied by the active Minecraft platform adapter.
 *
 * @since 1.8.x
 */
@Unstable
interface Platform {
    /** Stable platform id, e.g. `fabric`, `neoforge`, or `paper`. */
    val id: String

    /** Typed platform id. [id] remains the string form for compatibility. */
    val platformId: PlatformId get() = PlatformId.fromWire(id)

    /** Logical side this platform instance runs on. */
    val side: PlatformSide

    /** Minecraft version reported by the loader / server runtime. */
    val minecraftVersion: String

    /** `Dream Displays` mod or plugin version. */
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
