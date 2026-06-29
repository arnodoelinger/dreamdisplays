package com.dreamdisplays.util

import com.dreamdisplays.util.net.DreamHttpClient
import com.dreamdisplays.util.json.DreamJson
import kotlinx.coroutines.launch
import org.semver4j.Semver
import org.slf4j.LoggerFactory

/** Checks mod updates against the latest stable GitHub release. **/
object UpdateCheck {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/UpdateCheck")

    /** GitHub releases API. */
    private const val API = "https://api.github.com/repos/arsmotorin/dreamdisplays/releases/latest"

    /** Check state. */
    @Volatile
    private var checked = false

    /** Latest release version of the mod, or null if the check failed or the version is unknown. */
    @Volatile
    private var latestVersion: String? = null

    /**
     * Returns true if the UI update arrow should be shown.
     * Suppressed on dev / preview builds and when the current version is already newer than the latest stable.
     */
    fun shouldShowArrow(): Boolean {
        if (isPreRelease(GeneralUtil.getModVersion())) return false
        if (!checked) startCheck()
        val latest = latestVersion ?: return false
        return compareVersions(latest, GeneralUtil.getModVersion()) > 0
    }

    /** If [version] is a dev or preview build, returns true. */
    fun isPreRelease(version: String): Boolean =
        version.contains("-dev", ignoreCase = true) || version.contains("-preview", ignoreCase = true)

    /** Start the background update check exactly once; subsequent calls are no-ops. */
    @Synchronized
    private fun startCheck() {
        if (checked) return
        checked = true
        DreamCoroutines.clientIo.launch { doCheck() }
    }

    /** Check the latest release version against the current version. */
    private fun doCheck() {
        try {
            val body = DreamHttpClient.readText(
                API,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf(
                        "User-Agent" to
                                "DreamDisplays/${GeneralUtil.getModVersion()} (+github.com/arsmotorin/dreamdisplays)",
                        "Accept" to "application/vnd.github+json",
                    ),
                    connectTimeoutMs = 5_000,
                    readTimeoutMs = 8_000,
                ),
            )
            val root = DreamJson.compact.parseToJsonElement(body)
            val rawTag: String = when {
                root.asJsonObjectOrNull() != null -> {
                    val obj = root.asJsonObjectOrNull()!!
                    obj.optString("tag_name") ?: obj.optString("name")
                }

                root.asJsonArrayOrNull() != null -> {
                    val arr = root.asJsonArrayOrNull()!!
                    arr.firstOrNull().asJsonObjectOrNull()?.optString("tag_name")
                }

                else -> null
            } ?: return
            latestVersion = rawTag.trimStart('v', 'V')
        } catch (e: Exception) {
            logger.warn("Update check failed: ${e.message}")
        }
    }

    /** Compares two version strings using semver rules. Returns positive if [a] is newer than [b]. */
    internal fun compareVersions(a: String, b: String): Int {
        val av = Semver.coerce(a) ?: return a.compareTo(b)
        val bv = Semver.coerce(b) ?: return a.compareTo(b)
        return av.compareTo(bv)
    }
}
