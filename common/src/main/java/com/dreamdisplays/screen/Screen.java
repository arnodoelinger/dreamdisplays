package com.dreamdisplays.screen;

import com.dreamdisplays.Initializer;
import com.dreamdisplays.net.Packets.Info;
import com.dreamdisplays.net.Packets.RequestSync;
import com.dreamdisplays.net.Packets.SetVideo;
import com.dreamdisplays.net.Packets.Sync;
import com.dreamdisplays.ytdlp.YtDlp;
import me.inotsleep.utils.logging.LoggingManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a video display screen in the game world.
 */
@NullMarked
public class Screen {

    private static final int DEFAULT_QUALITY = 720;
    private static final int INIT_WAIT_TIMEOUT_MS = 200_000;
    private static final int INIT_WAIT_INTERVAL_MS = 25;
    private static final long SYNC_SEEK_TOLERANCE_NS = 750_000_000L;
    private final UUID uuid;
    private final UUID ownerUuid;
    private final AtomicLong mediaPlayerGeneration = new AtomicLong();
    public boolean owner;
    public boolean errored;
    public boolean isSync;
    public boolean muted;
    public @Nullable DynamicTexture texture = null;
    public @Nullable Identifier textureId = null;
    public @Nullable RenderType renderType = null;
    public int textureWidth = 0;
    public int textureHeight = 0;
    private int x;
    private int y;
    private int z;
    private String facing;
    private int width;
    private int height;
    private float volume;
    private float brightness;
    private boolean videoStarted;
    private boolean paused;
    private String quality;
    private long savedTimeNanos = 0;
    private int renderDistance = 64;
    private @Nullable MediaPlayer mediaPlayer;
    private @Nullable String videoUrl;
    private boolean clientUrlOverride = false;
    private transient @Nullable BlockPos blockPos;
    private @Nullable String lang;

    // Constructor for the Screen class
    public Screen(
            UUID uuid,
            UUID ownerUuid,
            int x,
            int y,
            int z,
            String facing,
            int width,
            int height,
            boolean isSync
    ) {
        this.uuid = uuid;
        this.ownerUuid = ownerUuid;
        this.x = x;
        this.y = y;
        this.z = z;
        this.facing = facing;
        this.width = width;
        this.height = height;
        owner =
                Minecraft.getInstance().player != null &&
                        (ownerUuid + "").equals(
                                Minecraft.getInstance().player.getUUID() + ""
                        );

        // Load saved settings for this display
        Settings.DisplaySettings savedSettings = Settings.getSettings(uuid);
        this.volume = savedSettings.volume;
        this.quality = savedSettings.quality;
        this.brightness = savedSettings.brightness;
        this.muted = savedSettings.muted;
        this.paused = savedSettings.paused;

        if (isSync) {
            sendRequestSyncPacket();
        }
    }

    // Creates a custom RenderType for rendering the screen texture
    private static RenderType createRenderType(Identifier id) {
        return RenderType.create(
                "dream-displays",
                RenderSetup.builder(RenderPipelines.SOLID_BLOCK)
                        .withTexture("Sampler0", id)
                        .bufferSize(RenderType.BIG_BUFFER_SIZE)
                        .affectsCrumbling()
                        .useLightmap()
                        .createRenderSetup()
        );
    }

    // Loads a video from a given URL and language
    public void loadVideo(String videoUrl, String lang) {
        if (!clientUrlOverride) {
            Settings.setUrlOverride(uuid, null, null);
        }
        loadVideoInternal(videoUrl, lang, true);
    }

    public void playVideoNow(String videoUrl, String lang) {
        this.paused = false;
        this.savedTimeNanos = 0L;
        loadVideoInternal(videoUrl, lang, false);
    }

    public void playSuggestedVideo(String videoUrl, String lang) {
        this.clientUrlOverride = true;
        Settings.setUrlOverride(uuid, videoUrl, lang);
        Initializer.sendPacket(new SetVideo(uuid, videoUrl, lang));
        playVideoNow(videoUrl, lang);
    }

    private void loadVideoInternal(String videoUrl, String lang, boolean preservePausedState) {
        if (Objects.equals(videoUrl, "")) return;

        YtDlp.prefetchFormats(videoUrl);

        long generation = mediaPlayerGeneration.incrementAndGet();
        MediaPlayer oldPlayer = mediaPlayer;
        mediaPlayer = null;
        videoStarted = false;
        errored = false;
        if (oldPlayer != null) {
            // Synchronous stop
            oldPlayer.stop();
        }

        // Load the video URL and language into the screen
        this.videoUrl = videoUrl;
        this.lang = lang;
        boolean shouldBePaused = preservePausedState && this.paused;
        mediaPlayer = new MediaPlayer(videoUrl, lang, this);
        int qualityInt = parseQualityOrDefault();
        textureWidth = (int) ((width / (double) height) * qualityInt);
        textureHeight = qualityInt;

        waitForMFInit(generation, () -> {
            startVideo();
            if (shouldBePaused) {
                this.paused = true;
                if (mediaPlayer != null) {
                    mediaPlayer.pause();
                }
            }
        });

        Minecraft.getInstance().execute(this::reloadTexture);
    }

