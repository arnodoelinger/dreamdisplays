package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.api.runtime.get
import com.dreamdisplays.api.media.MediaServices
import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.api.media.search.YouTubeUrls
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.twitch.TwitchApi
import com.dreamdisplays.media.source.twitch.TwitchMetadata
import com.dreamdisplays.media.source.twitch.TwitchMetadataCache
import com.dreamdisplays.platform.client.render.Thumbnails
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import kotlinx.atomicfu.atomic
import kotlin.math.min

/**
 * Async state machine behind the suggestions panel: runs searches and related-video lookups on a
 * background coroutine, publishes results back on the client thread, and drops stale responses via a
 * request sequence number. Holds no rendering state, so the panel widget stays a pure view.
 */
class SuggestionsController {
    /** Current result cards, mutated only on the client thread. */
    val cards = ArrayList<MediaSearchResult>()

    /** Translation key of the current status line (loading/empty/error), or null when results are shown. */
    var statusKey: String? = null; private set

    /** Wall-clock start of the in-flight load, for the elapsed-seconds suffix on the loading message. */
    var loadStartedAtMs: Long = 0L; private set

    private val requestSeq = atomic(0)
    private var currentVideoId: String? = null

    /** Reload hook the panel uses to reset scroll when new results land. */
    var onResults: () -> Unit = {}

    /** True while the status line is the animated loading message. */
    val isLoading: Boolean get() = statusKey == KEY_LOADING

    /** Shows videos related to [videoId]; clears the panel when null/empty; no-op if already shown. */
    fun setRelatedTo(videoId: String?) {
        if (videoId.isNullOrEmpty()) {
            currentVideoId = null
            cards.clear()
            statusKey = null
            return
        }
        if (videoId == currentVideoId && cards.isNotEmpty()) return
        currentVideoId = videoId
        loadRelated(videoId)
    }

