package com.dreamdisplays.core.render

import com.dreamdisplays.api.util.WireEnum
import com.dreamdisplays.api.util.wireEnumValueOf

/** Render backend. */
enum class RenderBackend(override val wire: String) : WireEnum {
    /** `OpenGL` renderer. */
    OPENGL("opengl"),

    /** `Vulkan` renderer. */
    VULKAN("vulkan"),

    /**
     * `Vulkan` renderer replacement provided by `VulkanMod`.
     *
     * @see <a href="https://modrinth.com/mod/vulkanmod">VulkanMod</a>
     */
    VULKAN_MOD("vulkanmod"),

    /** Other renderer. */
    OTHER("other"),

    /** Unknown renderer. */
    UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String?): RenderBackend = wireEnumValueOf(raw, UNKNOWN, entries)
    }
}