    // Updates the screen data based on a DisplayInfoPacket
    public void updateData(Info packet) {
        this.x = packet.pos().x;
        this.y = packet.pos().y;
        this.z = packet.pos().z;

        this.facing = String.valueOf(packet.facing());

        this.width = packet.width();
        this.height = packet.height();
        this.isSync = packet.isSync();

        owner =
                Minecraft.getInstance().player != null &&
                        (packet.ownerUuid() + "").equals(
                                Minecraft.getInstance().player.getUUID() + ""
                        );

        if (
                !Objects.equals(videoUrl, packet.url()) ||
                        !Objects.equals(lang, packet.lang())
        ) {
            // If the user picked a different video locally via the suggestions panel,
            // ignore the server's URL override – that packet is just the canonical
            // URL the server still believes the display should show.
            if (clientUrlOverride) return;

            // Check for a persisted URL override (survives rejoin)
            Settings.DisplaySettings ds = Settings.getSettings(uuid);
            if (ds.urlOverride != null && !ds.urlOverride.isEmpty()) {
                this.clientUrlOverride = true;
                String overrideUrl = ds.urlOverride;
                String overrideLang = ds.langOverride != null ? ds.langOverride : packet.lang();
                this.paused = false;
                loadVideo(overrideUrl, overrideLang);
                return;
            }

            this.paused = false;
            loadVideo(packet.url(), packet.lang());
            if (isSync) {
                sendRequestSyncPacket();
            }
        }
    }

    // Sends a RequestSyncPacket to the server to request synchronization data
    private void sendRequestSyncPacket() {
        Initializer.sendPacket(new RequestSync(uuid));
    }

    // Updates the screen data based on a SyncPacket
    public void updateData(Sync packet) {
        isSync = packet.isSync();
        if (!isSync) return;

        long nanos = System.nanoTime();
        boolean desiredPaused = packet.currentState();

        waitForMFInit(() -> {
            if (!videoStarted) {
                this.paused = desiredPaused;
                startVideo();
            }

            long lostTime = System.nanoTime() - nanos;
            long targetTime = Math.max(0L, packet.currentTime() + lostTime);
            long currentTime = getCurrentTimeNanos();
            long drift = Math.abs(targetTime - currentTime);
            boolean canSeek = canSeek();

            if (desiredPaused && !paused) {
                setPaused(true);
            }
            MediaPlayer mp = mediaPlayer;
            boolean clockRunning = mp != null && mp.isClockRunning();
            if (canSeek && clockRunning && drift > SYNC_SEEK_TOLERANCE_NS) {
                seekVideoTo(targetTime);
            }
            if (!desiredPaused && paused) {
                setPaused(false);
            }
        });
    }

    public void reloadTexture() {
        this.createTexture();
    }

    // Reloads the video quality
    public void reloadQuality() {
        if (mediaPlayer != null) {
            mediaPlayer.setQuality(quality);
        }
    }

    // Checks if a given BlockPos is within the screen boundaries
    public boolean isInScreen(BlockPos pos) {
        int maxX = x;
        int maxY = y + height - 1;
        int maxZ = z;

        switch (facing) {
            case "NORTH", "SOUTH" -> maxX += width - 1;
            default -> maxZ += width - 1;
        }

        return (
                x <= pos.getX() &&
                        maxX >= pos.getX() &&
                        y <= pos.getY() &&
                        maxY >= pos.getY() &&
                        z <= pos.getZ() &&
                        maxZ >= pos.getZ()
        );
    }

    // Checks if the video has started playing
    public boolean isVideoStarted() {
        return mediaPlayer != null && mediaPlayer.textureFilled();
    }

    // Calculates the distance from a given BlockPos to the closest point on the screen
    public double getDistanceToScreen(BlockPos pos) {
        int maxX = x;
        int maxY = y + height - 1;
        int maxZ = z;

        switch (facing) {
            case "NORTH", "SOUTH" -> maxX += width - 1;
            case "EAST", "WEST" -> maxZ += width - 1;
        }

        int clampedX = Math.min(Math.max(pos.getX(), x), maxX);
        int clampedY = Math.min(Math.max(pos.getY(), y), maxY);
        int clampedZ = Math.min(Math.max(pos.getZ(), z), maxZ);

        BlockPos closestPos = new BlockPos(clampedX, clampedY, clampedZ);

        return Math.sqrt(pos.distSqr(closestPos));
    }

