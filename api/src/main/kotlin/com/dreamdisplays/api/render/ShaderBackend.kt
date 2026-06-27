package com.dreamdisplays.api.render

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.util.WireEnum
import com.dreamdisplays.api.util.wireEnumValueOf

/**
 * Shader-pack integration currently controlling the world render pass.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
enum class ShaderBackend(override val wire: String) : WireEnum {
    /** No shader pack integration. */
    NONE("none"),

    /** `Iris` shader pack integration. */
    IRIS("iris"),

    /** `OptiFine` shader pack integration. */
    OPTIFINE("optifine"),

    /** `Canvas` shader pack integration. Legacy project. */
    CANVAS("canvas"),

    /** Unknown shader pack integration. */
    UNKNOWN("unknown");

    companion object {
        /** Returns the enum value corresponding to the given wire value, or [UNKNOWN] if not found. */
        fun fromWire(raw: String?): ShaderBackend = wireEnumValueOf(raw, UNKNOWN)
    }
}
