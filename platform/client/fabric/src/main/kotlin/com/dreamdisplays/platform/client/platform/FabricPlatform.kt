package com.dreamdisplays.platform.client.platform

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.api.platform.Platform
import com.dreamdisplays.api.platform.PlatformId
import com.dreamdisplays.api.platform.PlatformLogger
import com.dreamdisplays.api.platform.PlatformPaths
import com.dreamdisplays.api.platform.PlatformScheduler
import com.dreamdisplays.api.platform.PlatformSide
import com.dreamdisplays.util.GeneralUtil
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

/** Fabric client [Platform]. Versions and paths come from [FabricLoader] metadata. */
object FabricPlatform : Platform {

    override val platformId: PlatformId = PlatformId.FABRIC
    override val id: String get() = platformId.wire
    override val side: PlatformSide = PlatformSide.CLIENT

    @Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    override val minecraftVersion: String by lazy {
        FabricLoader.getInstance().getModContainer("minecraft")
            .map { it.metadata.version.friendlyString }
            .orElse("unknown")
    }

    @Suppress("TYPE_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
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
