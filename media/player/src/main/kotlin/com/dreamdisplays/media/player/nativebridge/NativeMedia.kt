@file:Suppress("Since15")

package com.dreamdisplays.media.player.nativebridge

import com.dreamdisplays.util.OsInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.foreign.*
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * `Java` FFM bridge to the bundled `Rust` libraries: `dreamdisplays_native` (`FFmpeg` process pipe, NV12 -> RGBA)
 * and `dreamdisplays_lav` (in-process libav decode).
 */
object NativeMedia {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val ABI_VERSION = 2
    private const val LAV_ABI_VERSION = 6

    const val READ_OK = 0
    const val READ_EOF = 1
    const val READ_INTERRUPTED = 2
    const val READ_PREVIEW = 3
    const val LAV_NO_PTS_NANOS = Long.MIN_VALUE

    private const val LIB_BASE_NAME = "dreamdisplays_native"
    private const val LAV_BASE_NAME = "dreamdisplays_lav"
    private const val CACHE_ROOT = "./dreamdisplays/native"
    private const val STDERR_CAP = 128L * 1024L

    private val LONG = ValueLayout.JAVA_LONG
    private val INT = ValueLayout.JAVA_INT
    private val ADDR = ValueLayout.ADDRESS

    private lateinit var videoOpenHandle: MethodHandle
    private lateinit var videoReadFrameRgbaHandle: MethodHandle
    private lateinit var videoReadFrameI420Handle: MethodHandle
    private lateinit var i420ToRgbaHandle: MethodHandle
    private lateinit var videoStderrHandle: MethodHandle
    private lateinit var videoExitCodeHandle: MethodHandle
    private lateinit var videoKillHandle: MethodHandle
    private lateinit var videoCloseHandle: MethodHandle
    private lateinit var lavOpenHandle: MethodHandle
    private lateinit var lavOpenReplayHandle: MethodHandle
    private lateinit var lavReadFramePtsHandle: MethodHandle
    private lateinit var lavSeekHandle: MethodHandle
    private lateinit var lavErrorHandle: MethodHandle
    private lateinit var lavKillHandle: MethodHandle
    private lateinit var lavCloseHandle: MethodHandle
    private lateinit var lavEnableCacheHandle: MethodHandle
    private lateinit var lavRingSnapshotAtHandle: MethodHandle

    private val nativeProbe = lazy { runCatching { init() }.getOrDefault(false) }
    private val lavProbe = lazy { isAvailable && runCatching { initLav() }.getOrDefault(false) }

    /** True once the native library has been located, loaded, bound, and ABI-checked. */
    val isAvailable: Boolean by nativeProbe

    /** True once the in-process libav library has been located, loaded, and bound. */
    val lavAvailable: Boolean by lavProbe

    /** Machine-readable cause of unavailability (empty when available). */
    @Volatile
    var unavailableReason: String = ""; private set

    /** Same as [unavailableReason] but for [lavAvailable]. */
    @Volatile
    var lavUnavailableReason: String = ""; private set

    /**
     * True once both library probes have finished, so reading the flags can no longer block. The first probe loads
     * native libraries and may download `FFmpeg`, so the game thread must check this instead of touching the flags.
     */
    val probesFinished: Boolean get() = nativeProbe.isInitialized() && lavProbe.isInitialized()

    data class LavFrameReadResult(val code: Int, val ptsNanos: Long)

    private val ptsSlots = ConcurrentHashMap<Long, MemorySegment>()

    /** Touches [isAvailable] and [lavAvailable] on a background thread to keep first playback latency low. */
    fun prewarmAsync() {
        Thread({ isAvailable; lavAvailable }, "NativeMedia-prewarm").apply { isDaemon = true }.start()
    }

    /** Spawns an `FFmpeg` session writing raw NV12 frames (returns opaque handle, or 0 on failure). */
    fun videoOpen(args: List<String>, w: Int, h: Int): Long = withBytes(args.joinToString("") { "$it\u0000" }) { seg, n ->
        videoOpenHandle.invoke(seg, n, w, h) as Long
    }

    /** Blocking read of the next frame as RGBA32 with brightness ([READ_OK], [READ_EOF], or error). */
    fun videoReadFrameRgba(handle: Long, dst: ByteBuffer, frameBytes: Int, brightnessMilli: Int): Int =
        videoReadFrameRgbaHandle.invoke(handle, MemorySegment.ofBuffer(dst), frameBytes.toLong(), brightnessMilli) as Int

