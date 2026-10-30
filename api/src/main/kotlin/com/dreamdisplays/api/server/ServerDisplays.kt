package com.dreamdisplays.api.server

import com.dreamdisplays.api.display.model.property.DisplayFacing
import java.util.UUID
import java.util.function.Predicate

/**
 * A lazy view over the displays on this server. Every iteration reads the displays that exist right now.
 *
 * @since 1.10.x
 */
interface ServerDisplays : Iterable<ServerDisplay> {
    /** Number of displays in this view. */
    val size: Int

    /** The display with [id], or `null`. */
    operator fun get(id: UUID): ServerDisplay?

    /** The display called [name], or `null`. */
    fun byName(name: String): ServerDisplay?

    /** Keeps only displays owned by [owner]. */
    fun ownedBy(owner: UUID): ServerDisplays

    /** Keeps only displays in [world] within [radius] blocks of the point. */
    fun near(world: String, x: Double, y: Double, z: Double, radius: Double): ServerDisplays

    /** Keeps only displays that match [predicate]. */
    fun filter(predicate: Predicate<ServerDisplay>): ServerDisplays

    /**
     * Creates a display in [world] between two opposite corner blocks, facing [facing]. The area must be free of other
     * displays; the caller is responsible for the blocks being a suitable wall.
     *
     * @return `null` when the world is unknown or the area overlaps another display.
     */
    fun create(
        owner: UUID,
        world: String,
        x1: Int, y1: Int, z1: Int,
        x2: Int, y2: Int, z2: Int,
        facing: DisplayFacing,
    ): ServerDisplay?
}
