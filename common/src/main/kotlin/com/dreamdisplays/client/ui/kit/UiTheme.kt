package com.dreamdisplays.client.ui.kit

/**
 * Shared visual constants for the Dream Displays UI. Colors, paddings, and control sizes.
 *
 * @since 1.6.0
 */
object UiTheme {
    // Screen-level spacing
    const val SCREEN_PADDING = 10
    const val PANEL_GAP = 8
    const val PANEL_PADDING_X = 10
    const val PANEL_PADDING_Y = 10

    // Settings rows
    const val ROW_GAP = 4
    const val CONTROL_BUTTON = 22
    const val ROW_H = CONTROL_BUTTON
    const val RESET_W = CONTROL_BUTTON
    const val CONTROL_W = 130

    // Menu panel colors
    const val PANEL_BG = 0x90101010.toInt()
    const val PANEL_BORDER = 0xFF606060.toInt()
    const val ROW_BG = 0x40000000

    // Suggestions panel colors
    const val SUGGESTIONS_BG = 0x9F0F0F0F.toInt()
    const val SUGGESTIONS_BORDER = 0xFF7A7A7A.toInt()
    const val CARD_BG = 0x602A2A2A

    // Common text colors
    const val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    const val TEXT_SECONDARY = 0xFFAAAAAA.toInt()
    const val TEXT_META = 0xFFB8B8B8.toInt()
    const val TEXT_DIM = 0xFFCCCCCC.toInt()
    const val ACCENT_NEW_TAG = 0xFFE53935.toInt()
    const val OVERLAY_SCRIM = 0xC0000000.toInt()
}
