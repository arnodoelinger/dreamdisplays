package com.dreamdisplays.platform.server

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.server.listeners.NeoForgePlayerListener
import com.dreamdisplays.platform.server.listeners.NeoForgeProtectionListener
import com.dreamdisplays.platform.server.listeners.NeoForgeSelectionListener
import com.dreamdisplays.platform.server.managers.StorageManager
import com.dreamdisplays.platform.server.registrar.NeoForgeBareTokenArgumentType
import com.dreamdisplays.platform.server.registrar.NeoForgeCommandRegistrar
import com.dreamdisplays.platform.server.utils.net.NeoForgeNetworkingAdapter
import com.dreamdisplays.platform.server.utils.net.NeoForgeV2Networking
import com.dreamdisplays.platform.server.utils.net.VanillaNetworking
import com.dreamdisplays.platform.server.utils.net.VanillaServerPacketHandler
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.bus.api.IEventBus
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import org.slf4j.LoggerFactory

/**
 * `NeoForge`-specific implementation of [PaperServer]. See `FabricServer.kt` for the `Fabric`
 * mirror and `VanillaBootstrap.kt` for the storage / playback bring-up shared by both.
 */
@Suppress("UNUSED")
@NeoForgeOnly
@Mod(Initializer.MOD_ID)
class NeoForgeServer(modEventBus: IEventBus) {
    init {
        logger.info("Initializing server-side mod...")
        configInstance = VanillaConfig(FMLPaths.CONFIGDIR.get().resolve("dreamdisplays").toFile())
        VanillaServerState.config = configInstance
        VanillaServerState.serverVersion = serverVersion
        VanillaNetworking.adapter = NeoForgeNetworkingAdapter

        NeoForgeBareTokenArgumentType.register()

        modEventBus.addListener(::registerPayloads)
        NeoForge.EVENT_BUS.register(this)
        NeoForge.EVENT_BUS.register(NeoForgePlayerListener)
        NeoForge.EVENT_BUS.register(NeoForgeProtectionListener)
        NeoForge.EVENT_BUS.register(NeoForgeSelectionListener)
        NeoForge.EVENT_BUS.addListener(NeoForgeCommandRegistrar::register)

        logger.info("Server-side initialization complete.")
    }

    /** Registers all custom payload types for clientbound and serverbound play channels. */
    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(Initializer.MOD_ID).optional().versioned("1")
        NeoForgeV2Networking.registerReceivers(registrar)
        VanillaServerPacketHandler.registerReceivers(registrar)
    }

    /** Storage bring-up, display registration, and repeating tasks; covers dedicated + integrated servers alike. */
    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        val server = event.server
        val dataDir = server.getWorldPath(LevelResource.LEVEL_DATA_FILE)
            .parent.resolve("dreamdisplays").toFile().also { it.mkdirs() }

        VanillaServerState.server = server
        VanillaBootstrap.onServerStarted(server, dataDir)

        logger.info("Server started. Storage connected.")
    }

    /** Persists state and tears down resources. */
    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        logger.info("Server stopping. Saving displays...")
        VanillaBootstrap.onServerStopping()
    }

    companion object {
        /** Logger. */
        val logger = LoggerFactory.getLogger("DreamDisplays")

        /** The mod version string, read from the bundled, Gradle-templated `version.txt` resource. */
        val serverVersion: String? by lazy {
            runCatching {
                NeoForgeServer::class.java.classLoader
                    .getResourceAsStream("assets/dreamdisplays/version.txt")
                    ?.use { it.readBytes().decodeToString().trim() }
            }.getOrNull()
        }

        private lateinit var configInstance: VanillaConfig

        val config: VanillaConfig; get() = configInstance
        val server: MinecraftServer?; get() = VanillaServerState.server
        val storage: StorageManager?; get() = VanillaServerState.storage
    }
}
