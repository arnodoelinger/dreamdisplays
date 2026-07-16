package com.dreamdisplays.platform.client.audio

import com.dreamdisplays.api.media.audio.ListenerPose
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import org.joml.Vector3f

/** Reads the current game camera pose for the acoustics engine's shared listener state. */
internal object ListenerPoseTracker {
    /** Returns the current camera pose, or [ListenerPose.IDENTITY] before a camera exists. */
    fun currentPose(minecraft: Minecraft): ListenerPose {
        val camera = runCatching { mainCameraOf(minecraft) }.getOrNull() ?: return ListenerPose.IDENTITY
        val pos =
            //? if >=1.21.11 {
            camera.position()
            //?} else
            /*camera.getPosition()*/
        val rotation = camera.rotation()
        val forward = Vector3f(0f, 0f, -1f).rotate(rotation)
        val up = Vector3f(0f, 1f, 0f).rotate(rotation)
        return ListenerPose(
            pos.x, pos.y, pos.z,
            forward.x.toDouble(), forward.y.toDouble(), forward.z.toDouble(),
            up.x.toDouble(), up.y.toDouble(), up.z.toDouble(),
        )
    }

    /**
     * Resolves the main camera via reflection (`mainCamera()` / `getMainCamera()`), matching the
     * accessor lookup the platform render entry points already use for the same version split.
     */
    private fun mainCameraOf(minecraft: Minecraft): Camera {
        val gameRenderer = minecraft.gameRenderer
        val method = runCatching { gameRenderer.javaClass.getMethod("mainCamera") }
            .getOrElse { gameRenderer.javaClass.getMethod("getMainCamera") }
        return method.invoke(gameRenderer) as Camera
    }
}
