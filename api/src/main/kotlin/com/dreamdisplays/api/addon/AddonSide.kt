package com.dreamdisplays.api.addon

/**
 * Where an addon runs.
 *
 * @since 1.10.x
 */
enum class AddonSide {
    /** In the game client (`Fabric` / `NeoForge`). */
    CLIENT,

    /** On the server (`Paper` plugin). */
    SERVER,

    /** On both; it is enabled wherever `Dream Displays` runs. */
    BOTH,
}
