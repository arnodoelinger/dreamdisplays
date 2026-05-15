package com.dreamdisplays.meta

import com.dreamdisplays.Main.Companion.modVersion
import com.dreamdisplays.Main.Companion.pluginLatestVersion
import com.dreamdisplays.utils.GitHubFetcherUtil
import com.github.zafarkhaja.semver.Version
import me.inotsleep.utils.logging.LoggingManager.warn
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.regex.Pattern

/**
 * Checks for updates of the plugin and mod from GitHub releases.
 */
object Updater {
    private val tailPattern = Pattern.compile("\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?")

    fun checkForUpdates(repoOwner: String, repoName: String) {
        try {
            val releases = GitHubFetcherUtil.fetchReleases(repoOwner, repoName)

            if (releases.isEmpty()) {
                warn("No releases found on GitHub. This may be due to network issues or API problems.")
                return
            }

            modVersion = releases
                .mapNotNull { parseVersion(it.tagName) }
                .filter { !it.toString().contains("-SNAPSHOT") }
                .maxOrNull()

            pluginLatestVersion = releases
                .filter {
                    it.tagName.contains("spigot", ignoreCase = true) ||
                            it.tagName.contains("plugin", ignoreCase = true)
                }
                .mapNotNull { parseVersion(it.tagName)?.toString() }
                .filter { !it.contains("-SNAPSHOT") }
                .maxOrNull() ?: modVersion?.toString()

        } catch (_: UnknownHostException) {
            warn("Cannot reach GitHub (DNS resolution failed). It seems that your hosting environment cannot resolve GitHub's domain.")
        } catch (_: ConnectException) {
            warn("Cannot connect to GitHub. It seems that your hosting environment is blocking connections or 443 port is closed.")
        } catch (_: SocketTimeoutException) {
            warn("GitHub connection timed out. The GitHub API may be experiencing issues or your hosting environment has a very slow connection.")
        } catch (e: Exception) {
            warn("Unable to load versions from GitHub: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun parseVersion(tag: String): Version? {
        val matcher = tailPattern.matcher(tag)
        return if (matcher.find()) runCatching { Version.parse(matcher.group()) }.getOrNull()
        else null
    }
}
