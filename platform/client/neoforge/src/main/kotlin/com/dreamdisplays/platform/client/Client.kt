package com.dreamdisplays.platform.client

import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.net.Packets
import com.dreamdisplays.platform.client.net.V2Payload
import com.dreamdisplays.platform.client.platform.NeoForgePlatformIntegrationProvider
import com.dreamdisplays.api.platform.PlatformServices
import com.dreamdisplays.platform.client.render.ScreenRenderer
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Camera
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
//? if >=1.21.11 {
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent
//?}
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

@Suppress("UNUSED")
@Mod(value = Initializer.MOD_ID, dist = [Dist.CLIENT])
class Client(modEventBus: IEventBus) : com.dreamdisplays.platform.client.Mod {
    init {
        // The Platform must be in the registry before onModInit, so ClientStartupManager
        // can host the ClientApplication on top of it during bootstrap.
        DreamServices.registry.register(PlatformServices.PLATFORM, NeoForgePlatformIntegrationProvider.create())
        Initializer.onModInit(this)
        modEventBus.addListener(::registerPayloads)
        NeoForge.EVENT_BUS.register(this)
    }

    /** Registers the payload handlers for the client. */
    // TODO: move
    fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(Initializer.MOD_ID).optional().versioned("1")

        // Protocol v2: one opaque envelope payload in both directions; the optional registrar
        // keeps blind sends to vanilla / Paper servers working, same as the legacy version packet.
        //? if >=1.21.11 {
        registrar.playBidirectional(
            V2Payload.TYPE, V2Payload.CODEC,
            { _, _ -> },
            { payload, _ -> Initializer.onV2Packet(payload.bytes) })
        //?} else
        /*registrar.playBidirectional(V2Payload.TYPE, V2Payload.CODEC) { payload, _ ->
            Initializer.onV2Packet(payload.bytes)
        }*/

        // Frozen v1 payloads for pre-v2 servers; incoming ones are lifted into v2 packets
        //? if >=1.21.11 {
        registrar.playBidirectional(
            Packets.Delete.PACKET_ID, Packets.Delete.PACKET_CODEC,
            { _, _ -> },
            { payload, _ -> Initializer.onLegacyPacket(payload) })
        //?} else
        /*registrar.playBidirectional(Packets.Delete.PACKET_ID, Packets.Delete.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }*/
        registrar.playToClient(Packets.Info.PACKET_ID, Packets.Info.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }
        registrar.playToClient(Packets.Premium.PACKET_ID, Packets.Premium.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }
        registrar.playToClient(Packets.IsAdmin.PACKET_ID, Packets.IsAdmin.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }
        registrar.playToClient(Packets.DisplayEnabled.PACKET_ID, Packets.DisplayEnabled.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }
        registrar.playToClient(Packets.ReportEnabled.PACKET_ID, Packets.ReportEnabled.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }
        registrar.playToClient(Packets.ClearCache.PACKET_ID, Packets.ClearCache.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }
        //? if >=1.21.11 {
        registrar.playBidirectional(
            Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC,
            { _, _ -> },
            { payload, _ -> Initializer.onLegacyPacket(payload) })
        //?} else
        /*registrar.playBidirectional(Packets.Sync.PACKET_ID, Packets.Sync.PACKET_CODEC) { payload, _ ->
            Initializer.onLegacyPacket(payload)
        }*/
        registrar.playToServer(Packets.RequestSync.PACKET_ID, Packets.RequestSync.PACKET_CODEC) { _, _ -> }
        registrar.playToServer(Packets.Report.PACKET_ID, Packets.Report.PACKET_CODEC) { _, _ -> }
        registrar.playToServer(Packets.Version.PACKET_ID, Packets.Version.PACKET_CODEC) { _, _ -> }
        registrar.playToServer(Packets.SetVideo.PACKET_ID, Packets.SetVideo.PACKET_CODEC) { _, _ -> }
        registrar.playToServer(Packets.SetLocked.PACKET_ID, Packets.SetLocked.PACKET_CODEC) { _, _ -> }
    }

    /** On server join / leave events. */
    @SubscribeEvent
    fun onLogin(event: ClientPlayerNetworkEvent.LoggingIn) {
        val mc = Minecraft.getInstance()
        if (mc.level != null && mc.player != null) {
            val serverId = if (mc.hasSingleplayerServer()) "singleplayer"
            else mc.currentServer?.ip ?: "unknown"
            Initializer.onServerJoined(serverId)
        }
    }

    /** On server join / leave events. */
    @SubscribeEvent
    fun onDisconnect(event: ClientPlayerNetworkEvent.LoggingOut) {
        Initializer.onServerLeft()
    }

    //? if >=1.21.11 {
    /** On client shutdown. */
    @SubscribeEvent
    fun onClientStopping(event: ClientStoppingEvent) {
        Initializer.onStop()
    }
    //?}

    //? if >=1.21.11 {
    /** On render events. */
    @SubscribeEvent
    fun onRenderAfterLevel(event: RenderLevelStageEvent.AfterLevel) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        try {
            modelViewStack.mul(event.modelViewMatrix)
            ScreenRenderer.render(event.poseStack, mainCamera(mc))
        } finally {
            modelViewStack.popMatrix()
        }
    }
    //?} else
    /*
    // On render events.
    @SubscribeEvent fun onRenderAfterLevel(event: RenderLevelStageEvent) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return
        ScreenRenderer.render(event.poseStack, event.camera)
    }*/

    /** Main camera accessor. */
    private fun mainCamera(mc: Minecraft): Camera {
        val gameRenderer = mc.gameRenderer
        val method = runCatching { gameRenderer.javaClass.getMethod("mainCamera") }
            .getOrElse { gameRenderer.javaClass.getMethod("getMainCamera") }
        return method.invoke(gameRenderer) as Camera
    }

    /** On tick events. */
    @SubscribeEvent
    fun onEndTick(event: ClientTickEvent.Post) {
        Initializer.onEndTick(Minecraft.getInstance())
    }

    /** On render events. */
    @SubscribeEvent
    fun onRenderGui(event: RenderGuiEvent.Post) {
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
        /** Packet sender. */
        Minecraft.getInstance().connection?.send(packet)
    }
}
