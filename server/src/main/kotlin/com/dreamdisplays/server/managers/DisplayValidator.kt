package com.dreamdisplays.server.managers

import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.server.Main
import com.dreamdisplays.server.datatypes.PaperSelectionData
import com.dreamdisplays.server.utils.MessageUtil
import org.bukkit.entity.Player

/**
 * Validates a player's block selection before a display is created. Checks facing depth, size
 * limits, Y-range, and that every block in the region is the configured base material.
 */
@PaperOnly object DisplayValidator {
    private const val VALID_DISPLAY = 6

    /**
     * Validates a player's selection. Returns [VALID_DISPLAY] if the selection forms a legal
     * display, or a non-zero error code consumed by [sendErrorMessage].
     */
    fun isValidDisplay(data: PaperSelectionData): Int {
        val pos1 = data.pos1 ?: return 0
        val pos2 = data.pos2 ?: return 0
        if (pos1.world != pos2.world) return 1

        val (minX, maxX) = minOf(pos1.blockX, pos2.blockX) to maxOf(pos1.blockX, pos2.blockX)
        val (minY, maxY) = minOf(pos1.blockY, pos2.blockY) to maxOf(pos1.blockY, pos2.blockY)
        val (minZ, maxZ) = minOf(pos1.blockZ, pos2.blockZ) to maxOf(pos1.blockZ, pos2.blockZ)

        val deltaX = maxX - minX + 1
        val deltaZ = maxZ - minZ + 1
        val face = data.getFace()

        if (deltaX != kotlin.math.abs(face.modX) && deltaZ != kotlin.math.abs(face.modZ)) return 2

        val width = maxOf(deltaX, deltaZ)
        val height = maxY - minY + 1

        if (height < Main.config.settings.minHeight || width < Main.config.settings.minWidth) return 3
        if (height > Main.config.settings.maxHeight || width > Main.config.settings.maxWidth) return 4

        val required = Main.config.settings.baseMaterial
        val world = pos1.world ?: return 1
        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ)
            if (world.getBlockAt(x, y, z).type != required) return 5

        return VALID_DISPLAY
    }

    /** Sends the localized validation-error message corresponding to [code]. */
    fun sendErrorMessage(player: Player, code: Int) {
        val key = when (code) {
            0 -> "secondPointNotSelected"
            1 -> "displayOverlap"
            2 -> "structureWrongDepth"
            3 -> "structureTooSmall"
            4 -> "structureTooLarge"
            5 -> "wrongStructure"
            else -> return
        }
        MessageUtil.sendMessage(player, key)
    }
}
