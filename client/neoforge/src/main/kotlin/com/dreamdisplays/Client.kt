package com.dreamdisplays

import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.register
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.net.Packets
import com.dreamdisplays.platform.NeoForgePlatform
import com.dreamdisplays.platform.api.Platform
import com.dreamdisplays.render.ScreenRenderer
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RenderGuiEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

@Suppress("UNUSED")
@Mod(value = Initializer.MOD_ID, dist = [Dist.CLIENT])
class Client(modEventBus: IEventBus) : com.dreamdisplays.Mod {
    init {
        // The Platform must be in the registry before onModInit so ClientStartupManager
        // can host the ClientApplication on top of it during bootstrap.
        DreamServices.registry.register<Platform>(NeoForgePlatform)
        Initializer.onModInit(this)
        modEventBus.addListener(::registerPayloads)
        NeoForge.EVENT_BUS.register(this)
    }

    fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(Initializer.MOD_ID).optional().versioned("1")

        registrar.playBidirectional(
            Packets.Delete.PACKET_ID, Packets.Delete.PACKET_CODEC,
            { _, _ -> },
            { payload, _ -> Initializer.onDeletePacket(payload) })
        registrar.playToClient(Packets.Info.PACKET_ID, Packets.Info.PACKET_CODEC) { payload, _ ->
            Initializer.onDisplayInfoPacket(payload)
        }
        registrar.playToClient(Packets.Premium.PACKET_ID, Packets.Premium.PACKET_CODEC) { payload, _ ->
            Initializer.onPremiumPacket(payload)
        }
        registrar.playToClient(Packets.DisplayEnabled.PACKET_ID, Packets.DisplayEnabled.PACKET_CODEC) { payload, _ ->
            Initializer.onDisplayEnabledPacket(payload)
        }
        registrar.playToClient(Packets.ReportEnabled.PACKET_ID, Packets.ReportEnabled.PACKET_CODEC) { payload, _ ->
            Initializer.onReportEnabledPacket(payload)
        }
        registrar.playToClient(Packets.ClearCache.PACKET_ID, Packets.ClearCache.PACKET_CODEC) { payload, _ ->
            Initializer.onClearCachePacket(payload)
        }
        registrar.playBidirectional(
            Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC,
            { _, _ -> },
            { payload, _ -> Initializer.onSyncPacket(payload) })
        registrar.playToServer(Packets.RequestSync.PACKET_ID, Packets.RequestSync.PACKET_CODEC) { _, _ -> }
        registrar.playToServer(Packets.Report.PACKET_ID, Packets.Report.PACKET_CODEC) { _, _ -> }
        registrar.playToServer(Packets.Version.PACKET_ID, Packets.Version.PACKET_CODEC) { _, _ -> }
        registrar.playToServer(Packets.SetVideo.PACKET_ID, Packets.SetVideo.PACKET_CODEC) { _, _ -> }
        registrar.playToServer(Packets.SetLocked.PACKET_ID, Packets.SetLocked.PACKET_CODEC) { _, _ -> }
    }

    @SubscribeEvent fun onLogin(event: ClientPlayerNetworkEvent.LoggingIn) {
        val mc = Minecraft.getInstance()
        if (mc.level != null && mc.player != null) {
            val serverId = if (mc.hasSingleplayerServer()) "singleplayer"
            else mc.currentServer?.ip ?: "unknown"
            Initializer.onServerJoined(serverId)
        }
    }

    @SubscribeEvent fun onDisconnect(event: ClientPlayerNetworkEvent.LoggingOut) {
        Initializer.onServerLeft()
    }

    @SubscribeEvent fun onClientStopping(event: ClientStoppingEvent) {
        Initializer.onStop()
    }

    //? if >=26 {
    @SubscribeEvent fun onRenderAfterLevel(event: RenderLevelStageEvent.AfterTranslucentParticles) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        ScreenRenderer.render(event.getPoseStack(), mc.gameRenderer.mainCamera)
    }
    //?} else
    /*@SubscribeEvent fun onRenderAfterLevel(event: RenderLevelStageEvent.AfterParticles) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        ScreenRenderer.render(event.poseStack, mc.gameRenderer.mainCamera)
    }*/

    @SubscribeEvent fun onEndTick(event: ClientTickEvent.Post) {
        Initializer.onEndTick(Minecraft.getInstance())
    }
    @SubscribeEvent fun onRenderGui(event: RenderGuiEvent.Post) {
        Initializer.onRenderHud(
            Minecraft.getInstance(),
            event.guiGraphics,
            event.partialTick.getGameTimeDeltaPartialTick(false)
        )
        // Render popout windows after all Minecraft/mod rendering is submitted,
        // so any GL-context switch (macOS GLFW backend) does not disturb in-flight commands.
        DisplayRegistry.getScreens().forEach { it.renderPopout() }
    }
    override fun sendPacket(packet: CustomPacketPayload) {
        Minecraft.getInstance().connection?.send(packet)
    }
}
