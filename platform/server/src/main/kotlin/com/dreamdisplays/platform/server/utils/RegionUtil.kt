package com.dreamdisplays.platform.server.utils

//? if >=1.21.11 {
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import org.bukkit.Location
import org.bukkit.World
import org.jspecify.annotations.NullMarked
import kotlin.math.max
import kotlin.math.min

/**
 * Utils for 3D region calculations, boundary checks, and world / level resolution. The single
 * source of truth for the min / max / delta and floor-or-ceiling screen-dimension math that both the
 * `Paper` ([Location]-based) and `Fabric` / `NeoForge` ([BlockPos]-based) selection / display code
 * needs identically.
 */
object RegionUtil {
    /** Computes the [RegionData] describing the axis-aligned box between two integer corners. */
    private fun calculateRegion(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): RegionData {
        val minX = min(x1, x2)
        val minY = min(y1, y2)
        val minZ = min(z1, z2)
        val maxX = max(x1, x2)
        val maxY = max(y1, y2)
        val maxZ = max(z1, z2)

        return RegionData(
            minX, minY, minZ,
            maxX, maxY, maxZ,
            maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1,
        )
    }

    /** Computes the [RegionData] describing the axis-aligned box between [pos1] and [pos2]. */
    @PaperOnly
    @NullMarked
    fun calculateRegion(pos1: Location, pos2: Location): RegionData =
        calculateRegion(pos1.blockX, pos1.blockY, pos1.blockZ, pos2.blockX, pos2.blockY, pos2.blockZ)

    /** Computes the [RegionData] describing the axis-aligned box between [pos1] and [pos2]. */
    fun calculateRegion(pos1: BlockPos, pos2: BlockPos): RegionData =
        calculateRegion(pos1.x, pos1.y, pos1.z, pos2.x, pos2.y, pos2.z)

    /** Is [location] within the boundaries of [pos1] and [pos2]? */
    @PaperOnly
    @NullMarked
    fun isInBoundaries(pos1: Location, pos2: Location, location: Location): Boolean {
        return location.world == pos1.world && location.blockX in getRange(pos1.blockX, pos2.blockX) &&
                location.blockY in getRange(pos1.blockY, pos2.blockY) &&
                location.blockZ in getRange(pos1.blockZ, pos2.blockZ)
    }

    /** Is [pos] within the boundaries of [pos1] and [pos2]? */
    fun isInBoundaries(pos1: BlockPos, pos2: BlockPos, pos: BlockPos): Boolean {
        return pos.x in getRange(pos1.x, pos2.x) &&
                pos.y in getRange(pos1.y, pos2.y) &&
                pos.z in getRange(pos1.z, pos2.z)
    }

    /** Returns the inclusive integer range covering [a] and [b] regardless of order. */
    private fun getRange(a: Int, b: Int): IntRange = min(a, b)..max(a, b)

    /** Returns the block position [player] is looking at within [maxDistance] blocks, or `null`. */
    fun getTargetedBlockPos(player: ServerPlayer, maxDistance: Double = 32.0): BlockPos? {
        val eyePos = player.eyePosition
        val hit = player.level().clip(
            ClipContext(
                eyePos,
                eyePos.add(player.lookAngle.scale(maxDistance)),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player,
            )
        )
        return if (hit.type == HitResult.Type.BLOCK) hit.blockPos else null
    }

    /** Resolves a [ServerLevel] from a dimension key string like `"minecraft:overworld"`. */
    fun getLevelByKey(server: MinecraftServer, worldKey: String): ServerLevel? {
        val rl = runCatching { Identifier.parse(worldKey) }.getOrNull() ?: return null
        val key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, rl)
        return server.getLevel(key)
    }

    /** Returns the dimension key string (e.g. `"minecraft:overworld"`) for a given [ServerLevel]. */
    fun getLevelKey(level: ServerLevel): String =
        //? if >=1.21.11 {
        level.dimension().identifier().toString()
        //?} else
        /*level.dimension().location().toString()*/

    /** Returns the dimension key string for a [ServerPlayer]'s current server level. */
    fun getPlayerLevelKey(player: ServerPlayer): String = getLevelKey(playerServerLevel(player))

    /**
     * Returns the owning [MinecraftServer] for [player]. `ServerPlayer.server` is a private field;
     * the running server is only reachable through the player's [ServerLevel] (public `.server`).
     */
    fun playerServer(player: ServerPlayer): MinecraftServer = playerServerLevel(player).server

    /**
     * Returns the [ServerLevel] of a [ServerPlayer]. `ServerPlayer.serverLevel()` was renamed to
     * `level()` (covariant `ServerLevel`) in >=1.21.11. Resolved via a direct, version-gated call so
     * loom remaps it correctly — a reflective by-name lookup fails in the remapped jar (the method is
     * intermediary `method_...` there, not the literal `"level"` / `"serverLevel"` string).
     */
    private fun playerServerLevel(player: ServerPlayer): ServerLevel =
        //? if >=1.21.11 {
        player.level()
        //?} else
        /*player.serverLevel()*/

    /**
     * Describes a region in 3D space.
     */
    @NullMarked
    data class RegionData(
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int,
        val deltaX: Int,
        val deltaY: Int,
        val deltaZ: Int,
    ) {
        /** Screen width in blocks; for vertical (floor / ceiling) facings this is the X-axis span. */
        fun screenWidth(isVertical: Boolean): Int = if (isVertical) deltaX else max(deltaX, deltaZ)

        /** Screen height in blocks; for vertical (floor / ceiling) facings this is the Z-axis span. */
        fun screenHeight(isVertical: Boolean): Int = if (isVertical) deltaZ else deltaY

        /** Returns the min-corner [Location] of this region in [world]. */
        @PaperOnly
        fun getMinLocation(world: World?): Location =
            Location(world, minX.toDouble(), minY.toDouble(), minZ.toDouble())

        /** Returns the max-corner [Location] of this region in [world]. */
        @PaperOnly
        fun getMaxLocation(world: World?): Location =
            Location(world, maxX.toDouble(), maxY.toDouble(), maxZ.toDouble())

        /** Returns the min-corner [BlockPos] of this region. */
        fun getMinBlockPos(): BlockPos = BlockPos(minX, minY, minZ)

        /** Returns the max-corner [BlockPos] of this region. */
        fun getMaxBlockPos(): BlockPos = BlockPos(maxX, maxY, maxZ)
    }
}
