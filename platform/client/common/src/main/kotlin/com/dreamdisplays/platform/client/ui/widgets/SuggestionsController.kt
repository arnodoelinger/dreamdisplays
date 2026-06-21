package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.core.get
import com.dreamdisplays.media.api.MediaSearchResult
import com.dreamdisplays.media.api.MediaSearchService
import com.dreamdisplays.platform.client.render.Thumbnails
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Async state machine behind the suggestions panel: runs searches and related-video lookups on a
 * background executor, publishes results back on the client thread, and drops stale responses via a
 * request sequence number. Holds no rendering state, so the panel widget stays a pure view.
 */
class SuggestionsController {
    /** Current result cards, mutated only on the client thread. */
    val cards = ArrayList<MediaSearchResult>()

    /** Translation key of the current status line (loading/empty/error), or null when results are shown. */
    var statusKey: String? = null
        private set

    /** Wall-clock start of the in-flight load, for the elapsed-seconds suffix on the loading message. */
    var loadStartedAtMs: Long = 0L
        private set

    private val requestSeq = AtomicInteger()
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
        val svc = DreamServices.registry.get<MediaSearchService>()
        val maybeId = if (q.startsWith("http") || "youtube.com" in q || "youtu.be" in q)
            svc.extractVideoId(q) else null
        if (maybeId != null) {
            startLoad()
            val seq = requestSeq.incrementAndGet()
            EXECUTOR.submit {
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
        startLoad()
        val seq = requestSeq.incrementAndGet()
        EXECUTOR.submit {
            try {
                publish(seq, svc.search(q, RESULT_LIMIT), null)
            } catch (e: Exception) {
                logger.warn("Search failed '$q': ${e.message}")
                publish(seq, null, KEY_ERROR)
            }
        }
    }

    /** Loads the related-videos list for [videoId] in the background. */
    private fun loadRelated(videoId: String) {
        startLoad()
        val seq = requestSeq.incrementAndGet()
        EXECUTOR.submit {
            try {
                publish(seq, DreamServices.registry.get<MediaSearchService>().related(videoId, RESULT_LIMIT), null)
            } catch (e: Exception) {
                logger.warn("Related failed $videoId: ${e.message}")
                publish(seq, null, KEY_ERROR)
            }
        }
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
            if (seq != requestSeq.get()) return@execute
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
            for (info in cards) Thumbnails.request(info.id, info.getThumbnailUrl())
        }
    }

    /** Minimal result used when URL metadata could not be fetched. */
    private fun fallbackResult(videoId: String) =
        MediaSearchResult(videoId, "https://youtu.be/$videoId", null, null, null)

    companion object {
        const val RESULT_LIMIT = 72
        private const val KEY_LOADING = "dreamdisplays.suggestions.loading"
        private const val KEY_ERROR = "dreamdisplays.suggestions.error"
        private const val KEY_EMPTY = "dreamdisplays.suggestions.empty"

        private val logger = LoggerFactory.getLogger("DreamDisplays/Suggestions")
        private val EXECUTOR = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "DD-Suggestions").apply { isDaemon = true }
        }
    }
}
