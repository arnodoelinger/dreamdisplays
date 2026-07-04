package com.dreamdisplays.platform.server.managers

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly

import com.dreamdisplays.core.protocol.DreamPacket
import com.dreamdisplays.platform.server.Main.Companion.config
import com.dreamdisplays.platform.server.Main.Companion.getInstance
import com.dreamdisplays.platform.server.NeoForgeServer
import com.dreamdisplays.platform.server.Server
import com.dreamdisplays.platform.server.datatypes.DisplayData
import com.dreamdisplays.platform.server.datatypes.FabricDisplayData
import com.dreamdisplays.platform.server.datatypes.FabricSelectionData
import com.dreamdisplays.platform.server.datatypes.NeoForgeDisplayData
import com.dreamdisplays.platform.server.datatypes.NeoForgeSelectionData
import com.dreamdisplays.platform.server.datatypes.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.PaperSelectionData
import com.dreamdisplays.platform.server.datatypes.SyncData
import com.dreamdisplays.platform.server.meta.Scheduler
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.meta.Scheduler.runSync
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import kotlinx.coroutines.launch
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.playback.WatchPartyManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.NeoForgeMessageUtil
import com.dreamdisplays.platform.server.utils.PlatformUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.RegionUtil.calculateRegion
import com.dreamdisplays.platform.server.utils.ReporterUtil
import com.dreamdisplays.platform.server.utils.ReporterUtil.sendReport
import com.dreamdisplays.platform.server.utils.net.FabricPacketUtil
import com.dreamdisplays.platform.server.utils.net.NeoForgePacketUtil
import com.dreamdisplays.platform.server.utils.net.PacketUtil
import com.dreamdisplays.platform.server.utils.net.PacketUtil.sendDelete
import com.dreamdisplays.platform.server.utils.net.PaperV2Networking
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit.getOfflinePlayer
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.jspecify.annotations.NullMarked
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * Manages all displays on the server. Handles registration, deletion, overlap detection,
 * receiver lookup, and report rate-limiting.
 */
@NullMarked
object DisplayManager {
    private const val REPORT_RATE_LIMIT_MAX_SIZE = 20_000L

    private data class ReportCooldown(val durationNanos: Long)

    private val reportCooldownExpiry = object : Expiry<UUID, ReportCooldown> {
        override fun expireAfterCreate(key: UUID, value: ReportCooldown, currentTime: Long): Long =
            value.durationNanos

        override fun expireAfterUpdate(
            key: UUID,
            value: ReportCooldown,
            currentTime: Long,
            currentDuration: Long
        ): Long = value.durationNanos

        override fun expireAfterRead(
            key: UUID,
            value: ReportCooldown,
            currentTime: Long,
            currentDuration: Long
        ): Long = currentDuration
    }

    private val displays: MutableMap<UUID, DisplayData> = ConcurrentHashMap()
    private val reportTime: Cache<UUID, ReportCooldown> = Caffeine.newBuilder()
        .maximumSize(REPORT_RATE_LIMIT_MAX_SIZE)
        .expireAfter(reportCooldownExpiry)
        .build()
    private val reporterTime: Cache<UUID, ReportCooldown> = Caffeine.newBuilder()
        .maximumSize(REPORT_RATE_LIMIT_MAX_SIZE)
        .expireAfter(reportCooldownExpiry)
        .build()
    private val reportRateLimitLock = Any()
    private val nearbyPlayersByDisplay: MutableMap<UUID, MutableSet<UUID>> = ConcurrentHashMap()
    private val nearbyDisplaysByPlayer: MutableMap<UUID, Set<UUID>> = ConcurrentHashMap()

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
            is FabricDisplayData -> delete(data)
            is NeoForgeDisplayData -> delete(data)
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
     * Checks whether a report from [reporterId] about display [id] should be rate-limited. Drops
     * the request when either the per-display or the per-reporter cooldown is still active; the
     * per-reporter limit stops an attacker from amplifying the webhook by spreading reports across
     * many displays. Records both cooldown markers only when the report may proceed.
     */
    private fun isReportThrottled(id: UUID, reporterId: UUID, cooldownMs: Long): Boolean {
        val durationNanos = TimeUnit.MILLISECONDS.toNanos(cooldownMs).coerceAtLeast(0L)
        if (durationNanos == 0L) return false

        synchronized(reportRateLimitLock) {
            if (reportTime.getIfPresent(id) != null || reporterTime.getIfPresent(reporterId) != null) {
                return true
            }
            val marker = ReportCooldown(durationNanos)
            reportTime.put(id, marker)
            reporterTime.put(reporterId, marker)
            return false
        }
    }

