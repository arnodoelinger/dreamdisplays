package com.dreamdisplays.api.media.source.model

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.source.url.YouTubeUrls
import com.dreamdisplays.api.media.source.url.BilibiliUrls
import com.dreamdisplays.api.media.source.url.CustomMediaUrls
import com.dreamdisplays.api.media.source.url.KickUrls
import com.dreamdisplays.api.media.source.url.VimeoUrls
import com.dreamdisplays.api.security.model.MediaHttpUrl
import java.util.*

/**
 * User-provided media locator before resolver-specific stream extraction.
 *
 * @since 1.8.x
 */
@Unstable
sealed interface MediaSource {
    /** Generic remote URL, passed through to the resolver pipeline. */
    data class Remote(val url: String) : MediaSource

    /** YouTube video identified by its 11-character id. */
    data class YouTube(val videoId: String) : MediaSource

    /** Twitch source: live channel, VOD, or clip. Exactly one of [channel] / [videoId] / [clipSlug] is set. */
    data class Twitch(
        val url: String,
        val channel: String? = null,
        val videoId: String? = null,
        val clipSlug: String? = null,
    ) : MediaSource

    /** Vimeo video identified by [videoId] and optional unlisted-video [hash] for authorization. */
    data class Vimeo(
        val url: String,
        val videoId: String,
        val hash: String? = null,
    ) : MediaSource

    /** Kick source: live channel or VOD. [channel] for live, [videoUuid] for VOD. */
    data class Kick(
        val url: String,
        val channel: String? = null,
        val videoUuid: String? = null,
    ) : MediaSource

    data class Bilibili(
        val url: String,
        val bvid: String? = null,
        val avid: Long? = null,
        val part: Int? = null,
        val roomId: Long? = null,
        val epId: Long? = null,
        val seasonId: Long? = null,
    ) : MediaSource

    /** Direct playable stream URL or manifest; [kind] records what [com.dreamdisplays.api.media.source.url.CustomMediaUrls] recognized. */
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
            is Bilibili -> MediaPlatform.BILIBILI
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
        is Bilibili -> url
    }

    companion object {
        /**
         * Parses [url] into a typed source: platform hosts first, then [com.dreamdisplays.api.media.source.url.CustomMediaUrls] for direct streams, else [Remote].
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
            BilibiliUrls.parse(url)?.let { return it }

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
