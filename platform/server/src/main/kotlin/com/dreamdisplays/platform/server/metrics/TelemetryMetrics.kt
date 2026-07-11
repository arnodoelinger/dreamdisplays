package com.dreamdisplays.platform.server.metrics

import com.dreamdisplays.core.protocol.ClientHello
import com.dreamdisplays.api.protocol.ProtocolVersion
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.storage.StorageBackend
import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bstats.bukkit.Metrics
import org.bstats.charts.AdvancedPie
import org.bstats.charts.SimplePie
import org.bstats.charts.SingleLineChart
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked

/** Registers bStats charts for protocol, client render / media capabilities, and server display usage. */
@PaperOnly
@NullMarked
object TelemetryMetrics {
    /** Registers all bStats charts. */
    fun register(plugin: PaperServer, metrics: Metrics) {
        metrics.addCustomChart(SimplePie("server_protocol_current") { ProtocolVersion.CURRENT.toString() })
        metrics.addCustomChart(SimplePie("storage_backend") {
            StorageBackend.fromConfig(PaperServer.config.storage.type).metricToken
        })
        metrics.addCustomChart(SimplePie("reports_enabled") {
            if (PaperServer.config.settings.webhookUrl.isNotBlank()) "enabled" else "disabled"
        })
        metrics.addCustomChart(SimplePie("max_display_area_bucket") {
            bucketArea(PaperServer.config.settings.maxWidth * PaperServer.config.settings.maxHeight)
        })
        metrics.addCustomChart(SingleLineChart("display_count") { DisplayManager.getDisplays().size })
        metrics.addCustomChart(AdvancedPie("display_state") { displayState() })

        metrics.addCustomChart(AdvancedPie("client_protocols") { protocolDistribution(plugin) })
        metrics.addCustomChart(AdvancedPie("client_protocol_versions") { helloDistribution(plugin) { "v2_p${it.protocolVersion}" } })
        metrics.addCustomChart(AdvancedPie("client_mod_versions") { helloDistribution(plugin) { it.modVersion.ifBlank { "unknown" } } })
        metrics.addCustomChart(AdvancedPie("client_render_backends") { helloDistribution(plugin) { it.renderBackend.ifBlank { "unknown" } } })
        metrics.addCustomChart(AdvancedPie("client_shader_backends") { helloDistribution(plugin) { it.shaderBackend.ifBlank { "unknown" } } })
        metrics.addCustomChart(AdvancedPie("client_texture_upload_paths") { helloDistribution(plugin) { it.textureUploadPath.ifBlank { "unknown" } } })
        metrics.addCustomChart(AdvancedPie("client_hwaccel_backends") { helloDistribution(plugin) { it.hwAccelBackend.ifBlank { "unknown" } } })
        metrics.addCustomChart(AdvancedPie("client_native_backend") {
            helloDistribution(plugin) { if (it.nativeBackendAvailable) "available" else "unavailable" }
        })
        metrics.addCustomChart(AdvancedPie("client_video_frame_path") {
            helloDistribution(plugin) { videoFramePath(it) }
        })
        metrics.addCustomChart(AdvancedPie("client_lav_status") {
            helloDistribution(plugin) { lavStatus(it) }
        })
        metrics.addCustomChart(AdvancedPie("client_native_unavailable_reason") {
            helloDistribution(plugin) {
                if (it.nativeBackendAvailable) "available" else it.nativeUnavailableReason.ifBlank { "unknown" }
            }
        })
        metrics.addCustomChart(AdvancedPie("client_lav_unavailable_reason") {
            helloDistribution(plugin) {
                if (it.lavAvailable) "available" else it.lavUnavailableReason.ifBlank { "unknown" }
            }
        })
        metrics.addCustomChart(AdvancedPie("client_capabilities") {
            capabilityDistribution(plugin)
        })
    }

    /** Returns a list of online players. */
    private fun onlinePlayers(plugin: PaperServer): List<Player> =
        runCatching { plugin.server.onlinePlayers.toList() }.getOrDefault(emptyList())

    /** Returns a list of online player's client hello messages. */
    private fun onlineHellos(plugin: PaperServer): List<ClientHello> {
        val onlineIds = onlinePlayers(plugin).mapTo(HashSet()) { it.uniqueId }
        if (onlineIds.isEmpty()) return emptyList()
        return V2PlayerTracker.snapshot()
            .filterKeys { it in onlineIds }
            .values
            .toList()
    }

    /** Returns a map of online player's protocol version. */
    private fun protocolDistribution(plugin: PaperServer): Map<String, Int> {
        val online = onlinePlayers(plugin)
        if (online.isEmpty()) return emptyMap()
        val snapshot = V2PlayerTracker.snapshot()
        val counts = linkedMapOf<String, Int>()
        for (player in online) {
            val hello = snapshot[player.uniqueId]
            val key = if (hello == null) "v1_legacy" else "v2_p${hello.protocolVersion}"
            counts.increment(key)
        }
        return counts
    }

    /** Returns a map of online player's client hello message. */
    private fun helloDistribution(plugin: PaperServer, keyOf: (ClientHello) -> String): Map<String, Int> =
        onlineHellos(plugin).countBy(keyOf)

    /** Returns a map of online player's client capability. */
    private fun capabilityDistribution(plugin: PaperServer): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        for (hello in onlineHellos(plugin)) {
            if (hello.supportsPopout) counts.increment("popout")
            if (hello.supportsPip) counts.increment("pip")
            if (hello.supportsAudio) counts.increment("audio")
            if (hello.supportsHardwareDecode) counts.increment("hardware_decode")
            if (hello.supportsHighResolution) counts.increment("high_resolution")
        }
        return counts
    }

    /** Returns a map of display state. */
    private fun displayState(): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        for (display in DisplayManager.getDisplays()) {
            counts.increment(if (display.url.isBlank()) "empty" else "has_video")
            counts.increment(if (display.isSync) "sync" else "local")
            counts.increment(if (display.isLocked) "locked" else "unlocked")
            counts.increment(bucketArea(display.width * display.height))
        }
        return counts
    }

    /** Returns a map of client render backend. */
    private fun videoFramePath(hello: ClientHello): String = when {
        hello.lavZeroCopyEnabled -> "lav_zero_copy"
        hello.lavInProcessEnabled -> "lav_in_process_i420"
        hello.nativeYuvGpuEnabled -> "native_gpu_yuv"
        hello.nativeRgbaFramesEnabled -> "native_rgba"
        hello.nativeBackendAvailable -> "native_rgb_or_nv12"
        else -> "ffmpeg_process"
    }

    /** Returns a map of client LAV status. */
    private fun lavStatus(hello: ClientHello): String = when {
        hello.lavZeroCopyEnabled -> "zero_copy_enabled"
        hello.lavSurfaceInteropAvailable -> "surface_interop_available"
        hello.lavInProcessEnabled -> "in_process_enabled"
        hello.lavAvailable -> "library_available"
        else -> "unavailable"
    }

    /** Returns a bucket area string. */
    private fun bucketArea(area: Int): String = when {
        area <= 16 -> "1_16"
        area <= 64 -> "17_64"
        area <= 256 -> "65_256"
        else -> "257_plus"
    }

    /** Counts the occurrences of each item in the iterable by the given key. */
    private fun <T> Iterable<T>.countBy(keyOf: (T) -> String): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        for (item in this) counts.increment(keyOf(item))
        return counts
    }

    /** Increments the count of the given key in the map. */
    private fun MutableMap<String, Int>.increment(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }
}
