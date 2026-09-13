package com.dreamdisplays.api.media.source.url

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.security.model.MediaHttpUrl
import java.util.*

/**
 * Recognizes and dissects Bilibili URLs (VODs, bangumi episodes / seasons, live rooms, `b23.tv` short links).
 *
 * @since 1.9.x
 */
@Unstable
object BilibiliUrls {
    /** A `BVID`: `BV` followed by 10 alphanumeric characters. */
    private val BVID_RE = Regex("^BV[0-9A-Za-z]{10}$")

    /** A legacy `AVID`: `av` followed by digits. */
    private val AVID_RE = Regex("^[aA][vV](\\d+)$")

    /** A bangumi episode id: `ep` followed by digits. */
    private val EP_ID_RE = Regex("^[eE][pP](\\d+)$")

    /** A bangumi season id: `ss` followed by digits. */
    private val SEASON_ID_RE = Regex("^[sS][sS](\\d+)$")

    /** True when [url] points at a BIlibili VOD, live room, or short link. */
    fun isBilibili(url: String): Boolean = parse(url) != null

    /**
     * Parses [url] into a [com.dreamdisplays.api.media.source.MediaSource.Bilibili], or null if not recognizable. A `b23.tv` short link
     * is recognized but left unresolved (no bvid / avid / roomId) — following its redirect needs a
     * network call, which belongs in [com.dreamdisplays.media.source.bilibili.BilibiliApi], not here.
     */
    fun parse(url: String): MediaSource.Bilibili? {
        val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}") ?: return null
        val host = parsed.uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.removePrefix("m.") ?: return null

        val normalized = parsed.value
        val segments = parsed.uri.path?.split('/')?.filter { it.isNotBlank() } ?: emptyList()

        if (host == "b23.tv") {
            if (segments.isEmpty()) return null
            return MediaSource.Bilibili(url = normalized)
        }

        if (host == "live.bilibili.com") {
            val roomId = segments.firstOrNull()?.toLongOrNull() ?: return null
            return MediaSource.Bilibili(url = normalized, roomId = roomId)
        }

        if (host != "bilibili.com") return null

        if (segments.getOrNull(0) == "bangumi" && segments.getOrNull(1) == "play") {
            val token = segments.getOrNull(2) ?: return null
            EP_ID_RE.matchEntire(token)?.let { m ->
                val epId = m.groupValues[1].toLongOrNull() ?: return null
                return MediaSource.Bilibili(url = normalized, epId = epId)
            }
            SEASON_ID_RE.matchEntire(token)?.let { m ->
                val seasonId = m.groupValues[1].toLongOrNull() ?: return null
                return MediaSource.Bilibili(url = normalized, seasonId = seasonId)
            }
            return null
        }

        if (segments.getOrNull(0) != "video") return null
        val id = segments.getOrNull(1) ?: return null
        val part = partQueryParam(parsed.uri.rawQuery)?.toIntOrNull()

        if (BVID_RE.matches(id)) return MediaSource.Bilibili(url = normalized, bvid = id, part = part)
        AVID_RE.matchEntire(id)?.let { m ->
            val avid = m.groupValues[1].toLongOrNull() ?: return null
            return MediaSource.Bilibili(url = normalized, avid = avid, part = part)
        }
        return null
    }

    /** Returns the value of the `p` (part index) query parameter in [rawQuery], or null when absent. */
    private fun partQueryParam(rawQuery: String?): String? =
        rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) null else pair.substring(0, idx).takeIf { it == "p" }?.let { pair.substring(idx + 1) }
        }
}
