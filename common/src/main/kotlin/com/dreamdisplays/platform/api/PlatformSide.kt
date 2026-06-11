package com.dreamdisplays.platform.api

/**
 * Which logical side of the game a [Platform] runs on. Drives side-aware guards so common code can
 * ask "am I on the client?" without touching loader internals.
 *
 * @since 1.8.0
 */
enum class PlatformSide {
    /** The physical client (rendering, input, the local player). */
    CLIENT,

    /** The dedicated or integrated server (world state, no rendering). */
    SERVER,

    /** Both sides at once, e.g. a singleplayer integrated server. */
    BOTH;

    /** True on [CLIENT] or [BOTH]. */
    val isClient: Boolean get() = this == CLIENT || this == BOTH

    /** True on [SERVER] or [BOTH]. */
    val isServer: Boolean get() = this == SERVER || this == BOTH
}
