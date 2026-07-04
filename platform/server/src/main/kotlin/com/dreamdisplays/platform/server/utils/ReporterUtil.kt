package com.dreamdisplays.platform.server.utils

import com.dreamdisplays.util.net.DreamHttpClient
import com.dreamdisplays.util.json.DreamJson
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * Utility for sending moderation reports to a Discord webhook. Rate limiting (per-display and
 * per-reporter) is enforced upstream by [DisplayManager] before any request reaches here.
 */
object ReporterUtil {
    /** Embed constants for the Discord webhook payload. */
    private const val EMBED_COLOR = 0x2F3136

    /** Embed constants for the Discord webhook payload. */
    private const val EMBED_TITLE = "# 🛡️ New report"

    /**
     * Sends a report to Discord. `Fabric` overload. Accepts a pre-formatted [locationStr]
     * because `Fabric` server code already converts [BlockPos] and world key to a readable string.
     */
    @FabricOnly
    fun sendReport(
        locationStr: String,
        videoLink: String?,
        displayId: UUID,
        reporterName: String,
        ownerName: String?,
        webhookUrl: String,
    ) {
        val payload = buildWebhookPayload(locationStr, videoLink, displayId, reporterName, ownerName)
        sendWebhookRequest(webhookUrl, payload)
    }

    /**
     * Sends a report to Discord. `Paper` overload. Converts a `Bukkit` [Location] and
     * [Player] to their string representations, then delegates to the shared logic.
     */
    @PaperOnly
    fun sendReport(
        location: Location,
        videoLink: String?,
        displayId: UUID,
        reporter: Player,
        webhookUrl: String,
        ownerName: String?,
    ) {
        val locationStr = "${location.world?.name} (x=${location.blockX}, y=${location.blockY}, z=${location.blockZ})"
        val payload = buildWebhookPayload(locationStr, videoLink, displayId, reporter.name, ownerName)
        sendWebhookRequest(webhookUrl, payload)
    }

    /**
     * Sends a report to Discord. `NeoForge` overload.
     */
    @NeoForgeOnly
    fun sendReportNeoForge(
        locationStr: String,
        videoLink: String?,
        displayId: UUID,
        reporterName: String,
        ownerName: String?,
        webhookUrl: String,
    ) {
        val payload = buildWebhookPayload(locationStr, videoLink, displayId, reporterName, ownerName)
        sendWebhookRequest(webhookUrl, payload)
    }

    /** Builds a webhook payload for a report, including location, video link, display ID, reporter, and owner. */
    private fun buildWebhookPayload(
        locationStr: String,
        videoLink: String?,
        displayId: UUID,
        reporterName: String,
        ownerName: String?,
    ): String {
        val embed = WebhookEmbed(
            description = EMBED_TITLE,
            color = EMBED_COLOR,
            timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            fields = listOf(
                createField("Location", locationStr),
                createField("Video", videoLink),
                createField("UUID", displayId.toString()),
                createField("Reporter", reporterName),
                createField("Owner", ownerName),
            ),
        )
        return DreamJson.compact.encodeToString(WebhookPayload(listOf(embed)))
    }

    /** Creates a Discord embed field with the given [name], [value], and [inline] status. */
    private fun createField(name: String, value: String?, inline: Boolean = false) =
        WebhookField(name, value ?: "N/A", inline)

    /** Sends a webhook request with the given [payload] to the given [webhookUrl]. */
    private fun sendWebhookRequest(webhookUrl: String, payload: String) {
        val response = DreamHttpClient.execute(
            webhookUrl,
            DreamHttpClient.RequestOptions(
                method = "POST",
                body = payload.toByteArray(StandardCharsets.UTF_8),
                contentType = "application/json",
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000,
            ),
        )
        if (response.code / 100 != 2) {
            throw IOException("Discord webhook failed: ${response.code}: ${response.bodyString()}.")
        }
    }

    /** Webhook payload and embed data classes for serialization. */
    @Serializable
    private data class WebhookPayload(val embeds: List<WebhookEmbed>)

    /** Webhook embed data class for serialization. */
    @Serializable
    private data class WebhookEmbed(
        val description: String,
        val color: Int,
        val timestamp: String,
        val fields: List<WebhookField>,
    )

    /** Webhook field data class for serialization. */
    @Serializable
    private data class WebhookField(
        val name: String,
        val value: String,
        val inline: Boolean,
    )
}
