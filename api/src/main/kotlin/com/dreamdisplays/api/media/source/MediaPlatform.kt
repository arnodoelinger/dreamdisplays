package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.Unstable

/**
 * Which service a [MediaSource] (or a search result) comes from, so the UI can badge it and pick
 * the right metadata path without a chain of `is` checks scattered across the client.
 *
 * @since 1.9.x
 */
@Unstable
enum class MediaPlatform {
    /** A YouTube video. */
    YOUTUBE,

    /** A Twitch channel, VOD, or clip. */
    TWITCH,

    /** A Vimeo video. */
    VIMEO,

    /** A Kick channel or VOD. */
    KICK,

    /** A Bilibili video, live room, or `b23.tv` short link. */
    BILIBILI,

    /**
     * A TikTok video or live room.
     *
     * @since 1.10.3
     */
    TIKTOK,

    /**
     * A Douyin video or `v.douyin.com` short link.
     *
     * @since 1.10.3
     */
    DOUYIN,

    /** A direct media file or streaming manifest the player opens itself. */
    DIRECT,

    /** Any other pasted link, left to the extractor chain (the long tail `yt-dlp` covers). */
    OTHER;
}
