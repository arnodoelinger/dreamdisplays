package com.dreamdisplays.client.ui

import com.dreamdisplays.Initializer
import com.dreamdisplays.client.ui.widgets.*
import com.dreamdisplays.display.DisplayManager
import com.dreamdisplays.display.DisplayScreen
import com.dreamdisplays.display.DisplaySettings
import com.dreamdisplays.managers.ClientStateManager
import com.dreamdisplays.meta.UpdateCheck
import com.dreamdisplays.net.Packets
import com.dreamdisplays.utils.GeneralUtil
import com.dreamdisplays.utils.MinecraftScreenUtil
import com.dreamdisplays.ytdlp.*
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import java.awt.Desktop
import java.net.URI
import kotlin.math.*

/** Configuration of a display screen GUI. */
// TODO: rewrite this class entirely in 1.8.0
class DisplayMenu private constructor() : Screen(Component.translatable("dreamdisplays.ui.title")) {

    var displayScreen: DisplayScreen? = null

    private var volume: SliderWidget? = null
    private var renderD: SliderWidget? = null
    private var quality: SliderWidget? = null
    private var brightness: SliderWidget? = null
    private var sync: ToggleWidget? = null
    private var backButtonWidget: ButtonWidget? = null
    private var forwardButtonWidget: ButtonWidget? = null
    private var pauseButtonWidget: ButtonWidget? = null
    private var renderDReset: ButtonWidget? = null
    private var qualityReset: ButtonWidget? = null
    private var brightnessReset: ButtonWidget? = null
    private var volumeReset: ButtonWidget? = null
    private var syncReset: ButtonWidget? = null
    private var muteButtonWidget: ButtonWidget? = null
    private var popoutButtonWidget: ButtonWidget? = null
    private var lockButtonWidget: ButtonWidget? = null
    private var deleteButtonWidget: ButtonWidget? = null
    private var reportButtonWidget: ButtonWidget? = null
    private var progress: ProgressSliderWidget? = null
    private var suggestions: SuggestionsPanelWidget? = null
    private var lastSuggestedVideoId: String? = null
    private var prevQualityListSize = 0

    private var popoutDropdownVisible = false
    private var ddX = 0; private var ddY = 0; private val ddW = 80; private val ddItemH = 18

    private var volumeHover: HoverArea? = null
    private var renderDHover: HoverArea? = null
    private var qualityHover: HoverArea? = null
    private var brightnessHover: HoverArea? = null
    private var syncHover: HoverArea? = null
    private var modLabelHover: HoverArea? = null
    private val modLabelOpenedAtMs: Long = System.currentTimeMillis()

    override fun init() {
        val ds = displayScreen ?: return

        volume = object : SliderWidget(
            0, 0, 0, 0,
            Component.literal("${floor(ds.volume.toDouble() * 200).toInt()}%"),
            ds.volume.toDouble()
        ) {
            override fun updateMessage() {
                message = Component.literal("${floor(value * 200).toInt()}%")
            }

            override fun applyValue() {
                ds.volume = value.toFloat()
            }
        }

        backButtonWidget = iconButton("left") { ds.seekBackward() }
        forwardButtonWidget = iconButton("right") { ds.seekForward() }
        pauseButtonWidget = object : ButtonWidget(
            0, 0, 0, 0, 64, 64,
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "pause"), 2
        ) {
            override fun onPress() {
                ds.setPaused(!ds.isPaused)
                setIconTextureId(
                    Identifier.fromNamespaceAndPath(
                        Initializer.MOD_ID,
                        if (ds.isPaused) "play" else "pause"
                    )
                )
            }
        }
        pauseButtonWidget!!.setIconTextureId(
            Identifier.fromNamespaceAndPath(
                Initializer.MOD_ID,
                if (ds.isPaused) "play" else "pause"
            )
        )

