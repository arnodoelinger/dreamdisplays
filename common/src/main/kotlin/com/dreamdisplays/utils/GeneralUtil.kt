package com.dreamdisplays.utils

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.*

/** General utility functions for the mod. */
object GeneralUtil {
    private val DIRECT_ID = Regex("[a-zA-Z0-9_-]{11}")

    /** Extracts the 11-character YouTube video ID from a URL or returns null if the input is not recognisable. */
    fun extractVideoId(youtubeUrl: String): String? {
        if (youtubeUrl.isEmpty()) return null

        try {
            val uri = URI(youtubeUrl)
            val host = uri.host

            if (host != null && "youtu.be" in host) {
                val path = uri.path
                if (path != null && path.length > 1) {
                    val id = path.substring(1).split('?', '#').first()
                    return id.ifEmpty { null }
                }
            }

            if (host != null && "youtube.com" in host) {
                uri.query?.split('&')?.forEach { param ->
                    val pair = param.split('=', limit = 2)
                    if (pair.size == 2 && pair[0] == "v") return pair[1]
                }

                val path = uri.path
                if (path != null && ("shorts" in path || "/live/" in path)) {
                    path.split('/').forEach { segment ->
                        if (segment.isNotEmpty() && segment != "shorts" && segment != "live") {
                            val id = segment.split('?', '#').first()
                            return id.ifEmpty { null }
                        }
                    }
                }
            }
        } catch (_: URISyntaxException) {
        }

        return DIRECT_ID.find(youtubeUrl)?.value
    }

    /** Reads the classpath resource at [resourcePath] and returns its content as a UTF-8 string. */
    @Throws(IOException::class)
    fun readResource(resourcePath: String): String {
        val stream = GeneralUtil::class.java.getResourceAsStream(resourcePath)
            ?: throw IOException("Can't find the resource: $resourcePath.")
        return stream.bufferedReader().use { it.readText() }
    }

    /** Returns the mod version string from `version.txt`, or "unknown" if the resource is missing. */
    fun getModVersion(): String =
        runCatching { readResource("/assets/dreamdisplays/version.txt").trim() }
            .getOrDefault("unknown")
}
