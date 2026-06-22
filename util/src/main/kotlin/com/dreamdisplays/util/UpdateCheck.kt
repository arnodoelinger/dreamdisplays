package com.dreamdisplays.util

import com.google.gson.JsonParser
import kotlinx.coroutines.launch
import org.semver4j.Semver
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/** Checks mod updates against the latest stable GitHub release. **/
object UpdateCheck {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/UpdateCheck")

    /** GitHub releases API. */
    private const val API = "https://api.github.com/repos/arsmotorin/dreamdisplays/releases/latest"

    /** Check state. */
    @Volatile private var checked = false

    /** Latest release version of the mod, or null if the check failed or the version is unknown. */
    @Volatile private var latestVersion: String? = null

    /**
     * Returns true if the UI update arrow should be shown.
     * Suppressed on DEV / SNAPSHOT builds and when the current version is already newer than the latest stable.
     */
    fun shouldShowArrow(): Boolean {
        if (isPreRelease(GeneralUtil.getModVersion())) return false
        if (!checked) startCheck()
        val latest = latestVersion ?: return false
        return compareVersions(latest, GeneralUtil.getModVersion()) > 0
    }

    /** If [version] is a DEV or SNAPSHOT build, returns true. */
    fun isPreRelease(version: String): Boolean =
        version.contains("-DEV", ignoreCase = true) || version.contains("-SNAPSHOT", ignoreCase = true)

    /** Start the background update check exactly once; subsequent calls are no-ops. */
    @Synchronized private fun startCheck() {
        if (checked) return
        checked = true
        DreamCoroutines.clientIo.launch { doCheck() }
    }

    /** Check the latest release version against the current version. */
    private fun doCheck() {
        var conn: HttpURLConnection? = null
        try {
            conn = (URI.create(API).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 8_000
                setRequestProperty(
                    "User-Agent",
                    "DreamDisplays/${GeneralUtil.getModVersion()} (+github.com/arsmotorin/dreamdisplays)"
                )
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (conn.responseCode != 200) return
            val body = conn.inputStream.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
            val root = JsonParser.parseString(body)
            val rawTag: String = when {
                root.isJsonObject -> {
                    val obj = root.asJsonObject
                    obj.optString("tag_name") ?: obj.optString("name")
                }
                root.isJsonArray -> {
                    val arr = root.asJsonArray
                    if (!arr.isEmpty && arr[0].isJsonObject) arr[0].asJsonObject.optString("tag_name") else null
                }
                else -> null
            } ?: return
            latestVersion = rawTag.trimStart('v', 'V')
        } catch (e: Exception) {
            logger.warn("Update check failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /** Compares two version strings using semver rules. Returns positive if [a] is newer than [b]. */
    internal fun compareVersions(a: String, b: String): Int {
        val av = Semver.coerce(a) ?: return a.compareTo(b)
        val bv = Semver.coerce(b) ?: return a.compareTo(b)
        return av.compareTo(bv)
    }
}
