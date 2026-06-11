package com.dreamdisplays.client.capabilities

import com.dreamdisplays.client.ui.VideoPopoutWindow
import com.dreamdisplays.player.process.HwAccelBackend
import com.dreamdisplays.protocol.ClientCapabilities

/**
 * Probes the running client for [ClientCapabilities]. Popout support comes from the `GLFW`
 * shared-context check in [VideoPopoutWindow], hardware decode from the per-OS
 * [HwAccelBackend] default, and codec support from what the FFmpeg pipeline decodes.
 *
 * @since 1.8.0
 */
object MinecraftClientCapabilityDetector : ClientCapabilityDetector {

    /** Matches [com.dreamdisplays.render.AsyncTextureUploader]; a GL query needs a current context, which detect-time can't guarantee. */
    override val maxTextureSize: Int = 8192

    /** True when `GLFW` can create the shared-context popout window on this platform. */
    override val supportsPopout: Boolean
        get() = VideoPopoutWindow.isAvailable

    /** True when the host OS has a known `FFmpeg` hwaccel backend. */
    override val supportsHardwareDecode: Boolean
        get() = HwAccelBackend.detectDefault() != HwAccelBackend.NONE

    /** Codecs the `FFmpeg` decode pipeline accepts regardless of hwaccel availability. */
    override val supportedCodecs: List<String> = listOf("h264", "hevc", "vp9", "av1")

    /** Snapshots all probes into an immutable [ClientCapabilities] for the handshake. */
    override fun detect(): ClientCapabilities = ClientCapabilities(
        supportsPopout = supportsPopout,
        supportsHardwareDecode = supportsHardwareDecode,
        supportsHighResolution = maxTextureSize >= 4096,
        maxTextureSize = maxTextureSize,
        supportedCodecs = supportedCodecs,
        supportsPip = true,
        supportsAudio = true,
    )
}
