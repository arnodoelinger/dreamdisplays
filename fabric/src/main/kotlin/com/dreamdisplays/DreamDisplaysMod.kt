package com.dreamdisplays

import com.dreamdisplays.display.DisplayManager
import com.dreamdisplays.net.Packets
import com.dreamdisplays.render.ScreenRenderer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

@Suppress("UNUSED")
class DreamDisplaysMod : ClientModInitializer, Mod {

    override fun onInitializeClient() {
        Initializer.onModInit(this)

        with(PayloadTypeRegistry.clientboundPlay()) {
            register(Packets.Info.PACKET_ID, Packets.Info.PACKET_CODEC)
            register(Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC)
            register(Packets.Premium.PACKET_ID, Packets.Premium.PACKET_CODEC)
            register(Packets.Delete.PACKET_ID, Packets.Delete.PACKET_CODEC)
            register(Packets.DisplayEnabled.PACKET_ID, Packets.DisplayEnabled.PACKET_CODEC)
            register(Packets.ReportEnabled.PACKET_ID, Packets.ReportEnabled.PACKET_CODEC)
            register(Packets.ClearCache.PACKET_ID, Packets.ClearCache.PACKET_CODEC)
        }

        with(PayloadTypeRegistry.serverboundPlay()) {
            register(Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC)
            register(Packets.RequestSync.PACKET_ID, Packets.RequestSync.PACKET_CODEC)
            register(Packets.Delete.PACKET_ID, Packets.Delete.PACKET_CODEC)
            register(Packets.Report.PACKET_ID, Packets.Report.PACKET_CODEC)
            register(Packets.Version.PACKET_ID, Packets.Version.PACKET_CODEC)
            register(Packets.SetVideo.PACKET_ID, Packets.SetVideo.PACKET_CODEC)
        }

        ClientPlayNetworking.registerGlobalReceiver(Packets.Info.PACKET_ID) { payload, _ ->
            Initializer.onDisplayInfoPacket(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(Packets.Premium.PACKET_ID) { payload, _ ->
            Initializer.onPremiumPacket(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(Packets.Delete.PACKET_ID) { payload, _ ->
            Initializer.onDeletePacket(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(Packets.DisplayEnabled.PACKET_ID) { payload, _ ->
            Initializer.onDisplayEnabledPacket(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(Packets.Sync.PACKET_ID) { payload, _ ->
            Initializer.onSyncPacket(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(Packets.ReportEnabled.PACKET_ID) { payload, _ ->
            Initializer.onReportEnabledPacket(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(Packets.ClearCache.PACKET_ID) { payload, _ ->
            Initializer.onClearCachePacket(payload)
        }

        LevelRenderEvents.BEFORE_GIZMOS.register { context ->
            val mc = Minecraft.getInstance()
            if (mc.level != null && mc.player != null) {
                ScreenRenderer.render(context.poseStack(), mc.gameRenderer.mainCamera)
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { Initializer.onEndTick(it) }

        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            if (client.level != null && client.player != null) {
                val serverId = if (client.isLocalServer) "singleplayer"
                else client.currentServer?.ip ?: "unknown"
                DisplayManager.loadScreensForServer(serverId)
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            DisplayManager.saveAllScreens()
            DisplayManager.unloadAll()
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register { Initializer.onStop() }
    }

    override fun sendPacket(packet: CustomPacketPayload) {
        ClientPlayNetworking.send(packet)
    }
}
