package com.dreamdisplays.platform.server.meta

import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.platform.server.Main.Companion.modVersion
import com.dreamdisplays.platform.server.Main.Companion.pluginLatestVersion
import com.dreamdisplays.platform.server.Server
import com.dreamdisplays.platform.server.utils.GitHubFetcherUtil
import com.dreamdisplays.util.net.DreamHttpClient
import org.semver4j.Semver
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Checks for updates of the `Paper` plugin and mod from GitHub releases.
 */
@PaperOnly
object Updater {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/Updater")

    /**
     * Fetches GitHub releases and stores the latest mod and plugin versions in `Main`.
     * Network errors are logged but never propagated, so a transient outage is harmless.
     */
    fun checkForUpdates(repoOwner: String, repoName: String) {
        try {
            val releases = GitHubFetcherUtil.fetchReleases(repoOwner, repoName)

            if (releases.isEmpty()) {
                logger.warn("No releases found on GitHub. This may be due to network issues or API problems.")
                return
            }

            val stableVersions = releases.mapNotNull { parseVersion(it.tagName) }

            modVersion = stableVersions.maxOrNull()

            pluginLatestVersion = releases
                .filter {
                    it.tagName.contains("spigot", ignoreCase = true) ||
                            it.tagName.contains("plugin", ignoreCase = true)
                }
                .mapNotNull { parseVersion(it.tagName)?.toString() }
                .maxOrNull() ?: modVersion?.toString()

        } catch (_: UnknownHostException) {
            logger.warn("Cannot reach GitHub (DNS resolution failed). It seems that your hosting environment cannot resolve GitHub's domain.")
        } catch (_: ConnectException) {
            logger.warn("Cannot connect to GitHub. It seems that your hosting environment is blocking connections or 443 port is closed.")
        } catch (_: SocketTimeoutException) {
            logger.warn("GitHub connection timed out. The GitHub API may be experiencing issues or your hosting environment has a very slow connection.")
        } catch (e: Exception) {
            logger.warn("Unable to load versions from GitHub: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Extracts a stable semver from a GitHub release tag; returns null for snapshots and unparseable tags. */
    private fun parseVersion(tag: String): Semver? =
        Semver.coerce(tag)?.takeIf { it.isStable() }
}

/**
 * `Fabric`-specific implementation of [Updater].
 */
@FabricOnly
object FabricUpdater {
    private val logger = LoggerFactory.getLogger("DreamDisplays/Updater")
    private val gson = Gson()

    /**
     * Fetches GitHub releases and stores the latest mod and plugin versions in `Main`.
     * Network errors are logged but never propagated, so a transient outage is harmless.
     */
    fun checkForUpdates(repoOwner: String, repoName: String) {
        try {
            val releases = fetchReleases(repoOwner, repoName)
            if (releases.isEmpty()) {
                logger.warn("No releases found on GitHub.")
                return
            }

            val stableVersions = releases.mapNotNull { parseVersion(it.tagName) }

            Server.modLatestVersion = stableVersions.maxOrNull()

            Server.pluginLatestVersion = releases
                .filter {
                    it.tagName.contains("spigot", ignoreCase = true) ||
                            it.tagName.contains("plugin", ignoreCase = true)
                }
                .mapNotNull { parseVersion(it.tagName)?.toString() }
                .maxOrNull() ?: Server.modLatestVersion?.toString()

        } catch (_: UnknownHostException) {
            logger.warn("Cannot reach GitHub (DNS resolution failed).")
        } catch (_: ConnectException) {
            logger.warn("Cannot connect to GitHub.")
        } catch (_: SocketTimeoutException) {
            logger.warn("GitHub connection timed out.")
        } catch (e: Exception) {
            logger.warn("Unable to load versions from GitHub: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Fetches releases from GitHub API, returning an empty list on non-200 responses. Rethrows connection errors. */
    private fun fetchReleases(owner: String, repo: String): List<Release> {
        val url = "https://api.github.com/repos/$owner/$repo/releases"
        val response = DreamHttpClient.execute(
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
        if (response.code != 200) return emptyList()

        return gson.fromJson(
            response.bodyString(),
            object : TypeToken<List<Release>>() {}.type
        ) ?: emptyList()
    }

    /** Extracts a stable semver from a GitHub release tag; returns null for snapshots and unparseable tags. */
    private fun parseVersion(tag: String): Semver? =
        Semver.coerce(tag)?.takeIf { it.isStable() }

    data class Release(
        @field:SerializedName("tag_name") val tagName: String,
        @field:SerializedName("name") val name: String,
    )
}
