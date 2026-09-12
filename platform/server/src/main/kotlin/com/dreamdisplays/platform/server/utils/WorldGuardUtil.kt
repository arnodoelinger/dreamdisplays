package com.dreamdisplays.platform.server.utils

import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Location
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked

/** `WorldGuard` region lookups for the `Paper` server; absent when `WorldGuard` isn't installed. */
@PaperOnly
@NullMarked
object WorldGuardRegions {
    private const val UNKNOWN = 0
    private const val ABSENT = 1
    private const val PRESENT = 2

    @Volatile
    private var state = UNKNOWN

    /** True when `WorldGuard` is installed, so region membership can be resolved at all. */
    fun isAvailable(): Boolean = isPresent()

    private fun isPresent(): Boolean {
        if (state == UNKNOWN) {
            state = try {
                Class.forName("com.sk89q.worldguard.WorldGuard")
                PRESENT
            } catch (_: ClassNotFoundException) {
                ABSENT
            }
        }
        return state == PRESENT
    }

    /** True if [location] falls inside any player-claimed `WorldGuard` region. */
    fun isProtectedTerritory(location: Location): Boolean {
        return isPresent() && try {
            WorldGuardBridge.isProtectedTerritory(location)
        } catch (_: Throwable) {
            false
        }
    }

    /** True if [player] owns any `WorldGuard` region at [location] (region owners can have several). */
    fun isRegionOwner(player: Player, location: Location): Boolean {
        return isPresent() && try {
            WorldGuardBridge.isRegionOwner(player, location)
        } catch (_: Throwable) {
            false
        }
    }

    /** True if [player] is a member (or owner — `WorldGuard` treats owners as members too) of any region at [location]. */
    fun isRegionMember(player: Player, location: Location): Boolean {
        return isPresent() && try {
            WorldGuardBridge.isRegionMember(player, location)
        } catch (_: Throwable) {
            false
        }
    }
}

@PaperOnly
private object WorldGuardBridge {
    private const val GLOBAL_REGION_ID = "__global__"

    fun isProtectedTerritory(location: Location): Boolean = applicableRegions(location).any { it.id != GLOBAL_REGION_ID }

    fun isRegionOwner(player: Player, location: Location): Boolean {
        val localPlayer = com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapPlayer(player)
        return applicableRegions(location).any { it.id != GLOBAL_REGION_ID && it.isOwner(localPlayer) }
    }

    fun isRegionMember(player: Player, location: Location): Boolean {
        val localPlayer = com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapPlayer(player)
        return applicableRegions(location).any { it.id != GLOBAL_REGION_ID && it.isMember(localPlayer) }
    }

    private fun applicableRegions(location: Location): Iterable<com.sk89q.worldguard.protection.regions.ProtectedRegion> {
        val world = location.world ?: return emptyList()
        val container = com.sk89q.worldguard.WorldGuard.getInstance().platform.regionContainer
        val regions = container[com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world)] ?: return emptyList()
        val point = com.sk89q.worldedit.bukkit.BukkitAdapter.asBlockVector(location)
        return regions.getApplicableRegions(point)
    }
}