    /**
     * Removes every display in [toRemove] from the in-memory registry, invokes [delete] for each,
     * and returns the list of removed UUIDs.
     */
    private fun removeDisplays(toRemove: List<DisplayData>, delete: (DisplayData) -> Unit): List<UUID> {
        return toRemove.map { display ->
            displays.remove(display.id)
            forgetNearbyDisplay(display.id)
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

    /** Removes a display from the cached proximity index. */
    private fun forgetNearbyDisplay(displayId: UUID) {
        nearbyPlayersByDisplay.remove(displayId)
        nearbyDisplaysByPlayer.replaceAll { _, ids -> ids - displayId }
    }

    /** Removes [playerId] from the cached Folia proximity index. */
    @PaperOnly
    fun forgetNearbyPlayer(playerId: UUID) {
        nearbyDisplaysByPlayer.remove(playerId)?.forEach { displayId ->
            nearbyPlayersByDisplay[displayId]?.remove(playerId)
        }
    }

    /** Cached nearby player ids for Folia global coordinators that cannot read entity locations directly. */
    @PaperOnly
    fun getTrackedNearbyPlayerIds(display: PaperDisplayData): List<UUID> =
        nearbyPlayersByDisplay[display.id]?.toList() ?: emptyList()

    /** Updates the cached proximity index after [player]'s entity task computed their nearby displays. */
    @PaperOnly
    private fun updateNearbyIndex(player: Player, nearbyDisplayIds: Set<UUID>) {
        val playerId = player.uniqueId
        val previous = nearbyDisplaysByPlayer.put(playerId, nearbyDisplayIds) ?: emptySet()

        (previous - nearbyDisplayIds).forEach { displayId ->
            nearbyPlayersByDisplay[displayId]?.remove(playerId)
        }
        (nearbyDisplayIds - previous).forEach { displayId ->
            nearbyPlayersByDisplay.computeIfAbsent(displayId) { ConcurrentHashMap.newKeySet() }.add(playerId)
        }
    }

    /** Sends a `DisplayInfo` packet describing [display] to the given [players]. */
    @PaperOnly
    fun sendUpdate(display: PaperDisplayData, players: List<Player>) {
        @Suppress("UNCHECKED_CAST")
        PacketUtil.sendDisplayInfo(
            players as MutableList<Player?>,
            display.id, display.ownerId, display.box.min, display.width, display.height,
            display.url, display.lang, display.facing, display.isSync, display.isLocked,
            display.mode, display.qualityCap, display.rotation,
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
            @Suppress("UNCHECKED_CAST")
            sendDelete(getReceivers(display) as MutableList<Player?>, display.id)
        }
    }

    /** Refreshes all displays visible to every tracked player through each player's entity scheduler. */
    @PaperOnly
    fun updateAllDisplaysForTrackedPlayers() {
        Scheduler.forEachTrackedPlayer { player ->
            val visible = displays.values.filterIsInstance<PaperDisplayData>()
                .filter { player.isInRange(it) }
            updateNearbyIndex(player, visible.mapTo(mutableSetOf()) { it.id })
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
        displays.remove(displayData.id)
        forgetNearbyDisplay(displayData.id)
    }

    /**
     * Posts a report about display [id] to the configured webhook, respecting per-display cooldown
     * and informing [player] about the outcome.
     */
    @PaperOnly
    @JvmStatic
    fun report(id: UUID, player: Player) {
        val displayData = displays[id] as? PaperDisplayData ?: return
        if (isReportThrottled(id, player.uniqueId, config.settings.reportCooldown.toLong())) {
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
     * are removed from disk and memory. Returns the UUIDs of removed displays.
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

        return removeDisplays(invalidDisplays) { display ->
            runAsync { getInstance().storage.deleteDisplay(display as PaperDisplayData) }
        }
    }

    /** Returns the first display whose bounding box contains [blockPos] in [worldKey]. */
    @FabricOnly
    fun isContains(worldKey: String, blockPos: BlockPos): FabricDisplayData? {
        return displays.values.filterIsInstance<FabricDisplayData>().firstOrNull { d ->
            d.worldKey == worldKey &&
                    d.box.contains(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        }
    }

    /** Returns true if the selection [sel] intersects any existing display. */
    @FabricOnly
    fun isOverlaps(sel: FabricSelectionData): Boolean {
        val selBox = sel.selectionBox() ?: return false
        val wk = sel.worldKey ?: return false
        return displays.values.filterIsInstance<FabricDisplayData>().any { d ->
            d.worldKey == wk && d.box.intersects(selBox)
        }
    }

    /** Registers a new display. Caller is responsible for broadcasting. */
    @FabricOnly
    fun register(data: FabricDisplayData) {
        displays[data.id] = data
    }

    /** Returns the players currently in range of [display] in its world. */
    @FabricOnly
    fun getReceivers(display: FabricDisplayData, server: MinecraftServer): List<ServerPlayer> {
        return server.playerList.players.filter { p ->
            RegionUtil.getPlayerLevelKey(p) == display.worldKey &&
                    p.blockPosition().isInRange(display)
        }
    }

    /** Returns true if this block position lies within `maxRenderDistance` of the [display]'s box. */
    @FabricOnly
    private fun BlockPos.isInRange(display: FabricDisplayData): Boolean =
        isInRangeImpl(
            x, y, z,
            display.minX, display.minY, display.minZ,
            display.maxX, display.maxY, display.maxZ,
            Server.config.settings.maxRenderDistance,
        )

    /** Sends a `DisplayInfo` packet describing [display] to the given [players]. */
    @FabricOnly
    fun sendUpdate(display: FabricDisplayData, players: List<ServerPlayer>) {
        FabricPacketUtil.sendDisplayInfo(players, display)
    }

    /** Sends a refresh packet for every display to in-range players. */
    @FabricOnly
    fun updateAllDisplays(server: MinecraftServer) {
        displays.values.filterIsInstance<FabricDisplayData>().forEach { display ->
            val receivers = getReceivers(display, server)
            if (receivers.isNotEmpty()) sendUpdate(display, receivers)
        }
    }

    /** Removes [data] from storage and the registry. The JDBC delete runs off-thread on [ServerCoroutines.io]. */
    @FabricOnly
    fun delete(data: FabricDisplayData) {
        displays.remove(data.id)
        TimelineManager.remove(data.id)
        WatchPartyManager.remove(data.id)
        ServerCoroutines.io.launch { Server.storage?.deleteDisplay(data) }
    }

    /**
     * Posts a report about display [id] to the configured webhook, respecting per-display cooldown
     * and informing [player] about the outcome.
     */
    @FabricOnly
    fun report(id: UUID, player: ServerPlayer, server: MinecraftServer) {
        val displayData = displays[id] as? FabricDisplayData ?: return
        val cfg = Server.config
        if (isReportThrottled(id, player.uuid, cfg.settings.reportCooldown)) {
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
    @FabricOnly
    fun save(saveDisplay: (FabricDisplayData) -> Unit) {
        displays.values.filterIsInstance<FabricDisplayData>().forEach(saveDisplay)
    }

    /**
     * Scans every display's bounding box for the configured base material; displays with none
     * are removed from disk and memory. Returns the UUIDs of removed displays.
     */
    @FabricOnly
    fun validateDisplaysAndCleanup(server: MinecraftServer): List<UUID> {
        val cfg = Server.config
        val baseMaterialKey = cfg.settings.baseMaterial
        val invalidDisplays = mutableListOf<FabricDisplayData>()

        displays.values.filterIsInstance<FabricDisplayData>().forEach { display ->
            // An unloaded dimension is not an invalid display: skip it this pass instead of wiping
            // it from the database.
            val level = RegionUtil.getLevelByKey(server, display.worldKey) ?: run {
                Server.logger.warn("Skipping validation for display ${display.id}: dimension '${display.worldKey}' is not loaded.")
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

        return removeDisplays(invalidDisplays) { display ->
            ServerCoroutines.io.launch { Server.storage?.deleteDisplay(display as FabricDisplayData) }
        }
    }

    /**
     * Returns the first display whose bounding box contains [blockPos] in [worldKey]. Named
     * distinctly from the `Fabric` overload (rather than reused) because both take the identical
     * `(String, BlockPos)` parameter list — only the return type differs, which the JVM does not
     * use for overload resolution, so this would otherwise be a genuine redeclaration clash.
     */
    @NeoForgeOnly
    fun isContainsNeoForge(worldKey: String, blockPos: BlockPos): NeoForgeDisplayData? {
        return displays.values.filterIsInstance<NeoForgeDisplayData>().firstOrNull { d ->
            d.worldKey == worldKey &&
                    d.box.contains(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        }
    }

    /** Returns true if the selection [sel] intersects any existing display. */
    @NeoForgeOnly
    fun isOverlaps(sel: NeoForgeSelectionData): Boolean {
        val selBox = sel.selectionBox() ?: return false
        val wk = sel.worldKey ?: return false
        return displays.values.filterIsInstance<NeoForgeDisplayData>().any { d ->
            d.worldKey == wk && d.box.intersects(selBox)
        }
    }

    /** Registers a new display. Caller is responsible for broadcasting. */
    @NeoForgeOnly
    fun register(data: NeoForgeDisplayData) {
        displays[data.id] = data
    }

    /** Returns the players currently in range of [display] in its world. */
    @NeoForgeOnly
    fun getReceivers(display: NeoForgeDisplayData, server: MinecraftServer): List<ServerPlayer> {
        return server.playerList.players.filter { p ->
            RegionUtil.getPlayerLevelKey(p) == display.worldKey &&
                    p.blockPosition().isInRange(display)
        }
    }

    /** Returns true if this block position lies within `maxRenderDistance` of the [display]'s box. */
    @NeoForgeOnly
    private fun BlockPos.isInRange(display: NeoForgeDisplayData): Boolean =
        isInRangeImpl(
            x, y, z,
            display.minX, display.minY, display.minZ,
            display.maxX, display.maxY, display.maxZ,
            NeoForgeServer.config.settings.maxRenderDistance,
        )

    /** Sends a `DisplayInfo` packet describing [display] to the given [players]. */
    @NeoForgeOnly
    fun sendUpdate(display: NeoForgeDisplayData, players: List<ServerPlayer>) {
        NeoForgePacketUtil.sendDisplayInfo(players, display)
    }

    /** Sends a refresh packet for every display to in-range players. */
    @NeoForgeOnly
    fun updateAllDisplaysNeoForge(server: MinecraftServer) {
        displays.values.filterIsInstance<NeoForgeDisplayData>().forEach { display ->
            val receivers = getReceivers(display, server)
            if (receivers.isNotEmpty()) sendUpdate(display, receivers)
        }
    }

    /** Removes [data] from storage and the registry. The JDBC delete runs off-thread on [ServerCoroutines.io]. */
    @NeoForgeOnly
    fun delete(data: NeoForgeDisplayData) {
        displays.remove(data.id)
        TimelineManager.remove(data.id)
        WatchPartyManager.remove(data.id)
        ServerCoroutines.io.launch { NeoForgeServer.storage?.deleteDisplay(data) }
    }

    /**
     * Posts a report about display [id] to the configured webhook, respecting per-display cooldown
     * and informing [player] about the outcome.
     */
    @NeoForgeOnly
    fun reportNeoForge(id: UUID, player: ServerPlayer, server: MinecraftServer) {
        val displayData = displays[id] as? NeoForgeDisplayData ?: return
        val cfg = NeoForgeServer.config
        if (isReportThrottled(id, player.uuid, cfg.settings.reportCooldown)) {
            NeoForgeMessageUtil.sendMessage(player, "reportTooQuickly")
            return
        }
        if (cfg.settings.webhookUrl.isEmpty()) return

        val ownerName = server.playerList.players.find { it.uuid == displayData.ownerId }?.name?.string ?: "Unknown"
        val locationStr =
            "${displayData.worldKey} (x=${displayData.minX}, y=${displayData.minY}, z=${displayData.minZ})"

        ServerCoroutines.io.launch {
            runCatching {
                ReporterUtil.sendReportNeoForge(
                    locationStr,
                    displayData.url,
                    displayData.id,
                    player.name.string,
                    ownerName,
                    cfg.settings.webhookUrl,
                )
                server.execute { NeoForgeMessageUtil.sendMessage(player, "reportSent") }
            }.onFailure {
                server.execute { NeoForgeMessageUtil.sendMessage(player, "reportFailed") }
            }
        }
    }

    /**
     * Invokes [saveDisplay] for every currently registered display (used by storage flush). Named
     * distinctly from the `Fabric` overload because both take a `(DisplayData) -> Unit`-shaped
     * lambda, which erases to the same JVM signature (`Function1`) regardless of the generic type
     * argument — a genuine platform declaration clash, not just a Kotlin-source-level ambiguity.
     */
    @NeoForgeOnly
    fun saveNeoForge(saveDisplay: (NeoForgeDisplayData) -> Unit) {
        displays.values.filterIsInstance<NeoForgeDisplayData>().forEach(saveDisplay)
    }

    /**
     * Scans every display's bounding box for the configured base material; displays with none
     * are removed from disk and memory. Returns the UUIDs of removed displays.
     */
    @NeoForgeOnly
    fun validateDisplaysAndCleanupNeoForge(server: MinecraftServer): List<UUID> {
        val cfg = NeoForgeServer.config
        val baseMaterialKey = cfg.settings.baseMaterial
        val invalidDisplays = mutableListOf<NeoForgeDisplayData>()

        displays.values.filterIsInstance<NeoForgeDisplayData>().forEach { display ->
            // An unloaded dimension is not an invalid display: skip it this pass instead of wiping
            // it from the database.
            val level = RegionUtil.getLevelByKey(server, display.worldKey) ?: run {
                NeoForgeServer.logger.warn("Skipping validation for display ${display.id}: dimension '${display.worldKey}' is not loaded.")
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

        return removeDisplays(invalidDisplays) { display ->
            ServerCoroutines.io.launch { NeoForgeServer.storage?.deleteDisplay(display as NeoForgeDisplayData) }
        }
    }
}
