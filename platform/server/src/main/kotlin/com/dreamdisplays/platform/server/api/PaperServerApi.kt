package com.dreamdisplays.platform.server.api

import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.display.model.property.DisplayRotation
import com.dreamdisplays.api.server.ServerDisplay
import com.dreamdisplays.api.server.ServerDisplays
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.shared.policy.CustomMediaPolicy
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.util.BoundingBox
import java.util.UUID
import kotlin.math.abs

/** `Paper` implementation of the public server API. */
@PaperOnly
object PaperServerApi : BaseServerBackend() {
    override val displays: ServerDisplays get() = PaperServerDisplays()

    override fun wrap(data: DisplayData): ServerDisplay = PaperServerDisplay(data as PaperDisplayData, this)
}

/** [ServerDisplay] over Paper display data. Resolves the display on every call. */
@PaperOnly
internal class PaperServerDisplay(
    private val paper: PaperDisplayData,
    backend: BaseServerBackend,
) : ServerDisplayBase(paper, backend) {
    override val isAlive: Boolean get() = DisplayManager.getDisplayData(paper.id) != null
    override val world: String get() = paper.pos1.world?.name.orEmpty()
    override val x: Double get() = paper.box.centerX
    override val y: Double get() = paper.box.centerY
    override val z: Double get() = paper.box.centerZ

    override fun customMediaSettings(): CustomMediaPolicy.Settings = PaperServer.config.settings.customMediaPolicy

    override fun save() = runAsync { PaperServer.getInstance().storage.saveDisplay(paper) }

    override fun broadcast() = DisplayManager.broadcastUpdate(paper)

    override fun doDelete() = DisplayManager.delete(paper.id)

    override fun doMove(
        world: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, facing: DisplayFacing,
    ): CommandResult {
        val bukkitWorld = Bukkit.getWorld(world) ?: return CommandResult.REFUSED
        val face = facing.toBlockFace()
        val region = RegionUtil.calculateRegion(
            Location(bukkitWorld, x1.toDouble(), y1.toDouble(), z1.toDouble()),
            Location(bukkitWorld, x2.toDouble(), y2.toDouble(), z2.toDouble()),
        )
        val box = BoundingBox(
            region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
            (region.maxX + 1).toDouble(), (region.maxY + 1).toDouble(), (region.maxZ + 1).toDouble(),
        )
        val overlaps = DisplayManager.getDisplays().filterIsInstance<PaperDisplayData>()
            .any { it.id != paper.id && it.pos1.world == bukkitWorld && it.box.overlaps(box) }
        if (overlaps) return CommandResult.REFUSED

        val before = DisplayManager.getReceivers(paper)
        val (screenWidth, screenHeight, _) = region.screenExtents(abs(face.modX), abs(face.modY), abs(face.modZ))
        paper.relocate(region.getMinLocation(bukkitWorld), region.getMaxLocation(bukkitWorld), face, screenWidth, screenHeight)
        DisplayManager.onMoved(paper)
        save()
        DisplayManager.sendUpdate(paper, before)
        broadcast()
        return CommandResult.OK
    }
}

/** Lazy view over `Paper` displays. */
@PaperOnly
internal class PaperServerDisplays(
    filters: List<(PaperDisplayData) -> Boolean> = [],
) : ServerDisplaysBase<PaperDisplayData>(filters) {
    override fun all(): List<PaperDisplayData> = DisplayManager.getDisplays().filterIsInstance<PaperDisplayData>()
    override fun wrap(data: PaperDisplayData): ServerDisplay = PaperServerApi.wrap(data)
    override fun worldOf(data: PaperDisplayData): String = data.pos1.world?.name.orEmpty()
    override fun centerOf(data: PaperDisplayData) = Triple(data.box.centerX, data.box.centerY, data.box.centerZ)
    override fun withFilters(filters: List<(PaperDisplayData) -> Boolean>): ServerDisplays = PaperServerDisplays(filters)

    override fun create(
        owner: UUID,
        world: String,
        x1: Int, y1: Int, z1: Int,
        x2: Int, y2: Int, z2: Int,
        facing: DisplayFacing,
    ): ServerDisplay? {
        val bukkitWorld = Bukkit.getWorld(world) ?: return null
        val face = facing.toBlockFace()
        val region = RegionUtil.calculateRegion(
            Location(bukkitWorld, x1.toDouble(), y1.toDouble(), z1.toDouble()),
            Location(bukkitWorld, x2.toDouble(), y2.toDouble(), z2.toDouble()),
        )
        val box = BoundingBox(
            region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
            (region.maxX + 1).toDouble(), (region.maxY + 1).toDouble(), (region.maxZ + 1).toDouble(),
        )
        if (all().any { it.pos1.world == bukkitWorld && it.box.overlaps(box) }) return null

        val (screenWidth, screenHeight, _) = region.screenExtents(abs(face.modX), abs(face.modY), abs(face.modZ))
        val data = PaperDisplayData(
            UUID.randomUUID(), owner,
            region.getMinLocation(bukkitWorld), region.getMaxLocation(bukkitWorld),
            screenWidth, screenHeight, face, DisplayRotation.NONE,
        )
        if (PaperServerApi.askCreate(data, null) != null) return null
        DisplayManager.register(data)
        return wrap(data)
    }
}

private fun DisplayFacing.toBlockFace(): BlockFace = when (this) {
    DisplayFacing.NORTH -> BlockFace.NORTH
    DisplayFacing.EAST -> BlockFace.EAST
    DisplayFacing.SOUTH -> BlockFace.SOUTH
    DisplayFacing.WEST -> BlockFace.WEST
    DisplayFacing.UP -> BlockFace.UP
    DisplayFacing.DOWN -> BlockFace.DOWN
}
