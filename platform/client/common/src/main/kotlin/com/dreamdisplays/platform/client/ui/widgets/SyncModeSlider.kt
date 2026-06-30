package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.kit.UiWidget
import com.dreamdisplays.api.playback.PlaybackMode
import net.minecraft.client.InputType
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
//?}
import net.minecraft.network.chat.Component
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * Discrete playback-mode selector styled like the existing sliders. Unlike [ValueSlider], this
 * emits exactly one mode per click and ignores drag updates, so transient pointer movement cannot
 * start multiple watch-party requests. Clicks cycle through modes to preserve the old toggle's
 * "click anywhere" behavior.
 */
class SyncModeSlider(
    initial: PlaybackMode,
    private val current: () -> PlaybackMode,
    private val enabledFor: (PlaybackMode) -> Boolean,
    private val label: (PlaybackMode) -> Component,
    private val onApply: (PlaybackMode) -> Unit,
) : UiWidget(Component.empty()) {

    var mode: PlaybackMode = initial
        private set

    private var sliderFocused: Boolean = false
    private var pendingMode: PlaybackMode? = null
    private var pendingUntilNanos: Long = 0L

    fun syncToCurrent() {
        val actual = current()
        val pending = pendingMode
        when {
            pending == null -> mode = actual
            actual == pending -> {
                pendingMode = null
                mode = actual
            }

            System.nanoTime() < pendingUntilNanos -> mode = pending
            else -> {
                pendingMode = null
                mode = actual
            }
        }
    }

    private fun trackSprite(): Identifier =
        if (isFocused && !sliderFocused) TRACK_HIGHLIGHTED else TRACK

    private fun handleSprite(): Identifier =
        if (!isHovered && !sliderFocused) HANDLE else HANDLE_HIGHLIGHTED

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, trackSprite(), x, y, width, height)
        //?} else
        /*g.blitSprite(trackSprite(), x, y, width, height)*/
        val idx = modes.indexOf(mode).coerceAtLeast(0)
        val handleX = x + ((width - 8) * idx / (modes.size - 1).toDouble()).toInt()
        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, handleSprite(), handleX, y, 8, height)
        //?} else
        /*g.blitSprite(handleSprite(), handleX, y, 8, height)*/
        val color = if (active) 0xFFFFFF else 0xA0A0A0
        drawScrollingLabel(g, label(mode).copy().withStyle { it.withColor(color) }, 2)
    }

    // NeoForge reroutes mouseClicked to a Neo-only 3-arg onClick that Fabric lacks, so the legacy
    // (1.21.1) branch overrides mouseClicked itself so the mode cycle fires on both platforms.
    //? if >=1.21.11 {
    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        if (pendingMode != null) return
        val next = nextEnabledMode() ?: return
        if (!enabledFor(next)) return
        mode = next
        if (next != current()) {
            pendingMode = next
            pendingUntilNanos = System.nanoTime() + PENDING_TIMEOUT_NANOS
            onApply(next)
        }
        playDownSound(Minecraft.getInstance().soundManager)
    }
    //?} else
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isValidClickButton(button) || !clicked(mouseX, mouseY)) return false
        if (pendingMode != null) return true
        val next = nextEnabledMode() ?: return true
        if (!enabledFor(next)) return true
        mode = next
        if (next != current()) {
            pendingMode = next
            pendingUntilNanos = System.nanoTime() + PENDING_TIMEOUT_NANOS
            onApply(next)
        }
        playDownSound(Minecraft.getInstance().soundManager)
        return true
    }*/

    //? if >=1.21.11 {
    override fun onDrag(event: MouseButtonEvent, dragX: Double, dragY: Double) {
        // Mode changes are packet-backed actions; dragging should not spam them
    }
    //?} else
    /*override fun onDrag(mouseX: Double, mouseY: Double, dragX: Double, dragY: Double) {
        // Mode changes are packet-backed actions; dragging should not spam them
    }*/

    override fun setFocused(focused: Boolean) {
        super.setFocused(focused)
        if (!focused) {
            sliderFocused = false
        } else {
            val t = Minecraft.getInstance().lastInputType
            if (t == InputType.MOUSE || t == InputType.KEYBOARD_TAB) sliderFocused = true
        }
    }

    private fun nextEnabledMode(): PlaybackMode? {
        val start = modes.indexOf(mode).coerceAtLeast(0)
        for (offset in 1..modes.size) {
            val candidate = modes[(start + offset) % modes.size]
            if (enabledFor(candidate)) return candidate
        }
        return null
    }

    companion object {
        // TODO: Watch party mode is implemented but not shipped for 1.8.0, so the slider exposes only the three
        //  base modes. The mode and all its code paths stay intact; it's just not selectable here.
        val modes = listOf(
            PlaybackMode.LOCAL,
            PlaybackMode.SYNCED,
            PlaybackMode.BROADCAST,
        )

        private val TRACK = Identifier.withDefaultNamespace("widget/slider")
        private val TRACK_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_highlighted")
        private val HANDLE = Identifier.withDefaultNamespace("widget/slider_handle")
        private val HANDLE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_handle_highlighted")
        private const val PENDING_TIMEOUT_NANOS = 2_000_000_000L
    }
}
