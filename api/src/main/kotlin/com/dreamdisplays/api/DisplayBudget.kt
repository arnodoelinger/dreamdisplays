package com.dreamdisplays.api

/**
 * Limits on how much `Dream Displays` may do at once. Displays beyond the limit stay valid but wait,
 * the nearest ones keep playing.
 *
 * @since 1.10.x
 */
interface DisplayBudget {
    /** Most displays that may play at the same time; 0 means no limit. */
    var maxActive: Int

    /** Highest value [maxActive] accepts. */
    val maxActiveLimit: Int
}
