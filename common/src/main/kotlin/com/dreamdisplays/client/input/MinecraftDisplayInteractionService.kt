package com.dreamdisplays.client.input

import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.utils.RayCastingUtil
import net.minecraft.client.Minecraft
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minecraft-backed [DisplayInteractionService]. Composes the generic block raycaster
 * ([RayCastingUtil]) with [DisplayRegistry] to answer the display-aware question "what display is the
 * player looking at?", and acts as the event bus for [DisplayInteraction]s.
 *
 * [RayCastingUtil] stays deliberately display-agnostic (it only knows blocks); the mapping from a
 * block hit to a [DisplayId] lives here so the contract can be satisfied without leaking world
 * geometry into the input layer.
 *
 * @since 1.0.0
 */
object MinecraftDisplayInteractionService : DisplayInteractionService {
    /** Max reach, in blocks, for the look raycast. Mirrors the value used by [com.dreamdisplays.managers.ClientTickManager]. */
    private const val MAX_REACH: Double = 64.0

    /** Thread-safe list of listeners subscribed to [DisplayInteraction] events. */
    private val listeners = CopyOnWriteArrayList<(DisplayInteraction) -> Unit>()

    /**
     * Casts a look ray from the player's eye and returns the display under the crosshair as a
     * [RaycastResult.Hit], or [RaycastResult.Miss] if the ray hits no block, no display occupies the
     * hit block, or there is no player / level.
     */
    override fun raycast(): RaycastResult {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return RaycastResult.Miss
        val hit = RayCastingUtil.rCBlock(MAX_REACH) ?: return RaycastResult.Miss
        val screen = DisplayRegistry.getScreens().firstOrNull { it.isInScreen(hit.blockPos) }
            ?: return RaycastResult.Miss
        val loc = hit.location
        val distanceSq = player.getEyePosition(1.0f).distanceToSqr(loc)
        return RaycastResult.Hit(
            displayId = DisplayId(screen.uuid),
            hitX = loc.x,
            hitY = loc.y,
            hitZ = loc.z,
            distanceSq = distanceSq,
        )
    }

    /** Convenience wrapper around [raycast] that returns the [RaycastResult.Hit] or null on a miss. */
    override fun getCurrentTarget(): RaycastResult.Hit? = raycast() as? RaycastResult.Hit

    /** Subscribes [listener] to [DisplayInteraction] events; close the returned handle to unsubscribe. */
    override fun on(listener: (DisplayInteraction) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /**
     * Publishes [interaction] to all subscribers. Call sites (e.g. the tick / input handlers) emit
     * here once they detect a right-click, hotbar scroll, focus toggle, popout request, etc.
     */
    override fun emit(interaction: DisplayInteraction) {
        listeners.forEach { it(interaction) }
    }
}
