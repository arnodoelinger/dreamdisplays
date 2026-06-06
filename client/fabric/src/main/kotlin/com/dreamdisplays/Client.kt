package com.dreamdisplays

import com.dreamdisplays.display.DisplayManager
import com.dreamdisplays.net.Packets
import com.dreamdisplays.render.ScreenRenderer
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
//? if >=26 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
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
                renderScreens(context, mc)
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
        //?} else
        /*WorldRenderEvents.AFTER_ENTITIES.register { context ->
            val mc = Minecraft.getInstance()
            if (mc.level != null && mc.player != null) {
                ScreenRenderer.render(context.matrices(), context.gameRenderer().mainCamera)
            }
        }*/

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

    //? if >=26 {
    private fun renderScreens(context: LevelRenderContext, mc: Minecraft) {
        val camera = mainCamera(mc)
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
