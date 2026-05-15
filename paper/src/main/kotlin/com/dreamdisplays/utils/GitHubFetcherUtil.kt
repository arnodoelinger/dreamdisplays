package com.dreamdisplays.utils

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import me.inotsleep.utils.logging.LoggingManager.error
import me.inotsleep.utils.logging.LoggingManager.warn
import org.jspecify.annotations.NullMarked
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpClient.newHttpClient
import java.net.http.HttpRequest.newBuilder
import java.net.http.HttpResponse.BodyHandlers.ofString
import java.time.Duration

@NullMarked
object GitHubFetcherUtil {
    private val gson = Gson()
    private val client: HttpClient = newHttpClient()

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
            error("Failed to connect to GitHub API: ${e.message}")
            throw e
        }

        if (response.statusCode() != 200) {
            val errorMsg = when (response.statusCode()) {
                403 -> "GitHub API rate limit exceeded or access forbidden"
                500, 502, 503 -> "GitHub servers are experiencing issues"
                else -> "Unexpected error"
            }
            error("GitHub API returned status ${response.statusCode()}: $errorMsg")
            warn("Response body: ${response.body().take(200)}")
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
