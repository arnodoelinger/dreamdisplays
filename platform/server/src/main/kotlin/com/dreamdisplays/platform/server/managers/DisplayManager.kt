package com.dreamdisplays.platform.server.managers

import io.github.arnodoelinger.platformweaver.PaperOnly

import com.dreamdisplays.core.protocol.DreamPacket
import com.dreamdisplays.platform.server.PaperServer.Companion.config
import com.dreamdisplays.platform.server.PaperServer.Companion.getInstance
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.datatypes.selection.VanillaSelectionData
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.selection.PaperSelectionData
import com.dreamdisplays.platform.server.datatypes.sync.SyncData
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.meta.Scheduler.runSync
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import kotlinx.coroutines.launch
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.PlatformUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.RegionUtil.calculateRegion
import com.dreamdisplays.platform.server.utils.ReporterUtil
import com.dreamdisplays.platform.server.utils.ReporterUtil.sendReport
import com.dreamdisplays.platform.server.utils.net.VanillaPacketUtil
import com.dreamdisplays.platform.server.utils.net.PacketUtil
import com.dreamdisplays.platform.server.utils.net.PacketUtil.sendDelete
import com.dreamdisplays.platform.server.utils.net.PaperV2Networking
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.Bukkit.getOfflinePlayer
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.jspecify.annotations.NullMarked
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Manages all displays on the server. Handles registration, deletion, overlap detection,
 * receiver lookup, and report rate-limiting.
 */
@NullMarked
object DisplayManager {
    /** In-memory registry of all displays, keyed by UUID. */
    private val displays: MutableMap<UUID, DisplayData> = ConcurrentHashMap()

    /** Throttles reports to prevent spam. */
    private val reportThrottle = ReportThrottle()

    /** Proximity index for tracking nearby players in Folia. */
    private val proximityIndex = DisplayProximityIndex()

    /** Returns the display registered under [id], or null if none exists. */
    @JvmStatic
    fun getDisplayData(id: UUID?): DisplayData? = displays[id]

    /** Returns a snapshot list of all currently registered displays. */
    fun getDisplays(): List<DisplayData> = displays.values.toList()

    /** Bulk-registers displays loaded from storage without sending any updates. */
    fun register(list: List<DisplayData>) {
        list.forEach { displays[it.id] = it }
    }

    /** Removes the display referenced by [id], if it exists. */
    @JvmStatic
    fun delete(id: UUID) {
        val data = displays[id] ?: return
        when (data) {
            is PaperDisplayData -> delete(data)
            is VanillaDisplayData -> delete(data)
        }
    }

    /** Returns true when [x,y,z] is within [maxRender] blocks of the axis-aligned box defined by the given bounds. */
    private fun isInRangeImpl(
        x: Int, y: Int, z: Int,
        minX: Int, minY: Int, minZ: Int,
        maxX: Int, maxY: Int, maxZ: Int,
        maxRender: Double,
    ): Boolean {
        val dx = x - x.coerceIn(minX, maxX)
        val dy = y - y.coerceIn(minY, maxY)
        val dz = z - z.coerceIn(minZ, maxZ)
        return dx * dx + dy * dy + dz * dz <= maxRender * maxRender
    }

    /**
     * Removes every display in [toRemove] from the in-memory registry (and its playback/timeline/
     * watch-party state, same as [delete]), invokes [delete] for each, and returns the removed UUIDs.
     */
    private fun removeDisplays(toRemove: List<DisplayData>, delete: (DisplayData) -> Unit): List<UUID> {
        return toRemove.map { display ->
            displays.remove(display.id)
            proximityIndex.forgetDisplay(display.id)
            TimelineManager.remove(display.id)
            WatchPartyManager.remove(display.id)
            FullscreenBroadcastManager.onDisplayRemoved(display.id)
            StateManager.remove(display.id)
            delete(display)
            display.id
        }
    }

    /** Returns the first display whose bounding box contains [location], or null. */
    @PaperOnly
    fun isContains(location: Location): PaperDisplayData? {
        return displays.values.filterIsInstance<PaperDisplayData>().firstOrNull { d ->
            d.pos1.world == location.world && d.box.contains(location.toVector())
        }
    }

