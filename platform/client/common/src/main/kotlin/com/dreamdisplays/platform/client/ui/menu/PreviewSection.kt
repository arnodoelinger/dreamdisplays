package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.api.runtime.getOrNull
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiText
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.widgets.IconButton
import com.dreamdisplays.platform.client.ui.widgets.SeekBar
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.render.AsyncTextureUploader
import com.dreamdisplays.platform.client.render.TextureUploadUtil
import com.dreamdisplays.platform.client.render.UploadPixelFormat
import com.dreamdisplays.api.media.MediaServices
import com.dreamdisplays.api.media.search.YouTubeUrls
import com.dreamdisplays.platform.client.render.Thumbnails
import com.dreamdisplays.media.source.ytdlp.VideoMetadataCache
import com.dreamdisplays.media.source.ytdlp.VideoTitleCache
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.renderer.RenderPipelines
//?}
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max

/**
 * The preview panel of the display menu: live video (or thumbnail while loading), the title/metadata
 * overlay strip, and the playback controls row (seek buttons, mute, popout + its dropdown, progress
 * bar, pause). Owns only drawing and per-frame placement; the widgets themselves live on the screen.
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
    private val yuvPreview = PreviewFrameTexture(ds)

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

        if (ds.isVideoStarted && ds.texture != null && ds.textureId != null) {
            yuvPreview.detach()
            ds.fitTexture()
            // fitTexture() may promote a staged quality-handoff texture, which releases and
            // unregisters the previous one. Re-read the id afterwards so we never blit a
            // just-freed texture (otherwise: "Missing resource" + GL_INVALID_OPERATION).
            val texId = ds.textureId
            if (texId != null) {
                blitTexture(g, texId, videoX, videoY, videoW, videoH)
            }
        } else if (ds.isVideoStarted && ds.isYuvTexture) {
            yuvPreview.attach()
            yuvPreview.uploadFrame()
            val previewId = yuvPreview.textureId
            if (previewId != null) {
                blitTexture(g, previewId, videoX, videoY, videoW, videoH)
            } else {
                drawWaiting(g, font, x, y, w, h, videoX, videoY, videoW, videoH)
            }
        } else {
            yuvPreview.detach()
            drawWaiting(g, font, x, y, w, h, videoX, videoY, videoW, videoH)
        }
    }

    private fun drawWaiting(
        g: GuiGraphicsCompat,
        font: net.minecraft.client.gui.Font,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        videoX: Int,
        videoY: Int,
        videoW: Int,
        videoH: Int,
    ) {
        currentThumbnail()?.let { thumb ->
            blitTexture(g, thumb, videoX, videoY, videoW, videoH)
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

    /** Draws the dark strip with the video title (+NEW tag) and channel/views/likes/date metadata. */
    private fun drawTitleOverlay(g: GuiGraphicsCompat, x: Int, y: Int, w: Int) {
        val font = Minecraft.getInstance().font
        val videoId = DreamServices.registry.getOrNull(MediaServices.SEARCH)?.extractVideoId(ds.videoUrl ?: "")
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
        val id = DreamServices.registry.getOrNull(MediaServices.SEARCH)?.extractVideoId(url) ?: return null
        Thumbnails.get(id)?.let { return it }
        Thumbnails.request(id, YouTubeUrls.thumbnailUrl(id))
        return null
    }

    private fun blitTexture(g: GuiGraphicsCompat, id: Identifier, x: Int, y: Int, w: Int, h: Int) {
        //? if >=1.21.11 {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, w, h)
        //?} else
        /*g.blit(id, x, y, 0f, 0f, w, h, w, h)*/
    }

    fun close() {
        yuvPreview.close()
    }

    private class PreviewFrameTexture(private val ds: DisplayScreen) {
        @Volatile
        private var frontBuf: ByteBuffer = EMPTY_DIRECT

        private var backBuf: ByteBuffer = EMPTY_DIRECT

        @Volatile
        private var frameW = 0

        @Volatile
        private var frameH = 0

        @Volatile
        private var frameFormat = UploadPixelFormat.RGB24

        @Volatile
        private var frameVersion = 0L

        private var uploadedVersion = 0L

        private var dynamicTexture: DynamicTexture? = null
        var textureId: Identifier? = null
            private set
        private var texW = 0
        private var texH = 0
        private var attached = false
        private var uploader: AsyncTextureUploader? = null
        private var rgbaUploadBuffer: ByteBuffer? = null

        fun attach() {
            attached = true
            ds.setPreviewFrameSink(::updateFrame)
        }

        fun detach() {
            if (!attached) return
            attached = false
            ds.setPreviewFrameSink(null)
        }

        private fun updateFrame(buf: ByteBuffer, w: Int, h: Int, format: UploadPixelFormat) {
            val size = w * h * format.bytesPerPixel
            if (size <= 0 || buf.remaining() < size) return
            var back = backBuf
            if (back.capacity() < size) {
                back = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            }
            back.clear()
            val savedLimit = buf.limit()
            val savedPos = buf.position()
            buf.limit(savedPos + size)
            back.put(buf)
            buf.limit(savedLimit)
            buf.position(savedPos)
            back.flip()

            val prev = frontBuf
            frontBuf = back
            backBuf =
                if (prev.capacity() >= size) prev else ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            frameW = w
            frameH = h
            frameFormat = format
            frameVersion++
        }

        fun uploadFrame() {
            val fw = frameW
            val fh = frameH
            val version = frameVersion
            if (version == uploadedVersion) return
            val buf = frontBuf
            val format = frameFormat
            val size = fw * fh * format.bytesPerPixel
            if (fw <= 0 || fh <= 0 || buf.remaining() < size) return

            val mc = Minecraft.getInstance()
            var tex = dynamicTexture
            if (tex == null || texW != fw || texH != fh) {
                tex?.close()
                textureId?.let { mc.textureManager.release(it) }
                val img = NativeImage(NativeImage.Format.RGBA, fw, fh, false)
                //? if >=1.21.11 {
                tex = DynamicTexture({ "dreamdisplays:preview" }, img)
                //?} else
                /*tex = DynamicTexture(img)*/
                textureId = Identifier.fromNamespaceAndPath(
                    com.dreamdisplays.platform.client.Initializer.MOD_ID,
                    "preview/${ds.uuid}-${UUID.randomUUID()}",
                )
                mc.textureManager.register(textureId!!, tex)
                dynamicTexture = tex
                texW = fw
                texH = fh
            }

            TextureUploadUtil.uploadDynamicTexture(
                texture = tex,
                src = buf,
                w = fw,
                h = fh,
                format = format,
                glUploader = { uploader ?: AsyncTextureUploader(stateCache = true).also { uploader = it } },
                rgbaScratch = rgbaUploadBuffer,
                setRgbaScratch = { rgbaUploadBuffer = it },
            )
            uploadedVersion = version
        }

        fun close() {
            detach()
            uploader?.close()
            uploader = null
            val mc = Minecraft.getInstance()
            dynamicTexture?.close()
            textureId?.let { mc.textureManager.release(it) }
            dynamicTexture = null
            textureId = null
        }

        companion object {
            private val EMPTY_DIRECT: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        }
    }
}
