package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Which service a [MediaSource] (or a search result) comes from, so the UI can badge it and pick
 * the right metadata path without a chain of `is` checks scattered across the client.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
enum class MediaPlatform {
    /** A YouTube video. */
    YOUTUBE,

    /** A Twitch channel, VOD, or clip. */
    TWITCH,

    /** A Vimeo video. */
    VIMEO,

    /** A Kick channel or VOD. */
    KICK,

    /** A direct media file or streaming manifest the player opens itself. */
    DIRECT,

    /** Any other pasted link, left to the extractor chain (the long tail `yt-dlp` covers). */
    OTHER;

    /** True for the two platforms whose links are always live-or-VOD pages, never plain files. */
    val isFirstPartyPlatform: Boolean get() = this == YOUTUBE || this == TWITCH || this == VIMEO || this == KICK
}
