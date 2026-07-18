package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.api.media.MediaServices
import com.dreamdisplays.api.media.search.MediaSearchPage
import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.api.media.search.YouTubeUrls
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.twitch.TwitchApi
import com.dreamdisplays.media.source.twitch.TwitchMetadata
import com.dreamdisplays.media.source.twitch.TwitchMetadataCache
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.render.Thumbnails
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
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

    /** How to continue the current result list when [loadMoreIfNeeded] fires; null when the current
     *  list isn't paginable (single-video / Twitch-only results). */
    private var moreMode: MoreMode? = null

    /** Continuation token for the next page, or null when the list is exhausted (or not yet loaded). */
    private var continuationToken: String? = null

    /** Guards against firing a second page-2 request while one is already in flight. */
    private var loadingMore = false

    /** Reload hook the panel uses to reset scroll when new results land. */
    var onResults: () -> Unit = {}

    /** True while the status line is the animated loading message. */
    val isLoading: Boolean get() = statusKey == KEY_LOADING

    /** True while a background "load more" request is in flight (distinct from the initial [isLoading]). */
    val isLoadingMore: Boolean get() = loadingMore

    /** Distinguishes what a follow-up "load more" page should continue: a text search or a related-video list. */
    private sealed class MoreMode {
        data class Search(val query: String) : MoreMode()
        data class Related(val videoId: String) : MoreMode()
    }

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

        startLoad()

        val seq = requestSeq.incrementAndGet()
        val svc = DreamServices.registry.get(MediaServices.SEARCH)
        val maybeId = YouTubeUrls.extractVideoId(q)
        val twitchSource = MediaSource.from(q) as? MediaSource.Twitch

        launchLoad {
            val results = runCatching {
                when {
                    maybeId != null -> {
                        val meta = runCatching { svc.metadata(maybeId) }
                            .onFailure { if (it is CancellationException) throw it; logger.warn("URL meta: ${it.message}") }
                            .getOrNull()
                        listOf(meta ?: fallbackResult(maybeId))
                    }

                    twitchSource != null -> {
                        val meta = runCatching {
                            withContext(Dispatchers.IO) { TwitchMetadataCache.resolveBlocking(twitchSource) }
                        }
                            .onFailure { if (it is CancellationException) throw it; logger.warn("Twitch meta: ${it.message}") }
                            .getOrNull()
                        listOf(twitchResult(twitchSource, meta))
                    }

                    else -> {
                        val twitchLogin = twitchLoginCandidate(q)

                        val ytDeferred = async {
                            runCatching { svc.search(q, RESULT_LIMIT) }
                                .onFailure { if (it is CancellationException) throw it; logger.warn("Search failed: ${it.message}") }
                                .getOrNull()
                        }

                        val twitchDeferred = async {
                            twitchLogin?.let {
                                runCatching { liveTwitchResult(it) }.getOrNull()
                            }
                        }

                        val youtubeResults = ytDeferred.await()
                        var liveTwitch = twitchDeferred.await()

                        if (liveTwitch == null) {
                            val fuzzyLogin = fuzzyTwitchLogin(q, youtubeResults)
                            if (fuzzyLogin != null && fuzzyLogin != twitchLogin) {
                                liveTwitch = runCatching { liveTwitchResult(fuzzyLogin) }.getOrNull()
                            }
                        }

                        if (youtubeResults == null && liveTwitch == null) {
                            publish(seq, null, KEY_ERROR)
                            return@launchLoad
                        }

                        ArrayList<MediaSearchResult>(1 + (youtubeResults?.size ?: 0)).apply {
                            liveTwitch?.let(::add)
                            youtubeResults?.let(::addAll)
                        }
                    }
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                publish(seq, null, KEY_ERROR)
            }.getOrNull()

            results?.let { publish(seq, it, null) }
        }
    }

    /** Returns [query] lowercased when it looks like a Twitch login (letters/digits/underscore, 3-25 chars). */
    private fun twitchLoginCandidate(query: String): String? {
        val q = query.trim()
        return if (TWITCH_LOGIN_RE.matches(q)) q.lowercase() else null
    }

    /** Looks up [login] on Twitch and, if it's live, builds the card shown ahead of the YouTube results. */
    private fun liveTwitchResult(login: String): MediaSearchResult? = runCatching {
        TwitchApi.queryChannel(login)?.takeIf { it.isLive }?.let { meta ->
            twitchResult(MediaSource.Twitch(url = "https://www.twitch.tv/$login", channel = login), meta)
        }
    }.onFailure { e ->
        logger.debug("Twitch live-channel lookup failed for '$login': ${e.message}.")
    }.getOrNull()

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
            runCatching {
                DreamServices.registry.get(MediaServices.SEARCH).related(videoId, RESULT_LIMIT)
            }.onSuccess { results ->
                publish(seq, results, null)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.warn("Related failed $videoId: ${e.message}")
                publish(seq, null, KEY_ERROR)
            }
        }
    }

    /** Launch load. */
    private fun launchLoad(block: suspend CoroutineScope.() -> Unit) =
        DreamCoroutines.clientIo.launch(block = block)

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
