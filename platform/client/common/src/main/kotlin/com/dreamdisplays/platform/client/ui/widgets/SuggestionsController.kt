package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.api.media.MediaServices
import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.api.media.search.YouTubeUrls
import com.dreamdisplays.api.media.source.CustomMediaUrls
import com.dreamdisplays.api.media.source.KickUrls
import com.dreamdisplays.api.media.source.MediaPlatform
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.kick.KickApi
import com.dreamdisplays.media.source.kick.KickMetadataCache
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import com.dreamdisplays.media.source.twitch.TwitchApi
import com.dreamdisplays.media.source.twitch.TwitchMetadata
import com.dreamdisplays.media.source.twitch.TwitchMetadataCache
import com.dreamdisplays.media.source.vimeo.VimeoMetadataCache
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.render.Thumbnails
import com.dreamdisplays.platform.client.storage.CustomVideoStore
import com.dreamdisplays.platform.client.storage.WatchedVideoStore
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Async state machine behind the suggestions panel: runs searches and related-video lookups on a
 * background coroutine, publishes results back on the client thread, and drops stale responses via a
 * request sequence number. Holds no rendering state, so the panel widget stays a pure view.
 */
class SuggestionsController {
    /** Current result cards, mutated only on the client thread. */
    val cards = ArrayList<MediaSearchResult>()

    /**
     * [cards] after applying [sortOption]'s client-side effect: [SortOption.POPULARITY] / [SortOption.NEWEST]
     * re-sort (a no-op when the network already sorted them, a genuine sort as a related-videos
     * fallback, since the `next` endpoint has no server-side sort), [SortOption.STREAMS] filters to
     * live results, and [SortOption.UNWATCHED] / [SortOption.WATCHED] filter by [WatchedVideoStore] —
     * a pure function of already-loaded data, so it needs no network round-trip.
     *
     * [SortOption.MY_LINKS] is the exception: it ignores [cards] and shows the player's own custom
     * links from [CustomVideoStore], which are local and need no request at all.
     */
    val visibleCards: List<MediaSearchResult>
        get() = when (sortOption) {
            SortOption.RELEVANCE -> cards
            SortOption.POPULARITY -> cards.sortedByDescending { it.viewCount ?: -1L }
            SortOption.NEWEST -> cards.sortedBy { it.publishedDaysAgo ?: Int.MAX_VALUE }
            SortOption.STREAMS -> cards.filter { it.isLive }
            SortOption.UNWATCHED -> cards.filterNot { WatchedVideoStore.isWatched(it.id) }
            SortOption.WATCHED -> cards.filter { WatchedVideoStore.isWatched(it.id) }
            SortOption.MY_LINKS -> CustomVideoStore.asResults()
        }

    /**
     * Translation key of the current status line (loading/empty/error), or null when results are
     * shown. While [SortOption.MY_LINKS] is active it reports on the local link list instead of the
     * in-flight request, so an unrelated search still loading never hides the remembered links.
     */
    val statusKey: String?
        get() = if (sortOption.isOwnList) {
            if (CustomVideoStore.isEmpty()) KEY_NO_LINKS else null
        } else {
            loadStatusKey
        }

    /** Status of the current network load, independent of which list is on screen. */
    private var loadStatusKey: String? = null

    /** Wall-clock start of the in-flight load, for the elapsed-seconds suffix on the loading message. */
    var loadStartedAtMs: Long = 0L; private set

    /** Currently selected sort/filter; see [setSort]. */
    var sortOption: SortOption = SortOption.RELEVANCE; private set

    private val requestSeq = atomic(0)
    private var currentVideoId: String? = null

