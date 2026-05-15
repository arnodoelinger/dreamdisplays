package com.dreamdisplays;

import com.dreamdisplays.display.DisplayManager;
import com.dreamdisplays.display.DisplayScreen;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NullMarked;

/**
 * A background thread that mutes/unmutes screens based on window focus.
 */
@NullMarked
public class Focuser extends Thread {

    public static Focuser instance = new Focuser();

    public Focuser() {
        setDaemon(true);
        instance = this;
        setName("window-focus-mute-thread");
    }

    @Override
    public void run() {
        while (true) {
            Minecraft client = Minecraft.getInstance();

            boolean focused = client.isWindowActive();

            if (Initializer.getConfig().muteOnAltTab
            ) for (DisplayScreen displayScreen : DisplayManager.getScreens()) {
                displayScreen.mute(!focused);
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
