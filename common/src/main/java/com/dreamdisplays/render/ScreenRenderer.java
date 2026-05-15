package com.dreamdisplays.render;

import com.dreamdisplays.display.DisplayManager;
import com.dreamdisplays.display.DisplayScreen;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.jspecify.annotations.NullMarked;

/**
 * Renders screens in the world.
 */
@NullMarked
public class ScreenRenderer {

    // Renders all screens in the world relative to the camera position
    public static void render(PoseStack stack, Camera camera) {
        Vec3 cameraPos = camera.position();
        for (DisplayScreen displayScreen : DisplayManager.getScreens()) {
            if (displayScreen.texture == null) displayScreen.createTexture();

            stack.pushPose();

            // Translate the matrix stack to the player's screen position
            BlockPos pos = displayScreen.getPos();
            Vec3 screenCenter = Vec3.atLowerCornerOf(pos);
            Vec3 relativePos = screenCenter.subtract(cameraPos);
            stack.translate(relativePos.x, relativePos.y, relativePos.z);

            // Move the matrix stack forward based on the screen's facing direction
            Tesselator tessellator = Tesselator.getInstance();

            renderScreenTexture(displayScreen, stack, tessellator);

            stack.popPose();
        }
    }

    // Renders the texture of a single screen
    private static void renderScreenTexture(
            DisplayScreen displayScreen,
            PoseStack stack,
            Tesselator tessellator
    ) {
        stack.pushPose();
        moveForward(stack, displayScreen.getFacing(), 0.008f);

        switch (displayScreen.getFacing()) {
            case "NORTH":
                moveHorizontal(stack, "NORTH", -(displayScreen.getWidth()));
                moveForward(stack, "NORTH", 1);
                break;
            case "SOUTH":
                moveHorizontal(stack, "SOUTH", 1);
                moveForward(stack, "SOUTH", 1);
                break;
            case "EAST":
                moveHorizontal(stack, "EAST", -(displayScreen.getWidth() - 1));
                moveForward(stack, "EAST", 2);
                break;
        }

        // Fix the rotation of the matrix stack based on the screen's facing direction
        fixRotation(stack, displayScreen.getFacing());
        stack.scale(displayScreen.getWidth(), displayScreen.getHeight(), 0);

        // Render the screen texture, a loading pulse, or black
        if (
                displayScreen.isVideoStarted() &&
                        displayScreen.texture != null &&
                        displayScreen.renderType != null
        ) {
            renderGpuTexture(stack, tessellator, displayScreen.renderType);
        } else if (displayScreen.renderType != null) {
            if (displayScreen.errored) {
                renderColor(stack, tessellator, displayScreen.renderType, 35, 5, 5);
            } else {
                float pulse = (float) Math.abs(Math.sin(System.nanoTime() / 1_500_000_000.0 * Math.PI));
                int v = (int) (10 + pulse * 20);
                renderColor(stack, tessellator, displayScreen.renderType, v, v, v);
            }
        }
        stack.popPose();
    }

    // Prevent rotation issues
    private static void fixRotation(PoseStack stack, String facing) {
        final Quaternionf rotation;

        switch (facing) {
            case "NORTH":
                rotation = new Quaternionf().rotationY(
                        (float) Math.toRadians(180)
                );
                stack.translate(0, 0, 1);
                break;
            case "WEST":
                rotation = new Quaternionf().rotationY(
                        (float) Math.toRadians(-90.0)
                );
                stack.translate(0, 0, 0);
                break;
            case "EAST":
                rotation = new Quaternionf().rotationY(
                        (float) Math.toRadians(90.0)
                );
                stack.translate(-1, 0, 1);
                break;
            default:
                rotation = new Quaternionf();
                stack.translate(-1, 0, 0);
                break;
        }
        stack.mulPose(rotation);
    }

    // Moves the matrix stack forward based on the facing direction
    private static void moveForward(
            PoseStack stack,
            String facing,
            float amount
    ) {
        switch (facing) {
            case "NORTH":
                stack.translate(0, 0, -amount);
                break;
            case "WEST":
                stack.translate(-amount, 0, 0);
                break;
            case "EAST":
                stack.translate(amount, 0, 0);
                break;
            default:
                stack.translate(0, 0, amount);
                break;
        }
    }

    // Moves the matrix stack horizontally based on the facing direction
    private static void moveHorizontal(
            PoseStack stack,
            String facing,
            float amount
    ) {
        switch (facing) {
            case "NORTH":
                stack.translate(-amount, 0, 0);
                break;
            case "WEST":
                stack.translate(0, 0, amount);
                break;
            case "EAST":
                stack.translate(0, 0, -amount);
                break;
            default:
                stack.translate(amount, 0, 0);
                break;
        }
    }

    // Renders a GPU texture onto a quad using the provided matrix stack and tessellator
    private static void renderGpuTexture(
            PoseStack stack,
            Tesselator tesselator,
            RenderType type
    ) {
        Matrix4f pose = stack.last().pose();

        BufferBuilder builder = tesselator.begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.BLOCK
        );

        builder
                .addVertex(pose, 0f, 0f, 0f)
                .setColor(255, 255, 255, 255)
                .setUv(0f, 1f)
                .setLight(0xF000F0)
                .setNormal(0f, 0f, 1f);

        builder
                .addVertex(pose, 1f, 0f, 0f)
                .setColor(255, 255, 255, 255)
                .setUv(1f, 1f)
                .setLight(0xF000F0)
                .setNormal(0f, 0f, 1f);

        builder
                .addVertex(pose, 1f, 1f, 0f)
                .setColor(255, 255, 255, 255)
                .setUv(1f, 0f)
                .setLight(0xF000F0)
                .setNormal(0f, 0f, 1f);

        builder
                .addVertex(pose, 0f, 1f, 0f)
                .setColor(255, 255, 255, 255)
                .setUv(0f, 0f)
                .setLight(0xF000F0)
                .setNormal(0f, 0f, 1f);

        MeshData built = builder.buildOrThrow();
        type.draw(built);
    }

    // Renders a solid color square with the specified RGB values
    private static void renderColor(
            PoseStack stack,
            Tesselator tesselator,
            RenderType type,
            int r,
            int g,
            int b
    ) {
        Matrix4f pose = stack.last().pose();

        BufferBuilder builder = tesselator.begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.BLOCK
        );

        builder
                .addVertex(pose, 0f, 0f, 0f)
                .setColor(r, g, b, 255)
                .setUv(0f, 1f)
                .setLight(0xF000F0)
                .setNormal(0f, 0f, 1f);

        builder
                .addVertex(pose, 1f, 0f, 0f)
                .setColor(r, g, b, 255)
                .setUv(1f, 1f)
                .setLight(0xF000F0)
                .setNormal(0f, 0f, 1f);

        builder
                .addVertex(pose, 1f, 1f, 0f)
                .setColor(r, g, b, 255)
                .setUv(1f, 0f)
                .setLight(0xF000F0)
                .setNormal(0f, 0f, 1f);

        builder
                .addVertex(pose, 0f, 1f, 0f)
                .setColor(r, g, b, 255)
                .setUv(0f, 0f)
                .setLight(0xF000F0)
                .setNormal(0f, 0f, 1f);

        MeshData built = builder.buildOrThrow();
        type.draw(built);
    }
}
