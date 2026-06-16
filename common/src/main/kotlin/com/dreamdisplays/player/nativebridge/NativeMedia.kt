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
    const val READ_UNSUPPORTED = -4
    const val LAV_NO_PTS_NANOS = Long.MIN_VALUE

    private const val LIB_BASE_NAME = "dreamdisplays_native"
    private const val LAV_BASE_NAME = "dreamdisplays_lav"

    /** Must match `LAV_ABI_VERSION` in `native/lav/src/lib.rs`. */
    private const val LAV_ABI_VERSION = 4
    private const val LAV_SURFACE_ABI_VERSION = 1
    private const val LAV_SURFACE_DESC_BYTES = 80L
    private const val CACHE_ROOT = "./dreamdisplays/native"
    private const val STDERR_CAP = 128L * 1024L

    const val LAV_SURFACE_PLATFORM_MACOS_IOSURFACE = 1
    const val LAV_SURFACE_FORMAT_NV12_8 = 1
    const val LAV_SURFACE_FORMAT_P010_10 = 2
    const val GL_TEXTURE_RECTANGLE = 0x84F5

    /** When true (default) the native pipe carries NV12 instead of RGB24, halving pipe traffic. */
    val nv12Enabled: Boolean = System.getProperty("dreamdisplays.native.nv12", "true").toBoolean()

    private var abiVersion: MethodHandle? = null
    private var videoOpen: MethodHandle? = null
    private var videoReadFrame: MethodHandle? = null
    private var videoReadFrameRgbaHandle: MethodHandle? = null
    private var videoReadFrameI420Handle: MethodHandle? = null
    private var i420ToRgbaHandle: MethodHandle? = null

    private var lavOpenHandle: MethodHandle? = null
    private var lavOpenReplayHandle: MethodHandle? = null
    private var lavReadFrameHandle: MethodHandle? = null
    private var lavReadFramePtsHandle: MethodHandle? = null
    private var lavErrorHandle: MethodHandle? = null
    private var lavKillHandle: MethodHandle? = null
    private var lavCloseHandle: MethodHandle? = null
    private var lavEnableCacheHandle: MethodHandle? = null
    private var lavRingSnapshotHandle: MethodHandle? = null
    private var lavRingSnapshotAtHandle: MethodHandle? = null
    private var lavReadSurfaceHandle: MethodHandle? = null
    private var lavBindSurfacePlaneGlHandle: MethodHandle? = null
    private var lavReleaseSurfaceHandle: MethodHandle? = null
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

    /**
     * Keeps frames as raw I420 planes all the way to the GPU: the YUV -> RGB conversion and
     * brightness both move into the fragment shader, removing the per-pixel CPU pass entirely.
     * Requires the NV12 pipe and a native library exporting the I420 entry points.
     */
    val yuvGpuEnabled: Boolean
        get() = isAvailable
                && nv12Enabled
                && System.getProperty("dreamdisplays.native.yuvgpu", "true").toBoolean()
                && videoReadFrameI420Handle != null
                && i420ToRgbaHandle != null

    /**
     * Experimental in-process decode: libavformat/libavcodec run inside `dreamdisplays_lav`
     * instead of a separate FFmpeg process, removing the process spawn and the stdout pipe.
     * Requires the separate lav library (which links the system FFmpeg shared libraries)
     * and the planar GPU path. Disable with `-Ddreamdisplays.native.libav=false`.
     */
    val lavInProcessEnabled: Boolean
        get() = yuvGpuEnabled
                && System.getProperty("dreamdisplays.native.libav", "true").toBoolean()
                && lavAvailable

    /**
     * True when the optional LAV surface ABI is present and explicitly enabled. This is the
     * zero-copy hardware-surface contract; it is gated separately because it needs a matching
     * platform renderer (NV12/rectangle on macOS, WGL/EGL interop on other platforms).
     */
    val lavZeroCopyEnabled: Boolean
        get() = lavInProcessEnabled
                && lavSurfaceInteropAvailable
                && System.getProperty("dreamdisplays.native.libav.zeroCopy", "false").toBoolean()

    /** True once the optional `dreamdisplays_lav` library has been located, loaded, and bound. */
    val lavAvailable: Boolean by lazy { isAvailable && runCatching { initLav() }.getOrDefault(false) }

    /** True when `dreamdisplays_lav` exports the additive hardware-surface ABI. */
    val lavSurfaceInteropAvailable: Boolean
        get() = lavAvailable
                && lavReadSurfaceHandle != null
                && lavBindSurfacePlaneGlHandle != null
                && lavReleaseSurfaceHandle != null

    /** True when the LAV packet-cache and replay ABI is available. */
    val lavReplayCacheAvailable: Boolean
        get() = lavAvailable
                && lavOpenReplayHandle != null
                && lavEnableCacheHandle != null
                && lavRingSnapshotAtHandle != null

    data class LavSurfaceDescriptor(
        val handle: Long,
        val platform: Int,
        val format: Int,
        val width: Int,
        val height: Int,
        val planeCount: Int,
        val textureTarget: Int,
        val planeWidth: IntArray,
        val planeHeight: IntArray,
    )

    data class LavFrameReadResult(val code: Int, val ptsNanos: Long)

    data class LavSurfaceReadResult(val code: Int, val descriptor: LavSurfaceDescriptor?)

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

    /**
     * Blocking read of the next frame into [dst] as raw I420 planes (Y, then U, then V) with
     * no conversion or brightness. Available only when [yuvGpuEnabled] is true.
     */
    fun videoReadFrameI420(handle: Long, dst: ByteBuffer, frameBytes: Int): Int =
        videoReadFrameI420Handle!!.invoke(handle, MemorySegment.ofBuffer(dst), frameBytes.toLong()) as Int

    /**
     * Converts an I420 frame in [src] into RGBA32 in [dst] (alpha 255, no brightness).
     * Both must be direct buffers. Used to feed the popout window in GPU-YUV mode.
     */
    fun i420ToRgba(src: ByteBuffer, srcBytes: Int, dst: ByteBuffer, w: Int, h: Int): Int =
        i420ToRgbaHandle!!.invoke(
            MemorySegment.ofBuffer(src), srcBytes.toLong(),
            MemorySegment.ofBuffer(dst), dst.capacity().toLong(), w, h,
        ) as Int

    /**
     * Opens an in-process decode session for [url] at the target [w] x [h], starting at
     * [startMicros]. [hwAccelCode] is a stable [com.dreamdisplays.player.process.HwAccelBackend.lavCode].
     * The native side validates that the decoder actually supports the requested hardware path
     * and falls back to software when it cannot be opened.
     * Returns an opaque handle, or 0 on failure.
     */
    fun lavOpen(url: String, w: Int, h: Int, startMicros: Long, hwAccelCode: Int): Long {
        val bytes = url.toByteArray(Charsets.UTF_8)
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, seg, 0L, bytes.size.toLong())
            return lavOpenHandle!!.invoke(seg, bytes.size.toLong(), w, h, startMicros, hwAccelCode) as Long
        }
    }

    /** Opens a native replay decode session from a serialized packet-ring [blob]. */
    fun lavOpenReplay(blob: ByteArray, w: Int, h: Int, resumeNanos: Long): Long {
        val openReplay = lavOpenReplayHandle ?: return 0L
        if (blob.isEmpty()) return 0L
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(blob.size.toLong())
            MemorySegment.copy(MemorySegment.ofArray(blob), 0L, seg, 0L, blob.size.toLong())
            return openReplay.invoke(seg, blob.size.toLong(), w, h, resumeNanos) as Long
        }
    }

    /** Enables the native rolling packet cache for a live LAV [handle]. */
    fun lavEnableCache(handle: Long, windowMs: Long, maxBytes: Long): Boolean {
        val enable = lavEnableCacheHandle ?: return false
        if (windowMs <= 0 || maxBytes <= 0) return false
        return (enable.invoke(handle, windowMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), maxBytes) as Int) == READ_OK
    }

    /** Captures the live LAV packet-ring snapshot for [handle], or null when no data is ready. */
    fun lavRingSnapshot(handle: Long, positionNanos: Long): ByteArray? {
        val snapshot = lavRingSnapshotAtHandle ?: return null
        var size = snapshot.invoke(handle, positionNanos, MemorySegment.NULL, 0L) as Int
        if (size <= 0) return null
        repeat(3) {
            Arena.ofConfined().use { arena ->
                val seg = arena.allocate(size.toLong())
                val written = snapshot.invoke(handle, positionNanos, seg, size.toLong()) as Int
                if (written <= 0) return null
                if (written <= size) {
                    val out = ByteArray(written)
                    MemorySegment.copy(seg, 0L, MemorySegment.ofArray(out), 0L, written.toLong())
                    return out
                }
                size = written
            }
        }
        return null
    }

    /** Blocking in-process decode of the next frame into [dst] as raw I420 planes. */
    fun lavReadFrameI420(handle: Long, dst: ByteBuffer, frameBytes: Int): Int =
        lavReadFrameHandle!!.invoke(handle, MemorySegment.ofBuffer(dst), frameBytes.toLong()) as Int

    /** Blocking in-process decode of the next I420 frame plus its normalized PTS, when exported. */
    fun lavReadFrameI420WithPts(handle: Long, dst: ByteBuffer, frameBytes: Int): LavFrameReadResult {
        val readWithPts = lavReadFramePtsHandle
        if (readWithPts == null) {
            return LavFrameReadResult(lavReadFrameI420(handle, dst, frameBytes), LAV_NO_PTS_NANOS)
        }
        Arena.ofConfined().use { arena ->
            val pts = arena.allocate(ValueLayout.JAVA_LONG)
            pts.set(ValueLayout.JAVA_LONG, 0L, LAV_NO_PTS_NANOS)
            val rc = readWithPts.invoke(handle, MemorySegment.ofBuffer(dst), frameBytes.toLong(), pts) as Int
            return LavFrameReadResult(rc, pts.get(ValueLayout.JAVA_LONG, 0L))
        }
    }

    /**
     * Blocking in-process decode of the next frame as a retained hardware surface.
     *
     * On success, the descriptor's [LavSurfaceDescriptor.handle] must be released via
     * [lavReleaseSurface]. Plane import must run on the render thread with the GL context current.
     */
    fun lavReadSurface(handle: Long): LavSurfaceReadResult {
        val readSurface = lavReadSurfaceHandle ?: return LavSurfaceReadResult(READ_UNSUPPORTED, null)
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(LAV_SURFACE_DESC_BYTES)
            val rc = readSurface.invoke(handle, seg) as Int
            return LavSurfaceReadResult(
                rc,
                if (rc == READ_OK) readLavSurfaceDescriptor(seg) else null,
            )
        }
    }

    /** Imports one retained surface plane into an existing OpenGL texture object. */
    fun lavBindSurfacePlaneGl(surfaceHandle: Long, plane: Int, textureId: Int): Int =
        lavBindSurfacePlaneGlHandle?.invoke(surfaceHandle, plane, textureId) as? Int ?: READ_UNSUPPORTED

    /** Releases a retained hardware surface returned by [lavReadSurface]. */
    fun lavReleaseSurface(surfaceHandle: Long) {
        lavReleaseSurfaceHandle?.invoke(surfaceHandle)
    }

    /** Returns the in-process session's last error description, or an empty string. */
    fun lavError(handle: Long): String {
        Arena.ofConfined().use { arena ->
            val seg = arena.allocate(STDERR_CAP)
            val n = lavErrorHandle!!.invoke(handle, seg, STDERR_CAP) as Int
            if (n <= 0) return ""
            val bytes = ByteArray(n)
            MemorySegment.copy(seg, 0L, MemorySegment.ofArray(bytes), 0L, n.toLong())
            return String(bytes, Charsets.UTF_8)
        }
    }

    /** Interrupts the in-process session, unblocking a reader stuck in [lavReadFrameI420]. */
    fun lavKill(handle: Long) {
        lavKillHandle!!.invoke(handle)
    }

    /** Frees the in-process session. Must not race a [lavReadFrameI420] on the same handle. */
    fun lavClose(handle: Long) {
        lavCloseHandle!!.invoke(handle)
    }

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
            videoReadFrameI420Handle = bindOptional("dd_video_read_frame_i420", FunctionDescriptor.of(int, long, addr, long))
            i420ToRgbaHandle = bindOptional("dd_i420_to_rgba", FunctionDescriptor.of(int, addr, long, addr, long, int, int))
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
            val yuvGpu = nv12Enabled
                    && System.getProperty("dreamdisplays.native.yuvgpu", "true").toBoolean()
                    && videoReadFrameI420Handle != null && i420ToRgbaHandle != null
            logger.info("Native media pipeline active: $lib (nv12=$nv12Enabled, rgba=$rgba, yuvGpu=$yuvGpu).")
            true
        } catch (t: Throwable) {
            // UnsupportedOperationException on Java 21 preview gates, UnsatisfiedLinkError, etc.
            logger.warn("Native pipeline unavailable (${t.javaClass.simpleName}: ${t.message}); using JVM pipeline.")
            false
        }
    }

    /**
     * Binds the optional in-process libav library (`dreamdisplays_lav`). It links FFmpeg
     * shared libraries, so loading can legitimately fail when they are neither bundled beside
     * the cdylib nor installed system-wide; any failure just leaves the in-process path unavailable.
     */
    private fun initLav(): Boolean {
        val lib = locateLibrary(LAV_BASE_NAME) ?: run {
            logger.info("In-process libav library not found; in-process decode unavailable.")
            return false
        }
        return try {
            val linker = Linker.nativeLinker()
            LavFfmpeg.ensure(lib.parentFile)
            preloadLavDependencies(lib.parentFile)
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

            val abi = bind("dd_lav_abi_version", FunctionDescriptor.of(int)).invoke() as Int
            if (abi != LAV_ABI_VERSION) {
                logger.warn("In-process libav library ABI mismatch: found $abi, expected $LAV_ABI_VERSION.")
                return false
            }
            lavOpenHandle = bind("dd_lav_open", FunctionDescriptor.of(long, addr, long, int, int, long, int))
            lavOpenReplayHandle = bind("dd_lav_open_replay", FunctionDescriptor.of(long, addr, long, int, int, long))
            lavReadFrameHandle = bind("dd_lav_read_frame_i420", FunctionDescriptor.of(int, long, addr, long))
            lavReadFramePtsHandle = bindOptional("dd_lav_read_frame_i420_pts", FunctionDescriptor.of(int, long, addr, long, addr))
            lavErrorHandle = bind("dd_lav_error", FunctionDescriptor.of(int, long, addr, long))
            lavKillHandle = bind("dd_lav_kill", FunctionDescriptor.ofVoid(long))
            lavCloseHandle = bind("dd_lav_close", FunctionDescriptor.ofVoid(long))
            lavEnableCacheHandle = bind("dd_lav_enable_cache", FunctionDescriptor.of(int, long, int, long))
            lavRingSnapshotHandle = bind("dd_lav_ring_snapshot", FunctionDescriptor.of(int, long, addr, long))
            lavRingSnapshotAtHandle = bind("dd_lav_ring_snapshot_at", FunctionDescriptor.of(int, long, long, addr, long))
            val surfaceAbi = bindOptional("dd_lav_surface_abi_version", FunctionDescriptor.of(int))
            if (surfaceAbi != null && surfaceAbi.invoke() as Int == LAV_SURFACE_ABI_VERSION) {
                lavReadSurfaceHandle = bindOptional("dd_lav_read_surface", FunctionDescriptor.of(int, long, addr))
                lavBindSurfacePlaneGlHandle = bindOptional("dd_lav_bind_surface_plane_gl", FunctionDescriptor.of(int, long, int, int))
                lavReleaseSurfaceHandle = bindOptional("dd_lav_release_surface", FunctionDescriptor.ofVoid(long))
            }
            val surfaceInterop = lavReadSurfaceHandle != null
                    && lavBindSurfacePlaneGlHandle != null
                    && lavReleaseSurfaceHandle != null
            val replayCache = lavOpenReplayHandle != null
                    && lavEnableCacheHandle != null
                    && lavRingSnapshotAtHandle != null
            logger.info("In-process libav backend available: $lib (surfaceInterop=$surfaceInterop, replayCache=$replayCache).")
            true
        } catch (t: Throwable) {
            // Typically UnsatisfiedLinkError when the system FFmpeg dylibs are missing.
            logger.info("In-process libav backend unavailable (${t.javaClass.simpleName}: ${t.message}).")
            false
        }
    }

    /**
     * Portable LAV bundles may place FFmpeg shared libraries next to `dreamdisplays_lav`.
     * Loading them first lets Linux resolve SONAMEs from the extracted cache and helps Windows
     * layouts where DLL search does not include the extracted directory early enough.
     */
    private fun preloadLavDependencies(dir: File?) {
        if (dir == null || !dir.isDirectory) return
        val libraries = dir.listFiles()?.filter { it.isFile && isSharedLibrary(it.name) && !isDreamDisplaysLibrary(it.name) } ?: return
        if (libraries.isEmpty()) return
        val pending = libraries
            .sortedWith(compareBy<File> { sharedLibraryLoadOrder(it.name) }.thenBy { it.name })
            .toMutableList()

        repeat(3) {
            if (pending.isEmpty()) return
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val lib = iterator.next()
                if (runCatching { SymbolLookup.libraryLookup(lib.toPath(), Arena.global()) }.isSuccess) {
                    iterator.remove()
                }
            }
        }

        pending.forEach { lib ->
            logger.debug("Could not preload LAV dependency ${lib.name}.")
        }
    }

    private fun isSharedLibrary(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".dll") || lower.endsWith(".dylib") || lower.contains(".so")
    }

    private fun isDreamDisplaysLibrary(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("dreamdisplays_native") || lower.contains("dreamdisplays_lav")
    }

    private fun sharedLibraryLoadOrder(name: String): Int {
        val ffmpegOrder = ffmpegSharedLibraryOrder(name)
        return if (ffmpegOrder == Int.MAX_VALUE) 0 else 100 + ffmpegOrder
    }

    private fun ffmpegSharedLibraryOrder(name: String): Int {
        val lower = name.lowercase()
        return FFMPEG_SHARED_LIBRARY_ORDER.entries.firstOrNull { lower.contains(it.key) }?.value ?: Int.MAX_VALUE
    }

    private fun readLavSurfaceDescriptor(seg: MemorySegment): LavSurfaceDescriptor {
        val planeWidth = IntArray(4)
        val planeHeight = IntArray(4)
        for (i in 0 until 4) {
            planeWidth[i] = seg.get(ValueLayout.JAVA_INT, 32L + i * 4L)
            planeHeight[i] = seg.get(ValueLayout.JAVA_INT, 48L + i * 4L)
        }
        return LavSurfaceDescriptor(
            handle = seg.get(ValueLayout.JAVA_LONG, 0L),
            platform = seg.get(ValueLayout.JAVA_INT, 8L),
            format = seg.get(ValueLayout.JAVA_INT, 12L),
            width = seg.get(ValueLayout.JAVA_INT, 16L),
            height = seg.get(ValueLayout.JAVA_INT, 20L),
            planeCount = seg.get(ValueLayout.JAVA_INT, 24L),
            textureTarget = seg.get(ValueLayout.JAVA_INT, 28L),
            planeWidth = planeWidth,
            planeHeight = planeHeight,
        )
    }

    /**
     * Locates the platform library: explicit `-Ddreamdisplays.native.path`, then the
     * game-dir cache, then a bundled jar resource extracted into that cache.
     */
    private fun locateLibrary(baseName: String = LIB_BASE_NAME): File? {
        val explicitPathProperty = when (baseName) {
            LIB_BASE_NAME -> "dreamdisplays.native.path"
            LAV_BASE_NAME -> "dreamdisplays.native.lav.path"
            else -> null
        }
        explicitPathProperty?.let { property ->
            System.getProperty(property)?.let { p ->
                val f = File(p)
                if (f.isFile) return f
                logger.warn("$property=$p does not exist.")
            }
        }

        val libName = System.mapLibraryName(baseName)
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

    private val FFMPEG_SHARED_LIBRARY_ORDER = linkedMapOf(
        "avutil" to 0,
        "swresample" to 1,
        "swscale" to 2,
        "avcodec" to 3,
        "avformat" to 4,
        "avfilter" to 5,
        "avdevice" to 6,
    )
}
