package com.dreamdisplays.media.source.youtube.binary

import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.media.source.youtube.ResolverConfig
import com.dreamdisplays.util.OsInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

object YtDlpJsRuntime {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val KNOWN = ["deno", "node", "bun", "quickjs"]

    @Volatile
    private var cached: Pair<String, List<String>>? = null

    @Volatile
    private var announcedDownloaded = false

    fun args(): List<String> {
        val setting = ResolverConfig.ytdlpJsRuntime.trim().ifEmpty { "auto" }
        val args = cached?.takeIf { it.first == setting }?.second ?: synchronized(this) {
            cached?.takeIf { it.first == setting }?.second ?: run {
                val detected = detect(setting)?.let { (name, path) -> runtimeArgs(name, path) } ?: []
                cached = setting to detected
                detected
            }
        }
        if (args.isNotEmpty() || !setting.equals("auto", ignoreCase = true)) return args
        val deno = DenoBinary.pathOrStartDownload() ?: return []
        if (!announcedDownloaded) {
            announcedDownloaded = true
            logger.info("yt-dlp JavaScript runtime: downloaded deno ({}).", deno)
        }
        return runtimeArgs("deno", deno)
    }

    private fun runtimeArgs(name: String, path: String): List<String> = ["--js-runtimes", "$name:$path"]

    private fun detect(setting: String): Pair<String, String>? {
        val lower = setting.lowercase()
        if (lower == "none" || lower == "off") return null
        if (lower == "auto") {
            val found = autoCandidates().firstNotNullOfOrNull { (name, exe) -> exe.takeIf(::runs)?.let { name to it } }
            if (found != null) {
                logger.info("yt-dlp JavaScript runtime: {} ({}).", found.first, found.second)
            } else {
                logger.info("No JavaScript runtime (deno, node or bun) installed for yt-dlp; using a downloaded Deno.")
            }
            return found
        }

        val explicit = parseExplicit(setting)
        if (explicit == null) {
            logger.warn("Unrecognized 'ytdlp-js-runtime' value '{}'; expected auto, none, deno, node, bun or a path.", setting)
            return null
        }
        val (name, exe) = explicit
        val candidates = if (File(exe).isAbsolute) [exe] else [exe] + wellKnownPaths(name)
        val found = candidates.firstOrNull(::runs)
        if (found == null) {
            logger.warn("Configured yt-dlp JavaScript runtime '{}' could not be started.", setting)
            return null
        }
        logger.info("yt-dlp JavaScript runtime: {} ({}).", name, found)
        return name to found
    }

    private fun parseExplicit(setting: String): Pair<String, String>? {
        val lower = setting.lowercase()
        if (lower in KNOWN) return lower to lower
        val colon = setting.indexOf(':')
        if (colon > 0) {
            val name = setting.substring(0, colon).lowercase()
            if (name in KNOWN) return name to setting.substring(colon + 1)
        }
        return runtimeFromFileName(setting)?.let { it to setting }
    }

    private fun runtimeFromFileName(path: String): String? {
        val base = File(path).name.lowercase().removeSuffix(".exe")
        return when {
            base.startsWith("deno") -> "deno"
            base.startsWith("node") -> "node"
            base.startsWith("bun") -> "bun"
            base.startsWith("qjs") || base.startsWith("quickjs") -> "quickjs"
            else -> null
        }
    }

    private fun autoCandidates(): List<Pair<String, String>> =
        ["deno", "node", "bun"].flatMap { name -> ([name] + wellKnownPaths(name)).map { name to it } }

    private fun wellKnownPaths(name: String): List<String> {
        val home = System.getProperty("user.home").orEmpty()
        val exe = if (OsInfo.isWindows) "$name.exe" else name
        val paths = ArrayList<String>()
        if (home.isNotEmpty()) {
            when (name) {
                "deno" -> paths += File(home, ".deno/bin/$exe").path
                "bun" -> paths += File(home, ".bun/bin/$exe").path
            }
        }
        if (OsInfo.isWindows) {
            if (name == "node") paths += "C:\\Program Files\\nodejs\\node.exe"
            System.getenv("LOCALAPPDATA")?.let { if (name == "deno") paths += File(it, "Microsoft\\WinGet\\Links\\deno.exe").path }
        } else {
            paths.addAll(listOf("/opt/homebrew/bin/$exe", "/usr/local/bin/$exe", "/usr/bin/$exe"))
        }
        return paths
    }

    private fun runs(exe: String): Boolean = runCatching {
        val f = File(exe)
        if ((f.isAbsolute || File.separator in exe) && !(f.isFile && f.canExecute())) return false
        val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
        runCatching { p.outputStream.close() }
        Processes.drainAsync(p.inputStream)
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            Processes.destroyTree(p)
            return false
        }
        p.exitValue() == 0
    }.getOrDefault(false)
}
