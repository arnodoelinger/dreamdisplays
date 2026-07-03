package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.api.runtime.getOrNull
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiText
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.darkenRgb
import com.dreamdisplays.platform.client.ui.kit.drawShimmer
import com.dreamdisplays.platform.client.ui.kit.fillVGradient
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

    companion object {
        /** Aspect ratio of a YouTube thumbnail image, independent of the screen's own block shape. */
        private const val THUMBNAIL_RATIO = 16f / 9f
    }

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
        // Ambient letterbox tinted to the current thumbnail's colors (YouTube-style) instead of a
        // flat black box, so the bars around the video blend with its palette. Preview-only — the
        // in-world displays are rendered elsewhere and keep their plain background.
        val ambient = ambientColor()
        g.fillVGradient(x, y, x + w, y + h, ambient, darkenRgb(ambient, 0.55f))

        val area = UiRect(x, y, w, h)
        // The decoded video frame is already server-side letterboxed to the screen's own block
        // shape, so it's fit to that ratio here too. The idle thumbnail is a raw 16:9 YouTube
        // image and gets its own fit box in drawWaiting() below instead.
        val screenRatio = ds.width / max(1f, ds.height.toFloat())
        val video = fitRatio(area, screenRatio)

        if (ds.isVideoStarted && ds.texture != null && ds.textureId != null) {
            yuvPreview.detach()
            ds.fitTexture()
            // fitTexture() may promote a staged quality-handoff texture, which releases and
            // unregisters the previous one. Re-read the id afterwards so we never blit a
            // just-freed texture (otherwise: "Missing resource" + GL_INVALID_OPERATION).
            val texId = ds.textureId
            if (texId != null) {
                blitTexture(g, texId, video.x, video.y, video.w, video.h)
            }
        } else if (ds.isVideoStarted && ds.isYuvTexture) {
            yuvPreview.attach()
            yuvPreview.uploadFrame()
            val previewId = yuvPreview.textureId
            if (previewId != null) {
                blitTexture(g, previewId, video.x, video.y, video.w, video.h)
            } else {
                drawWaiting(g, font, area)
            }
        } else {
            yuvPreview.detach()
            drawWaiting(g, font, area)
        }
    }

    /** Returns the largest box with aspect ratio [ratio] that fits inside [area], centered. */
    private fun fitRatio(area: UiRect, ratio: Float): UiRect {
        val w: Int
        val h: Int
        if (area.w / area.h.toFloat() > ratio) {
            h = area.h; w = (h * ratio).toInt()
        } else {
            w = area.w; h = (w / ratio).toInt()
        }
        return area.centered(w, h)
    }

    private fun drawWaiting(g: GuiGraphicsCompat, font: net.minecraft.client.gui.Font, area: UiRect) {
        // YouTube thumbnails are always 16:9, regardless of the screen's own block shape.
        val box = fitRatio(area, THUMBNAIL_RATIO)
        val thumb = currentThumbnail()
        if (thumb != null) {
            blitTexture(g, thumb, box.x, box.y, box.w, box.h)
            g.fill(box.x, box.y, box.right, box.bottom, 0x80000000.toInt())
        } else {
            // No thumbnail yet: a neat shimmer in the video area instead of an empty box.
            g.drawShimmer(box.x, box.y, box.right, box.bottom, UiTheme.PLACEHOLDER_BG, UiTheme.PLACEHOLDER_SHIMMER)
        }
        val waiting = Component.translatable("dreamdisplays.ui.waiting").string
        g.drawText(
            font, waiting,
            area.centerX - font.width(waiting) / 2,
            area.centerY - font.lineHeight / 2,
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

    /** Video ID of the currently loaded URL, or null when there's none or it isn't a YouTube link. */
    private fun currentVideoId(): String? {
        val url = ds.videoUrl ?: return null
        return DreamServices.registry.getOrNull(MediaServices.SEARCH)?.extractVideoId(url)
    }

    /** Ambient letterbox tint: the current thumbnail's average color, darkened, or a neutral fallback. */
    private fun ambientColor(): Int {
        val id = currentVideoId() ?: return UiTheme.AMBIENT_DEFAULT
        val avg = Thumbnails.averageColor(id) ?: run {
            // Warm the thumbnail even while the video plays, so the tint appears once it decodes
            // (request de-dups, so calling it per frame is cheap).
            Thumbnails.request(id, YouTubeUrls.thumbnailUrl(id))
            return UiTheme.AMBIENT_DEFAULT
        }
        return darkenRgb(avg, 0.30f)
    }

    /** Returns the cached thumbnail for the current video, requesting it asynchronously if absent. */
    private fun currentThumbnail(): Identifier? {
        val id = currentVideoId() ?: return null
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
            if (attached) return
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
                TextureUploadUtil.applyBilinearFilter(tex)
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
