package com.dreamdisplays.managers

import com.dreamdisplays.api.DisplayFacing
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.displays.DisplayScreen
import com.dreamdisplays.displays.store.DisplayStorage
import com.dreamdisplays.displays.store.FullDisplayData
import com.dreamdisplays.displays.store.ServerDisplayStore
import com.dreamdisplays.media.api.VideoQuality
import com.dreamdisplays.protocol.DisplayInfo
import com.dreamdisplays.protocol.PlaybackMode
import com.dreamdisplays.utils.FacingUtil
import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.media.api.MediaResolverChain
import com.dreamdisplays.media.api.MediaSource
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

    fun handleInfoPacket(packet: DisplayInfo) {
        if (!ClientStateManager.displaysEnabled) return
        if (!isValidDisplaySize(packet.width, packet.height)) {
            logger.warn("Ignoring display ${packet.id}: invalid size ${packet.width} x ${packet.height}.")
            return
        }

        DisplayRegistry.screens[packet.id]?.let {
            it.updateData(packet)
            return
        }

        val facing = FacingUtil.fromPacket(packet.facing.toByte())

        Minecraft.getInstance().player?.let { player ->
            val renderDistance = ServerDisplayStore.getDisplayData(packet.id)?.renderDistance ?: ClientStateManager.config.defaultDistance
            val dist = distanceToScreen(
                packet.x, packet.y, packet.z,
                packet.width, packet.height, facing.toDisplayFacing(),
                player.blockPosition()
            )
            if (dist > renderDistance) return
        }

        DreamServices.registry.getOrNull<MediaResolverChain>()?.prefetch(MediaSource.from(packet.url))
        DisplayRegistry.unloadedScreens.remove(packet.id)

        createScreen(
            packet.id, packet.ownerId, Vector3i(packet.x, packet.y, packet.z), facing,
            packet.width, packet.height, packet.url, packet.lang,
            PlaybackMode.fromWire(packet.mode), packet.qualityCap,
        )
    }

    fun createScreen(
        uuid: UUID, ownerUuid: UUID, pos: Vector3i, facingUtil: FacingUtil,
        width: Int, height: Int, code: String, lang: String,
        mode: PlaybackMode, qualityCap: Int,
    ) {
        val displayScreen = DisplayScreen(
            uuid, ownerUuid, pos.x(), pos.y(), pos.z(), facingUtil.toDisplayFacing(),
            width, height, mode, qualityCap
        )

        val savedData = ServerDisplayStore.getDisplayData(uuid)
        displayScreen.renderDistance = savedData?.renderDistance ?: ClientStateManager.config.defaultDistance

        displayScreen.createTexture()
        DisplayRegistry.registerScreen(displayScreen)
        if (code != "") displayScreen.loadVideo(code, lang)
    }

    fun restoreVisibleUnloadedScreens(playerPos: BlockPos) {
        DisplayRegistry.unloadedScreens.values
            .filter { it.videoUrl.isNotEmpty() && distanceToData(it, playerPos) <= it.renderDistance }
            .toList()
            .forEach { data ->
                DisplayRegistry.unloadedScreens.remove(data.uuid)
                restoreScreen(data)
            }
    }

    private fun restoreScreen(data: FullDisplayData) {
        if (!isValidDisplaySize(data.width, data.height)) {
            logger.warn("Skipping cached display ${data.uuid}: invalid size ${data.width}x${data.height}.")
            DisplayStorage.removeDisplay(data.uuid)
            return
        }

        val displayScreen = DisplayScreen(
            data.uuid, data.ownerUuid, data.x, data.y, data.z, data.facing,
            data.width, data.height, data.mode ?: PlaybackMode.LOCAL
        )
        displayScreen.renderDistance = data.renderDistance
        displayScreen.savedTimeNanos = data.currentTimeNanos
        displayScreen.volume = data.volume
        displayScreen.quality = VideoQuality.parse(data.quality)
        displayScreen.brightness = data.brightness
        displayScreen.muted = data.muted

        displayScreen.createTexture()
        DisplayRegistry.screens[displayScreen.uuid] = displayScreen

        if (data.videoUrl.isNotEmpty()) {
            displayScreen.loadVideo(data.videoUrl, data.lang)
        }
    }

    private fun distanceToData(data: FullDisplayData, playerPos: BlockPos) =
        distanceToScreen(data.x, data.y, data.z, data.width, data.height, data.facing, playerPos)

    private fun distanceToScreen(
        x: Int, y: Int, z: Int, width: Int, height: Int, facing: DisplayFacing, playerPos: BlockPos
    ): Double {
        var maxX = x
        val maxY = y + height - 1
        var maxZ = z
        when (facing) {
            DisplayFacing.NORTH, DisplayFacing.SOUTH -> maxX += width - 1
            DisplayFacing.EAST, DisplayFacing.WEST -> maxZ += width - 1
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
