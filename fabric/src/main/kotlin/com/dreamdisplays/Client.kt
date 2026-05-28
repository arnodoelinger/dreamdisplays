package com.dreamdisplays

import com.dreamdisplays.display.DisplayManager
import com.dreamdisplays.net.Packets
import com.dreamdisplays.render.ScreenRenderer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

@Suppress("UNUSED")
class Client : ClientModInitializer, Mod {
    override fun onInitializeClient() {
        Initializer.onModInit(this)

        // Note: PayloadTypeRegistry registrations are done in server/ (it's a main entrypoint)
        // which runs on both integrated and dedicated servers, before the client entrypoint.

        ClientPlayNetworking.registerGlobalReceiver(Packets.Info.PACKET_ID) { payload, _ ->
            Initializer.onDisplayInfoPacket(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(Packets.Premium.PACKET_ID) { payload, _ ->
            Initializer.onPremiumPacket(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(Packets.IsAdmin.PACKET_ID) { payload, _ ->
            Initializer.onIsAdminPacket(payload)
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

        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "pip_overlay")
        ) { graphics, deltaTracker ->
            Initializer.onRenderHud(
                Minecraft.getInstance(), graphics,
                deltaTracker.getGameTimeDeltaPartialTick(false)
            )
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
            Initializer.isPremium = false
            Initializer.isAdmin = false
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register { Initializer.onStop() }
    }

    override fun sendPacket(packet: CustomPacketPayload) {
        ClientPlayNetworking.send(packet)
    }
}
