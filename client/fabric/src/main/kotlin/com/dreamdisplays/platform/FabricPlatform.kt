package com.dreamdisplays.platform

import com.dreamdisplays.Initializer
import com.dreamdisplays.platform.api.Platform
import com.dreamdisplays.platform.api.PlatformLogger
import com.dreamdisplays.platform.api.PlatformPaths
import com.dreamdisplays.platform.api.PlatformScheduler
import com.dreamdisplays.platform.api.PlatformSide
import com.dreamdisplays.utils.GeneralUtil
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

/** Fabric client [Platform]. Versions and paths come from [FabricLoader] metadata. */
object FabricPlatform : Platform {

    override val id: String = "fabric"
    override val side: PlatformSide = PlatformSide.CLIENT

    override val minecraftVersion: String by lazy {
        FabricLoader.getInstance().getModContainer("minecraft")
            .map { it.metadata.version.friendlyString }
            .orElse("unknown")
    }

    override val modVersion: String by lazy {
        FabricLoader.getInstance().getModContainer(Initializer.MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse(GeneralUtil.getModVersion())
    }

    override val scheduler: PlatformScheduler = MinecraftClientScheduler
    override val logger: PlatformLogger = Slf4jPlatformLogger("DreamDisplays")

    /** Mirrors the mod's existing layout: `config/dreamdisplays` for config and caches, `libs` for binaries. */
    override val paths: PlatformPaths = object : PlatformPaths {
        override val configDir: Path get() = FabricLoader.getInstance().configDir.resolve(Initializer.MOD_ID)
        override val cacheDir: Path get() = configDir.resolve("yt-cache")
        override val dataDir: Path get() = FabricLoader.getInstance().gameDir.resolve("libs")
        override val modDir: Path get() = FabricLoader.getInstance().gameDir.resolve("mods")
    }

    override val isDevEnvironment: Boolean
        get() = FabricLoader.getInstance().isDevelopmentEnvironment
}
