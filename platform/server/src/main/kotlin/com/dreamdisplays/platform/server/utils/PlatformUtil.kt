package com.dreamdisplays.platform.server.utils

/**
 * Platform-specific checks. Right now it includes `Folia` detection.
 */
object PlatformUtil {
    val isFolia: Boolean by lazy {
        runCatching {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
        }.isSuccess
    }
}