    /**
     * Runs a free-text or URL search for [query]; an empty query falls back to the current related
     * list. URL queries resolve metadata for the single referenced video.
     */
    fun runSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            currentVideoId?.let { loadRelated(it) }
            return
        }
        val svc = DreamServices.registry.get(MediaServices.SEARCH)
        val maybeId = YouTubeUrls.extractVideoId(q)
        if (maybeId != null) {
            startLoad()
            val seq = requestSeq.incrementAndGet()
            launchLoad {
                try {
                    val meta = svc.metadata(maybeId)
                    publish(seq, listOf(meta ?: fallbackResult(maybeId)), null)
                } catch (e: Exception) {
                    logger.warn("URL meta fetch failed: ${e.message}")
                    publish(seq, listOf(fallbackResult(maybeId)), null)
                }
            }
            return
        }
        val twitchSource = MediaSource.from(q) as? MediaSource.Twitch
        if (twitchSource != null) {
            startLoad()
            val seq = requestSeq.incrementAndGet()
            launchLoad {
                try {
                    publish(seq, listOf(twitchResult(twitchSource, TwitchMetadataCache.resolveBlocking(twitchSource))), null)
                } catch (e: Exception) {
                    logger.warn("Twitch meta fetch failed: ${e.message}.")
                    publish(seq, listOf(twitchResult(twitchSource, null)), null)
                }
            }
            return
        }
        startLoad()
        val seq = requestSeq.incrementAndGet()
        val twitchLogin = twitchLoginCandidate(q)
        launchLoad {
            val youtubeResults = try {
                svc.search(q, RESULT_LIMIT)
            } catch (e: Exception) {
                logger.warn("Search failed '$q': ${e.message}")
                null
            }
            // The query itself might be a channel's handle (e.g. searching a streamer's name): if that
            // channel is live right now, it's almost certainly what the user is after, so it's shown
            // ahead of the YouTube results rather than mixed in by relevance.
            var liveTwitch = twitchLogin?.let(::liveTwitchResult)
            if (liveTwitch == null) {
                // Twitch's public channel lookup only matches an exact login, so a typo'd or truncated
                // query ("shroud" -> "shrou") would otherwise find nothing. YouTube's own (fuzzy) search
                // has already done the hard work of guessing the intended creator, so the uploader names
                // it returned are used as the candidate pool for an edit-distance match against the query.
                val fuzzyLogin = fuzzyTwitchLogin(q, youtubeResults)
                if (fuzzyLogin != null && fuzzyLogin != twitchLogin) {
                    liveTwitch = liveTwitchResult(fuzzyLogin)
                }
            }
            if (youtubeResults == null && liveTwitch == null) {
                publish(seq, null, KEY_ERROR)
                return@launchLoad
            }
            val merged = ArrayList<MediaSearchResult>(1 + (youtubeResults?.size ?: 0))
            liveTwitch?.let(merged::add)
            youtubeResults?.let(merged::addAll)
            publish(seq, merged, null)
        }
    }

    /** Returns [query] lowercased when it looks like a Twitch login (letters/digits/underscore, 3-25 chars). */
    private fun twitchLoginCandidate(query: String): String? {
        val q = query.trim()
        return if (TWITCH_LOGIN_RE.matches(q)) q.lowercase() else null
    }

    /** Looks up [login] on Twitch and, if it's live, builds the card shown ahead of the YouTube results. */
    private fun liveTwitchResult(login: String): MediaSearchResult? = try {
        TwitchApi.queryChannel(login)?.takeIf { it.isLive }?.let { meta ->
            twitchResult(MediaSource.Twitch(url = "https://www.twitch.tv/$login", channel = login), meta)
        }
    } catch (e: Exception) {
        logger.debug("Twitch live-channel lookup failed for '$login': ${e.message}.")
        null
    }

    /**
     * Picks the uploader (from [youtubeResults]) whose login shape is closest to [query] by edit
     * distance, within the tolerance [fuzzyThreshold] allows for its length, e.g. so "shrou" or
     * "shroug" still matches an uploader named "shroud". Returns null when no uploader is close enough.
     */
    private fun fuzzyTwitchLogin(query: String, youtubeResults: List<MediaSearchResult>?): String? {
        if (youtubeResults.isNullOrEmpty()) return null
        val qShape = toLoginShape(query)
        if (qShape.length < 3) return null
        val threshold = fuzzyThreshold(qShape.length)
        val seen = HashSet<String>()
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        for (result in youtubeResults) {
            val candidate = result.uploader?.let(::toLoginShape) ?: continue
            if (candidate.length < 3 || !seen.add(candidate)) continue
            val dist = levenshtein(qShape, candidate)
            if (dist <= threshold && dist < bestDist) {
                bestDist = dist
                best = candidate
            }
            if (seen.size >= FUZZY_CANDIDATE_LIMIT) break
        }
        return best
    }

    /** Normalizes free text to a Twitch login's character set: lowercase letters, digits, underscore. */
    private fun toLoginShape(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(25)

    /**
     * Elasticsearch-style "AUTO" fuzziness: how many single-character edits still count as the same
     * word, scaled by its length (short strings tolerate none, longer ones tolerate more).
     */
    private fun fuzzyThreshold(length: Int): Int = when {
        length <= 2 -> 0
        length <= 5 -> 1
        else -> 2
    }

    /** Levenshtein edit distance between [a] and [b] (Wagner-Fischer dynamic programming, single-row). */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** Loads the related-videos list for [videoId] in the background. */
    private fun loadRelated(videoId: String) {
        startLoad()
        val seq = requestSeq.incrementAndGet()
        launchLoad {
            try {
                publish(seq, DreamServices.registry.get(MediaServices.SEARCH).related(videoId, RESULT_LIMIT), null)
            } catch (e: Exception) {
                logger.warn("Related failed $videoId: ${e.message}")
                publish(seq, null, KEY_ERROR)
            }
        }
    }

    /** Launch load. */
    private fun launchLoad(block: () -> Unit) {
        DreamCoroutines.clientIo.launch { block() }
    }

    /** Switches the panel into the loading state and clears stale results. */
    private fun startLoad() {
        statusKey = KEY_LOADING
        loadStartedAtMs = System.currentTimeMillis()
        cards.clear()
        onResults()
    }

    /** Applies a finished request on the client thread, ignoring it if a newer request superseded it. */
    private fun publish(seq: Int, results: List<MediaSearchResult>?, error: String?) {
        Minecraft.getInstance().execute {
            if (seq != requestSeq.value) return@execute
            cards.clear()
            onResults()
            if (error != null) {
                statusKey = error
                return@execute
            }
            if (results.isNullOrEmpty()) {
                statusKey = KEY_EMPTY
                return@execute
            }
            statusKey = null
            cards.addAll(results.subList(0, min(results.size, RESULT_LIMIT)))
            for (info in cards) {
                val thumbUrl = info.thumbnailUrlOverride
                if (thumbUrl != null) Thumbnails.request(info.id, thumbUrl)
                else Thumbnails.request(info.id, Thumbnails.Quality.LOW)
            }
        }
    }

    /** Minimal result used when URL metadata could not be fetched. */
    private fun fallbackResult(videoId: String) =
        MediaSearchResult(videoId, YouTubeUrls.watchUrl(videoId), null, null, null)

    /** Builds a single-card result for a pasted Twitch URL, using [meta] when the Helix lookup succeeded. */
    private fun twitchResult(source: MediaSource.Twitch, meta: TwitchMetadata?): MediaSearchResult {
        val id = TwitchMetadataCache.cacheKey(source) ?: source.url
        val fallbackTitle = source.channel ?: source.videoId ?: source.clipSlug ?: source.url
        return MediaSearchResult(
            id = id,
            title = meta?.title ?: fallbackTitle,
            uploader = meta?.channelName,
            durationSec = null,
            viewCount = meta?.viewCount,
            watchUrlOverride = source.url,
            thumbnailUrlOverride = meta?.thumbnailUrl,
            isTwitch = true,
        )
    }

    companion object {
        /** Maximum number of results to show in the panel. */
        const val RESULT_LIMIT = 72

        /** Translation kes for the status line. */
        private const val KEY_LOADING = "dreamdisplays.suggestions.loading"

        /** Translation key for the error status line. */
        private const val KEY_ERROR = "dreamdisplays.suggestions.error"

        /** Translation key for the empty status line. */
        private const val KEY_EMPTY = "dreamdisplays.suggestions.empty"

        /** Logger. */
        private val logger = LoggerFactory.getLogger("DreamDisplays/Suggestions")

        /** Twitch login shape: letters, digits, underscore, 3-25 chars (real handles never contain spaces). */
        private val TWITCH_LOGIN_RE = Regex("^[A-Za-z0-9_]{3,25}$")

        /** Max distinct uploader names scanned for a fuzzy Twitch-login match, so a big result page stays cheap. */
        private const val FUZZY_CANDIDATE_LIMIT = 8
    }
}
