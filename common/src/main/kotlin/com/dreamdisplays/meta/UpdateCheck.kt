package com.dreamdisplays.meta

import com.dreamdisplays.utils.GeneralUtil
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.inotsleep.utils.logging.LoggingManager
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/** Checks mod updates. **/
object UpdateCheck {
    private const val API = "https://api.github.com/repos/arsmotorin/dreamdisplays/releases/latest"

    @Volatile private var checked = false
    @Volatile private var updateAvailable = false
    @Volatile private var latestVersion: String? = null

    fun isUpdateAvailable(): Boolean {
        if (!checked) startCheck()
        return updateAvailable
    }

    fun shouldShowArrow(): Boolean {
        if (!checked) startCheck()
        val latest = latestVersion ?: return false
        return !latest.equals(GeneralUtil.getModVersion(), ignoreCase = true)
    }

    fun latestVersion(): String = latestVersion ?: GeneralUtil.getModVersion()

    @Synchronized private fun startCheck() {
        if (checked) return
        checked = true
        Thread(::doCheck, "dreamdisplays-update-check").apply { isDaemon = true }.start()
    }

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
                    optString(obj, "tag_name") ?: optString(obj, "name")
                }

                root.isJsonArray -> {
                    val arr = root.asJsonArray
                    if (!arr.isEmpty && arr[0].isJsonObject) optString(arr[0].asJsonObject, "tag_name") else null
                }

                else -> null
            } ?: return
            val tag = if (rawTag.startsWith("v") || rawTag.startsWith("V")) rawTag.substring(1) else rawTag
            latestVersion = tag
            if (compareVersions(tag, GeneralUtil.getModVersion()) > 0) {
                updateAvailable = true
            }
        } catch (e: Exception) {
            LoggingManager.warn("[UpdateChecker] Update check failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun optString(obj: JsonObject, key: String): String? {
        if (!obj.has(key) || obj.get(key).isJsonNull) return null
        return runCatching { obj.get(key).asString }.getOrNull()
    }

    private fun compareVersions(a: String, b: String): Int = runCatching {
        val aa = a.split('.', '-', '+')
        val bb = b.split('.', '-', '+')
        aa.zip(bb).firstNotNullOfOrNull { (x, y) ->
            if (x == y) return@firstNotNullOfOrNull null
            val ai = x.toIntOrNull()
            val bi = y.toIntOrNull()
            val cmp = if (ai != null && bi != null) ai.compareTo(bi) else x.compareTo(y)
            cmp.takeIf { it != 0 }
        } ?: aa.size.compareTo(bb.size)
    }.getOrElse { a.compareTo(b) }
}
