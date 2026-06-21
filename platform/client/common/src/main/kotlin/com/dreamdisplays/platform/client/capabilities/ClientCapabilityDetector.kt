package com.dreamdisplays.platform.client.capabilities

import com.dreamdisplays.protocol.ClientHello

/**
 * Detects the capabilities of the client device, such as supported codecs, maximum texture size, and whether popout
 * windows are supported.
 */
interface ClientCapabilityDetector {
    /** Returns the detected capabilities as a [ClientHello] (without the mod version filled in). */
    fun detect(): ClientHello

    /** True if the client supports popout windows, which allow the display to be rendered in a separate window. */
    val supportsPopout: Boolean

    /** True if the client has a known `FFmpeg` hwaccel backend. */
    val supportsHardwareDecode: Boolean

    /** The maximum texture size supported by the client's GPU. */
    val maxTextureSize: Int

    /** Codecs the `FFmpeg` decode pipeline accepts regardless of hwaccel availability. */
    val supportedCodecs: List<String>
}
