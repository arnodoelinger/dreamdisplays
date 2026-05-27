package com.dreamdisplays.server.utils

import io.github.arsmotorin.ofrat.PaperOnly

/**
 * Platform-specific checks. Right now it includes `Folia` detection.
 */
@PaperOnly object PlatformUtil {
    val isFolia: Boolean by lazy {
        runCatching {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
        }.isSuccess
    }
}
