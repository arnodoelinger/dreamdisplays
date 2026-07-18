package com.dreamdisplays.platform.server.utils

import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions
import net.minecraft.server.level.ServerPlayer
import java.util.*

/**
 * Permission-node resolution for the `Fabric` / `NeoForge` servers, giving parity with the Paper
 * plugin's `hasPermission` checks. Nodes are resolved through the `LuckPerms` API when the `LuckPerms`
 * mod is installed; otherwise (or when `LuckPerms` leaves the node undefined) each check falls back
 * to its documented `config.toml` default: everyone, or operator level 2.
 */
object VanillaPermissions {
    /**
     * Fallback applied when LuckPerms is absent or leaves the node undefined. [NOBODY] is for literal membership checks
     * (e.g. `group.<x>`) that must not silently default to a broad grant.
     */
    enum class Fallback { EVERYONE, OP, NOBODY }

    private const val UNKNOWN = 0
    private const val ABSENT = 1
    private const val PRESENT = 2

    @Volatile
    private var luckPermsState = UNKNOWN

    /** True if [player] holds [node], consulting `LuckPerms` first and [fallback] otherwise. */
    fun has(player: ServerPlayer, node: String, fallback: Fallback): Boolean =
        checkLuckPerms(player.uuid, node) ?: when (fallback) {
            Fallback.EVERYONE -> true
            Fallback.OP -> VanillaDisplayActions.isOpLevel2(player)
            Fallback.NOBODY -> false
        }

    /** `LuckPerms` verdict for [node], or null when LuckPerms is unavailable or the node is undefined. */
    private fun checkLuckPerms(playerId: UUID, node: String): Boolean? {
        if (luckPermsState == ABSENT) return null
        if (luckPermsState == UNKNOWN) {
            luckPermsState = try {
                Class.forName("net.luckperms.api.LuckPermsProvider")
                PRESENT
            } catch (_: ClassNotFoundException) {
                ABSENT
            }
            if (luckPermsState == ABSENT) return null
        }
        return try {
            LuckPermsBridge.check(playerId, node)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * The only class that links against `LuckPerms` types; loaded exclusively behind the
 * [VanillaPermissions] `Class.forName` probe so servers without `LuckPerms` never touch it.
 */
private object LuckPermsBridge {
    /** True / false when the node is set for the user, null while `LuckPerms` is still initializing or the node is undefined. */
    fun check(playerId: UUID, node: String): Boolean? {
        val luckPerms = try {
            net.luckperms.api.LuckPermsProvider.get()
        } catch (_: IllegalStateException) {
            return null
        }
        val user = luckPerms.userManager.getUser(playerId) ?: return null
        return when (user.cachedData.getPermissionData().checkPermission(node)) {
            net.luckperms.api.util.Tristate.TRUE -> true
            net.luckperms.api.util.Tristate.FALSE -> false
            else -> null
        }
    }
}
