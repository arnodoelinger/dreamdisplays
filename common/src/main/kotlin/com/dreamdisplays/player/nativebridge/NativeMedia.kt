package com.dreamdisplays.player.nativebridge

import com.dreamdisplays.utils.OsInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer

/**
 * Java FFM (Project Panama) bridge to the optional `dreamdisplays_native` Rust library.
 *
 * The library owns the FFmpeg video process and its pipe: it reads raw frames in large
 * blocks, converts NV12 -> RGB24 and applies brightness in a single fused native pass,
 * writing straight into the direct `ByteBuffer` that is later uploaded to the GPU.
 *
 * Availability is decided once, lazily: requires Java 22+ (FFM is a preview API on 21),
 * a loadable library for the current platform, and a matching ABI version. When any of
 * that fails the media pipeline silently falls back to the pure-JVM [com.dreamdisplays.player.pipeline.VideoFramePipe].
 */
internal object NativeMedia {
    private val logger = LoggerFactory.getLogger("DreamDisplays/NativeMedia")

    /** Must match `ABI_VERSION` in `native/src/lib.rs`. */
    private const val ABI_VERSION = 1

    /** Result codes of [videoReadFrame]; mirror `native/src/session.rs`. */
    const val READ_OK = 0
    const val READ_EOF = 1

    private const val LIB_BASE_NAME = "dreamdisplays_native"
    private const val CACHE_ROOT = "./dreamdisplays/native"
    private const val STDERR_CAP = 128L * 1024L

    /** When true (default) the native pipe carries NV12 instead of RGB24, halving pipe traffic. */
    val nv12Enabled: Boolean = System.getProperty("dreamdisplays.native.nv12", "true").toBoolean()

    private var abiVersion: MethodHandle? = null
    private var videoOpen: MethodHandle? = null
    private var videoReadFrame: MethodHandle? = null
    private var videoReadFrameRgbaHandle: MethodHandle? = null
    private var videoStderr: MethodHandle? = null
    private var videoExitCode: MethodHandle? = null
    private var videoKill: MethodHandle? = null
    private var videoClose: MethodHandle? = null

    /** True once the library has been located, loaded, bound, and ABI-checked. */
    val isAvailable: Boolean by lazy { runCatching { init() }.getOrDefault(false) }

    /** Uses native RGBA output so the render thread can upload directly into RGBA8 textures. */
    val rgbaFramesEnabled: Boolean
        get() = isAvailable
                && System.getProperty("dreamdisplays.native.rgba", "true").toBoolean()
                && videoReadFrameRgbaHandle != null

    /** Touches [isAvailable] on a background thread to keep first playback latency low. */
    fun prewarmAsync() {
        Thread({ isAvailable }, "NativeMedia-prewarm").apply { isDaemon = true }.start()
    }

