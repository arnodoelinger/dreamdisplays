package com.dreamdisplays.platform.client.render

//? if >=26.3 {
import net.minecraft.client.Minecraft
//?}
import java.util.*

/**
 * Tracks the display render types so they can be kept out of the translucent phase while
 * `Improved Transparency` is on.
 *
 * Display pipelines blend (first-frame fade-in), which makes the game queue them as translucent
 * geometry. With `Improved Transparency` that whole phase is drawn through order-independent
 * transparency and every render type in it must carry dedicated OIT pipelines, or the frame just
 * crashes.
 *
 * A display is an opaque surface, so it is drawn in the solid phase instead.
 */
object DisplaySolidPhase {
    private val displayTypes: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** Registers [type] as a display render type and returns it. */
    fun <T : Any> mark(type: T): T = type.also { displayTypes.add(it) }

    /** True when [type] is a display render type that must be drawn in the solid phase this frame. */
    fun drawsSolid(type: Any): Boolean = improvedTransparency() && type in displayTypes

    private fun improvedTransparency(): Boolean =
        //? if >=26.3 {
        Minecraft.getInstance().gameRenderer.useImprovedTransparency()
        //?} else
        /*false*/
}