    /** Returns true if the selection [data] intersects any existing display. */
    @PaperOnly
    fun isOverlaps(data: PaperSelectionData): Boolean {
        val pos1 = data.pos1 ?: return false
        val pos2 = data.pos2 ?: return false
        val selWorld = pos1.world
        val region = calculateRegion(pos1, pos2)
        val box = BoundingBox(
            region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
            (region.maxX + 1).toDouble(), (region.maxY + 1).toDouble(), (region.maxZ + 1).toDouble(),
        )
        return displays.values.filterIsInstance<PaperDisplayData>().any { d ->
            d.pos1.world == selWorld && box.overlaps(d.box)
        }
    }

    /** Registers a new display, persists it, and broadcasts an update to nearby players. */
    @PaperOnly
    fun register(data: PaperDisplayData) {
        displays[data.id] = data
        runAsync { getInstance().storage.saveDisplay(data) }
        broadcastUpdate(data)
    }

    /** Returns the players currently in range of [display] in its world. */
    @PaperOnly
    fun getReceivers(display: PaperDisplayData): List<Player> =
        display.pos1.world?.players?.filter { it.isInRange(display) } ?: emptyList()

    /** Returns true if this location lies within `maxRenderDistance` of the [display]'s box. */
    @PaperOnly
    private fun Location.isInRange(display: PaperDisplayData): Boolean =
        isInRangeImpl(
            blockX, blockY, blockZ,
            display.box.minX.toInt(), display.box.minY.toInt(), display.box.minZ.toInt(),
            display.box.maxX.toInt(), display.box.maxY.toInt(), display.box.maxZ.toInt(),
            config.settings.maxRenderDistance,
        )

    /** Returns true if [player] is in [display]'s world and within render range. Must run on the player's thread on Folia. */
    @PaperOnly
    private fun Player.isInRange(display: PaperDisplayData): Boolean {
        if (display.pos1.world != world) return false
        return location.isInRange(display)
    }

    /** Removes [playerId] from the cached Folia proximity index. */
    @PaperOnly
    fun forgetNearbyPlayer(playerId: UUID) = proximityIndex.forgetPlayer(playerId)

    /** Cached nearby player ids for Folia global coordinators that cannot read entity locations directly. */
    @PaperOnly
    fun getTrackedNearbyPlayerIds(display: PaperDisplayData): List<UUID> = proximityIndex.trackedNearbyPlayerIds(display.id)

    /** Sends a `DisplayInfo` packet describing [display] to the given [players]. */
    @PaperOnly
    fun sendUpdate(display: PaperDisplayData, players: List<Player>, forced: Boolean = false) {
        PacketUtil.sendDisplayInfo(
            players,
            display.id, display.ownerId, display.box.min, display.width, display.height,
            display.url, display.lang, display.facing, display.isSync, display.isLocked,
            display.mode, display.qualityCap, display.rotation,
            virtual = display.virtual, forced = forced,
        )
    }

    /** Broadcasts [display]'s current info through the appropriate Paper/Folia player scheduler path. */
    @PaperOnly
    fun broadcastUpdate(display: PaperDisplayData) {
        if (PlatformUtil.isFolia) {
            Scheduler.forEachTrackedPlayer { player ->
                if (player.isInRange(display)) sendUpdate(display, listOf(player))
            }
        } else {
            sendUpdate(display, getReceivers(display))
        }
    }

    /** Broadcasts a display delete packet through the appropriate Paper/Folia player scheduler path. */
    @PaperOnly
    fun broadcastDelete(display: PaperDisplayData) {
        if (PlatformUtil.isFolia) {
            Scheduler.forEachTrackedPlayer { player ->
                if (player.isInRange(display)) sendDelete(listOf(player), display.id)
            }
        } else {
            sendDelete(getReceivers(display), display.id)
        }
    }

