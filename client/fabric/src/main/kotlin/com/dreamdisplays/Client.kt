package com.dreamdisplays

import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.register
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.net.Packets
import com.dreamdisplays.platform.FabricPlatform
import com.dreamdisplays.platform.api.Platform
import com.dreamdisplays.render.ScreenRenderer
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
//? if >=26 {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
//?} else
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents*/
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy

@Suppress("UNUSED")
class Client : ClientModInitializer, Mod {
    private var customGeometryUnavailable = false

    override fun onInitializeClient() {
        // The Platform must be in the registry before onModInit so ClientStartupManager
        // can host the ClientApplication on top of it during bootstrap.
        DreamServices.registry.register<Platform>(FabricPlatform)
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

        //? if >=26 {
        LevelRenderEvents.BEFORE_GIZMOS.register { context ->
            val mc = Minecraft.getInstance()
            if (mc.level != null && mc.player != null) {
                renderSubmittedScreens(context, mc)
            }
        }

        LevelRenderEvents.END_MAIN.register { context ->
            val mc = Minecraft.getInstance()
            if (mc.level != null && mc.player != null) {
                renderBufferedScreens(context, mc)
                // Render popout windows after all Minecraft/mod rendering is submitted,
                // so any GL-context switch (macOS GLFW backend) does not disturb in-flight commands.
                DisplayRegistry.getScreens().forEach { it.renderPopout() }
            }
        }

        //?} else
        /*WorldRenderEvents.AFTER_ENTITIES.register { context ->
            val mc = Minecraft.getInstance()
            if (mc.level != null && mc.player != null) {
                ScreenRenderer.render(context.matrices(), context.gameRenderer().mainCamera)
                DisplayRegistry.getScreens().forEach { it.renderPopout() }
            }
        }*/

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
                Initializer.onServerJoined(serverId)
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            Initializer.onServerLeft()
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register { Initializer.onStop() }
    }

    override fun sendPacket(packet: CustomPacketPayload) {
        ClientPlayNetworking.send(packet)
    }

    //? if >=26 {
    private fun renderSubmittedScreens(context: LevelRenderContext, mc: Minecraft) {
        val camera = mainCamera(mc)
        if (hasBufferSource(context)) {
            return
        }

        val submitNodeCollector = runCatching {
            context.javaClass.getMethod("submitNodeCollector").invoke(context)
        }.getOrNull()

        if (submitNodeCollector == null || customGeometryUnavailable) {
            ScreenRenderer.render(context.poseStack(), camera)
            return
        }

        runCatching {
            ScreenRenderer.render(context.poseStack(), camera) { type, appendVertices ->
                submitCustomGeometry(context.poseStack(), submitNodeCollector, type, appendVertices)
            }
        }.onFailure { e ->
            customGeometryUnavailable = true
            logger.warn("Fabric custom geometry submission unavailable, falling back to immediate rendering: ${e.message}.")
            ScreenRenderer.render(context.poseStack(), camera)
        }
    }

    private fun renderBufferedScreens(context: LevelRenderContext, mc: Minecraft) {
        if (!hasBufferSource(context)) {
            return
        }
        renderWithBufferSource(context, mainCamera(mc))
    }

    private fun hasBufferSource(context: LevelRenderContext): Boolean =
        runCatching { context.javaClass.getMethod("bufferSource") }.isSuccess

    private fun renderWithBufferSource(context: LevelRenderContext, camera: Camera) {
        val bufferSource = runCatching {
            context.javaClass.getMethod("bufferSource").invoke(context)
        }.getOrNull() ?: return
        val getBuffer = runCatching { bufferSource.javaClass.getMethod("getBuffer", RenderType::class.java) }
            .getOrNull() ?: return
        val endBatch = runCatching { bufferSource.javaClass.getMethod("endBatch") }
            .getOrNull() ?: return

        ScreenRenderer.render(context.poseStack(), camera) { type, appendVertices ->
            appendVertices(context.poseStack().last(), getBuffer.invoke(bufferSource, type) as VertexConsumer)
        }
        endBatch.invoke(bufferSource)
    }

    private fun mainCamera(mc: Minecraft): Camera {
        val gameRenderer = mc.gameRenderer
        val method = runCatching { gameRenderer.javaClass.getMethod("mainCamera") }
            .getOrElse { gameRenderer.javaClass.getMethod("getMainCamera") }
        return method.invoke(gameRenderer) as Camera
    }

    private fun submitCustomGeometry(
        stack: PoseStack,
        submitNodeCollector: Any,
        type: RenderType,
        appendVertices: (PoseStack.Pose, VertexConsumer) -> Unit,
    ) {
        val rendererClass = Class.forName("net.minecraft.client.renderer.SubmitNodeCollector\$CustomGeometryRenderer")
        val renderer = Proxy.newProxyInstance(rendererClass.classLoader, arrayOf(rendererClass)) { _, method, args ->
            if (method.name == "render" && args != null && args.size == 2) {
                appendVertices(args[0] as PoseStack.Pose, args[1] as VertexConsumer)
            }
            null
        }
        submitNodeCollector.javaClass
            .getMethod("submitCustomGeometry", PoseStack::class.java, RenderType::class.java, rendererClass)
            .invoke(submitNodeCollector, stack, type, renderer)
    }
    //?}

    private companion object {
        private val logger = LoggerFactory.getLogger("DreamDisplays/FabricClient")
    }
}
