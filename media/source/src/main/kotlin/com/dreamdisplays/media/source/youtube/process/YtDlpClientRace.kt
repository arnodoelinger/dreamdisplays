package com.dreamdisplays.media.source.youtube.process

import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.media.source.youtube.binary.YtDlpBinary
import com.dreamdisplays.media.source.youtube.binary.YtDlpOutputParser
import com.dreamdisplays.media.source.youtube.cookie.YtCookieManager
import com.dreamdisplays.media.source.youtube.model.YtStream
import com.dreamdisplays.media.source.youtube.model.YtStreams
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Runs and races `yt-dlp` subprocesses for a single fetch, using [cookies] for the cookie/proxy
 * path. Extracted from [com.dreamdisplays.media.source.youtube.YtDlp] to isolate the subprocess
 * mechanics from its caching concerns.
 */
class YtDlpClientRace(private val cookies: YtCookieManager) {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Per-invocation `yt-dlp` wait (keep short to surface failures fast). */
        const val FETCH_TIMEOUT_SECONDS: Long = 25L

        private const val PRIMARY_CLIENT = "visionos"

        /** Token-free clients raced in parallel (hit PO-token wall, but auto-recover if working). */
        private val FALLBACK_CLIENTS: List<String?> = listOf("android_vr", "ios", "tv", "android")
    }

    /**
     * Resolves [videoUrl] via `yt-dlp`. Tries [PRIMARY_CLIENT] alone first (one request, fast, the only client that
     * isn't PO-token gated), then races [FALLBACK_CLIENTS] in parallel if that fails. [onProcess] receives every
     * spawned subprocess so the caller can kill stragglers once it no longer needs this branch.
     */
    suspend fun resolve(videoUrl: String, onProcess: (Process) -> Unit): List<YtStream>? {
        if (!cookies.disabledByConfig()) {
            return runCatchingClient(videoUrl, null, onProcess)?.takeIf { YtStreams.usable(it) }
        }
        runCatchingClient(videoUrl, PRIMARY_CLIENT, onProcess)?.takeIf { YtStreams.usable(it) }?.let { return it }
        return raceParallel(videoUrl, FALLBACK_CLIENTS, onProcess)
    }

    /** Runs a single [client] fetch, reporting its subprocess to [onProcess]; returns null instead of throwing. */
    private fun runCatchingClient(videoUrl: String, client: String?, onProcess: (Process) -> Unit): List<YtStream>? =
        runCatching {
            runClientFetch(videoUrl, client, onProcess)
        }.onFailure { e ->
            logger.debug("yt-dlp client {} failed for {}: {}.", client ?: "cookies", videoUrl, e.message?.take(200))
        }.getOrNull()

    /**
     * Races [clients] (one subprocess each) in parallel: the first to yield a quality ladder wins
     * immediately and the still-running losers are killed; otherwise, once every client finishes,
     * [bestResult] picks the strongest result. Returns null when every client failed.
     */
    private suspend fun raceParallel(
        videoUrl: String,
        clients: List<String?>,
        onProcess: (Process) -> Unit
    ): List<YtStream>? {
        val processes = CopyOnWriteArrayList<Process>()
        val results = CopyOnWriteArrayList<List<YtStream>>()
        val winner = CompletableDeferred<List<YtStream>>()
        val remaining = AtomicInteger(clients.size)
        val ioContext = DreamCoroutines.clientIo.coroutineContext.minusKey(Job)

        val runRace: suspend () -> List<YtStream> = {
            coroutineScope {
                for (client in clients) {
                    launch(ioContext) {
                        runCatching {
                            val streams = runClientFetch(videoUrl, client) { proc ->
                                processes.add(proc)
                                onProcess(proc)
                                if (winner.isCompleted) Processes.destroyTree(proc)
                            }
                            if (YtStreams.usable(streams)) results.add(streams)
                            if (YtStreams.usable(streams) && YtStreams.offersQualityLadder(streams)) {
                                winner.complete(streams)
                            }
                        }.onFailure { e ->
                            if (e is CancellationException) throw e
                            logger.debug(
                                "yt-dlp client {} failed for {}: {}",
                                client ?: "cookies",
                                videoUrl,
                                e.message?.take(200)
                            )
                        }.also { result ->
                            if (result.exceptionOrNull() is CancellationException) return@launch

                            if (remaining.decrementAndGet() == 0) {
                                winner.complete(bestResult(results))
                            }
                        }
                    }
                }
                winner.await()
            }
        }

        val finalResult = try {
            withTimeoutOrNull((FETCH_TIMEOUT_SECONDS + 10).seconds) { runRace() } ?: run {
                logger.warn("yt-dlp race for $videoUrl did not settle.")
                emptyList()
            }
        } finally {
            processes.forEach { runCatching { Processes.destroyTree(it) } }
        }

        return finalResult.takeIf { it.isNotEmpty() }
    }

    /** Picks the strongest raced result: a quality ladder first, then the one with the most heights. */
    private fun bestResult(results: List<List<YtStream>>): List<YtStream> =
        results.maxWithOrNull(
            compareBy<List<YtStream>> { if (YtStreams.offersQualityLadder(it)) 1 else 0 }
                .thenBy { YtStreams.distinctHeights(it).size }
        ) ?: emptyList()

    /**
     * Runs a single `yt-dlp` invocation for [videoUrl] with the given [client] (null = let yt-dlp
     * choose, used on the cookie path). [onStarted] receives the live process so the racer can kill
     * it once another client wins. Returns the parsed streams; throws on non-zero exit or timeout.
     */
    @Throws(IOException::class)
    private fun runClientFetch(videoUrl: String, client: String?, onStarted: (Process) -> Unit): List<YtStream> {
        val cmd = ArrayList<String>()
        cmd.addAll(YtDlpBinary.resolveCommand())
        val tempCookies = cookies.appendArgs(cmd)

        val hasCookieArg = cmd.any { it == "--cookies" || it == "--cookies-from-browser" }
        if (!hasCookieArg && client != null) {
            cmd.addAll(listOf("--extractor-args", "youtube:player_client=$client"))
        }

        cmd.addAll(
            listOf(
                "--force-ipv4",
                "-J", "--no-playlist", "--no-warnings", "--no-check-formats",
                "--ignore-config", "--no-mark-watched",
                "--extractor-retries", "0",
                "--socket-timeout", "8",
                "--",
                videoUrl,
            )
        )
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(false)
        val process = pb.start()
        onStarted(process)
        runCatching { process.outputStream.close() }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = Processes.collector(process.inputStream, stdout, "YtDlp-stdout")
        val stderrReader = Processes.collector(process.errorStream, stderr, "YtDlp-stderr")
        stdoutReader.start()
        stderrReader.start()
        try {
            if (!process.waitFor(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Processes.destroyTree(process)
                stdoutReader.join(2_000)
                stderrReader.join(2_000)
                throw IOException("Timed out for url: $videoUrl (client=${client ?: "cookies"}).")
            }
            stdoutReader.join(5_000)
            stderrReader.join(5_000)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for yt-dlp.", e)
        } finally {
            if (tempCookies != null) runCatching { Files.deleteIfExists(tempCookies) }
        }
        if (process.exitValue() != 0) {
            throw IOException("Exited with code ${process.exitValue()}: ${stderr.toString().trim()}.")
        }
        return YtDlpOutputParser.parseFormats(stdout.toString())
    }
}