    /** Blocking read of the next frame as raw I420 planes. */
    fun videoReadFrameI420(handle: Long, dst: ByteBuffer, frameBytes: Int): Int =
        videoReadFrameI420Handle.invoke(handle, MemorySegment.ofBuffer(dst), frameBytes.toLong()) as Int

    /** Converts an I420 frame to RGBA32 (both direct buffers, for the popout in GPU-YUV mode). */
    fun i420ToRgba(src: ByteBuffer, srcBytes: Int, dst: ByteBuffer, w: Int, h: Int): Int =
        i420ToRgbaHandle.invoke(
            MemorySegment.ofBuffer(src), srcBytes.toLong(),
            MemorySegment.ofBuffer(dst), dst.capacity().toLong(), w, h,
        ) as Int

    /** @return the `FFmpeg` stderr captured so far for [handle] (capped at 128 KiB). */
    fun videoStderr(handle: Long): String = readString(videoStderrHandle, handle)

    /** Waits up to [waitMillis] for `FFmpeg` to exit; returns the exit code or -1 (force-killing it on timeout). */
    fun videoExitCode(handle: Long, waitMillis: Int): Int = videoExitCodeHandle.invoke(handle, waitMillis) as Int

    /** Kills the `FFmpeg` process, unblocking a reader stuck in a frame read. */
    fun videoKill(handle: Long) {
        videoKillHandle.invoke(handle)
    }

    /** Frees the native session. Must not race a frame read on the same handle. */
    fun videoClose(handle: Long) {
        videoCloseHandle.invoke(handle)
    }

    /** Opens an in-process decode session (returns opaque handle, or 0 on failure). */
    fun lavOpen(url: String, w: Int, h: Int, startMicros: Long, hwAccelCode: Int): Long = withBytes(url) { seg, n ->
        lavOpenHandle.invoke(seg, n, w, h, startMicros, hwAccelCode) as Long
    }

    /** Opens a native replay decode session from a serialized packet-ring [blob]. */
    fun lavOpenReplay(blob: ByteArray, w: Int, h: Int, resumeNanos: Long): Long {
        if (blob.isEmpty()) return 0L
        return withBytes(blob) { seg, n -> lavOpenReplayHandle.invoke(seg, n, w, h, resumeNanos) as Long }
    }

