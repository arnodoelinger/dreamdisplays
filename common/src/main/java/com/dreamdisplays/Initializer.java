package com.dreamdisplays;

import com.dreamdisplays.display.DisplayScreen;
import com.dreamdisplays.ffmpeg.FFmpegBinary;
import com.dreamdisplays.net.Packets.*;
import com.dreamdisplays.client.ui.DisplayMenu;
import com.dreamdisplays.display.DisplayManager;
import com.dreamdisplays.display.DisplaySettings;
import com.dreamdisplays.util.FacingUtil;
import com.dreamdisplays.util.RayCastingUtil;
import com.dreamdisplays.util.GeneralUtil;
import com.dreamdisplays.ytdlp.FormatDiskCache;
import com.dreamdisplays.ytdlp.YtDlp;
import me.inotsleep.utils.logging.LoggingManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Vector3i;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main initializer.
 */
@NullMarked
public class Initializer {

    public static final String MOD_ID = "dreamdisplays";
    private static final boolean[] wasPressed = {false};
    private static final AtomicBoolean wasInMultiplayer = new AtomicBoolean(
            false
    );
    private static final AtomicReference<@Nullable ClientLevel> lastLevel =
            new AtomicReference<>(null);
    private static final AtomicBoolean wasFocused = new AtomicBoolean(false);
    public static Config config = new Config(new File("./config/" + MOD_ID));
    public static Thread timerThread = new Thread(() -> {
        boolean running = true;
        while (running) {
            DisplayManager.getScreens().forEach(DisplayScreen::reloadQuality);
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }, "dreamdisplays-quality-refresh");
    public static boolean isOnScreen = false;
    public static boolean focusMode = false;
    public static boolean displaysEnabled = true;
    public static boolean isPremium = false;
    public static boolean isReportingEnabled = true;
    private static int unloadCheckTick = 0;
    private static @Nullable DisplayScreen hoveredDisplayScreen = null;
    private static Mod mod;

    public static Config getConfig() {
        return config;
    }

    public static void onModInit(Mod dreamDisplaysMod) {
        mod = dreamDisplaysMod;
        LoggingManager.setLogger(LoggerFactory.getLogger(MOD_ID));
        LoggingManager.info("Starting Dream Displays...");
        config.reload();

        // Load client display settings
        DisplaySettings.load();

        YtDlp.prewarmAsync();
        FFmpegBinary.prewarmAsync();
        // Drop yt-dlp cache entries older than the URL TTL on a background thread
        new Thread(FormatDiskCache::sweepExpired,
                "dreamdisplays-cache-sweep").start();
        new Focuser().start();

        timerThread.start();
    }

    public static void onDisplayInfoPacket(Info packet) {
        if (!Initializer.displaysEnabled) return;

        // Kick off yt-dlp early – by the time we render the display the formats are
        // already cached.
        YtDlp.prefetchFormats(packet.url());

        if (DisplayManager.screens.containsKey(packet.uuid())) {
            DisplayScreen displayScreen = DisplayManager.screens.get(packet.uuid());
            displayScreen.updateData(packet);
            return;
        }

        // Check if player is in range before creating the display
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            // Get saved render distance or use default
            DisplaySettings.FullDisplayData savedData = DisplaySettings.getDisplayData(packet.uuid());
            int renderDistance = savedData != null ? savedData.renderDistance : config.defaultDistance;

            // Screen.getDistanceToScreen()
            int x = packet.pos().x;
            int y = packet.pos().y;
            int z = packet.pos().z;
            int width = packet.width();
            int height = packet.height();
            String facing = packet.facingUtil().toString();

            int maxX = x;
            int maxY = y + height - 1;
            int maxZ = z;

            switch (facing) {
                case "NORTH", "SOUTH" -> maxX += width - 1;
                case "EAST", "WEST" -> maxZ += width - 1;
            }

            BlockPos playerPos = player.blockPosition();
            int clampedX = Math.min(Math.max(playerPos.getX(), x), maxX);
            int clampedY = Math.min(Math.max(playerPos.getY(), y), maxY);
            int clampedZ = Math.min(Math.max(playerPos.getZ(), z), maxZ);

            BlockPos closestPos = new BlockPos(clampedX, clampedY, clampedZ);
            double distance = Math.sqrt(playerPos.distSqr(closestPos));

            // Only create if within render distance
            if (distance > renderDistance) {
                return;
            }
        }

        DisplayManager.unloadedScreens.remove(packet.uuid());

        createScreen(
                packet.uuid(),
                packet.ownerUuid(),
                packet.pos(),
                packet.facingUtil(),
                packet.width(),
                packet.height(),
                packet.url(),
                packet.lang(),
                packet.isSync()
        );
    }

