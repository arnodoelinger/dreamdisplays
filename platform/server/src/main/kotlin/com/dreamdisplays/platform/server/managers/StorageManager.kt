package com.dreamdisplays.platform.server.managers

import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.security.MediaUrlPolicy
import com.dreamdisplays.platform.server.datatypes.*
import com.dreamdisplays.platform.server.storage.StorageBackend
import com.dreamdisplays.platform.server.utils.StoragePackingUtil.DIRECTION_TO_ORDINAL
import com.dreamdisplays.platform.server.utils.StoragePackingUtil.ORDINAL_TO_DIRECTION
import com.dreamdisplays.platform.server.utils.StoragePackingUtil.packFacing
import com.dreamdisplays.platform.server.utils.StoragePackingUtil.packInts
import com.dreamdisplays.platform.server.utils.StoragePackingUtil.packPos
import com.dreamdisplays.platform.server.utils.StoragePackingUtil.toBytes
import com.dreamdisplays.platform.server.utils.StoragePackingUtil.toUUID
import com.dreamdisplays.platform.server.utils.StoragePackingUtil.unpackFacingOrdinal
import com.dreamdisplays.platform.server.utils.StoragePackingUtil.unpackInts
import com.dreamdisplays.platform.server.utils.StoragePackingUtil.unpackPos
import com.dreamdisplays.platform.server.utils.StoragePackingUtil.unpackRotation
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.arnodoelinger.platformweaver.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.replace
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.jspecify.annotations.NullMarked
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*

/**
 * Exposed table definition for persistent display rows. Positions and dimensions are packed to keep
 * the schema compact across both `Paper` and `Fabric` server variants.
 */
class DisplaysTable(prefix: String = "") : Table("${prefix}displays") {
    val id = binary("id", 16)
    val ownerId = binary("ownerId", 16)
    val videoCode = varchar("videoCode", MediaUrlPolicy.MAX_URL_LENGTH).default("")
    val world = varchar("world", 255)
    val pos1 = long("pos1")
    val pos2 = long("pos2")
    val size = long("size")
    val facing = integer("facing")
    val isSync = bool("isSync")
    val duration = long("duration").nullable()
    val lang = varchar("lang", 255).default("")
    val isLocked = bool("isLocked").default(true)
    val mode = integer("mode").default(PlaybackMode.LOCAL.wire)

    override val primaryKey = PrimaryKey(id)
}

/**
 * SQL-backed display storage adapter. Loads persisted rows into platform-specific [DisplayData]
 * objects and writes updates from managers back to SQLite or MySQL through Exposed / Hikari.
 */
