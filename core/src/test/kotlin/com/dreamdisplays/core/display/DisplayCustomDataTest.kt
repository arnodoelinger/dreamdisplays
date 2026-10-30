package com.dreamdisplays.core.display

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.DisplayInfo
import com.dreamdisplays.api.display.model.property.DisplayBounds
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.property.DisplayState
import com.dreamdisplays.api.display.model.settings.DisplaySettings
import com.dreamdisplays.core.api.LiveDisplay
import com.dreamdisplays.core.protocol.common.PacketRegistry
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo as DisplayInfoPacket
import com.dreamdisplays.core.services.DefaultDisplayService
import com.dreamdisplays.core.services.DefaultDisplaySystem
import com.dreamdisplays.core.services.DefaultPlaybackService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayCustomDataTest {
    private val id = DisplayId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    @Test
    fun `addon data survives the protobuf round trip`() {
        val data = mapOf("sable:binding" to byteArrayOf(1, 2, 3), "other:x" to ByteArray(0))
        val packet = DisplayInfoPacket(id = id.uuid, url = "https://example.test", customData = data)

        val back = PacketRegistry.decode(PacketRegistry.encode(packet)) as DisplayInfoPacket

        assertEquals(data.keys, back.customData.keys)
        assertContentEquals(byteArrayOf(1, 2, 3), back.customData["sable:binding"])
        assertContentEquals(ByteArray(0), back.customData["other:x"])
    }

    @Test
    fun `a packet without addon data decodes to an empty map, as an older server sends it`() {
        val back = PacketRegistry.decode(PacketRegistry.encode(DisplayInfoPacket(id = id.uuid))) as DisplayInfoPacket
        assertTrue(back.customData.isEmpty())
    }

    // ---- custom data events and the live handle ----

    private val system = DefaultDisplaySystem()
    private val display = LiveDisplay(id, DefaultDisplayService(system, system), DefaultPlaybackService(system))

    private fun info(data: Map<String, ByteArray>) = DisplayInfo(
        id = id,
        bounds = DisplayBounds(0.0, 0.0, 0.0, 4, 3, DisplayFacing.NORTH),
        settings = DisplaySettings.DEFAULT,
        url = null,
        state = DisplayState.Idle,
        customData = data,
    )

    @Test
    fun `a change of addon data is announced with exactly the keys that changed`() {
        system.recordDisplay(info(mapOf("a:one" to byteArrayOf(1), "a:two" to byteArrayOf(2))))
        val events = ArrayList<DisplayEvent>()
        system.onDisplayEvent { events += it }

        system.recordDisplay(info(mapOf("a:one" to byteArrayOf(1), "a:two" to byteArrayOf(9), "a:three" to byteArrayOf(3))))
        system.recordDisplay(info(mapOf("a:one" to byteArrayOf(1), "a:two" to byteArrayOf(9), "a:three" to byteArrayOf(3))))

        val changes = events.filterIsInstance<DisplayEvent.CustomDataChanged>()
        assertEquals(1, changes.size)
        assertEquals(setOf("a:two", "a:three"), changes.single().keys)
    }

    @Test
    fun `the live display reads addon data and hands out copies`() {
        system.recordDisplay(info(mapOf("a:one" to byteArrayOf(7))))
        assertEquals(setOf("a:one"), display.customData.keys)
        val copy = display.customData["a:one"]!!
        copy[0] = 0
        assertContentEquals(byteArrayOf(7), display.customData["a:one"])
        assertNull(display.customData["a:missing"])
    }

    @Test
    fun `the lifecycle reports data changes`() {
        system.recordDisplay(info(emptyMap()))
        val seen = ArrayList<Set<String>>()
        display.events.lifecycle { it.onDataChanged { keys -> seen += keys } }

        system.recordDisplay(info(mapOf("a:one" to byteArrayOf(1))))

        assertEquals([setOf("a:one")], seen)
    }
}
