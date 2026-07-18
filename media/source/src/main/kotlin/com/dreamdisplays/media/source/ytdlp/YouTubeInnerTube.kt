package com.dreamdisplays.media.source.ytdlp

import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.media.source.ytdlp.YouTubeInnerTube.runsText
import com.dreamdisplays.util.*
import com.dreamdisplays.util.json.DreamJson
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Direct client for YouTube's `InnerTube` API.
 */
object YouTubeInnerTube {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/InnerTube")

    /** Base URL for the `InnerTube` API. */
    private const val BASE_URL = "https://www.youtube.com/youtubei/v1"

    /** Client metadata. */
    private const val CLIENT_NAME = "WEB"

    /** Client version. */
    private const val CLIENT_VERSION = "2.20250501.00.00"

    /** User-Agent header. */
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    /** Pattern for parsing YouTube-style "age" strings (e.g. "2 years ago"). */
    private val AGE_PATTERN: Pattern = Pattern.compile(
        "(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago",
        Pattern.CASE_INSENSITIVE
    )

    /** `InnerTube` API request structure. */
    @Serializable
    private data class InnerTubeRequest(
        val context: InnerTubeContext = InnerTubeContext(),
        val query: String? = null,
        val videoId: String? = null,
    )

    /** `InnerTube` API response structure. */
    @Serializable
    private data class InnerTubeContext(
        val client: InnerTubeClient = InnerTubeClient(),
    )

    /** `InnerTube` API client structure. */
    @Serializable
    private data class InnerTubeClient(
        val clientName: String = CLIENT_NAME,
        val clientVersion: String = CLIENT_VERSION,
        val hl: String = "en",
    )

    /** Searches YouTube for [query] and returns up to [limit] video results via the InnerTube search endpoint. */
    @Throws(IOException::class)
    fun search(query: String, limit: Int): List<MediaSearchResult> {
        val root = post("search", InnerTubeRequest(query = query))
        return extractSearchVideos(root, limit)
    }

    /** Fetches watch-next metadata and related videos for [videoId] via the `InnerTube` `next` endpoint. */
    @Throws(IOException::class)
    fun next(videoId: String): NextResult {
        val root = post("next", InnerTubeRequest(videoId = videoId))
        val meta = extractWatchMetadata(root)
        val related = extractRelatedVideos(root, videoId, 25)
        return NextResult(meta?.title, meta?.uploader, meta?.viewCountRaw, meta?.likeCountRaw, related)
    }

    /** Returns up to [limit] related videos for [videoId], excluding the video itself. */
    @Throws(IOException::class)
    fun related(videoId: String, limit: Int): List<MediaSearchResult> {
        val result = next(videoId)
        val list = result.related.toMutableList()
        list.removeAll { it.id == videoId }
        return if (list.size > limit) list.subList(0, limit) else list
    }

    /** Fetches title, uploader, and view / like counts for a single [videoId]; returns null if the video is unavailable. */
    @Throws(IOException::class)
    fun metadata(videoId: String): MediaSearchResult? {
        val root = post("next", InnerTubeRequest(videoId = videoId))
        val meta = extractWatchMetadata(root) ?: return null
        return MediaSearchResult(
            videoId, meta.title ?: return null, meta.uploader, null,
            meta.viewCountRaw, meta.likeCountRaw, meta.publishedText, meta.daysAgo,
            channelAvatarUrl = meta.channelAvatarUrl,
        )
    }

    data class NextResult(
        val title: String?,
        val uploader: String?,
        val views: Long?,
        val likes: Long?,
        val related: List<MediaSearchResult>,
    )

    private data class MetaHolder(
        val title: String?,
        val uploader: String?,
        val viewCountRaw: Long?,
        val likeCountRaw: Long?,
        val publishedText: String?,
        val daysAgo: Int?,
        val channelAvatarUrl: String? = null,
        val isVerified: Boolean = false,
    )

