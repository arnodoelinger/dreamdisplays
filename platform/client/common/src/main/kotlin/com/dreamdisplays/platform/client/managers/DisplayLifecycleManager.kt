package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.api.display.model.property.DisplayRotation
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.media.service.keys.MediaServices
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.storage.model.FullDisplayData
import com.dreamdisplays.core.protocol.common.packets.DisplayInfo
import com.dreamdisplays.core.services.DisplayStorage
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.managers.DisplayLifecycleManager.MAX_DISPLAY_BLOCKS
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import com.dreamdisplays.util.FacingUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import org.joml.Vector3i
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.math.sqrt

/**
 * Handles client-side display creation, restoration, and render-distance lifecycle.
 */
object DisplayLifecycleManager {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Maximum allowed display dimension, in blocks. */
    private const val MAX_DISPLAY_BLOCKS = 256

    /** Creates or updates a display from a server [DisplayInfo] packet, honoring render distance and size limits. */
    fun handleInfoPacket(packet: DisplayInfo) {
        if (!ClientStateManager.displaysEnabled) return
        if (!isValidDisplaySize(packet.width, packet.height)) {
            logger.warn("Ignoring display ${packet.id}: invalid size ${packet.width} x ${packet.height}.")
            return
        }

        DisplayRegistry.screens[packet.id]?.let {
            DisplayRegistry.markReconfirmed(packet.id)
            it.updateData(packet)
            DisplayRegistry.recordScreen(it)
            return
        }

        val facing = FacingUtil.fromPacket(packet.facing.toByte())
        val mode = if (packet.mode == PlaybackMode.LOCAL.wire && packet.isSync) {
            PlaybackMode.SYNCED
        } else {
            PlaybackMode.fromWire(packet.mode)
        }
        val renderDistance = DisplayScreen.clientRenderDistanceBlocks()

        if (!packet.forced && !packet.virtual) {
            Minecraft.getInstance().player?.let { player ->
                val dist = distanceToScreen(
                    packet.x, packet.y, packet.z,
                    packet.width, packet.height, facing.toDisplayFacing(),
                    player.blockPosition()
                )
                if (dist > renderDistance) {
                    cacheUnloadedDisplay(packet, facing, mode, currentDimensionKey())
                    return
                }
            }
        }

        DreamServices.registry.getOrNull(MediaServices.RESOLVER_REGISTRY)?.prefetch(MediaSource.from(packet.url))
        DisplayRegistry.unloadedScreens.remove(packet.id)

        createScreen(
            packet.id, packet.ownerId, Vector3i(packet.x, packet.y, packet.z), facing,
            packet.width, packet.height, packet.url, packet.lang,
            mode, packet.qualityCap, DisplayRotation.fromQuarterTurns(packet.rotation),
            currentDimensionKey(),
        )
        DisplayRegistry.screens[packet.id]?.virtual = packet.virtual
    }

    /**
     * Stashes an out-of-range [packet] as an unloaded-screen snapshot (viewer's saved volume / quality /
     * etc. merged in, matching a normal [DisplayScreen.toFullDisplayData] capture), so it restores from
     * the local cache instead of needing another server broadcast once the player is back in range.
     */
    private fun cacheUnloadedDisplay(
        packet: DisplayInfo, facing: FacingUtil, mode: PlaybackMode, dimensionKey: String,
    ) {
        val settings = ClientSettingsStore.getSettings(packet.id, DisplayScreen.defaultVolume())
        DisplayRegistry.unloadedScreens[packet.id] = FullDisplayData(
            uuid = packet.id,
            x = packet.x, y = packet.y, z = packet.z,
            facing = facing.toDisplayFacing(),
            width = packet.width, height = packet.height,
            videoUrl = packet.url, lang = packet.lang,
            volume = settings.volume, quality = settings.quality, brightness = settings.brightness,
            muted = settings.muted, mode = mode, ownerUuid = packet.ownerId,
            currentTimeNanos = settings.savedTimeNanos,
            rotation = DisplayRotation.fromQuarterTurns(packet.rotation).quarterTurns,
            qualityCap = packet.qualityCap,
            dimensionKey = dimensionKey,
        )
    }