    public static void onDisplayEnabledPacket(DisplayEnabled packet) {
        Initializer.displaysEnabled = packet.enabled();
        config.displaysEnabled = packet.enabled();
        config.save();
    }

    public static void createScreen(
            UUID uuid,
            UUID ownerUuid,
            Vector3i pos,
            FacingUtil facingUtil,
            int width,
            int height,
            String code,
            String lang,
            boolean isSync
    ) {
        DisplayScreen displayScreen = new DisplayScreen(
                uuid,
                ownerUuid,
                pos.x(),
                pos.y(),
                pos.z(),
                facingUtil.toString(),
                width,
                height,
                isSync
        );

        DisplaySettings.FullDisplayData savedData = DisplaySettings.getDisplayData(uuid);
        int renderDistance = savedData != null ? savedData.renderDistance : config.defaultDistance;
        displayScreen.setRenderDistance(renderDistance);

        DisplayManager.registerScreen(displayScreen);
        if (!Objects.equals(code, "")) displayScreen.loadVideo(code, lang);
    }

    public static void onSyncPacket(Sync packet) {
        if (!DisplayManager.screens.containsKey(packet.uuid())) return;
        DisplayScreen displayScreen = DisplayManager.screens.get(packet.uuid());
        if (displayScreen != null) {
            displayScreen.updateData(packet);
        }
    }

    // Restore a screen from cached data (when player enters render distance)
    private static void restoreScreen(DisplaySettings.FullDisplayData data) {
        DisplayScreen displayScreen = new DisplayScreen(
                data.uuid,
                data.ownerUuid,
                data.x,
                data.y,
                data.z,
                data.facing,
                data.width,
                data.height,
                data.isSync
        );

        displayScreen.setRenderDistance(data.renderDistance);
        displayScreen.setSavedTimeNanos(data.currentTimeNanos);
        displayScreen.setVolume(data.volume);
        displayScreen.setQuality(data.quality);
        displayScreen.setBrightness(data.brightness);
        displayScreen.muted = data.muted;

        DisplayManager.screens.put(displayScreen.getUUID(), displayScreen);

        if (data.videoUrl != null && !data.videoUrl.isEmpty()) {
            displayScreen.loadVideo(data.videoUrl, data.lang != null ? data.lang : "");
        }
    }

    private static void checkVersionAndSendPacket() {
        try {
            String version = GeneralUtil.getModVersion();
            sendPacket(new Version(version));
        } catch (Exception e) {
            LoggingManager.error("Unable to get version", e);
        }
    }

