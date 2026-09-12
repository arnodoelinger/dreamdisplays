package com.dreamdisplays.api.media.stream.model

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.util.WireEnum
import com.dreamdisplays.api.util.wireEnumValueOf

/**
 * Video codecs the media pipeline recognizes and can advertise to servers.
 *
 * @since 1.8.x
 */
@Unstable
enum class SupportedCodec(override val wire: String, private vararg val prefixes: String) : WireEnum {
    /** H.264 / AVC video. */
    H264("h264", "avc", "h264"),

    /** HEVC / H.265 video. */
    HEVC("hevc", "hvc", "hev", "h265"),

    /** VP9 video. */
    VP9("vp9", "vp9", "vp09"),

    /** AV1 video. */
    AV1("av1", "av01", "av1"),

    /** Unknown or unsupported codec. */
    UNKNOWN("unknown");

    companion object {
        /** Codecs advertised by the default client capability detector. */
        val advertised: List<SupportedCodec> = listOf(H264, HEVC, VP9, AV1)

        /** Returns the enum value corresponding to the given wire value, or [UNKNOWN] if not found. */
        fun fromWire(raw: String?): SupportedCodec = wireEnumValueOf(raw, UNKNOWN)

        /** Returns the enum value corresponding to the codec name, or [UNKNOWN] if not found. */
        fun fromCodecName(raw: String?): SupportedCodec {
            val codec = raw?.trim()?.lowercase() ?: return UNKNOWN
            return entries.firstOrNull { candidate ->
                candidate !== UNKNOWN && candidate.prefixes.any { codec.startsWith(it) }
            } ?: UNKNOWN
        }
    }
}
