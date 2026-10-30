package com.dreamdisplays.api.internal

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.server.DisplayVeto
import com.dreamdisplays.api.server.ServerDisplayEvent
import com.dreamdisplays.api.server.ServerDisplays
import java.util.function.Consumer

/**
 * What the server plugin plugs in behind `Dream Displays` server API.
 *
 * Integrations never use this.
 *
 * @since 1.10.x
 */
@Unstable
interface ServerBackend {
    /** All displays on the server. */
    val displays: ServerDisplays

    /** Subscribes to display events. */
    fun on(listener: Consumer<ServerDisplayEvent>): AutoCloseable

    /** Subscribes to requests that can still be refused. */
    fun onVeto(listener: Consumer<DisplayVeto>): AutoCloseable

    /** Registers and enables an addon. */
    fun registerAddon(addon: Any)
}