    /** Refreshes all displays visible to every tracked player through each player's entity scheduler. */
    @PaperOnly
    fun updateAllDisplaysForTrackedPlayers() {
        Scheduler.forEachTrackedPlayer { player ->
            val visible = displays.values.filterIsInstance<PaperDisplayData>()
                .filter { player.isInRange(it) }
            proximityIndex.update(player.uniqueId, visible.mapTo(mutableSetOf()) { it.id })
            visible.forEach { display -> sendUpdate(display, listOf(player)) }
        }
    }

    /** Sends a legacy sync packet to tracked nearby v1 players, evaluating each location on that player's entity thread. */
    @PaperOnly
    fun sendLegacySyncToTrackedNearbyPlayers(
        display: PaperDisplayData,
        packet: SyncData,
        excludedPlayerId: UUID? = null,
    ) {
        Scheduler.forEachTrackedPlayer { player ->
            if (player.uniqueId != excludedPlayerId && !V2PlayerTracker.isV2(player.uniqueId) && player.isInRange(
                    display
                )
            ) {
                PacketUtil.sendSync(listOf(player), packet)
            }
        }
    }

    /** Sends a v2 packet to tracked nearby v2 players, evaluating each location on that player's entity thread. */
    @PaperOnly
    fun sendV2ToTrackedNearbyPlayers(display: PaperDisplayData, packet: DreamPacket) {
        Scheduler.forEachTrackedPlayer { player ->
            if (V2PlayerTracker.isV2(player.uniqueId) && player.isInRange(display)) {
                PaperV2Networking.send(listOf(player), packet)
            }
        }
    }

    /** Sends a refresh packet for every display to in-range players in their respective worlds. */
    @PaperOnly
    fun updateAllDisplays() {
        val papers = displays.values.filterIsInstance<PaperDisplayData>()
        val playersByWorld = papers.mapNotNull { it.pos1.world }.distinct()
            .associateWith { it.players.toMutableList() }

        papers.forEach { display ->
            val world = display.pos1.world ?: return@forEach
            val worldPlayers = playersByWorld[world] ?: mutableListOf()
            val receivers = worldPlayers.filter { it.location.isInRange(display) }
            sendUpdate(display, receivers)
        }
    }

    /** Removes [displayData] from storage and the registry and notifies nearby clients. */
    @PaperOnly
    fun delete(displayData: PaperDisplayData) {
        runAsync { getInstance().storage.deleteDisplay(displayData) }
        broadcastDelete(displayData)
        TimelineManager.remove(displayData.id)
        WatchPartyManager.remove(displayData.id)
        FullscreenBroadcastManager.onDisplayRemoved(displayData.id)
        StateManager.remove(displayData.id)
        displays.remove(displayData.id)
        proximityIndex.forgetDisplay(displayData.id)
    }

    /**
     * Posts a report about display [id] to the configured webhook, respecting per-display cooldown
     * and informing [player] about the outcome.
     */
    @PaperOnly
    @JvmStatic
    fun report(id: UUID, player: Player) {
        val displayData = displays[id] as? PaperDisplayData ?: return
        if (reportThrottle.isThrottled(id, player.uniqueId, config.settings.reportCooldown.toLong())) {
            MessageUtil.sendMessage(player, "reportTooQuickly")
            return
        }
        runAsync {
            try {
                if (config.settings.webhookUrl.isEmpty()) return@runAsync
                sendReport(
                    displayData.pos1, displayData.url, displayData.id, player,
                    config.settings.webhookUrl, getOfflinePlayer(displayData.ownerId).name,
                )
                runSync { MessageUtil.sendMessage(player, "reportSent") }
            } catch (e: Exception) {
                getInstance().logger.warning("Exception while sending report: ${e.message}")
                runSync { MessageUtil.sendMessage(player, "reportFailed") }
            }
        }
    }

    /** Invokes [saveDisplay] for every currently registered display (used by storage flush). */
    @PaperOnly
    fun save(saveDisplay: Consumer<PaperDisplayData>) {
        displays.values.filterIsInstance<PaperDisplayData>().forEach(saveDisplay)
    }

