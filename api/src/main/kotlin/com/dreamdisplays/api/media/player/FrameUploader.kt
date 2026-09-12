package com.dreamdisplays.api.media.player

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.model.FramePixelFormat
import java.nio.ByteBuffer

/**
 * Render-thread sink that uploads decoded frames into GPU textures (one per decode channel).
 *
 * @since 1.8.x
 */
@Unstable
interface FrameUploader {
    /** True when uploads should proceed (e.g. the game window is not minimized). */
    fun canUpload(): Boolean

    /** Uploads interleaved [src] to [target]; returns true when successful. */
    fun uploadInterleaved(target: GpuTextureRef, src: ByteBuffer, format: FramePixelFormat): Boolean

    /** Uploads planar I420 [src] (Y, U, V) to three plane textures; returns true when successful. */
    fun uploadPlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, src: ByteBuffer): Boolean

    /** Releases any GPU upload resources. Called on the render thread at permanent shutdown. */
    fun cleanup()
}

/** Creates a fresh [FrameUploader] for one decode channel. */
@Unstable
fun interface FrameUploaderFactory {
    fun create(): FrameUploader
}
