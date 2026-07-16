package com.dreamdisplays.platform.client.audio

import com.dreamdisplays.api.media.audio.AcousticEnvironment
import com.dreamdisplays.api.media.audio.AcousticMaterial
import com.dreamdisplays.api.media.audio.ListenerPose
import com.dreamdisplays.api.media.audio.SourcePlane
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Platform-side voxel raytracer that turns the blocks around a display into an [AcousticEnvironment]
 * (occlusion + reverb) for the DSP chain. It only ever reads the world through the same
 * [Level.clip] primitive the block-interaction code already uses, plus a per-hit sound-type lookup for
 * material coloring, so it stays robust across the versions the mod targets and degrades to
 * [AcousticEnvironment.OPEN_AIR] on any failure.
 */
object VoxelAcousticsProbe {
    /** Number of Fibonacci-sphere rays used to sample the enclosing space for reverb. */
    private const val REVERB_RAYS = 24

    /** How far (blocks) a reverb ray travels before it counts as "escaped to open air". */
    private const val REVERB_MAX_DISTANCE = 24.0

    /** How far past a wall the occlusion walk steps before re-casting, to clear the block it just hit. */
    private const val OCCLUSION_STEP_EPSILON = 0.15

    /** Cap on how many walls the occlusion walk pierces before giving up. */
    private const val MAX_OCCLUSION_STEPS = 5

    /** Fraction of the screen's half-size each fanned occlusion sample point is inset from the centre. */
    private const val OCCLUSION_SAMPLE_INSET = 0.35

    /** Accumulated material occlusion that maps to a fully muffled path (≈ three solid stone walls). */
    private const val MAX_OCCLUSION = 3.0f

    /** Reflectivity that counts as "fully reflective" when normalizing (hard stone / metal). */
    private const val MAX_REFLECTIVITY = 1.5f

    /** Cap on how many transparent blocks a single reverb ray pierces before giving up on that ray. */
    private const val MAX_REVERB_PIERCE_STEPS = 6

    /**
     * Blocks tagged under any of these are ignored entirely by the raytracer — treated as open air for
     * both occlusion and reverb, as if they weren't there. This is the "excluder": add a tag here to make
     * a whole block family (foliage, thin decorations, ...) acoustically invisible without touching the
     * material table above.
     */
    private val TRANSPARENT_TAGS: Set<TagKey<Block>> = setOf(BlockTags.LEAVES)

    /** True if [state] is tagged as acoustically transparent (see [TRANSPARENT_TAGS]). */
    private fun isTransparent(state: BlockState): Boolean = TRANSPARENT_TAGS.any { state.`is`(it) }

    /** Unit ray directions, evenly spread over the sphere via the golden-angle spiral. */
    private val RAY_DIRECTIONS: Array<Vec3> = run {
        val golden = PI * (3.0 - sqrt(5.0)) // = 2.399963... rad
        Array(REVERB_RAYS) { i ->
            val y = 1.0 - (i + 0.5) / REVERB_RAYS * 2.0
            val r = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val theta = golden * i
            Vec3(cos(theta) * r, y, sin(theta) * r)
        }
    }

    /** SoundType → material coefficients, seeded from Sound Physics Remastered's default tables. */
    private val MATERIALS: Map<SoundType, AcousticMaterial> = buildMaterialTable()

    /**
     * Raytraces the space between [plane] and [listener] into an [AcousticEnvironment]. Returns
     * [AcousticEnvironment.OPEN_AIR] if no world / player is available or anything throws.
     */
    fun probe(plane: SourcePlane, listener: ListenerPose): AcousticEnvironment = runCatching {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return AcousticEnvironment.OPEN_AIR
        val entity = mc.player ?: return AcousticEnvironment.OPEN_AIR

        val center = Vec3(plane.centerX, plane.centerY, plane.centerZ)
        val ear = Vec3(listener.x, listener.y, listener.z)

        val occlusion = traceOcclusion(level, entity, ear, plane, center)
        val (decay, wet, damping) = traceReverb(level, entity, center)
        AcousticEnvironment(occlusion, decay, wet, damping)
    }.getOrDefault(AcousticEnvironment.OPEN_AIR)

    /**
     * Averages the occlusion of several rays fanned from [ear] to a spread of points across the screen
     * ([center] plus four points inset toward the edges along the plane axes), and normalizes to `0..1`.
     * Fanning is what keeps the result smooth: a single centre ray flips hard between clear and blocked
     * as the listener steps one block, whereas averaged partial coverage ramps gradually instead.
     */
    private fun traceOcclusion(level: Level, entity: Entity, ear: Vec3, plane: SourcePlane, center: Vec3): Float {
        val u = Vec3(plane.uAxisX, plane.uAxisY, plane.uAxisZ).scale(plane.width * OCCLUSION_SAMPLE_INSET)
        val v = Vec3(plane.vAxisX, plane.vAxisY, plane.vAxisZ).scale(plane.height * OCCLUSION_SAMPLE_INSET)
        val targets = arrayOf(center, center.add(u), center.subtract(u), center.add(v), center.subtract(v))
        var sum = 0f
        for (target in targets) sum += occlusionAlong(level, entity, ear, target)
        return (sum / targets.size / MAX_OCCLUSION).coerceIn(0f, 1f)
    }

