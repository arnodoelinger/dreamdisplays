package com.dreamdisplays.platform.server.utils

import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
import org.semver4j.Semver

/**
 * Runtime Minecraft-version detection for the single, cross-version Paper jar.
 */
@PaperOnly
object ServerVersion {
    /** The running server's Minecraft version, e.g. `1.21.1` / `26.2`; null if it can't be read. */
    val current: Semver? by lazy {
        runCatching { Semver.coerce(Bukkit.getMinecraftVersion()) }.getOrNull()
    }

    /**
     * True on Minecraft 1.21.11+ (and all 26.x), where Adventure exposes the object / sprite hover
     * API. Defaults to `false` when the version can't be determined, so the fallback path uses only
     * the legacy API that exists on every supported version — never a class absent on older servers.
     */
    val isAtLeast_1_21_11: Boolean by lazy {
        current?.isGreaterThanOrEqualTo("1.21.11") ?: false
    }

    /** True on Minecraft 26.x and newer. */
    val isAtLeast_26: Boolean by lazy {
        (current?.major ?: 0) >= 26
    }
}
