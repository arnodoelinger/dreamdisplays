package com.dreamdisplays.api

import java.util.function.Consumer
import java.util.function.Predicate

/**
 * A lazy view over displays that can be narrowed and acted on as a group.
 *
 * The view is evaluated every time it is iterated, so it always reflects the displays
 * that exist right now.
 *
 * Example:
 * ```
 * DreamDisplays.displays
 *     .near(x, y, z, radius = 32.0)
 *     .filter { it.isPlaying }
 *     .pause()
 * ```
 *
 * @since 1.10.x
 */
interface Displays : Iterable<Display> {
    /** Number of displays in this view right now. */
    val size: Int

    /** Keeps only displays within [radius] blocks of the point. */
    fun near(x: Double, y: Double, z: Double, radius: Double): Displays

    /** Keeps only displays that match [predicate]. */
    fun filter(predicate: Predicate<Display>): Displays

    /**
     * Runs [action] for displays that exist now and for every display that appears later.
     *
     * Close the handle to stop.
     */
    fun each(action: Consumer<Display>): AutoCloseable

    /** Plays every display in this view. */
    fun play()

    /** Pauses every display in this view. */
    fun pause()

    /** Stops every display in this view. */
    fun stop()
}
