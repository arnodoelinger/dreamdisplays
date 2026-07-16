package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.playback.PlaybackServices
import com.dreamdisplays.api.watchparty.WatchPartyServices
import com.dreamdisplays.api.display.service.DisplayServices
import com.dreamdisplays.api.playback.FullscreenMode
import com.dreamdisplays.platform.client.popout.PopoutManager
import com.dreamdisplays.platform.client.render.ScrubPreview
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.api.runtime.get
import com.dreamdisplays.api.runtime.getOrNull
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.UiScreenBase
import com.dreamdisplays.platform.client.ui.kit.drawPanel
import com.dreamdisplays.platform.client.ui.menu.ErrorPanel
import com.dreamdisplays.platform.client.ui.menu.MenuLayout
import com.dreamdisplays.platform.client.ui.menu.ModTitleLabel
import com.dreamdisplays.platform.client.ui.menu.PopoutDropdown
import com.dreamdisplays.platform.client.ui.menu.PreviewSection
import com.dreamdisplays.platform.client.ui.menu.SettingsSection
import com.dreamdisplays.platform.client.ui.widgets.IconButton
import com.dreamdisplays.platform.client.ui.widgets.ModeSlider
import com.dreamdisplays.platform.client.ui.widgets.SeekBar
import com.dreamdisplays.platform.client.ui.widgets.SuggestionsPanel
import com.dreamdisplays.platform.client.ui.widgets.ValueSlider
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.api.media.MediaServices
import com.dreamdisplays.api.media.audio.AcousticQuality
import com.dreamdisplays.api.media.audio.AudioAcousticsServices
import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.api.media.VideoQuality
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import com.dreamdisplays.media.source.ytdlp.VideoMetadataCache
import com.dreamdisplays.media.source.ytdlp.VideoTitleCache
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
//?}
import net.minecraft.network.chat.Component
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The display configuration screen: video preview with playback controls, the settings rows, and
 * the suggestions panel.
 */
