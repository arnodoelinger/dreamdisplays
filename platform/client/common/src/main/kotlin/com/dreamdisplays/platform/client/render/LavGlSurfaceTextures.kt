package com.dreamdisplays.platform.client.render

import com.dreamdisplays.media.player.nativebridge.NativeMedia
import org.lwjgl.opengl.GL11

/**
 * Render-thread owner for LAV zero-copy surface textures.
 *
 * The native side retains the decoder's hardware frame. This class owns the OpenGL texture
 * objects and asks `dreamdisplays_lav` to import each retained surface plane into them.
 */
internal class LavGlSurfaceTextures : AutoCloseable {
    /** GL texture ids for each imported plane (0 until allocated). */
    private val textureIds = IntArray(MAX_PLANES)

    /** GL texture id of the Y plane. */
    val yTextureId: Int get() = textureIds[0]

    /** GL texture id of the interleaved UV plane. */
    val uvTextureId: Int get() = textureIds[1]

    /**
     * Imports [desc]'s hardware planes into this object's GL texture IDs.
     * Returns a native result code (`NativeMedia.READ_OK` on success).
     */
    fun import(desc: NativeMedia.LavSurfaceDescriptor): Int {
        if (!RenderBackendCompat.canUseDirectOpenGl()) return NativeMedia.READ_UNSUPPORTED
        if (desc.textureTarget != NativeMedia.GL_TEXTURE_RECTANGLE
            || desc.format != NativeMedia.LAV_SURFACE_FORMAT_NV12_8
            || desc.planeCount < 2
        ) {
            return NativeMedia.READ_UNSUPPORTED
        }
        ensureTextures()
        val planes = desc.planeCount.coerceAtMost(MAX_PLANES)
        for (plane in 0 until planes) {
            val rc = NativeMedia.lavBindSurfacePlaneGl(desc.handle, plane, textureIds[plane])
            if (rc != NativeMedia.READ_OK) return rc
        }
        return NativeMedia.READ_OK
    }

    /** Lazily allocates a GL texture id for each plane that doesn't have one yet. */
    private fun ensureTextures() {
        for (i in textureIds.indices) {
            if (textureIds[i] == 0) {
                textureIds[i] = GL11.glGenTextures()
            }
        }
    }

    /** Deletes the GL textures owned by this object. */
    override fun close() {
        for (id in textureIds) {
            if (id != 0) GL11.glDeleteTextures(id)
        }
        textureIds.fill(0)
    }

    private companion object {
        /** NV12 has two planes: Y and interleaved UV. */
        private const val MAX_PLANES = 2
    }
}
