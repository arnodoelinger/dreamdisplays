package com.dreamdisplays.platform.server.utils

import com.dreamdisplays.util.net.DreamHttpClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly
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

    /** Builds a webhook payload for a report, including location, video link, display ID, reporter, and owner. */
    private fun buildWebhookPayload(
        locationStr: String,
        videoLink: String?,
        displayId: UUID,
        reporterName: String,
        ownerName: String?,
    ): String {
        val embed = JsonObject().apply {
            addProperty("description", EMBED_TITLE)
            addProperty("color", EMBED_COLOR)
            addProperty("timestamp", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            add("fields", JsonArray().apply {
                add(createField("Location", locationStr))
                add(createField("Video", videoLink))
                add(createField("UUID", displayId.toString()))
                add(createField("Reporter", reporterName))
                add(createField("Owner", ownerName))
            })
        }
        return JsonObject().apply {
            add("embeds", JsonArray().apply { add(embed) })
        }.toString()
    }

    /** Creates a Discord embed field with the given [name], [value], and [inline] status. */
    private fun createField(name: String, value: String?, inline: Boolean = false) =
        JsonObject().apply {
            addProperty("name", name)
            addProperty("value", value ?: "N/A")
            addProperty("inline", inline)
        }

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
}