class DisplayMenu private constructor(
    val displayScreen: DisplayScreen,
) : UiScreenBase(Component.translatable("dreamdisplays.ui.title")) {

    private val modLabel = ModTitleLabel()
    private val popout = DreamServices.registry.get<PopoutManager>()
    private val dropdown = PopoutDropdown(
        onWindow = { popout.openWindow(DisplayId(displayScreen.uuid)) },
        onPip = { popout.openPip(DisplayId(displayScreen.uuid)) },
        onFullscreen = { popout.openFullscreen(DisplayId(displayScreen.uuid), FullscreenMode.STANDARD); onClose() },
        onBorderless = { popout.openFullscreen(DisplayId(displayScreen.uuid), FullscreenMode.IMMERSIVE); onClose() },
    )

    private lateinit var volume: ValueSlider
    private lateinit var renderD: ValueSlider
    private lateinit var quality: ValueSlider
    private lateinit var brightness: ValueSlider
    private lateinit var audio3d: ModeSlider<AcousticQuality>
    private lateinit var sync: ModeSlider<PlaybackMode>
    private lateinit var progress: SeekBar
    private lateinit var suggestions: SuggestionsPanel
    private lateinit var preview: PreviewSection
    private lateinit var settings: SettingsSection
    private lateinit var errorPanel: ErrorPanel

    private var lastSuggestedVideoId: String? = null
    private var prevQualityListSize = 0
    private var suggestionsRect: UiRect? = null

    override fun init() {
        super.init()
        val ds = displayScreen
        // Playback controls drive the display through the core PlaybackService instead of mutating
        // the DisplayScreen directly, so the UI no longer reaches into the live screen for these actions.
        val displayId = DisplayId(ds.uuid)
        val playback = DreamServices.registry.get(PlaybackServices.PLAYBACK)
        val watchParty = DreamServices.registry.get(WatchPartyServices.WATCH_PARTY)
        val displays = DreamServices.registry.get(DisplayServices.DISPLAY)
        val videoReady = { ds.isVideoStarted && !ds.errored }
        val notErrored = { !ds.errored }

        // Migrate legacy block-based renderDistance to the nearest valid chunk multiple (2–12 chunks).
        val migratedChunks = (ds.renderDistance / 16.0).roundToInt().coerceIn(MIN_CHUNKS, MAX_CHUNKS)
        val migratedBlocks = migratedChunks * CHUNK_BLOCKS
        if (ds.renderDistance != migratedBlocks) {
            ds.renderDistance = migratedBlocks
            DisplayRegistry.saveScreenData(ds)
        }

        volume = addUi(
            ValueSlider(
                initial = ds.volume.toDouble(),
                label = { Component.literal("${floor(it * 200).toInt()}%") },
                // Volume's fraction maps to 0-200%, so a 5%-of-displayed-value stop is 0.025 of the fraction.
                step = 0.025,
            ) { playback.setVolume(displayId, it.toFloat()) })
        volume.enabledWhen = videoReady
        volume.visibleWhen = notErrored

        renderD = addUi(
            ValueSlider(
                initial = chunksToFraction(ds.renderDistance / CHUNK_BLOCKS),
                label = { Component.translatable("dreamdisplays.button.render-distance.label", fractionToChunks(it)) },
                // One chunk per stop: CHUNK_STEPS+1 fixed positions from MIN_CHUNKS to MAX_CHUNKS
                step = 1.0 / CHUNK_STEPS,
            ) {
                ds.renderDistance = fractionToChunks(it) * CHUNK_BLOCKS
                DisplayRegistry.saveScreenData(ds)
            })
        renderD.enabledWhen = { videoReady() && !ds.isPopoutActive }
        renderD.visibleWhen = notErrored

        quality = addUi(
            ValueSlider(
                initial = qualityFraction(ds.quality.serialize()),
                label = {
                    when {
                        // Broadcast pins everyone to the highest quality within the cap; show that, not the saved setting
                        ds.qualityCap > 0 -> Component.literal("${broadcastQuality()}p")
                        ds.qualityList.isNotEmpty() -> Component.literal("${qualityFromFraction(it)}p")
                        else -> Component.literal("${ds.quality.serialize()}p")
                    }
                },
                // One stop per available quality, so the handle can only ever rest exactly on a real option
                step = qualityStep(ds.qualityList.size),
                // Commit on release: applying live would restart the decoder on every stop crossed
                // while dragging, which can drop videoReady() mid-drag and freeze the widget.
                live = false,
            ) {
                if (ds.qualityList.isNotEmpty()) playback.setQuality(
                    displayId,
                    VideoQuality.parse(qualityFromFraction(it))
                )
            })
        quality.enabledWhen = { videoReady() && ds.qualityList.isNotEmpty() && ds.canChangeQualityHere }
        quality.visibleWhen = notErrored

        brightness = addUi(
            ValueSlider(
                initial = ds.brightness.toDouble().coerceIn(0.0, 1.0),
                label = { Component.literal("${floor(it * 100).toInt()}%") },
                step = 0.05,
            ) { playback.setBrightness(displayId, it.toFloat()) })
        brightness.enabledWhen = { videoReady() && (!ds.isSync || ds.canEdit) }
        brightness.visibleWhen = notErrored

        audio3d = addUi(
            ModeSlider(
                modes = AUDIO_3D_MODES,
                initial = ClientStateManager.config.audioAcoustics,
                current = { ClientStateManager.config.audioAcoustics },
                enabledFor = { true },
                label = { Component.translatable(audio3dModeLabel(it)) },
            ) { quality ->
                ClientStateManager.config.audioAcoustics = quality
                ClientStateManager.config.save()
                DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.setGlobalQuality(quality)
            })
        audio3d.visibleWhen = notErrored

        sync = addUi(
            ModeSlider(
                modes = SYNC_MODES,
                initial = ds.effectiveMode,
                current = { ds.effectiveMode },
                enabledFor = {
                    if (ds.watchParty != null) {
                        it == PlaybackMode.LOCAL && ds.canCloseWatchPartyHere
                    } else {
                        it != PlaybackMode.WATCH_PARTY && ds.canSetModeHere
                    }
                },
                label = { Component.translatable(syncModeLabel(it)) },
            ) { mode ->
                when {
                    mode == ds.effectiveMode -> Unit
                    ds.watchParty != null && mode == PlaybackMode.LOCAL -> watchParty.close(displayId)
                    PlaybackMode.isBaseMode(mode) -> playback.setMode(displayId, mode)
                }
            })
        sync.enabledWhen = {
            videoReady() && (ds.canSetModeHere || (ds.watchParty != null && ds.canCloseWatchPartyHere))
        }
        sync.visibleWhen = notErrored

        val renderDReset = addUi(IconButton("refresh") {
            val defaultChunks =
                (ClientStateManager.config.defaultDistance / CHUNK_BLOCKS).coerceIn(MIN_CHUNKS, MAX_CHUNKS)
            ds.renderDistance = defaultChunks * CHUNK_BLOCKS
            renderD.value = chunksToFraction(defaultChunks)
            DisplayRegistry.saveScreenData(ds)
        })
        renderDReset.enabledWhen = {
            val defaultBlocks = (ClientStateManager.config.defaultDistance / CHUNK_BLOCKS).coerceIn(
                MIN_CHUNKS,
                MAX_CHUNKS
            ) * CHUNK_BLOCKS
            videoReady() && !ds.isPopoutActive && ds.renderDistance != defaultBlocks
        }
        renderDReset.visibleWhen = notErrored

        val qualityReset = addUi(IconButton("refresh") {
            playback.setQuality(displayId, VideoQuality.DEFAULT)
            quality.value = qualityFraction(VideoQuality.DEFAULT.serialize())
        })
        qualityReset.enabledWhen = { videoReady() && ds.canChangeQualityHere && ds.quality != VideoQuality.DEFAULT }
        qualityReset.visibleWhen = notErrored

        val brightnessReset = addUi(IconButton("refresh") {
            playback.setBrightness(displayId, 1.0f)
            brightness.value = 1.0
        })
        brightnessReset.enabledWhen = { videoReady() && abs(brightness.value - 1.0) > 0.01 }
        brightnessReset.visibleWhen = notErrored

        val audio3dReset = addUi(IconButton("refresh") {
            ClientStateManager.config.audioAcoustics = AUDIO_3D_DEFAULT
            ClientStateManager.config.save()
            DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.setGlobalQuality(AUDIO_3D_DEFAULT)
        })
        audio3dReset.enabledWhen = { ClientStateManager.config.audioAcoustics != AUDIO_3D_DEFAULT }
        audio3dReset.visibleWhen = notErrored

        val syncReset = addUi(IconButton("refresh") {
            if (ds.canSetModeHere) playback.setMode(displayId, PlaybackMode.LOCAL)
        })
        syncReset.enabledWhen = { videoReady() && ds.canSetModeHere && ds.effectiveMode != PlaybackMode.LOCAL }
        syncReset.visibleWhen = notErrored

        val muteButton = addUi(
            IconButton(
                icon = { IconButton.modIcon(if (ds.muted) "mute" else "sound") },
            ) { playback.mute(displayId, !ds.muted) })
        muteButton.enabledWhen = videoReady
        muteButton.visibleWhen = notErrored

        val popoutButton = addUi(IconButton("popout") {
            if (ds.isPopoutActive) {
                popout.close(displayId)
                dropdown.hide()
            } else {
                dropdown.toggle()
            }
        })
        popoutButton.enabledWhen = { videoReady() && (ds.canPopoutHere || ds.isPopoutActive) }
        popoutButton.visibleWhen = notErrored

        val pauseButton = addUi(
            IconButton(
                icon = { IconButton.modIcon(if (ds.isPaused) "play" else "pause") },
            ) { if (ds.isPaused) playback.play(displayId) else playback.pause(displayId) })
        pauseButton.enabledWhen = { ds.canControlPlayback }
        pauseButton.visibleWhen = notErrored

        progress = addUi(
            SeekBar(
                current = { ds.currentTimeNanos },
                duration = { ds.mediaPlayerDurationNanos },
                previewFrame = { nanos ->
                    if (ds.isLive) null else {
                        val key = ds.videoUrl
                        val rawUrl = ds.scrubPreviewRawUrl
                        val dur = ds.mediaPlayerDurationNanos
                        if (key != null && rawUrl != null) ScrubPreview.request(key, rawUrl, dur)
                        key?.let { ScrubPreview.frameAt(it, nanos) }
                    }
                },
            ) { nanos ->
                if (ds.canSeek() && !ds.isLive && ds.canSeekHere) {
                    playback.seek(displayId, (nanos / 1_000_000L).milliseconds)
                }
            })
        progress.enabledWhen = { videoReady() && ds.canSeek() && !ds.isLive && ds.canSeekHere }
        progress.visibleWhen = notErrored

        val lockButton = addUi(
            IconButton(
                icon = { IconButton.modIcon(if (ds.isLocked == true) "lock" else "unlock") },
            ) {
                val locked = ds.isLocked ?: return@IconButton
                displays.setLocked(displayId, !locked)
            })
        lockButton.enabledWhen = { ds.canToggleLockHere }
        lockButton.visibleWhen = { ds.isLocked != null && !ds.errored }

        val retryButton = addUi(IconButton("refresh") {
            playback.retry(displayId) // Local re-resolve; the error panel clears itself once it succeeds
        })
        // Only the error panel places it; keep it hidden in the normal menu so it never strays to (0,0)
        retryButton.visibleWhen = { ds.errored }

        val deleteButton = addUi(
            IconButton(
                icon = { IconButton.modIcon("delete") },
                sprites = IconButton.RED_SPRITES,
            ) {
                displays.delete(displayId)
                onClose()
            })
        deleteButton.enabledWhen = { ds.owner || ds.isAdmin }

        val reportButton = if (ClientStateManager.isReportingEnabled) {
            addUi(
                IconButton(
                    icon = { IconButton.modIcon("report") },
                    sprites = IconButton.RED_SPRITES,
                ) {
                    displays.report(displayId)
                    onClose()
                })
        } else null

        suggestions = addUi(SuggestionsPanel(::onPickSuggested))
        suggestions.visibleWhen = { !ds.errored && suggestionsRect != null }
        // Locked / Broadcast / Watch party displays only let the owner / admin change the video, so
        // the panel shows an "unavailable" notice to everyone else instead of pickable suggestions.
        suggestions.available = { ds.canSetVideoHere }

        preview =
            PreviewSection(ds, muteButton, volume, popoutButton, pauseButton, progress, dropdown)
        settings = SettingsSection(
            rows = settingsRows(renderDReset, qualityReset, brightnessReset, audio3dReset, syncReset),
            ownerActions = listOf(reportButton, deleteButton, lockButton),
            buttonTooltips = listOf(
                lockButton to {
                    ds.isLocked?.let { locked ->
                        listOf(
                            Component.translatable(if (locked) "dreamdisplays.button.unlock.tooltip.1" else "dreamdisplays.button.lock.tooltip.1")
                                .withStyle { it.withColor(ChatFormatting.WHITE).withBold(true) },
                            Component.translatable(if (locked) "dreamdisplays.button.unlock.tooltip.2" else "dreamdisplays.button.lock.tooltip.2")
                                .withStyle { it.withColor(ChatFormatting.GRAY) },
                        )
                    }
                },
                deleteButton to { buttonTooltip("dreamdisplays.button.delete") },
                reportButton to { buttonTooltip("dreamdisplays.button.report") },
            ),
        )
        errorPanel = ErrorPanel(retryButton, deleteButton, reportButton) { ds.mediaError }
    }

    /** Builds the settings rows with their tooltip content. */
    private fun settingsRows(
        renderDReset: IconButton, qualityReset: IconButton,
        brightnessReset: IconButton, audio3dReset: IconButton, syncReset: IconButton,
    ): List<SettingsSection.Row> {
        val ds = displayScreen
        return listOf(
            SettingsSection.Row("dreamdisplays.button.render-distance", renderD, renderDReset) {
                listOf(
                    tooltipTitle("dreamdisplays.button.render-distance.tooltip.1"),
                    tooltipBody("dreamdisplays.button.render-distance.tooltip.2"),
                    Component.literal(""),
                    tooltipValue("dreamdisplays.button.render-distance.tooltip.7", fractionToChunks(renderD.value)),
                )
            },
            SettingsSection.Row("dreamdisplays.button.quality", quality, qualityReset) {
                val tip = mutableListOf(
                    tooltipTitle("dreamdisplays.button.quality.tooltip.1"),
                    tooltipBody("dreamdisplays.button.quality.tooltip.2"),
                    Component.literal(""),
                    tooltipValue("dreamdisplays.button.quality.tooltip.4", qualityFromFraction(quality.value)),
                )
                if ((ds.quality.targetHeight ?: 0) >= 1080) {
                    tip.add(
                        Component.translatable("dreamdisplays.button.quality.tooltip.5")
                            .withStyle { it.withColor(ChatFormatting.YELLOW) },
                    )
                }
                tip
            },
            SettingsSection.Row("dreamdisplays.button.brightness", brightness, brightnessReset) {
                listOf(
                    tooltipTitle("dreamdisplays.button.brightness.tooltip.1"),
                    tooltipBody("dreamdisplays.button.brightness.tooltip.2"),
                    Component.literal(""),
                    tooltipValue("dreamdisplays.button.brightness.tooltip.3", floor(brightness.value * 100).toInt()),
                )
            },
            SettingsSection.Row("dreamdisplays.button.audio3d", audio3d, audio3dReset) {
                listOf(
                    tooltipTitle("dreamdisplays.button.audio3d.tooltip.1"),
                    tooltipBody("dreamdisplays.button.audio3d.tooltip.2"),
                    Component.literal(""),
                    tooltipModeBullet("dreamdisplays.mode.audio_off", "dreamdisplays.button.audio3d.tooltip.3"),
                    tooltipModeBullet("dreamdisplays.mode.audio_enhanced", "dreamdisplays.button.audio3d.tooltip.4"),
                    tooltipModeBullet("dreamdisplays.mode.audio_advanced", "dreamdisplays.button.audio3d.tooltip.5"),
                    Component.literal(""),
                    tooltipValue(
                        "dreamdisplays.button.audio3d.tooltip.6",
                        Component.translatable(audio3dModeLabel(audio3d.mode)),
                    ),
                )
            },
            SettingsSection.Row("dreamdisplays.button.synchronization", sync, syncReset, extraGapBefore = 6) {
                listOf(
                    tooltipTitle("dreamdisplays.button.synchronization.tooltip.1"),
                    tooltipBody("dreamdisplays.button.synchronization.tooltip.2"),
                    Component.literal(""),
                    tooltipModeBullet("dreamdisplays.mode.local", "dreamdisplays.button.synchronization.tooltip.3"),
                    tooltipModeBullet("dreamdisplays.mode.synced", "dreamdisplays.button.synchronization.tooltip.4"),
                    tooltipModeBullet("dreamdisplays.mode.broadcast", "dreamdisplays.button.synchronization.tooltip.6"),
                    Component.literal(""),
                    tooltipValue(
                        "dreamdisplays.button.synchronization.tooltip.5",
                        Component.translatable(syncModeLabel(sync.mode)),
                    ),
                )
            },
        )
    }

    private fun tooltipTitle(key: String): Component =
        Component.translatable(key).withStyle { it.withColor(ChatFormatting.WHITE).withBold(true) }

    private fun tooltipBody(key: String): Component =
        Component.translatable(key).withStyle { it.withColor(ChatFormatting.GRAY) }

    private fun tooltipValue(key: String, arg: Any): Component =
        Component.translatable(key, arg).withStyle { it.withColor(ChatFormatting.GOLD) }

    /** Bullet line naming a playback mode ([modeKey], e.g. `dreamdisplays.mode.local`) plus its short [descKey]. */
    private fun tooltipModeBullet(modeKey: String, descKey: String): Component =
        Component.literal("• ").withStyle { it.withColor(ChatFormatting.GRAY) }
            .append(Component.translatable(modeKey).withStyle { it.withColor(ChatFormatting.GRAY) })
            .append(Component.literal(": ").withStyle { it.withColor(ChatFormatting.GRAY) })
            .append(Component.translatable(descKey).withStyle { it.withColor(ChatFormatting.GRAY) })

    /** Two-line white/gray tooltip used by the delete and report buttons. */
    private fun buttonTooltip(prefix: String): List<Component> = listOf(
        tooltipTitle("$prefix.tooltip.1"),
        tooltipBody("$prefix.tooltip.2"),
    )

    /** Requests [info] as the display video and reloads the related list once the intent is sent. */
    private fun onPickSuggested(info: MediaSearchResult) {
        val ds = displayScreen
        if (!ds.canSetVideoHere) return
        DreamServices.registry.get(DisplayServices.DISPLAY).setUrl(DisplayId(ds.uuid), info.getWatchUrl(), ds.lang)
        VideoTitleCache.put(info.id, info.title)
        VideoMetadataCache.put(info.id, info)
        lastSuggestedVideoId = info.id
        suggestions.setRelatedTo(info.id)
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawScreenBackground(g)
        val ds = displayScreen

        modLabel.draw(g, UiTheme.SCREEN_PADDING, 6)
        resyncQualitySlider()
        resyncModeSlider()
        audio3d.syncToCurrent()

        if (ds.errored) {
            dropdown.hide()
            suggestionsRect = null
            errorPanel.render(g, width, height)
            drawChildren(g, mouseX, mouseY, partialTick)
            return
        }

        val layout = MenuLayout.compute(width, height, font.lineHeight)
        suggestionsRect = layout.suggestions

        g.drawPanel(font, layout.preview, Component.translatable("dreamdisplays.ui.preview").string)
        g.drawPanel(font, layout.settings, Component.translatable("dreamdisplays.ui.settings").string)
        preview.render(g, layout.preview)
        settings.render(g, layout.settings)

        val suggestionsArea = layout.suggestions
        if (suggestionsArea != null) {
            suggestions.visible = true
            suggestions.setVertical(layout.suggestionsVertical)
            suggestions.setCompactCards(false)
            suggestions.place(suggestionsArea)
        } else {
            suggestions.visible = false
        }
        refreshRelatedVideos()

        drawChildren(g, mouseX, mouseY, partialTick)
        settings.renderTooltips(g, mouseX, mouseY, toRealX(mouseX), toRealY(mouseY))
    }

    /** Re-syncs the quality slider position when the available quality list (re)appears. */
    private fun resyncQualitySlider() {
        val ds = displayScreen
        val qualityList = ds.qualityList
        if (qualityList.size != prevQualityListSize) {
            prevQualityListSize = qualityList.size
            quality.step = qualityStep(qualityList.size)
            if (qualityList.isNotEmpty()) {
                // In Broadcast the handle should sit on the capped quality, not the user's saved value.
                quality.value = qualityFraction(
                    if (ds.qualityCap > 0) broadcastQuality().toString() else ds.quality.serialize()
                )
            }
        }
    }

    /** Keeps the synchronization mode slider aligned with server echoes and watch-party state. */
    private fun resyncModeSlider() {
        sync.syncToCurrent()
    }

    /** Points the suggestions panel at the currently playing video when it changes. */
    private fun refreshRelatedVideos() {
        val ds = displayScreen
        val currentId = DreamServices.registry.getOrNull(MediaServices.SEARCH)?.extractVideoId(ds.videoUrl ?: "")
        if (currentId != null && currentId != lastSuggestedVideoId) {
            lastSuggestedVideoId = currentId
            suggestions.setRelatedTo(currentId)
        }
    }

    //? if >=1.21.11 {
    override fun onMouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        // Coordinates are already in virtual space (UiScreenBase converted them); dropdown and mod
        // label are laid out in that same space, so hit-testing matches what's drawn at any GUI scale.
        val mx = event.x().toInt()
        val my = event.y().toInt()
        if (dropdown.visible && event.button() == 0 && dropdown.handleClick(mx, my)) return true
        return modLabel.handleClick(mx, my)
    }

    override fun onMouseReleased(event: MouseButtonEvent): Boolean = progress.commitDragIfActive()
    //?} else
    /*override fun onMouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        if (dropdown.visible && button == 0 && dropdown.handleClick(mx, my)) return true
        return modLabel.handleClick(mx, my)
    }

    override fun onMouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = progress.commitDragIfActive()*/

    override fun isPauseScreen(): Boolean = false

    override fun removed() {
        if (::preview.isInitialized) preview.close()
        super.removed()
    }

    /**
     * The menu needs roughly this much logical space for the normal (non-compact) layout — preview and
     * settings side by side on top, suggestions strip below. On smaller windows (e.g. high GUI scale)
     * [UiScreenBase] scales the whole menu down to fit instead of letting panels overflow.
     */
    override fun minContentSize(): Pair<Int, Int> = MIN_CONTENT_W to MIN_CONTENT_H

    /** The highest available quality within Broadcast's cap — what every client is actually pinned to. */
    private fun broadcastQuality(): Int {
        val ds = displayScreen
        val cap = ds.qualityCap
        return ds.qualityList.filter { it <= cap }.maxOrNull() ?: cap
    }

    /** The slider step for [size] evenly spaced quality stops (1 per available option). */
    private fun qualityStep(size: Int): Double = 1.0 / max(1, size - 1)

    /** Maps a quality string (e.g. "720") to its fractional position within the available quality list. */
    private fun qualityFraction(q: String): Double {
        val list = displayScreen.qualityList
        if (list.isEmpty()) return 0.0
        val target = q.replace("p", "").toIntOrNull() ?: 720
        val closest = list.minByOrNull { abs(target - it) } ?: return 0.0
        return list.indexOf(closest) / max(1, list.size - 1).toDouble()
    }

    /** Maps a fractional slider position back to the nearest quality string from the available list. */
    private fun qualityFromFraction(v: Double): String {
        val list = displayScreen.qualityList
        if (list.isEmpty()) return "144"
        val idx = (v * (list.size - 1)).roundToInt().coerceIn(0, list.size - 1)
        return list[idx].toString()
    }

    companion object {
        /** Minimum logical canvas the normal layout is comfortable in; smaller windows scale down. */
        private const val MIN_CONTENT_W = 640
        private const val MIN_CONTENT_H = 410

        private const val CHUNK_BLOCKS = 16
        private const val MIN_CHUNKS = 2
        private const val MAX_CHUNKS = 12
        private const val CHUNK_STEPS = MAX_CHUNKS - MIN_CHUNKS  // 10

        /** Converts a chunk count (2–12) to a slider fraction (0.0–1.0). */
        private fun chunksToFraction(chunks: Int): Double =
            (chunks.coerceIn(MIN_CHUNKS, MAX_CHUNKS) - MIN_CHUNKS) / CHUNK_STEPS.toDouble()

        /** Converts a slider fraction (0.0–1.0) to a snapped chunk count (2–12). */
        private fun fractionToChunks(fraction: Double): Int =
            (fraction * CHUNK_STEPS).roundToInt() + MIN_CHUNKS

        /** Translation key for the compact mode label shown inside the sync slider. */
        private fun syncModeLabel(mode: PlaybackMode): String = when (mode) {
            PlaybackMode.LOCAL -> "dreamdisplays.mode.local"
            PlaybackMode.SYNCED -> "dreamdisplays.mode.synced"
            PlaybackMode.WATCH_PARTY -> "dreamdisplays.mode.watch_party"
            PlaybackMode.BROADCAST -> "dreamdisplays.mode.broadcast"
        }

        /** The three tiers exposed by the 3D audio slider; BASIC stays an internal-only engine step. */
        private val AUDIO_3D_MODES = listOf(AcousticQuality.OFF, AcousticQuality.ADVANCED, AcousticQuality.ULTRA)

        /** Factory default the 3D audio row's reset button restores. */
        private val AUDIO_3D_DEFAULT = AcousticQuality.ADVANCED

        /** Translation key for the compact mode label shown inside the 3D audio slider. */
        private fun audio3dModeLabel(quality: AcousticQuality): String = when (quality) {
            AcousticQuality.OFF -> "dreamdisplays.mode.audio_off"
            AcousticQuality.ULTRA -> "dreamdisplays.mode.audio_advanced"
            else -> "dreamdisplays.mode.audio_enhanced"
        }

        /** The three sync-mode notches exposed by the playback-mode slider. */
        private val SYNC_MODES = listOf(PlaybackMode.LOCAL, PlaybackMode.SYNCED, PlaybackMode.BROADCAST)

        /** Opens the menu for [displayScreen]. */
        fun open(displayScreen: DisplayScreen) {
            MinecraftScreenUtil.setScreen(Minecraft.getInstance(), DisplayMenu(displayScreen))
        }
    }
}
