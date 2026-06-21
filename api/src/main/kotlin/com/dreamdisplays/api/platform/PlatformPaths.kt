package com.dreamdisplays.api.platform

import java.nio.file.Path

interface PlatformPaths {
    val configDir: Path
    val cacheDir: Path
    val dataDir: Path
    val modDir: Path
}
