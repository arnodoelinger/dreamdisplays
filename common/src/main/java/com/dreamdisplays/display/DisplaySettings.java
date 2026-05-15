package com.dreamdisplays.display;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import me.inotsleep.utils.logging.LoggingManager;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages loading and saving of display settings and data.
 */
@NullMarked
public class DisplaySettings {
    // Store settings per server
    private static final File SETTINGS_DIR = new File("./config/dreamdisplays");
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    // displayUuid -> DisplaySettings
    private static final Map<UUID, ClientDisplaySettings> displaySettings =
            new HashMap<>();
    // serverId -> (displayUuid -> FullDisplayData)
    private static final Map<
            String,
            Map<UUID, FullDisplayData>
            > serverDisplays = new HashMap<>();
    private static @Nullable String currentServerId = null;

    // Load settings from disk
    public static void load() {
        // Ensure directory exists
        if (!SETTINGS_DIR.exists() && !SETTINGS_DIR.mkdirs()) {
            LoggingManager.error("Failed to create settings directory.");
            return;
        }

        // Load client display settings (volume, quality, muted)
        File clientSettingsFile = new File(
                SETTINGS_DIR,
                "client-display-settings.json"
        );
        try (Reader reader = new FileReader(clientSettingsFile)) {
            Type type = new TypeToken<
                    Map<String, ClientDisplaySettings>
                    >() {
            }.getType();
            Map<String, ClientDisplaySettings> loadedSettings = GSON.fromJson(
                    reader,
                    type
            );

            if (loadedSettings != null) {
                displaySettings.clear();
                for (Map.Entry<
                        String,
                        ClientDisplaySettings
                        > entry : loadedSettings.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        displaySettings.put(uuid, entry.getValue());
                    } catch (IllegalArgumentException e) {
                        LoggingManager.error(
                                "Invalid UUID in client display settings: " +
                                        entry.getKey()
                        );
                    }
                }
            }
        } catch (FileNotFoundException ignored) {
            // File doesn't exist yet, that's fine
        } catch (IOException e) {
            LoggingManager.error("Failed to load client display settings", e);
        }
    }

    // Load server-specific display data
    public static void loadServerDisplays(String serverId) {
        currentServerId = serverId;
        File serverFile = new File(
                SETTINGS_DIR,
                "server-" + serverId + "-displays.json"
        );

        try (Reader reader = new FileReader(serverFile)) {
            Type type = new TypeToken<
                    Map<String, FullDisplayData>
                    >() {
            }.getType();
            Map<String, FullDisplayData> loadedDisplays = GSON.fromJson(
                    reader,
                    type
            );

            Map<UUID, FullDisplayData> displays = new HashMap<>();
            if (loadedDisplays != null) {
                for (Map.Entry<
                        String,
                        FullDisplayData
                        > entry : loadedDisplays.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        displays.put(uuid, entry.getValue());
                    } catch (IllegalArgumentException e) {
                        LoggingManager.error(
                                "Invalid UUID in server displays: " + entry.getKey()
                        );
                    }
                }
            }
            serverDisplays.put(serverId, displays);
            LoggingManager.info(
                    "Loaded " +
                            displays.size() +
                            " displays for server: " +
                            serverId
            );
        } catch (FileNotFoundException ignored) {
            serverDisplays.put(serverId, new HashMap<>());
        } catch (IOException e) {
            LoggingManager.error(
                    "Failed to load server displays for " + serverId,
                    e
            );
            serverDisplays.put(serverId, new HashMap<>());
        }
    }

    // Save all client settings to disk
    public static void save() {
        try {
            if (!SETTINGS_DIR.exists() && !SETTINGS_DIR.mkdirs()) {
                LoggingManager.error("Failed to create settings directory.");
                return;
            }

            Map<String, ClientDisplaySettings> toSave = new HashMap<>();
            for (Map.Entry<
                    UUID,
                    ClientDisplaySettings
                    > entry : displaySettings.entrySet()) {
                toSave.put(entry.getKey().toString(), entry.getValue());
            }

            File clientSettingsFile = new File(
                    SETTINGS_DIR,
                    "client-display-settings.json"
            );
            try (Writer writer = new FileWriter(clientSettingsFile)) {
                GSON.toJson(toSave, writer);
            }
        } catch (IOException e) {
            LoggingManager.error("Failed to save client display settings", e);
        }
    }

    // Save server-specific displays to disk
    public static void saveServerDisplays(String serverId) {
        try {
            if (!SETTINGS_DIR.exists() && !SETTINGS_DIR.mkdirs()) {
                LoggingManager.error("Failed to create settings directory.");
                return;
            }

            Map<UUID, FullDisplayData> displays = serverDisplays.getOrDefault(
                    serverId,
                    new HashMap<>()
            );
            Map<String, FullDisplayData> toSave = new HashMap<>();
            for (Map.Entry<UUID, FullDisplayData> entry : displays.entrySet()) {
                toSave.put(entry.getKey().toString(), entry.getValue());
            }

            File serverFile = new File(
                    SETTINGS_DIR,
                    "server-" + serverId + "-displays.json"
            );
            try (Writer writer = new FileWriter(serverFile)) {
                GSON.toJson(toSave, writer);
            }
        } catch (IOException e) {
            LoggingManager.error(
                    "Failed to save server displays for " + serverId,
                    e
            );
        }
    }

    // Get client display settings
    public static ClientDisplaySettings getSettings(UUID displayUuid) {
        return displaySettings.computeIfAbsent(displayUuid, k ->
                new ClientDisplaySettings()
        );
    }

    // Update client display settings
    public static void updateSettings(
            UUID displayUuid,
            float volume,
            String quality,
            float brightness,
            boolean muted,
            boolean paused
    ) {
        ClientDisplaySettings settings = getSettings(displayUuid);
        settings.volume = volume;
        settings.quality = quality;
        settings.brightness = brightness;
        settings.muted = muted;
        settings.paused = paused;
        save();
    }

    // Update the client-side URL override for a display (set when user picks from suggestions)
    public static void setUrlOverride(UUID displayUuid, @Nullable String url, @Nullable String lang) {
        ClientDisplaySettings settings = getSettings(displayUuid);
        settings.urlOverride = url;
        settings.langOverride = lang;
        save();
    }

    // Get full display data for a server
    public static @Nullable FullDisplayData getDisplayData(UUID displayUuid) {
        if (currentServerId == null) return null;
        return serverDisplays
                .getOrDefault(currentServerId, new HashMap<>())
                .get(displayUuid);
    }

    // Save full display data
    public static void saveDisplayData(UUID displayUuid, FullDisplayData data) {
        if (currentServerId == null) return;

        serverDisplays
                .computeIfAbsent(currentServerId, k -> new HashMap<>())
                .put(displayUuid, data);
        saveServerDisplays(currentServerId);
    }

    // Remove display from all servers and client settings
    public static void removeDisplay(UUID displayUuid) {
        // Remove from server-specific display data
        for (Map<UUID, FullDisplayData> displays : serverDisplays.values()) {
            if (displays.remove(displayUuid) != null) {
                String serverId = null;
                for (Map.Entry<
                        String,
                        Map<UUID, FullDisplayData>
                        > entry : serverDisplays.entrySet()) {
                    if (entry.getValue() == displays) {
                        serverId = entry.getKey();
                        break;
                    }
                }
                if (serverId != null) {
                    saveServerDisplays(serverId);
                }
            }
        }

        // Also remove from client display settings (volume, quality, muted)
        displaySettings.remove(displayUuid);
        save();
    }

    // Client settings for a display (volume, quality, muted, brightness, paused)
    public static class ClientDisplaySettings {

        public float volume = 0.5f;
        public String quality = "720";
        public float brightness = 1.0f;
        public boolean muted = false;
        public boolean paused = true;
        public @Nullable String urlOverride = null;
        public @Nullable String langOverride = null;

        public ClientDisplaySettings() {
        }
    }

    // Full display data (stored per server)
    public static class FullDisplayData {

        public UUID uuid;
        public int x;
        public int y;
        public int z;
        public String facing;
        public int width;
        public int height;
        public String videoUrl;
        public String lang;
        public float volume;
        public String quality;
        public float brightness;
        public boolean muted;
        public boolean isSync;
        public UUID ownerUuid;
        public int renderDistance = 64;
        public long currentTimeNanos = 0;

        public FullDisplayData(
                UUID uuid,
                int x,
                int y,
                int z,
                String facing,
                int width,
                int height,
                String videoUrl,
                String lang,
                float volume,
                String quality,
                float brightness,
                boolean muted,
                boolean isSync,
                UUID ownerUuid
        ) {
            this.uuid = uuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.facing = facing;
            this.width = width;
            this.height = height;
            this.videoUrl = videoUrl;
            this.lang = lang;
            this.volume = volume;
            this.quality = quality;
            this.brightness = brightness;
            this.muted = muted;
            this.isSync = isSync;
            this.ownerUuid = ownerUuid;
        }

        public FullDisplayData(
                UUID uuid,
                int x,
                int y,
                int z,
                String facing,
                int width,
                int height,
                String videoUrl,
                String lang,
                float volume,
                String quality,
                float brightness,
                boolean muted,
                boolean isSync,
                UUID ownerUuid,
                int renderDistance,
                long currentTimeNanos
        ) {
            this(uuid,
                    x,
                    y,
                    z,
                    facing,
                    width,
                    height,
                    videoUrl,
                    lang,
                    volume,
                    quality,
                    brightness,
                    muted,
                    isSync,
                    ownerUuid);
            this.renderDistance = renderDistance;
            this.currentTimeNanos = currentTimeNanos;
        }
    }
}