    // Updates the texture to fit the current video frame
    public void fitTexture() {
        if (mediaPlayer != null && texture != null) {
            try {
                mediaPlayer.updateFrame(texture.getTexture());
            } catch (Exception e) {
                // Ignore errors if texture is not ready
            }
        }
    }

    // Returns screen position as BlockPos
    public BlockPos getPos() {
        if (blockPos == null) {
            blockPos = new BlockPos(x, y, z);
        }
        return blockPos;
    }

    // Returns screen facing direction
    public String getFacing() {
        return facing;
    }

    // Returns screen width
    public float getWidth() {
        return width;
    }

    // Returns screen height
    public float getHeight() {
        return height;
    }

    // Sets video volume
    public void setVideoVolume(float volume) {
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(volume);
        }
    }

    // Returns video quality
    public String getQuality() {
        return quality;
    }

    // Sets video quality (e.g., "480", "720", "1080", "2160")
    public void setQuality(String quality) {
        this.quality = quality;
        if (mediaPlayer != null) {
            mediaPlayer.setQuality(quality);
        }
        // reloadTexture();
        // Save settings
        Settings.updateSettings(uuid, volume, quality, brightness, muted, paused);
    }

    // Returns list of available video qualities
    public List<Integer> getQualityList() {
        if (mediaPlayer == null) return Collections.emptyList();
        return mediaPlayer.getAvailableQualities();
    }

    // Returns video brightness (0.0 to 2.0)
    public float getBrightness() {
        return brightness;
    }

    // Sets video brightness (0.0 to 2.0 - 100%)
    public void setBrightness(float brightness) {
        this.brightness = Math.max(0, Math.min(2, brightness));
        if (mediaPlayer != null) {
            mediaPlayer.setBrightness(this.brightness);
        }
        // Save settings
        Settings.updateSettings(uuid, volume, quality, this.brightness, muted, paused);
    }

    // Starts video playback
    public void startVideo() {
        if (mediaPlayer != null) {
            videoStarted = true;
            setVideoVolume(muted ? 0 : volume);
            mediaPlayer.setBrightness(brightness);
            if (paused) {
                mediaPlayer.pause();
            } else {
                mediaPlayer.play();
                paused = false;
            }
            restoreSavedTime();
        }
    }

    // Returns the paused state of the video
    public boolean getPaused() {
        return paused;
    }

    // Sets the paused state of the video
    public void setPaused(boolean paused) {
        if (!videoStarted) {
            this.paused = paused;
            waitForMFInit(() -> startVideo());
            return;
        }
        if (this.paused == paused) return;
        this.paused = paused;
        if (mediaPlayer != null) {
            if (paused) {
                mediaPlayer.pause();
            } else {
                mediaPlayer.play();
            }
        }
        Settings.updateSettings(uuid, volume, quality, brightness, muted, paused);
        if (owner && isSync) sendSync();
    }

    // Relative seek video: moves the video by a specified number of seconds (in our case it's +5 seconds) relative to the current position
    public void seekForward() {
        seekVideoRelative(5);
    }

    //  Relative seek video: moves the video by a specified number of seconds (in our case it's -5 seconds) relative to the current position
    public void seekBackward() {
        seekVideoRelative(-5);
    }

    // Relative seek video: moves the video by a specified number of seconds relative to the current position
    public void seekVideoRelative(double seconds) {
        if (mediaPlayer != null && mediaPlayer.canSeek()) {
            mediaPlayer.seekRelative(seconds);
        }
    }

    // Absolute (cinema) seek video: moves to a specific second
    public void seekVideoTo(long nanos) {
        if (mediaPlayer != null && mediaPlayer.canSeek()) {
            mediaPlayer.seekTo(nanos, false);
        }
    }

    public void seekToMillis(long ms) {
        if (mediaPlayer != null && mediaPlayer.canSeek()) {
            mediaPlayer.seekTo(ms * 1_000_000L, false);
        }
    }

    public void unregister() {
        mediaPlayerGeneration.incrementAndGet();
        videoStarted = false;
        MediaPlayer currentPlayer = mediaPlayer;
        mediaPlayer = null;
        if (currentPlayer != null) currentPlayer.stop();

        // Schedule texture cleanup on render thread to avoid "Rendersystem called from wrong thread" error
        Minecraft minecraft = getMinecraft();

        if (minecraft.screen instanceof Configuration displayConfScreen) {
            if (displayConfScreen.screen == this) displayConfScreen.onClose();
        }
    }

    private Minecraft getMinecraft() {
        Minecraft minecraft = Minecraft.getInstance();
        if (textureId != null) {
            minecraft.execute(() -> {
                TextureManager manager = minecraft.getTextureManager();
                if (textureId != null) {
                    try {
                        manager.release(textureId);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        return minecraft;
    }

    public UUID getUUID() {
        return uuid;
    }

    public void mute(boolean status) {
        if (muted == status) return;
        muted = status;

        setVideoVolume(!status ? volume : 0);
        Settings.updateSettings(uuid, volume, quality, brightness, muted, paused);
    }

    public double getVolume() {
        return volume;
    }

    // Sets video volume (0.0 to 1.0)
    public void setVolume(float volume) {
        this.volume = volume;
        setVideoVolume(volume);
        Settings.updateSettings(uuid, volume, quality, brightness, muted, paused);
    }

    // Creates a new texture for the screen based on its dimensions and quality
    public void createTexture() {
        int qualityInt = parseQualityOrDefault();
        textureWidth = (int) ((width / (double) height) * qualityInt);
        textureHeight = qualityInt;

        if (texture != null) {
            texture.close();
            if (textureId != null) Minecraft.getInstance()
                    .getTextureManager()
                    .release(textureId);
        }
        texture = new DynamicTexture(
                UUID.randomUUID().toString(),
                textureWidth,
                textureHeight,
                true
        );
        textureId = Identifier.fromNamespaceAndPath(
                Initializer.MOD_ID,
                "screen-main-texture-" + uuid + "-" + UUID.randomUUID()
        );

        Minecraft.getInstance()
                .getTextureManager()
                .register(textureId, texture);
        renderType = createRenderType(textureId);
    }

    public void sendSync() {
        if (mediaPlayer != null) {
            Initializer.sendPacket(
                    new Sync(
                            uuid,
                            isSync,
                            paused,
                            mediaPlayer.getCurrentTime(),
                            mediaPlayer.getDuration()
                    )
            );
        }
    }

    public long getCurrentTimeNanos() {
        if (mediaPlayer != null) {
            return mediaPlayer.getCurrentTime();
        }
        return 0;
    }

    public int getRenderDistance() {
        return renderDistance;
    }

    public void setRenderDistance(int distance) {
        this.renderDistance = distance;
    }

    // Set the saved time to restore when video loads
    public void setSavedTimeNanos(long timeNanos) {
        this.savedTimeNanos = timeNanos;
    }

    // Restore the saved video playback time
    public void restoreSavedTime() {
        if (
                savedTimeNanos > 0 &&
                        mediaPlayer != null &&
                        mediaPlayer.isInitialized() &&
                        mediaPlayer.canSeek()
        ) {
            mediaPlayer.seekTo(savedTimeNanos, false);
        }
    }

    public boolean isLive() {
        return mediaPlayer != null && mediaPlayer.isLive();
    }

    public long getMediaPlayerDurationNanos() {
        return mediaPlayer != null ? mediaPlayer.getDuration() : 0L;
    }

    public boolean canSeek() {
        return mediaPlayer != null && mediaPlayer.canSeek();
    }

    public void waitForMFInit(Runnable action) {
        waitForMFInit(mediaPlayerGeneration.get(), action);
    }

    private void waitForMFInit(long expectedGeneration, Runnable action) {
        Thread initWaitThread = new Thread(() -> {
            int attempts = INIT_WAIT_TIMEOUT_MS / INIT_WAIT_INTERVAL_MS;
            while (attempts-- > 0) {
                if (expectedGeneration != mediaPlayerGeneration.get()) {
                    return;
                }
                MediaPlayer currentPlayer = mediaPlayer;
                if (currentPlayer != null && currentPlayer.isInitialized()) {
                    if (expectedGeneration != mediaPlayerGeneration.get() || currentPlayer != mediaPlayer) {
                        return;
                    }
                    action.run();
                    return;
                }
                if (errored) return;
                try {
                    Thread.sleep(INIT_WAIT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            LoggingManager.warn(
                    "Timed out waiting for media initialization for display " + uuid
            );
        }, "Screen-wait-init-" + uuid);
        initWaitThread.setDaemon(true);
        initWaitThread.start();
    }

    private int parseQualityOrDefault() {
        try {
            int parsed = Integer.parseInt(this.quality.replace("p", ""));
            return parsed > 0 ? parsed : DEFAULT_QUALITY;
        } catch (NumberFormatException e) {
            LoggingManager.warn(
                    "Invalid quality value '" + quality + "' for display " + uuid + ", using " + DEFAULT_QUALITY + "p"
            );
            return DEFAULT_QUALITY;
        }
    }

    public @Nullable String getVideoUrl() {
        return videoUrl;
    }

    public @Nullable String getLang() {
        return lang;
    }

    public @Nullable UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void tick(BlockPos pos) {
        if (mediaPlayer != null) mediaPlayer.tick(
                pos,
                Initializer.config.defaultDistance
        );
    }

    public void afterSeek() {
        if (owner && isSync) sendSync();
    }
}
