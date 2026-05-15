package com.dreamdisplays.utils

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.bukkit.Location
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Utility object for sending reports to a Discord webhook.
 */
// TODO: add rate limiting to prevent spam
// TODO: customize embed further (add more fields, etc.)
@NullMarked
object ReporterUtil {
    private val httpClient: HttpClient by lazy { HttpClient.newHttpClient() }
    private const val EMBED_COLOR = 0x2F3136
    private const val EMBED_TITLE = "# 🛡️ New report"

    fun sendReport(
        location: Location,
        videoLink: String?,
        displayId: UUID,
        reporter: Player,
        webhookUrl: String,
        ownerName: String?,
    ) {
        val payload = buildWebhookPayload(
            location,
            videoLink,
            displayId,
            reporter.name,
            ownerName
        )

        sendWebhookRequest(webhookUrl, payload)
    }

    private fun buildWebhookPayload(
        location: Location,
        videoLink: String?,
        displayId: UUID,
        reporterName: String,
        ownerName: String?,
    ): String {
        val embed = JsonObject().apply {
            addProperty("description", EMBED_TITLE)
            addProperty("color", EMBED_COLOR)
            addProperty("timestamp", getCurrentTimestamp())
            add("fields", buildFields(location, videoLink, displayId, reporterName, ownerName))
        }

        return JsonObject().apply {
            add("embeds", JsonArray().apply { add(embed) })
        }.toString()
    }

    private fun buildFields(
        location: Location,
        videoLink: String?,
        displayId: UUID,
        reporterName: String,
        ownerName: String?,
    ): JsonArray {
        return JsonArray().apply {
            add(createField("Location", location.toReadableString(), inline = false))
            add(createField("Video", videoLink, inline = false))
            add(createField("UUID", displayId.toString(), inline = false))
            add(createField("Reporter", reporterName, inline = false))
            add(createField("Owner", ownerName, inline = false))
        }
    }

    private fun createField(name: String, value: String?, inline: Boolean): JsonObject {
        return JsonObject().apply {
            addProperty("name", name)
            addProperty("value", value ?: "N/A")
            addProperty("inline", inline)
        }
    }

    private fun sendWebhookRequest(webhookUrl: String, payload: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() / 100 != 2) {
            throw IOException("Discord webhook failed: ${response.statusCode()} - ${response.body()}")
        }
    }

    private fun getCurrentTimestamp(): String {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    private fun Location.toReadableString(): String {
        return "${world?.name} (x=$blockX, y=$blockY, z=$blockZ)"
    }
}
