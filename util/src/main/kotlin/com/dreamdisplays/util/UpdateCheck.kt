package com.dreamdisplays.util

import com.dreamdisplays.util.json.DreamJson
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.coroutines.launch
import org.semver4j.Semver
import org.slf4j.LoggerFactory

/** Checks mod updates against the latest stable GitHub release. **/
object UpdateCheck {
    private val logger = LoggerFactory.getLogger(javaClass)

    private const val API = "https://api.github.com/repos/arnodoelinger/dreamdisplays/releases/latest"

    @Volatile
    private var checked = false

    @Volatile
    private var latestVersion: String? = null

    fun shouldShowArrow(): Boolean {
        if (isPreRelease(GeneralUtil.getModVersion())) return false
        if (!checked) startCheck()
        val latest = latestVersion ?: return false
        return compareVersions(latest, GeneralUtil.getModVersion()) > 0
    }

    fun isPreRelease(version: String): Boolean =
        version.contains("-dev", ignoreCase = true) || version.contains("-preview", ignoreCase = true)

    @Synchronized
    private fun startCheck() {
        if (checked) return
        checked = true
        DreamCoroutines.clientIo.launch { doCheck() }
    }

    private fun doCheck() {
        runCatching {
            val body = DreamHttpClient.readText(
                API,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf(
                        "User-Agent" to
                                "DreamDisplays/${GeneralUtil.getModVersion()} (+github.com/arnodoelinger/dreamdisplays)",
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
        }.onFailure { e ->
            logger.warn("Update check failed: ${e.message}")
        }
    }

    internal fun compareVersions(a: String, b: String): Int {
        val av = Semver.coerce(a) ?: return a.compareTo(b)
        val bv = Semver.coerce(b) ?: return a.compareTo(b)
        return av.compareTo(bv)
    }
}
