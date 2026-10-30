package com.dreamdisplays.platform.server.api

import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.display.model.property.DisplayRotation
import com.dreamdisplays.api.server.ServerDisplay
import com.dreamdisplays.api.server.ServerDisplays
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.shared.policy.CustomMediaPolicy
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import java.util.UUID
import kotlin.math.abs

/** `Fabric` / `NeoForge` implementation of the public server API. */
object VanillaServerApi : BaseServerBackend() {
    override val displays: ServerDisplays get() = VanillaServerDisplays()

    override fun wrap(data: DisplayData): ServerDisplay = VanillaServerDisplay(data as VanillaDisplayData, this)
}

/** [ServerDisplay] over vanilla display data. Resolves the display on every call. */
internal class VanillaServerDisplay(
    private val vanilla: VanillaDisplayData,
    backend: BaseServerBackend,
) : ServerDisplayBase(vanilla, backend) {
    override val isAlive: Boolean get() = DisplayManager.getDisplayData(vanilla.id) != null
    override val world: String get() = vanilla.worldKey
    override val x: Double get() = (vanilla.box.minX + vanilla.box.maxX) / 2
    override val y: Double get() = (vanilla.box.minY + vanilla.box.maxY) / 2
    override val z: Double get() = (vanilla.box.minZ + vanilla.box.maxZ) / 2

    override fun customMediaSettings(): CustomMediaPolicy.Settings = VanillaServerState.config.settings.customMediaPolicy

    override fun save() {
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(vanilla) }
    }

    override fun broadcast() {
        val server = VanillaServerState.server ?: return
        DisplayManager.sendUpdate(vanilla, DisplayManager.getReceivers(vanilla, server))
    }

    override fun doDelete() = DisplayManager.delete(vanilla)

    override fun doMove(
        world: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, facing: DisplayFacing,
    ): CommandResult {
        val server = VanillaServerState.server ?: return CommandResult.REFUSED
        if (RegionUtil.getLevelByKey(server, world) == null) return CommandResult.REFUSED
        val direction = facing.toDirection()
        val region = RegionUtil.calculateRegion(BlockPos(x1, y1, z1), BlockPos(x2, y2, z2))
        val box = AABB(
            region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
            (region.maxX + 1).toDouble(), (region.maxY + 1).toDouble(), (region.maxZ + 1).toDouble(),
        )
        val overlaps = DisplayManager.getDisplays().filterIsInstance<VanillaDisplayData>()
            .any { it.id != vanilla.id && it.worldKey == world && it.box.intersects(box) }
        if (overlaps) return CommandResult.REFUSED

        val before = DisplayManager.getReceivers(vanilla, server)
        val (screenWidth, screenHeight, _) = region.screenExtents(
            abs(direction.stepX), abs(direction.stepY), abs(direction.stepZ),
        )
        vanilla.relocate(world, region.getMinBlockPos(), region.getMaxBlockPos(), direction, screenWidth, screenHeight)
        save()
        DisplayManager.sendUpdate(vanilla, before)
        broadcast()
        return CommandResult.OK
    }
}

/** Lazy view over `Fabric` / `NeoForge` displays. */
internal class VanillaServerDisplays(
    filters: List<(VanillaDisplayData) -> Boolean> = [],
) : ServerDisplaysBase<VanillaDisplayData>(filters) {
    override fun all(): List<VanillaDisplayData> = DisplayManager.getDisplays().filterIsInstance<VanillaDisplayData>()
    override fun wrap(data: VanillaDisplayData): ServerDisplay = VanillaServerApi.wrap(data)
    override fun worldOf(data: VanillaDisplayData): String = data.worldKey
    override fun centerOf(data: VanillaDisplayData) = Triple(
        (data.box.minX + data.box.maxX) / 2, (data.box.minY + data.box.maxY) / 2, (data.box.minZ + data.box.maxZ) / 2,
    )
    override fun withFilters(filters: List<(VanillaDisplayData) -> Boolean>): ServerDisplays = VanillaServerDisplays(filters)

    override fun create(
        owner: UUID,
        world: String,
        x1: Int, y1: Int, z1: Int,
        x2: Int, y2: Int, z2: Int,
        facing: DisplayFacing,
    ): ServerDisplay? {
        val server = VanillaServerState.server ?: return null
        if (RegionUtil.getLevelByKey(server, world) == null) return null
        val direction = facing.toDirection()
        val region = RegionUtil.calculateRegion(BlockPos(x1, y1, z1), BlockPos(x2, y2, z2))
        val box = AABB(
            region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
            (region.maxX + 1).toDouble(), (region.maxY + 1).toDouble(), (region.maxZ + 1).toDouble(),
        )
        if (all().any { it.worldKey == world && it.box.intersects(box) }) return null

        val (screenWidth, screenHeight, _) = region.screenExtents(
            abs(direction.stepX), abs(direction.stepY), abs(direction.stepZ),
        )
        val data = VanillaDisplayData(
            id = UUID.randomUUID(), ownerId = owner, worldKey = world,
            pos1 = region.getMinBlockPos(), pos2 = region.getMaxBlockPos(),
            width = screenWidth, height = screenHeight, facing = direction, rotation = DisplayRotation.NONE,
        )
        if (VanillaServerApi.askCreate(data, null) != null) return null
        DisplayManager.register(data)
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(data) }
        DisplayManager.sendUpdate(data, DisplayManager.getReceivers(data, server))
        return wrap(data)
    }
}

private fun DisplayFacing.toDirection(): Direction = when (this) {
    DisplayFacing.NORTH -> Direction.NORTH
    DisplayFacing.EAST -> Direction.EAST
    DisplayFacing.SOUTH -> Direction.SOUTH
    DisplayFacing.WEST -> Direction.WEST
    DisplayFacing.UP -> Direction.UP
    DisplayFacing.DOWN -> Direction.DOWN
}
