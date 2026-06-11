package com.dreamdisplays.client.popout

/**
 * Windowing toolkit used to host a detached popout window. Chosen per-OS because macOS cannot run
 * an `AWT` window alongside the `GLFW` game window, while Windows / Linux prefer `AWT` for native chrome.
 *
 * @since 1.8.0
 */
enum class WindowBackend {
    /** A `GLFW` window sharing the game's GL context. Required on macOS. */
    GLFW,

    /** A native `AWT` / `Swing` window. Default on Windows and Linux. */
    AWT;

    companion object {
        /** Picks [GLFW] on macOS and [AWT] everywhere else. */
        fun detectDefault(): WindowBackend {
            val os = System.getProperty("os.name", "").lowercase()
            return if ("mac" in os) GLFW else AWT
        }
    }
}
