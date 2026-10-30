package com.dreamdisplays.api.server

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.internal.ServerBackend
import com.dreamdisplays.api.internal.ServerHolder
import java.util.UUID
import java.util.function.Consumer

/**
 * The `Dream Displays` API on a server (`Paper`). The client side has `DreamDisplays`.
 *
 * Example:
 * ```
 * DreamDisplaysServer.displays.ownedBy(player.uniqueId).forEach { it.setUrl("https://...") }
 * ```
 * It is available once the plugin has finished loading;
 * use [onReady] if your code may run earlier.
 *
 * @since 1.10.x
 */
@Unstable
object DreamDisplaysServer {
    private val backend: ServerBackend
        get() = ServerHolder.current
            ?: error("Dream Displays server API is not available yet. Use DreamDisplaysServer.onReady { ... }.")

    /** `true` once the plugin has finished loading and the API can be used. */
    @JvmStatic
    val isAvailable: Boolean get() = ServerHolder.current != null

    /** Events of every display on this server. */
    @JvmStatic
    val events: ServerEvents get() = BackendEvents

    /** All displays on this server. */
    @JvmStatic
    val displays: ServerDisplays get() = backend.displays

    /**
     * Runs [action] as soon as the API is available: right away if it already is,
     * otherwise once it loads.
     */
    @JvmStatic
    fun onReady(action: Runnable): Unit = ServerHolder.whenReady { action.run() }

    /** The display with [id], or `null`. */
    @JvmStatic
    fun display(id: UUID): ServerDisplay? = backend.displays[id]

    /**
     * Requests that listeners may refuse before they happen: video changes, creating and
     * removing displays.
     */
    @JvmStatic
    val vetoes: DisplayVetoes get() = BackendVetoes

    /**
     * Subscribes to display events.
     *
     * Close the handle to stop.
     *
     * *Deprecated. Use [events].*
     */
    @Deprecated(
        "Use events.on(type, listener).",
        ReplaceWith("events.on(ServerDisplayEvent::class.java, listener)"),
    )
    @JvmStatic
    fun on(listener: Consumer<ServerDisplayEvent>): AutoCloseable = backend.on(listener)

    /**
     * Registers an addon: an instance of a class annotated with `@Addon` (and optionally implementing
     * `AddonLifecycle`).
     *
     * It is enabled when the API is ready and disabled when the plugin shuts down.
     */
    @JvmStatic
    fun register(addon: Any): Unit = ServerHolder.whenReady { backend.registerAddon(addon) }

    private object BackendVetoes : DisplayVetoes {
        override fun <T : DisplayVeto> on(type: Class<T>, listener: Consumer<T>): AutoCloseable =
            backend.onVeto { veto -> if (type.isInstance(veto)) listener.accept(type.cast(veto)) }
    }

    private object BackendEvents : ServerEvents {
        override fun <T : ServerDisplayEvent> on(type: Class<T>, listener: Consumer<T>): AutoCloseable =
            backend.on { event -> if (type.isInstance(event)) listener.accept(type.cast(event)) }
    }
}
