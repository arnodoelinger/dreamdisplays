package com.dreamdisplays.core.api

import com.dreamdisplays.api.DisplayLifecycle
import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.event.DisplayEvents
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.core.services.DisplayService
import java.util.function.Consumer

/**
 * [DisplayEvents] over the display service.
 *
 * With a [displayId] it hears only that display and ends when it is removed; with an [owner]
 * every subscription ends with the session.
 */
internal class DefaultEvents(
    private val displayService: DisplayService,
    private val owner: DefaultSession?,
    private val displayId: DisplayId?,
) : DisplayEvents {
    override fun <T : DisplayEvent> on(type: Class<T>, listener: Consumer<T>): AutoCloseable {
        lateinit var subscription: Subscription
        val upstream = displayService.on { event ->
            if (subscription.isClosed || (displayId != null && event.displayId != displayId)) return@on
            if (type.isInstance(event)) listener.accept(type.cast(event))
            if (displayId != null && event is DisplayEvent.Removed) subscription.close()
        }
        subscription = Subscription { upstream.close() }
        owner?.own(subscription)
        return subscription
    }

    override fun lifecycle(configure: Consumer<DisplayLifecycle>): AutoCloseable {
        val handlers = LifecycleHandlers().also { configure.accept(it) }
        return on(DisplayEvent::class.java) { handlers.dispatch(it) }
    }
}
