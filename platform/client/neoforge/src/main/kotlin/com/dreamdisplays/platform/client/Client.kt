package com.dreamdisplays.platform.client

import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.platform.NeoForgePlatformIntegrationProvider
import com.dreamdisplays.api.platform.service.keys.PlatformServices
import com.dreamdisplays.platform.client.render.ScreenRenderer
import com.dreamdisplays.platform.client.render.UnshadedDisplayPass
import com.dreamdisplays.platform.client.Mod as DreamMod
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
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
import net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent
//?} else
/*import net.neoforged.neoforge.client.event.RenderHighlightEvent*/
import net.neoforged.neoforge.common.NeoForge

@Mod(value = Initializer.MOD_ID, dist = [Dist.CLIENT])
class Client(modEventBus: IEventBus) : DreamMod {
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

    //? if >=26 {
    @SubscribeEvent
    fun onRenderDisplays(event: RenderLevelStageEvent.AfterOpaqueFeatures) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        val camera = mainCamera(mc)
        UnshadedDisplayPass.capture(event.poseStack, camera)
        ScreenRenderer.render(event.poseStack, camera)
    }
    //?} else
    /*
    //? if ==1.21.11 {
    @SubscribeEvent
    fun onRenderDisplays(event: RenderLevelStageEvent.AfterEntities) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        val camera = mainCamera(mc)
        UnshadedDisplayPass.capture(event.poseStack, camera)
        ScreenRenderer.render(event.poseStack, camera)
    }
    //?}
    //? if <1.21.11 {
    @SubscribeEvent fun onRenderDisplays(event: RenderLevelStageEvent) {
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return
        UnshadedDisplayPass.capture(event.poseStack, event.camera)
        ScreenRenderer.render(event.poseStack, event.camera)
    }
    //?}
    */

    //? if >=1.21.11 {
    @SubscribeEvent
    fun onExtractBlockOutline(event: ExtractBlockOutlineRenderStateEvent) {
        if (isDisplayBlock(event.blockPos)) {
            event.isCanceled = true
        }
    }
    //?} else
    /*
    @SubscribeEvent
    fun onRenderBlockHighlight(event: RenderHighlightEvent.Block) {
        if (isDisplayBlock(event.target.blockPos)) {
            event.isCanceled = true
        }
    }
    */

    private fun isDisplayBlock(pos: BlockPos): Boolean =
        DisplayRegistry.getScreens().any { it.isInScreen(pos) }

    /** Main camera accessor. */
    private fun mainCamera(mc: Minecraft): Camera {
        //? if >=26.2 {
        return mc.gameRenderer.mainCamera()
        //?} else
        /*return mc.gameRenderer.getMainCamera()*/
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
