package com.dreamdisplays.platform.client.popout

import com.dreamdisplays.util.OsInfo

/**
 * Windowing toolkit used to host a detached popout window.
 */
enum class WindowBackend {
    /** A `GLFW` window sharing the game's GL context. Required on macOS. */
    GLFW,

    /** A native `AWT` / `Swing` window. Default on Windows and Linux. */
    AWT;

    companion object {
        /** Picks [GLFW] on macOS and [AWT] everywhere else. */
        fun detectDefault(): WindowBackend =
            //? if >=26.3 {
            AWT
            //?} else
            /*if (OsInfo.isMac) GLFW else AWT*/
    }
}
