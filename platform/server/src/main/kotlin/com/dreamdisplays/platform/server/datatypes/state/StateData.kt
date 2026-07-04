package com.dreamdisplays.platform.server.datatypes.state

import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.datatypes.sync.SyncData
import com.dreamdisplays.platform.server.managers.DisplayManager
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.jspecify.annotations.NullMarked
import java.util.*

/**
 * Class to manage the state data of a display.
 *
 * @param id the unique identifier of the display.
 */
@NullMarked
class StateData(private val id: UUID) {
    /** Display data associated with this state. Can be null if the display is not found. */
    @PaperOnly
    private var displayData: PaperDisplayData? = DisplayManager.getDisplayData(id) as? PaperDisplayData

    /** Whether the display is currently paused. */
    private var paused = false

    /** The last reported playback time in nanoseconds. */
    private var lastReportedTime: Long = 0

    /** The timestamp of the last reported time in nanoseconds. */
    private var lastReportedTimestamp: Long = 0

    /** The duration limit of the display in nanoseconds. */
    private var limitTime: Long = 0

    /** Applies a client-reported [SyncData] packet to the local state. */
    fun update(packet: SyncData) {
        paused = packet.currentState
        lastReportedTime = packet.currentTime
        lastReportedTimestamp = System.nanoTime()
        limitTime = packet.limitTime
    }

    /**
     * Builds a fresh [SyncData] packet describing the current playback position,
     * wrapping the time around the display's duration when known.
     */
    @PaperOnly
    fun createPacket(): SyncData {
        val nanos = System.nanoTime()
        val currentTime = if (paused) lastReportedTime
        else lastReportedTime + (nanos - lastReportedTimestamp)

        if (limitTime == 0L) {
            val currentDisplay = (DisplayManager.getDisplayData(id) as? PaperDisplayData) ?: displayData
            displayData = currentDisplay
            currentDisplay?.duration?.let { limitTime = it }
        }

        val time = if (limitTime > 0) currentTime % limitTime else currentTime
        return SyncData(id, true, paused, time, limitTime)
    }

    /**
     * Builds a fresh [SyncData] packet describing the current playback position.
     *
     * @param display optional display data used to seed [limitTime] on first call.
     */
    fun createPacket(display: VanillaDisplayData?): SyncData {
        val nanos = System.nanoTime()
        val currentTime = if (paused) lastReportedTime
        else lastReportedTime + (nanos - lastReportedTimestamp)

        if (limitTime == 0L) display?.duration?.let { limitTime = it }

        val time = if (limitTime > 0) currentTime % limitTime else currentTime
        return SyncData(id, true, paused, time, limitTime)
    }
}
