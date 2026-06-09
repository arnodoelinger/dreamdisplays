package com.dreamdisplays.platform

import com.dreamdisplays.Initializer
import com.dreamdisplays.platform.api.Platform
import com.dreamdisplays.platform.api.PlatformLogger
import com.dreamdisplays.platform.api.PlatformNetworking
import com.dreamdisplays.platform.api.PlatformPaths
import com.dreamdisplays.platform.api.PlatformScheduler
import com.dreamdisplays.platform.api.PlatformSide
import com.dreamdisplays.utils.GeneralUtil
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path

/** NeoForge client [Platform]. Versions come from [ModList]; paths from [FMLPaths]. */
object NeoForgePlatform : Platform {

    override val id: String = "neoforge"
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
    override val networking: PlatformNetworking = PendingProtocolNetworking

    /** Mirrors the mod's existing layout: `config/dreamdisplays` for config and caches, `libs` for binaries. */
    override val paths: PlatformPaths = object : PlatformPaths {
        override val configDir: Path get() = FMLPaths.CONFIGDIR.get().resolve(Initializer.MOD_ID)
        override val cacheDir: Path get() = configDir.resolve("yt-cache")
        override val dataDir: Path get() = FMLPaths.GAMEDIR.get().resolve("libs")
        override val modDir: Path get() = FMLPaths.MODSDIR.get()
    }

    override val isDevEnvironment: Boolean
        //? if >=26 {
        get() = !FMLEnvironment.isProduction()
        //?} else
        /*get() = !FMLEnvironment.production*/
}
