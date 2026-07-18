package com.dreamdisplays.platform.client.platform

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.api.platform.Platform
import com.dreamdisplays.api.platform.PlatformId
import com.dreamdisplays.api.platform.PlatformLogger
import com.dreamdisplays.api.platform.PlatformPaths
import com.dreamdisplays.api.platform.PlatformScheduler
import com.dreamdisplays.api.platform.PlatformSide
import com.dreamdisplays.util.GeneralUtil
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path

/** NeoForge client [Platform]. Versions come from [ModList]; paths from [FMLPaths]. */
object NeoForgePlatform : Platform {

    override val platformId: PlatformId = PlatformId.NEOFORGE
    override val id: String get() = platformId.wire
    override val side: PlatformSide = PlatformSide.CLIENT

    override val minecraftVersion: String by lazy {
        ModList.get().getModContainerById("minecraft")
            .map { it.modInfo.version.toString() }
            .orElse("unknown")
    }

    override val modVersion: String by lazy {
        ModList.get().getModContainerById(Initializer.MOD_ID)
            .map { it.modInfo.version.toString() }
            .orElse(GeneralUtil.getModVersion())
    }

    override val scheduler: PlatformScheduler = MinecraftClientScheduler
    override val logger: PlatformLogger = Slf4jPlatformLogger("DreamDisplays")

    /** Mirrors the mod's existing layout: `config/dreamdisplays` for config and caches, `libs` for binaries. */
    override val paths: PlatformPaths = object : PlatformPaths {
        override val configDir: Path get() = FMLPaths.CONFIGDIR.get().resolve(Initializer.MOD_ID)
        override val cacheDir: Path get() = configDir.resolve("yt-cache")
        override val dataDir: Path get() = FMLPaths.GAMEDIR.get().resolve("libs")
        override val modDir: Path get() = FMLPaths.MODSDIR.get()
    }

    override val isDevEnvironment: Boolean
        get() =
            //? if >=1.21.11 {
            !FMLEnvironment.isProduction()
    //?} else
    /*!FMLEnvironment.production*/
}
