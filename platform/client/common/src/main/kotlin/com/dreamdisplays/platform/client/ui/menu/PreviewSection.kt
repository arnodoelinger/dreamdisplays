package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.api.runtime.getOrNull
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiText
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.drawShimmer
import com.dreamdisplays.platform.client.ui.widgets.IconButton
import com.dreamdisplays.platform.client.ui.widgets.SeekBar
import com.dreamdisplays.platform.client.ui.widgets.ValueSlider
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.render.AmbientGrid
import com.dreamdisplays.platform.client.render.AsyncTextureUploader
import com.dreamdisplays.platform.client.render.TextureUploadUtil
import com.dreamdisplays.platform.client.render.UploadPixelFormat
import com.dreamdisplays.api.media.MediaServices
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.platform.client.render.Thumbnails
import com.dreamdisplays.media.source.twitch.TwitchMetadataCache
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
import kotlin.math.roundToInt

/**
 * The preview panel of the display menu: live video (or thumbnail while loading), the title/metadata
 * overlay strip, and the playback controls row (mute, volume, progress bar, popout + its dropdown,
 * pause). Owns only drawing and per-frame placement; the widgets themselves live on the screen.
 */
class PreviewSection(
    private val ds: DisplayScreen,
    private val muteButton: IconButton,
    private val volume: ValueSlider,
    private val popoutButton: IconButton,
    private val audioTrackButton: IconButton,
    private val pauseButton: IconButton,
    private val progress: SeekBar,
    private val dropdown: PopoutDropdown,
    private val audioTrackDropdown: AudioTrackDropdown,
) {
    private val yuvPreview = PreviewFrameTexture(ds)
    private val ambientSampler = AmbientFrameSampler(ds)
    private var frameSinkAttached = false
    private var lastVideoUrl: String? = null
    private var audioPresence = 0f
    private var lastPresenceFrameNanos = 0L

    companion object {
        /** Aspect ratio of a YouTube thumbnail image, independent of the screen's own block shape. */
        private const val THUMBNAIL_RATIO = 16f / 9f

        /** Width of the volume slider in the controls row. */
        private const val VOLUME_W = 76
    }

    /** Draws the panel content into [panel] and lays out the controls row along its bottom edge. */
    fun render(g: GuiGraphicsCompat, panel: UiRect, mouseX: Int, mouseY: Int) {
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

        val now = System.nanoTime()
        val dt = if (lastPresenceFrameNanos == 0L) 0.016f else ((now - lastPresenceFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastPresenceFrameNanos = now
        val target = if (ds.audioTrackList.size > 1) 1f else 0f
        val diff = target - audioPresence
        audioPresence += diff * minOf(1f, dt * 10f)
        if (diff in -0.002f..0.002f) audioPresence = target

        // Controls row: [mute][volume] [progress........] [audio][popout][pause]
        muteButton.place(UiRect(innerX, controlsRowY, btn, btn))
        val volumeX = innerX + btn + 4
        volume.place(UiRect(volumeX, controlsRowY, VOLUME_W, btn))
        pauseButton.place(UiRect(controlsRight - btn, controlsRowY, btn, btn))
        popoutButton.place(UiRect(controlsRight - btn * 2 - 4, controlsRowY, btn, btn))

        val audioSlotRight = controlsRight - btn * 2 - 8
        val audioBtnW = (btn * audioPresence).roundToInt()
        val audioGap = (4 * audioPresence).roundToInt()
        val audioBtnLeft = audioSlotRight - audioBtnW
        audioTrackButton.place(UiRect(audioBtnLeft, controlsRowY, audioBtnW, btn))
        audioTrackButton.alpha = audioPresence

        val progX = volumeX + VOLUME_W + 4
        val progW = max(40, (audioBtnLeft - audioGap) - progX)
        progress.place(UiRect(progX, controlsRowY, progW, btn))

        dropdown.draw(g, popoutButton.x + btn / 2, popoutButton.y, mouseX, mouseY)
        // Centered on the slot's fixed target position (not the animating button rect), so it never
        // drifts or jitters while the button is still easing in.
        val audioBtnFinalCenterX = audioSlotRight - btn / 2
        if (audioPresence > 0.01f) audioTrackDropdown.draw(g, audioBtnFinalCenterX, controlsRowY, mouseX, mouseY)
    }

    /** Draws the letterboxed video frame, or the dimmed thumbnail + waiting text while loading. */
    private fun drawVideoArea(g: GuiGraphicsCompat, x: Int, y: Int, w: Int, h: Int) {
        val font = Minecraft.getInstance().font

        if (ds.videoUrl != lastVideoUrl) {
            lastVideoUrl = ds.videoUrl
            ambientSampler.reset()
        }

        if (currentSource() != null) {
            drawAmbientBackdrop(g, x, y, w, h)
        } else {
            g.fill(x, y, x + w, y + h, UiTheme.VIDEO_BACKDROP)
        }

        val area = UiRect(x, y, w, h)
        val contentRatio = ds.videoContentAspect.toFloat().takeIf { it > 0f } ?: THUMBNAIL_RATIO
        val video = fitRatio(area, contentRatio)

        if (ds.isVideoStarted) {
            attachFrameSink()
            ambientSampler.uploadFrame()
        } else {
            detachFrameSink()
        }

        if (ds.isVideoStarted && ds.texture != null && ds.textureId != null) {
            ds.fitTexture()
            // fitTexture() may promote a staged quality-handoff texture, which releases and
            // unregisters the previous one. Re-read the id afterwards so we never blit a
            // just-freed texture (otherwise: "Missing resource" + GL_INVALID_OPERATION).
            val texId = ds.textureId
            if (texId != null) {
                blitVideoTexture(g, texId, video.x, video.y, video.w, video.h, ds.textureWidth, ds.textureHeight)
            }
        } else if (ds.isVideoStarted && ds.isYuvTexture) {
            yuvPreview.uploadFrame()
            val previewId = yuvPreview.textureId
            if (previewId != null) {
                blitVideoTexture(g, previewId, video.x, video.y, video.w, video.h, yuvPreview.texW, yuvPreview.texH)
            } else {
                drawWaiting(g, font, area)
            }
        } else {
            drawWaiting(g, font, area)
        }
    }

    /**
     * Attaches the single preview-frame sink shared by the full YUV preview texture (only relevant
     * while [DisplayScreen.isYuvTexture]) and the ambient sampler (relevant for every playing video,
     * regardless of which texture path is actually rendered on screen).
     */
    private fun attachFrameSink() {
        if (frameSinkAttached) return
        frameSinkAttached = true
        ds.setPreviewFrameSink { buf, w, h, format ->
            if (ds.isYuvTexture) yuvPreview.updateFrame(buf, w, h, format)
            ambientSampler.onFrame(buf, w, h, format)
        }
    }

    private fun detachFrameSink() {
        if (!frameSinkAttached) return
        frameSinkAttached = false
        ds.setPreviewFrameSink(null)
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

    /**
     * The decode pipeline pads every frame to the display's own block aspect ratio (its GPU texture
     * is allocated at that shape), so a wide / narrow block display bakes black bars into the texture
     * pixels themselves.
     */
    private fun contentRect(frameW: Int, frameH: Int, contentAspect: Double): UiRect {
        if (frameW <= 0 || frameH <= 0 || contentAspect <= 0.0 || !contentAspect.isFinite()) {
            return UiRect(0, 0, frameW, frameH)
        }
        val frameAspect = frameW / frameH.toDouble()
        return if (contentAspect > frameAspect) {
            val contentH = (frameW / contentAspect).toInt().coerceIn(1, frameH)
            UiRect(0, (frameH - contentH) / 2, frameW, contentH)
        } else {
            val contentW = (frameH * contentAspect).toInt().coerceIn(1, frameW)
            UiRect((frameW - contentW) / 2, 0, contentW, frameH)
        }
    }

    /** Like [blitTexture], but crops the block-shape padding out of a [texW] x [texH] decode texture first. */
    private fun blitVideoTexture(g: GuiGraphicsCompat, id: Identifier, x: Int, y: Int, w: Int, h: Int, texW: Int, texH: Int) {
        val content = contentRect(texW, texH, ds.videoContentAspect)
        //? if >=1.21.11 {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, content.x.toFloat(), content.y.toFloat(), w, h, content.w, content.h, texW, texH)
        //?} else
        /*g.blit(id, x, y, w, h, content.x.toFloat(), content.y.toFloat(), content.w, content.h, texW, texH)*/
    }

    private fun drawWaiting(g: GuiGraphicsCompat, font: net.minecraft.client.gui.Font, area: UiRect) {
        // YouTube thumbnails are always 16:9, regardless of the screen's own block shape.
        val box = fitRatio(area, THUMBNAIL_RATIO)
        val thumb = currentThumbnail()
        when {
            thumb != null -> {
                blitTexture(g, thumb, box.x, box.y, box.w, box.h)
                g.fill(box.x, box.y, box.right, box.bottom, UiTheme.THUMB_DIM_SCRIM)
            }
            // Something is assigned and loading, just no thumbnail decoded yet: a neat shimmer
            currentSource() != null ->
                g.drawShimmer(box.x, box.y, box.right, box.bottom, UiTheme.PLACEHOLDER_BG, UiTheme.PLACEHOLDER_SHIMMER)
            // Nothing assigned to this display: leave the plain black backdrop from drawVideoArea
            else -> {}
        }
        val waiting = Component.translatable("dreamdisplays.ui.waiting").string
        g.drawText(
            font, waiting,
            area.centerX - font.width(waiting) / 2,
            area.centerY - font.lineHeight / 2,
            UiTheme.TEXT_DIM, true,
        )
    }

    /** Generic overlay text resolved for the current display URL, regardless of provider. */
    private data class OverlayInfo(
        val title: String?,
        val uploader: String?,
        val views: String,
        val likes: String,
        val published: String?,
        val isNew: Boolean,
        val isTwitch: Boolean,
    )

    /** Resolves [OverlayInfo] for the current display URL: YouTube via [VideoMetadataCache], Twitch via [TwitchMetadataCache]. */
    private fun overlayInfo(): OverlayInfo {
        when (val source = currentSource()) {
            is MediaSource.Twitch -> {
                val key = TwitchMetadataCache.cacheKey(source)
                val meta = key?.let { TwitchMetadataCache.get(it) }
                if (meta == null) TwitchMetadataCache.requestAsync(source)
                return OverlayInfo(
                    title = meta?.title,
                    uploader = meta?.channelName,
                    views = meta?.viewCount?.let {
                        formatCompactCount(it) + " " + Component.translatable(
                            if (meta.isLive) "dreamdisplays.ui.watching" else "dreamdisplays.ui.views_short"
                        ).string
                    } ?: "",
                    likes = "",
                    published = meta?.gameName,
                    isNew = false,
                    isTwitch = true,
                )
            }

            else -> {
                val videoId = DreamServices.registry.getOrNull(MediaServices.SEARCH)?.extractVideoId(ds.videoUrl ?: "")
                val meta = if (videoId != null) VideoMetadataCache.get(videoId) else null
                if (videoId != null && meta == null) VideoMetadataCache.requestAsync(videoId)
                var title = meta?.title
                if (title.isNullOrEmpty() && videoId != null) title = VideoTitleCache.get(videoId)
                return OverlayInfo(
                    title = title,
                    uploader = meta?.uploader,
                    views = meta?.formatViews() ?: "",
                    likes = meta?.formatLikes() ?: "",
                    published = meta?.publishedText,
                    isNew = meta?.isRecent(7) == true,
                    isTwitch = false,
                )
            }
        }
    }

    /** Draws the dark strip with the video title (+NEW tag) and channel / views / likes / date metadata. */
    private fun drawTitleOverlay(g: GuiGraphicsCompat, x: Int, y: Int, w: Int) {
        val font = Minecraft.getInstance().font
        val info = overlayInfo()

        var title = info.title
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
        if (info.isTwitch) {
            val tag = Component.translatable("dreamdisplays.ui.twitch").string
            val tw = font.width(tag) + 6
            g.fill(titleX, titleY - 1, titleX + tw, titleY + font.lineHeight, UiTheme.ACCENT_TWITCH_TAG)
            g.drawText(font, tag, titleX + 3, titleY, UiTheme.TEXT_PRIMARY, false)
            titleX += tw + 4
            shown = UiText.trim(font, title, textW - tw - 4)
        } else if (info.isNew) {
            val tag = Component.translatable("dreamdisplays.ui.new").string
            val tw = font.width(tag) + 6
            g.fill(titleX, titleY - 1, titleX + tw, titleY + font.lineHeight, UiTheme.ACCENT_NEW_TAG)
            g.drawText(font, tag, titleX + 3, titleY, UiTheme.TEXT_PRIMARY, false)
            titleX += tw + 4
            shown = UiText.trim(font, title, textW - tw - 4)
        }
        g.drawText(font, shown, titleX, titleY, UiTheme.TEXT_PRIMARY, false)

        val parts = StringBuilder()
        if (!info.uploader.isNullOrEmpty()) parts.append(info.uploader)
        if (info.views.isNotEmpty()) {
            if (parts.isNotEmpty()) parts.append(" • ")
            parts.append(info.views)
        }
        if (info.likes.isNotEmpty()) {
            if (parts.isNotEmpty()) parts.append(" • ")
            parts.append(info.likes).append(" ").append(Component.translatable("dreamdisplays.ui.likes").string)
        }
        if (!info.published.isNullOrEmpty()) {
            if (parts.isNotEmpty()) parts.append(" • ")
            parts.append(info.published)
        }
        g.drawText(
            font, UiText.trim(font, parts.toString(), textW),
            x + padX, boxY + padY + font.lineHeight + padY, UiTheme.TEXT_SECONDARY, false,
        )
    }

    /** Formats [v] compactly (e.g. "1.2M"), or its plain value below 1000. */
    private fun formatCompactCount(v: Long): String = when {
        v >= 1_000_000_000L -> String.format("%.1fB", v / 1_000_000_000.0)
        v >= 1_000_000L -> String.format("%.1fM", v / 1_000_000.0)
        v >= 1_000L -> String.format("%.1fK", v / 1_000.0)
        else -> v.toString()
    }

    /** The [MediaSource] for the display's current URL, or null when there's none set. */
    private fun currentSource(): MediaSource? = ds.videoUrl?.takeIf { it.isNotEmpty() }?.let { MediaSource.from(it) }

    /** Cache key for the current source's thumbnail/metadata: a YouTube video id, or a Twitch composite key. */
    private fun currentThumbnailKey(): String? = when (val source = currentSource()) {
        is MediaSource.Twitch -> TwitchMetadataCache.cacheKey(source)
        is MediaSource.YouTube -> source.videoId
        else -> null
    }

    /** Requests the thumbnail download for the current source once its metadata (and thumbnail URL) is ready. */
    private fun requestCurrentThumbnail() {
        when (val source = currentSource()) {
            is MediaSource.Twitch -> {
                val key = TwitchMetadataCache.cacheKey(source) ?: return
                val meta = TwitchMetadataCache.get(key)
                if (meta == null) {
                    TwitchMetadataCache.requestAsync(source)
                    return
                }
                meta.thumbnailUrl?.let { Thumbnails.request(key, it) }
            }

            is MediaSource.YouTube -> Thumbnails.request(source.videoId)
            else -> {}
        }
    }

    private fun drawAmbientBackdrop(g: GuiGraphicsCompat, x: Int, y: Int, w: Int, h: Int) {
        val live = ambientSampler.textureId
        val id = currentThumbnailKey()
        val ambient = live ?: id?.let { Thumbnails.ambientTexture(it) }
        if (ambient != null) {
            blitTexture(g, ambient, x, y, w, h)
            g.fill(x, y, x + w, y + h, UiTheme.AMBIENT_SCRIM)
        } else {
            // Warm the thumbnail even while the video plays, so the backdrop appears once it decodes
            // (request de-dups, so calling it per frame is cheap).
            requestCurrentThumbnail()
            g.fill(x, y, x + w, y + h, UiTheme.AMBIENT_DEFAULT)
        }
    }

    /** Returns the cached thumbnail for the current video, requesting it asynchronously if absent. */
    private fun currentThumbnail(): Identifier? {
        val id = currentThumbnailKey() ?: return null
        Thumbnails.get(id)?.let { return it }
        requestCurrentThumbnail()
        return null
    }

    private fun blitTexture(g: GuiGraphicsCompat, id: Identifier, x: Int, y: Int, w: Int, h: Int) {
        //? if >=1.21.11 {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, w, h)
        //?} else
        /*g.blit(id, x, y, 0f, 0f, w, h, w, h)*/
    }

    fun close() {
        detachFrameSink()
        yuvPreview.close()
        ambientSampler.close()
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
        var texW = 0
            private set
        var texH = 0
            private set
        private var uploader: AsyncTextureUploader? = null
        private var rgbaUploadBuffer: ByteBuffer? = null

        fun updateFrame(buf: ByteBuffer, w: Int, h: Int, format: UploadPixelFormat) {
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
                    Initializer.MOD_ID,
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

    private class AmbientFrameSampler(private val ds: DisplayScreen) {
        @Volatile
        private var target: AmbientGrid.Grid? = null
        private var lastSampleNanos = 0L

        private var currentR: FloatArray? = null
        private var currentG: FloatArray? = null
        private var currentB: FloatArray? = null
        private var lastUploadNanos = 0L

        private var rgbaBuf: ByteBuffer = EMPTY_DIRECT
        private var dynamicTexture: DynamicTexture? = null
        var textureId: Identifier? = null
            private set
        private var uploader: AsyncTextureUploader? = null
        private var rgbaUploadBuffer: ByteBuffer? = null

        fun onFrame(buf: ByteBuffer, w: Int, h: Int, format: UploadPixelFormat) {
            val now = System.nanoTime()
            if (now - lastSampleNanos < SAMPLE_INTERVAL_NS) return
            lastSampleNanos = now
            target = AmbientGrid.fromFrameBuffer(buf, w, h, format.bytesPerPixel)
        }

        fun uploadFrame() {
            val t = target ?: return
            val gw = AmbientGrid.GRID_W
            val gh = AmbientGrid.GRID_H
            val n = gw * gh

            var cr = currentR
            var cg = currentG
            var cb = currentB
            val now = System.nanoTime()
            if (cr == null || cg == null || cb == null) {
                cr = FloatArray(n) { t.r[it].toFloat() }
                cg = FloatArray(n) { t.g[it].toFloat() }
                cb = FloatArray(n) { t.b[it].toFloat() }
                currentR = cr; currentG = cg; currentB = cb
            } else if (lastUploadNanos != 0L) {
                val dtSeconds = ((now - lastUploadNanos).coerceAtLeast(0)) / 1_000_000_000f
                val alpha = 1f - kotlin.math.exp(-dtSeconds / SMOOTH_TAU_SECONDS)
                for (i in 0 until n) {
                    cr[i] += (t.r[i] - cr[i]) * alpha
                    cg[i] += (t.g[i] - cg[i]) * alpha
                    cb[i] += (t.b[i] - cb[i]) * alpha
                }
            }
            lastUploadNanos = now

            val size = n * 4
            var out = rgbaBuf
            if (out.capacity() < size) out = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            out.clear()
            for (i in 0 until n) {
                out.put(cr[i].toInt().coerceIn(0, 255).toByte())
                out.put(cg[i].toInt().coerceIn(0, 255).toByte())
                out.put(cb[i].toInt().coerceIn(0, 255).toByte())
                out.put(0xFF.toByte())
            }
            out.flip()
            rgbaBuf = out

            val mc = Minecraft.getInstance()
            var tex = dynamicTexture
            if (tex == null) {
                val img = NativeImage(NativeImage.Format.RGBA, gw, gh, false)
                //? if >=1.21.11 {
                tex = DynamicTexture({ "dreamdisplays:ambient" }, img)
                //?} else
                /*tex = DynamicTexture(img)*/
                textureId = Identifier.fromNamespaceAndPath(
                    Initializer.MOD_ID,
                    "ambient/${ds.uuid}-${UUID.randomUUID()}",
                )
                mc.textureManager.register(textureId!!, tex)
                TextureUploadUtil.applyBilinearFilter(tex)
                dynamicTexture = tex
            }

            TextureUploadUtil.uploadDynamicTexture(
                texture = tex,
                src = rgbaBuf,
                w = gw,
                h = gh,
                format = UploadPixelFormat.RGBA32,
                glUploader = { uploader ?: AsyncTextureUploader(stateCache = true).also { uploader = it } },
                rgbaScratch = rgbaUploadBuffer,
                setRgbaScratch = { rgbaUploadBuffer = it },
            )
        }

        fun reset() {
            target = null
            lastSampleNanos = 0
            currentR = null; currentG = null; currentB = null
            lastUploadNanos = 0
            val mc = Minecraft.getInstance()
            dynamicTexture?.close()
            textureId?.let { mc.textureManager.release(it) }
            dynamicTexture = null
            textureId = null
        }

        fun close() {
            uploader?.close()
            uploader = null
            reset()
        }

        companion object {
            private val EMPTY_DIRECT: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
            private const val SAMPLE_INTERVAL_NS = 1_500_000_000L

            /** Time constant of the exponential ease toward each newly sampled target — bigger = slower, calmer drift. */
            private const val SMOOTH_TAU_SECONDS = 2.5f
        }
    }
}
