package com.dreamdisplays.render.api

@JvmInline
value class TextureHandle(val id: Int) {
    val isValid: Boolean get() = id > 0

    companion object {
        val INVALID = TextureHandle(-1)
    }
}
