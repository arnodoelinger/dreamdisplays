package com.dreamdisplays.platform.server.utils

import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.util.net.DreamHttpClient
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory

/**
 * GitHub version fetcher. Uses the GitHub API to fetch the latest release of the mod and plugin.
 */
@PaperOnly
@NullMarked
object GitHubFetcherUtil {
    private val logger = LoggerFactory.getLogger("DreamDisplays/GitHubFetcher")
    private val gson = Gson()

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

        return gson.fromJson(
            response.bodyString(),
            object : TypeToken<List<Release>>() {}.type
        ) ?: emptyList()
    }

    data class Release(
        @field:SerializedName("tag_name") val tagName: String,
        @field:SerializedName("name") val name: String,
        @field:SerializedName("html_url") val htmlUrl: String,
        @field:SerializedName("published_at") val publishedAt: String,
    )
}
