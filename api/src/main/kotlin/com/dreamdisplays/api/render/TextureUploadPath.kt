package com.dreamdisplays.api.render

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.util.WireEnum
import com.dreamdisplays.api.util.wireEnumValueOf

/**
 * Texture-upload path selected for the active render backend.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
enum class TextureUploadPath(override val wire: String) : WireEnum {
    /** Direct OpenGL PBO upload path. */
    DIRECT_OPENGL_PBO("direct_opengl_pbo"),

    /** Command encoder upload path. */
    COMMAND_ENCODER("command_encoder"),

    /** No upload path available. */
    UNKNOWN("unknown");

    companion object {
        /** Returns the enum value corresponding to the given wire value, or [UNKNOWN] if not found. */
        fun fromWire(raw: String?): TextureUploadPath = wireEnumValueOf(raw, UNKNOWN)
    }
}
