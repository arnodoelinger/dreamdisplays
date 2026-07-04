package com.dreamdisplays.platform.server.meta

import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly

import com.dreamdisplays.platform.server.utils.GitHubFetcherUtil
import com.dreamdisplays.util.asJsonArrayOrNull
import com.dreamdisplays.util.asJsonObjectOrNull
import com.dreamdisplays.util.net.DreamHttpClient
import com.dreamdisplays.util.optString
import com.dreamdisplays.util.json.DreamJson
import org.semver4j.Semver
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

            val latestMod = stableVersions.maxOrNull()
            setSemverProperty(PAPER_MAIN_CLASS, "modVersion", latestMod)

            val latestPlugin = releases
                .filter {
                    it.tagName.contains("spigot", ignoreCase = true) ||
                            it.tagName.contains("plugin", ignoreCase = true)
                }
                .mapNotNull { parseVersion(it.tagName)?.toString() }
                .maxOrNull() ?: latestMod?.toString()
            setStringProperty(PAPER_MAIN_CLASS, "pluginLatestVersion", latestPlugin)

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

            val latestMod = stableVersions.maxOrNull()
            setSemverProperty(FABRIC_SERVER_CLASS, "modLatestVersion", latestMod)

            val latestPlugin = releases
                .filter {
                    it.tagName.contains("spigot", ignoreCase = true) ||
                            it.tagName.contains("plugin", ignoreCase = true)
                }
                .mapNotNull { parseVersion(it.tagName)?.toString() }
                .maxOrNull() ?: latestMod?.toString()
            setStringProperty(FABRIC_SERVER_CLASS, "pluginLatestVersion", latestPlugin)

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

        return parseReleases(response.bodyString())
    }

    /** Parses only the GitHub release fields used by the updater; no generated serializer required. */
    private fun parseReleases(body: String): List<Release> =
        DreamJson.compact.parseToJsonElement(body)
            .asJsonArrayOrNull()
            ?.mapNotNull { element ->
                val obj = element.asJsonObjectOrNull() ?: return@mapNotNull null
                Release(
                    tagName = obj.optString("tag_name").orEmpty(),
                    name = obj.optString("name").orEmpty(),
                )
            }
            ?.filter { it.tagName.isNotBlank() }
            ?: emptyList()

    /** Extracts a stable semver from a GitHub release tag; returns null for snapshots and unparseable tags. */
    private fun parseVersion(tag: String): Semver? =
        Semver.coerce(tag)?.takeIf { it.isStable() }

    data class Release(
        val tagName: String = "",
        val name: String = "",
    )
}

/**
 * `NeoForge`-specific implementation of [Updater]. Named distinctly from [FabricUpdater] (rather
 * than reused) to avoid a redeclaration clash — see [FabricDisplayData] doc for why.
 */
@NeoForgeOnly
object NeoForgeUpdater {
    private val logger = LoggerFactory.getLogger("DreamDisplays/Updater")

    /**
     * Fetches GitHub releases and stores the latest mod and plugin versions in `NeoForgeServer`.
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

            val latestMod = stableVersions.maxOrNull()
            setSemverProperty(NEOFORGE_SERVER_CLASS, "modLatestVersion", latestMod)

            val latestPlugin = releases
                .filter {
                    it.tagName.contains("spigot", ignoreCase = true) ||
                            it.tagName.contains("plugin", ignoreCase = true)
                }
                .mapNotNull { parseVersion(it.tagName)?.toString() }
                .maxOrNull() ?: latestMod?.toString()
            setStringProperty(NEOFORGE_SERVER_CLASS, "pluginLatestVersion", latestPlugin)

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

        return parseReleases(response.bodyString())
    }

    /** Parses only the GitHub release fields used by the updater; no generated serializer required. */
    private fun parseReleases(body: String): List<Release> =
        DreamJson.compact.parseToJsonElement(body)
            .asJsonArrayOrNull()
            ?.mapNotNull { element ->
                val obj = element.asJsonObjectOrNull() ?: return@mapNotNull null
                Release(
                    tagName = obj.optString("tag_name").orEmpty(),
                    name = obj.optString("name").orEmpty(),
                )
            }
            ?.filter { it.tagName.isNotBlank() }
            ?: emptyList()

    /** Extracts a stable semver from a GitHub release tag; returns null for snapshots and unparseable tags. */
    private fun parseVersion(tag: String): Semver? =
        Semver.coerce(tag)?.takeIf { it.isStable() }

    data class Release(
        val tagName: String = "",
        val name: String = "",
    )
}

private const val PAPER_MAIN_CLASS = "com.dreamdisplays.platform.server.Main"
private const val FABRIC_SERVER_CLASS = "com.dreamdisplays.platform.server.Server"
private const val NEOFORGE_SERVER_CLASS = "com.dreamdisplays.platform.server.NeoForgeServer"

private fun setSemverProperty(className: String, property: String, value: Semver?) {
    setCompanionProperty(className, property, Semver::class.java, value)
}

private fun setStringProperty(className: String, property: String, value: String?) {
    setCompanionProperty(className, property, String::class.java, value)
}

private fun setCompanionProperty(className: String, property: String, type: Class<*>, value: Any?) {
    val companion = Class.forName(className).getField("Companion").get(null)
    companion.javaClass.getMethod("set${property.replaceFirstChar(Char::uppercaseChar)}", type)
        .invoke(companion, value)
}
