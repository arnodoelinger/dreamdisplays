package com.dreamdisplays.media.source.platform

import com.dreamdisplays.media.source.bilibili.BilibiliPlatform
import com.dreamdisplays.media.source.kick.KickPlatform
import com.dreamdisplays.media.source.twitch.TwitchPlatform
import com.dreamdisplays.media.source.vimeo.VimeoPlatform

/** Every built-in [Platform]; a new platform is added here and the resolver chain and stall recovery pick it up. */
object Platforms {
    val all: List<Platform<*, *>> = [TwitchPlatform, VimeoPlatform, KickPlatform, BilibiliPlatform]

    /** Drops [url]'s cached resolution on whichever platform owns it. */
    fun invalidate(url: String) = all.forEach { it.invalidate(url) }
}