@NullMarked
class StorageManager(
    backend: StorageBackend,
    dataDir: File,
    tablePrefix: String = "",
    host: String = "",
    port: String = "",
    database: String = "",
    username: String = "",
    password: String = "",
    private val logger: Logger = LoggerFactory.getLogger("DreamDisplays/Storage"),
) {
    private val table = DisplaysTable(tablePrefix)

    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = when (backend) {
            StorageBackend.SQLITE -> "jdbc:sqlite:${File(dataDir, "dreamdisplays.db").absolutePath}"
            StorageBackend.MYSQL -> "jdbc:mysql://$host:$port/$database?autoReconnect=true&useSSL=false&useInformationSchema=false"
        }
        if (backend != StorageBackend.SQLITE) {
            this.username = username
            this.password = password
        }
        // Read more why SQLite should use a single connection:
        // https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
        maximumPoolSize = if (backend == StorageBackend.SQLITE) 1 else 3
        isAutoCommit = false
    })

    /** Exposed database connection. */
    private val db = Database.connect(dataSource)

    /**
     * Creates the displays table if missing, applies in-place column migrations (`lang`, `isLocked`, `videoCode` widening),
     * and loads previously stored displays into the runtime registry.
     */
    fun createSchema() {
        transaction(db) {
            MigrationUtils.statementsRequiredForDatabaseMigration(table).forEach { stmt -> exec(stmt) }
        }
    }

    /** Persists all in-memory displays and closes the database connection on plugin shutdown. */
    fun disconnect() = dataSource.close()

    /** Load all displays from the database, returning a list of [DisplayData] objects. */
    @PaperOnly
    fun loadAllPaperDisplays(): List<PaperDisplayData> = transaction(db) {
        table.selectAll().mapNotNull(::rowToPaper)
    }

    /** Upserts the full row for [data] into the displays table. */
    @PaperOnly
    fun saveDisplay(data: PaperDisplayData) {
        val worldName = data.pos1.world?.name ?: run {
            logger.error("Cannot save display ${data.id}: world is null.")
            return
        }
        upsert(
            data, worldName,
            packPos(data.pos1.blockX, data.pos1.blockY, data.pos1.blockZ),
            packPos(data.pos2.blockX, data.pos2.blockY, data.pos2.blockZ),
            packFacing(data.facing.ordinal, data.rotation)
        )
    }

    /** Deletes the display with the given [data] from the displays table. */
    fun deleteDisplay(data: DisplayData) = delete(data.id)

    /** Converts a row from the displays table into a [PaperDisplayData] object, or null if the row */
    @PaperOnly
    private fun rowToPaper(row: ResultRow): PaperDisplayData? {
        val id = row[table.id].toUUID()
        val worldName = row[table.world]
        val world = Bukkit.getWorld(worldName)
            ?: runCatching { UUID.fromString(worldName) }.getOrNull()?.let { Bukkit.getWorld(it) }
        if (world == null) {
            logger.warn("Skipping display $id: world '$worldName' not found.")
            return null
        }
        val (x1, y1, z1) = unpackPos(row[table.pos1])
        val (x2, y2, z2) = unpackPos(row[table.pos2])
        val (w, h) = unpackInts(row[table.size])
        val facing = BlockFace.entries.getOrNull(unpackFacingOrdinal(row[table.facing])) ?: BlockFace.NORTH
        val rotation = unpackRotation(row[table.facing])

        return PaperDisplayData(
            id, row[table.ownerId].toUUID(),
            Location(world, x1.toDouble(), y1.toDouble(), z1.toDouble()),
            Location(world, x2.toDouble(), y2.toDouble(), z2.toDouble()),
            w, h, facing, rotation,
        ).applyCommon(row)
    }

    /** Load all displays from the database, returning a list of [VanillaDisplayData] objects. */
    fun loadAllVanillaDisplays(): List<VanillaDisplayData> = transaction(db) {
        table.selectAll().mapNotNull(::rowToVanilla)
    }

    /** Upserts the full row for [data] into the displays table. */
    fun saveDisplay(data: VanillaDisplayData) {
        upsert(
            data, data.worldKey,
            packPos(data.pos1.x, data.pos1.y, data.pos1.z),
            packPos(data.pos2.x, data.pos2.y, data.pos2.z),
            packFacing(DIRECTION_TO_ORDINAL.getValue(data.facing), data.rotation)
        )
    }

    /** Converts a row from the displays table into a [VanillaDisplayData] object. */
    private fun rowToVanilla(row: ResultRow): VanillaDisplayData {
        val (x1, y1, z1) = unpackPos(row[table.pos1])
        val (x2, y2, z2) = unpackPos(row[table.pos2])
        val (w, h) = unpackInts(row[table.size])
        val facing = ORDINAL_TO_DIRECTION.getOrDefault(unpackFacingOrdinal(row[table.facing]), Direction.NORTH)
        val rotation = unpackRotation(row[table.facing])

        return VanillaDisplayData(
            row[table.id].toUUID(), row[table.ownerId].toUUID(),
            row[table.world],
            BlockPos(x1, y1, z1), BlockPos(x2, y2, z2),
            w, h, facing, rotation,
        ).applyCommon(row)
    }

    /** Upserts the given display data into the displays table. */
    private fun upsert(data: DisplayData, worldName: String, p1: Long, p2: Long, facingOrd: Int) {
        transaction(db) {
            table.replace {
                it[id] = data.id.toBytes()
                it[ownerId] = data.ownerId.toBytes()
                it[videoCode] = data.url
                it[world] = worldName
                it[pos1] = p1
                it[pos2] = p2
                it[size] = packInts(data.width, data.height)
                it[facing] = facingOrd
                it[isSync] = data.isSync
                it[duration] = data.duration
                it[lang] = data.lang
                it[isLocked] = data.isLocked
                it[mode] = data.mode.wire
            }
        }
    }

    /** Deletes the display with the given [displayId] from the display table. */
    private fun delete(displayId: UUID) {
        transaction(db) { table.deleteWhere { id eq displayId.toBytes() } }
    }

    /** Applies common properties from a row to a display data object, such as URL and playback mode. */
    private fun <T : DisplayData> T.applyCommon(row: ResultRow): T = apply {
        url = row[table.videoCode]
        val stored = PlaybackMode.fromWire(row[table.mode])
        mode = if (stored != PlaybackMode.LOCAL) stored
        else if (row[table.isSync]) PlaybackMode.SYNCED else PlaybackMode.LOCAL
        duration = row[table.duration]
        lang = row[table.lang]
        isLocked = row[table.isLocked]
    }
}
