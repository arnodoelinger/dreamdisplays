package com.dreamdisplays.media.source.ytdlp

import com.dreamdisplays.api.media.search.MediaSearchPage
import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.api.media.search.SortOrder
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
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

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
        val continuation: String? = null,
        val params: String? = null,
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
    fun search(query: String, limit: Int): List<MediaSearchResult> = searchPage(query, limit).results

    /** Returns the first page (up to [limit] videos) matching [query] in [sortOrder], plus a continuation token for [searchMore]. */
    @Throws(IOException::class)
    fun searchPage(query: String, limit: Int, sortOrder: SortOrder = SortOrder.RELEVANCE): MediaSearchPage {
        val root = post("search", InnerTubeRequest(query = query, params = sortOrder.spParam))
        return extractSearchPage(root, limit)
    }

    /** Returns the page following [continuationToken] from a prior [searchPage]/[searchMore] call. */
    @Throws(IOException::class)
    fun searchMore(continuationToken: String, limit: Int): MediaSearchPage {
        val root = post("search", InnerTubeRequest(continuation = continuationToken))
        return extractSearchContinuationPage(root, limit)
    }

    /** Fetches watch-next metadata and related videos for [videoId] via the `InnerTube` `next` endpoint. */
    @Throws(IOException::class)
    fun next(videoId: String): NextResult {
        val root = post("next", InnerTubeRequest(videoId = videoId))
        val meta = extractWatchMetadata(root)
        val related = extractRelatedPage(root, videoId, 25)
        return NextResult(meta?.title, meta?.uploader, meta?.viewCountRaw, meta?.likeCountRaw, related.results)
    }

    /** Returns up to [limit] related videos for [videoId], excluding the video itself. */
    @Throws(IOException::class)
    fun related(videoId: String, limit: Int): List<MediaSearchResult> {
        val result = next(videoId)
        val list = result.related.toMutableList()
        list.removeAll { it.id == videoId }
        return if (list.size > limit) list.subList(0, limit) else list
    }

    /**
     * Returns the first page (up to [limit] videos) related to [videoId], plus a continuation token for
     * [relatedMore]. Falls back to a title search when the related sidebar is empty (rare, but seen on
     * videos with related recommendations disabled); that fallback batch has no continuation of its own
     * since it's a one-shot substitute, not a real paginated list.
     */
    @Throws(IOException::class)
    fun relatedPage(videoId: String, limit: Int): MediaSearchPage {
        val root = post("next", InnerTubeRequest(videoId = videoId))
        val page = extractRelatedPage(root, videoId, limit + 1)
        val hits = page.results.filter { it.id != videoId }.take(limit)
        if (hits.isNotEmpty()) return MediaSearchPage(hits, page.continuationToken)
        val title = extractWatchMetadata(root)?.title ?: return MediaSearchPage(hits, null)
        val fallback = extractSearchPage(post("search", InnerTubeRequest(query = title)), limit)
        return MediaSearchPage(fallback.results.filter { it.id != videoId }, null)
    }

    /** Returns the page following [continuationToken] from a prior [relatedPage]/[relatedMore] call. */
    @Throws(IOException::class)
    fun relatedMore(continuationToken: String, limit: Int): MediaSearchPage {
        val root = post("next", InnerTubeRequest(continuation = continuationToken))
        return extractRelatedContinuationPage(root, limit)
    }

    /** Fetches title, uploader, and view / like counts for a single [videoId]; returns null if the video is unavailable. */
    @Throws(IOException::class)
    fun metadata(videoId: String): MediaSearchResult? {
        val root = post("next", InnerTubeRequest(videoId = videoId))
        val meta = extractWatchMetadata(root) ?: return null
        return MediaSearchResult(
            videoId, meta.title ?: return null, meta.uploader, null,
            meta.viewCountRaw, meta.likeCountRaw, meta.publishedText, meta.daysAgo,
            channelAvatarUrl = meta.channelAvatarUrl, isVerified = meta.isVerified,
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

    /** Walks the initial `InnerTube` search response JSON, collecting up to [limit] videos plus the next-page token. */
    private fun extractSearchPage(root: JsonObject, limit: Int): MediaSearchPage {
        val out = ArrayList<MediaSearchResult>()
        var token: String? = null
        runCatching {
            val sections = path(
                root, "contents", "twoColumnSearchResultsRenderer", "primaryContents",
                "sectionListRenderer", "contents"
            )
            val sectionArray = sections.asJsonArrayOrNull() ?: return MediaSearchPage(out, null)
            for (sec in sectionArray) {
                val secObj = sec.asJsonObjectOrNull() ?: continue
                secObj.obj("continuationItemRenderer")?.let { token = token ?: continuationToken(it) }
                val contents = secObj.obj("itemSectionRenderer")?.array("contents") ?: continue
                for (el in contents) {
                    val vr = el.asJsonObjectOrNull()?.obj("videoRenderer") ?: continue
                    parseVideoRenderer(vr)?.let { out.add(it) }
                }
            }
        }.onFailure { e ->
            logger.warn("Search parse failed: ${e.message}")
        }
        return MediaSearchPage(if (out.size > limit) out.subList(0, limit) else out, token)
    }

    /** Walks a search continuation response (`appendContinuationItemsAction`), collecting up to [limit] videos plus the next token. */
    private fun extractSearchContinuationPage(root: JsonObject, limit: Int): MediaSearchPage {
        val out = ArrayList<MediaSearchResult>()
        var token: String? = null
        runCatching {
            val commands = path(root, "onResponseReceivedCommands").asJsonArrayOrNull() ?: return MediaSearchPage(out, null)
            for (cmd in commands) {
                val items = cmd.asJsonObjectOrNull()?.obj("appendContinuationItemsAction")?.array("continuationItems")
                    ?: continue
                for (el in items) {
                    val itemObj = el.asJsonObjectOrNull() ?: continue
                    itemObj.obj("continuationItemRenderer")?.let { token = token ?: continuationToken(it) }
                    val contents = itemObj.obj("itemSectionRenderer")?.array("contents")
                    if (contents != null) {
                        for (c in contents) {
                            val vr = c.asJsonObjectOrNull()?.obj("videoRenderer") ?: continue
                            parseVideoRenderer(vr)?.let { out.add(it) }
                        }
                    } else {
                        itemObj.obj("videoRenderer")?.let { vr -> parseVideoRenderer(vr)?.let { out.add(it) } }
                    }
                }
            }
        }.onFailure { e ->
            logger.warn("Search continuation parse failed: ${e.message}")
        }
        return MediaSearchPage(if (out.size > limit) out.subList(0, limit) else out, token)
    }

    /** Extracts the continuation token from a `continuationItemRenderer` object, or null if malformed. */
    private fun continuationToken(continuationItemRenderer: JsonObject): String? =
        continuationItemRenderer.obj("continuationEndpoint")?.obj("continuationCommand")?.optString("token")

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
        val isLive = hasLiveBadge(vr.array("badges"))
        return MediaSearchResult(
            id, title, uploader, duration, views, null, publishedText, daysAgo,
            channelAvatarUrl = avatarUrl, isVerified = isVerified, isLive = isLive,
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
                    isVerified = hasVerifiedBadge(owner?.array("badges"))
                }
            }
            if (title == null) return null
            MetaHolder(title, channel, views, likes, publishedText, daysAgo, channelAvatarUrl, isVerified)
        }.onFailure { e ->
            logger.warn("Watch metadata parse failed: ${e.message}")
        }.getOrNull()
    }

    /**
     * Walks the initial `next` response's related-video sidebar, collecting up to [limit] videos
     * (including [selfId] — callers filter it out) plus the next-page token. YouTube's related sidebar
     * has migrated from `compactVideoRenderer` to `lockupViewModel`; both are handled, [lockupViewModel]
     * first since it's the current shape.
     */
    private fun extractRelatedPage(root: JsonObject, selfId: String, limit: Int): MediaSearchPage {
        val out = ArrayList<MediaSearchResult>()
        var token: String? = null
        runCatching {
            val results = path(
                root, "contents", "twoColumnWatchNextResults", "secondaryResults",
                "secondaryResults", "results"
            )
            val resultArray = results.asJsonArrayOrNull() ?: return MediaSearchPage(out, null)
            for (el in resultArray) {
                val itemObj = el.asJsonObjectOrNull() ?: continue
                itemObj.obj("continuationItemRenderer")?.let { token = token ?: continuationToken(it) }
                val info = itemObj.obj("lockupViewModel")?.let(::parseLockupViewModel)
                    ?: itemObj.obj("compactVideoRenderer")?.let(::parseCompactVideoRenderer)
                // Keep scanning past `limit` (just stop collecting) instead of returning early — the
                // continuationItemRenderer that carries the next-page token is the LAST element in this
                // array, after all the real video items, so an early return here would exit before ever
                // seeing it and permanently strand the list with no continuation.
                if (info != null && out.size < limit) out.add(info)
            }
        }.onFailure { e ->
            logger.warn("Related parse failed: ${e.message}")
        }
        return MediaSearchPage(out, token)
    }

    /**
     * Walks a related-video continuation response (`appendContinuationItemsAction` under
     * `onResponseReceivedEndpoints`), collecting up to [limit] videos plus the next token. Continuation
     * items are `lockupViewModel`s directly (no `itemSectionRenderer` wrapper, unlike search).
     */
    private fun extractRelatedContinuationPage(root: JsonObject, limit: Int): MediaSearchPage {
        val out = ArrayList<MediaSearchResult>()
        var token: String? = null
        runCatching {
            val endpoints = path(root, "onResponseReceivedEndpoints").asJsonArrayOrNull() ?: return MediaSearchPage(out, null)
            for (ep in endpoints) {
                val items = ep.asJsonObjectOrNull()?.obj("appendContinuationItemsAction")?.array("continuationItems")
                    ?: continue
                for (el in items) {
                    val itemObj = el.asJsonObjectOrNull() ?: continue
                    itemObj.obj("continuationItemRenderer")?.let { token = token ?: continuationToken(it) }
                    val info = itemObj.obj("lockupViewModel")?.let(::parseLockupViewModel)
                        ?: itemObj.obj("compactVideoRenderer")?.let(::parseCompactVideoRenderer)
                    // See extractRelatedPage: don't return early, the token-bearing item can trail the
                    // page's video items and an early exit would strand the list without a next token.
                    if (info != null && out.size < limit) out.add(info)
                }
            }
        }.onFailure { e ->
            logger.warn("Related continuation parse failed: ${e.message}")
        }
        return MediaSearchPage(out, token)
    }

    /** Parses a `lockupViewModel` JSON object (the current related-video card shape); returns null for non-video content. */
    private fun parseLockupViewModel(lockup: JsonObject): MediaSearchResult? {
        val id = lockup.optString("contentId") ?: return null
        if (lockup.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val metadataVm = lockup.obj("metadata")?.obj("lockupMetadataViewModel") ?: return null
        val title = metadataVm.obj("title")?.optString("content") ?: id
        val avatarUrl = metadataVm.obj("image")
            ?.obj("decoratedAvatarViewModel")?.obj("avatar")?.obj("avatarViewModel")
            ?.obj("image")?.array("sources")?.lastOrNull()?.asJsonObjectOrNull()?.optString("url")
        val rows = metadataVm.obj("metadata")?.obj("contentMetadataViewModel")?.array("metadataRows")
        val uploader = rows?.getOrNull(0)?.asJsonObjectOrNull()
            ?.array("metadataParts")?.firstOrNull()?.asJsonObjectOrNull()
            ?.obj("text")?.optString("content")
        val detailParts = rows?.getOrNull(1)?.asJsonObjectOrNull()?.array("metadataParts")
        val views = parseViews(detailParts?.getOrNull(0)?.asJsonObjectOrNull()?.obj("text")?.optString("content"))
        val publishedText = detailParts?.getOrNull(1)?.asJsonObjectOrNull()?.obj("text")?.optString("content")
        val daysAgo = parseDaysAgo(publishedText)
        // A live broadcast has no fixed length, so YouTube puts "LIVE" in the same bottom-overlay badge
        // slot a finished video would use for its duration; parseDuration silently rejects that text.
        val durationBadgeText = lockup.obj("contentImage")?.obj("thumbnailViewModel")?.array("overlays")
            ?.firstOrNull()?.asJsonObjectOrNull()?.obj("thumbnailBottomOverlayViewModel")
            ?.array("badges")?.firstOrNull()?.asJsonObjectOrNull()
            ?.obj("thumbnailBadgeViewModel")?.optString("text")
        val duration = parseDuration(durationBadgeText)
        val isLive = durationBadgeText?.equals("LIVE", ignoreCase = true) == true
        return MediaSearchResult(
            id, title, uploader, duration, views, null, publishedText, daysAgo,
            channelAvatarUrl = avatarUrl, isLive = isLive,
        )
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
        val isLive = hasLiveBadge(cvr.array("badges"))
        return MediaSearchResult(
            id, title, uploader, duration, views, null, publishedText, daysAgo,
            channelAvatarUrl = avatarUrl, isVerified = isVerified, isLive = isLive,
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

    /** Returns true if [badgesArray] contains a `metadataBadgeRenderer` marking the video as currently live. */
    private fun hasLiveBadge(badgesArray: JsonArray?): Boolean {
        val badges = badgesArray ?: return false
        return badges.any { badge ->
            val style = badge.asJsonObjectOrNull()?.obj("metadataBadgeRenderer")?.optString("style")
            style != null && "LIVE_NOW" in style
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
