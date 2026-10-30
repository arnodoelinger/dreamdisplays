package com.dreamdisplays.core.api

import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.lifecycle
import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.DisplayInfo
import com.dreamdisplays.api.display.model.property.DisplayBounds
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.property.DisplayState
import com.dreamdisplays.core.services.DefaultDisplayService
import com.dreamdisplays.core.services.DefaultDisplaySystem
import com.dreamdisplays.core.services.DefaultPlaybackService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveDisplayApiTest {
    private val system = DefaultDisplaySystem()
    private val displayService = DefaultDisplayService(system, system)
    private val playbackService = DefaultPlaybackService(system)

    private fun id(n: Int) = DisplayId(UUID.fromString("00000000-0000-0000-0000-00000000000$n"))

    private fun info(n: Int, x: Double = 0.0, state: DisplayState = DisplayState.Idle) = DisplayInfo(
        id = id(n),
        bounds = DisplayBounds(x, 0.0, 0.0, 4, 3, DisplayFacing.NORTH),
        settings = com.dreamdisplays.api.display.model.settings.DisplaySettings.DEFAULT,
        url = null,
        state = state,
    )

    private fun live(n: Int, owner: DefaultSession? = null) = LiveDisplay(id(n), displayService, playbackService, owner)

    private fun playing(ms: Long) = DisplayState.Playing("s", ms, 100_000)

    @Test
    fun `commands on a removed display are stale and harmless`() {
        system.recordDisplay(info(1))
        val display = live(1)
        assertEquals(CommandResult.OK, display.play())

        system.removeDisplay(id(1))

        assertFalse(display.isAlive)
        assertEquals(CommandResult.STALE, display.play())
        assertEquals(CommandResult.STALE, display.seek(1_000))
        display.volume = 0.2f
        assertEquals(DisplayState.Idle, display.state)
    }

    @Test
    fun `on delivers only this display and ends after removal`() {
        system.recordDisplay(info(1))
        system.recordDisplay(info(2))
        val seen = mutableListOf<DisplayEvent>()
        live(1).events.on(DisplayEvent::class.java) { seen.add(it) }

        system.publish(DisplayEvent.UrlChanged(id(2), "other"))
        system.publish(DisplayEvent.UrlChanged(id(1), "mine"))
        system.removeDisplay(id(1))
        system.publish(DisplayEvent.UrlChanged(id(1), "after"))

        assertEquals(["UrlChanged", "Removed"], seen.map { it::class.simpleName })
    }

    @Test
    fun `typed events filter by type and a wider scope hears every display`() {
        system.recordDisplay(info(1))
        system.recordDisplay(info(2))
        val urls = mutableListOf<String?>()
        val everything = mutableListOf<DisplayEvent>()
        val scoped = DefaultEvents(displayService, owner = null, displayId = id(1))
        val global = DefaultEvents(displayService, owner = null, displayId = null)
        scoped.on(DisplayEvent.UrlChanged::class.java) { urls += it.url }
        global.on(DisplayEvent::class.java) { everything += it }

        system.publish(DisplayEvent.UrlChanged(id(1), "a"))
        system.publish(DisplayEvent.UrlChanged(id(2), "b"))
        system.publish(DisplayEvent.CustomDataChanged(id(1), setOf("x:y")))

        assertEquals("a", urls.single())
        assertEquals(3, everything.size)
    }

    @Test
    fun `deprecated on and watch still work through events`() {
        system.recordDisplay(info(1))
        val seen = mutableListOf<DisplayEvent>()
        @Suppress("DEPRECATION")
        live(1).on { seen.add(it) }

        system.publish(DisplayEvent.UrlChanged(id(1), "a"))

        assertEquals(1, seen.size)
    }

    @Test
    fun `lifecycle fires on a change of state kind, not on every tick`() {
        system.recordDisplay(info(1))
        val log = mutableListOf<String>()
        live(1).lifecycle {
            onPlaying { log += "playing" }
            onPaused { log += "paused" }
            onRemoved { log += "removed" }
        }

        system.recordDisplay(info(1, state = playing(1_000)))
        system.recordDisplay(info(1, state = playing(2_000)))
        system.recordDisplay(info(1, state = playing(3_000)))
        system.recordDisplay(info(1, state = DisplayState.Paused("s", 3_000)))
        system.removeDisplay(id(1))

        assertEquals(["playing", "paused", "removed"], log)
    }

    @Test
    fun `closing a session ends its subscriptions in reverse order and is idempotent`() {
        system.recordDisplay(info(1))
        val order = mutableListOf<Int>()
        val session = DefaultSession("test", displayService, playbackService)
        session.own(AutoCloseable { order += 1 })
        session.own(AutoCloseable { order += 2 })
        val log = mutableListOf<DisplayEvent>()
        session.display(id(1))!!.events.on(DisplayEvent::class.java) { log.add(it) }

        system.publish(DisplayEvent.UrlChanged(id(1), "a"))
        session.close()
        session.close()
        system.publish(DisplayEvent.UrlChanged(id(1), "b"))

        assertEquals(1, log.size)
        assertEquals([2, 1], order)
        assertTrue(session.isClosed)
    }

    @Test
    fun `owning on a closed session closes the resource immediately`() {
        val session = DefaultSession("test", displayService, playbackService)
        session.close()
        var closed = false
        session.own(AutoCloseable { closed = true })
        assertTrue(closed)
    }

    @Test
    fun `displays view is lazy and each covers existing and future displays once`() {
        system.recordDisplay(info(1, x = 0.0))
        system.recordDisplay(info(2, x = 100.0))
        val displays = DefaultDisplays(displayService, playbackService, owner = null)
        val nearOrigin = displays.near(0.0, 0.0, 0.0, 10.0)
        assertEquals(1, nearOrigin.size)

        val seen = mutableListOf<DisplayId>()
        val subscription = nearOrigin.each { seen += it.id }
        system.recordDisplay(info(3, x = 5.0))      // joins the view later
        system.recordDisplay(info(4, x = 500.0))    // never matches
        system.recordDisplay(info(3, x = 5.0, state = playing(1)))   // update, not a new display

        assertEquals(2, nearOrigin.size)
        assertEquals([id(1), id(3)], seen)

        subscription.close()
        system.recordDisplay(info(5, x = 1.0))
        assertEquals([id(1), id(3)], seen)
    }

    @Test
    fun `group commands reach every display in the view`() {
        system.recordDisplay(info(1, state = playing(1)))
        system.recordDisplay(info(2, x = 100.0, state = playing(1)))
        val displays = DefaultDisplays(displayService, playbackService, owner = null)

        val playingNearby = displays.near(0.0, 0.0, 0.0, 10.0).filter { it.isPlaying }
        assertEquals([id(1)], playingNearby.map { it.id })
    }
}
