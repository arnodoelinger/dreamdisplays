package com.dreamdisplays.api.platform.identity

import com.dreamdisplays.api.util.WireEnum
import com.dreamdisplays.api.util.wireEnumValueOf

/**
 * Stable ids for known platform adapters.
 *
 * @since 1.8.x
 */
enum class PlatformId(override val wire: String) : WireEnum {
    /** `Fabric` mod loader. */
    FABRIC("fabric"),

    /** `NeoForge` mod loader. */
    NEOFORGE("neoforge"),

    /** `Forge` mod loader (Minecraft 1.20.1). */
    FORGE("forge"),

    /** `Paper` server. */
    PAPER("paper"),

    /** Unknown platform. */
    UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String?): PlatformId = wireEnumValueOf(raw, UNKNOWN, entries)
    }
}
