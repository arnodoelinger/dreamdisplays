package com.dreamdisplays.core.display

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.*
import com.dreamdisplays.core.display.service.impl.DefaultDisplaySystem
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DefaultDisplaySystemTest {
    private val id = DisplayId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    @Test
    fun `recordDisplay stores snapshot and emits created event`() {
        val system = DefaultDisplaySystem()
        val events = mutableListOf<DisplayEvent>()
        system.onDisplayEvent(events::add)

        val display = display()
        system.recordDisplay(display)

        assertEquals(display, system.getDisplay(id))
        assertEquals(listOf(display), system.listDisplays())
        assertIs<DisplayEvent.Created>(events.single())
    }

    @Test
    fun `recordDisplay emits changes for settings state and url`() {
        val system = DefaultDisplaySystem()
        val events = mutableListOf<DisplayEvent>()
        system.recordDisplay(display())
        system.onDisplayEvent(events::add)

        system.recordDisplay(
            display(
                settings = DisplaySettings(volume = 0.5f),
                url = "https://example.test/video",
                state = DisplayRuntimeState.Playing(
                    sessionId = id.uuid.toString(),
                    positionMs = 10,
                    durationMs = 100,
                ),
            ),
        )

        assertEquals(
            listOf(
                DisplayEvent.SettingsChanged::class,
                DisplayEvent.StateChanged::class,
                DisplayEvent.UrlChanged::class,
            ),
            events.map { it::class },
        )
    }

    @Test
    fun `commands refresh the stored snapshot`() {
        val updated = display(settings = DisplaySettings(volume = 0.25f))
        val system = DefaultDisplaySystem(
            object : DisplayCommandExecutor {
                override fun setVolume(displayId: DisplayId, volume: Float): Display =
                    updated.copy(settings = updated.settings.copy(volume = volume))
            },
        )
        system.recordDisplay(display())

        system.setVolume(id, 0.75f)

        assertEquals(0.75f, system.getDisplay(id)?.settings?.volume)
    }

    @Test
    fun `removeDisplay drops the snapshot and emits removed event`() {
        val system = DefaultDisplaySystem()
        val events = mutableListOf<DisplayEvent>()
        system.recordDisplay(display())
        system.onDisplayEvent(events::add)

        system.removeDisplay(id)

        assertNull(system.getDisplay(id))
        assertIs<DisplayEvent.Removed>(events.single())
    }

    private fun display(
        settings: DisplaySettings = DisplaySettings.DEFAULT,
        url: String? = null,
        state: DisplayRuntimeState = DisplayRuntimeState.Idle,
    ): Display = Display(
        id = id,
        bounds = DisplayBounds(1.0, 2.0, 3.0, 4, 5, DisplayFacing.NORTH),
        settings = settings,
        url = url,
        state = state,
    )
}
