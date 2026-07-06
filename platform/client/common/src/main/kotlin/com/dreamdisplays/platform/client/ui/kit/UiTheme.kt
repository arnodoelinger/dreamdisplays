package com.dreamdisplays.platform.client.ui.kit

/**
 * Shared visual constants for the Dream Displays UI. Colors, paddings, and control sizes.
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

    /** Card background when hovered: a static, slightly lifted grey (no animation). */
    const val CARD_BG_HOVER = 0xC0484848.toInt()

    /** 1px accent outline drawn around a hovered card; neutral light to match the panel borders. */
    const val CARD_BORDER_HOVER = 0xFFE8E8E8.toInt()

    // Loading placeholder / ambient
    /** Base fill of a still-loading thumbnail (a neutral dark grey, not a black hole). */
    const val PLACEHOLDER_BG = 0xFF1C1C1C.toInt()

    /** Soft white shimmer band swept across a loading placeholder. */
    const val PLACEHOLDER_SHIMMER = 0x24FFFFFF

    /** Fallback ambient letterbox tint for the preview before a thumbnail color is known. */
    const val AMBIENT_DEFAULT = 0xFF121212.toInt()

    // Common text colors
    const val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    const val TEXT_SECONDARY = 0xFFAAAAAA.toInt()
    const val TEXT_META = 0xFFB8B8B8.toInt()
    const val TEXT_DIM = 0xFFCCCCCC.toInt()
    const val ACCENT_NEW_TAG = 0xFFE53935.toInt()
    const val ACCENT_TWITCH_TAG = 0xFF9146FF.toInt()
    const val OVERLAY_SCRIM = 0xC0000000.toInt()
}
