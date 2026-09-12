package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import org.joml.Matrix4f

/**
 * Repeats the display draw once the level pass - and with it a shader pack's whole composite chain -
 * is over, so the video itself is never graded by the pack.
 *
 * Displays are always drawn inside the level pass as well, right before translucent terrain: that is
 * what keeps them behind blocks and underneath water and glass. This pass only paints the untouched
 * picture back over the part of the quad that nothing translucent covers, so it is pointless without
 * a shader pack - there the level-pass draw already runs our own program - and is skipped.
 */
object UnshadedDisplayPass {
    @Volatile
    private var tailHookAlive = false

    private var pendingCamera: Camera? = null

    private val pendingView = Matrix4f()

    private var pending = false

    private val enabled: Boolean; get() = runCatching { ClientStateManager.config.unshadedDisplays }.getOrDefault(true)

    val active: Boolean get() = tailHookAlive && enabled && ShaderPackCompat.isShaderPackActive

    fun capture(stack: PoseStack, camera: Camera) {
        if (!active) {
            pending = false
            return
        }
        pendingCamera = camera
        pendingView.set(RenderSystem.getModelViewStack()).mul(stack.last().pose())
        pending = true
    }

    fun drawPending() {
        tailHookAlive = true
        if (!pending) return
        pending = false
        val camera = pendingCamera ?: return
        if (Minecraft.getInstance().level == null) return

        val modelView = RenderSystem.getModelViewStack()
        modelView.pushMatrix()
        modelView.set(pendingView)
        //? if >=1.21.11 {
        //?} else
        /*RenderSystem.applyModelViewMatrix()*/
        try {
            ScreenRenderer.render(PoseStack(), camera, replay = true)
        } finally {
            modelView.popMatrix()
            //? if >=1.21.11 {
            //?} else
            /*RenderSystem.applyModelViewMatrix()*/
        }
    }
}
