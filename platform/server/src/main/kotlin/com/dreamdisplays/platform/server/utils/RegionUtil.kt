package com.dreamdisplays.platform.server.utils

import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly

import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import org.bukkit.Location
import org.bukkit.World
import org.jspecify.annotations.NullMarked
import kotlin.math.max
import kotlin.math.min

/**
 * Utils for 3D region calculations, boundary checks, and world / level resolution.
 */
object RegionUtil {
    /** Computes the [RegionData] describing the axis-aligned box between [pos1] and [pos2]. */
    @PaperOnly @NullMarked fun calculateRegion(pos1: Location, pos2: Location): RegionData {
        val minX = min(pos1.blockX, pos2.blockX)
        val minY = min(pos1.blockY, pos2.blockY)
        val minZ = min(pos1.blockZ, pos2.blockZ)

        val maxX = max(pos1.blockX, pos2.blockX)
        val maxY = max(pos1.blockY, pos2.blockY)
        val maxZ = max(pos1.blockZ, pos2.blockZ)

        val deltaX = maxX - minX + 1
        val deltaZ = maxZ - minZ + 1
        val width = max(deltaX, deltaZ)
        val height = maxY - minY + 1

        return RegionData(
            minX, minY, minZ,
            maxX, maxY, maxZ,
            width, height,
            deltaX, deltaZ
        )
    }

    @PaperOnly @NullMarked fun isInBoundaries(pos1: Location, pos2: Location, location: Location): Boolean {
        return location.world == pos1.world && location.blockX in getRange(pos1.blockX, pos2.blockX) &&
                location.blockY in getRange(pos1.blockY, pos2.blockY) &&
                location.blockZ in getRange(pos1.blockZ, pos2.blockZ)
    }
    /** Returns the inclusive integer range covering [a] and [b] regardless of order. */
    @PaperOnly private fun getRange(a: Int, b: Int): IntRange = min(a, b)..max(a, b)
    /** Resolves a [ServerLevel] from a dimension key string like `"minecraft:overworld"`. */
    @FabricOnly fun getLevelByKey(server: MinecraftServer, worldKey: String): ServerLevel? {
        val rl = runCatching { net.minecraft.resources.Identifier.parse(worldKey) }.getOrNull() ?: return null
        val key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, rl)
        return server.getLevel(key)
    }
    /** Returns the dimension key string (e.g. `"minecraft:overworld"`) for a given [ServerLevel]. */
    @FabricOnly fun getLevelKey(level: ServerLevel): String = level.dimension().identifier().toString()

    @PaperOnly @NullMarked data class RegionData(
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int,
        val width: Int,
        val height: Int,
        val deltaX: Int,
        val deltaZ: Int,
    ) {
        /** Returns the min-corner [Location] of this region in [world]. */
        fun getMinLocation(world: World?): Location =
            Location(world, minX.toDouble(), minY.toDouble(), minZ.toDouble())

        /** Returns the max-corner [Location] of this region in [world]. */
        fun getMaxLocation(world: World?): Location =
            Location(world, maxX.toDouble(), maxY.toDouble(), maxZ.toDouble())
    }
}
