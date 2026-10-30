package com.dreamdisplays.core.render

import com.dreamdisplays.api.util.WireEnum
import com.dreamdisplays.api.util.wireEnumValueOf

/** Shader-pack integration currently controlling the world render pass. */
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
        fun fromWire(raw: String?): ShaderBackend = wireEnumValueOf(raw, UNKNOWN, entries)
    }
}
