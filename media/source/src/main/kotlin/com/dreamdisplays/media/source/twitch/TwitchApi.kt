package com.dreamdisplays.media.source.twitch

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.ytdlp.YtDlp
import com.dreamdisplays.util.*
import com.dreamdisplays.util.json.DreamJson
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

/** A single piece of Twitch metadata resolved for a channel, VOD, or clip. */
data class TwitchMetadata(
    val title: String?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val viewCount: Long?,
    val isLive: Boolean,
    val gameName: String? = null,
    val channelAvatarUrl: String? = null,
)

/** A usher playback access token: [value] is the token blob, [signature] authenticates it. */
data class TwitchAccessToken(val value: String, val signature: String)

/** Live-channel playback lookup: metadata plus the usher access token, from one GQL round trip. */
data class TwitchLivePlayback(val metadata: TwitchMetadata, val token: TwitchAccessToken)

/** VOD playback lookup: metadata, usher access token, and the VOD length. */
data class TwitchVodPlayback(
    val metadata: TwitchMetadata,
    val token: TwitchAccessToken,
    val durationSeconds: Long,
)

/** One downloadable clip rendition, as reported by the clip's `videoQualities` list. */
data class TwitchClipQuality(val quality: String, val frameRate: Double?, val sourceUrl: String)

/** Clip playback lookup: metadata, the token that signs the mp4 URLs, duration, and renditions. */
data class TwitchClipPlayback(
    val metadata: TwitchMetadata,
    val token: TwitchAccessToken,
    val durationSeconds: Long,
    val qualities: List<TwitchClipQuality>,
)

/**
 * Resolves Twitch metadata and playback access tokens without any user-supplied credentials.
 *
 * Primary path is Twitch's public GQL endpoint using Twitch's own web-player client id (the same
 * one `yt-dlp` ships) — this is plain scraping of the public site API, not an API key a user must
 * register for.
 *
 * It is fast (~100ms) and, unlike `yt-dlp`'s info-dict, carries the real live stream
 * title, viewer count, category, and properly sized thumbnails.
 *
 * The same endpoint also issues the playback access tokens the usher playlist service requires, which is
 * what lets [TwitchResolver] skip the `yt-dlp` subprocess entirely.
 *
 * When GQL fails, the [YtDlp] stream list (whose generic info-dict fields work for VODs and clips) is used as the
 * metadata fallback.
 */
object TwitchApi {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/TwitchApi")

    /** GQL URL. */
    private const val GQL_URL = "https://gql.twitch.tv/gql"

    /** Twitch's public web-player client id (also hardcoded in `yt-dlp`); not a user credential. */
    private const val WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

    /** The token params Twitch's own web player sends; usher rejects tokens minted for other player types. */
    private const val TOKEN_PARAMS =
        """params:{platform:"web",playerBackend:"mediaplayer",playerType:"site"}"""

    /** Resolves metadata for [source], preferring the GQL fast path over the yt-dlp fallback. */
    fun resolve(source: MediaSource.Twitch): TwitchMetadata? =
        resolveViaGql(source) ?: resolveViaStreams(source)

    /**
     * Fetches live-channel metadata and the stream playback access token in one GQL round trip.
     * Returns null when the channel is offline or unknown; throws on transport failure.
     */
    fun livePlayback(login: String): TwitchLivePlayback? {
        val data = gql(
            """{user(login:"${escape(login)}"){displayName profileImageURL(width:70) broadcastSettings{title} """ +
                    """stream{viewersCount game{displayName} previewImageURL(width:640,height:360)}} """ +
                    """streamPlaybackAccessToken(channelName:"${escape(login)}",$TOKEN_PARAMS){value signature}}"""
        )
        val user = data.obj("user") ?: return null
        if (user.obj("stream") == null) return null // Channel exists but is offline
        val token = data.obj("streamPlaybackAccessToken")?.toToken() ?: return null
        return TwitchLivePlayback(channelMetadata(user, login), token)
    }

