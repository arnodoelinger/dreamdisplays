package com.dreamdisplays.render

import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.client.render.ClientRenderService
import com.dreamdisplays.client.render.DisplayRenderEntry
import com.dreamdisplays.client.render.RenderHook
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.displays.DisplayScreen
import com.dreamdisplays.render.api.RenderContext
import com.dreamdisplays.render.api.TextureHandle
import com.mojang.blaze3d.vertex.*
import net.minecraft.client.Camera
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sin

/** Renders screens in the world. */
object ScreenRenderer : ClientRenderService {
    private typealias QuadAppender = (PoseStack.Pose, VertexConsumer) -> Unit
    private typealias QuadRenderer = (RenderType, QuadAppender) -> Unit

    /** Iterates all registered screens and renders each one relative to [camera]. */
    fun render(stack: PoseStack, camera: Camera) {
        render(stack, camera) { type, appendVertices ->
            drawImmediate(stack, type, appendVertices)
        }
    }

    /**
     * [ClientRenderService] render entry point. Unwraps a [MinecraftRenderContext] and delegates to
     * [render]; any other [RenderContext] type is a no-op (this renderer can only draw through a live
     * [PoseStack] / [Camera]).
     */
    override fun renderAll(context: RenderContext) {
        val ctx = context as? MinecraftRenderContext ?: return
        render(ctx.stack, ctx.camera)
    }

    /** No-op: live screens are created by [DisplayRegistry] from network packets, not flat entries. */
    override fun registerDisplay(entry: DisplayRenderEntry) = Unit

    /** Unregisters the live screen matching [displayId], delegating to [DisplayRegistry]. */
    override fun unregisterDisplay(displayId: DisplayId) {
        DisplayRegistry.getScreens()
            .firstOrNull { it.uuid == displayId.uuid }
            ?.let { DisplayRegistry.unregisterScreen(it) }
    }

    /** No-op: each [DisplayScreen] owns its own GPU texture lifecycle in the current model. */
    override fun updateTexture(displayId: DisplayId, handle: TextureHandle) = Unit

    /** Number of live screens with an uploaded texture. Those this renderer will actually draw. */
    override val registeredCount: Int
        get() = DisplayRegistry.getScreens().count { it.hasTexture }

    /** Iterates all registered screens and lets the caller submit quads through the active renderer. */
    fun render(stack: PoseStack, camera: Camera, drawQuad: QuadRenderer) {
        val cameraPos = camera.position()
        for (displayScreen in DisplayRegistry.getScreens()) {
            if (displayScreen.isDormant || !displayScreen.hasTexture) continue

            stack.pushPose()

            val pos = displayScreen.pos
            val screenCenter = Vec3.atLowerCornerOf(pos)
            val relativePos = screenCenter.subtract(cameraPos)
            stack.translate(relativePos.x, relativePos.y, relativePos.z)

            renderScreenTexture(displayScreen, stack, drawQuad)

            stack.popPose()
        }

        // The registered RenderHook extends the world pass after the mod's own screens
        // (by default it dispatches API-registered surfaces, see DreamServices.bootstrap).
        // The world render hooks do not surface a partial tick, hence the 0f tickDelta.
        DreamServices.registry.getOrNull<RenderHook>()
            ?.onRender(MinecraftRenderContext(stack, camera, 0f))
    }

    /** Translates and rotates the pose for [displayScreen]'s facing direction, then renders the video or fallback color. */
    private fun renderScreenTexture(displayScreen: DisplayScreen, stack: PoseStack, drawQuad: QuadRenderer) {
        // Upload the latest decoded frame to the GPU texture (if a new one is ready).
        // Done here on the render thread instead of via mc.execute() per frame.
        displayScreen.fitTexture()

        stack.pushPose()
        DisplayGeometry.applyScreenTransform(stack, displayScreen.facing, displayScreen.width, displayScreen.height)

        if (displayScreen.isVideoStarted && displayScreen.hasTexture && displayScreen.renderType != null) {
            renderGpuTexture(drawQuad, displayScreen)
        } else if (displayScreen.fallbackRenderType != null) {
            if (displayScreen.errored) {
                renderColor(drawQuad, displayScreen.fallbackRenderType!!, 35, 5, 5, displayScreen.rotation)
            } else {
                val pulse = abs(sin(System.nanoTime() / 1_500_000_000.0 * Math.PI)).toFloat()
                val v = (10 + pulse * 20).toInt()
                renderColor(drawQuad, displayScreen.fallbackRenderType!!, v, v, v, displayScreen.rotation)
            }
        }
        stack.popPose()
    }

    /** Draws a unit quad using the screen's GPU texture. */
    private fun renderGpuTexture(drawQuad: QuadRenderer, displayScreen: DisplayScreen) {
        val c = if (displayScreen.isYuvTexture) {
            (displayScreen.brightness.coerceIn(0f, 2f) * 127.5f).toInt().coerceIn(0, 255)
        } else {
            255
        }
        drawQuad(displayScreen.renderType!!) { pose, builder ->
            appendQuad(pose, builder, c, c, c, displayScreen.rotation)
        }
    }

