package com.dreamdisplays.shared.capability

import com.dreamdisplays.api.util.WireEnum
import com.dreamdisplays.api.util.wireEnumValueOfOrNull

/** Server capabilities advertised during negotiation; string tokens stay centralized in this enum. */
enum class ServerFeature(override val wire: String) : WireEnum {
    /** Server supports selecting playback modes. */
    MODES("modes"),

    /** Server supports watch-party sessions. */
    WATCH_PARTY("watch_party"),

    /** Server supports broadcast playback. */
    BROADCAST("broadcast"),

    /**
     * Server can resolve region membership (i.e. `WorldGuard` is installed), so the region access
     * level is a real choice.
     */
    REGION_ACCESS("region_access");

    companion object {
        /** Playback-related features every server implementation supports unconditionally. */
        val playbackFeatures: List<ServerFeature> = [MODES, WATCH_PARTY, BROADCAST]

        /** Playback-related feature tokens for string-based wire protocols. */
        val playbackFeatureWires: List<String> = playbackFeatures.toWire()

        /** Decodes a feature token, or returns `null` when unknown. */
        fun fromWire(raw: String): ServerFeature? = wireEnumValueOfOrNull(raw, entries)
    }
}

/** Converts feature enums to their wire tokens. */
fun Iterable<ServerFeature>.toWire(): List<String> = map { it.wire }