    /**
     * Scans every display's bounding box for the configured base material; displays with none
     * are removed from disk and memory, and every online player is told to forget them (matching
     * what a normal [delete] does — [removeDisplays] alone only updates storage and the registry).
     * Returns the UUIDs of removed displays.
     */
    @PaperOnly
    fun validateDisplaysAndCleanup(): List<UUID> {
        val baseMaterial = config.settings.baseMaterial
        val invalidDisplays = mutableListOf<PaperDisplayData>()

        displays.values.filterIsInstance<PaperDisplayData>().forEach { display ->
            // An unloaded world (e.g. a Multiverse world that loads later) is not an invalid display:
            // skip it this pass instead of wiping it from the database.
            val world = display.pos1.world
            if (world == null) {
                getInstance().logger.warning("Skipping validation for display ${display.id}: world is not loaded.")
                return@forEach
            }

            var hasBaseMaterial = false
            val minX = display.box.minX.toInt()
            val minY = display.box.minY.toInt()
            val minZ = display.box.minZ.toInt()
            val maxX = display.box.maxX.toInt()
            val maxY = display.box.maxY.toInt()
            val maxZ = display.box.maxZ.toInt()

            outerLoop@ for (x in minX until maxX) {
                for (y in minY until maxY) {
                    for (z in minZ until maxZ) {
                        if (world.getBlockAt(x, y, z).type == baseMaterial) {
                            hasBaseMaterial = true
                            break@outerLoop
                        }
                    }
                }
            }
            if (!hasBaseMaterial) invalidDisplays.add(display)
        }

        val removed = removeDisplays(invalidDisplays) { display ->
            runAsync { getInstance().storage.deleteDisplay(display as PaperDisplayData) }
        }
        if (removed.isNotEmpty()) {
            PacketUtil.sendClearCache(Bukkit.getOnlinePlayers().toList(), removed)
        }
        return removed
    }