    /** Sends a POST request to the InnerTube [endpoint] with [body] and returns the parsed JSON response. */
    @Throws(IOException::class)
    private fun post(endpoint: String, body: InnerTubeRequest): JsonObject {
        val url = "$BASE_URL/$endpoint?prettyPrint=false"
        val cookies = YtDlp.getPublicCookieHeader()
        val response = DreamHttpClient.execute(
            url,
            DreamHttpClient.RequestOptions(
                method = "POST",
                body = DreamJson.compact.encodeToString(body).toByteArray(StandardCharsets.UTF_8),
                contentType = "application/json",
                headers = DreamHttpClient.headersOf(
                    "User-Agent" to UA,
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Cookie" to (cookies ?: "CONSENT=YES+cb; SOCS=CAI; PREF=hl=en"),
                ),
                connectTimeoutMs = 8_000,
                readTimeoutMs = 15_000,
                proxyUrl = ResolverConfig.ytdlpProxy,
            ),
        )
        if (!response.isSuccessful) {
            throw IOException("$endpoint returned HTTP ${response.code}: ${response.bodyString().take(500)}")
        }
        return runCatching {
            DreamJson.compact.parseToJsonElement(response.bodyString()).asJsonObjectOrNull()
                ?: throw IOException("InnerTube $endpoint returned unexpected JSON shape")
        }.getOrElse { e ->
            throw IOException("Failed to parse InnerTube $endpoint response", e)
        }
    }

    /** Walks the `InnerTube` search response JSON and collects up to [limit] parsed video entries. */
    private fun extractSearchVideos(root: JsonObject, limit: Int): List<MediaSearchResult> {
        val out = ArrayList<MediaSearchResult>()
        runCatching {
            val sections = path(
                root, "contents", "twoColumnSearchResultsRenderer", "primaryContents",
                "sectionListRenderer", "contents"
            )
            val sectionArray = sections.asJsonArrayOrNull() ?: return out
            for (sec in sectionArray) {
                val isr = sec.asJsonObjectOrNull()?.obj("itemSectionRenderer") ?: continue
                val contents = isr.array("contents") ?: continue
                for (el in contents) {
                    val vr = el.asJsonObjectOrNull()?.obj("videoRenderer") ?: continue
                    parseVideoRenderer(vr)?.let {
                        out.add(it)
                        if (out.size >= limit) return out
                    }
                }
            }
        }.onFailure { e ->
            logger.warn("Search parse failed: ${e.message}")
        }
        return out
    }

    /** Parses a single `videoRenderer` JSON object from search results; returns null for Shorts or missing IDs. */
    private fun parseVideoRenderer(vr: JsonObject): MediaSearchResult? {
        val id = vr.optString("videoId") ?: return null
        if (looksLikeShorts(vr)) return null
        val title = runsText(vr.obj("title"))
            ?: simpleText(vr.obj("title")) ?: id
        val uploader = runsText(vr.obj("ownerText"))
            ?: runsText(vr.obj("longBylineText"))
        val duration = parseDuration(simpleText(vr.obj("lengthText")))
        val views = parseViews(simpleText(vr.obj("viewCountText")))
            ?: parseViews(simpleText(vr.obj("shortViewCountText")))
        val publishedText = simpleText(vr.obj("publishedTimeText"))
        val daysAgo = parseDaysAgo(publishedText)
        val avatarUrl = largestThumbnailUrl(
            vr.obj("channelThumbnailSupportedRenderers")?.obj("channelThumbnailWithLinkRenderer")?.obj("thumbnail")
        )
        val isVerified = hasVerifiedBadge(vr.array("ownerBadges"))
        return MediaSearchResult(
            id, title, uploader, duration, views, null, publishedText, daysAgo,
            channelAvatarUrl = avatarUrl, isVerified = isVerified,
        )
    }

