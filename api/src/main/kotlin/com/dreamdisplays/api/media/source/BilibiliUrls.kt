package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.Experimental
import java.util.*

/**
 * Recognizes and dissects Bilibili URLs (VODs, bangumi episodes / seasons, live rooms, `b23.tv` short links).
 *
 * @since 1.9.x
 */
@Experimental
object BilibiliUrls {
    private val BVID_RE = Regex("^BV[0-9A-Za-z]{10}$")
    private val AVID_RE = Regex("^[aA][vV](\\d+)$")
    private val EP_ID_RE = Regex("^[eE][pP](\\d+)$")
    private val SEASON_ID_RE = Regex("^[sS][sS](\\d+)$")

    /** True when [url] points at a BIlibili VOD, live room, or short link. */
    fun isBilibili(url: String): Boolean = parse(url) != null

    /** Parses [url] into a Bilibili, or `null` if not recognizable. */
    fun parse(url: String): MediaSource.Bilibili? {
        val parsed = MediaHttpUrl.parse(url) ?: MediaHttpUrl.parse("https://${url.trim()}") ?: return null
        val host = parsed.uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.removePrefix("m.") ?: return null

        val normalized = parsed.value
        val segments = parsed.uri.path?.split('/')?.filter { it.isNotBlank() } ?: []

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

    private fun partQueryParam(rawQuery: String?): String? =
        rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) null else pair.substring(0, idx).takeIf { it == "p" }?.let { pair.substring(idx + 1) }
        }
}
