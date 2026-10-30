package com.dreamdisplays.platform.client.capabilities

import com.dreamdisplays.api.media.stream.SupportedCodec
import com.dreamdisplays.core.render.RenderBackend
import com.dreamdisplays.core.render.ShaderBackend
import com.dreamdisplays.core.render.TextureUploadPath
import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.media.player.process.HwAccelBackend
import com.dreamdisplays.platform.client.managers.WarmParkPolicy
import com.dreamdisplays.platform.client.render.AsyncTextureUploader
import com.dreamdisplays.platform.client.render.RenderBackendCompat
import com.dreamdisplays.platform.client.render.ShaderPackCompat
import com.dreamdisplays.platform.client.ui.VideoPopoutWindow
import java.time.Instant
import java.time.ZoneId

private const val PROBING = "probing"

/**
 * Probes the running client for [ClientHello] capabilities. Popout support comes from the `GLFW`
 * shared-context check in [VideoPopoutWindow], hardware decode from the per-OS
 * [HwAccelBackend] default, and codec support from what the `FFmpeg` pipeline decodes.
 */
object MinecraftClientCapabilityDetector : ClientCapabilityDetector {
    override val maxTextureSize: Int = 8192

    override val supportsPopout: Boolean get() = VideoPopoutWindow.isAvailable

    override val supportsHardwareDecode: Boolean get() = HwAccelBackend.detectDefault() != HwAccelBackend.NONE

    override val supportedCodecs: List<SupportedCodec> = SupportedCodec.advertised

    override fun detect(): ClientHello {
        val hwAccel = HwAccelBackend.detectDefault()
        val probed = safeBool { NativeMedia.probesFinished }
        val nativeAvailable = probed && safeBool { NativeMedia.isAvailable }
        val lavAvailable = probed && safeBool { NativeMedia.lavAvailable }
        val memory = ClientMemoryProbe.detected
        return ClientHello(
            supportsPopout = supportsPopout,
            supportsHardwareDecode = hwAccel != HwAccelBackend.NONE,
            supportsHighResolution = maxTextureSize >= 4096,
            maxTextureSize = maxTextureSize,
            supportedCodecs = supportedCodecs.map { it.wire },
            supportsPip = true,
            supportsAudio = true,
            renderBackend = safeString(RenderBackend.UNKNOWN.wire) { RenderBackendCompat.backend().wire },
            shaderBackend = safeString(ShaderBackend.UNKNOWN.wire) { ShaderPackCompat.shaderBackend().wire },
            textureUploadPath = safeString(TextureUploadPath.UNKNOWN.wire) { RenderBackendCompat.textureUploadPath().wire },
            hwAccelBackend = hwAccel.name.lowercase(),
            nativeBackendAvailable = nativeAvailable,
            nativeRgbaFramesEnabled = nativeAvailable,
            nativeYuvGpuEnabled = nativeAvailable,
            lavAvailable = lavAvailable,
            lavInProcessEnabled = lavAvailable,
            nativeUnavailableReason = when {
                nativeAvailable -> ""
                !probed -> PROBING
                else -> safeString("unknown") { NativeMedia.unavailableReason.ifBlank { "unknown" } }
            },
            lavUnavailableReason = when {
                lavAvailable -> ""
                !probed -> PROBING
                else -> safeString("unknown") { NativeMedia.lavUnavailableReason.ifBlank { "unknown" } }
            },
            systemRamMb = memory.systemRamMb,
            maxJvmMemoryMb = memory.maxJvmMemoryMb,
            dedicatedVramMb = memory.dedicatedVramMb,
            warmDisplayLimit = WarmParkPolicy.maxFullWarmDisplays,
            timeZoneOffsetMinutes = safeInt { ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60 },
        )
    }

    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)

    private fun safeBool(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)

    private fun safeString(default: String, block: () -> String): String =
        runCatching(block).getOrDefault(default).ifBlank { default }
}
