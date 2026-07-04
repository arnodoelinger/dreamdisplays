package com.dreamdisplays.platform.client

import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
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

@Suppress("UNUSED")
@Mod(value = Initializer.MOD_ID, dist = [Dist.CLIENT])
class Client(modEventBus: IEventBus) : com.dreamdisplays.platform.client.Mod {
    init {
        // The Platform must be in the registry before onModInit, so ClientStartupManager
        // can host the ClientApplication on top of it during bootstrap.
        DreamServices.registry.register(PlatformServices.PLATFORM, NeoForgePlatformIntegrationProvider.create())
        Initializer.onModInit(this)

        // Payload registration lives entirely in NeoForgeServer.registerPayloads (see
        // platform/server/.../Main.kt): that class loads unconditionally on every dist, unlike this
        // one (dist = [Dist.CLIENT]), and NeoForge rejects registering the same payload id twice,
        // so there can only be one registrar per mod, not one per @Mod class.

        NeoForge.EVENT_BUS.register(this)
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
