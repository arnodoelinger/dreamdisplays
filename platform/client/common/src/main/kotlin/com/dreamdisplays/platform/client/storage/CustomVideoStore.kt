package com.dreamdisplays.platform.client.storage

import com.dreamdisplays.api.media.search.model.MediaSearchResult
import com.dreamdisplays.api.media.source.url.CustomMediaUrls
import com.dreamdisplays.api.media.source.model.CustomVideoRecord
import com.dreamdisplays.util.json.JsonFileStore
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory

/** The player's own list of custom links, most recent first. Pasted links persist across sessions. */
object CustomVideoStore {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    private const val FILE_NAME = "custom-videos.json"
    private const val SCHEMA_VERSION = 1
    private const val MAX_ENTRIES = 100

    private val jsonFiles = JsonFileStore()
    private val entriesSerializer = ListSerializer(CustomVideoRecord.serializer())

    /** Most-recent-first; the list is small, so a plain [ArrayList] kept in order is enough. */
    private val entries = ArrayList<CustomVideoRecord>()

    /** Loads the remembered links from disk into memory, replacing any current state. */
    fun load() {
        val loaded = jsonFiles.readVersioned(jsonFiles.file(FILE_NAME), entriesSerializer, SCHEMA_VERSION, logger)
            ?: return
        entries.clear()
        entries.addAll(loaded.sortedByDescending { it.lastUsedAtMs }.take(MAX_ENTRIES))
    }

    /** The remembered links, most recently used first. */
    fun all(): List<CustomVideoRecord> = entries.toList()

    /** True when nothing has been remembered yet, so the UI can show an explanatory empty state. */
    fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * Records [url] as used now, moving it to the front when it is already known and refreshing its
     * [CustomVideoRecord.title] from [title] (a later resolve usually knows a better name than the
     * first paste).
     */
    fun remember(url: String, title: String? = null) {
        val normalized = CustomMediaUrls.normalize(url) ?: return
        val existing = entries.firstOrNull { it.url == normalized }
        entries.remove(existing)
        entries.add(
            0,
            CustomVideoRecord(
                url = normalized,
                title = title?.takeIf { it.isNotBlank() }
                    ?: existing?.title
                    ?: CustomMediaUrls.displayName(normalized),
                lastUsedAtMs = System.currentTimeMillis(),
            ),
        )
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        save()
    }

    /** Forgets [url]; no-op when it was never remembered. */
    fun forget(url: String) {
        val normalized = CustomMediaUrls.normalize(url) ?: url
        if (entries.removeAll { it.url == normalized }) save()
    }

    /** Forgets every remembered link. */
    fun clear() {
        if (entries.isEmpty()) return
        entries.clear()
        save()
    }

    /** The remembered links as suggestion cards, ready for the panel to render. */
    fun asResults(): List<MediaSearchResult> = entries.map { entry ->
        MediaSearchResult(
            id = entry.url,
            title = entry.title,
            uploader = CustomMediaUrls.hostOf(entry.url),
            durationSec = null,
            viewCount = null,
            watchUrlOverride = entry.url,
            isCustom = true,
        )
    }

    /** Persists the in-memory list to disk. */
    private fun save() {
        if (!jsonFiles.ensureDir(logger)) return
        jsonFiles.writeVersioned(jsonFiles.file(FILE_NAME), entriesSerializer, entries.toList(), SCHEMA_VERSION, logger)
    }
}
