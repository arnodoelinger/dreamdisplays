package com.dreamdisplays.media.source.platform

import com.dreamdisplays.util.asJsonObjectOrNull
import com.dreamdisplays.util.json.DreamJson
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/** HTTP helpers shared by the platform APIs. */
object PlatformHttp {
    private val logger = LoggerFactory.getLogger(javaClass)

    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /**
     * Gets [url] with [headers] and parses a JSON object, or returns `null` (logged at debug)
     * on any failure.
     */
    fun getJson(url: String, headers: Map<String, List<String>>): JsonObject? = runCatching {
        val body = DreamHttpClient.readText(
            url,
            DreamHttpClient.RequestOptions(headers = headers, connectTimeoutMs = 8_000, readTimeoutMs = 8_000),
        )
        DreamJson.compact.parseToJsonElement(body).asJsonObjectOrNull()
    }.onFailure { logger.debug("Platform API fetch failed for {}: {}.", url, it.message) }.getOrNull()
}