    /** Enables the native rolling packet cache for a live LAV [handle]. */
    fun lavEnableCache(handle: Long, windowMs: Long, maxBytes: Long): Boolean =
        windowMs > 0 && maxBytes > 0 &&
                lavEnableCacheHandle.invoke(handle, windowMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), maxBytes) as Int == READ_OK

    /** Captures the live LAV packet-ring snapshot for [handle] from [positionNanos], or null when no data is ready. */
    fun lavRingSnapshot(handle: Long, positionNanos: Long): ByteArray? {
        var size = lavRingSnapshotAtHandle.invoke(handle, positionNanos, MemorySegment.NULL, 0L) as Int
        repeat(3) {
            if (size <= 0) return null
            Arena.ofConfined().use { arena ->
                val seg = arena.allocate(size.toLong())
                val written = lavRingSnapshotAtHandle.invoke(handle, positionNanos, seg, size.toLong()) as Int
                if (written in 1..size) return seg.asSlice(0, written.toLong()).toArray(ValueLayout.JAVA_BYTE)
                size = written
            }
        }
        return null
    }

    /** Blocking in-process decode of the next I420 frame plus its normalized PTS. */
    fun lavReadFrameI420WithPts(handle: Long, dst: ByteBuffer, frameBytes: Int): LavFrameReadResult {
        val pts = ptsSlots.computeIfAbsent(handle) { Arena.ofAuto().allocate(LONG) }
        pts.set(LONG, 0L, LAV_NO_PTS_NANOS)
        val rc = lavReadFramePtsHandle.invoke(handle, MemorySegment.ofBuffer(dst), frameBytes.toLong(), pts) as Int
        return LavFrameReadResult(rc, pts.get(LONG, 0L))
    }

    /** Seeks a live in-process libav session in place. */
    fun lavSeek(handle: Long, targetMicros: Long): Boolean =
        lavSeekHandle.invoke(handle, targetMicros.coerceAtLeast(0L)) as Int == READ_OK

    /** Returns the in-process session's last error description, or an empty string. */
    fun lavError(handle: Long): String = readString(lavErrorHandle, handle)

    /** Interrupts the in-process session, unblocking a reader stuck in a frame read. */
    fun lavKill(handle: Long) {
        lavKillHandle.invoke(handle)
    }

    /** Frees the in-process session. Must not race a frame read on the same handle. */
    fun lavClose(handle: Long) {
        ptsSlots.remove(handle)
        lavCloseHandle.invoke(handle)
    }

    private inline fun <T> withBytes(text: String, block: (MemorySegment, Long) -> T): T =
        withBytes(text.toByteArray(Charsets.UTF_8), block)

    private inline fun <T> withBytes(bytes: ByteArray, block: (MemorySegment, Long) -> T): T =
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, seg, 0L, bytes.size.toLong())
            block(seg, bytes.size.toLong())
        }

    private fun readString(handle: MethodHandle, session: Long): String = Arena.ofConfined().use { arena ->
        val seg = arena.allocate(STDERR_CAP)
        val n = handle.invoke(session, seg, STDERR_CAP) as Int
        if (n <= 0) "" else String(seg.asSlice(0, n.toLong()).toArray(ValueLayout.JAVA_BYTE), Charsets.UTF_8)
    }

    private class Binder(lib: File) {
        private val linker = Linker.nativeLinker()

        private val lookup = SymbolLookup.libraryLookup(lib.toPath(), Arena.global())

        fun bind(name: String, desc: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(lookup.find(name).orElseThrow { IllegalStateException("Symbol $name missing") }, desc)
    }

    private fun init(): Boolean {
        if (Runtime.version().feature() < 21) {
            logger.warn("Native pipeline requires Java 21+ (running ${Runtime.version().feature()}); using JVM pipeline.")
            unavailableReason = "java_too_old"
            return false
        }
        val lib = locateLibrary(LIB_BASE_NAME) ?: run {
            logger.warn("Native library not found; using JVM pipeline.")
            unavailableReason = "library_not_found"
            return false
        }
        return try {
            val b = Binder(lib)
            val abi = b.bind("dd_abi_version", FunctionDescriptor.of(INT)).invoke() as Int
            if (abi != ABI_VERSION) {
                logger.warn("Native library ABI mismatch: found $abi, expected $ABI_VERSION; using JVM pipeline.")
                unavailableReason = "abi_mismatch"
                return false
            }
            videoOpenHandle = b.bind("dd_video_open", FunctionDescriptor.of(LONG, ADDR, LONG, INT, INT))
            videoReadFrameRgbaHandle = b.bind("dd_video_read_frame_rgba", FunctionDescriptor.of(INT, LONG, ADDR, LONG, INT))
            videoReadFrameI420Handle = b.bind("dd_video_read_frame_i420", FunctionDescriptor.of(INT, LONG, ADDR, LONG))
            i420ToRgbaHandle = b.bind("dd_i420_to_rgba", FunctionDescriptor.of(INT, ADDR, LONG, ADDR, LONG, INT, INT))
            videoStderrHandle = b.bind("dd_video_stderr", FunctionDescriptor.of(INT, LONG, ADDR, LONG))
            videoExitCodeHandle = b.bind("dd_video_exit_code", FunctionDescriptor.of(INT, LONG, INT))
            videoKillHandle = b.bind("dd_video_kill", FunctionDescriptor.ofVoid(LONG))
            videoCloseHandle = b.bind("dd_video_close", FunctionDescriptor.ofVoid(LONG))
            logger.info("Native media pipeline active: $lib.")
            true
        } catch (t: Throwable) {
            // UnsupportedOperationException on Java 21 preview gates, UnsatisfiedLinkError, etc.
            logger.warn("Native pipeline unavailable (${t.javaClass.simpleName}: ${t.message}); using JVM pipeline.")
            unavailableReason = "error_${t.javaClass.simpleName}"
            false
        }
    }

    private fun initLav(): Boolean {
        val lib = locateLibrary(LAV_BASE_NAME) ?: run {
            logger.info("In-process libav library not found; in-process decode unavailable.")
            lavUnavailableReason = "library_not_found"
            return false
        }
        return try {
            LavFfmpeg.ensure(lib.parentFile)
            preloadLavDependencies(lib.parentFile)
            val b = Binder(lib)
            val abi = b.bind("dd_lav_abi_version", FunctionDescriptor.of(INT)).invoke() as Int
            if (abi != LAV_ABI_VERSION) {
                logger.warn("In-process libav library ABI mismatch: found $abi, expected $LAV_ABI_VERSION.")
                lavUnavailableReason = "abi_mismatch"
                return false
            }
            lavOpenHandle = b.bind("dd_lav_open", FunctionDescriptor.of(LONG, ADDR, LONG, INT, INT, LONG, INT))
            lavOpenReplayHandle = b.bind("dd_lav_open_replay", FunctionDescriptor.of(LONG, ADDR, LONG, INT, INT, LONG))
            lavReadFramePtsHandle = b.bind("dd_lav_read_frame_i420_pts", FunctionDescriptor.of(INT, LONG, ADDR, LONG, ADDR))
            lavSeekHandle = b.bind("dd_lav_seek", FunctionDescriptor.of(INT, LONG, LONG))
            lavErrorHandle = b.bind("dd_lav_error", FunctionDescriptor.of(INT, LONG, ADDR, LONG))
            lavKillHandle = b.bind("dd_lav_kill", FunctionDescriptor.ofVoid(LONG))
            lavCloseHandle = b.bind("dd_lav_close", FunctionDescriptor.ofVoid(LONG))
            lavEnableCacheHandle = b.bind("dd_lav_enable_cache", FunctionDescriptor.of(INT, LONG, INT, LONG))
            lavRingSnapshotAtHandle = b.bind("dd_lav_ring_snapshot_at", FunctionDescriptor.of(INT, LONG, LONG, ADDR, LONG))
            logger.info("In-process libav backend available: $lib.")
            true
        } catch (t: Throwable) {
            // Typically UnsatisfiedLinkError when the FFmpeg shared libraries are missing.
            logger.warn("In-process libav backend unavailable (${t.javaClass.simpleName}: ${t.message}).")
            lavUnavailableReason = "error_${t.javaClass.simpleName}"
            false
        }
    }

    private fun preloadLavDependencies(dir: File?) {
        val pending = dir?.listFiles()
            ?.filter { it.isFile && isFfmpegSharedLibrary(it.name) }
            ?.sortedWith(compareBy<File> { loadOrder(it.name) }.thenBy { it.name })
            ?.toMutableList() ?: return
        repeat(3) {
            pending.removeAll { runCatching { SymbolLookup.libraryLookup(it.toPath(), Arena.global()) }.isSuccess }
        }
        pending.forEach { logger.debug("Could not preload LAV dependency ${it.name}.") }
    }

    private fun isFfmpegSharedLibrary(name: String): Boolean {
        val lower = name.lowercase()
        val shared = lower.endsWith(".dll") || lower.endsWith(".dylib") || lower.contains(".so")
        return shared && "dreamdisplays_" !in lower
    }

    private fun loadOrder(name: String): Int {
        val lower = name.lowercase()
        val index = FFMPEG_LOAD_ORDER.indexOfFirst { it in lower }
        return if (index < 0) 0 else 100 + index
    }

    private fun locateLibrary(baseName: String): File? {
        val libName = System.mapLibraryName(baseName)
        val cached = File("$CACHE_ROOT/${platformKey()}/$libName")
        val bytes = javaClass.getResourceAsStream("/dreamdisplays-natives/${platformKey()}/$libName")?.use { it.readBytes() }
            ?: return cached.takeIf { it.isFile && it.length() > 0 }
        if (cached.isFile && cached.length() == bytes.size.toLong() && runCatching { cached.readBytes().contentEquals(bytes) }.getOrDefault(false)) {
            return cached
        }
        val parent = cached.parentFile
        if (!parent.exists() && !parent.mkdirs()) return null
        val tmp = File(parent, "$libName.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(cached)) {
            tmp.delete()
            return null
        }
        return cached
    }

    private fun platformKey(): String {
        val os = when {
            OsInfo.isWindows -> "windows"
            OsInfo.isMac -> "macos"
            else -> "linux"
        }
        return "$os-${if (OsInfo.isArm) "aarch64" else "x64"}"
    }

    private val FFMPEG_LOAD_ORDER = ["avutil", "swresample", "swscale", "avcodec", "avformat", "avfilter", "avdevice"]
}