        muteButtonWidget = object : ButtonWidget(
            0, 0, 0, 0, 64, 64,
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, if (ds.muted) "mute" else "sound"), 2
        ) {
            override fun onPress() {
                ds.mute(!ds.muted)
                setIconTextureId(
                    Identifier.fromNamespaceAndPath(
                        Initializer.MOD_ID,
                        if (ds.muted) "mute" else "sound"
                    )
                )
            }
        }

        popoutButtonWidget = object : ButtonWidget(
            0, 0, 0, 0, 64, 64,
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "popout"), 2
        ) {
            override fun onPress() {
                val cur = displayScreen ?: return
                if (cur.isPopoutActive) {
                    cur.deactivatePopout()
                    popoutDropdownVisible = false
                } else {
                    popoutDropdownVisible = !popoutDropdownVisible
                }
            }
        }

        progress = ProgressSliderWidget(
            0, 0, 100, CTRL_BTN,
            { displayScreen?.currentTimeNanos ?: 0L },
            { displayScreen?.mediaPlayerDurationNanos ?: 0L },
            { nanos ->
                val cur = displayScreen
                if (cur != null && cur.canSeek() && !cur.isLive && (!cur.isSync || cur.owner)) {
                    cur.seekToMillis(nanos / 1_000_000L)
                }
            })

        renderD = object : SliderWidget(
            0, 0, 0, 0,
            Component.literal("${ds.renderDistance} blocks"),
            (ds.renderDistance - 24) / (128 - 24).toDouble()
        ) {
            override fun updateMessage() {
                message = Component.literal("${(value * (128 - 24)).toInt() + 24} blocks")
            }

            override fun applyValue() {
                ds.renderDistance = (value * (128 - 24) + 24).toInt()
                DisplayManager.saveScreenData(ds)
            }
        }

        quality = object : SliderWidget(
            0, 0, 0, 0,
            Component.literal("${ds.quality}p"),
            qualityFraction(ds.quality)
        ) {
            override fun updateMessage() {
                message = Component.literal(
                    if (ds.qualityList.isNotEmpty()) "${qualityFromFraction(value)}p"
                    else "${ds.quality}p"
                )
            }

            override fun applyValue() {
                if (ds.qualityList.isNotEmpty()) ds.quality = qualityFromFraction(value)
            }
        }

        brightness = object : SliderWidget(
            0, 0, 0, 0,
            Component.literal("${floor(ds.brightness.toDouble() * 100).toInt()}%"),
            ds.brightness.toDouble().coerceIn(0.0, 1.0)
        ) {
            override fun updateMessage() {
                message = Component.literal("${floor(value * 100).toInt()}%")
            }

            override fun applyValue() {
                ds.brightness = value.toFloat()
            }
        }

        renderDReset = resetButton {
            ds.renderDistance = ClientStateManager.config.defaultDistance
            renderD?.let {
                it.value = (ClientStateManager.config.defaultDistance - 24) / (128 - 24).toDouble()
                it.message = Component.literal("${ClientStateManager.config.defaultDistance} blocks")
            }
            DisplayManager.saveScreenData(ds)
        }
        qualityReset = resetButton {
            ds.quality = "720"
            quality?.let {
                it.value = qualityFraction("720")
                it.message = Component.literal("720p")
            }
        }
        brightnessReset = resetButton {
            ds.brightness = 1.0f
            brightness?.let {
                it.value = 1.0
                it.message = Component.literal("100%")
            }
        }
        volumeReset = resetButton {
            ds.volume = 0.5f
            volume?.let {
                it.value = 0.5
                it.message = Component.literal("100%")
            }
        }

        sync = object : ToggleWidget(
            0, 0, 0, 0,
            Component.translatable(if (ds.isSync) "dreamdisplays.button.enabled" else "dreamdisplays.button.disabled"),
            ds.isSync
        ) {
            override fun updateMessage() {
                message =
                    Component.translatable(if (value) "dreamdisplays.button.enabled" else "dreamdisplays.button.disabled")
            }

            override fun applyValue() {
                if (ds.canEdit && syncReset != null) {
                    ds.isSync = value
                    syncReset!!.active = !value
                    ds.waitForMFInit { ds.sendSync() }
                }
            }
        }
        syncReset = resetButton {
            if (ds.canEdit && sync != null) {
                sync!!.updateValue(false)
                ds.waitForMFInit { ds.sendSync() }
            }
        }
        sync!!.active = ds.canEdit
        brightness?.let { it.active = !ds.isSync || ds.canEdit }

        val red = WidgetSprites(
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "widgets/red_button"),
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "widgets/red_button_disabled"),
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "widgets/red_button_highlighted")
        )

        lockButtonWidget = object : ButtonWidget(
            0, 0, 0, 0, 24, 24,
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, if (ds.isLocked == false) "unlock" else "lock"), 2
        ) {
            override fun onPress() {
                val cur = displayScreen ?: return
                if (cur.isLocked == null) return
                val newLocked = cur.isLocked != true
                cur.isLocked = newLocked
                Initializer.sendPacket(Packets.SetLocked(cur.uuid, newLocked))
                setIconTextureId(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, if (newLocked) "lock" else "unlock"))
            }
        }
        lockButtonWidget!!.active = ds.owner || ds.isAdmin
        lockButtonWidget!!.visible = ds.isLocked != null

        deleteButtonWidget = object : ButtonWidget(
            0, 0, 0, 0, 64, 64,
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "delete"), 2
        ) {
            override fun onPress() {
                DisplaySettings.removeDisplay(ds.uuid)
                DisplayManager.unregisterScreen(ds)
                Initializer.sendPacket(Packets.Delete(ds.uuid))
                onClose()
            }
        }
        deleteButtonWidget!!.setSprites(red)
        deleteButtonWidget!!.active = ds.owner || ds.isAdmin

        reportButtonWidget = if (ClientStateManager.isReportingEnabled) {
            object : ButtonWidget(
                0, 0, 0, 0, 64, 64,
                Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "report"), 2
            ) {
                override fun onPress() {
                    Initializer.sendPacket(Packets.Report(ds.uuid))
                    onClose()
                }
            }.also { it.setSprites(red) }
        } else null

        addRenderableWidget(volume!!)
        addRenderableWidget(backButtonWidget!!)
        addRenderableWidget(forwardButtonWidget!!)
        addRenderableWidget(muteButtonWidget!!)
        addRenderableWidget(popoutButtonWidget!!)
        addRenderableWidget(progress!!)
        addRenderableWidget(pauseButtonWidget!!)
        addRenderableWidget(renderD!!)
        addRenderableWidget(quality!!)
        addRenderableWidget(qualityReset!!)
        addRenderableWidget(brightness!!)
        addRenderableWidget(brightnessReset!!)
        addRenderableWidget(renderDReset!!)
        addRenderableWidget(volumeReset!!)
        addRenderableWidget(sync!!)
        addRenderableWidget(syncReset!!)
        addRenderableWidget(lockButtonWidget!!)
        addRenderableWidget(deleteButtonWidget!!)
        reportButtonWidget?.let { addRenderableWidget(it) }

        suggestions = SuggestionsPanelWidget(0, 0, 100, 100) { onPickSuggested(it) }
        addRenderableWidget(suggestions!!)
    }

    private fun iconButton(icon: String, action: Runnable): ButtonWidget =
        object : ButtonWidget(
            0, 0, 0, 0, 64, 64,
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, icon), 2
        ) {
            override fun onPress() {
                action.run()
            }
        }

    private fun resetButton(action: Runnable): ButtonWidget =
        object : ButtonWidget(
            0, 0, 0, 0, 64, 64,
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "refresh"), 2
        ) {
            override fun onPress() {
                action.run()
            }
        }

    private fun onPickSuggested(info: YtVideoInfo) {
        val ds = displayScreen ?: return
        ds.playSuggestedVideo(info.getWatchUrl(), ds.lang ?: "")
        VideoTitleCache.put(info.id, info.title)
        VideoMetadataCache.put(info.id, info)
        lastSuggestedVideoId = info.id
        suggestions?.setRelatedTo(info.id)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        extractTransparentBackground(g)

        val ds = displayScreen
        if (ds == null) {
            super.extractRenderState(g, mouseX, mouseY, delta)
            return
        }

        val titleY = 6
        renderModLabel(g, PADDING, titleY)

        val videoReady = ds.isVideoStarted && !ds.errored
        val popoutLocked = ds.isPopoutActive
        syncReset?.active = videoReady && ds.canEdit && ds.isSync
        renderDReset?.active = videoReady && !popoutLocked && ds.renderDistance != ClientStateManager.config.defaultDistance
        qualityReset?.active = videoReady && ds.quality != "720"
        brightness?.let { brightnessReset?.active = videoReady && abs(it.value - 0.5) > 0.01 }
        volume?.let { volumeReset?.active = videoReady && abs(it.value - 0.5) > 0.01 }

        val qualityList = ds.qualityList
        if (qualityList.size != prevQualityListSize) {
            prevQualityListSize = qualityList.size
            if (qualityList.isNotEmpty()) {
                quality?.value = qualityFraction(ds.quality)
                quality?.updateMessage()
            }
        }

        volume?.active = videoReady
        renderD?.active = videoReady && !popoutLocked
        quality?.active = videoReady && qualityList.isNotEmpty()
        brightness?.active = videoReady && (!ds.isSync || ds.canEdit)
        sync?.active = videoReady && ds.canEdit
        deleteButtonWidget?.active = ds.owner || ds.isAdmin
        lockButtonWidget?.let {
            val locked = ds.isLocked
            it.visible = locked != null
            if (locked != null) {
                it.active = ds.owner || ds.isAdmin
                it.setIconTextureId(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, if (locked) "lock" else "unlock"))
            }
        }
        progress?.active = videoReady && ds.canSeek() && !ds.isLive && (!ds.isSync || ds.canEdit)

        if (ds.errored) {
            popoutDropdownVisible = false
            renderErroredOverlay(g, mouseX, mouseY, delta)
            return
        }

        val contentTop = titleY + font.lineHeight + 8
        val contentBottom = this.height - PADDING
        val totalW = this.width - PADDING * 2
        val totalH = contentBottom - contentTop

        val wide = totalW >= 900 && totalH >= 480
        val compact = !wide && totalW < 600

        val leftX = PADDING
        val previewX: Int
        val previewY: Int
        val previewW: Int
        val previewH: Int
        val settingsX: Int
        val settingsY: Int
        val settingsW: Int
        val settingsH: Int
        val suggestionsX: Int
        val suggestionsY: Int
        val suggestionsW: Int
        val suggestionsH: Int
        var suggestionsVertical = false

        if (wide) {
            val rightColW = max(200, min(280, totalW * 3 / 10))
            val leftColW = totalW - rightColW - PANEL_GAP
            val previewSlice = totalH * 6 / 10
            previewX = leftX; previewY = contentTop; previewW = leftColW; previewH = previewSlice
            settingsX = leftX; settingsY = contentTop + previewSlice + PANEL_GAP
            settingsW = leftColW; settingsH = totalH - previewSlice - PANEL_GAP
            suggestionsX = leftX + leftColW + PANEL_GAP; suggestionsY = contentTop
            suggestionsW = rightColW; suggestionsH = totalH
            suggestionsVertical = true
        } else {
            val minSh = 120
            var topRowH = max(220, (totalH * 6) / 10)
            var sH = totalH - topRowH - PANEL_GAP
            if (sH < minSh) {
                sH = minSh; topRowH = totalH - sH - PANEL_GAP
            }
            val showSuggestions = topRowH >= 160

            if (compact) {
                previewW = totalW
                previewH = min(220, topRowH * 3 / 5)
                settingsW = totalW
                settingsH = topRowH - previewH - PANEL_GAP
                settingsX = leftX
                settingsY = contentTop + previewH + PANEL_GAP
            } else {
                previewW = (totalW * 6) / 10 - PANEL_GAP / 2
                settingsW = totalW - previewW - PANEL_GAP
                settingsX = leftX + previewW + PANEL_GAP
                previewH = topRowH
                settingsH = topRowH
                settingsY = contentTop
            }
            previewX = leftX
            previewY = contentTop
            suggestionsX = leftX
            suggestionsY = contentTop + topRowH + PANEL_GAP
            suggestionsW = totalW
            suggestionsH = if (showSuggestions) sH else 0
        }

        drawPanel(
            g, previewX, previewY, previewW, previewH,
            Component.translatable("dreamdisplays.ui.preview").string
        )
        drawPanel(
            g, settingsX, settingsY, settingsW, settingsH,
            Component.translatable("dreamdisplays.ui.settings").string
        )

        renderPreviewSection(g, previewX, previewY, previewW, previewH)
        renderSettingsSection(g, settingsX, settingsY, settingsW, settingsH)

        suggestions?.let { s ->
            s.visible = suggestionsH > 0
            s.setVertical(suggestionsVertical)
            s.setCompactCards(false)
            s.x = suggestionsX
            s.y = suggestionsY
            s.width = suggestionsW
            s.height = suggestionsH

            val currentId = YtDlp.extractVideoId(ds.videoUrl)
            if (currentId != null && currentId != lastSuggestedVideoId) {
                lastSuggestedVideoId = currentId
                s.setRelatedTo(currentId)
            }
        }

        layoutOwnerActions(settingsX, settingsY, settingsW, settingsH)
        super.extractRenderState(g, mouseX, mouseY, delta)
        renderTooltips(g, mouseX, mouseY)
    }

    private fun renderErroredOverlay(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        suggestions?.visible = false
        listOf(volume, renderD, quality, brightness).forEach { w ->
            w?.active = false; w?.visible = false
        }
        sync?.let { it.active = false; it.visible = false }
        listOf(
            backButtonWidget, forwardButtonWidget, pauseButtonWidget,
            renderDReset, qualityReset, brightnessReset, volumeReset, syncReset, muteButtonWidget,
            popoutButtonWidget, lockButtonWidget
        ).forEach { w ->
            w?.active = false; w?.visible = false
        }
        progress?.let { it.active = false; it.visible = false }

        val panelW = min(420, this.width - 40)
        val panelX = this.width / 2 - panelW / 2
        val panelY = this.height / 2 - 70
        drawPanel(
            g, panelX, panelY, panelW, 130,
            Component.translatable("dreamdisplays.ui.error").string
        )
        val lines = listOf(
            Component.translatable("dreamdisplays.error.loadingerror.1").withStyle { it.withColor(ChatFormatting.RED) },
            Component.translatable("dreamdisplays.error.loadingerror.2").withStyle { it.withColor(ChatFormatting.RED) },
            Component.translatable("dreamdisplays.error.loadingerror.4")
                .withStyle { it.withColor(ChatFormatting.GRAY) },
            Component.translatable("dreamdisplays.error.loadingerror.5")
                .withStyle { it.withColor(ChatFormatting.GRAY) },
        )
        var y = panelY + headerHeight() + 8
        for (line in lines) {
            g.text(font, line, this.width / 2 - font.width(line) / 2, y, 0xFFFFFFFF.toInt(), false)
            y += font.lineHeight + 4
        }
        deleteButtonWidget?.let {
            it.x = panelX + panelW / 2 - 22
            it.y = panelY + 130 - 24
            it.width = 20
            it.height = 20
        }
        reportButtonWidget?.let {
            it.x = panelX + panelW / 2 + 2
            it.y = panelY + 130 - 24
            it.width = 20
            it.height = 20
        }
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    private fun renderPreviewSection(g: GuiGraphicsExtractor, px: Int, py: Int, pw: Int, ph: Int) {
        val scr = displayScreen ?: return
        val innerX = px + PANEL_PADDING_X
        val innerY = py + headerHeight()
        val innerW = pw - PANEL_PADDING_X * 2

        val controlsRowY = py + ph - PANEL_PADDING_Y - CTRL_BTN
        val controlsRight = innerX + innerW

        val previewMaxH = controlsRowY - innerY - 6
        g.fill(innerX, innerY, innerX + innerW, innerY + previewMaxH, 0xFF000000.toInt())

        val ratio = scr.width / max(1f, scr.height.toFloat())
        val videoW: Int
        val videoH: Int
        if (innerW / previewMaxH.toFloat() > ratio) {
            videoH = previewMaxH; videoW = (videoH * ratio).toInt()
        } else {
            videoW = innerW; videoH = (videoW / ratio).toInt()
        }
        val videoX = innerX + (innerW - videoW) / 2
        val videoY = innerY + (previewMaxH - videoH) / 2

        val texId = scr.textureId
        if (scr.isVideoStarted && scr.texture != null && texId != null) {
            scr.fitTexture()
            g.blit(
                RenderPipelines.GUI_TEXTURED, texId,
                videoX, videoY, 0f, 0f, videoW, videoH,
                videoW, videoH,
            )
        } else {
            currentThumbnail()?.let { thumb ->
                g.blit(
                    RenderPipelines.GUI_TEXTURED, thumb,
                    videoX, videoY, 0f, 0f, videoW, videoH, videoW, videoH
                )
                g.fill(videoX, videoY, videoX + videoW, videoY + videoH, 0x80000000.toInt())
            }
            val waiting = Component.translatable("dreamdisplays.ui.waiting").string
            g.text(
                font, waiting,
                innerX + innerW / 2 - font.width(waiting) / 2,
                innerY + previewMaxH / 2 - font.lineHeight / 2,
                0xFFCCCCCC.toInt(), true
            )
        }

        renderTitleOverlay(g, scr, innerX, innerY + previewMaxH, innerW)

        val canSeek = !(scr.isSync && !scr.canEdit) && scr.canSeek()
        backButtonWidget?.let {
            it.x = innerX; it.y = controlsRowY
            it.width = CTRL_BTN; it.height = CTRL_BTN
            it.active = canSeek
        }
        forwardButtonWidget?.let {
            it.x = innerX + CTRL_BTN + 4; it.y = controlsRowY
            it.width = CTRL_BTN; it.height = CTRL_BTN
            it.active = canSeek
        }
        muteButtonWidget?.let {
            it.x = innerX + CTRL_BTN * 2 + 8; it.y = controlsRowY
            it.width = CTRL_BTN; it.height = CTRL_BTN
            it.active = scr.isVideoStarted && !scr.errored
            it.setIconTextureId(
                Identifier.fromNamespaceAndPath(
                    Initializer.MOD_ID,
                    if (scr.muted) "mute" else "sound"
                )
            )
        }
        popoutButtonWidget?.let {
            it.x = innerX + CTRL_BTN * 3 + 12; it.y = controlsRowY
            it.width = CTRL_BTN; it.height = CTRL_BTN
            it.active = scr.isVideoStarted && !scr.errored
            if (popoutDropdownVisible) {
                val bx = it.x; val by = it.y
                val dTop = by - ddItemH * 2 - 2
                ddX = bx; ddY = dTop
                g.fill(ddX, ddY, ddX + ddW, ddY + ddItemH * 2, 0xFF1C1C1C.toInt())
                g.fill(ddX, ddY, ddX + ddW, ddY + 1, 0xFF555555.toInt())
                g.fill(ddX, ddY + ddItemH, ddX + ddW, ddY + ddItemH + 1, 0xFF333333.toInt())
                g.fill(ddX, ddY + ddItemH * 2 - 1, ddX + ddW, ddY + ddItemH * 2, 0xFF555555.toInt())
                g.fill(ddX, ddY, ddX + 1, ddY + ddItemH * 2, 0xFF555555.toInt())
                g.fill(ddX + ddW - 1, ddY, ddX + ddW, ddY + ddItemH * 2, 0xFF555555.toInt())
                val fy = ddY + (ddItemH - font.lineHeight) / 2
                g.text(font, "Window", ddX + 6, fy, 0xFFFFFFFF.toInt(), false)
                g.text(font, "In-game", ddX + 6, fy + ddItemH, 0xFFDDDDDD.toInt(), false)
            }
        }
        pauseButtonWidget?.let {
            it.x = controlsRight - CTRL_BTN; it.y = controlsRowY
            it.width = CTRL_BTN; it.height = CTRL_BTN
            it.active = !(scr.isSync && !scr.canEdit)
            it.setIconTextureId(
                Identifier.fromNamespaceAndPath(
                    Initializer.MOD_ID,
                    if (scr.isPaused) "play" else "pause"
                )
            )
        }
        progress?.let {
            val progX = innerX + CTRL_BTN * 4 + 16
            val progRight = controlsRight - CTRL_BTN - 4
            val progW = max(40, progRight - progX)
            it.x = progX; it.y = controlsRowY
            it.width = progW; it.height = CTRL_BTN
        }
    }

    private fun renderTitleOverlay(g: GuiGraphicsExtractor, scr: DisplayScreen, x: Int, y: Int, w: Int) {
        val videoId = YtDlp.extractVideoId(scr.videoUrl)
        val meta = if (videoId != null) VideoMetadataCache.get(videoId) else null
        if (videoId != null && meta == null) VideoMetadataCache.requestAsync(videoId)

        var title: String? = meta?.title
        if (title.isNullOrEmpty() && videoId != null) title = VideoTitleCache.get(videoId)
        if (title.isNullOrEmpty()) title = scr.videoUrl
        if (title == null) title = "—"

        val channel = meta?.uploader
        val views = meta?.formatViews() ?: ""
        val likes = meta?.formatLikes() ?: ""
        val published = meta?.publishedText
        val isNew = meta?.isRecent(7) == true

        val padX = 4
        val padY = 3
        val textW = w - padX * 2
        var shown = trimToWidth(title, textW)

        val boxH = font.lineHeight * 2 + padY * 3
        val boxY = y - boxH
        g.fill(x, boxY, x + w, y, 0xC0000000.toInt())

        var titleX = x + padX
        val titleY = boxY + padY
        if (isNew) {
            val tag = Component.translatable("dreamdisplays.ui.new").string
            val tw = font.width(tag) + 6
            val th = font.lineHeight
            g.fill(titleX, titleY - 1, titleX + tw, titleY + th, 0xFFE53935.toInt())
            g.text(font, tag, titleX + 3, titleY, 0xFFFFFFFF.toInt(), false)
            titleX += tw + 4
            shown = trimToWidth(title, textW - tw - 4)
        }
        g.text(font, shown, titleX, titleY, 0xFFFFFFFF.toInt(), false)

        val meta2 = StringBuilder()
        if (!channel.isNullOrEmpty()) meta2.append(channel)
        if (views.isNotEmpty()) {
            if (meta2.isNotEmpty()) meta2.append(" • ")
            meta2.append(views)
        }
        if (likes.isNotEmpty()) {
            if (meta2.isNotEmpty()) meta2.append(" • ")
            meta2.append(likes).append(" ").append(Component.translatable("dreamdisplays.ui.likes").string)
        }
        if (!published.isNullOrEmpty()) {
            if (meta2.isNotEmpty()) meta2.append(" • ")
            meta2.append(published)
        }
        val metaShown = trimToWidth(meta2.toString(), textW)
        g.text(font, metaShown, x + padX, boxY + padY + font.lineHeight + padY, 0xFFAAAAAA.toInt(), false)
    }

    private fun trimToWidth(s: String, maxW: Int): String {
        if (font.width(s) <= maxW) return s
        val dots = "..."
        val sb = StringBuilder()
        for (c in s) {
            if (font.width(sb.toString() + c + dots) > maxW) break
            sb.append(c)
        }
        return "$sb$dots"
    }

    private fun currentThumbnail(): Identifier? {
        val url = displayScreen?.videoUrl ?: return null
        val id = YtDlp.extractVideoId(url) ?: return null
        Thumbnails.get(id)?.let { return it }
        Thumbnails.request(id, "https://i.ytimg.com/vi/$id/mqdefault.jpg")
        return null
    }

    private fun renderSettingsSection(g: GuiGraphicsExtractor, px: Int, py: Int, pw: Int, ph: Int) {
        val innerX = px + PANEL_PADDING_X
        val innerY = py + headerHeight()
        val innerW = pw - PANEL_PADDING_X * 2

        var rowY = innerY
        val volumeRowY = rowY
        rowY = renderRow(g, innerX, rowY, innerW, "dreamdisplays.button.volume", volume, volumeReset)
        val renderDRowY = rowY
        rowY = renderRow(g, innerX, rowY, innerW, "dreamdisplays.button.render-distance", renderD, renderDReset)
        val qualityRowY = rowY
        rowY = renderRow(g, innerX, rowY, innerW, "dreamdisplays.button.quality", quality, qualityReset)
        val brightnessRowY = rowY
        rowY = renderRow(g, innerX, rowY, innerW, "dreamdisplays.button.brightness", brightness, brightnessReset)
        rowY += 6
        val syncRowY = rowY
        renderRow(g, innerX, rowY, innerW, "dreamdisplays.button.synchronization", sync, syncReset)

        volumeHover = labelHover(innerX + 6, volumeRowY, "dreamdisplays.button.volume")
        renderDHover = labelHover(innerX + 6, renderDRowY, "dreamdisplays.button.render-distance")
        qualityHover = labelHover(innerX + 6, qualityRowY, "dreamdisplays.button.quality")
        brightnessHover = labelHover(innerX + 6, brightnessRowY, "dreamdisplays.button.brightness")
        syncHover = labelHover(innerX + 6, syncRowY, "dreamdisplays.button.synchronization")
    }

    private fun labelHover(x: Int, rowY: Int, key: String): HoverArea {
        val w = font.width(Component.translatable(key))
        val textY = rowY + ROW_H / 2 - font.lineHeight / 2
        return HoverArea(x, textY, w, font.lineHeight)
    }

    private fun renderRow(
        g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, key: String,
        control: AbstractWidget?, reset: ButtonWidget?
    ): Int {
        g.fill(x, y, x + w, y + ROW_H, ROW_BG)
        val label = Component.translatable(key)
        g.text(font, label, x + 6, y + ROW_H / 2 - font.lineHeight / 2, 0xFFFFFFFF.toInt(), false)

        var rightEdge = x + w - 4
        if (reset != null) {
            reset.x = rightEdge - RESET_W
            reset.y = y
            reset.width = RESET_W
            reset.height = ROW_H
            rightEdge -= RESET_W + 4
        }
        if (control != null) {
            val controlW = min(CONTROL_W, max(60, rightEdge - (x + 6 + font.width(label) + 8)))
            control.x = rightEdge - controlW
            control.y = y
            control.width = controlW
            control.height = ROW_H
        }
        return y + ROW_H + ROW_GAP
    }

    private fun layoutOwnerActions(sx: Int, sy: Int, sw: Int, sh: Int) {
        val btn = CTRL_BTN
        val padding = PANEL_PADDING_X
        var rightEdge = sx + sw - padding
        val yEdge = sy + sh - padding - btn

        reportButtonWidget?.let {
            it.x = rightEdge - btn; it.y = yEdge; it.width = btn; it.height = btn
            rightEdge -= btn + 4
        }
        deleteButtonWidget?.let {
            it.x = rightEdge - btn; it.y = yEdge; it.width = btn; it.height = btn
            rightEdge -= btn + 4
        }
        lockButtonWidget?.let {
            if (it.visible) {
                it.x = rightEdge - btn; it.y = yEdge; it.width = btn; it.height = btn
                rightEdge -= btn + 4
            }
        }
    }

    private fun headerHeight(): Int = PANEL_PADDING_Y + font.lineHeight + 6

    override fun mouseClicked(event: MouseButtonEvent, dbl: Boolean): Boolean {
        val mx = event.x().toInt(); val my = event.y().toInt()

        if (popoutDropdownVisible && event.button() == 0) {
            val ds = displayScreen
            if (ds != null && mx in ddX..(ddX + ddW) && my in ddY..(ddY + ddItemH * 2)) {
                popoutDropdownVisible = false
                if (my < ddY + ddItemH) {
                    ds.activateWindowMode()
                } else {
                    ds.activatePipMode()
                }
                return true
            }
            popoutDropdownVisible = false
        }

        if (modLabelHover != null && UpdateCheck.shouldShowArrow()
            && modLabelHover!!.contains(mx, my)
        ) {
            try {
                Desktop.getDesktop().browse(URI.create(MODRINTH_URL))
            } catch (_: Exception) {
            }
            return true
        }
        return super.mouseClicked(event, dbl)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (progress?.commitDragIfActive() == true) return true
        return super.mouseReleased(event)
    }

    private fun renderTooltips(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val scr = displayScreen ?: return
        if (scr.errored) return

        if (volumeHover?.contains(mouseX, mouseY) == true) {
            g.setComponentTooltipForNextFrame(
                font, listOf(
                    Component.translatable("dreamdisplays.button.volume.tooltip.1")
                        .withStyle { it.withColor(ChatFormatting.WHITE).withBold(true) },
                    Component.translatable("dreamdisplays.button.volume.tooltip.2")
                        .withStyle { it.withColor(ChatFormatting.GRAY) },
                    Component.translatable("dreamdisplays.button.volume.tooltip.3")
                        .withStyle { it.withColor(ChatFormatting.GRAY) },
                    Component.translatable(
                        "dreamdisplays.button.volume.tooltip.4",
                        volume?.let { (it.value * 200).toInt() } ?: 0)
                        .withStyle { it.withColor(ChatFormatting.GOLD) },
                ), mouseX, mouseY
            )
        }
        if (renderDHover?.contains(mouseX, mouseY) == true) {
            g.setComponentTooltipForNextFrame(
                font, listOf(
                    Component.translatable("dreamdisplays.button.render-distance.tooltip.1")
                        .withStyle { it.withColor(ChatFormatting.WHITE).withBold(true) },
                    Component.translatable("dreamdisplays.button.render-distance.tooltip.2")
                        .withStyle { it.withColor(ChatFormatting.GRAY) },
                    Component.translatable("dreamdisplays.button.render-distance.tooltip.3")
                        .withStyle { it.withColor(ChatFormatting.GRAY) },
                    Component.literal(""),
                    Component.translatable(
                        "dreamdisplays.button.render-distance.tooltip.8",
                        renderD?.let { (it.value * (128 - 24) + 24).toInt() } ?: 0)
                        .withStyle { it.withColor(ChatFormatting.GOLD) },
                ), mouseX, mouseY
            )
        }
        if (qualityHover?.contains(mouseX, mouseY) == true && quality != null) {
            val tip = mutableListOf<Component>(
                Component.translatable("dreamdisplays.button.quality.tooltip.1")
                    .withStyle { it.withColor(ChatFormatting.WHITE).withBold(true) },
                Component.translatable("dreamdisplays.button.quality.tooltip.2")
                    .withStyle { it.withColor(ChatFormatting.GRAY) },
                Component.literal(""),
                Component.translatable("dreamdisplays.button.quality.tooltip.4", qualityFromFraction(quality!!.value))
                    .withStyle { it.withColor(ChatFormatting.GOLD) },
            )
            try {
                if (scr.quality.toInt() >= 1080) {
                    tip.add(
                        Component.translatable("dreamdisplays.button.quality.tooltip.5")
                            .withStyle { it.withColor(ChatFormatting.YELLOW) })
                }
            } catch (_: NumberFormatException) {
            }
            g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY)
        }
        if (brightnessHover?.contains(mouseX, mouseY) == true) {
            g.setComponentTooltipForNextFrame(
                font, listOf(
                    Component.translatable("dreamdisplays.button.brightness.tooltip.1")
                        .withStyle { it.withColor(ChatFormatting.WHITE).withBold(true) },
                    Component.translatable("dreamdisplays.button.brightness.tooltip.2")
                        .withStyle { it.withColor(ChatFormatting.GRAY) },
                    Component.literal(""),
                    Component.translatable(
                        "dreamdisplays.button.brightness.tooltip.3",
                        brightness?.let { floor(it.value * 200).toInt() } ?: 100)
                        .withStyle { it.withColor(ChatFormatting.GOLD) },
                ), mouseX, mouseY
            )
        }
        if (syncHover?.contains(mouseX, mouseY) == true && sync != null) {
            g.setComponentTooltipForNextFrame(
                font, listOf(
                    Component.translatable("dreamdisplays.button.synchronization.tooltip.1")
                        .withStyle { it.withColor(ChatFormatting.WHITE).withBold(true) },
                    Component.translatable("dreamdisplays.button.synchronization.tooltip.2")
                        .withStyle { it.withColor(ChatFormatting.GRAY) },
                    Component.translatable("dreamdisplays.button.synchronization.tooltip.3")
                        .withStyle { it.withColor(ChatFormatting.GRAY) },
                    Component.literal(""),
                    Component.translatable(
                        "dreamdisplays.button.synchronization.tooltip.5",
                        if (sync!!.value) Component.translatable("dreamdisplays.button.enabled")
                        else Component.translatable("dreamdisplays.button.disabled")
                    )
                        .withStyle { it.withColor(ChatFormatting.GOLD) },
                ), mouseX, mouseY
            )
        }

        lockButtonWidget?.let {
            val locked = displayScreen?.isLocked
            if (locked != null && hovered(mouseX, mouseY, it)) {
                g.setComponentTooltipForNextFrame(
                    font, listOf(
                        Component.translatable(if (locked) "dreamdisplays.button.lock.tooltip.1" else "dreamdisplays.button.unlock.tooltip.1")
                            .withStyle { s -> s.withColor(ChatFormatting.WHITE).withBold(true) },
                        Component.translatable(if (locked) "dreamdisplays.button.lock.tooltip.2" else "dreamdisplays.button.unlock.tooltip.2")
                            .withStyle { s -> s.withColor(ChatFormatting.GRAY) },
                    ), mouseX, mouseY
                )
            }
        }
        deleteButtonWidget?.let {
            if (hovered(mouseX, mouseY, it)) {
                g.setComponentTooltipForNextFrame(
                    font, listOf(
                        Component.translatable("dreamdisplays.button.delete.tooltip.1")
                            .withStyle { s -> s.withColor(ChatFormatting.WHITE).withBold(true) },
                        Component.translatable("dreamdisplays.button.delete.tooltip.2")
                            .withStyle { s -> s.withColor(ChatFormatting.GRAY) },
                    ), mouseX, mouseY
                )
            }
        }
        reportButtonWidget?.let {
            if (hovered(mouseX, mouseY, it)) {
                g.setComponentTooltipForNextFrame(
                    font, listOf(
                        Component.translatable("dreamdisplays.button.report.tooltip.1")
                            .withStyle { s -> s.withColor(ChatFormatting.WHITE).withBold(true) },
                        Component.translatable("dreamdisplays.button.report.tooltip.2")
                            .withStyle { s -> s.withColor(ChatFormatting.GRAY) },
                    ), mouseX, mouseY
                )
            }
        }
    }

    private fun drawPanel(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, title: String) {
        g.fill(x, y, x + w, y + h, PANEL_BG)
        val b = PANEL_BORDER
        g.fill(x, y, x + w, y + 1, b)
        g.fill(x, y + h - 1, x + w, y + h, b)
        g.fill(x, y, x + 1, y + h, b)
        g.fill(x + w - 1, y, x + w, y + h, b)
        g.text(font, title, x + PANEL_PADDING_X, y + PANEL_PADDING_Y, 0xFFFFFFFF.toInt(), false)
    }

    private fun qualityFraction(q: String): Double {
        val ds = displayScreen ?: return 0.0
        val list = ds.qualityList
        if (list.isEmpty()) return 0.0
        val target = try {
            q.replace("p", "").toInt()
        } catch (_: Exception) {
            720
        }
        var closest = list[0]
        var minDiff = abs(target - closest)
        for (v in list) {
            val d = abs(target - v)
            if (d < minDiff) {
                minDiff = d; closest = v
            }
        }
        return list.indexOf(closest) / max(1, list.size - 1).toDouble()
    }

    private fun qualityFromFraction(v: Double): String {
        val ds = displayScreen ?: return "720"
        val list = ds.qualityList
        if (list.isEmpty()) return "144"
        var idx = (v * (list.size - 1)).roundToInt()
        idx = max(0, min(list.size - 1, idx))
        return list[idx].toString()
    }

    override fun isPauseScreen(): Boolean = false

    private fun renderModLabel(g: GuiGraphicsExtractor, x: Int, y: Int) {
        val update = UpdateCheck.shouldShowArrow()
        val name = Component.literal("Dream Displays")
        val ver = Component.literal(" ${GeneralUtil.getModVersion()}")
            .withStyle(Style.EMPTY.withColor(0xFF6AB7FF.toInt()))
        val label = name.copy().append(ver)
        g.text(font, label, x, y, 0xFFFFFFFF.toInt(), true)

        val textW = font.width(label)
        var totalW = textW
        if (update) {
            val t = ((System.currentTimeMillis() - modLabelOpenedAtMs) % 1800L) / 1800f
            var arrowYOffset = 0
            if (t < 0.25f) {
                val p = t / 0.25f
                arrowYOffset = (-sin(p * Math.PI) * 3.0).toInt()
            }
            val arrow = Component.literal(" ▲")
                .withStyle(Style.EMPTY.withColor(0xFFFF4040.toInt()))
            g.text(font, arrow, x + textW, y + arrowYOffset, 0xFFFFFFFF.toInt(), true)
            totalW += font.width(arrow)
        }
        modLabelHover = HoverArea(x, y - 1, totalW, font.lineHeight + 2)
    }

    private data class HoverArea(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(mx: Int, my: Int): Boolean = mx >= x && mx < x + w && my >= y && my < y + h
    }

    companion object {
        private const val PADDING = 10
        private const val PANEL_GAP = 8
        private const val PANEL_PADDING_X = 10
        private const val PANEL_PADDING_Y = 10
        private const val ROW_GAP = 4
        private const val CTRL_BTN = 22
        private const val ROW_H = CTRL_BTN
        private const val RESET_W = CTRL_BTN
        private const val CONTROL_W = 130
        private const val PANEL_BG = 0x90101010.toInt()
        private const val PANEL_BORDER = 0xFF606060.toInt()
        private const val ROW_BG = 0x40000000
        private const val MODRINTH_URL = "https://modrinth.com/plugin/dreamdisplays/versions"


        fun open(displayScreen: DisplayScreen) {
            val s = DisplayMenu()
            s.displayScreen = displayScreen
            MinecraftScreenUtil.setScreen(Minecraft.getInstance(), s)
        }

        private fun hovered(mx: Int, my: Int, w: AbstractWidget): Boolean =
            mx >= w.x && mx < w.x + w.width && my >= w.y && my < w.y + w.height
    }
}
