package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * What a user-pasted "custom" URL points at, as far as it can be told from the URL alone.
 *
 * Classification is deliberately syntactic (extension / known host shapes): it decides which
 * resolver gets the first attempt, never whether playback will actually succeed. The authoritative
 * answer comes from the resolver's HTTP probe, so an [UNKNOWN] URL still reaches the extractor
 * chain and a mislabelled [PROGRESSIVE] one still falls through when the probe disagrees.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
enum class CustomMediaKind {
    /** A plain media file (`.mp4`, `.webm`, `.mkv`, ...) the player can open and byte-range seek. */
    PROGRESSIVE,

    /** An HLS playlist (`.m3u8`) — either a master with several renditions or a single media playlist. */
    HLS,

    /** An MPEG-DASH manifest (`.mpd`). */
    DASH,

    /** An audio container (`.mp3`, `.flac`, ...): recognizable media, but a display needs a picture. */
    AUDIO_ONLY,

    /** Not recognizably direct media; the extractor chain (`NewPipe` / `yt-dlp`) decides. */
    UNKNOWN;

    /** True when the URL can be handed straight to the player without an extractor. */
    val isDirect: Boolean get() = this == PROGRESSIVE || this == HLS || this == DASH

    /** True when the URL is a manifest whose variants are fetched rather than the media itself. */
    val isManifest: Boolean get() = this == HLS || this == DASH
}
