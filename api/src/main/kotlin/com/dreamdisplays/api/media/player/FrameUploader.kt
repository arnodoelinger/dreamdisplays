package com.dreamdisplays.api.media.player

import com.dreamdisplays.media.FramePixelFormat
import java.nio.ByteBuffer

/**
 * Render-thread sink that uploads decoded frames into GPU textures. One instance is created per
 * decode channel (it may keep persistent GPU upload state, e.g. a PBO ring), and the platform
 * layer supplies the concrete implementation.
 *
 * All methods are called on the render thread.
 */
interface FrameUploader {
    /** True when uploads should proceed (e.g. the game window is not minimized). */
    fun canUpload(): Boolean

    /**
     * Uploads interleaved [src] (rewound) to the single [target] texture, whose dimensions the
     * implementation reads from the texture itself. Returns true when a frame was uploaded.
     */
    fun uploadInterleaved(target: GpuTextureRef, src: ByteBuffer, format: FramePixelFormat): Boolean

    /**
     * Uploads a planar I420 [src] (Y, then U, then V) into the three plane textures. Returns true
     * when a frame was uploaded.
     */
    fun uploadPlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, src: ByteBuffer): Boolean

    /** Releases any GPU upload resources. Called on the render thread at permanent shutdown. */
    fun cleanup()
}

/** Creates a fresh [FrameUploader] for one decode channel. */
fun interface FrameUploaderFactory {
    fun create(): FrameUploader
}
