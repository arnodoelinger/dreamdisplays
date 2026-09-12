package com.dreamdisplays.api.platform.capability

import com.dreamdisplays.api.Unstable
import java.nio.file.Path

/**
 * Platform-resolved filesystem locations used by API consumers and shared modules.
 *
 * @since 1.8.x
 */
@Unstable
interface PlatformPaths {
    /** User-editable configuration directory. */
    val configDir: Path

    /** Cache directory for disposable derived files. */
    val cacheDir: Path

    /** Persistent data directory owned by the mod / plugin. */
    val dataDir: Path

    /** Directory where installed mods / plugins are located. */
    val modDir: Path
}
