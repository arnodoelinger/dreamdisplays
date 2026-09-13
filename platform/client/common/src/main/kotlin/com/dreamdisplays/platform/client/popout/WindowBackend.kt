package com.dreamdisplays.platform.client.popout

import com.dreamdisplays.util.OsInfo

/**
 * Windowing toolkit used to host a detached popout window.
 */
enum class WindowBackend {
    /** A `GLFW` window sharing the game's GL context. Required on macOS before 26.3. */
    GLFW,

    /** A native `AWT` / `Swing` window. Default on Windows and Linux. */
    AWT,

    /** An `SDL` window using the game's already-initialized SDL (26.3+ macOS). */
    SDL;

    companion object {
        /** Picks [GLFW] on macOS and [AWT] everywhere else; 26.3 macOS uses [SDL]. */
        fun detectDefault(): WindowBackend =
            //? if >=26.3 {
            if (OsInfo.isMac) SDL else AWT
            //?} else
            /*if (OsInfo.isMac) GLFW else AWT*/
    }
}
