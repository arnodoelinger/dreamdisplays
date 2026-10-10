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
import com.dreamdisplays.platform.client.render.DisplayGeometry
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import com.dreamdisplays.util.FacingUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import org.joml.Vector3i
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Handles client-side display creation, restoration, and render-distance lifecycle.
 */
object DisplayLifecycleManager {
    private val logger = LoggerFactory.getLogger(javaClass)

    private const val MAX_DISPLAY_BLOCKS = 256
    private const val BUDGET_HYSTERESIS_BLOCKS = 4

    /** Creates or updates a display from a server [DisplayInfo] packet, honoring render distance and size limits. */
    fun handleInfoPacket(packet: DisplayInfo) {
        if (!ClientStateManager.displaysEnabled) return
        if (!isValidDisplaySize(packet.width, packet.height, packet.depth)) {
            logger.warn("Ignoring display ${packet.id}: invalid size ${packet.width} x ${packet.height} x ${packet.depth}.")
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
                    player.blockPosition(), packet.depth,
                )
                if (dist > renderDistance || !fitsBudget(dist, player.blockPosition())) {
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
            currentDimensionKey(), packet.depth, packet.conforming,
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
            depth = packet.depth, conforming = packet.conforming,
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
        depth: Int = 1, conforming: Boolean = false,
    ) {
        val displayScreen = DisplayScreen(
            uuid, ownerUuid, pos.x(), pos.y(), pos.z(), facingUtil.toDisplayFacing(),
            width, height, mode, qualityCap, rotation, dimensionKey,
            depth.coerceAtLeast(1), conforming,
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
            .sortedBy { distanceToData(it, playerPos) }
            .forEach { data ->
                if (!fitsBudget(distanceToData(data, playerPos), playerPos)) return@forEach
                DisplayRegistry.unloadedScreens.remove(data.uuid)
                restoreScreen(data)
            }
    }

    /**
     * The displays past the viewer's "max playing displays" setting: everything but the nearest ones.
     *
     * A display already playing keeps its place against one a few blocks nearer, so two at about the
     * same distance do not keep swapping. Popped-out and virtual displays are not counted.
     */
    fun overBudget(playerPos: BlockPos): Set<DisplayScreen> {
        val max = ClientStateManager.config.maxActiveDisplays
        if (max <= 0) return emptySet()
        val counted = DisplayRegistry.getScreens().filter { !it.isPopoutActive && !it.virtual }
        if (counted.size <= max) return emptySet()
        return counted
            .sortedBy { it.getDistanceToScreen(playerPos) + if (it.isDormant) BUDGET_HYSTERESIS_BLOCKS else 0 }
            .drop(max)
            .toSet()
    }

    private fun fitsBudget(distance: Double, playerPos: BlockPos): Boolean {
        val max = ClientStateManager.config.maxActiveDisplays
        if (max <= 0) return true
        val playing = DisplayRegistry.getScreens()
            .filter { !it.isPopoutActive && !it.virtual && !it.isDormant }
            .map { it.getDistanceToScreen(playerPos) }
        return playing.size < max || distance + BUDGET_HYSTERESIS_BLOCKS < playing.max()
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
        if (!isValidDisplaySize(data.width, data.height, data.depth)) {
            logger.warn("Skipping cached display ${data.uuid}: invalid size ${data.width}x${data.height}x${data.depth}.")
            DisplayStorage.removeDisplay(data.uuid)
            return
        }

        val displayScreen = DisplayScreen(
            data.uuid, data.ownerUuid, data.x, data.y, data.z, data.facing,
            data.width, data.height, data.mode ?: PlaybackMode.LOCAL,
            qualityCap = data.qualityCap, rotation = DisplayRotation.fromQuarterTurns(data.rotation),
            dimensionKey = data.dimensionKey.ifEmpty { currentDimensionKey() },
            depth = data.depth.coerceAtLeast(1), conforming = data.conforming,
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
        distanceToScreen(data.x, data.y, data.z, data.width, data.height, data.facing, playerPos, data.depth)

    /** Shortest distance from [playerPos] to the screen's block bounding box (facing-aware). */
    private fun distanceToScreen(
        x: Int, y: Int, z: Int, width: Int, height: Int, facing: DisplayFacing, playerPos: BlockPos, depth: Int = 1,
    ): Double = DisplayGeometry.distanceTo(playerPos, x, y, z, width, height, facing, depth.coerceAtLeast(1))

    /** True if every dimension is within `1..`[MAX_DISPLAY_BLOCKS]. */
    private fun isValidDisplaySize(width: Int, height: Int, depth: Int = 1): Boolean =
        width in 1..MAX_DISPLAY_BLOCKS && height in 1..MAX_DISPLAY_BLOCKS && depth in 1..MAX_DISPLAY_BLOCKS
}
