package com.dreamdisplays.server.utils

import io.github.arsmotorin.ofrat.PaperOnly

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpClient.newHttpClient
import java.net.http.HttpRequest.newBuilder
import java.net.http.HttpResponse.BodyHandlers.ofString
import java.time.Duration

/**
 * GitHub version fetcher. Uses the GitHub API to fetch the latest release of the mod and plugin.
 */
@PaperOnly @NullMarked object GitHubFetcherUtil {
    private val logger = LoggerFactory.getLogger("DreamDisplays/GitHubFetcher")
    private val gson = Gson()
    private val client: HttpClient = newHttpClient()

    /**
     * Fetches the releases of `owner/repo` from GitHub. Returns an empty list on non-200
     * responses (logged) but rethrows connection errors so callers can distinguish outages.
     */
    @Throws(Exception::class)
    fun fetchReleases(owner: String, repo: String): List<Release> {
        val url = "https://api.github.com/repos/$owner/$repo/releases"

        val request = newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "DreamDisplays-Updater")
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = try {
            client.send(request, ofString())
        } catch (e: Exception) {
            logger.error("Failed to connect to GitHub API: ${e.message}")
            throw e
        }

        if (response.statusCode() != 200) {
            val errorMsg = when (response.statusCode()) {
                403 -> "GitHub API rate limit exceeded or access forbidden"
                500, 502, 503 -> "GitHub servers are experiencing issues"
                else -> "Unexpected error"
            }
            logger.error("GitHub API returned status ${response.statusCode()}: $errorMsg")
            logger.warn("Response body: ${response.body().take(200)}")
            return emptyList()
        }

        return gson.fromJson(
            response.body(),
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
