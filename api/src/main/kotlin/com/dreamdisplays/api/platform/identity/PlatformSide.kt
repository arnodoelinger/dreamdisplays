package com.dreamdisplays.api.platform.identity

import com.dreamdisplays.api.Unstable

/**
 * Which logical side of the game a [Platform] runs on. Drives side-aware guards so common code can
 * ask "am I on the client?" without touching loader internals.
 *
 * @since 1.8.x
 */
@Unstable
enum class PlatformSide {
    /** The physical client (rendering, input, local). */
    CLIENT,

    /** The dedicated or integrated server. */
    SERVER,

    /** Both sides at once. */
    BOTH;

    /** True on [CLIENT] or [BOTH]. */
    val isClient: Boolean get() = this == CLIENT || this == BOTH

    /** True on [SERVER] or [BOTH]. */
    val isServer: Boolean get() = this == SERVER || this == BOTH
}
