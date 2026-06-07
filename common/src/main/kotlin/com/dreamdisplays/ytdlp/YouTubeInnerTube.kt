package com.dreamdisplays.ytdlp

import com.dreamdisplays.managers.ClientStateManager
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Direct client for YouTube's InnerTube API.
 */
object YouTubeInnerTube {
    private val logger = LoggerFactory.getLogger("DreamDisplays/InnerTube")

    private const val BASE_URL = "https://www.youtube.com/youtubei/v1"
    private const val CLIENT_NAME = "WEB"
    private const val CLIENT_VERSION = "2.20250501.00.00"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val AGE_PATTERN: Pattern = Pattern.compile(
        "(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago",
        Pattern.CASE_INSENSITIVE
    )

    /** Searches YouTube for [query] and returns up to [limit] video results via the InnerTube search endpoint. */
    @Throws(IOException::class)
    fun search(query: String, limit: Int): List<YtVideoInfo> {
        val body = baseContext().apply {
            addProperty("query", query)
        }
        val root = post("search", body)
        return extractSearchVideos(root, limit)
    }

    /** Fetches watch-next metadata and related videos for [videoId] via the InnerTube `next` endpoint. */
    @Throws(IOException::class)
    fun next(videoId: String): NextResult {
        val body = baseContext().apply {
            addProperty("videoId", videoId)
        }
        val root = post("next", body)
        val meta = extractWatchMetadata(root, videoId)
        val related = extractRelatedVideos(root, videoId, 25)
        return NextResult(meta?.title, meta?.uploader, meta?.viewCountRaw, meta?.likeCountRaw, related)
    }

    /** Returns up to [limit] related videos for [videoId], excluding the video itself. */
    @Throws(IOException::class)
    fun related(videoId: String, limit: Int): List<YtVideoInfo> {
        val result = next(videoId)
        val list = result.related.toMutableList()
        list.removeAll { it.id == videoId }
        return if (list.size > limit) list.subList(0, limit) else list
    }

    /** Fetches title, uploader, and view / like counts for a single [videoId]; returns null if the video is unavailable. */
    @Throws(IOException::class)
    fun metadata(videoId: String): YtVideoInfo? {
        val body = baseContext().apply {
            addProperty("videoId", videoId)
        }
        val root = post("next", body)
        val meta = extractWatchMetadata(root, videoId) ?: return null
        return YtVideoInfo(
            videoId, meta.title ?: return null, meta.uploader, null,
            meta.viewCountRaw, meta.likeCountRaw, meta.publishedText, meta.daysAgo
        )
    }

    data class NextResult(
        val title: String?,
        val uploader: String?,
        val views: Long?,
        val likes: Long?,
        val related: List<YtVideoInfo>,
    )

    private data class MetaHolder(
        val title: String?,
        val uploader: String?,
        val viewCountRaw: Long?,
        val likeCountRaw: Long?,
        val publishedText: String?,
        val daysAgo: Int?,
    )

