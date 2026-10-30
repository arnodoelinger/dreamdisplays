package com.dreamdisplays.api.server

import com.dreamdisplays.api.CommandResult
import java.util.UUID
import java.util.function.Consumer

/**
 * A change somebody asked for, that a listener may refuse before it happens.
 *
 * Nothing has changed yet when it is delivered; call [deny] to stop it.
 *
 * Example:
 * ```
 * DreamDisplaysServer.vetoes.on<DisplayVeto.SetUrl> { veto ->
 *     if (!veto.url.startsWith("https://")) veto.deny("Only https links are allowed here.")
 * }
 * ```
 *
 * Listeners run on the server thread before the change, so keep them quick. A listener that throws is
 * logged and does not deny. Players see the reason of the first denial; a request from an addon gets
 * [CommandResult.REFUSED].
 *
 * @since 1.10.x
 */
sealed class DisplayVeto {
    /** The player who asked, or `null` when the request came from an addon or the API. */
    abstract val actor: UUID?

    /** The reason of the first denial (may be empty), or `null` while nobody denied. */
    var denial: String? = null
        private set

    /** `true` once a listener denied the request. */
    val isDenied: Boolean get() = denial != null

    /** Refuses the request. [reason] is shown to the player; the first denial wins. */
    fun deny(reason: String = "") {
        if (denial == null) denial = reason
    }

    /** A player or an addon wants to change the video link of [display] to [url]. */
    class SetUrl(
        val display: ServerDisplay,
        val url: String,
        val lang: String,
        override val actor: UUID?,
    ) : DisplayVeto()

    /**
     * A display is about to be created. [display] is the candidate: it has its owner, place and
     * size, but it does not exist yet ([ServerDisplay.isAlive] is `false`).
     */
    class Create(
        val display: ServerDisplay,
        override val actor: UUID?,
    ) : DisplayVeto()

    /** A player or an addon wants to remove [display]. */
    class Delete(
        val display: ServerDisplay,
        override val actor: UUID?,
    ) : DisplayVeto()
}

/**
 * Listen to requests that can still be refused.
 *
 * @since 1.10.x
 */
interface DisplayVetoes {
    /**
     * Calls [listener] before every request of [type] (or a subtype).
     *
     * Close the returned handle to stop.
     */
    fun <T : DisplayVeto> on(type: Class<T>, listener: Consumer<T>): AutoCloseable
}

/**
 * Listens to requests of type [T] and lets you refuse them.
 *
 * @since 1.10.x
 */
inline fun <reified T : DisplayVeto> DisplayVetoes.on(crossinline action: (T) -> Unit): AutoCloseable =
    on(T::class.java, Consumer { action(it) })
