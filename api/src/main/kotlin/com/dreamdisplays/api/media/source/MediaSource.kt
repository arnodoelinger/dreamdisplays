package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.media.search.YouTubeUrls
import com.dreamdisplays.api.security.MediaHttpUrl
import java.util.*

/**
 * User-provided media locator before resolver-specific stream extraction.
 *
 * @since 1.8.0
 */
@DreamDisplaysUnstableApi
sealed interface MediaSource {
    /** Generic remote URL, passed through to the resolver pipeline. */
    data class Remote(val url: String) : MediaSource

    /** YouTube video identified by its 11-character id. */
    data class YouTube(val videoId: String) : MediaSource

    /**
     * Twitch source: a live channel, a VOD (`twitch.tv/videos/<id>`), or a clip
     * (`clips.twitch.tv/<slug>`). Exactly one of [channel] / [videoId] / [clipSlug] is set,
     * matching which of the three URL shapes [url] was.
     */
    data class Twitch(
        val url: String,
        val channel: String? = null,
        val videoId: String? = null,
        val clipSlug: String? = null,
    ) : MediaSource

    /**
     * Vimeo video, identified by its numeric [videoId] and an optional unlisted-video [hash] the
     * player config endpoint needs to authorize playback.
     */
    data class Vimeo(
        val url: String,
        val videoId: String,
        val hash: String? = null,
    ) : MediaSource

    /**
     * Kick source: a live channel (`kick.com/<slug>`) or a VOD (`kick.com/video/<uuid>` or
     * `kick.com/<slug>/videos/<uuid>`). [channel] is set for a live channel, [videoUuid] for a VOD.
     */
    data class Kick(
        val url: String,
        val channel: String? = null,
        val videoUuid: String? = null,
    ) : MediaSource

    /**
     * Direct playable stream URL: a media file or streaming manifest the player opens itself,
     * with no extractor in between. [kind] records what [CustomMediaUrls] recognized it as.
     */
    data class DirectStream(
        val streamUrl: String,
        val kind: CustomMediaKind = CustomMediaKind.PROGRESSIVE,
    ) : MediaSource

    /** Which service this source belongs to, for UI badging and metadata routing. */
    val platform: MediaPlatform
        get() = when (this) {
            is YouTube -> MediaPlatform.YOUTUBE
            is Twitch -> MediaPlatform.TWITCH
            is Vimeo -> MediaPlatform.VIMEO
            is Kick -> MediaPlatform.KICK
            is DirectStream -> MediaPlatform.DIRECT
            is Remote -> MediaPlatform.OTHER
        }

    /** Returns the HTTP(S) URL a resolver can feed to `yt-dlp` / `NewPipeExtractor`. */
    fun toResolvableUrl(): String? = when (this) {
        is YouTube -> YouTubeUrls.watchUrl(videoId)
        is Remote -> url
        is DirectStream -> streamUrl
        is Twitch -> url
        is Vimeo -> url
        is Kick -> url
    }

    companion object {
        /**
         * Parses [url] into a typed source when a known host or a direct media URL is recognized;
         * falls back to [Remote].
         *
         * Platform hosts are matched first, so a YouTube or Twitch link never gets mistaken for a
         * plain file. Whatever is left is run through [CustomMediaUrls] - which also repairs pasted
         * links and rewrites file-host share URLs - and becomes a [DirectStream] when it names a
         * playable file or manifest. [Remote] stays the fallback for everything else, which is what
         * keeps every site the extractor chain supports working exactly as before.
         */
        fun from(url: String): MediaSource {
            YouTubeUrls.extractVideoId(url)?.let { return YouTube(it) }

            val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}")
            val host = parsed?.uri?.host?.lowercase(Locale.ROOT)
            if (parsed != null && (host == "twitch.tv" || host?.endsWith(".twitch.tv") == true)) {
                // Store the scheme-normalized URL, never the raw paste: a scheme-less "twitch.tv/x"
                // would otherwise be rejected by the server's http(s)-only URL policy.
                val twitchUrl = parsed.value
                val segments = parsed.uri.path?.split('/')?.filter { it.isNotBlank() } ?: emptyList()
                when {
                    host == "clips.twitch.tv" && segments.isNotEmpty() ->
                        return Twitch(twitchUrl, clipSlug = segments[0])

                    segments.getOrNull(0) == "videos" && segments.size > 1 ->
                        return Twitch(twitchUrl, videoId = segments[1])

                    segments.isNotEmpty() -> return Twitch(twitchUrl, channel = segments[0])
                }
            }

            VimeoUrls.parse(url)?.let { return it }
            KickUrls.parse(url)?.let { return it }

            val normalized = CustomMediaUrls.normalize(url)
            if (normalized != null) {
                val kind = CustomMediaUrls.classify(normalized)
                if (kind.isDirect) return DirectStream(normalized, kind)
                return Remote(normalized)
            }

            return Remote(url)
        }
    }
}
