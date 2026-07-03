package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import kotlin.math.max
import kotlin.math.min

/**
 * Responsive panel layout for the display menu.
 *
 * Three modes (carried over from the original implementation):
 * - Wide (>=900x480): preview + settings stacked left, suggestions as a right sidebar (vertical cards).
 * - Normal: preview and settings side by side on top, suggestions strip below.
 * - Compact (<600 wide): preview above settings, suggestions strip below.
 */
class MenuLayout private constructor(
    val preview: UiRect,
    val settings: UiRect,
    val suggestions: UiRect?,
    val suggestionsVertical: Boolean,
) {
    companion object {
        /** Computes the panel layout for a [screenW] x [screenH] screen with the given font [lineHeight]. */
        fun compute(screenW: Int, screenH: Int, lineHeight: Int): MenuLayout {
            val pad = UiTheme.SCREEN_PADDING
            val gap = UiTheme.PANEL_GAP
            val titleY = 6
            val contentTop = titleY + lineHeight + 8
            val contentBottom = screenH - pad
            val totalW = screenW - pad * 2
            val totalH = contentBottom - contentTop
            val leftX = pad

            val wide = totalW >= 900 && totalH >= 480
            val compact = !wide && totalW < 600

            if (wide) {
                val rightColW = max(200, min(280, totalW * 3 / 10))
                val leftColW = totalW - rightColW - gap
                // The video is letterboxed to the screen's own aspect ratio, so height (not width)
                // is almost always its limiting dimension here; give it as much as the settings
                // panel below can spare instead of a flat 60/40 split, so it isn't left tiny inside
                // a much wider column.
                val settingsMinH = 190
                var previewSlice = (totalH * 8) / 10
                if (totalH - previewSlice - gap < settingsMinH) {
                    previewSlice = totalH - settingsMinH - gap
                }
                return MenuLayout(
                    preview = UiRect(leftX, contentTop, leftColW, previewSlice),
                    settings = UiRect(leftX, contentTop + previewSlice + gap, leftColW, totalH - previewSlice - gap),
                    suggestions = UiRect(leftX + leftColW + gap, contentTop, rightColW, totalH),
                    suggestionsVertical = true,
                )
            }

            val minSuggestionsH = 120
            // Same reasoning as the wide layout's previewSlice: favor the video's height budget over
            // a flat 60/40 split, falling back to it only when there isn't enough slack.
            var topRowH = max(220, (totalH * 8) / 10)
            var suggestionsH = totalH - topRowH - gap
            if (suggestionsH < minSuggestionsH) {
                suggestionsH = minSuggestionsH
                topRowH = totalH - suggestionsH - gap
            }
            val showSuggestions = topRowH >= 160

            val preview: UiRect
            val settings: UiRect
            if (compact) {
                val previewH = min(220, topRowH * 3 / 5)
                preview = UiRect(leftX, contentTop, totalW, previewH)
                settings = UiRect(leftX, contentTop + previewH + gap, totalW, topRowH - previewH - gap)
            } else {
                val previewW = (totalW * 6) / 10 - gap / 2
                preview = UiRect(leftX, contentTop, previewW, topRowH)
                settings = UiRect(leftX + previewW + gap, contentTop, totalW - previewW - gap, topRowH)
            }
            return MenuLayout(
                preview = preview,
                settings = settings,
                suggestions = if (showSuggestions)
                    UiRect(leftX, contentTop + topRowH + gap, totalW, suggestionsH) else null,
                suggestionsVertical = false,
            )
        }
    }
}
