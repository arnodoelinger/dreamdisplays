package com.dreamdisplays

import me.inotsleep.utils.config.AbstractConfig
import me.inotsleep.utils.config.Path
import java.io.File

/** Client configuration settings. */
class Config(baseDir: File) : AbstractConfig(baseDir, "config.yml") {

    @Path("mute-on-alt-tab")
    var muteOnAltTab: Boolean = false

    @Path("default-render-distance")
    var defaultDistance: Int = 64

    @Path("default-sync-display-volume")
    var syncDisplayVolume: Double = 0.25

    @Path("default-default-display-volume")
    var defaultDisplayVolume: Double = 0.5

    @Path("displays-enabled")
    var displaysEnabled: Boolean = true

    @Path("ytdlp-cookies-from-browser")
    var ytdlpCookiesFromBrowser: String = "auto"

    @Path("ytdlp-proxy")
    var ytdlpProxy: String = ""

    companion object {
        init {
            System.setProperty("file.encoding", "UTF-8")
        }
    }
}
