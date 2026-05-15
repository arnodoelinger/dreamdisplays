package com.dreamdisplays.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Utility class for ray casting operations.
 */
@NullMarked
public class RayCastingUtil {

    @Nullable
    public static BlockHitResult rCBlock(double maxDistance) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;

        if (player == null || minecraft.level == null) return null;

        Vec3 start = player.getEyePosition(1.0f);
        Vec3 direction = player.getViewVector(1.0f);
        Vec3 end = start.add(direction.scale(maxDistance));

        BlockHitResult hitResult = minecraft.level.clip(
                new net.minecraft.world.level.ClipContext(
                        start,
                        end,
                        net.minecraft.world.level.ClipContext.Block.OUTLINE,
                        net.minecraft.world.level.ClipContext.Fluid.NONE,
                        player
                )
        );

        if (hitResult.getType() == HitResult.Type.BLOCK) {
            return hitResult;
        }

        return null;
    }
}
