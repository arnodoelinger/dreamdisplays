package com.dreamdisplays.platform.server

import com.dreamdisplays.platform.client.net.Packets
import com.dreamdisplays.platform.client.net.V2Payload
import com.dreamdisplays.platform.server.listeners.FabricPlayerListener
import com.dreamdisplays.platform.server.listeners.FabricProtectionListener
import com.dreamdisplays.platform.server.listeners.FabricSelectionListener
import com.dreamdisplays.platform.server.managers.StorageManager
import com.dreamdisplays.platform.server.registrar.FabricBareTokenArgumentType
import com.dreamdisplays.platform.server.registrar.FabricCommandRegistrar
import com.dreamdisplays.platform.server.storage.StorageBackend
import com.dreamdisplays.platform.server.utils.net.FabricNetworkingAdapter
import com.dreamdisplays.platform.server.utils.net.FabricV2Networking
import com.dreamdisplays.platform.server.utils.net.VanillaNetworking
import com.dreamdisplays.platform.server.utils.net.VanillaServerPacketHandler
import io.github.arnodoelinger.platformweaver.FabricOnly
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.io.File

/**
 * `Fabric`-specific implementation of [PaperServer]. See `NeoForgeServerMod.kt` for the `NeoForge`
 * mirror and `VanillaBootstrap.kt` for the storage / playback bring-up shared by both.
 */
@Suppress("UNUSED")
@FabricOnly
class Server : ModInitializer {
    /**
     * Initializes the server-side mod. It registers all necessary event listeners, packet handlers, and commands.
     * Also sets up repeating tasks for display updates and update checking.
     *
     * This method is called by the `Fabric` loader when the mod is loaded.
     */
    override fun onInitialize() {
        logger.info("Initializing server-side mod...")

        configInstance = VanillaConfig(FabricLoader.getInstance().configDir.resolve("dreamdisplays").toFile())
        VanillaServerState.config = configInstance
        VanillaServerState.serverVersion = serverVersion
        VanillaNetworking.adapter = FabricNetworkingAdapter

        FabricBareTokenArgumentType.register()
        registerPayloadTypes()

        VanillaServerPacketHandler.registerReceivers()
        FabricV2Networking.registerReceivers()
        FabricCommandRegistrar.register()
        FabricPlayerListener.register()
        FabricProtectionListener.register()
        FabricSelectionListener.register()

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            VanillaServerState.server = server
            val dataDir = server.getWorldPath(LevelResource.LEVEL_DATA_FILE).parent
                .resolve("dreamdisplays").toFile().also { it.mkdirs() }
            if (StorageBackend.fromConfig(configInstance.storage.type) == StorageBackend.SQLITE) {
                migrateGlobalDb(dataDir)
            }
            VanillaBootstrap.onServerStarted(server, dataDir)
            logger.info("Server started. Storage connected.")
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            logger.info("Server stopping. Saving displays...")
            VanillaBootstrap.onServerStopping()
        }

        logger.info("Server-side initialization complete.")
    }

    /** Registers all custom payload types for clientbound and serverbound play channels. */
    private fun registerPayloadTypes() {
        runCatching {
            payloadRegistry("clientboundPlay", "playS2C").let { clientbound ->
                registerPayload(clientbound, V2Payload.TYPE, V2Payload.CODEC)
                registerPayload(clientbound, Packets.Info.PACKET_ID, Packets.Info.PACKET_CODEC)
                registerPayload(clientbound, Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC)
                registerPayload(clientbound, Packets.Premium.PACKET_ID, Packets.Premium.PACKET_CODEC)
                registerPayload(clientbound, Packets.IsAdmin.PACKET_ID, Packets.IsAdmin.PACKET_CODEC)
                registerPayload(clientbound, Packets.Delete.PACKET_ID, Packets.Delete.PACKET_CODEC)
                registerPayload(clientbound, Packets.DisplayEnabled.PACKET_ID, Packets.DisplayEnabled.PACKET_CODEC)
                registerPayload(clientbound, Packets.ReportEnabled.PACKET_ID, Packets.ReportEnabled.PACKET_CODEC)
                registerPayload(clientbound, Packets.ClearCache.PACKET_ID, Packets.ClearCache.PACKET_CODEC)
            }

            payloadRegistry("serverboundPlay", "playC2S").let { serverbound ->
                registerPayload(serverbound, V2Payload.TYPE, V2Payload.CODEC)
                registerPayload(serverbound, Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC)
                registerPayload(serverbound, Packets.RequestSync.PACKET_ID, Packets.RequestSync.PACKET_CODEC)
                registerPayload(serverbound, Packets.Delete.PACKET_ID, Packets.Delete.PACKET_CODEC)
                registerPayload(serverbound, Packets.Report.PACKET_ID, Packets.Report.PACKET_CODEC)
                registerPayload(serverbound, Packets.Version.PACKET_ID, Packets.Version.PACKET_CODEC)
                registerPayload(serverbound, Packets.SetVideo.PACKET_ID, Packets.SetVideo.PACKET_CODEC)
                registerPayload(serverbound, Packets.SetLocked.PACKET_ID, Packets.SetLocked.PACKET_CODEC)
                registerPayload(serverbound, Packets.DisplayEnabled.PACKET_ID, Packets.DisplayEnabled.PACKET_CODEC)
            }
        }.onFailure { e ->
            logger.error("Failed to register payload types.", e)
            throw e
        }
    }

    private fun payloadRegistry(vararg methodNames: String): Any {
        val type = PayloadTypeRegistry::class.java
        val method = methodNames.firstNotNullOfOrNull { name ->
            runCatching { type.getMethod(name) }.getOrNull()
        } ?: error("No compatible Fabric payload registry method found: ${methodNames.joinToString()}.")
        return method.invoke(null)
    }

    private fun registerPayload(registry: Any, packetId: Any, packetCodec: Any) {
        val register = registry.javaClass.methods.first {
            it.name == "register" && it.parameterCount == 2
        }
        register.invoke(registry, packetId, packetCodec)
    }

    companion object {
        /** Logger. */
        val logger = LoggerFactory.getLogger("DreamDisplays")

        /** The mod version string, resolved from the `Fabric` mod container. */
        val serverVersion: String? by lazy {
            runCatching {
                FabricLoader.getInstance()
                    .getModContainer("dreamdisplays")
                    .orElse(null)
                    ?.metadata
                    ?.version
                    ?.friendlyString
            }.getOrNull()
        }

        private lateinit var configInstance: VanillaConfig

        val config: VanillaConfig; get() = configInstance
        val server: MinecraftServer?; get() = VanillaServerState.server
        val storage: StorageManager?; get() = VanillaServerState.storage

        /** Copies the pre-1.8.1 global `SQLite DB` into [worldDataDir] on first startup for this world. */
        @Deprecated("Will be removed in 1.9.0")
        // TODO: remove
        private fun migrateGlobalDb(worldDataDir: File) {
            val oldDb = FabricLoader.getInstance().configDir
                .resolve("dreamdisplays").resolve("dreamdisplays.db").toFile()
            val newDb = File(worldDataDir, "dreamdisplays.db")
            if (!oldDb.exists() || newDb.exists()) return
            runCatching { oldDb.copyTo(newDb) }
                .onSuccess {
                    logger.info(
                        "Migrated displays from legacy global DB to per-world DB at ${newDb.absolutePath}. " +
                                "The old file at ${oldDb.absolutePath} can be deleted once all worlds have been started at least once."
                    )
                }
                .onFailure { logger.error("Failed to migrate global DB to ${newDb.absolutePath}.", it) }
        }
    }
}
