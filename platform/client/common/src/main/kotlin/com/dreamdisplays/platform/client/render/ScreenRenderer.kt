package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.display.model.DisplayFacing
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.core.getOrNull
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.api.render.RenderContext
import com.dreamdisplays.api.render.TextureHandle
import com.mojang.blaze3d.vertex.*
import net.minecraft.client.Camera
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.world.phys.Vec3
import kotlin.math.sin

/** Renders screens in the world. Better not to touch this shit. */
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
    override val registeredCount: Int; get() = DisplayRegistry.getScreens().count { it.hasTexture }

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

        val facing = displayScreen.facing
        val w = displayScreen.width
        val h = displayScreen.height

        if (displayScreen.isVideoStarted && displayScreen.hasTexture && displayScreen.renderType != null) {
            stack.pushPose()
            DisplayGeometry.applyScreenTransform(stack, facing, w, h)
            renderGpuTexture(drawQuad, displayScreen)
            stack.popPose()
        } else {
            renderPlaceholder(
                stack,
                drawQuad,
                DisplayYuvRenderTypes.solidColorType(),
                facing,
                w,
                h,
                displayScreen.errored
            )
        }
    }

    /** Draws a unit quad using the screen's GPU texture, ramping up the first-appear fade. */
    private fun renderGpuTexture(drawQuad: QuadRenderer, displayScreen: DisplayScreen) {
        val appear = displayScreen.appearProgress()
        val base = if (displayScreen.isYuvTexture) {
            displayScreen.brightness.coerceIn(0f, 2f) * 127.5f
        } else {
            255f
        }
        val c = (base * appear).toInt().coerceIn(0, 255)
        drawQuad(displayScreen.renderType!!) { pose, builder ->
            appendQuad(pose, builder, c, c, c, displayScreen.rotation)
        }
    }

    /** Depth-layer spacing between placeholder elements, in blocks toward the viewer (see [drawLayer]). */
    private const val OVERLAY_LIFT = 0.01f

    /**
     * Loading / error placeholder. Loading is a faintly breathing dark backdrop with an indeterminate
     * progress bar near the bottom — a track plus an accent segment that sweeps left to right; error is
     * a dark red backdrop with a static red bar. Each element sits on its own depth layer so they read
     * cleanly instead of z-fighting or blinking in place.
     */
    private fun renderPlaceholder(
        stack: PoseStack, drawQuad: QuadRenderer, type: RenderType,
        facing: DisplayFacing, w: Int, h: Int, error: Boolean,
    ) {
        // Backdrop on the screen plane
        drawLayer(stack, facing, w, h, 0f) {
            val (r, g, b) = if (error) {
                Triple(28, 6, 6)
            } else {
                val breathe = (sin(System.nanoTime() / 2_000_000_000.0 * 2.0 * Math.PI).toFloat() + 1f) * 0.5f
                val v = (8 + breathe * 6f).toInt()
                Triple(v, v, v)
            }
            drawQuad(type) { pose, vb -> appendRect(pose, vb, 0f, 0f, 1f, 1f, r, g, b) }
        }

        val y0 = 0.045f
        val y1 = 0.075f
        val x0 = 0.06f
        val x1 = 0.94f

        // Bar track, lifted off the backdrop.
        drawLayer(stack, facing, w, h, OVERLAY_LIFT) {
            val (r, g, b) = if (error) Triple(120, 30, 30) else Triple(22, 24, 34)
            drawQuad(type) { pose, vb -> appendRect(pose, vb, x0, y0, x1, y1, r, g, b) }
        }
        if (error) return

        // Accent segment sweeping across the track, clipped at the ends so it grows in and shrinks out,
        // lifted again so it never fights the track.
        val period = 1_300_000_000L
        val phase = (System.nanoTime() % period).toFloat() / period
        val segW = 0.28f
        val travel = (x1 - x0) + segW
        val segStart = x0 - segW + travel * phase
        val sx0 = segStart.coerceIn(x0, x1)
        val sx1 = (segStart + segW).coerceIn(x0, x1)
        if (sx1 > sx0) drawLayer(stack, facing, w, h, OVERLAY_LIFT * 2f) {
            drawQuad(type) { pose, vb -> appendRect(pose, vb, sx0, y0, sx1, y1, 40, 110, 255) }
        }
    }

    /**
     * Runs [body] with the screen transform applied and the layer lifted [lift] blocks toward the
     * viewer (in world space, before the transform's z-flattening scale) so stacked overlay quads
     * occupy distinct depths.
     */
    private inline fun drawLayer(
        stack: PoseStack, facing: DisplayFacing, w: Int, h: Int, lift: Float, body: () -> Unit,
    ) {
        stack.pushPose()
        if (lift != 0f) DisplayGeometry.liftTowardViewer(stack, facing, lift)
        DisplayGeometry.applyScreenTransform(stack, facing, w, h)
        body()
        stack.popPose()
    }

    /** Appends a solid-color rectangle spanning [x0,y0]-[x1,y1] in unit-quad space (UV is ignored: the
     *  overlay type samples a 1x1 white texture). Wound CCW to match the video quad. */
    private fun appendRect(
        pose: PoseStack.Pose, builder: VertexConsumer,
        x0: Float, y0: Float, x1: Float, y1: Float, r: Int, g: Int, b: Int,
    ) {
        addVertex(pose, builder, x0, y0, 0f, r, g, b, 0f, 0f)
        addVertex(pose, builder, x1, y0, 0f, r, g, b, 0f, 0f)
        addVertex(pose, builder, x1, y1, 0f, r, g, b, 0f, 0f)
        addVertex(pose, builder, x0, y1, 0f, r, g, b, 0f, 0f)
    }

    /** Texture corners in vertex order; rotating the list by [rotation] quarter-turns spins the image. */
    private val baseUv = arrayOf(0f to 1f, 1f to 1f, 1f to 0f, 0f to 0f)

    /** Appends a quad with the given [rotation] (0..3) and color [r,g,b] (0..255). */
    private fun appendQuad(pose: PoseStack.Pose, builder: VertexConsumer, r: Int, g: Int, b: Int, rotation: Int) {
        val rot = ((rotation % 4) + 4) % 4
        val uv = Array(4) { baseUv[(it + rot) % 4] }
        addVertex(pose, builder, 0f, 0f, 0f, r, g, b, uv[0].first, uv[0].second)
        addVertex(pose, builder, 1f, 0f, 0f, r, g, b, uv[1].first, uv[1].second)
        addVertex(pose, builder, 1f, 1f, 0f, r, g, b, uv[2].first, uv[2].second)
        addVertex(pose, builder, 0f, 1f, 0f, r, g, b, uv[3].first, uv[3].second)
    }

    /** Adds a vertex with the given [r,g,b] (0..255) and [u,v] (0..1) coordinates. */
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

    /** Draws a quad using the given [type] and [appendVertices] function. A bit of a hack. */
    private fun drawImmediate(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
        ImmediateRenderCompat.draw(stack, type, appendVertices)
    }

    /** Compatibility layer for the new immediate mode API. */
    private object ImmediateRenderCompat {
        fun draw(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
            //? if >=26 {
            draw262(stack, type, appendVertices)
            //?} else
            /*run {
                val builder = Tesselator.getInstance().begin(type.mode(), type.format())
                appendVertices(stack.last(), builder)
                type.draw(builder.buildOrThrow())
            }*/
        }

        //? if >=26 {
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
        //?}
    }
}
