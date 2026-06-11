package com.dreamdisplays.client.ui.menu

import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.client.ui.GuiGraphicsCompat
import com.dreamdisplays.client.ui.drawText
import com.dreamdisplays.client.ui.kit.UiRect
import com.dreamdisplays.client.ui.kit.UiText
import com.dreamdisplays.client.ui.kit.UiTheme
import com.dreamdisplays.client.ui.widgets.IconButton
import com.dreamdisplays.client.ui.widgets.SeekBar
import com.dreamdisplays.displays.DisplayScreen
import com.dreamdisplays.media.api.YouTubeUrls
import com.dreamdisplays.media.api.MediaSearchService
import com.dreamdisplays.ytdlp.Thumbnails
import com.dreamdisplays.ytdlp.VideoMetadataCache
import com.dreamdisplays.ytdlp.VideoTitleCache
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import kotlin.math.max

/**
 * The preview panel of the display menu: live video (or thumbnail while loading), the title/metadata
 * overlay strip, and the playback controls row (seek buttons, mute, popout + its dropdown, progress
 * bar, pause). Owns only drawing and per-frame placement; the widgets themselves live on the screen.
 *
 * @since 1.0.0
 */
class PreviewSection(
    private val ds: DisplayScreen,
    private val backButton: IconButton,
    private val forwardButton: IconButton,
    private val muteButton: IconButton,
    private val popoutButton: IconButton,
    private val pauseButton: IconButton,
    private val progress: SeekBar,
    private val dropdown: PopoutDropdown,
) {
    /** Draws the panel content into [panel] and lays out the controls row along its bottom edge. */
    fun render(g: GuiGraphicsCompat, panel: UiRect) {
        val font = Minecraft.getInstance().font
        val btn = UiTheme.CONTROL_BUTTON
        val innerX = panel.x + UiTheme.PANEL_PADDING_X
        val innerY = panel.y + UiTheme.PANEL_PADDING_Y + font.lineHeight + 6
        val innerW = panel.w - UiTheme.PANEL_PADDING_X * 2

        val controlsRowY = panel.bottom - UiTheme.PANEL_PADDING_Y - btn
        val controlsRight = innerX + innerW
        val previewMaxH = controlsRowY - innerY - 6

        drawVideoArea(g, innerX, innerY, innerW, previewMaxH)
        drawTitleOverlay(g, innerX, innerY + previewMaxH, innerW)

        // Controls row: [back][forward][mute][popout] [progress........] [pause]
        backButton.place(UiRect(innerX, controlsRowY, btn, btn))
        forwardButton.place(UiRect(innerX + btn + 4, controlsRowY, btn, btn))
        muteButton.place(UiRect(innerX + btn * 2 + 8, controlsRowY, btn, btn))
        popoutButton.place(UiRect(innerX + btn * 3 + 12, controlsRowY, btn, btn))
        pauseButton.place(UiRect(controlsRight - btn, controlsRowY, btn, btn))
        val progX = innerX + btn * 4 + 16
        val progW = max(40, (controlsRight - btn - 4) - progX)
        progress.place(UiRect(progX, controlsRowY, progW, btn))

        dropdown.draw(g, popoutButton.x, popoutButton.y)
    }

    /** Draws the letterboxed video frame, or the dimmed thumbnail + waiting text while loading. */
    private fun drawVideoArea(g: GuiGraphicsCompat, x: Int, y: Int, w: Int, h: Int) {
        val font = Minecraft.getInstance().font
        g.fill(x, y, x + w, y + h, 0xFF000000.toInt())

        val ratio = ds.width / max(1f, ds.height.toFloat())
        val videoW: Int
        val videoH: Int
        if (w / h.toFloat() > ratio) {
            videoH = h; videoW = (videoH * ratio).toInt()
        } else {
            videoW = w; videoH = (videoW / ratio).toInt()
        }
        val videoX = x + (w - videoW) / 2
        val videoY = y + (h - videoH) / 2

        val texId = ds.textureId
        if (ds.isVideoStarted && ds.texture != null && texId != null) {
            ds.fitTexture()
            g.blit(RenderPipelines.GUI_TEXTURED, texId, videoX, videoY, 0f, 0f, videoW, videoH, videoW, videoH)
        } else {
            currentThumbnail()?.let { thumb ->
                g.blit(RenderPipelines.GUI_TEXTURED, thumb, videoX, videoY, 0f, 0f, videoW, videoH, videoW, videoH)
                g.fill(videoX, videoY, videoX + videoW, videoY + videoH, 0x80000000.toInt())
            }
            val waiting = Component.translatable("dreamdisplays.ui.waiting").string
            g.drawText(
                font, waiting,
                x + w / 2 - font.width(waiting) / 2,
                y + h / 2 - font.lineHeight / 2,
                UiTheme.TEXT_DIM, true,
            )
        }
    }

    /** Draws the dark strip with the video title (+NEW tag) and channel/views/likes/date metadata. */
    private fun drawTitleOverlay(g: GuiGraphicsCompat, x: Int, y: Int, w: Int) {
        val font = Minecraft.getInstance().font
        val videoId = DreamServices.registry.getOrNull<MediaSearchService>()?.extractVideoId(ds.videoUrl ?: "")
        val meta = if (videoId != null) VideoMetadataCache.get(videoId) else null
        if (videoId != null && meta == null) VideoMetadataCache.requestAsync(videoId)

        var title: String? = meta?.title
        if (title.isNullOrEmpty() && videoId != null) title = VideoTitleCache.get(videoId)
        if (title.isNullOrEmpty()) title = ds.videoUrl
        if (title == null) title = "—"

        val padX = 4
        val padY = 3
        val textW = w - padX * 2
        var shown = UiText.trim(font, title, textW)

        val boxH = font.lineHeight * 2 + padY * 3
        val boxY = y - boxH
        g.fill(x, boxY, x + w, y, UiTheme.OVERLAY_SCRIM)

        var titleX = x + padX
        val titleY = boxY + padY
        if (meta?.isRecent(7) == true) {
            val tag = Component.translatable("dreamdisplays.ui.new").string
            val tw = font.width(tag) + 6
            g.fill(titleX, titleY - 1, titleX + tw, titleY + font.lineHeight, UiTheme.ACCENT_NEW_TAG)
            g.drawText(font, tag, titleX + 3, titleY, UiTheme.TEXT_PRIMARY, false)
            titleX += tw + 4
            shown = UiText.trim(font, title, textW - tw - 4)
        }
        g.drawText(font, shown, titleX, titleY, UiTheme.TEXT_PRIMARY, false)

        val parts = StringBuilder()
        val channel = meta?.uploader
        val views = meta?.formatViews() ?: ""
        val likes = meta?.formatLikes() ?: ""
        val published = meta?.publishedText
        if (!channel.isNullOrEmpty()) parts.append(channel)
        if (views.isNotEmpty()) {
            if (parts.isNotEmpty()) parts.append(" • ")
            parts.append(views)
        }
        if (likes.isNotEmpty()) {
            if (parts.isNotEmpty()) parts.append(" • ")
            parts.append(likes).append(" ").append(Component.translatable("dreamdisplays.ui.likes").string)
        }
        if (!published.isNullOrEmpty()) {
            if (parts.isNotEmpty()) parts.append(" • ")
            parts.append(published)
        }
        g.drawText(
            font, UiText.trim(font, parts.toString(), textW),
            x + padX, boxY + padY + font.lineHeight + padY, UiTheme.TEXT_SECONDARY, false,
        )
    }

    /** Returns the cached thumbnail for the current video, requesting it asynchronously if absent. */
    private fun currentThumbnail(): Identifier? {
        val url = ds.videoUrl ?: return null
        val id = DreamServices.registry.getOrNull<MediaSearchService>()?.extractVideoId(url) ?: return null
        Thumbnails.get(id)?.let { return it }
        Thumbnails.request(id, YouTubeUrls.thumbnailUrl(id))
        return null
    }
}
