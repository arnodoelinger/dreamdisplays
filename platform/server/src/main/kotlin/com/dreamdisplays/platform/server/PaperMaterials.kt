package com.dreamdisplays.platform.server

import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Material

/** Resolved [Material] for the selection tool; falls back to a diamond axe if the configured id doesn't resolve. */
@PaperOnly
val SettingsSection.selectionMaterial: Material
    get() = Material.matchMaterial(display.selection_material) ?: Material.DIAMOND_AXE

/** Resolved [Material] for the display base; falls back to black concrete if the configured id doesn't resolve. */
@PaperOnly
val SettingsSection.baseMaterial: Material
    get() = Material.matchMaterial(display.base_material) ?: Material.BLACK_CONCRETE
