package com.dreamdisplays.media.source.bilibili

import com.dreamdisplays.api.media.source.url.BilibiliUrls
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.media.stream.model.MediaStream
import com.dreamdisplays.api.media.stream.model.MediaStreamType
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import com.dreamdisplays.util.*
import com.dreamdisplays.util.json.DreamJson
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A resolved BIlibili VOD or live room: playable [streams] and [metadata]. */
data class BilibiliPlayback(
    val streams: List<MediaStream>,
    val metadata: PlatformVideoMetadata,
    val isSeekable: Boolean,
)

/** One video card from a BIlibili keyword search. */
data class BilibiliSearchItem(
    val bvid: String,
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val durationSec: Long?,
    val viewCount: Long?,
)

/**
 * Resolves BIlibili VODs and live rooms through BIlibili's public web API — the same JSON the
 * website uses, gated behind WBI-signed `playurl` requests (no API key or auth required).
 */
object BilibiliApi {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** BIlibili's API rejects requests without a browser-shaped `Referer` / `User-Agent`. */
    private val HEADERS = DreamHttpClient.headersOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept" to "application/json",
        "Referer" to "https://www.bilibili.com",
    )

    /** `b23.tv` short links get followed for at most this many hops before giving up. */
    private const val MAX_REDIRECT_HOPS = 5

    /** Fixed permutation table BIlibili uses to derive the 32-char WBI mixin key. Publicly documented, stable! */
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12,
        38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62,
        11, 36, 20, 34, 44, 52,
    )

    /** The `img_key` / `sub_key` WBI signing keys, cached since they rotate roughly once a day. */
    private data class WbiKeys(val imgKey: String, val subKey: String, val fetchedAt: Instant)

    private var wbiCache: WbiKeys? = null
    private val WBI_TTL = 20.hours

    fun resolve(source: MediaSource.Bilibili): BilibiliPlayback? = when {
        source.bvid != null || source.avid != null -> resolveVod(source.bvid, source.avid, source.part ?: 1)
        source.epId != null || source.seasonId != null -> resolveBangumi(source.epId, source.seasonId)
        source.roomId != null -> resolveLive(source.roomId!!)
        else -> resolveShortlink(source.url)?.let { resolve(it) }
    }

    /** Metadata-only lookup for the cache. */
    fun metadata(source: MediaSource.Bilibili): PlatformVideoMetadata? = resolve(source)?.metadata

    /** Keyword video search, WBI-signed like `playurl` — used to mix BIlibili results into the free-text suggestions list. */
    fun searchVideos(keyword: String, page: Int = 1): List<BilibiliSearchItem> {
        val params = mapOf("search_type" to "video", "keyword" to keyword, "page" to page.toString())
        val root = getJson("https://api.bilibili.com/x/web-interface/wbi/search/type?${signedQuery(params)}")
        val results = root?.obj("data")?.array("result")?.mapNotNull { it.asJsonObjectOrNull() } ?: return emptyList()
        return results.mapNotNull { item ->
            val bvid = item.optString("bvid") ?: return@mapNotNull null
            BilibiliSearchItem(
                bvid = bvid,
                title = stripHighlightTags(item.optString("title").orEmpty()),
                uploader = item.optString("author"),
                thumbnailUrl = normalizeThumbnailUrl(item.optString("pic")),
                durationSec = parseSearchDuration(item.optString("duration")),
                viewCount = item.optLong("play"),
            )
        }
    }

    /** Strips the `<em class="keyword">...</em>` highlight markup BIlibili's search API wraps matches in. */
    private fun stripHighlightTags(title: String): String = title.replace(Regex("</?em[^>]*>"), "")

    /** BIlibili's search API returns protocol-relative thumbnail URLs (`//i0.hdslb.com/...`). */
    private fun normalizeThumbnailUrl(pic: String?): String? = when {
        pic.isNullOrEmpty() -> null
        pic.startsWith("//") -> "https:$pic"
        else -> pic
    }

    /** Parses a search result's `"mm:ss"` / `"hh:mm:ss"` duration string into seconds. */
    private fun parseSearchDuration(text: String?): Long? {
        val parts = text?.split(":")?.map { it.toLongOrNull() ?: return null } ?: return null
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    /** Follows a `b23.tv` short link's redirect chain and reparses the final URL into a BIlibili source. */
    private fun resolveShortlink(url: String): MediaSource.Bilibili? {
        var current = url
        repeat(MAX_REDIRECT_HOPS) {
            val location = runCatching {
                DreamHttpClient.peekRedirectLocation(
                    current,
                    DreamHttpClient.RequestOptions(headers = HEADERS, connectTimeoutMs = 8_000, readTimeoutMs = 8_000),
                )
            }.getOrNull() ?: return BilibiliUrls.parse(current)?.takeIf { it.isResolved }

            val resolved = BilibiliUrls.parse(location)
            if (resolved != null && resolved.isResolved) return resolved
            current = location
        }
        return BilibiliUrls.parse(current)?.takeIf { it.isResolved }
    }

    /** True once a BIlibili source carries an actual id, i.e. is no longer a bare `b23.tv` short link. */
    private val MediaSource.Bilibili.isResolved: Boolean
        get() = bvid != null || avid != null || roomId != null || epId != null || seasonId != null

    /** Resolves a VOD identified by [bvid] or legacy [avid], at part [part] (1-based). */
    private fun resolveVod(bvid: String?, avid: Long?, part: Int): BilibiliPlayback? {
        val viewParams = buildMap {
            bvid?.let { put("bvid", it) }
            avid?.let { put("aid", it.toString()) }
        }
        if (viewParams.isEmpty()) return null

        val viewRoot = getJson("https://api.bilibili.com/x/web-interface/view?${plainQuery(viewParams)}")
        val data = viewRoot?.obj("data") ?: return null

        val pages = data.array("pages")?.mapNotNull { it.asJsonObjectOrNull() }
        val page = pages?.firstOrNull { it.optInt("page") == part } ?: pages?.firstOrNull()
        val cid = page?.optLong("cid") ?: data.optLong("cid") ?: return null
        val resolvedBvid = data.optString("bvid") ?: bvid

        val playurlParams = buildMap {
            resolvedBvid?.let { put("bvid", it) }
            put("cid", cid.toString())
            put("qn", "80")
            put("fnval", "4048")
            put("fourk", "1")
        }
        val playurlRoot = getJson("https://api.bilibili.com/x/player/playurl?${signedQuery(playurlParams)}")
        val streams = buildStreams(playurlRoot?.obj("data"))
        if (streams.isEmpty()) return null

        val stat = data.obj("stat")
        val owner = data.obj("owner")
        val metadata = PlatformVideoMetadata(
            title = data.optString("title"),
            uploader = owner?.optString("name"),
            thumbnailUrl = data.optString("pic"),
            uploaderAvatarUrl = owner?.optString("face"),
            viewCount = stat?.optLong("view"),
            durationSec = data.optLong("duration"),
            isLive = false,
        )
        return BilibiliPlayback(streams = streams, metadata = metadata, isSeekable = true)
    }

    private fun resolveBangumi(epId: Long?, seasonId: Long?): BilibiliPlayback? {
        val seasonParams = buildMap {
            epId?.let { put("ep_id", it.toString()) }
            seasonId?.let { put("season_id", it.toString()) }
        }
        if (seasonParams.isEmpty()) return null

        val seasonRoot = getJson("https://api.bilibili.com/pgc/view/web/season?${plainQuery(seasonParams)}")
        val result = seasonRoot?.obj("result") ?: return null
        val episodes = result.array("episodes")?.mapNotNull { it.asJsonObjectOrNull() } ?: return null
        val episode = epId?.let { id -> episodes.firstOrNull { it.optLong("ep_id") == id } }
            ?: episodes.firstOrNull()
            ?: return null
        val resolvedEpId = episode.optLong("ep_id") ?: return null

        val playurlRoot =
            getJson("https://api.bilibili.com/pgc/player/web/v2/playurl?fnval=12240&fourk=1&ep_id=$resolvedEpId")
        val streams = buildStreams(playurlRoot?.obj("result")?.obj("video_info"))
        if (streams.isEmpty()) return null

        val seasonTitle = result.optString("title")
        val episodeTitle = episode.optString("long_title")?.takeIf { it.isNotBlank() }
            ?: episode.optString("show_title")
        val title = listOfNotNull(seasonTitle, episodeTitle).joinToString(" · ").ifBlank { null }

        val metadata = PlatformVideoMetadata(
            title = title,
            uploader = null,
            thumbnailUrl = episode.optString("cover") ?: result.optString("cover"),
            uploaderAvatarUrl = null,
            viewCount = null,
            durationSec = episode.optLong("duration")?.let { it / 1000 },
            isLive = false,
        )
        return BilibiliPlayback(streams = streams, metadata = metadata, isSeekable = true)
    }

    /** Resolves a live room identified by [roomId]. */
    private fun resolveLive(roomId: Long): BilibiliPlayback {
        val infoRoot = getJson("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=$roomId")
        val info = infoRoot?.obj("data")
        val isLive = info?.optInt("live_status") == 1

        val metadata = PlatformVideoMetadata(
            title = info?.optString("title"),
            uploader = null,
            thumbnailUrl = info?.optString("user_cover") ?: info?.optString("keyframe"),
            uploaderAvatarUrl = null,
            viewCount = info?.optLong("online"),
            durationSec = null,
            isLive = isLive,
        )

        if (!isLive) return BilibiliPlayback(streams = emptyList(), metadata = metadata, isSeekable = false)

        val playParams = mapOf(
            "room_id" to roomId.toString(),
            "protocol" to "0,1",
            "format" to "0,1,2",
            "codec" to "0,1",
            "qn" to "10000",
        )
        val playRoot = getJson(
            "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?${signedQuery(playParams)}",
        )
        val streams = buildLiveStreams(playRoot?.obj("data"))
        return BilibiliPlayback(streams = streams, metadata = metadata, isSeekable = false)
    }

    /** Builds video-only / audio-only [MediaStream]s from a VOD `playurl` DASH response, or muxed ones from `durl`. */
    private fun buildStreams(playurlData: JsonObject?): List<MediaStream> {
        if (playurlData == null) return emptyList()

        val dash = playurlData.obj("dash")
        if (dash != null) {
            val streams = ArrayList<MediaStream>()
            dash.array("video")?.mapNotNull { it.asJsonObjectOrNull() }?.forEach { v ->
                val url = dashUrl(v) ?: return@forEach
                streams += MediaStream(
                    url = url,
                    type = MediaStreamType.VIDEO,
                    codec = null,
                    width = v.optInt("width"),
                    height = v.optInt("height"),
                    fps = v.optString("frameRate")?.toDoubleOrNull(),
                    bitrate = v.optInt("bandwidth"),
                    audioTrackName = null,
                    audioTrackLang = null,
                )
            }
            dash.array("audio")?.mapNotNull { it.asJsonObjectOrNull() }?.forEach { a ->
                val url = dashUrl(a) ?: return@forEach
                streams += MediaStream(
                    url = url,
                    type = MediaStreamType.AUDIO,
                    codec = null,
                    width = null,
                    height = null,
                    fps = null,
                    bitrate = a.optInt("bandwidth"),
                    audioTrackName = null,
                    audioTrackLang = null,
                )
            }
            if (streams.isNotEmpty()) return streams
        }

        // Very low quality / old videos have no DASH manifest, only a single progressive URL.
        // Regular VODs key this "durl"; bangumi's `video_info` keys the same shape "durls".
        val durl = (playurlData.array("durl") ?: playurlData.array("durls"))
            ?.firstOrNull()?.asJsonObjectOrNull() ?: return emptyList()
        val durlUrl = durl.optString("url") ?: return emptyList()
        return listOf(
            MediaStream(
                url = durlUrl, type = MediaStreamType.VIDEO_AUDIO,
                codec = null, width = null, height = null, fps = null, bitrate = durl.optInt("bandwidth"),
                audioTrackName = null, audioTrackLang = null,
            ),
        )
    }

    /** Builds muxed live streams (FLV / HLS) from a `getRoomPlayInfo` response. */
    private fun buildLiveStreams(playInfoData: JsonObject?): List<MediaStream> {
        val streams = ArrayList<MediaStream>()
        val codecs = playInfoData?.obj("playurl_info")?.obj("playurl")?.array("stream")
            ?.mapNotNull { it.asJsonObjectOrNull() } ?: return streams
        for (stream in codecs) {
            val formats = stream.array("format")?.mapNotNull { it.asJsonObjectOrNull() } ?: continue
            for (format in formats) {
                val codecList = format.array("codec")?.mapNotNull { it.asJsonObjectOrNull() } ?: continue
                for (codec in codecList) {
                    val baseUrl = codec.optString("base_url") ?: continue
                    val urlInfo = codec.array("url_info")?.firstOrNull()?.asJsonObjectOrNull() ?: continue
                    val host = urlInfo.optString("host") ?: continue
                    val extra = urlInfo.optString("extra").orEmpty()
                    streams += MediaStream(
                        url = "$host$baseUrl$extra", type = MediaStreamType.VIDEO_AUDIO,
                        codec = codec.optString("codec_name"), width = null, height = null, fps = null,
                        bitrate = codec.optInt("current_qn"), audioTrackName = null, audioTrackLang = null,
                    )
                }
            }
        }
        return streams
    }

    /** Reads a DASH representation's playable URL, preferring `baseUrl` over its backup list. */
    private fun dashUrl(rep: JsonObject): String? =
        rep.optString("baseUrl") ?: rep.optString("base_url")
            ?: rep.array("backupUrl")?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
            ?: rep.array("backup_url")?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }

    /** Fetches (and caches) the WBI `img_key` / `sub_key` pair used to sign gated endpoints. */
    private fun wbiKeys(): WbiKeys? {
        wbiCache?.let { if (Clock.System.now() - it.fetchedAt < WBI_TTL) return it }

        val wbiImg = getJson("https://api.bilibili.com/x/web-interface/nav")?.obj("data")?.obj("wbi_img")
        val imgUrl = wbiImg?.optString("img_url")
        val subUrl = wbiImg?.optString("sub_url") ?: wbiImg?.optString("sub_key_url")
        if (imgUrl == null || subUrl == null) return wbiCache

        return WbiKeys(keySegment(imgUrl), keySegment(subUrl), Clock.System.now()).also { wbiCache = it }
    }

    /** Extracts the filename segment (stripped of extension) that WBI uses as a key from a signed asset URL. */
    private fun keySegment(url: String): String = url.substringAfterLast('/').substringBeforeLast('.')

    /** Builds the 32-char WBI mixin key by permuting `imgKey + subKey` through [MIXIN_KEY_ENC_TAB]. */
    private fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        val sb = StringBuilder(32)
        for (i in 0 until 32) {
            val idx = MIXIN_KEY_ENC_TAB[i]
            if (idx < raw.length) sb.append(raw[idx])
        }
        return sb.toString()
    }

    /** Builds a plain (unsigned) `key=value&...` query string, URL-encoding each value. */
    private fun plainQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }

    /**
     * Builds a WBI-signed query string for [params]: adds `wts` (unix seconds), sorts keys, strips
     * `!'()*` from values, appends the mixin key, and MD5-hashes the result into `w_rid`. Falls back
     * to a plain (unsigned) query when the WBI keys are unavailable — some endpoints still answer,
     * just at reduced quality.
     */
    private fun signedQuery(params: Map<String, String>): String {
        val wts = Clock.System.now().epochSeconds.toString()
        val all = (params + ("wts" to wts)).toSortedMap()
        val keys = wbiKeys() ?: return plainQuery(all)

        val filtered = all.mapValues { (_, v) -> v.filterNot { c -> c in "!'()*" } }
        val base = filtered.entries.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        val wRid = md5(base + mixinKey(keys.imgKey, keys.subKey))
        return "$base&w_rid=$wRid"
    }

    /** URL-encodes [value] for a query string, using `%20` (not `+`) for spaces. */
    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Returns the lowercase hex MD5 digest of [s]. */
    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** GETs [url] and parses it as a JSON object, or null on any failure (offline, blocked, 404). */
    private fun getJson(url: String): JsonObject? = runCatching {
        val body = DreamHttpClient.readText(
            url,
            DreamHttpClient.RequestOptions(headers = HEADERS, connectTimeoutMs = 8_000, readTimeoutMs = 8_000),
        )
        DreamJson.compact.parseToJsonElement(body).asJsonObjectOrNull()
    }.onFailure { logger.debug("Bilibili API fetch failed for {}: {}.", url, it.message) }.getOrNull()
}
