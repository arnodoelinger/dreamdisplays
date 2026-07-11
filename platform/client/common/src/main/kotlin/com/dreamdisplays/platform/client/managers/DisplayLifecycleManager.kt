package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.api.display.model.DisplayFacing
import com.dreamdisplays.api.display.model.ContentRotation
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.core.storage.DisplayStorage
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import com.dreamdisplays.api.storage.FullDisplayData
import com.dreamdisplays.api.media.VideoQuality
import com.dreamdisplays.core.protocol.DisplayInfo
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.util.FacingUtil
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.api.runtime.getOrNull
import com.dreamdisplays.api.media.MediaServices
import com.dreamdisplays.api.media.source.MediaSource
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
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/DisplayLifecycleManager")

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
        val renderDistance = DisplayStorage.getDisplayData(packet.id)?.renderDistance
            ?: persistedRenderDistance(packet.id)

        if (!packet.forced && !packet.virtual) {
            Minecraft.getInstance().player?.let { player ->
                val dist = distanceToScreen(
                    packet.x, packet.y, packet.z,
                    packet.width, packet.height, facing.toDisplayFacing(),
                    player.blockPosition()
                )
                if (dist > renderDistance) {
                    cacheUnloadedDisplay(packet, facing, mode, renderDistance)
                    return
                }
            }
        }

        DreamServices.registry.getOrNull(MediaServices.RESOLVER_REGISTRY)?.prefetch(MediaSource.from(packet.url))
        DisplayRegistry.unloadedScreens.remove(packet.id)

        createScreen(
            packet.id, packet.ownerId, Vector3i(packet.x, packet.y, packet.z), facing,
            packet.width, packet.height, packet.url, packet.lang,
            mode, packet.qualityCap, ContentRotation.fromQuarterTurns(packet.rotation),
        )
    }

    /**
     * Stashes an out-of-range [packet] as an unloaded-screen snapshot (viewer's saved volume / quality /
     * etc. merged in, matching a normal [DisplayScreen.toFullDisplayData] capture), so it restores from
     * the local cache instead of needing another server broadcast once the player is back in range.
     */
    private fun cacheUnloadedDisplay(packet: DisplayInfo, facing: FacingUtil, mode: PlaybackMode, renderDistance: Int) {
        val settings = ClientSettingsStore.getSettings(packet.id, DisplayScreen.defaultVolume())
        DisplayRegistry.unloadedScreens[packet.id] = FullDisplayData(
            uuid = packet.id,
            x = packet.x, y = packet.y, z = packet.z,
            facing = facing.toDisplayFacing(),
            width = packet.width, height = packet.height,
            videoUrl = packet.url, lang = packet.lang,
            volume = settings.volume, quality = settings.quality, brightness = settings.brightness,
            muted = settings.muted, mode = mode, ownerUuid = packet.ownerId,
            renderDistance = renderDistance, currentTimeNanos = settings.savedTimeNanos,
            rotation = ContentRotation.fromQuarterTurns(packet.rotation).quarterTurns,
            qualityCap = packet.qualityCap,
        )
    }

    /** Builds and registers a new [DisplayScreen], applying saved render distance and loading the video. */
    fun createScreen(
        uuid: UUID, ownerUuid: UUID, pos: Vector3i, facingUtil: FacingUtil,
        width: Int, height: Int, code: String, lang: String,
        mode: PlaybackMode, qualityCap: Int, rotation: ContentRotation = ContentRotation.NONE,
    ) {
        val displayScreen = DisplayScreen(
            uuid, ownerUuid, pos.x(), pos.y(), pos.z(), facingUtil.toDisplayFacing(),
            width, height, mode, qualityCap, rotation
        )

        val savedData = DisplayStorage.getDisplayData(uuid)
        displayScreen.renderDistance = savedData?.renderDistance ?: persistedRenderDistance(uuid)

        displayScreen.createTexture()
        DisplayRegistry.registerScreen(displayScreen)
        if (code != "") displayScreen.loadVideo(code, lang)
    }

    /**
     * Restores any softly-unloaded screens that are back within render distance of [playerPos].
     * Screens with no video (idle, or an out-of-range first sighting) are restored too — restoreScreen
     * already handles an empty [FullDisplayData.videoUrl] fine — so they don't stay invisible forever.
     */
    fun restoreVisibleUnloadedScreens(playerPos: BlockPos) {
        DisplayRegistry.unloadedScreens.values
            .filter { distanceToData(it, playerPos) <= it.renderDistance }
            .toList()
            .forEach { data ->
                DisplayRegistry.unloadedScreens.remove(data.uuid)
                restoreScreen(data)
            }
    }

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
            qualityCap = data.qualityCap, rotation = ContentRotation.fromQuarterTurns(data.rotation),
        )
        displayScreen.renderDistance = data.renderDistance
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

    /** The viewer's disk-persisted render distance for [uuid], or the config default if never customized. */
    private fun persistedRenderDistance(uuid: UUID): Int {
        val saved = ClientSettingsStore.getSettings(uuid).renderDistance
        return if (saved > 0) saved else ClientStateManager.config.defaultDistance
    }
}
