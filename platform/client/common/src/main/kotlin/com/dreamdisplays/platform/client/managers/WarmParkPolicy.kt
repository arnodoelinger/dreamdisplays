package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.platform.client.capabilities.ClientMemoryProbe
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.managers.WarmParkPolicy.MAX_AUTO_WARM_LIMIT
import com.dreamdisplays.platform.client.managers.WarmParkPolicy.replayOnly
import kotlin.math.max

/** Adaptive budget for displays kept fully warm versus compressed into replay snapshots. */
object WarmParkPolicy {
    /** Bytes in one mebibyte. */
    private const val MIB = 1024L * 1024L

    /** Fallback warm-display limit when system RAM can't be probed. */
    private const val DEFAULT_WARM_LIMIT = 8

    /** Hard ceiling on the auto-computed warm-display limit. */
    private const val MAX_AUTO_WARM_LIMIT = 12

    /** Default delay before a parked display is demoted to a replay snapshot, in milliseconds. */
    private const val DEFAULT_DEMOTE_MS = 15_000L

    /** Default time-to-live for a dormant display before teardown, in milliseconds. */
    private const val DEFAULT_TTL_MS = 60_000L

    /** Fallback replay-cache budget when system RAM can't be probed. */
    private const val DEFAULT_REPLAY_CACHE_BYTES = 512L * MIB

    /** Whether to skip full warm-park and always use replay snapshots by default. */
    private const val DEFAULT_REPLAY_ONLY = false

    /** Conservative full-warm RAM estimate: decoder state, packet ring, audio process, sockets, threads. */
    private const val ESTIMATED_FULL_WARM_RAM_BYTES = 96L * MIB

    /** Typical current texture budget for one display; actual display textures are counted separately. */
    private const val ESTIMATED_FULL_WARM_VRAM_BYTES = 8L * MIB

    /** Operator override for the warm limit via `dreamdisplays.warmPark.max`, or `null` to auto-compute. */
    private val configuredMax: Int? =
        System.getProperty("dreamdisplays.warmPark.max")?.toIntOrNull()?.coerceAtLeast(0)

    /** Whether full warm-park is disabled in favor of replay snapshots (`dreamdisplays.warmPark.replayOnly`). */
    private val replayOnly: Boolean =
        System.getProperty("dreamdisplays.warmPark.replayOnly")?.toBooleanStrictOrNull() ?: DEFAULT_REPLAY_ONLY

    /** Maximum number of displays kept fully warm at once (0 when [replayOnly]). */
    val maxFullWarmDisplays: Int by lazy {
        if (replayOnly) 0 else configuredMax ?: autoWarmLimit()
    }

    /** Delay before a parked display is demoted to a replay snapshot, in nanoseconds. */
    val demoteAfterNanos: Long =
        (System.getProperty("dreamdisplays.warmPark.demoteMs")?.toLongOrNull()?.coerceAtLeast(0L)
            ?: DEFAULT_DEMOTE_MS) * 1_000_000L

    /** Time a dormant display may sit parked before teardown, in nanoseconds. */
    val ttlNanos: Long =
        (System.getProperty("dreamdisplays.warmPark.ttlMs")?.toLongOrNull()?.coerceAtLeast(0L)
            ?: DEFAULT_TTL_MS) * 1_000_000L

    /** Total RAM budget for warm displays, scaled from system RAM (~5%). */
    val ramBudgetBytes: Long by lazy {
        val ram = ClientMemoryProbe.detected.systemRamBytes
        if (ram > 0L) max(ESTIMATED_FULL_WARM_RAM_BYTES, ram / 20L)
        else ESTIMATED_FULL_WARM_RAM_BYTES * DEFAULT_WARM_LIMIT
    }

    /** Total VRAM budget for warm displays, scaled from dedicated VRAM (~10%); unbounded when unknown. */
    val vramBudgetBytes: Long by lazy {
        val vram = ClientMemoryProbe.detected.dedicatedVramBytes
        if (vram > 0L) max(ESTIMATED_FULL_WARM_VRAM_BYTES, vram / 10L)
        else Long.MAX_VALUE
    }

    /** Memory budget for the replay-snapshot cache, tiered by system RAM. */
    val replayCacheBudgetBytes: Long by lazy {
        val ram = ClientMemoryProbe.detected.systemRamBytes
        when {
            ram >= 32L * 1024L * MIB -> 2L * 1024L * MIB
            ram >= 24L * 1024L * MIB -> 1024L * MIB
            ram >= 16L * 1024L * MIB -> 768L * MIB
            ram >= 8L * 1024L * MIB -> 512L * MIB
            ram >= 4L * 1024L * MIB -> 256L * MIB
            ram > 0L -> 128L * MIB
            else -> DEFAULT_REPLAY_CACHE_BYTES
        }
    }

    /** Estimated RAM held by [display] when parked fully warm (decoder state plus half its texture bytes). */
    fun estimatedFullWarmRamBytes(display: DisplayScreen): Long {
        val textureBytes = display.estimatedTextureBytes()
        return ESTIMATED_FULL_WARM_RAM_BYTES + (textureBytes / 2L)
    }

    /** Estimated VRAM held by [display] when parked fully warm. */
    fun estimatedFullWarmVramBytes(display: DisplayScreen): Long =
        max(display.estimatedTextureBytes(), ESTIMATED_FULL_WARM_VRAM_BYTES)

    /** True if [candidate] can join [currentDormant] without exceeding the count, RAM, or VRAM budgets. */
    fun fits(currentDormant: Collection<DisplayScreen>, candidate: DisplayScreen): Boolean {
        if (maxFullWarmDisplays <= 0) return false
        if (currentDormant.size >= maxFullWarmDisplays) return false
        val ram = currentDormant.sumOf(::estimatedFullWarmRamBytes) + estimatedFullWarmRamBytes(candidate)
        val vram = currentDormant.sumOf(::estimatedFullWarmVramBytes) + estimatedFullWarmVramBytes(candidate)
        return ram <= ramBudgetBytes && vram <= vramBudgetBytes
    }

    /** Derives the warm-display limit from probed RAM / VRAM, clamped to [MAX_AUTO_WARM_LIMIT]. */
    private fun autoWarmLimit(): Int {
        val memory = ClientMemoryProbe.detected
        val ramLimit = if (memory.systemRamBytes > 0L) {
            ((memory.systemRamBytes / 20L) / ESTIMATED_FULL_WARM_RAM_BYTES).toInt()
        } else {
            DEFAULT_WARM_LIMIT
        }
        val vramLimit = if (memory.dedicatedVramBytes > 0L) {
            ((memory.dedicatedVramBytes / 10L) / ESTIMATED_FULL_WARM_VRAM_BYTES).toInt()
        } else {
            Int.MAX_VALUE
        }
        return minOf(ramLimit.coerceAtLeast(1), vramLimit.coerceAtLeast(1), MAX_AUTO_WARM_LIMIT)
    }
}
