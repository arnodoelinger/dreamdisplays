package com.dreamdisplays.api.render.texture.model

import com.dreamdisplays.api.Unstable

/**
 * Opaque integer texture id owned by the platform renderer.
 *
 * @since 1.8.x
 */
@JvmInline
@Unstable
value class TextureHandle(val id: Int) {
    /** True when [id] points at a platform-owned texture. */
    val isValid: Boolean get() = id > 0

    companion object {
        /** Sentinel for a missing or released texture. */
        val INVALID = TextureHandle(-1)
    }
}