    /**
     * Walks the direct segment from [ear] to [target], summing the (material-weighted) occlusion of every
     * wall it pierces, capped at [MAX_OCCLUSION]. Returns the raw accumulated weight (not normalized).
     */
    private fun occlusionAlong(level: Level, entity: Entity, ear: Vec3, target: Vec3): Float {
        var start = ear
        var accum = 0f
        for (step in 0 until MAX_OCCLUSION_STEPS) {
            val hit = level.clip(ClipContext(start, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity))
            if (hit.type != HitResult.Type.BLOCK) break // Reached the screen unobstructed from here
            val at = hit.location
            if (at.distanceTo(target) < 0.6) break // The hit is the screen's own block; nothing between us
            accum += materialAt(level, hit.blockPos).occlusion
            if (accum >= MAX_OCCLUSION) return MAX_OCCLUSION
            val dir = target.subtract(start).normalize()
            start = at.add(dir.scale(OCCLUSION_STEP_EPSILON)) // Step just past this wall and look for the next
        }
        return accum
    }

    /**
     * Sprays [REVERB_RAYS] rays off [center], measures how enclosed and reflective the surroundings are,
     * and collapses that into `(reverbDecaySeconds, reverbWetGain, reverbDamping)`.
     */
    private fun traceReverb(level: Level, entity: Entity, center: Vec3): Triple<Float, Float, Float> {
        var hits = 0
        var pathSum = 0.0
        var reflSum = 0.0
        for (dir in RAY_DIRECTIONS) {
            val end = center.add(dir.scale(REVERB_MAX_DISTANCE))
            val hit = level.clip(ClipContext(center, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity))
            if (hit.type == HitResult.Type.BLOCK) {
                hits++
                pathSum += hit.location.distanceTo(center)
                reflSum += materialAt(level, hit.blockPos).reflectivity
            } else {
                pathSum += REVERB_MAX_DISTANCE // energy escaped to open air along this direction
            }
        }
        if (hits == 0) return Triple(0f, 0f, 0f)

        val enclosure = (hits.toFloat() / REVERB_RAYS)
        val meanFreePath = (pathSum / REVERB_RAYS).toFloat()
        val reflNorm = (reflSum / hits / MAX_REFLECTIVITY).toFloat().coerceIn(0f, 1f)
        val size = (meanFreePath / REVERB_MAX_DISTANCE.toFloat()).coerceIn(0f, 1f)

        val decay = ((0.25f + 2.5f * size) * (0.35f + 0.65f * reflNorm))
        val wet = (enclosure * (0.3f + 0.7f * reflNorm) * 0.6f).coerceIn(0f, 1f)
        val damping = 1f - reflNorm
        return Triple(decay, wet, damping)
    }

    /** Looks up the [AcousticMaterial] of the block at [pos], defaulting when the sound type is unknown. */
    @Suppress("DEPRECATION")
    private fun materialAt(level: Level, pos: BlockPos): AcousticMaterial {
        val soundType = runCatching { level.getBlockState(pos).soundType }.getOrNull() ?: return AcousticMaterial.DEFAULT
        return MATERIALS[soundType] ?: AcousticMaterial.DEFAULT
    }

    /** Builds the SoundType → material map from Sound Physics Remastered's reflectivity / occlusion values. */
    private fun buildMaterialTable(): Map<SoundType, AcousticMaterial> {
        fun m(reflectivity: Float, occlusion: Float = 1.0f) = AcousticMaterial(reflectivity, occlusion)
        val map = HashMap<SoundType, AcousticMaterial>()
        fun put(type: SoundType?, material: AcousticMaterial) { if (type != null) map[type] = material }
        val stone = m(1.5f)

        put(SoundType.STONE, stone); put(SoundType.DEEPSLATE, stone); put(SoundType.TUFF, stone)
        put(SoundType.CALCITE, stone); put(SoundType.BASALT, stone); put(SoundType.AMETHYST, stone)
        put(SoundType.BONE_BLOCK, stone); put(SoundType.NETHER_BRICKS, stone); put(SoundType.NETHERITE_BLOCK, stone)
        put(SoundType.METAL, m(1.25f)); put(SoundType.COPPER, m(1.25f))
        put(SoundType.NETHERRACK, m(1.1f))
        put(SoundType.GLASS, m(0.75f, 0.1f))
        put(SoundType.WOOD, m(0.4f)); put(SoundType.STEM, m(0.4f))
        put(SoundType.GRAVEL, m(0.3f)); put(SoundType.GRASS, m(0.3f))
        put(SoundType.SAND, m(0.2f)); put(SoundType.SOUL_SAND, m(0.2f))
        put(SoundType.SOUL_SOIL, m(0.2f)); put(SoundType.CORAL_BLOCK, m(0.2f))
        put(SoundType.SNOW, m(0.15f, 0.1f)); put(SoundType.POWDER_SNOW, m(0.15f, 0.1f))
        put(SoundType.WOOL, m(0.1f, 1.5f)); put(SoundType.HONEY_BLOCK, m(0.1f, 0.5f))
        put(SoundType.MOSS, m(0.1f, 0.75f)); put(SoundType.WET_GRASS, m(0.2f, 0.1f))

        return map
    }
}