    /** Returns the first display whose bounding box contains [blockPos] in [worldKey]. */
    fun isContains(worldKey: String, blockPos: BlockPos): VanillaDisplayData? {
        return displays.values.filterIsInstance<VanillaDisplayData>().firstOrNull { d ->
            d.worldKey == worldKey &&
                    d.box.contains(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        }
    }

    /** Returns true if the selection [sel] intersects any existing display. */
    fun isOverlaps(sel: VanillaSelectionData): Boolean {
        val selBox = sel.selectionBox() ?: return false
        val wk = sel.worldKey ?: return false
        return displays.values.filterIsInstance<VanillaDisplayData>().any { d ->
            d.worldKey == wk && d.box.intersects(selBox)
        }
    }

    /** Registers a new display. Caller is responsible for broadcasting. */
    fun register(data: VanillaDisplayData) {
        displays[data.id] = data
    }

    /** Returns the players currently in range of [display] in its world. */
    fun getReceivers(display: VanillaDisplayData, server: MinecraftServer): List<ServerPlayer> {
        return server.playerList.players.filter { p ->
            RegionUtil.getPlayerLevelKey(p) == display.worldKey &&
                    p.blockPosition().isInRange(display)
        }
    }

    /** Returns true if this block position lies within `maxRenderDistance` of the [display]'s box. */
    private fun BlockPos.isInRange(display: VanillaDisplayData): Boolean =
        isInRangeImpl(
            x, y, z,
            display.minX, display.minY, display.minZ,
            display.maxX, display.maxY, display.maxZ,
            VanillaServerState.config.settings.maxRenderDistance,
        )

    /** Sends a `DisplayInfo` packet describing [display] to the given [players]. */
    fun sendUpdate(display: VanillaDisplayData, players: List<ServerPlayer>) {
        VanillaPacketUtil.sendDisplayInfo(players, display)
    }

    /** Sends a refresh packet for every display to in-range players. */
    fun updateAllDisplays(server: MinecraftServer) {
        displays.values.filterIsInstance<VanillaDisplayData>().forEach { display ->
            val receivers = getReceivers(display, server)
            if (receivers.isNotEmpty()) sendUpdate(display, receivers)
        }
    }

    /** Removes [data] from storage and the registry. The JDBC delete runs off-thread on [ServerCoroutines.io]. */
    fun delete(data: VanillaDisplayData) {
        val receivers = VanillaServerState.server?.let { getReceivers(data, it) }.orEmpty()
        displays.remove(data.id)
        TimelineManager.remove(data.id)
        WatchPartyManager.remove(data.id)
        FullscreenBroadcastManager.onDisplayRemoved(data.id)
        StateManager.remove(data.id)
        ServerCoroutines.io.launch { VanillaServerState.storage?.deleteDisplay(data) }
        if (receivers.isNotEmpty()) VanillaPacketUtil.sendDelete(receivers, data.id)
    }

    /**
     * Posts a report about display [id] to the configured webhook, respecting per-display cooldown
     * and informing [player] about the outcome.
     */
    fun report(id: UUID, player: ServerPlayer, server: MinecraftServer) {
        val displayData = displays[id] as? VanillaDisplayData ?: return
        val cfg = VanillaServerState.config
        if (reportThrottle.isThrottled(id, player.uuid, cfg.settings.reportCooldown)) {
            MessageUtil.sendMessage(player, "reportTooQuickly")
            return
        }
        if (cfg.settings.webhookUrl.isEmpty()) return

        val ownerName = server.playerList.players.find { it.uuid == displayData.ownerId }?.name?.string ?: "Unknown"
        val locationStr =
            "${displayData.worldKey} (x=${displayData.minX}, y=${displayData.minY}, z=${displayData.minZ})"

        ServerCoroutines.io.launch {
            runCatching {
                ReporterUtil.sendReport(
                    locationStr,
                    displayData.url,
                    displayData.id,
                    player.name.string,
                    ownerName,
                    cfg.settings.webhookUrl,
                )
                server.execute { MessageUtil.sendMessage(player, "reportSent") }
            }.onFailure {
                server.execute { MessageUtil.sendMessage(player, "reportFailed") }
            }
        }
    }

    /** Invokes [saveDisplay] for every currently registered display (used by storage flush). */
    fun save(saveDisplay: (VanillaDisplayData) -> Unit) {
        displays.values.filterIsInstance<VanillaDisplayData>().forEach(saveDisplay)
    }

    /**
     * Scans every display's bounding box for the configured base material; displays with none
     * are removed from disk and memory, and every online player is told to forget them (matching
     * what a normal [delete] does — [removeDisplays] alone only updates storage and the registry).
     * Returns the UUIDs of removed displays.
     */
    fun validateDisplaysAndCleanup(server: MinecraftServer): List<UUID> {
        val cfg = VanillaServerState.config
        val baseMaterialKey = cfg.settings.baseMaterial
        val invalidDisplays = mutableListOf<VanillaDisplayData>()

        displays.values.filterIsInstance<VanillaDisplayData>().forEach { display ->
            // An unloaded dimension is not an invalid display: skip it this pass instead of wiping
            // it from the database.
            val level = RegionUtil.getLevelByKey(server, display.worldKey) ?: run {
                VanillaServerState.logger.warn("Skipping validation for display ${display.id}: dimension '${display.worldKey}' is not loaded.")
                return@forEach
            }
            var hasBaseMaterial = false
            outerLoop@ for (x in display.minX..display.maxX) {
                for (y in display.minY..display.maxY) {
                    for (z in display.minZ..display.maxZ) {
                        val state = level.getBlockState(BlockPos(x, y, z))
                        val regName = BuiltInRegistries.BLOCK.getKey(state.block).toString()
                        if (regName == baseMaterialKey) {
                            hasBaseMaterial = true
                            break@outerLoop
                        }
                    }
                }
            }
            if (!hasBaseMaterial) invalidDisplays.add(display)
        }

        val removed = removeDisplays(invalidDisplays) { display ->
            ServerCoroutines.io.launch { VanillaServerState.storage?.deleteDisplay(display as VanillaDisplayData) }
        }
        if (removed.isNotEmpty()) {
            VanillaPacketUtil.sendClearCache(server.playerList.players, removed)
        }
        return removed
    }
}