    /** Extracts title, channel, view count, and like count from a `next` endpoint response. */
    private fun extractWatchMetadata(root: JsonObject): MetaHolder? {
        return runCatching {
            val contents = path(
                root, "contents", "twoColumnWatchNextResults", "results",
                "results", "contents"
            )
            val contentArray = contents.asJsonArrayOrNull() ?: return null
            var title: String? = null
            var channel: String? = null
            var channelAvatarUrl: String? = null
            var isVerified = false
            var views: Long? = null
            var likes: Long? = null
            var publishedText: String? = null
            var daysAgo: Int? = null
            for (el in contentArray) {
                val obj = el.asJsonObjectOrNull() ?: continue
                val vp = obj.obj("videoPrimaryInfoRenderer")
                if (vp != null) {
                    if (title == null) title = runsText(vp.obj("title"))
                    val dateText = simpleText(vp.obj("dateText"))
                    if (publishedText == null) publishedText = dateText
                    if (daysAgo == null) daysAgo = parseDaysAgo(dateText)
                    val viewCountObj = vp.obj("viewCount")?.obj("videoViewCountRenderer")?.obj("viewCount")
                    var v = parseViews(runsText(viewCountObj))
                    if (v == null) v = parseViews(simpleText(maybeViewCountText(vp)))
                    if (v != null) views = v
                    likes = extractLikeCount(vp)
                }
                val vs = obj.obj("videoSecondaryInfoRenderer")
                if (vs != null && channel == null) {
                    val owner = vs.obj("owner")?.obj("videoOwnerRenderer")
                    channel = runsText(owner?.obj("title"))
                    channelAvatarUrl = largestThumbnailUrl(owner?.obj("thumbnail"))
                }
            }
            if (title == null) return null
            MetaHolder(title, channel, views, likes, publishedText, daysAgo, channelAvatarUrl)
        }.onFailure { e ->
            logger.warn("Watch metadata parse failed: ${e.message}")
        }.getOrNull()
    }

    /** Parses up to [limit] related videos from a `next` response, skipping the video with id [selfId]. */
    private fun extractRelatedVideos(root: JsonObject, selfId: String, limit: Int): List<MediaSearchResult> {
        val out = ArrayList<MediaSearchResult>()
        runCatching {
            val results = path(
                root, "contents", "twoColumnWatchNextResults", "secondaryResults",
                "secondaryResults", "results"
            )
            val resultArray = results.asJsonArrayOrNull() ?: return out
            for (el in resultArray) {
                val cvr = el.asJsonObjectOrNull()?.obj("compactVideoRenderer") ?: continue
                val info = parseCompactVideoRenderer(cvr)
                if (info != null && info.id != selfId) {
                    out.add(info)
                    if (out.size >= limit) return out
                }
            }
        }.onFailure { e ->
            logger.warn("Related parse failed: ${e.message}")
        }
        return out
    }

    /** Parses a `compactVideoRenderer` JSON object from the related-video sidebar; returns null for Shorts or missing IDs. */
    private fun parseCompactVideoRenderer(cvr: JsonObject): MediaSearchResult? {
        val id = cvr.optString("videoId") ?: return null
        if (looksLikeShorts(cvr)) return null
        val title = simpleText(cvr.obj("title")) ?: id
        val uploader = simpleText(cvr.obj("longBylineText"))
            ?: simpleText(cvr.obj("shortBylineText"))
        val duration = parseDuration(simpleText(cvr.obj("lengthText")))
        val views = parseViews(simpleText(cvr.obj("viewCountText")))
            ?: parseViews(simpleText(cvr.obj("shortViewCountText")))
        val publishedText = simpleText(cvr.obj("publishedTimeText"))
        val daysAgo = parseDaysAgo(publishedText)
        val avatarUrl = largestThumbnailUrl(cvr.obj("channelThumbnail"))
        val isVerified = hasVerifiedBadge(cvr.array("ownerBadges") ?: cvr.array("channelBadges"))
        return MediaSearchResult(
            id, title, uploader, duration, views, null, publishedText, daysAgo,
            channelAvatarUrl = avatarUrl, isVerified = isVerified,
        )
    }

    /** Navigates the nested `viewCount.videoViewCountRenderer.viewCount` path in [vp]; returns null if absent. */
    private fun maybeViewCountText(vp: JsonObject): JsonObject? {
        return vp.obj("viewCount")?.obj("videoViewCountRenderer")?.obj("viewCount")
    }

    /** Drills through the deeply nested like-button view model in [vp] to extract a numeric like count. */
    private fun extractLikeCount(vp: JsonObject): Long? {
        val topLevel = vp.obj("videoActions")?.obj("menuRenderer")?.array("topLevelButtons") ?: return null
        for (btn in topLevel) {
            val bo = btn.asJsonObjectOrNull() ?: continue
            val buttonViewModel = bo
                .obj("segmentedLikeDislikeButtonViewModel")
                ?.obj("likeButtonViewModel")
                ?.obj("likeButtonViewModel")
                ?.obj("toggleButtonViewModel")
                ?.obj("toggleButtonViewModel")
                ?.obj("defaultButtonViewModel")
                ?.obj("buttonViewModel")
                ?: continue
            val parsed = parseViews(buttonViewModel.optString("title"))
            if (parsed != null) return parsed
        }
        return null
    }

