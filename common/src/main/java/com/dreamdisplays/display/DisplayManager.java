package com.dreamdisplays.display;

import org.jspecify.annotations.NullMarked;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for all screen displays.
 */
@NullMarked
public class DisplayManager {

    public static final ConcurrentHashMap<UUID, DisplayScreen> screens =
            new ConcurrentHashMap<>();

    // Cache of unloaded displays
    public static final ConcurrentHashMap<UUID, DisplaySettings.FullDisplayData> unloadedScreens =
            new ConcurrentHashMap<>();

    public DisplayManager() {
    }

    public static Collection<DisplayScreen> getScreens() {
        return screens.values();
    }

    public static void registerScreen(DisplayScreen displayScreen) {
        if (screens.containsKey(displayScreen.getUUID())) {
            DisplayScreen old = screens.get(displayScreen.getUUID());
            old.unregister();
        }

        DisplaySettings.ClientDisplaySettings clientSettings = DisplaySettings.getSettings(
                displayScreen.getUUID()
        );
        displayScreen.setVolume(clientSettings.volume);
        displayScreen.setQuality(clientSettings.quality);
        displayScreen.muted = clientSettings.muted;

        DisplaySettings.FullDisplayData savedData = DisplaySettings.getDisplayData(
                displayScreen.getUUID()
        );
        if (savedData != null) {
            displayScreen.setRenderDistance(savedData.renderDistance);
            displayScreen.setSavedTimeNanos(savedData.currentTimeNanos);
        }

        screens.put(displayScreen.getUUID(), displayScreen);

        // Save the display data for persistence
        saveScreenData(displayScreen);
    }

    public static void unregisterScreen(DisplayScreen displayScreen) {
        // Cache the display data before unregistering
        String videoUrl = displayScreen.getVideoUrl();
        String lang = displayScreen.getLang();
        UUID ownerUuid = displayScreen.getOwnerUuid();

        DisplaySettings.FullDisplayData data = new DisplaySettings.FullDisplayData(
                displayScreen.getUUID(),
                displayScreen.getPos().getX(),
                displayScreen.getPos().getY(),
                displayScreen.getPos().getZ(),
                displayScreen.getFacing(),
                (int) displayScreen.getWidth(),
                (int) displayScreen.getHeight(),
                videoUrl != null ? videoUrl : "",
                lang != null ? lang : "",
                (float) displayScreen.getVolume(),
                displayScreen.getQuality(),
                displayScreen.getBrightness(),
                displayScreen.muted,
                displayScreen.isSync,
                ownerUuid != null ? ownerUuid : displayScreen.getUUID(),
                displayScreen.getRenderDistance(),
                displayScreen.getCurrentTimeNanos()
        );
        unloadedScreens.put(displayScreen.getUUID(), data);

        screens.remove(displayScreen.getUUID());
        displayScreen.unregister();
    }

    public static void unloadAll() {
        for (DisplayScreen displayScreen : screens.values()) {
            displayScreen.unregister();
        }

        screens.clear();
        unloadedScreens.clear(); // Clear cache when changing servers
    }

    // Save screen data to persistent storage
    public static void saveScreenData(DisplayScreen displayScreen) {
        DisplaySettings.FullDisplayData data = new DisplaySettings.FullDisplayData(
                displayScreen.getUUID(),
                displayScreen.getPos().getX(),
                displayScreen.getPos().getY(),
                displayScreen.getPos().getZ(),
                displayScreen.getFacing(),
                (int) displayScreen.getWidth(),
                (int) displayScreen.getHeight(),
                displayScreen.getVideoUrl(),
                displayScreen.getLang(),
                (float) displayScreen.getVolume(),
                displayScreen.getQuality(),
                displayScreen.getBrightness(),
                displayScreen.muted,
                displayScreen.isSync,
                displayScreen.getOwnerUuid(),
                displayScreen.getRenderDistance(),
                displayScreen.getCurrentTimeNanos()
        );

        DisplaySettings.saveDisplayData(displayScreen.getUUID(), data);
    }

    // Load displays from persistent storage for a server
    // Actual display data comes from the server via Info packets.
    // Local cache is used only for client preferences (volume, quality, muted).
    public static void loadScreensForServer(String serverId) {
        DisplaySettings.loadServerDisplays(serverId);
        // Displays will be received from server via Info packets
    }

    // Save all screens to persistent storage for current server
    public static void saveAllScreens() {
        for (DisplayScreen displayScreen : screens.values()) {
            saveScreenData(displayScreen);
        }
    }
}