    /**
     * Spawns an FFmpeg session in the native library. [args] is the full argv including
     * the binary path. Returns an opaque handle, or 0 on failure.
     */
    fun videoOpen(args: List<String>, w: Int, h: Int, nv12: Boolean): Long {
        val blob = buildString { args.forEach { append(it); append('\u0000') } }.toByteArray(Charsets.UTF_8)
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(blob.size.toLong())
            MemorySegment.copy(MemorySegment.ofArray(blob), 0L, seg, 0L, blob.size.toLong())
            return videoOpen!!.invoke(seg, blob.size.toLong(), w, h, if (nv12) 1 else 0) as Long
        }
    }

    /**
     * Blocking read of the next frame into [dst] (a direct buffer) as RGB24 with
     * brightness pre-applied. Returns [READ_OK], [READ_EOF], or a negative error code.
     */
    fun videoReadFrame(handle: Long, dst: ByteBuffer, frameBytes: Int, brightnessMilli: Int): Int =
        videoReadFrame!!.invoke(handle, MemorySegment.ofBuffer(dst), frameBytes.toLong(), brightnessMilli) as Int

    /**
     * Blocking read of the next frame into [dst] as RGBA32 with brightness pre-applied.
     * Available only when [rgbaFramesEnabled] is true.
     */
    fun videoReadFrameRgba(handle: Long, dst: ByteBuffer, frameBytes: Int, brightnessMilli: Int): Int =
        videoReadFrameRgbaHandle!!.invoke(handle, MemorySegment.ofBuffer(dst), frameBytes.toLong(), brightnessMilli) as Int

    /** Returns the FFmpeg stderr captured so far for [handle] (capped at 128 KiB). */
    fun videoStderr(handle: Long): String {
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(STDERR_CAP)
            val n = videoStderr!!.invoke(handle, seg, STDERR_CAP) as Int
            if (n <= 0) return ""
            val bytes = ByteArray(n)
            MemorySegment.copy(seg, 0L, MemorySegment.ofArray(bytes), 0L, n.toLong())
            return String(bytes, Charsets.UTF_8)
        }
    }

    /** Waits up to [waitMillis] for FFmpeg to exit; returns the exit code or -1 (force-killing it on timeout). */
    fun videoExitCode(handle: Long, waitMillis: Int): Int =
        videoExitCode!!.invoke(handle, waitMillis) as Int

    /** Kills the FFmpeg process, unblocking a reader stuck in [videoReadFrame]. */
    fun videoKill(handle: Long) {
        videoKill!!.invoke(handle)
    }

    /** Frees the native session. Must not race a [videoReadFrame] on the same handle. */
    fun videoClose(handle: Long) {
        videoClose!!.invoke(handle)
    }

    /**
     * Performs the one-time gate checks and binds all downcall handles.
     * Any failure leaves the bridge unavailable; this must never throw past [isAvailable].
     */
    private fun init(): Boolean {
        if (!System.getProperty("dreamdisplays.native", "true").toBoolean()) {
            logger.info("Native pipeline disabled via -Ddreamdisplays.native=false.")
            return false
        }
        if (Runtime.version().feature() < 22) {
            logger.info("Native pipeline requires Java 22+ (running ${Runtime.version().feature()}); using JVM pipeline.")
            return false
        }
        val lib = locateLibrary() ?: run {
            logger.info("Native library not found; using JVM pipeline.")
            return false
        }
        return try {
            val linker = Linker.nativeLinker()
            // Global arena: the library stays loaded for the lifetime of the process.
            val lookup = SymbolLookup.libraryLookup(lib.toPath(), Arena.global())

            fun bind(name: String, desc: FunctionDescriptor): MethodHandle =
                linker.downcallHandle(
                    lookup.find(name).orElseThrow { IllegalStateException("Symbol $name missing") },
                    desc,
                )

            fun bindOptional(name: String, desc: FunctionDescriptor): MethodHandle? =
                lookup.find(name).map { linker.downcallHandle(it, desc) }.orElse(null)

            val long = ValueLayout.JAVA_LONG
            val int = ValueLayout.JAVA_INT
            val addr = ValueLayout.ADDRESS

            abiVersion = bind("dd_abi_version", FunctionDescriptor.of(int))
            videoOpen = bind("dd_video_open", FunctionDescriptor.of(long, addr, long, int, int, int))
            videoReadFrame = bind("dd_video_read_frame", FunctionDescriptor.of(int, long, addr, long, int))
            videoReadFrameRgbaHandle = bindOptional("dd_video_read_frame_rgba", FunctionDescriptor.of(int, long, addr, long, int))
            videoStderr = bind("dd_video_stderr", FunctionDescriptor.of(int, long, addr, long))
            videoExitCode = bind("dd_video_exit_code", FunctionDescriptor.of(int, long, int))
            videoKill = bind("dd_video_kill", FunctionDescriptor.ofVoid(long))
            videoClose = bind("dd_video_close", FunctionDescriptor.ofVoid(long))

            val abi = abiVersion!!.invoke() as Int
            if (abi != ABI_VERSION) {
                logger.warn("Native library ABI mismatch: found $abi, expected $ABI_VERSION; using JVM pipeline.")
                return false
            }
            val rgba = System.getProperty("dreamdisplays.native.rgba", "true").toBoolean()
                    && videoReadFrameRgbaHandle != null
            logger.info("Native media pipeline active: $lib (nv12=$nv12Enabled, rgba=$rgba).")
            true
        } catch (t: Throwable) {
            // UnsupportedOperationException on Java 21 preview gates, UnsatisfiedLinkError, etc.
            logger.warn("Native pipeline unavailable (${t.javaClass.simpleName}: ${t.message}); using JVM pipeline.")
            false
        }
    }

    /**
     * Locates the platform library: explicit `-Ddreamdisplays.native.path`, then the
     * game-dir cache, then a bundled jar resource extracted into that cache.
     */
    private fun locateLibrary(): File? {
        System.getProperty("dreamdisplays.native.path")?.let { p ->
            val f = File(p)
            if (f.isFile) return f
            logger.warn("dreamdisplays.native.path=$p does not exist.")
        }

        val libName = System.mapLibraryName(LIB_BASE_NAME)
        val cached = File("$CACHE_ROOT/${platformKey()}/$libName")

        val resource = "/dreamdisplays-natives/${platformKey()}/$libName"
        javaClass.getResourceAsStream(resource)?.use { input ->
            val bytes = input.readBytes()
            if (cached.isFile && cached.length() == bytes.size.toLong()
                && runCatching { cached.readBytes().contentEquals(bytes) }.getOrDefault(false)
            ) {
                return cached
            }
            val parent = cached.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) return null
            val tmp = File(parent, "$libName.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(cached)) {
                tmp.delete()
                return null
            }
            return cached
        }
        if (cached.isFile && cached.length() > 0) return cached
        return null
    }

    /** Platform key matching the layout used by the FFmpeg binary cache. */
    private fun platformKey(): String = when {
        OsInfo.isWindows -> if (OsInfo.isArm) "windows-aarch64" else "windows-x64"
        OsInfo.isMac -> if (OsInfo.isArm) "macos-aarch64" else "macos-x64"
        else -> if (OsInfo.isArm) "linux-aarch64" else "linux-x64"
    }
}