    /** Sends a POST request to the InnerTube [endpoint] with [body] and returns the parsed JSON response. */
    @Throws(IOException::class)
    private fun post(endpoint: String, body: JsonObject): JsonObject {
        val url = "$BASE_URL/$endpoint?prettyPrint=false"
        val conn = openConnection(url)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        val cookies = YtDlp.getPublicCookieHeader()
        conn.setRequestProperty("Cookie", cookies ?: "CONSENT=YES+cb; SOCS=CAI; PREF=hl=en")

        try {
            val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }

            val status = conn.responseCode
            if (status !in 200..299) {
                val e = try {
                    conn.errorStream?.readAllBytes()?.toString(StandardCharsets.UTF_8)?.take(500) ?: ""
                } catch (_: Exception) {
                    ""
                }
                throw IOException("$endpoint returned HTTP $status: $e")
            }
            conn.inputStream.use { input ->
                val raw = String(input.readAllBytes(), StandardCharsets.UTF_8)
                return try {
                    JsonParser.parseString(raw).asJsonObject
                } catch (e: Exception) {
                    throw IOException("Failed to parse InnerTube $endpoint response", e)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Opens an [HttpURLConnection] for [url], routing through the configured proxy if one is set. */
    private fun openConnection(url: String): HttpURLConnection {
        val uri = URI.create(url)
        val proxyStr = try {
            ClientStateManager.config.ytdlpProxy.trim()
        } catch (_: Exception) {
            ""
        }
        if (proxyStr.isEmpty()) {
            return uri.toURL().openConnection() as HttpURLConnection
        }
        val proxyUri = try {
            URI.create(proxyStr)
        } catch (_: Exception) {
            logger.warn("Invalid proxy URL: $proxyStr.")
            return uri.toURL().openConnection() as HttpURLConnection
        }
        val type = when (proxyUri.scheme?.lowercase()) {
            "socks5", "socks4", "socks" -> Proxy.Type.SOCKS
            else -> Proxy.Type.HTTP
        }
        val port = if (proxyUri.port > 0) proxyUri.port else if (type == Proxy.Type.SOCKS) 1080 else 8080
        val host = proxyUri.host ?: return uri.toURL().openConnection() as HttpURLConnection
        val proxy = Proxy(type, InetSocketAddress(host, port))
        return uri.toURL().openConnection(proxy) as HttpURLConnection
    }

    /** Builds the base InnerTube request body with client context (name, version, language). */
    private fun baseContext(): JsonObject {
        val client = JsonObject().apply {
            addProperty("clientName", CLIENT_NAME)
            addProperty("clientVersion", CLIENT_VERSION)
            addProperty("hl", "en")
        }
        val context = JsonObject().apply {
            add("client", client)
        }
        return JsonObject().apply {
            add("context", context)
        }
    }

    /** Walks the InnerTube search response JSON and collects up to [limit] parsed video entries. */
    private fun extractSearchVideos(root: JsonObject, limit: Int): List<YtVideoInfo> {
        val out = ArrayList<YtVideoInfo>()
        try {
            val sections = path(
                root, "contents", "twoColumnSearchResultsRenderer", "primaryContents",
                "sectionListRenderer", "contents"
            )
            if (!sections.isJsonArray) return out
            for (sec in sections.asJsonArray) {
                if (!sec.isJsonObject) continue
                val isr = sec.asJsonObject.get("itemSectionRenderer")
                if (isr == null || !isr.isJsonObject) continue
                val contents = isr.asJsonObject.getAsJsonArray("contents") ?: continue
                for (el in contents) {
                    if (!el.isJsonObject) continue
                    val vr = el.asJsonObject.get("videoRenderer")
                    if (vr == null || !vr.isJsonObject) continue
                    parseVideoRenderer(vr.asJsonObject)?.let {
                        out.add(it)
                        if (out.size >= limit) return out
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Search parse failed: ${e.message}")
        }
        return out
    }

    /** Parses a single `videoRenderer` JSON object from search results; returns null for Shorts or missing IDs. */
    private fun parseVideoRenderer(vr: JsonObject): YtVideoInfo? {
        val id = optString(vr, "videoId") ?: return null
        if (looksLikeShorts(vr)) return null
        val title = runsText(vr.getAsJsonObject("title"))
            ?: simpleText(vr.getAsJsonObject("title")) ?: id
        val uploader = runsText(vr.getAsJsonObject("ownerText"))
            ?: runsText(vr.getAsJsonObject("longBylineText"))
        val duration = parseDuration(simpleText(vr.getAsJsonObject("lengthText")))
        val views = parseViews(simpleText(vr.getAsJsonObject("viewCountText")))
            ?: parseViews(simpleText(vr.getAsJsonObject("shortViewCountText")))
        val publishedText = simpleText(vr.getAsJsonObject("publishedTimeText"))
        val daysAgo = parseDaysAgo(publishedText)
        return YtVideoInfo(id, title, uploader, duration, views, null, publishedText, daysAgo)
    }

    /** Extracts title, channel, view count, and like count from a `next` endpoint response for [videoId]. */
    private fun extractWatchMetadata(root: JsonObject, videoId: String): MetaHolder? {
        try {
            val contents = path(
                root, "contents", "twoColumnWatchNextResults", "results",
                "results", "contents"
            )
            if (!contents.isJsonArray) return null
            var title: String? = null
            var channel: String? = null
            var views: Long? = null
            var likes: Long? = null
            var publishedText: String? = null
            var daysAgo: Int? = null
            for (el in contents.asJsonArray) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                if (obj.has("videoPrimaryInfoRenderer")) {
                    val vp = obj.getAsJsonObject("videoPrimaryInfoRenderer")
                    if (title == null) title = runsText(vp.getAsJsonObject("title"))
                    val dateText = simpleText(vp.getAsJsonObject("dateText"))
                    if (publishedText == null) publishedText = dateText
                    if (daysAgo == null) daysAgo = parseDaysAgo(dateText)
                    val vc = vp.getAsJsonObject("viewCount")
                    val viewCountObj = vc?.getAsJsonObject("videoViewCountRenderer")?.getAsJsonObject("viewCount")
                    var v = parseViews(runsText(viewCountObj))
                    if (v == null) v = parseViews(simpleText(maybeViewCountText(vp)))
                    if (v != null) views = v
                    likes = extractLikeCount(vp)
                }
                if (obj.has("videoSecondaryInfoRenderer")) {
                    val vs = obj.getAsJsonObject("videoSecondaryInfoRenderer")
                    if (channel == null) {
                        val owner = vs.getAsJsonObject("owner")
                        if (owner != null) {
                            val vor = owner.getAsJsonObject("videoOwnerRenderer")
                            if (vor != null) channel = runsText(vor.getAsJsonObject("title"))
                        }
                    }
                }
            }
            if (title == null) return null
            return MetaHolder(title, channel, views, likes, publishedText, daysAgo)
        } catch (e: Exception) {
            logger.warn("Watch metadata parse failed: ${e.message}")
            return null
        }
    }

    /** Parses up to [limit] related videos from a `next` response, skipping the video with id [selfId]. */
    private fun extractRelatedVideos(root: JsonObject, selfId: String, limit: Int): List<YtVideoInfo> {
        val out = ArrayList<YtVideoInfo>()
        try {
            val results = path(
                root, "contents", "twoColumnWatchNextResults", "secondaryResults",
                "secondaryResults", "results"
            )
            if (!results.isJsonArray) return out
            for (el in results.asJsonArray) {
                if (!el.isJsonObject) continue
                val cvr = el.asJsonObject.get("compactVideoRenderer")
                if (cvr == null || !cvr.isJsonObject) continue
                val info = parseCompactVideoRenderer(cvr.asJsonObject)
                if (info != null && info.id != selfId) {
                    out.add(info)
                    if (out.size >= limit) return out
                }
            }
        } catch (e: Exception) {
            logger.warn("Related parse failed: ${e.message}")
        }
        return out
    }

    /** Parses a `compactVideoRenderer` JSON object from the related-video sidebar; returns null for Shorts or missing IDs. */
    private fun parseCompactVideoRenderer(cvr: JsonObject): YtVideoInfo? {
        val id = optString(cvr, "videoId") ?: return null
        if (looksLikeShorts(cvr)) return null
        val title = simpleText(cvr.getAsJsonObject("title")) ?: id
        val uploader = simpleText(cvr.getAsJsonObject("longBylineText"))
            ?: simpleText(cvr.getAsJsonObject("shortBylineText"))
        val duration = parseDuration(simpleText(cvr.getAsJsonObject("lengthText")))
        val views = parseViews(simpleText(cvr.getAsJsonObject("viewCountText")))
            ?: parseViews(simpleText(cvr.getAsJsonObject("shortViewCountText")))
        val publishedText = simpleText(cvr.getAsJsonObject("publishedTimeText"))
        val daysAgo = parseDaysAgo(publishedText)
        return YtVideoInfo(id, title, uploader, duration, views, null, publishedText, daysAgo)
    }

    /** Navigates the nested `viewCount.videoViewCountRenderer.viewCount` path in [vp]; returns null if absent. */
    private fun maybeViewCountText(vp: JsonObject): JsonObject? {
        if (!vp.has("viewCount")) return null
        val vc = vp.getAsJsonObject("viewCount") ?: return null
        if (vc.has("videoViewCountRenderer")) {
            val vvcr = vc.getAsJsonObject("videoViewCountRenderer")
            if (vvcr != null && vvcr.has("viewCount")) return vvcr.getAsJsonObject("viewCount")
        }
        return null
    }

    /** Drills through the deeply nested like-button view model in [vp] to extract a numeric like count. */
    private fun extractLikeCount(vp: JsonObject): Long? {
        val menu = vp.getAsJsonObject("videoActions") ?: return null
        val mr = menu.getAsJsonObject("menuRenderer") ?: return null
        val topLevel = mr.get("topLevelButtons")
        if (topLevel == null || !topLevel.isJsonArray) return null
        for (btn in topLevel.asJsonArray) {
            if (!btn.isJsonObject) continue
            val bo = btn.asJsonObject
            val sbvr = bo.getAsJsonObject("segmentedLikeDislikeButtonViewModel") ?: continue
            val lb = sbvr.getAsJsonObject("likeButtonViewModel") ?: continue
            val inner = lb.getAsJsonObject("likeButtonViewModel") ?: continue
            val toggleButton = inner.getAsJsonObject("toggleButtonViewModel") ?: continue
            val tbvm = toggleButton.getAsJsonObject("toggleButtonViewModel") ?: continue
            val defaultButton = tbvm.getAsJsonObject("defaultButtonViewModel") ?: continue
            val buttonViewModel = defaultButton.getAsJsonObject("buttonViewModel") ?: continue
            val parsed = parseViews(optString(buttonViewModel, "title"))
            if (parsed != null) return parsed
        }
        return null
    }

    /** Returns true if [vr] appears to be a YouTube Shorts entry based on its navigation URL or JSON markers. */
    private fun looksLikeShorts(vr: JsonObject): Boolean {
        try {
            val nav = vr.getAsJsonObject("navigationEndpoint")
            if (nav != null) {
                val cmd = nav.getAsJsonObject("commandMetadata")
                if (cmd != null) {
                    val web = cmd.getAsJsonObject("webCommandMetadata")
                    if (web != null) {
                        val webUrl = optString(web, "url")
                        if (webUrl != null && webUrl.startsWith("/shorts/")) return true
                    }
                }
            }
        } catch (_: Exception) {
        }
        val s = vr.toString()
        return "\"label\":\"Shorts\"" in s || "shortsLockupViewModel" in s
    }

    /** Traverses [obj] along [keys] and returns the element at the end, or an empty object if any step is missing. */
    private fun path(obj: JsonObject, vararg keys: String): JsonElement {
        var cur: JsonElement? = obj
        for (k in keys) {
            if (cur == null || !cur.isJsonObject) return JsonObject()
            cur = cur.asJsonObject.get(k)
        }
        return cur ?: JsonObject()
    }

    /** Returns the string value of [key] in [obj], or null if absent, null, or not a string. */
    private fun optString(obj: JsonObject, key: String): String? {
        if (!obj.has(key) || obj.get(key).isJsonNull) return null
        return try {
            obj.get(key).asString
        } catch (_: Exception) {
            null
        }
    }

    /** Concatenates all `text` values from the `runs` array in [obj]; returns null if the array is absent or empty. */
    private fun runsText(obj: JsonObject?): String? {
        if (obj == null || !obj.has("runs") || !obj.get("runs").isJsonArray) return null
        val sb = StringBuilder()
        for (el in obj.getAsJsonArray("runs")) {
            if (!el.isJsonObject) continue
            optString(el.asJsonObject, "text")?.let { sb.append(it) }
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /** Returns the `simpleText` field of [obj], falling back to [runsText] if absent. */
    private fun simpleText(obj: JsonObject?): String? {
        if (obj == null) return null
        return optString(obj, "simpleText") ?: runsText(obj)
    }

    /** Parses a colon-separated duration string (e.g. "1:23:45") into total seconds, or null on failure. */
    private fun parseDuration(s: String?): Long? {
        if (s == null) return null
        val parts = s.split(":")
        return try {
            var total = 0L
            for (p in parts) total = total * 60 + p.trim().toInt()
            total
        } catch (_: NumberFormatException) {
            null
        }
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
        return try {
            (t.trim().toDouble() * mult).toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Converts a relative age string (e.g. "3 days ago", "2 weeks ago") to an approximate day count, or null. */
    private fun parseDaysAgo(s: String?): Int? {
        if (s == null) return null
        val m = AGE_PATTERN.matcher(s)
        if (!m.find()) return null
        val n = try {
            m.group(1).toInt()
        } catch (_: NumberFormatException) {
            return null
        }
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
