package com.dreamdisplays.managers

import com.dreamdisplays.display.DisplayManager
import com.dreamdisplays.display.DisplayScreen
import com.dreamdisplays.display.DisplaySettings
import com.dreamdisplays.net.Packets
import com.dreamdisplays.utils.FacingUtil
import com.dreamdisplays.ytdlp.YtDlp
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import org.joml.Vector3i
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.math.sqrt

/**
 * Handles client-side display creation, restoration, and render-distance lifecycle.
 */
object DisplayLifecycleManager {
    private val logger = LoggerFactory.getLogger("DreamDisplays/DisplayLifecycleManager")

    private const val MAX_DISPLAY_BLOCKS = 256

    fun handleInfoPacket(packet: Packets.Info) {
        if (!ClientStateManager.displaysEnabled) return
        if (!isValidDisplaySize(packet.width, packet.height)) {
            logger.warn("Ignoring display ${packet.uuid}: invalid size ${packet.width}x${packet.height}.")
            return
        }

        DisplayManager.screens[packet.uuid]?.let {
            it.updateData(packet)
            return
        }

        Minecraft.getInstance().player?.let { player ->
            val renderDistance = DisplaySettings.getDisplayData(packet.uuid)?.renderDistance ?: ClientStateManager.config.defaultDistance
            val dist = distanceToScreen(
                packet.pos.x, packet.pos.y, packet.pos.z,
                packet.width, packet.height, packet.facingUtil.toString(),
                player.blockPosition()
            )
            if (dist > renderDistance) return
        }

        YtDlp.prefetchFormats(packet.url)
        DisplayManager.unloadedScreens.remove(packet.uuid)

        createScreen(
            packet.uuid, packet.ownerUuid, packet.pos, packet.facingUtil,
            packet.width, packet.height, packet.url, packet.lang, packet.isSync
        )
    }

    fun createScreen(
        uuid: UUID, ownerUuid: UUID, pos: Vector3i, facingUtil: FacingUtil,
        width: Int, height: Int, code: String, lang: String, isSync: Boolean,
    ) {
        val displayScreen = DisplayScreen(
            uuid, ownerUuid, pos.x(), pos.y(), pos.z(), facingUtil.toString(),
            width, height, isSync
        )

        val savedData = DisplaySettings.getDisplayData(uuid)
        displayScreen.renderDistance = savedData?.renderDistance ?: ClientStateManager.config.defaultDistance

        displayScreen.createTexture()
        DisplayManager.registerScreen(displayScreen)
        if (code != "") displayScreen.loadVideo(code, lang)
    }

    fun restoreVisibleUnloadedScreens(playerPos: BlockPos) {
        DisplayManager.unloadedScreens.values
            .filter { it.videoUrl.isNotEmpty() && distanceToData(it, playerPos) <= it.renderDistance }
            .toList()
            .forEach { data ->
                DisplayManager.unloadedScreens.remove(data.uuid)
                restoreScreen(data)
            }
    }

    private fun restoreScreen(data: DisplaySettings.FullDisplayData) {
        if (!isValidDisplaySize(data.width, data.height)) {
            logger.warn("Skipping cached display ${data.uuid}: invalid size ${data.width}x${data.height}.")
            DisplaySettings.removeDisplay(data.uuid)
            return
        }

        val displayScreen = DisplayScreen(
            data.uuid, data.ownerUuid, data.x, data.y, data.z, data.facing,
            data.width, data.height, data.isSync
        )
        displayScreen.renderDistance = data.renderDistance
        displayScreen.savedTimeNanos = data.currentTimeNanos
        displayScreen.volume = data.volume
        displayScreen.quality = data.quality
        displayScreen.brightness = data.brightness
        displayScreen.muted = data.muted

        displayScreen.createTexture()
        DisplayManager.screens[displayScreen.uuid] = displayScreen

        if (data.videoUrl.isNotEmpty()) {
            displayScreen.loadVideo(data.videoUrl, data.lang)
        }
    }

    private fun distanceToData(data: DisplaySettings.FullDisplayData, playerPos: BlockPos) =
        distanceToScreen(data.x, data.y, data.z, data.width, data.height, data.facing, playerPos)

    private fun distanceToScreen(
        x: Int, y: Int, z: Int, width: Int, height: Int, facing: String, playerPos: BlockPos
    ): Double {
        var maxX = x
        val maxY = y + height - 1
        var maxZ = z
        when (facing) {
            "NORTH", "SOUTH" -> maxX += width - 1
            "EAST", "WEST" -> maxZ += width - 1
        }
        return sqrt(playerPos.distSqr(BlockPos(
            minOf(maxOf(playerPos.x, x), maxX),
            minOf(maxOf(playerPos.y, y), maxY),
            minOf(maxOf(playerPos.z, z), maxZ)
        )))
    }

    private fun isValidDisplaySize(width: Int, height: Int): Boolean =
        width in 1..MAX_DISPLAY_BLOCKS && height in 1..MAX_DISPLAY_BLOCKS
}
