package com.dreamdisplays.server.datatypes

import com.dreamdisplays.server.managers.DisplayManager
import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly
import org.jspecify.annotations.NullMarked
import java.util.*

/**
 * Class to manage the state data of a display.
 *
 * @param id the unique identifier of the display.
 */
@NullMarked class StateData(private val id: UUID) {
    @PaperOnly private var displayData: PaperDisplayData? = DisplayManager.getDisplayData(id) as? PaperDisplayData
    private var paused = false
    private var lastReportedTime: Long = 0
    private var lastReportedTimestamp: Long = 0
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
    @PaperOnly fun createPacket(): SyncData {
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
    @FabricOnly fun createPacket(display: FabricDisplayData?): SyncData {
        val nanos = System.nanoTime()
        val currentTime = if (paused) lastReportedTime
        else lastReportedTime + (nanos - lastReportedTimestamp)

        if (limitTime == 0L) display?.duration?.let { limitTime = it }

        val time = if (limitTime > 0) currentTime % limitTime else currentTime
        return SyncData(id, true, paused, time, limitTime)
    }
}
