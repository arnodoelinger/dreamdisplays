package com.dreamdisplays.server.managers

import com.github.zafarkhaja.semver.Version
import io.github.arsmotorin.ofrat.*
import net.minecraft.server.level.ServerPlayer
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-player state: reported mod version, notification flags, and whether
 * displays are enabled for each connected player.
 */
@NullMarked object PlayerManager {
    private val versions: MutableMap<UUID, Version?> = ConcurrentHashMap()
    private val modUpdateNotified: MutableMap<UUID, Boolean> = ConcurrentHashMap()
    private val pluginUpdateNotified: MutableMap<UUID, Boolean> = ConcurrentHashMap()
    private val modRequiredNotified: MutableMap<UUID, Boolean> = ConcurrentHashMap()
    private val displaysEnabled: MutableMap<UUID, Boolean> = ConcurrentHashMap()

    /** Records the mod [version] reported by [player] for compatibility checks. */
    @PaperOnly @JvmStatic fun setVersion(player: Player, version: Version?) {
        versions[player.uniqueId] = version
    }

    /** Records the mod [version] reported by [player] for compatibility checks. */
    @FabricOnly fun setVersion(player: ServerPlayer, version: Version?) {
        versions[player.uuid] = version
    }

    /** Drops all cached per-player state on disconnect. */
    @PaperOnly fun removeVersion(player: Player) {
        val id = player.uniqueId
        versions.remove(id)
        modUpdateNotified.remove(id)
        pluginUpdateNotified.remove(id)
        modRequiredNotified.remove(id)
        displaysEnabled.remove(id)
    }

    /** Drops all cached per-player state on disconnect. */
    @FabricOnly fun removeVersion(player: ServerPlayer) {
        val id = player.uuid
        versions.remove(id)
        modUpdateNotified.remove(id)
        pluginUpdateNotified.remove(id)
        modRequiredNotified.remove(id)
        displaysEnabled.remove(id)
    }

    /** Returns a defensive copy of the per-player version map. */
    @JvmStatic fun getVersions(): Map<UUID, Version?> {
        return HashMap(versions)
    }

    /** Returns the mod version reported by [player], or null if none was reported. */
    @PaperOnly @JvmStatic fun getVersion(player: Player): Version? {
        return versions[player.uniqueId]
    }

    /** Returns the mod version reported by [player], or null if none was reported. */
    @FabricOnly fun getVersion(player: ServerPlayer): Version? = versions[player.uuid]

    /** Returns true if [player] has already been informed about a mod update. */
    @PaperOnly @JvmStatic fun hasBeenNotifiedAboutModUpdate(player: Player): Boolean {
        return modUpdateNotified[player.uniqueId] ?: false
    }

    /** Returns true if [player] has already been informed about a mod update. */
    @FabricOnly fun hasBeenNotifiedAboutModUpdate(player: ServerPlayer): Boolean =
        modUpdateNotified[player.uuid] ?: false

    /** Marks whether [player] has been notified about a mod update. */
    @PaperOnly @JvmStatic fun setModUpdateNotified(player: Player, notified: Boolean) {
        modUpdateNotified[player.uniqueId] = notified
    }

    /** Marks whether [player] has been notified about a mod update. */
    @FabricOnly fun setModUpdateNotified(player: ServerPlayer, notified: Boolean) {
        modUpdateNotified[player.uuid] = notified
    }

    /** Returns true if [player] has already been informed about a plugin update. */
    @PaperOnly @JvmStatic fun hasBeenNotifiedAboutPluginUpdate(player: Player): Boolean {
        return pluginUpdateNotified[player.uniqueId] ?: false
    }

    /** Returns true if [player] has already been informed about a plugin update. */
    @FabricOnly fun hasBeenNotifiedAboutPluginUpdate(player: ServerPlayer): Boolean =
        pluginUpdateNotified[player.uuid] ?: false

    /** Marks whether [player] has been notified about a plugin update. */
    @PaperOnly @JvmStatic fun setPluginUpdateNotified(player: Player, notified: Boolean) {
        pluginUpdateNotified[player.uniqueId] = notified
    }

    /** Marks whether [player] has been notified about a plugin update. */
    @FabricOnly fun setPluginUpdateNotified(player: ServerPlayer, notified: Boolean) {
        pluginUpdateNotified[player.uuid] = notified
    }

    /** Returns true if [player] has already been informed that the mod is required. */
    @PaperOnly @JvmStatic fun hasBeenNotifiedAboutModRequired(player: Player): Boolean {
        return modRequiredNotified[player.uniqueId] ?: false
    }

    /** Returns true if [player] has already been informed that the mod is required. */
    @FabricOnly fun hasBeenNotifiedAboutModRequired(player: ServerPlayer): Boolean =
        modRequiredNotified[player.uuid] ?: false

    /** Marks whether [player] has been notified that the mod is required. */
    @PaperOnly @JvmStatic fun setModRequiredNotified(player: Player, notified: Boolean) {
        modRequiredNotified[player.uniqueId] = notified
    }

    /** Marks whether [player] has been notified that the mod is required. */
    @FabricOnly fun setModRequiredNotified(player: ServerPlayer, notified: Boolean) {
        modRequiredNotified[player.uuid] = notified
    }

    /** Sets whether displays should be rendered for [player]. */
    @PaperOnly @JvmStatic fun setDisplaysEnabled(player: Player, enabled: Boolean) {
        displaysEnabled[player.uniqueId] = enabled
    }

    /** Sets whether displays should be rendered for [player]. */
    @FabricOnly fun setDisplaysEnabled(player: ServerPlayer, enabled: Boolean) {
        displaysEnabled[player.uuid] = enabled
    }

    /** Returns whether displays are enabled for [player] (defaults to true). */
    @PaperOnly @JvmStatic fun isDisplaysEnabled(player: Player): Boolean {
        return displaysEnabled.getOrDefault(player.uniqueId, true)
    }

    /** Returns whether displays are enabled for [player] (defaults to true). */
    @FabricOnly fun isDisplaysEnabled(player: ServerPlayer): Boolean =
        displaysEnabled.getOrDefault(player.uuid, true)
}
