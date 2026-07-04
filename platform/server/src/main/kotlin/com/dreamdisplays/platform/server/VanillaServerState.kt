package com.dreamdisplays.platform.server

import com.dreamdisplays.platform.server.managers.StorageManager
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

/**
 * Shared runtime state for the vanilla Minecraft API loaders (`Fabric` and `NeoForge`).
 *
 * Both loaders populate this at startup instead of keeping their own copies, so shared code (managers,
 * packet handling, playback transport) can read [config] / [storage] / [server] directly without
 * reflection, regardless of which of the two loaders is actually running.
 */
object VanillaServerState {
    /** Logger shared by both vanilla loaders. */
    val logger = LoggerFactory.getLogger("DreamDisplays")

    /** Set once during `onLoad` / `init`, before [server] or [storage] exist. */
    lateinit var config: VanillaConfig

    /**
     * The mod version string, resolved by whichever loader is running (`Fabric` via its mod
     * container's metadata, `NeoForge` via a bundled Gradle-templated resource) — the resolution
     * mechanism itself is genuinely loader-specific, but readers don't need to care which.
     */
    var serverVersion: String? = null

    /** Set once the underlying server starts. */
    var server: MinecraftServer? = null

    /** Set once storage connects at server start; cleared on stop. */
    var storage: StorageManager? = null
}