    /** The last non-empty text search query, so [setSort] knows what to re-run for a network sort change. */
    private var lastQuery: String? = null

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
    val isLoading: Boolean get() = loadStatusKey == KEY_LOADING

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
            lastQuery = null
            cards.clear()
            loadStatusKey = null
            return
        }
        if (videoId == currentVideoId && cards.isNotEmpty()) return
        currentVideoId = videoId
        lastQuery = null
        loadRelated(videoId)
    }

    /**
     * Changes the active sort / filter. [SortOption.UNWATCHED]/[SortOption.WATCHED] take effect purely
     * through [visibleCards] and need no network call. The other options carry their own `YouTube` sort
     * order, so if a text search is currently active it's re-run to fetch results in that order —
     * related-video lists (no active query) have no server-side sort, so [visibleCards] falls back to
     * a client-side re-sort of what's already loaded instead of re-fetching.
     */
    fun setSort(option: SortOption) {
        if (option == sortOption) return
        sortOption = option
        onResults()
        if (option.refetches) lastQuery?.let { runSearch(it) }
    }

    /**
     * Forgets a remembered custom link. Only meaningful while [SortOption.MY_LINKS] shows the local
     * list; a no-op for anything else, since only custom results back a forgettable entry. The list
     * is read live from [CustomVideoStore], so dropping it there is all the refresh the panel needs.
     */
    fun forgetCustom(result: MediaSearchResult): Boolean {
        if (!result.isCustom) return false
        CustomVideoStore.forget(result.getWatchUrl())
        onResults()
        return true
    }

    /**
     * Runs a free-text or URL search for [query]; an empty query falls back to the current related
     * list. URL queries resolve metadata for the single referenced video.
     *
     * A pasted link never means "search for this text": a YouTube or Twitch URL resolves to that
     * one video, and any other http(s) URL becomes a single custom-link card
     * ([customResult]) the player can click to play it directly.
     */
    fun runSearch(query: String) {
        val q = query.trim()

        if (q.isEmpty()) {
            lastQuery = null
            currentVideoId?.let { loadRelated(it) }
            return
        }

        // Pasting into the box is a request to play that link, so leaving a local-list filter
        // active would hide the result the player just asked for.
        if (sortOption.isOwnList) sortOption = SortOption.RELEVANCE

        lastQuery = q
        startLoad()

        val seq = requestSeq.incrementAndGet()
        val svc = DreamServices.registry.get(MediaServices.SEARCH)
        val maybeId = YouTubeUrls.extractVideoId(q)
        val source = MediaSource.from(q)

        // A direct / long-tail link needs no network to show its card, so publish it synchronously.
        // Platform links (Twitch / Vimeo / Kick) instead resolve real metadata below.
        if (maybeId == null && source !is MediaSource.Twitch &&
            source !is MediaSource.Vimeo && source !is MediaSource.Kick
        ) {
            customUrlOf(source)?.let {
                publish(seq, listOf(customResult(it)), null)
                return
            }
        }

        launchLoad {
            val results = runCatching {
                when {
                    maybeId != null -> {
                        val meta = runCatching { svc.metadata(maybeId) }
                            .onFailure { if (it is CancellationException) throw it; logger.warn("URL meta: ${it.message}") }
                            .getOrNull()
                        listOf(meta ?: fallbackResult(maybeId))
                    }

                    source is MediaSource.Twitch -> {
                        val meta = runCatching {
                            withContext(Dispatchers.IO) { TwitchMetadataCache.resolveBlocking(source) }
                        }
                            .onFailure { if (it is CancellationException) throw it; logger.warn("Twitch meta: ${it.message}") }
                            .getOrNull()
                        listOf(twitchResult(source, meta))
                    }

                    source is MediaSource.Vimeo -> {
                        val meta = runCatching {
                            withContext(Dispatchers.IO) { VimeoMetadataCache.resolveBlocking(source) }
                        }
                            .onFailure { if (it is CancellationException) throw it; logger.warn("Vimeo meta: ${it.message}") }
                            .getOrNull()
                        listOf(platformResult(source.url, MediaPlatform.VIMEO, meta, fallbackTitle = "Vimeo ${source.videoId}"))
                    }

                    source is MediaSource.Kick -> {
                        val meta = runCatching {
                            withContext(Dispatchers.IO) { KickMetadataCache.resolveBlocking(source) }
                        }
                            .onFailure { if (it is CancellationException) throw it; logger.warn("Kick meta: ${it.message}") }
                            .getOrNull()
                        val fallback = source.channel ?: source.videoUuid ?: "Kick"
                        listOf(platformResult(source.url, MediaPlatform.KICK, meta, fallbackTitle = fallback))
                    }

                    else -> {
                        val twitchLogin = twitchLoginCandidate(q)
                        val kickSlug = KickUrls.channelSlugCandidate(q)

                        val ytDeferred = async {
                            runCatching { svc.searchPage(q, PAGE_SIZE, sortOption.networkSort) }
                                .onFailure { if (it is CancellationException) throw it; logger.warn("Search failed: ${it.message}") }
                                .getOrNull()
                        }

                        val twitchDeferred = async {
                            twitchLogin?.let { runCatching { liveTwitchResult(it) }.getOrNull() }
                        }
                        val kickDeferred = async {
                            kickSlug?.let { runCatching { liveKickResult(it) }.getOrNull() }
                        }

                        val youtubePage = ytDeferred.await()
                        val youtubeResults = youtubePage?.results
                        var liveTwitch = twitchDeferred.await()
                        val liveKick = kickDeferred.await()

                        if (liveTwitch == null) {
                            val fuzzyLogin = fuzzyTwitchLogin(q, youtubeResults)
                            if (fuzzyLogin != null && fuzzyLogin != twitchLogin) {
                                liveTwitch = runCatching { liveTwitchResult(fuzzyLogin) }.getOrNull()
                            }
                        }

                        if (youtubeResults == null && liveTwitch == null && liveKick == null) {
                            publish(seq, null, KEY_ERROR)
                            return@launchLoad
                        }

                        // Live channels lead the list, ahead of the on-demand YouTube results
                        val combined = ArrayList<MediaSearchResult>(2 + (youtubeResults?.size ?: 0)).apply {
                            liveTwitch?.let(::add)
                            liveKick?.let(::add)
                            youtubeResults?.let(::addAll)
                        }
                        publish(seq, combined, null, nextToken = youtubePage?.continuationToken, mode = MoreMode.Search(q))
                        return@launchLoad
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

    /** Looks up [slug] on Kick and, if it's live, builds the card shown ahead of the YouTube results. */
    private fun liveKickResult(slug: String): MediaSearchResult? = runCatching {
        val playback = KickApi.resolveLive(slug)?.takeIf { it.metadata.isLive } ?: return@runCatching null
        val source = MediaSource.Kick(url = "https://kick.com/$slug", channel = slug)
        KickMetadataCache.put(source, playback.metadata)
        platformResult(source.url, MediaPlatform.KICK, playback.metadata, fallbackTitle = slug)
    }.onFailure { e ->
        logger.debug("Kick live-channel lookup failed for '$slug': ${e.message}.")
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
                DreamServices.registry.get(MediaServices.SEARCH).relatedPage(videoId, PAGE_SIZE)
            }.onSuccess { page ->
                publish(seq, page.results, null, nextToken = page.continuationToken, mode = MoreMode.Related(videoId))
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.warn("Related failed $videoId: ${e.message}")
                publish(seq, null, KEY_ERROR)
            }
        }
    }

    /**
     * Appends the next page of results if the current list came from a paginable search / related load,
     * a page isn't already in flight, and the list isn't already exhausted. Called by the panel as the
     * user scrolls near the end of the currently loaded cards; safe to call every frame.
     */
    fun loadMoreIfNeeded() {
        if (loadingMore || isLoading) return
        // The local link list is complete by definition; there is no page two to fetch
        if (sortOption.isOwnList) return
        val mode = moreMode ?: return
        val token = continuationToken ?: return
        loadingMore = true
        val seq = requestSeq.value
        launchLoad {
            val page = runCatching {
                val svc = DreamServices.registry.get(MediaServices.SEARCH)
                when (mode) {
                    is MoreMode.Search -> svc.searchMore(token, PAGE_SIZE)
                    is MoreMode.Related -> svc.relatedMore(token, PAGE_SIZE)
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.warn("Load-more failed: ${e.message}")
            }.getOrNull()

            Minecraft.getInstance().execute {
                loadingMore = false
                if (seq != requestSeq.value || page == null) return@execute
                continuationToken = page.continuationToken
                appendCards(page.results)
            }
        }
    }

    /** Launch load. */
    private fun launchLoad(block: suspend CoroutineScope.() -> Unit) =
        DreamCoroutines.clientIo.launch(block = block)

    /** Switches the panel into the loading state and clears stale results. */
    private fun startLoad() {
        loadStatusKey = KEY_LOADING
        loadStartedAtMs = System.currentTimeMillis()
        cards.clear()
        moreMode = null
        continuationToken = null
        onResults()
    }

    /**
     * Applies a finished request on the client thread, ignoring it if a newer request superseded it.
     * [nextToken]/[mode] are set for paginable loads (plain search / related-videos); left null for
     * single-video or Twitch-only results, which have nothing more to page through.
     */
    private fun publish(
        seq: Int, results: List<MediaSearchResult>?, error: String?,
        nextToken: String? = null, mode: MoreMode? = null,
    ) {
        Minecraft.getInstance().execute {
            if (seq != requestSeq.value) return@execute
            cards.clear()
            moreMode = mode
            continuationToken = nextToken
            onResults()
            if (error != null) {
                loadStatusKey = error
                return@execute
            }
            if (results.isNullOrEmpty()) {
                loadStatusKey = KEY_EMPTY
                return@execute
            }
            loadStatusKey = null
            appendCards(results)
        }
    }

    /**
     * Adds [results] to [cards] (skipping ids already shown — search / related continuation pages can
     * overlap the previous page when the underlying list shifts between requests) and kicks off their
     * thumbnail downloads; must run on the client thread.
     */
    private fun appendCards(results: List<MediaSearchResult>) {
        val startIndex = cards.size
        val seen = HashSet<String>(cards.size).apply { cards.mapTo(this) { it.id } }
        for (info in results) if (seen.add(info.id)) cards.add(info)
        for (i in startIndex until cards.size) {
            val info = cards[i]
            val thumbUrl = info.thumbnailUrlOverride
            when {
                // A platform result (Twitch / Vimeo / Kick) carries its thumbnail URL directly
                thumbUrl != null -> Thumbnails.request(info.id, thumbUrl)
                // Only real YouTube ids derive a thumbnail; a custom link or a metadata-less platform
                // card has none, and deriving a ytimg URL from its URL-shaped id would fetch nonsense.
                info.isYouTubeResult -> Thumbnails.request(info.id, Thumbnails.Quality.LOW)
            }
        }
    }

    /**
     * The playable URL behind [source] when it is a custom link (a direct media file, or any other
     * remote page the extractor chain may know), or null when it is not a URL at all - which is how
     * a plain search phrase stays a search phrase.
     */
    private fun customUrlOf(source: MediaSource): String? = when (source) {
        is MediaSource.DirectStream -> source.streamUrl
        is MediaSource.Remote -> CustomMediaUrls.normalize(source.url)
        else -> null
    }

    /**
     * The single card shown for a pasted link. Built purely from the URL - file name as the title,
     * host as the uploader - so the card appears the instant the player hits Enter; whether the
     * link actually plays is settled by the resolver, which reports failures through the menu's
     * error panel rather than an empty result list.
     */
    private fun customResult(url: String): MediaSearchResult = MediaSearchResult(
        id = url,
        title = CustomMediaUrls.displayName(url),
        uploader = CustomMediaUrls.hostOf(url),
        durationSec = null,
        viewCount = null,
        watchUrlOverride = url,
        isCustom = true,
    )

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
            isLive = meta?.isLive ?: false,
            platform = MediaPlatform.TWITCH,
        )
    }

    /**
     * Builds a single-card result for a pasted Vimeo / Kick link. The card is keyed by the watch URL
     * (unlike Twitch, whose id is a cache key) so its thumbnail slot never collides with a YouTube id;
     * [meta] fills in the title / uploader / thumbnail when the metadata lookup succeeded.
     */
    private fun platformResult(
        url: String,
        platform: MediaPlatform,
        meta: PlatformVideoMetadata?,
        fallbackTitle: String,
    ): MediaSearchResult = MediaSearchResult(
        id = url,
        title = meta?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
        uploader = meta?.uploader,
        durationSec = meta?.durationSec,
        viewCount = meta?.viewCount,
        watchUrlOverride = url,
        thumbnailUrlOverride = meta?.thumbnailUrl,
        isLive = meta?.isLive ?: false,
        platform = platform,
    )

    companion object {
        /** Results fetched per page; the panel loads another page as the user scrolls near the end. */
        const val PAGE_SIZE = 15

        /** Translation kes for the status line. */
        private const val KEY_LOADING = "dreamdisplays.suggestions.loading"

        /** Translation key for the error status line. */
        private const val KEY_ERROR = "dreamdisplays.suggestions.error"

        /** Translation key for the empty status line. */
        private const val KEY_EMPTY = "dreamdisplays.suggestions.empty"

        /** Translation key shown when the player has not saved any custom links yet. */
        private const val KEY_NO_LINKS = "dreamdisplays.suggestions.no_links"

        /** Logger. */
        private val logger = LoggerFactory.getLogger("DreamDisplays/Suggestions")

        /** Twitch login shape: letters, digits, underscore, 3-25 chars (real handles never contain spaces). */
        private val TWITCH_LOGIN_RE = Regex("^[A-Za-z0-9_]{3,25}$")

        /** Max distinct uploader names scanned for a fuzzy Twitch-login match, so a big result page stays cheap. */
        private const val FUZZY_CANDIDATE_LIMIT = 8
    }
}
