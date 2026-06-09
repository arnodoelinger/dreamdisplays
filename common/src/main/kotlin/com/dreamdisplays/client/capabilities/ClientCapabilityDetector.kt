package com.dreamdisplays.client.capabilities

import com.dreamdisplays.protocol.ClientCapabilities

/**
 * Detects the capabilities of the client device, such as supported codecs, maximum texture size, and whether popout
 * windows are supported.
 *
 * @since 1.8.0
 */
interface ClientCapabilityDetector {
    /** Returns the detected capabilities. */
    fun detect(): ClientCapabilities

    /** True if the client supports popout windows, which allow the display to be rendered in a separate window. */
    val supportsPopout: Boolean

    /** True if the client has a known `FFmpeg` hwaccel backend. */
    val supportsHardwareDecode: Boolean

    /** The maximum texture size supported by the client's GPU. */
    val maxTextureSize: Int

    /** Codecs the `FFmpeg` decode pipeline accepts regardless of hwaccel availability. */
    val supportedCodecs: List<String>
}