    /** Draws a unit quad filled with a solid RGB color (used for loading / error state). */
    private fun renderColor(drawQuad: QuadRenderer, type: RenderType, r: Int, g: Int, b: Int, rotation: Int) {
        drawQuad(type) { pose, builder ->
            appendQuad(pose, builder, r, g, b, rotation)
        }
    }

    /** Texture corners in vertex order; rotating the list by [rotation] quarter-turns spins the image. */
    private val baseUv = arrayOf(0f to 1f, 1f to 1f, 1f to 0f, 0f to 0f)

    private fun appendQuad(pose: PoseStack.Pose, builder: VertexConsumer, r: Int, g: Int, b: Int, rotation: Int) {
        val rot = ((rotation % 4) + 4) % 4
        val uv = Array(4) { baseUv[(it + rot) % 4] }
        addVertex(pose, builder, 0f, 0f, 0f, r, g, b, uv[0].first, uv[0].second)
        addVertex(pose, builder, 1f, 0f, 0f, r, g, b, uv[1].first, uv[1].second)
        addVertex(pose, builder, 1f, 1f, 0f, r, g, b, uv[2].first, uv[2].second)
        addVertex(pose, builder, 0f, 1f, 0f, r, g, b, uv[3].first, uv[3].second)
    }

    private fun addVertex(
        pose: PoseStack.Pose,
        builder: VertexConsumer,
        x: Float,
        y: Float,
        z: Float,
        r: Int,
        g: Int,
        b: Int,
        u: Float,
        v: Float,
    ) {
        builder.addVertex(pose, x, y, z).setUv(u, v).setColor(r, g, b, 255)
    }

    private fun drawImmediate(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
        ImmediateRenderCompat.draw(stack, type, appendVertices)
    }

    private object ImmediateRenderCompat {
        private val tesselatorClass: Class<*>? by lazy {
            runCatching { Class.forName("com.mojang.blaze3d.vertex.Tesselator") }.getOrNull()
        }

        private val stagedVertexBufferClass: Class<*>? by lazy {
            runCatching { Class.forName("net.minecraft.client.renderer.StagedVertexBuffer") }.getOrNull()
        }

        fun draw(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
            when {
                stagedVertexBufferClass != null -> draw262(stack, type, appendVertices)
                tesselatorClass != null -> drawLegacy(stack, type, appendVertices, tesselatorClass!!)
                else -> drawBufferBuilder(stack, type, appendVertices)
            }
        }

        private fun drawLegacy(
            stack: PoseStack,
            type: RenderType,
            appendVertices: QuadAppender,
            tesselatorClass: Class<*>,
        ) {
            val tesselator = tesselatorClass.getMethod("getInstance").invoke(null)
            val mode = type.javaClass.getMethod("mode").invoke(type)
            val builder = tesselatorClass
                .getMethod("begin", mode.javaClass, VertexFormat::class.java)
                .invoke(tesselator, mode, type.format()) as VertexConsumer
            appendVertices(stack.last(), builder)
            val mesh = builder.javaClass.getMethod("buildOrThrow").invoke(builder)
            val meshDataClass = Class.forName("com.mojang.blaze3d.vertex.MeshData")
            type.javaClass.getMethod("draw", meshDataClass).invoke(type, mesh)
        }

        private fun drawBufferBuilder(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
            val mode = type.javaClass.getMethod("mode").invoke(type)
            val builderClass = Class.forName("com.mojang.blaze3d.vertex.BufferBuilder")
            val builder = builderClass
                .getConstructor(mode.javaClass, VertexFormat::class.java, Int::class.javaPrimitiveType)
                .newInstance(mode, type.format(), 1536) as VertexConsumer
            appendVertices(stack.last(), builder)
            val mesh = builder.javaClass.getMethod("buildOrThrow").invoke(builder)
            val meshDataClass = Class.forName("com.mojang.blaze3d.vertex.MeshData")
            type.javaClass.getMethod("draw", meshDataClass).invoke(type, mesh)
        }

        private fun draw262(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
            val stagedClass = Class.forName("net.minecraft.client.renderer.StagedVertexBuffer")
            val staged = stagedClass
                .getConstructor(java.util.function.Supplier::class.java, Int::class.javaPrimitiveType)
                .newInstance(java.util.function.Supplier { "dream-displays-immediate" }, 1536)
            try {
                val primitiveTopology = type.javaClass.getMethod("primitiveTopology").invoke(type)
                val draw = stagedClass
                    .getMethod("appendDraw", VertexFormat::class.java, primitiveTopology.javaClass)
                    .invoke(staged, type.format(), primitiveTopology)
                val builder = stagedClass
                    .getMethod("getVertexBuilder", draw.javaClass)
                    .invoke(staged, draw) as VertexConsumer
                appendVertices(stack.last(), builder)
                stagedClass.getMethod("upload").invoke(staged)
                val executeInfo = stagedClass.getMethod("getExecuteInfo", draw.javaClass).invoke(staged, draw) ?: return
                val prepared = type.javaClass.getMethod("prepare").invoke(type)
                prepared.javaClass.getMethod("drawFromBuffer", executeInfo.javaClass).invoke(prepared, executeInfo)
            } finally {
                (staged as AutoCloseable).close()
            }
        }
    }
}
