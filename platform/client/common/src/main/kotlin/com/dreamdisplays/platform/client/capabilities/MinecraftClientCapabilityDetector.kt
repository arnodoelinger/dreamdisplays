package com.dreamdisplays.platform.client.capabilities

import com.dreamdisplays.platform.client.ui.VideoPopoutWindow
import com.dreamdisplays.platform.client.managers.WarmParkPolicy
import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.media.player.process.HwAccelBackend
import com.dreamdisplays.core.protocol.ClientHello
import com.dreamdisplays.platform.client.render.RenderBackendCompat
import com.dreamdisplays.platform.client.render.ShaderPackCompat

/**
 * Probes the running client for [ClientHello] capabilities. Popout support comes from the `GLFW`
 * shared-context check in [VideoPopoutWindow], hardware decode from the per-OS
 * [HwAccelBackend] default, and codec support from what the FFmpeg pipeline decodes.
 */
object MinecraftClientCapabilityDetector : ClientCapabilityDetector {

    /** Matches [com.dreamdisplays.platform.client.render.AsyncTextureUploader]; a GL query needs a current context, which detect-time can't guarantee. */
    override val maxTextureSize: Int = 8192

    /** True when `GLFW` can create the shared-context popout window on this platform. */
    override val supportsPopout: Boolean get() = VideoPopoutWindow.isAvailable

    /** True when the host OS has a known `FFmpeg` hwaccel backend. */
    override val supportsHardwareDecode: Boolean get() = HwAccelBackend.detectDefault() != HwAccelBackend.NONE

    /** Codecs the `FFmpeg` decode pipeline accepts regardless of hwaccel availability. */
    override val supportedCodecs: List<String> = listOf("h264", "hevc", "vp9", "av1")

    /** Snapshots all probes into an immutable [ClientHello] for the handshake. */
    override fun detect(): ClientHello {
        val hwAccel = HwAccelBackend.detectDefault()
        val nativeAvailable = safeBool { NativeMedia.isAvailable }
        val lavAvailable = safeBool { NativeMedia.lavAvailable }
        val memory = ClientMemoryProbe.detected
        return ClientHello(
            supportsPopout = supportsPopout,
            supportsHardwareDecode = hwAccel != HwAccelBackend.NONE,
            supportsHighResolution = maxTextureSize >= 4096,
            maxTextureSize = maxTextureSize,
            supportedCodecs = supportedCodecs,
            supportsPip = true,
            supportsAudio = true,
            renderBackend = safeString("unknown") { RenderBackendCompat.backendName() },
            shaderBackend = safeString("unknown") { ShaderPackCompat.shaderBackendName() },
            textureUploadPath = safeString("unknown") { RenderBackendCompat.textureUploadPath() },
            hwAccelBackend = hwAccel.name.lowercase(),
            nativeBackendAvailable = nativeAvailable,
            nativeRgbaFramesEnabled = nativeAvailable && safeBool { NativeMedia.rgbaFramesEnabled },
            nativeYuvGpuEnabled = nativeAvailable && safeBool { NativeMedia.yuvGpuEnabled },
            lavAvailable = lavAvailable,
            lavInProcessEnabled = lavAvailable && safeBool { NativeMedia.lavInProcessEnabled },
            lavSurfaceInteropAvailable = lavAvailable && safeBool { NativeMedia.lavSurfaceInteropAvailable },
            lavZeroCopyEnabled = lavAvailable && safeBool { NativeMedia.lavZeroCopyEnabled },
            systemRamMb = memory.systemRamMb,
            maxJvmMemoryMb = memory.maxJvmMemoryMb,
            dedicatedVramMb = memory.dedicatedVramMb,
            warmDisplayLimit = WarmParkPolicy.maxFullWarmDisplays,
        )
    }

    /** Runs [block] and returns `false` on any exception. */
    private fun safeBool(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)

    /** Runs [block] and returns the empty string on any exception. */
    private fun safeString(default: String, block: () -> String): String = runCatching(block).getOrDefault(default).ifBlank { default }
}
