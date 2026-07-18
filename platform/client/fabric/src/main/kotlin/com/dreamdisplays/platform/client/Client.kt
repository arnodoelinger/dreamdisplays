package com.dreamdisplays.platform.client

//? if >=1.21.11 {
//?} else
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback*/
//? if >=26 {
//?} else
/*
//? if ==1.21.11 {
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
//?}
//? if <1.21.11 {
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
//?}
*/
//? if >=1.21.11 {
//?} else
/*import net.minecraft.client.renderer.RenderType*/
//? if >=1.21.11 {
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import com.dreamdisplays.api.platform.PlatformServices
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.net.Packets
import com.dreamdisplays.platform.client.net.V2Payload
import com.dreamdisplays.platform.client.platform.FabricPlatformIntegrationProvider
import com.dreamdisplays.platform.client.render.ScreenRenderer
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy

@Suppress("UNUSED")
class Client : ClientModInitializer, Mod {
    /** If the `LevelRenderContext` has a `BufferSource` API, this is set to true. */
    private var customGeometryUnavailable = false

    /** Called on client initialization. */
    override fun onInitializeClient() {
        // The Platform must be in the registry before onModInit so ClientStartupManager
        // can host the ClientApplication on top of it during bootstrap.
        DreamServices.registry.register(PlatformServices.PLATFORM, FabricPlatformIntegrationProvider.create())
        Initializer.onModInit(this)

        // Note: PayloadTypeRegistry registrations are done in platform/server/ (it's a main entrypoint)
        // which runs on both integrated and dedicated servers, before the client entrypoint.

        // Protocol v2: every packet arrives as one opaque envelope payload
        ClientPlayNetworking.registerGlobalReceiver(V2Payload.TYPE) { payload, _ ->
            Initializer.onV2Packet(payload.bytes)
        }

        // Frozen v1 receivers for pre-v2 servers; payloads are lifted into v2 packets
        listOf(
            Packets.Info.PACKET_ID, Packets.Premium.PACKET_ID, Packets.IsAdmin.PACKET_ID,
            Packets.Delete.PACKET_ID, Packets.DisplayEnabled.PACKET_ID, Packets.Sync.PACKET_ID,
            Packets.ReportEnabled.PACKET_ID, Packets.ClearCache.PACKET_ID,
        ).forEach { type ->
            ClientPlayNetworking.registerGlobalReceiver(type) { payload, _ ->
                Initializer.onLegacyPacket(payload)
            }
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
                ScreenRenderer.render(worldPoseStack(context), mainCamera(mc))
                DisplayRegistry.getScreens().forEach { it.renderPopout() }
            }
        }*/

        //? if >=1.21.11 {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "pip_overlay")
        ) { graphics, deltaTracker ->
            Initializer.onRenderHud(
                Minecraft.getInstance(), graphics,
                deltaTracker.getGameTimeDeltaPartialTick(false)
            )
        }
        //?} else
        /*HudRenderCallback.EVENT.register { graphics, deltaTracker ->
            Initializer.onRenderHud(
                Minecraft.getInstance(), graphics,
                deltaTracker.getGameTimeDeltaPartialTick(false)
            )
        }*/

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

    /** Packet sender. */
    override fun sendPacket(packet: CustomPacketPayload) {
        ClientPlayNetworking.send(packet)
    }

    //? if >=26 {
    /** Cached `SubmitNodeCollector$CustomGeometryRenderer` interface, resolved once. */
    private val customGeometryRendererClass: Class<*> by lazy {
        Class.forName("net.minecraft.client.renderer.SubmitNodeCollector\$CustomGeometryRenderer")
    }

    /** Cached `LevelRenderContext.submitNodeCollector` accessor (the context's runtime class is stable). */
    private var submitNodeCollectorMethod: java.lang.reflect.Method? = null

    /** Cached `SubmitNodeCollector.submitCustomGeometry(PoseStack, RenderType, CustomGeometryRenderer)`. */
    private var submitCustomGeometryMethod: java.lang.reflect.Method? = null

    /** Whether the `LevelRenderContext` runtime class exposes `bufferSource` (probed once). */
    private var hasBufferSourceCache: Boolean? = null

    /** Renders the screen using the `submitNodeCollector` API. */
    private fun renderSubmittedScreens(context: LevelRenderContext, mc: Minecraft) {
        val camera = mainCamera(mc)
        if (hasBufferSource(context)) {
            return
        }

        val submitNodeCollector = runCatching {
            val method = submitNodeCollectorMethod
                ?.takeIf { it.declaringClass.isAssignableFrom(context.javaClass) }
                ?: context.javaClass.getMethod("submitNodeCollector").also { submitNodeCollectorMethod = it }
            method.invoke(context)
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

    /** Renders the screen using the `BufferSource` API. */
    private fun renderBufferedScreens(context: LevelRenderContext, mc: Minecraft) {
        if (!hasBufferSource(context)) {
            return
        }
        renderWithBufferSource(context, mainCamera(mc))
    }

    /** If the `LevelRenderContext` has a `BufferSource` API, returns true. */
    private fun hasBufferSource(context: LevelRenderContext): Boolean =
        hasBufferSourceCache
            ?: runCatching { context.javaClass.getMethod("bufferSource") }.isSuccess.also { hasBufferSourceCache = it }

    /** Renders the screen using the `BufferSource` API. */
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

    /**
     * Submits custom geometry to the GPU. The renderer proxy is per-quad by design: the collector
     * renders deferred, so each submission must capture its own [appendVertices] (the generated
     * proxy class itself is cached by the JDK, so per-call instantiation is just an allocation).
     */
    private fun submitCustomGeometry(
        stack: PoseStack,
        submitNodeCollector: Any,
        type: RenderType,
        appendVertices: (PoseStack.Pose, VertexConsumer) -> Unit,
    ) {
        val rendererClass = customGeometryRendererClass
        val renderer = Proxy.newProxyInstance(rendererClass.classLoader, arrayOf(rendererClass)) { _, method, args ->
            if (method.name == "render" && args != null && args.size == 2) {
                appendVertices(args[0] as PoseStack.Pose, args[1] as VertexConsumer)
            }
            null
        }
        val method = submitCustomGeometryMethod
            ?.takeIf { it.declaringClass.isAssignableFrom(submitNodeCollector.javaClass) }
            ?: submitNodeCollector.javaClass
                .getMethod("submitCustomGeometry", PoseStack::class.java, RenderType::class.java, rendererClass)
                .also { submitCustomGeometryMethod = it }
        method.invoke(submitNodeCollector, stack, type, renderer)
    }
    //?}

    /** Main camera accessor. */
    private fun mainCamera(mc: Minecraft): Camera {
        //? if >=26.2 {
        return mc.gameRenderer.mainCamera()
        //?} else
        /*return mc.gameRenderer.getMainCamera()*/
    }

    /** World pose stack accessor. */
    private fun worldPoseStack(context: Any): PoseStack =
        runCatching { context.javaClass.getMethod("matrixStack") }
            .getOrElse { context.javaClass.getMethod("matrices") }
            .invoke(context) as PoseStack

    private companion object {
        /** Logger. */
        private val logger = LoggerFactory.getLogger("DreamDisplays/FabricClient")
    }
}