    public static void onEndTick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level != null && minecraft.getCurrentServer() != null) {
            if (lastLevel.get() == null) {
                lastLevel.set(level);
                checkVersionAndSendPacket();
            }

            if (level != lastLevel.get()) {
                lastLevel.set(level);

                DisplayManager.unloadAll();
                hoveredDisplayScreen = null;

                checkVersionAndSendPacket();
            }

            wasInMultiplayer.set(true);
        } else {
            if (wasInMultiplayer.get()) {
                wasInMultiplayer.set(false);
                DisplayManager.unloadAll();
                hoveredDisplayScreen = null;
                lastLevel.set(null);
                return;
            }
        }

        BlockHitResult result = RayCastingUtil.rCBlock(64);
        hoveredDisplayScreen = null;
        Initializer.isOnScreen = false;
        Player player = minecraft.player;
        if (player == null) return;

        unloadCheckTick++;
        if (unloadCheckTick >= 10 && Initializer.displaysEnabled && !DisplayManager.unloadedScreens.isEmpty()) {
            unloadCheckTick = 0;
            // Collect screens to restore first to avoid ConcurrentModificationException
            java.util.List<DisplaySettings.FullDisplayData> toRestore = new java.util.ArrayList<>();

            for (DisplaySettings.FullDisplayData data : DisplayManager.unloadedScreens.values()) {
                if (data.videoUrl == null || data.videoUrl.isEmpty()) continue;

                // Screen.getDistanceToScreen
                int maxX = data.x;
                int maxY = data.y + data.height - 1;
                int maxZ = data.z;

                switch (data.facing) {
                    case "NORTH", "SOUTH" -> maxX += data.width - 1;
                    case "EAST", "WEST" -> maxZ += data.width - 1;
                }

                BlockPos playerPos = player.blockPosition();
                int clampedX = Math.min(Math.max(playerPos.getX(), data.x), maxX);
                int clampedY = Math.min(Math.max(playerPos.getY(), data.y), maxY);
                int clampedZ = Math.min(Math.max(playerPos.getZ(), data.z), maxZ);

                BlockPos closestPos = new BlockPos(clampedX, clampedY, clampedZ);
                double distance = Math.sqrt(playerPos.distSqr(closestPos));

                // If player is now in range, mark for restoration
                if (distance <= data.renderDistance) {
                    toRestore.add(data);
                }
            }

            // Now restore outside the iteration
            for (DisplaySettings.FullDisplayData data : toRestore) {
                DisplayManager.unloadedScreens.remove(data.uuid);
                restoreScreen(data);
            }
        }

        for (DisplayScreen displayScreen : DisplayManager.getScreens()) {
            double displayRenderDistance = displayScreen.getRenderDistance();

            if (
                    displayRenderDistance <
                            displayScreen.getDistanceToScreen(player.blockPosition()) ||
                            !Initializer.displaysEnabled
            ) {
                DisplayManager.saveScreenData(displayScreen);
                DisplayManager.unregisterScreen(displayScreen);
                if (hoveredDisplayScreen == displayScreen) {
                    hoveredDisplayScreen = null;
                    Initializer.isOnScreen = false;
                }
            } else {
                if (result != null) if (
                        displayScreen.isInScreen(result.getBlockPos())
                ) {
                    hoveredDisplayScreen = displayScreen;
                    Initializer.isOnScreen = true;
                }

                displayScreen.tick(player.blockPosition());
            }
        }

        long window = minecraft.getWindow().handle();
        boolean pressed =
                GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) ==
                        GLFW.GLFW_PRESS;

        if (pressed && !wasPressed[0]) {
            if (player.isShiftKeyDown()) {
                checkAndOpenScreen();
            }
        }

        wasPressed[0] = pressed;

        if (Initializer.focusMode && hoveredDisplayScreen != null) {
            player.addEffect(
                    new MobEffectInstance(
                            MobEffects.BLINDNESS,
                            20 * 2,
                            1,
                            false,
                            false,
                            false
                    )
            );

            wasFocused.set(true);
        } else if (!Initializer.focusMode && wasFocused.get()) {
            player.removeEffect(MobEffects.BLINDNESS);
            wasFocused.set(false);
        }
    }

    private static void checkAndOpenScreen() {
        if (hoveredDisplayScreen == null) return;
        DisplayMenu.open(hoveredDisplayScreen);
    }

    public static void sendPacket(CustomPacketPayload packet) {
        mod.sendPacket(packet);
    }

    public static void onDeletePacket(Delete packet) {
        DisplayScreen displayScreen = DisplayManager.screens.get(packet.uuid());
        if (displayScreen != null) {
            DisplayManager.unregisterScreen(displayScreen);
        }

        DisplayManager.unloadedScreens.remove(packet.uuid());

        DisplaySettings.removeDisplay(packet.uuid());
        LoggingManager.info(
                "Display deleted and removed from saved data: " + packet.uuid()
        );
    }

    public static void onStop() {
        DisplayManager.saveAllScreens();
        timerThread.interrupt();
        DisplayManager.unloadAll();
        Focuser.instance.interrupt();
    }

    public static void onPremiumPacket(Premium packet) {
        isPremium = packet.premium();
    }

    public static void onReportEnabledPacket(ReportEnabled packet) {
        isReportingEnabled = packet.enabled();
    }

    public static void onClearCachePacket(ClearCache packet) {
        // Remove specific displays from active screens and cache
        for (UUID displayUuid : packet.displayUuids()) {
            DisplayScreen displayScreen = DisplayManager.screens.get(displayUuid);
            if (displayScreen != null) {
                displayScreen.unregister();
                DisplayManager.screens.remove(displayUuid);
            }

            // Remove from persistent storage
            DisplaySettings.removeDisplay(displayUuid);
        }
    }
}