    /** Builds and registers a new [DisplayScreen], applying saved render distance and loading the video. */
    fun createScreen(
        uuid: UUID, ownerUuid: UUID, pos: Vector3i, facingUtil: FacingUtil,
        width: Int, height: Int, code: String, lang: String,
        mode: PlaybackMode, qualityCap: Int, rotation: DisplayRotation = DisplayRotation.NONE,
        dimensionKey: String = currentDimensionKey(),
    ) {
        val displayScreen = DisplayScreen(
            uuid, ownerUuid, pos.x(), pos.y(), pos.z(), facingUtil.toDisplayFacing(),
            width, height, mode, qualityCap, rotation, dimensionKey,
        )

        displayScreen.createTexture()
        DisplayRegistry.registerScreen(displayScreen)
        if (code != "") displayScreen.loadVideo(code, lang)

        if (ClientSettingsStore.getSettings(uuid, DisplayScreen.defaultVolume()).pipOpen) {
            displayScreen.activatePipMode()
        }
    }

    fun restoreVisibleUnloadedScreens(playerPos: BlockPos) {
        val dimensionKey = currentDimensionKey()
        val renderDistance = DisplayScreen.clientRenderDistanceBlocks()
        DisplayRegistry.unloadedScreens.values
            .filter { sameDimension(it.dimensionKey, dimensionKey) && distanceToData(it, playerPos) <= renderDistance }
            .toList()
            .forEach { data ->
                DisplayRegistry.unloadedScreens.remove(data.uuid)
                restoreScreen(data)
            }
    }

    private fun sameDimension(cached: String, current: String): Boolean =
        cached.isEmpty() || cached == current

    internal fun currentDimensionKey(): String =
        Minecraft.getInstance().level?.let { dimensionKeyOf(it) } ?: ""

    private fun dimensionKeyOf(level: ClientLevel): String =
        //? if >=1.21.11 {
        level.dimension().identifier().toString()
    //?} else
    /*level.dimension().location().toString()*/

    /** Rebuilds a [DisplayScreen] from persisted [data] and re-registers it. */
    private fun restoreScreen(data: FullDisplayData) {
        if (!isValidDisplaySize(data.width, data.height)) {
            logger.warn("Skipping cached display ${data.uuid}: invalid size ${data.width}x${data.height}.")
            DisplayStorage.removeDisplay(data.uuid)
            return
        }

        val displayScreen = DisplayScreen(
            data.uuid, data.ownerUuid, data.x, data.y, data.z, data.facing,
            data.width, data.height, data.mode ?: PlaybackMode.LOCAL,
            qualityCap = data.qualityCap, rotation = DisplayRotation.fromQuarterTurns(data.rotation),
            dimensionKey = data.dimensionKey.ifEmpty { currentDimensionKey() },
        )
        displayScreen.savedTimeNanos = data.currentTimeNanos
        displayScreen.volume = data.volume
        displayScreen.quality = VideoQuality.parse(data.quality)
        displayScreen.brightness = data.brightness
        displayScreen.muted = data.muted

        displayScreen.createTexture()
        DisplayRegistry.screens[displayScreen.uuid] = displayScreen
        DisplayRegistry.recordScreen(displayScreen)

        if (data.videoUrl.isNotEmpty()) {
            displayScreen.loadVideo(data.videoUrl, data.lang)
        }
    }

    /** Distance from [playerPos] to the persisted display [data]'s bounding box. */
    private fun distanceToData(data: FullDisplayData, playerPos: BlockPos) =
        distanceToScreen(data.x, data.y, data.z, data.width, data.height, data.facing, playerPos)

    /** Shortest distance from [playerPos] to the screen's block bounding box (facing-aware). */
    private fun distanceToScreen(
        x: Int, y: Int, z: Int, width: Int, height: Int, facing: DisplayFacing, playerPos: BlockPos
    ): Double {
        var maxX = x
        var maxY = y + height - 1
        var maxZ = z
        when (facing) {
            DisplayFacing.NORTH, DisplayFacing.SOUTH -> maxX += width - 1
            DisplayFacing.EAST, DisplayFacing.WEST -> maxZ += width - 1
            DisplayFacing.UP, DisplayFacing.DOWN -> {
                maxX += width - 1
                maxZ += height - 1
                maxY = y
            }
        }
        return sqrt(
            playerPos.distSqr(
                BlockPos(
                    minOf(maxOf(playerPos.x, x), maxX),
                    minOf(maxOf(playerPos.y, y), maxY),
                    minOf(maxOf(playerPos.z, z), maxZ)
                )
            )
        )
    }

    /** True if both dimensions are within `1..`[MAX_DISPLAY_BLOCKS]. */
    private fun isValidDisplaySize(width: Int, height: Int): Boolean =
        width in 1..MAX_DISPLAY_BLOCKS && height in 1..MAX_DISPLAY_BLOCKS
}
