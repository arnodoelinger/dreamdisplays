package com.dreamdisplays.core.render

/** Opaque integer texture id owned by the platform renderer. */
data class TextureHandle(val id: Int) {
    /** True when [id] points at a platform-owned texture. */
    val isValid: Boolean get() = id > 0
}
