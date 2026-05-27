package com.dreamdisplays.server.utils

import io.github.arsmotorin.ofrat.PaperOnly
import org.bukkit.Color

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.jspecify.annotations.NullMarked
import java.util.*

/**
 * Utility object for outlining areas with particles.
 */
@PaperOnly @NullMarked object OutlinerUtil {
    private val activeOutlines: MutableMap<UUID, OutlineData> = mutableMapOf()

    data class OutlineData(
        val box: BoundingBox,
        val world: org.bukkit.World,
    )

    /** Stores the latest outline for [player] and renders it once between [pos1] and [pos2]. */
    fun showOutline(player: Player, pos1: Location, pos2: Location) {
        val world = pos1.world ?: return

        val minX = minOf(pos1.blockX, pos2.blockX).toDouble()
        val minY = minOf(pos1.blockY, pos2.blockY).toDouble()
        val minZ = minOf(pos1.blockZ, pos2.blockZ).toDouble()
        val maxX = maxOf(pos1.blockX, pos2.blockX) + 1.0
        val maxY = maxOf(pos1.blockY, pos2.blockY) + 1.0
        val maxZ = maxOf(pos1.blockZ, pos2.blockZ) + 1.0

        val box = BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)
        activeOutlines[player.uniqueId] = OutlineData(box, world)

        // Draw the outline using particles
        drawOutlineBox(player, box, world)
    }

    /** Emits dust particles along the 12 edges of [box] visible only to [player]. */
    private fun drawOutlineBox(player: Player, box: BoundingBox, world: org.bukkit.World) {
        val color = org.bukkit.Color.fromRGB(0, 255, 255)

        // Bottom rectangle
        drawLine(
            player,
            Location(world, box.minX, box.minY, box.minZ),
            Location(world, box.maxX, box.minY, box.minZ),
            color
        )
        drawLine(
            player,
            Location(world, box.maxX, box.minY, box.minZ),
            Location(world, box.maxX, box.minY, box.maxZ),
            color
        )
        drawLine(
            player,
            Location(world, box.maxX, box.minY, box.maxZ),
            Location(world, box.minX, box.minY, box.maxZ),
            color
        )
        drawLine(
            player,
            Location(world, box.minX, box.minY, box.maxZ),
            Location(world, box.minX, box.minY, box.minZ),
            color
        )

        // Top rectangle
        drawLine(
            player,
            Location(world, box.minX, box.maxY, box.minZ),
            Location(world, box.maxX, box.maxY, box.minZ),
            color
        )
        drawLine(
            player,
            Location(world, box.maxX, box.maxY, box.minZ),
            Location(world, box.maxX, box.maxY, box.maxZ),
            color
        )
        drawLine(
            player,
            Location(world, box.maxX, box.maxY, box.maxZ),
            Location(world, box.minX, box.maxY, box.maxZ),
            color
        )
        drawLine(
            player,
            Location(world, box.minX, box.maxY, box.maxZ),
            Location(world, box.minX, box.maxY, box.minZ),
            color
        )

        // Vertical edges
        drawLine(
            player,
            Location(world, box.minX, box.minY, box.minZ),
            Location(world, box.minX, box.maxY, box.minZ),
            color
        )
        drawLine(
            player,
            Location(world, box.maxX, box.minY, box.minZ),
            Location(world, box.maxX, box.maxY, box.minZ),
            color
        )
        drawLine(
            player,
            Location(world, box.maxX, box.minY, box.maxZ),
            Location(world, box.maxX, box.maxY, box.maxZ),
            color
        )
        drawLine(
            player,
            Location(world, box.minX, box.minY, box.maxZ),
            Location(world, box.minX, box.maxY, box.maxZ),
            color
        )
    }

    /** Spawns dust particles spaced every 0.5 blocks along the segment from [from] to [to]. */
    private fun drawLine(player: Player, from: Location, to: Location, color: Color) {
        val distance = from.distance(to)
        val particles = (distance * 2).toInt()

        if (particles <= 0) return

        val dustOptions = Particle.DustOptions(color, 0.5f)

        for (i in 0..particles) {
            val t = i / particles.toDouble()
            val x = from.x + (to.x - from.x) * t
            val y = from.y + (to.y - from.y) * t
            val z = from.z + (to.z - from.z) * t

            player.spawnParticle(
                Particle.DUST,
                x, y, z,
                1, 0.0, 0.0, 0.0, 0.0,
                dustOptions
            )
        }
    }
}
