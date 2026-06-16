package com.dreamdisplays.client.ui

import com.dreamdisplays.Initializer
import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.client.ui.kit.UiRect
import com.dreamdisplays.client.ui.kit.UiTheme
import com.dreamdisplays.client.ui.kit.UiScreenBase
import com.dreamdisplays.client.ui.kit.drawPanel
import com.dreamdisplays.client.ui.menu.ErrorPanel
import com.dreamdisplays.client.ui.menu.MenuLayout
import com.dreamdisplays.client.ui.menu.ModTitleLabel
import com.dreamdisplays.client.ui.menu.PopoutDropdown
import com.dreamdisplays.client.ui.menu.PreviewSection
import com.dreamdisplays.client.ui.menu.SettingsSection
import com.dreamdisplays.client.ui.widgets.IconButton
import com.dreamdisplays.client.ui.widgets.SeekBar
import com.dreamdisplays.client.ui.widgets.SuggestionsPanel
import com.dreamdisplays.client.ui.widgets.SyncModeSlider
import com.dreamdisplays.client.ui.widgets.ValueSlider
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.displays.DisplayScreen
import com.dreamdisplays.displays.store.DisplayStorage
import com.dreamdisplays.managers.ClientStateManager
import com.dreamdisplays.media.api.MediaSearchResult
import com.dreamdisplays.media.api.MediaSearchService
import com.dreamdisplays.media.api.VideoQuality
import com.dreamdisplays.protocol.DisplayDelete
import com.dreamdisplays.protocol.PlaybackMode
import com.dreamdisplays.protocol.ReportDisplay
import com.dreamdisplays.protocol.SetLocked
import com.dreamdisplays.utils.MinecraftScreenUtil
import com.dreamdisplays.ytdlp.VideoMetadataCache
import com.dreamdisplays.ytdlp.VideoTitleCache
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.abs
import kotlin.math.floor
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
    private val dropdown = PopoutDropdown(
        onWindow = { displayScreen.activateWindowMode() },
        onPip = { displayScreen.activatePipMode() },
    )

    private lateinit var volume: ValueSlider
    private lateinit var renderD: ValueSlider
    private lateinit var quality: ValueSlider
    private lateinit var brightness: ValueSlider
    private lateinit var sync: SyncModeSlider
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
        val videoReady = { ds.isVideoStarted && !ds.errored }
        val notErrored = { !ds.errored }

        // Migrate legacy block-based renderDistance to the nearest valid chunk multiple (2–12 chunks).
        val migratedChunks = (ds.renderDistance / 16.0).roundToInt().coerceIn(MIN_CHUNKS, MAX_CHUNKS)
        val migratedBlocks = migratedChunks * CHUNK_BLOCKS
        if (ds.renderDistance != migratedBlocks) {
            ds.renderDistance = migratedBlocks
            DisplayRegistry.saveScreenData(ds)
        }

        volume = addUi(ValueSlider(
            initial = ds.volume.toDouble(),
            label = { Component.literal("${floor(it * 200).toInt()}%") },
        ) { ds.volume = it.toFloat() })
        volume.enabledWhen = videoReady
        volume.visibleWhen = notErrored

        renderD = addUi(ValueSlider(
            initial = chunksToFraction(ds.renderDistance / CHUNK_BLOCKS),
            label = { Component.translatable("dreamdisplays.button.render-distance.label", fractionToChunks(it)) },
        ) {
            ds.renderDistance = fractionToChunks(it) * CHUNK_BLOCKS
            DisplayRegistry.saveScreenData(ds)
        })
        renderD.enabledWhen = { videoReady() && !ds.isPopoutActive }
        renderD.visibleWhen = notErrored

        quality = addUi(ValueSlider(
            initial = qualityFraction(ds.quality.serialize()),
            label = {
                when {
                    // Broadcast pins everyone to the highest quality within the cap; show that, not the saved setting
                    ds.qualityCap > 0 -> Component.literal("${broadcastQuality()}p")
                    ds.qualityList.isNotEmpty() -> Component.literal("${qualityFromFraction(it)}p")
                    else -> Component.literal("${ds.quality.serialize()}p")
                }
            },
            // Commit on release: a quality change restarts the video, so don't fire on every drag step
            live = false,
        ) {
            if (ds.qualityList.isNotEmpty()) ds.quality = VideoQuality.parse(qualityFromFraction(it))
        })
        quality.enabledWhen = { videoReady() && ds.qualityList.isNotEmpty() && ds.canChangeQualityHere }
        quality.visibleWhen = notErrored

        brightness = addUi(ValueSlider(
            initial = ds.brightness.toDouble().coerceIn(0.0, 1.0),
            label = { Component.literal("${floor(it * 100).toInt()}%") },
        ) { ds.brightness = it.toFloat() })
        brightness.enabledWhen = { videoReady() && (!ds.isSync || ds.canEdit) }
        brightness.visibleWhen = notErrored

        sync = addUi(SyncModeSlider(
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
                ds.watchParty != null && mode == PlaybackMode.LOCAL -> ds.closeWatchParty()
                PlaybackMode.isBaseMode(mode) -> ds.requestMode(mode)
            }
        })
        sync.enabledWhen = {
            videoReady() && (ds.canSetModeHere || (ds.watchParty != null && ds.canCloseWatchPartyHere))
        }
        sync.visibleWhen = notErrored

        val volumeReset = addUi(IconButton("refresh") {
            ds.volume = 0.5f
            volume.value = 0.5
        })
        volumeReset.enabledWhen = { videoReady() && abs(volume.value - 0.5) > 0.01 }
        volumeReset.visibleWhen = notErrored

        val renderDReset = addUi(IconButton("refresh") {
            val defaultChunks = (ClientStateManager.config.defaultDistance / CHUNK_BLOCKS).coerceIn(MIN_CHUNKS, MAX_CHUNKS)
            ds.renderDistance = defaultChunks * CHUNK_BLOCKS
            renderD.value = chunksToFraction(defaultChunks)
            DisplayRegistry.saveScreenData(ds)
        })
        renderDReset.enabledWhen = {
            val defaultBlocks = (ClientStateManager.config.defaultDistance / CHUNK_BLOCKS).coerceIn(MIN_CHUNKS, MAX_CHUNKS) * CHUNK_BLOCKS
            videoReady() && !ds.isPopoutActive && ds.renderDistance != defaultBlocks
        }
        renderDReset.visibleWhen = notErrored

        val qualityReset = addUi(IconButton("refresh") {
            ds.quality = VideoQuality.DEFAULT
            quality.value = qualityFraction(VideoQuality.DEFAULT.serialize())
        })
        qualityReset.enabledWhen = { videoReady() && ds.canChangeQualityHere && ds.quality != VideoQuality.DEFAULT }
        qualityReset.visibleWhen = notErrored

        val brightnessReset = addUi(IconButton("refresh") {
            ds.brightness = 1.0f
            brightness.value = 1.0
        })
        brightnessReset.enabledWhen = { videoReady() && abs(brightness.value - 0.5) > 0.01 }
        brightnessReset.visibleWhen = notErrored

        val syncReset = addUi(IconButton("refresh") {
            if (ds.canSetModeHere) ds.requestMode(PlaybackMode.LOCAL)
        })
        syncReset.enabledWhen = { videoReady() && ds.canSetModeHere && ds.effectiveMode != PlaybackMode.LOCAL }
        syncReset.visibleWhen = notErrored

        val canSeekNow = { ds.canSeekHere && ds.canSeek() }
        val backButton = addUi(IconButton("left") { ds.seekBackward() })
        backButton.enabledWhen = canSeekNow
        backButton.visibleWhen = notErrored
        val forwardButton = addUi(IconButton("right") { ds.seekForward() })
        forwardButton.enabledWhen = canSeekNow
        forwardButton.visibleWhen = notErrored

        val muteButton = addUi(IconButton(
            icon = { IconButton.modIcon(if (ds.muted) "mute" else "sound") },
        ) { ds.mute(!ds.muted) })
        muteButton.enabledWhen = videoReady
        muteButton.visibleWhen = notErrored

        val popoutButton = addUi(IconButton("popout") {
            if (ds.isPopoutActive) {
                ds.deactivatePopout()
                dropdown.hide()
            } else {
                dropdown.toggle()
            }
        })
        popoutButton.enabledWhen = { videoReady() && (ds.canPopoutHere || ds.isPopoutActive) }
        popoutButton.visibleWhen = notErrored

        val pauseButton = addUi(IconButton(
            icon = { IconButton.modIcon(if (ds.isPaused) "play" else "pause") },
        ) { ds.setPaused(!ds.isPaused) })
        pauseButton.enabledWhen = { ds.canControlPlayback }
        pauseButton.visibleWhen = notErrored

        progress = addUi(SeekBar(
            current = { ds.currentTimeNanos },
            duration = { ds.mediaPlayerDurationNanos },
        ) { nanos ->
            if (ds.canSeek() && !ds.isLive && ds.canSeekHere) {
                ds.seekToMillis(nanos / 1_000_000L)
            }
        })
        progress.enabledWhen = { videoReady() && ds.canSeek() && !ds.isLive && ds.canSeekHere }
        progress.visibleWhen = notErrored

        val lockButton = addUi(IconButton(
            icon = { IconButton.modIcon(if (ds.isLocked == true) "lock" else "unlock") },
        ) {
            val locked = ds.isLocked ?: return@IconButton
            val newLocked = !locked
            ds.isLocked = newLocked
            Initializer.sendPacket(SetLocked(ds.uuid, newLocked))
        })
        lockButton.enabledWhen = { ds.canToggleLockHere }
        lockButton.visibleWhen = { ds.isLocked != null && !ds.errored }

        val deleteButton = addUi(IconButton(
            icon = { IconButton.modIcon("delete") },
            sprites = IconButton.RED_SPRITES,
        ) {
            DisplayStorage.removeDisplay(ds.uuid)
            DisplayRegistry.unregisterScreen(ds)
            Initializer.sendPacket(DisplayDelete(ds.uuid))
            onClose()
        })
        deleteButton.enabledWhen = { ds.owner || ds.isAdmin }

        val reportButton = if (ClientStateManager.isReportingEnabled) {
            addUi(IconButton(
                icon = { IconButton.modIcon("report") },
                sprites = IconButton.RED_SPRITES,
            ) {
                Initializer.sendPacket(ReportDisplay(ds.uuid))
                onClose()
            })
        } else null

        suggestions = addUi(SuggestionsPanel(::onPickSuggested))
        suggestions.visibleWhen = { !ds.errored && suggestionsRect != null }
        // Locked / Broadcast / Watch party displays only let the owner / admin change the video, so
        // the panel shows an "unavailable" notice to everyone else instead of pickable suggestions.
        suggestions.available = { ds.canSetVideoHere }

        preview = PreviewSection(ds, backButton, forwardButton, muteButton, popoutButton, pauseButton, progress, dropdown)
        settings = SettingsSection(
            rows = settingsRows(volumeReset, renderDReset, qualityReset, brightnessReset, syncReset),
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
        errorPanel = ErrorPanel(deleteButton, reportButton)
    }

    /** Builds the five settings rows with their tooltip content. */
    private fun settingsRows(
        volumeReset: IconButton, renderDReset: IconButton, qualityReset: IconButton,
        brightnessReset: IconButton, syncReset: IconButton,
    ): List<SettingsSection.Row> {
        val ds = displayScreen
        return listOf(
            SettingsSection.Row("dreamdisplays.button.volume", volume, volumeReset) {
                listOf(
                    tooltipTitle("dreamdisplays.button.volume.tooltip.1"),
                    tooltipBody("dreamdisplays.button.volume.tooltip.2"),
                    tooltipBody("dreamdisplays.button.volume.tooltip.3"),
                    tooltipValue("dreamdisplays.button.volume.tooltip.4", (volume.value * 200).toInt()),
                )
            },
            SettingsSection.Row("dreamdisplays.button.render-distance", renderD, renderDReset) {
                listOf(
                    tooltipTitle("dreamdisplays.button.render-distance.tooltip.1"),
                    tooltipBody("dreamdisplays.button.render-distance.tooltip.2"),
                    tooltipBody("dreamdisplays.button.render-distance.tooltip.3"),
                    Component.literal(""),
                    tooltipValue("dreamdisplays.button.render-distance.tooltip.8", fractionToChunks(renderD.value)),
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
                    tooltipValue("dreamdisplays.button.brightness.tooltip.3", floor(brightness.value * 200).toInt()),
                )
            },
            SettingsSection.Row("dreamdisplays.button.synchronization", sync, syncReset, extraGapBefore = 6) {
                listOf(
                    tooltipTitle("dreamdisplays.button.synchronization.tooltip.1"),
                    tooltipBody("dreamdisplays.button.synchronization.tooltip.2"),
                    tooltipBody("dreamdisplays.button.synchronization.tooltip.3"),
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

    /** Two-line white/gray tooltip used by the delete and report buttons. */
    private fun buttonTooltip(prefix: String): List<Component> = listOf(
        tooltipTitle("$prefix.tooltip.1"),
        tooltipBody("$prefix.tooltip.2"),
    )

    /** Requests [info] as the display video and reloads the related list once the intent is sent. */
    private fun onPickSuggested(info: MediaSearchResult) {
        val ds = displayScreen
        if (!ds.canSetVideoHere) return
        if (!ds.playSuggestedVideo(info.getWatchUrl(), ds.lang ?: "")) return
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
        val currentId = DreamServices.registry.getOrNull<MediaSearchService>()?.extractVideoId(ds.videoUrl ?: "")
        if (currentId != null && currentId != lastSuggestedVideoId) {
            lastSuggestedVideoId = currentId
            suggestions.setRelatedTo(currentId)
        }
    }

    override fun onMouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        // Coordinates are already in virtual space (UiScreenBase converted them); dropdown and mod
        // label are laid out in that same space, so hit-testing matches what's drawn at any GUI scale.
        val mx = event.x().toInt()
        val my = event.y().toInt()
        if (dropdown.visible && event.button() == 0 && dropdown.handleClick(mx, my)) return true
        return modLabel.handleClick(mx, my)
    }

    override fun onMouseReleased(event: MouseButtonEvent): Boolean = progress.commitDragIfActive()

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
        private const val MIN_CONTENT_H = 380

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

        /** Opens the menu for [displayScreen]. */
        fun open(displayScreen: DisplayScreen) {
            MinecraftScreenUtil.setScreen(Minecraft.getInstance(), DisplayMenu(displayScreen))
        }
    }
}