    /**
     * Fetches VOD metadata and the video playback access token in one GQL round trip.
     * Returns null when the VOD is unknown or the token is withheld; throws on transport failure.
     */
    fun vodPlayback(id: String): TwitchVodPlayback? {
        val data = gql(
            """{video(id:"${escape(id)}"){title lengthSeconds viewCount owner{displayName profileImageURL(width:70)} """ +
                    """game{displayName} previewThumbnailURL(width:640,height:360)} """ +
                    """videoPlaybackAccessToken(id:"${escape(id)}",$TOKEN_PARAMS){value signature}}"""
        )
        val video = data.obj("video") ?: return null
        val token = data.obj("videoPlaybackAccessToken")?.toToken() ?: return null
        return TwitchVodPlayback(videoMetadata(video), token, video.optLong("lengthSeconds") ?: 0L)
    }

    /**
     * Fetches clip metadata, its playback access token, and the downloadable renditions in one GQL
     * round trip. Returns null when the clip is unknown or exposes no renditions.
     */
    fun clipPlayback(slug: String): TwitchClipPlayback? {
        val data = gql(
            """{clip(slug:"${escape(slug)}"){title durationSeconds viewCount broadcaster{displayName profileImageURL(width:70)} """ +
                    """game{displayName} thumbnailURL(width:480,height:272) """ +
                    """playbackAccessToken(params:{platform:"web",playerType:"site"}){value signature} """ +
                    """videoQualities{frameRate quality sourceURL}}}"""
        )
        val clip = data.obj("clip") ?: return null
        val token = clip.obj("playbackAccessToken")?.toToken() ?: return null
        val qualities = clip.array("videoQualities")?.mapNotNull { element ->
            val quality = element.asJsonObjectOrNull() ?: return@mapNotNull null
            val sourceUrl = quality.optString("sourceURL") ?: return@mapNotNull null
            TwitchClipQuality(
                quality = quality.optString("quality").orEmpty(),
                frameRate = quality.optDouble("frameRate"),
                sourceUrl = sourceUrl,
            )
        }.orEmpty()
        if (qualities.isEmpty()) return null
        return TwitchClipPlayback(
            metadata = clipMetadata(clip),
            token = token,
            durationSeconds = clip.optLong("durationSeconds") ?: 0L,
            qualities = qualities,
        )
    }

    /** Fetches metadata from the public GQL endpoint; returns null on any failure. */
    private fun resolveViaGql(source: MediaSource.Twitch): TwitchMetadata? =
        runCatching {
            source.channel?.let { return@runCatching queryChannel(it) }
            source.videoId?.let { return@runCatching queryVideo(it) }
            source.clipSlug?.let { return@runCatching queryClip(it) }
            null
        }.onFailure { e ->
            logger.debug("Twitch GQL lookup failed for {}: {}.", source.url, e.message)
        }.getOrNull()

    /** Fetches channel metadata (title, viewers, live status) for [login], or null if the channel doesn't exist. */
    fun queryChannel(login: String): TwitchMetadata? {
        val user = gql(
            """{user(login:"${escape(login)}"){displayName profileImageURL(width:70) broadcastSettings{title} """ +
                    """stream{viewersCount game{displayName} previewImageURL(width:640,height:360)}}}"""
        ).obj("user") ?: return null
        return channelMetadata(user, login)
    }

    private fun queryVideo(id: String): TwitchMetadata? {
        val video = gql(
            """{video(id:"${escape(id)}"){title viewCount owner{displayName profileImageURL(width:70)} game{displayName} """ +
                    """previewThumbnailURL(width:640,height:360)}}"""
        ).obj("video") ?: return null
        return videoMetadata(video)
    }