    /** Returns true if [vr] appears to be a YouTube Shorts entry based on its navigation URL or JSON markers. */
    private fun looksLikeShorts(vr: JsonObject): Boolean {
        val webUrl = runCatching {
            vr.obj("navigationEndpoint")
                ?.obj("commandMetadata")
                ?.obj("webCommandMetadata")
                ?.optString("url")
        }.getOrNull()
        if (webUrl != null && webUrl.startsWith("/shorts/")) return true
        val s = vr.toString()
        return "\"label\":\"Shorts\"" in s || "shortsLockupViewModel" in s
    }

    /** Traverses [obj] along [keys] and returns the element at the end, or an empty object if any step is missing. */
    private fun path(obj: JsonObject, vararg keys: String): JsonElement? {
        var cur: JsonElement? = obj
        for (k in keys) {
            cur = cur.asJsonObjectOrNull()?.get(k) ?: return null
        }
        return cur
    }

    /** Concatenates all `text` values from the `runs` array in [obj]; returns null if the array is absent or empty. */
    private fun runsText(obj: JsonObject?): String? {
        val runs = obj?.array("runs") ?: return null
        val sb = StringBuilder()
        for (el in runs) {
            el.asJsonObjectOrNull()?.optString("text")?.let { sb.append(it) }
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /** Returns the `simpleText` field of [obj], falling back to [runsText] if absent. */
    private fun simpleText(obj: JsonObject?): String? {
        if (obj == null) return null
        return obj.optString("simpleText") ?: runsText(obj)
    }

    /** Extracts the largest listed image URL from a `{thumbnails: [{url, width, height}, ...]}`-shaped [thumbnailContainer]. */
    private fun largestThumbnailUrl(thumbnailContainer: JsonObject?): String? {
        val thumbs = thumbnailContainer?.array("thumbnails") ?: return null
        return thumbs.lastOrNull()?.asJsonObjectOrNull()?.optString("url")
    }

    /** Returns true if [badgesArray] contains a `metadataBadgeRenderer` whose style is a verified-channel badge. */
    private fun hasVerifiedBadge(badgesArray: JsonArray?): Boolean {
        val badges = badgesArray ?: return false
        return badges.any { badge ->
            val style = badge.asJsonObjectOrNull()?.obj("metadataBadgeRenderer")?.optString("style")
            style != null && "VERIFIED" in style
        }
    }

    /** Parses a colon-separated duration string (e.g. "1:23:45") into total seconds, or null on failure. */
    private fun parseDuration(s: String?): Long? {
        if (s == null) return null
        val parts = s.split(":")
        return runCatching {
            var total = 0L
            for (p in parts) total = total * 60 + p.trim().toInt()
            total
        }.getOrNull()
    }

    /** Parses a human-readable view count string (e.g. "1.2M views", "45K") into a raw long, or null on failure. */
    private fun parseViews(s: String?): Long? {
        if (s == null) return null
        var t = s.lowercase().replace(",", "").replace("views", "").trim()
        if (t.isEmpty()) return null
        var mult = 1.0
        when (t.last()) {
            'k' -> {
                mult = 1_000.0; t = t.dropLast(1)
            }

            'm' -> {
                mult = 1_000_000.0; t = t.dropLast(1)
            }

            'b' -> {
                mult = 1_000_000_000.0; t = t.dropLast(1)
            }
        }
        return runCatching { (t.trim().toDouble() * mult).toLong() }.getOrNull()
    }

    /** Converts a relative age string (e.g. "3 days ago", "2 weeks ago") to an approximate day count, or null. */
    private fun parseDaysAgo(s: String?): Int? {
        if (s == null) return null
        val m = AGE_PATTERN.matcher(s)
        if (!m.find()) return null
        val n = runCatching { m.group(1).toInt() }.getOrNull() ?: return null
        return when (m.group(2).lowercase()) {
            "second", "minute", "hour" -> 0
            "day" -> n
            "week" -> n * 7
            "month" -> n * 30
            "year" -> n * 365
            else -> null
        }
    }
}
