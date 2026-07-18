package com.dreamdisplays.platform.server.managers

import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.server.level.ServerPlayer
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import org.semver4j.Semver
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-player state: reported mod version, notification flags, and whether
 * displays are enabled for each connected player.
 */
@NullMarked
object PlayerManager {
    /** Map of player UUIDs to their reported mod versions. */
    private val versions: MutableMap<UUID, Semver?> = ConcurrentHashMap()

    /** Set of player UUIDs for which displays are disabled. */
    private val displaysDisabled: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * One-way "already notified this session" bits per player, kept for the server's lifetime (see
     * [removePlayer]) so a player is told about an update / requirement only once per server session,
     * not on every re-join.
     *
     * Packed into a single bitmask entry instead of three separate `UUID` sets — on a server that sees
     * hundreds of unique joins in one session, three per-player hash-set nodes each is pure overhead once
     * you're just tracking three booleans.
     */
    private val notifiedFlags: MutableMap<UUID, Int> = ConcurrentHashMap()

    private const val MOD_UPDATE_NOTIFIED = 1
    private const val PLUGIN_UPDATE_NOTIFIED = 1 shl 1
    private const val MOD_REQUIRED_NOTIFIED = 1 shl 2

    /** Returns true if [flag] is set for [uuid]. */
    private fun hasNotifiedFlag(uuid: UUID, flag: Int): Boolean = (notifiedFlags[uuid] ?: 0) and flag != 0

    /** Sets or clears [flag] for [uuid]; drops the map entry entirely once no flags remain set. */
    private fun setNotifiedFlag(uuid: UUID, flag: Int, notified: Boolean) {
        notifiedFlags.compute(uuid) { _, bits ->
            val current = bits ?: 0
            val updated = if (notified) current or flag else current and flag.inv()
            updated.takeIf { it != 0 }
        }
    }

    /** Records the mod [version] reported by [uuid] for compatibility checks. */
    fun setVersion(uuid: UUID, version: Semver?) {
        versions[uuid] = version
    }

    /** Records the mod [version] reported by [player] for compatibility checks. */
    @PaperOnly
    @JvmStatic
    fun setVersion(player: Player, version: Semver?) = setVersion(player.uniqueId, version)

    /** Records the mod [version] reported by [player] for compatibility checks. */
    @FabricOnly
    fun setVersion(player: ServerPlayer, version: Semver?) = setVersion(player.uuid, version)

    /**
     * Drops transient per-player state on disconnect. The update / mod-required notification flags
     * are deliberately kept for the server's lifetime so a player is told about an update only once
     * per server session, not on every re-join; they reset naturally on server restart.
     */
    fun removePlayer(uuid: UUID) {
        versions.remove(uuid)
        displaysDisabled.remove(uuid)
    }

    /** Drops all cached per-player state on disconnect. */
    @PaperOnly
    fun removeVersion(player: Player) = removePlayer(player.uniqueId)

    /** Drops all cached per-player state on disconnect. */
    @FabricOnly
    fun removeVersion(player: ServerPlayer) = removePlayer(player.uuid)

    /** Returns a defensive copy of the per-player version map. */
    @JvmStatic
    fun getVersions(): Map<UUID, Semver?> = HashMap(versions)

    /** Returns the mod version reported by [uuid], or null if none was reported. */
    fun getVersion(uuid: UUID): Semver? = versions[uuid]

    /** Returns the mod version reported by [player], or null if none was reported. */
    @PaperOnly
    @JvmStatic
    fun getVersion(player: Player): Semver? = getVersion(player.uniqueId)

    /** Returns the mod version reported by [player], or null if none was reported. */
    @FabricOnly
    fun getVersion(player: ServerPlayer): Semver? = getVersion(player.uuid)

    /** Returns true if [uuid] has already been informed about a mod update. */
    fun hasBeenNotifiedAboutModUpdate(uuid: UUID): Boolean = hasNotifiedFlag(uuid, MOD_UPDATE_NOTIFIED)

    /** Returns true if [player] has already been informed about a mod update. */
    @PaperOnly
    @JvmStatic
    fun hasBeenNotifiedAboutModUpdate(player: Player): Boolean =
        hasBeenNotifiedAboutModUpdate(player.uniqueId)

    /** Returns true if [player] has already been informed about a mod update. */
    @FabricOnly
    fun hasBeenNotifiedAboutModUpdate(player: ServerPlayer): Boolean =
        hasBeenNotifiedAboutModUpdate(player.uuid)

    /** Marks whether [uuid] has been notified about a mod update. */
    fun setModUpdateNotified(uuid: UUID, notified: Boolean) = setNotifiedFlag(uuid, MOD_UPDATE_NOTIFIED, notified)

    /** Marks whether [player] has been notified about a mod update. */
    @PaperOnly
    @JvmStatic
    fun setModUpdateNotified(player: Player, notified: Boolean) =
        setModUpdateNotified(player.uniqueId, notified)

    /** Marks whether [player] has been notified about a mod update. */
    @FabricOnly
    fun setModUpdateNotified(player: ServerPlayer, notified: Boolean) =
        setModUpdateNotified(player.uuid, notified)

    /** Returns true if [uuid] has already been informed about a plugin update. */
    fun hasBeenNotifiedAboutPluginUpdate(uuid: UUID): Boolean = hasNotifiedFlag(uuid, PLUGIN_UPDATE_NOTIFIED)

    /** Returns true if [player] has already been informed about a plugin update. */
    @PaperOnly
    @JvmStatic
    fun hasBeenNotifiedAboutPluginUpdate(player: Player): Boolean =
        hasBeenNotifiedAboutPluginUpdate(player.uniqueId)

    /** Returns true if [player] has already been informed about a plugin update. */
    @FabricOnly
    fun hasBeenNotifiedAboutPluginUpdate(player: ServerPlayer): Boolean =
        hasBeenNotifiedAboutPluginUpdate(player.uuid)

    /** Marks whether [uuid] has been notified about a plugin update. */
    fun setPluginUpdateNotified(uuid: UUID, notified: Boolean) =
        setNotifiedFlag(uuid, PLUGIN_UPDATE_NOTIFIED, notified)

    /** Marks whether [player] has been notified about a plugin update. */
    @PaperOnly
    @JvmStatic
    fun setPluginUpdateNotified(player: Player, notified: Boolean) =
        setPluginUpdateNotified(player.uniqueId, notified)

    /** Marks whether [player] has been notified about a plugin update. */
    @FabricOnly
    fun setPluginUpdateNotified(player: ServerPlayer, notified: Boolean) =
        setPluginUpdateNotified(player.uuid, notified)

    /** Returns true if [uuid] has already been informed that the mod is required. */
    fun hasBeenNotifiedAboutModRequired(uuid: UUID): Boolean = hasNotifiedFlag(uuid, MOD_REQUIRED_NOTIFIED)

    /** Returns true if [player] has already been informed that the mod is required. */
    @PaperOnly
    @JvmStatic
    fun hasBeenNotifiedAboutModRequired(player: Player): Boolean =
        hasBeenNotifiedAboutModRequired(player.uniqueId)

    /** Returns true if [player] has already been informed that the mod is required. */
    @FabricOnly
    fun hasBeenNotifiedAboutModRequired(player: ServerPlayer): Boolean =
        hasBeenNotifiedAboutModRequired(player.uuid)

    /** Marks whether [uuid] has been notified that the mod is required. */
    fun setModRequiredNotified(uuid: UUID, notified: Boolean) =
        setNotifiedFlag(uuid, MOD_REQUIRED_NOTIFIED, notified)

    /** Marks whether [player] has been notified that the mod is required. */
    @PaperOnly
    @JvmStatic
    fun setModRequiredNotified(player: Player, notified: Boolean) =
        setModRequiredNotified(player.uniqueId, notified)

    /** Marks whether [player] has been notified that the mod is required. */
    @FabricOnly
    fun setModRequiredNotified(player: ServerPlayer, notified: Boolean) =
        setModRequiredNotified(player.uuid, notified)

    /** Sets whether displays should be rendered for [uuid]. */
    fun setDisplaysEnabled(uuid: UUID, enabled: Boolean) {
        if (enabled) displaysDisabled.remove(uuid) else displaysDisabled.add(uuid)
    }

    /** Sets whether displays should be rendered for [player]. */
    @PaperOnly
    @JvmStatic
    fun setDisplaysEnabled(player: Player, enabled: Boolean) =
        setDisplaysEnabled(player.uniqueId, enabled)

    /** Sets whether displays should be rendered for [player]. */
    @FabricOnly
    fun setDisplaysEnabled(player: ServerPlayer, enabled: Boolean) =
        setDisplaysEnabled(player.uuid, enabled)

    /** Returns whether displays are enabled for [uuid] (defaults to true). */
    fun isDisplaysEnabled(uuid: UUID): Boolean = uuid !in displaysDisabled

    /** Returns whether displays are enabled for [player] (defaults to true). */
    @PaperOnly
    @JvmStatic
    fun isDisplaysEnabled(player: Player): Boolean =
        isDisplaysEnabled(player.uniqueId)

    /** Returns whether displays are enabled for [player] (defaults to true). */
    @FabricOnly
    fun isDisplaysEnabled(player: ServerPlayer): Boolean =
        isDisplaysEnabled(player.uuid)
}