    private fun queryClip(slug: String): TwitchMetadata? {
        val clip = gql(
            """{clip(slug:"${escape(slug)}"){title viewCount broadcaster{displayName profileImageURL(width:70)} """ +
                    """game{displayName} thumbnailURL(width:480,height:272)}}"""
        ).obj("clip") ?: return null
        return clipMetadata(clip)
    }

    /** Maps a GQL `user` object (with `broadcastSettings` / `stream`) to [TwitchMetadata]. */
    private fun channelMetadata(user: JsonObject, login: String): TwitchMetadata {
        val stream = user.obj("stream")
        return TwitchMetadata(
            title = user.obj("broadcastSettings")?.optString("title"),
            channelName = user.optString("displayName") ?: login,
            thumbnailUrl = stream?.optString("previewImageURL"),
            viewCount = stream?.optLong("viewersCount"),
            isLive = stream != null,
            gameName = stream?.obj("game")?.optString("displayName"),
            channelAvatarUrl = user.optString("profileImageURL"),
        )
    }

    /** Maps a GQL `video` object to [TwitchMetadata]. */
    private fun videoMetadata(video: JsonObject): TwitchMetadata = TwitchMetadata(
        title = video.optString("title"),
        channelName = video.obj("owner")?.optString("displayName"),
        thumbnailUrl = video.optString("previewThumbnailURL"),
        viewCount = video.optLong("viewCount"),
        isLive = false,
        gameName = video.obj("game")?.optString("displayName"),
        channelAvatarUrl = video.obj("owner")?.optString("profileImageURL"),
    )

    /** Maps a GQL `clip` object to [TwitchMetadata]. */
    private fun clipMetadata(clip: JsonObject): TwitchMetadata = TwitchMetadata(
        title = clip.optString("title"),
        channelName = clip.obj("broadcaster")?.optString("displayName"),
        thumbnailUrl = clip.optString("thumbnailURL"),
        viewCount = clip.optLong("viewCount"),
        isLive = false,
        gameName = clip.obj("game")?.optString("displayName"),
        channelAvatarUrl = clip.obj("broadcaster")?.optString("profileImageURL"),
    )

    /** Maps a GQL access-token object to [TwitchAccessToken]; null when either half is missing. */
    private fun JsonObject.toToken(): TwitchAccessToken? {
        val value = optString("value") ?: return null
        val signature = optString("signature") ?: return null
        return TwitchAccessToken(value, signature)
    }

    /** Executes a GQL [query] and returns the `data` object; throws on transport or shape errors. */
    private fun gql(query: String): JsonObject {
        val payload = DreamJson.compact.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("query" to JsonPrimitive(query))),
        )
        val response = DreamHttpClient.readText(
            GQL_URL,
            DreamHttpClient.RequestOptions(
                method = "POST",
                headers = DreamHttpClient.headersOf("Client-ID" to WEB_CLIENT_ID),
                body = payload.toByteArray(StandardCharsets.UTF_8),
                contentType = "application/json",
                readTimeoutMs = 10_000L,
                callTimeoutMs = 12_000L,
            ),
        )
        return DreamJson.compact.parseToJsonElement(response).jsonObject.obj("data")
            ?: throw IllegalStateException("GQL response has no data object.")
    }

    /** Escapes a URL-derived value for safe embedding inside a GQL string literal. */
    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Fallback: derives metadata from the yt-dlp stream list's generic info-dict fields. */
    private fun resolveViaStreams(source: MediaSource.Twitch): TwitchMetadata? =
        runCatching {
            YtDlp.fetch(source.url).firstOrNull()?.let { first ->
                TwitchMetadata(
                    title = first.title,
                    channelName = first.uploaderName,
                    thumbnailUrl = first.thumbnailUrl,
                    viewCount = first.viewCount,
                    isLive = first.isLive,
                )
            }
        }.onFailure { e ->
            logger.debug("Twitch metadata fetch failed for {}: {}.", source.url, e.message)
        }.getOrNull()
}
