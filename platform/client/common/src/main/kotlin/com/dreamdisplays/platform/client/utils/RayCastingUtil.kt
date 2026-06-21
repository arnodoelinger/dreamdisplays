package com.dreamdisplays.platform.client.utils

import net.minecraft.client.Minecraft
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/** Ray-casting utilities for block interaction and placement. */
object RayCastingUtil {
    /** Casts a ray from the player's eye position up to [maxDistance] blocks and returns the hit block, or null. */
    fun rCBlock(maxDistance: Double): BlockHitResult? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        val level = mc.level ?: return null

        val start = player.getEyePosition(1.0f)
        val end = start.add(player.getViewVector(1.0f).scale(maxDistance))

        val hit = level.clip(ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        return if (hit.type == HitResult.Type.BLOCK) hit else null
    }
}
