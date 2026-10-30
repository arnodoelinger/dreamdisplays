package com.dreamdisplays.core.api

import com.dreamdisplays.api.Display
import com.dreamdisplays.api.Displays
import com.dreamdisplays.api.Session
import com.dreamdisplays.api.display.event.Events
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.core.services.DisplayService
import com.dreamdisplays.core.services.PlaybackService

/** Owner of subscriptions: closing it closes everything it owns, in reverse order. */
internal class DefaultSession(
    override val name: String,
    private val displayService: DisplayService,
    private val playbackService: PlaybackService,
) : Session {
    private val owned = ArrayDeque<AutoCloseable>()

    @Volatile
    override var isClosed: Boolean = false
        private set

    override val events: Events = DefaultEvents(displayService, this, null)

    override val displays: Displays get() = DefaultDisplays(displayService, playbackService, this)

    override fun display(id: DisplayId): Display? {
        check(!isClosed) { "Session \"$name\" is closed." }
        return displayService.getDisplay(id)?.let { LiveDisplay(id, displayService, playbackService, this) }
    }

    @Synchronized
    override fun <T : AutoCloseable> own(resource: T): T {
        if (isClosed) {
            resource.close()
        } else {
            owned.addLast(resource)
        }
        return resource
    }

    override fun close() {
        val toClose = synchronized(this) {
            if (isClosed) return
            isClosed = true
            owned.toList().asReversed().also { owned.clear() }
        }
        toClose.forEach { runCatching { it.close() } }
    }

    override fun toString(): String = "Session ($name${if (isClosed) ", closed" else ""})"
}
