package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.kit.UiWidget
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
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
 * Discrete-mode selector styled like the existing sliders, generic over any small fixed [modes] list
 * (playback mode, acoustics tier, ...). Unlike [ValueSlider], this ignores drag updates, so transient
 * pointer movement cannot repeatedly re-trigger [onApply]. A click snaps directly to whichever mode's
 * notch it lands closest to, like a real slider, instead of always advancing one step.
 */
class ModeSlider<T : Any>(
    private val modes: List<T>,
    initial: T,
    private val current: () -> T,
    private val enabledFor: (T) -> Boolean,
    private val label: (T) -> Component,
    private val onApply: (T) -> Unit,
) : UiWidget(Component.empty()) {

    var mode: T = initial
        private set

    private var sliderFocused: Boolean = false
    private var pendingMode: T? = null
    private var pendingUntilNanos: Long = 0L

    override fun handlesWholeWidgetCursor(): Boolean = false

    /**
     * Re-syncs [mode] with [current], honoring a still-pending [onApply] (e.g. one still awaiting a
     * server echo) until it either lands or [PENDING_TIMEOUT_NANOS] elapses.
     */
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
        //? if >=1.21.11 {
        if (isHovered) {
            g.requestCursor(if (active) CursorTypes.POINTING_HAND else CursorTypes.NOT_ALLOWED)
        }
        //?}
    }

    /**
     * Snaps to the mode at [mouseX]'s notch and applies it if it differs from the current one.
     * Shared by click and drag so dragging tracks the cursor across notches exactly like a click
     * would on each one, instead of being ignored mid-drag.
     */
    private fun trySelect(mouseX: Double) {
        if (pendingMode != null) return
        val next = modeFromMouse(mouseX)
        if (next == mode || !enabledFor(next)) return
        mode = next
        if (next != current()) {
            pendingMode = next
            pendingUntilNanos = System.nanoTime() + PENDING_TIMEOUT_NANOS
            onApply(next)
        }
        playDownSound(Minecraft.getInstance().soundManager)
    }

    // NeoForge deprecates the 2-arg onClick and reroutes mouseClicked to a Neo-only 3-arg overload
    // that Fabric lacks, so the legacy (1.21.1) branch overrides mouseClicked itself so the mode
    // cycle fires on both platforms.
    //? if >=1.21.11 {
    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        trySelect(event.x())
    }
    //?} else
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isValidClickButton(button) || !clicked(mouseX, mouseY)) return false
        trySelect(mouseX)
        return true
    }*/

    //? if >=1.21.11 {
    override fun onDrag(event: MouseButtonEvent, dragX: Double, dragY: Double) {
        trySelect(event.x())
    }
    //?} else
    /*override fun onDrag(mouseX: Double, mouseY: Double, dragX: Double, dragY: Double) {
        trySelect(mouseX)
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

    /** Maps a click's x position to the mode whose notch it's closest to (mirrors the [draw] layout). */
    private fun modeFromMouse(mouseX: Double): T {
        val fraction = (mouseX - x) / (width - 8).toDouble()
        val idx = Math.round(fraction * (modes.size - 1)).toInt().coerceIn(0, modes.size - 1)
        return modes[idx]
    }

    companion object {
        private val TRACK = Identifier.withDefaultNamespace("widget/slider")
        private val TRACK_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_highlighted")
        private val HANDLE = Identifier.withDefaultNamespace("widget/slider_handle")
        private val HANDLE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_handle_highlighted")
        private const val PENDING_TIMEOUT_NANOS = 2_000_000_000L
    }
}
