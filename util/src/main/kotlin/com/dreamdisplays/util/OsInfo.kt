package com.dreamdisplays.util

import java.util.*

/**
 * Single source of truth for OS / architecture detection.
 */
object OsInfo {
    private val os: String = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
    private val arch: String = System.getProperty("os.arch", "").lowercase(Locale.ENGLISH)

    val isWindows: Boolean = "win" in os
    val isMac: Boolean = "mac" in os

    val isLinux: Boolean = "nux" in os || "nix" in os
    val isArm: Boolean = "aarch64" in arch || "arm64" in arch || "arm" in arch
    val isArm64: Boolean = "aarch64" in arch || "arm64" in arch
}
