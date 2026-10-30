package com.dreamdisplays.platform.client.render

import com.dreamdisplays.platform.client.displays.DisplayScreen
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Per-frame view-cone test that lets [ScreenRenderer] skip both the frame upload and the
 * draw of a display the camera cannot see.
 */
internal object DisplayCulling {
    private const val FOV_HEADROOM = 1.5
    private const val SPHERE_MARGIN = 1.0
    private const val MAX_FOV_DEGREES = 179.0
    private var active = false
    private var camX = 0.0
    private var camY = 0.0
    private var camZ = 0.0
    private var fwdX = 0.0
    private var fwdY = 0.0
    private var fwdZ = 1.0
    private var sinHalf = 0.0
    private var cosHalf = 0.0

    /** Captures the camera pose and the cone half-angle for the frame about to be drawn. */
    fun begin(camera: Camera, cameraPos: Vec3) {
        active = false
        val mc = Minecraft.getInstance()
        val windowW = mc.window.width
        val windowH = mc.window.height
        if (windowW <= 0 || windowH <= 0) return

        //? if >=1.21.11 {
        val forward = camera.forwardVector()
        //?} else
        /*val forward = camera.getLookVector()*/
        val fx = forward.x().toDouble()
        val fy = forward.y().toDouble()
        val fz = forward.z().toDouble()
        val len = sqrt(fx * fx + fy * fy + fz * fz)
        if (len < 1e-6) return

        //? if >=1.19 {
        val fovDegrees = (mc.options.fov().get() * FOV_HEADROOM).coerceAtMost(MAX_FOV_DEGREES)
        //?} else
        /*val fovDegrees = (mc.options.fov * FOV_HEADROOM).coerceAtMost(MAX_FOV_DEGREES)*/
        val tanV = tan(Math.toRadians(fovDegrees) / 2.0)
        val tanH = tanV * windowW / windowH
        val half = atan(sqrt(tanV * tanV + tanH * tanH))

        camX = cameraPos.x
        camY = cameraPos.y
        camZ = cameraPos.z
        fwdX = fx / len
        fwdY = fy / len
        fwdZ = fz / len
        sinHalf = sin(half)
        cosHalf = cos(half)
        active = true
    }

    /** True unless [screen]'s bounding sphere lies entirely outside the view cone captured by [begin]. */
    fun isVisible(screen: DisplayScreen): Boolean {
        if (!active) return true
        val sphere = screen.boundingSphere()
        val dx = sphere[0] - camX
        val dy = sphere[1] - camY
        val dz = sphere[2] - camZ
        val radius = sphere[3] + SPHERE_MARGIN
        val distSq = dx * dx + dy * dy + dz * dz
        if (distSq <= radius * radius) return true
        val along = dx * fwdX + dy * fwdY + dz * fwdZ
        val across = sqrt((distSq - along * along).coerceAtLeast(0.0))
        return across * cosHalf - along * sinHalf <= radius
    }
}
