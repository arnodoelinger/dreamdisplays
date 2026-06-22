package com.dreamdisplays.api.render

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/** Opaque integer texture id owned by the platform renderer. */
@DreamDisplaysUnstableApi
@JvmInline
value class TextureHandle(val id: Int) {
    /** True when [id] points at a platform-owned texture. */
    val isValid: Boolean get() = id > 0

    companion object {
        /** Sentinel for a missing or released texture. */
        val INVALID = TextureHandle(-1)
    }
}
