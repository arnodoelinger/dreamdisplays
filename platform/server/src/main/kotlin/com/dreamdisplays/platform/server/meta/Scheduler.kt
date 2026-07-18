package com.dreamdisplays.platform.server.meta

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.utils.PlatformUtil.isFolia
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.jspecify.annotations.NullMarked
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * `Paper` plugin scheduler. Provides methods for running tasks asynchronously, synchronously, or with a delay, transparently
 * dispatching to `Folia`'s region-aware schedulers when present.
 */
@PaperOnly
@NullMarked
object Scheduler {
    /** The plugin instance that owns this scheduler. */
    private lateinit var plugin: Plugin

    /** A map of tracked players, used for `Folia` compatibility. */
    private val trackedPlayers: MutableMap<UUID, Player> = ConcurrentHashMap()

    /** A map of tracked player names, used for `Folia` compatibility. */
    private val trackedNames: MutableMap<UUID, String> = ConcurrentHashMap()

    /** A map of tracked admin flags, used for `Folia` compatibility. */
    private val trackedAdmins: MutableMap<UUID, Boolean> = ConcurrentHashMap()

    /** Binds the scheduler to its owning [plugin]. Must be called before any scheduling helper. */
    fun init(plugin: Plugin) {
        this.plugin = plugin
        trackedPlayers.clear()
        trackedNames.clear()
        trackedAdmins.clear()
        plugin.server.onlinePlayers.forEach(::trackPlayer)
    }

    /** Runs [task] off the main / global thread using `Paper`'s async scheduler. */
    fun runAsync(task: Runnable) {
        plugin.server.asyncScheduler.runNow(plugin) { task.run() }
    }

    /** Runs [task] on the main / global region thread. Do not touch region-owned entities from this helper on `Folia`. */
    fun runSync(task: Runnable) {
        if (isFolia) plugin.server.globalRegionScheduler.run(plugin) { task.run() }
        else plugin.server.scheduler.runTask(plugin, task)
    }

    /** Runs [task] after [ticks] ticks on the main / global region thread. Prefer [runPlayerLater] for player work. */
    fun runLater(ticks: Long, task: Runnable) {
        if (isFolia) plugin.server.globalRegionScheduler.runDelayed(plugin, { task.run() }, ticks.coerceAtLeast(1L))
        else plugin.server.scheduler.runTaskLater(plugin, task, ticks)
    }

    /** Adds or refreshes [player] in the `Folia`-safe tracked-player registry. */
    fun trackPlayer(player: Player) {
        trackedPlayers[player.uniqueId] = player
        refreshPlayerSnapshot(player)
    }

    /** Removes [player] from the tracked-player registry. */
    fun untrackPlayer(player: Player) {
        untrackPlayer(player.uniqueId)
    }

    /** Removes a player by [playerId] from the tracked-player registry. */
    fun untrackPlayer(playerId: UUID) {
        trackedPlayers.remove(playerId)
        trackedNames.remove(playerId)
        trackedAdmins.remove(playerId)
    }

    /** Runs [task] on [player]'s owning entity scheduler on `Folia`, or on the main thread on `Paper`. */
    fun runPlayer(player: Player, task: Runnable) {
        if (isFolia) {
            player.scheduler.run(
                plugin,
                {
                    if (!player.isOnline) return@run
                    refreshPlayerSnapshot(player)
                    task.run()
                },
                Runnable { untrackPlayer(player) },
            )
        } else {
            val wrapped = Runnable {
                if (!player.isOnline) return@Runnable
                refreshPlayerSnapshot(player)
                task.run()
            }
            if (plugin.server.isPrimaryThread) wrapped.run()
            else plugin.server.scheduler.runTask(plugin, wrapped)
        }
    }

    /** Runs [task] on [player]'s owning entity scheduler after [ticks]. */
    fun runPlayerLater(player: Player, ticks: Long, task: Runnable) {
        if (isFolia) {
            player.scheduler.runDelayed(
                plugin,
                {
                    if (!player.isOnline) return@runDelayed
                    refreshPlayerSnapshot(player)
                    task.run()
                },
                Runnable { untrackPlayer(player) },
                ticks.coerceAtLeast(1L),
            )
        } else {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                refreshPlayerSnapshot(player)
                task.run()
            }, ticks)
        }
    }

    /** Runs [task] for the tracked player [playerId], returning false when the player is not online. */
    fun runTrackedPlayer(playerId: UUID, task: (Player) -> Unit): Boolean {
        val player = trackedPlayers[playerId]
            ?: if (isFolia) return false else plugin.server.getPlayer(playerId) ?: return false
        trackPlayer(player)
        runPlayer(player) { task(player) }
        return true
    }

    /** Runs [task] for every tracked online player. */
    fun forEachTrackedPlayer(task: (Player) -> Unit) {
        val players = if (isFolia) trackedPlayers.values.toList() else plugin.server.onlinePlayers.toList()
        players.forEach { player -> runPlayer(player) { task(player) } }
    }

    /** Cached player name safe to read from `Folia` global / async coordinators. */
    fun trackedPlayerName(playerId: UUID): String? = trackedNames[playerId]

    /** Cached admin flag safe to read from `Folia` global / async coordinators. */
    fun trackedPlayerIsAdmin(playerId: UUID): Boolean = trackedAdmins[playerId] == true

    /** Updates the tracked player snapshot for [player]. */
    private fun refreshPlayerSnapshot(player: Player) {
        trackedNames[player.uniqueId] = player.name
        trackedAdmins[player.uniqueId] = player.hasPermission(PaperServer.config.permissions.delete)
    }
}
