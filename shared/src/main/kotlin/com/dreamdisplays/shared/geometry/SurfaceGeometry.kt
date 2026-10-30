package com.dreamdisplays.shared.geometry

/**
 * How a block relates to the configured display base (`black_concrete`, its slab, its stairs, etc.).
 *
 * Air is allowed inside a conforming selection so a staircase can include the empty space beside
 * the steps. Anything else that is not the base block or its slab / stair variant rejects the selection.
 */
enum class SurfaceRole {
    /** The base block itself, a full cube. */
    FULL,

    /** A slab or stair of the base block. The screen wraps this shape. */
    SHAPED,

    /** Air, cave air, or void air. */
    AIR,

    /** A block that cannot be part of this display. */
    FOREIGN,
}

/**
 * The configured display base and the slabs and stairs made of it.
 *
 * The platform supplies [shapedIds] from the game's own data, so any base with vanilla variants works,
 * whatever they happen to be called.
 *
 * IDs may be registry names (`minecraft:black_concrete_stairs`) or enum names (`BLACK_CONCRETE_STAIRS`).
 */
class DisplaySurface(baseId: String, shapedIds: Collection<String>) {
    private val base = normalizeBlockId(baseId)
    private val shaped = shapedIds.mapTo(HashSet(), ::normalizeBlockId)

    /** Classifies [blockId] against the base. */
    fun role(blockId: String): SurfaceRole {
        val block = normalizeBlockId(blockId)
        return when (block) {
            "air", "cave_air", "void_air" -> SurfaceRole.AIR
            base -> SurfaceRole.FULL
            in shaped -> SurfaceRole.SHAPED
            else -> SurfaceRole.FOREIGN
        }
    }

    /** True when the wand may select this block and a display may be built on it. */
    fun accepts(blockId: String): Boolean {
        val role = role(blockId)
        return role == SurfaceRole.FULL || role == SurfaceRole.SHAPED
    }
}

/** Lower-cases a registry or enum id and strips the default `minecraft:` namespace. */
fun normalizeBlockId(raw: String): String =
    raw.trim().lowercase().removePrefix("minecraft:")

/**
 * Counts of each [SurfaceRole] inside a selection.
 *
 * [conforming] selections contain at least one slab or stair and no foreign blocks, so
 * the screen is allowed to be deeper than one block.
 *
 * [flat] selections are a solid rectangle of the base block, the original screen shape.
 */
data class SurfaceCensus(
    val full: Int = 0,
    val shaped: Int = 0,
    val air: Int = 0,
    val foreign: Int = 0,
) {
    /** Returns a census with one more block of [role]. */
    fun add(role: SurfaceRole): SurfaceCensus = when (role) {
        SurfaceRole.FULL -> copy(full = full + 1)
        SurfaceRole.SHAPED -> copy(shaped = shaped + 1)
        SurfaceRole.AIR -> copy(air = air + 1)
        SurfaceRole.FOREIGN -> copy(foreign = foreign + 1)
    }

    /** Slabs or stairs are present, and every solid block belongs to the base material. */
    val conforming: Boolean get() = foreign == 0 && shaped > 0 && full + shaped > 0

    /** A solid rectangle of the base block with no gaps and no partial blocks. */
    val flat: Boolean get() = foreign == 0 && air == 0 && shaped == 0 && full > 0
}
