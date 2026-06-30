package com.dreamdisplays.platform.server.utils

import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.util.asJsonArrayOrNull
import com.dreamdisplays.util.asJsonObjectOrNull
import com.dreamdisplays.util.net.DreamHttpClient
import com.dreamdisplays.util.optString
import com.dreamdisplays.util.json.DreamJson
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory

/**
 * GitHub version fetcher. Uses the GitHub API to fetch the latest release of the mod and plugin.
 */
@PaperOnly
@NullMarked
object GitHubFetcherUtil {
    private val logger = LoggerFactory.getLogger("DreamDisplays/GitHubFetcher")

    /**
     * Fetches the releases of `owner/repo` from GitHub. Returns an empty list on non-200
     * responses (logged) but rethrows connection errors so callers can distinguish outages.
     */
    @Throws(Exception::class)
    fun fetchReleases(owner: String, repo: String): List<Release> {
        val url = "https://api.github.com/repos/$owner/$repo/releases"

        val response = try {
            DreamHttpClient.execute(
                url,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf(
                        "Accept" to "application/vnd.github.v3+json",
                        "User-Agent" to "DreamDisplays-Updater",
                    ),
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 10_000,
                    callTimeoutMs = 10_000,
                ),
            )
        } catch (e: Exception) {
            logger.error("Failed to connect to GitHub API: ${e.message}")
            throw e
        }

        if (response.code != 200) {
            val errorMsg = when (response.code) {
                403 -> "GitHub API rate limit exceeded or access forbidden"
                500, 502, 503 -> "GitHub servers are experiencing issues"
                else -> "Unexpected error"
            }
            logger.error("GitHub API returned status ${response.code}: $errorMsg")
            logger.warn("Response body: ${response.bodyString().take(200)}")
            return emptyList()
        }

        return parseReleases(response.bodyString())
    }

    /** Parses only the GitHub release fields used by update checks; no generated serializer required. */
    private fun parseReleases(body: String): List<Release> =
        DreamJson.compact.parseToJsonElement(body)
            .asJsonArrayOrNull()
            ?.mapNotNull { element ->
                val obj = element.asJsonObjectOrNull() ?: return@mapNotNull null
                Release(
                    tagName = obj.optString("tag_name").orEmpty(),
                    name = obj.optString("name").orEmpty(),
                    htmlUrl = obj.optString("html_url").orEmpty(),
                    publishedAt = obj.optString("published_at").orEmpty(),
                )
            }
            ?.filter { it.tagName.isNotBlank() }
            ?: emptyList()

    data class Release(
        val tagName: String = "",
        val name: String = "",
        val htmlUrl: String = "",
        val publishedAt: String = "",
    )
}
