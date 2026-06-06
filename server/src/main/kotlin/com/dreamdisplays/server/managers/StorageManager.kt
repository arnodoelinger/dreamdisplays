package com.dreamdisplays.server.managers

import com.dreamdisplays.server.datatypes.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.arsmotorin.ofrat.*
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
import java.nio.ByteBuffer
import java.util.*

/**
 * Database table for storing display data.
 */
class DisplaysTable(prefix: String = "") : Table("${prefix}displays") {
    val id = binary("id", 16)
    val ownerId = binary("ownerId", 16)
    val videoCode = varchar("videoCode", 255).default("")
    val world = varchar("world", 255)
    val pos1 = long("pos1")
    val pos2 = long("pos2")
    val size = long("size")
    val facing = integer("facing")
    val isSync = bool("isSync")
    val duration = long("duration").nullable()
    val lang = varchar("lang", 255).default("")
    val isLocked = bool("isLocked").default(true)

    override val primaryKey = PrimaryKey(id)
}

@NullMarked class StorageManager(
    type: String,
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
        jdbcUrl = when (type.uppercase()) {
            "SQLITE" -> "jdbc:sqlite:${File(dataDir, "dreamdisplays.db").absolutePath}"
            "MYSQL" -> "jdbc:mysql://$host:$port/$database?autoReconnect=true&useSSL=false&useInformationSchema=false"
            else -> error("Unsupported storage type: $type")
        }
        if (type.uppercase() != "SQLITE") {
            this.username = username
            this.password = password
        }
        // Read more why SQLite should use a single connection:
        // https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
        maximumPoolSize = if (type.uppercase() == "SQLITE") 1 else 3
        isAutoCommit = false
    })

    private val db = Database.connect(dataSource)

    /**
     * Creates the displays table if missing, applies in-place column migrations (`lang`, `isLocked`),
     * and loads previously stored displays into the runtime registry.
     */
    fun createSchema() {
        transaction(db) {
            MigrationUtils.statementsRequiredForDatabaseMigration(table).forEach { stmt -> exec(stmt) }
        }
    }

    /** Persists all in-memory displays and closes the database connection on plugin shutdown. */
    fun disconnect() = dataSource.close()

    @PaperOnly fun loadAllPaperDisplays(): List<PaperDisplayData> = transaction(db) {
        table.selectAll().mapNotNull(::rowToPaper)
    }

    /** Upserts the full row for [data] into the displays table. */
    @PaperOnly fun saveDisplay(data: PaperDisplayData) {
        val worldName = data.pos1.world?.name ?: run {
            logger.error("Cannot save display ${data.id}: world is null")
            return
        }
        upsert(data, worldName,
            packPos(data.pos1.blockX, data.pos1.blockY, data.pos1.blockZ),
            packPos(data.pos2.blockX, data.pos2.blockY, data.pos2.blockZ),
            data.facing.ordinal)
    }

    /** Deletes the display with the given [data] from the displays table. */
    fun deleteDisplay(data: DisplayData) = delete(data.id)

    /** Upserts all rows in [displays] into the displays table. */
    @PaperOnly fun saveAllPaper(displays: Collection<PaperDisplayData>) {
        displays.forEach(::saveDisplay)
    }

    @PaperOnly private fun rowToPaper(row: ResultRow): PaperDisplayData? {
        val id = row[table.id].toUUID()
        val worldName = row[table.world]
        val world = Bukkit.getWorld(worldName)
            ?: runCatching { UUID.fromString(worldName) }.getOrNull()?.let { Bukkit.getWorld(it) }
        if (world == null) {
            logger.warn("Skipping display $id: world '$worldName' not found")
            return null
        }
        val (x1, y1, z1) = unpackPos(row[table.pos1])
        val (x2, y2, z2) = unpackPos(row[table.pos2])
        val (w, h) = unpackInts(row[table.size])
        val facing = BlockFace.entries.getOrNull(row[table.facing]) ?: BlockFace.NORTH

        return PaperDisplayData(
            id, row[table.ownerId].toUUID(),
            Location(world, x1.toDouble(), y1.toDouble(), z1.toDouble()),
            Location(world, x2.toDouble(), y2.toDouble(), z2.toDouble()),
            w, h, facing,
        ).applyCommon(row)
    }

    @FabricOnly fun loadAllFabricDisplays(): List<FabricDisplayData> = transaction(db) {
        table.selectAll().mapNotNull(::rowToFabric)
    }

    @FabricOnly fun saveDisplay(data: FabricDisplayData) {
        upsert(data, data.worldKey,
            packPos(data.pos1.x, data.pos1.y, data.pos1.z),
            packPos(data.pos2.x, data.pos2.y, data.pos2.z),
            DIRECTION_TO_ORDINAL.getValue(data.facing))
    }

    @FabricOnly fun saveAllFabric(displays: Collection<FabricDisplayData>) {
        displays.forEach(::saveDisplay)
    }

    @FabricOnly private fun rowToFabric(row: ResultRow): FabricDisplayData {
        val (x1, y1, z1) = unpackPos(row[table.pos1])
        val (x2, y2, z2) = unpackPos(row[table.pos2])
        val (w, h) = unpackInts(row[table.size])
        val facing = ORDINAL_TO_DIRECTION.getOrDefault(row[table.facing], Direction.NORTH)

        return FabricDisplayData(
            row[table.id].toUUID(), row[table.ownerId].toUUID(),
            row[table.world],
            BlockPos(x1, y1, z1), BlockPos(x2, y2, z2),
            w, h, facing,
        ).applyCommon(row)
    }

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
            }
        }
    }

    private fun delete(displayId: UUID) {
        transaction(db) { table.deleteWhere { id eq displayId.toBytes() } }
    }

    private fun <T : DisplayData> T.applyCommon(row: ResultRow): T = apply {
        url = row[table.videoCode]
        isSync = row[table.isSync]
        duration = row[table.duration]
        lang = row[table.lang]
        isLocked = row[table.isLocked]
    }

    companion object {
        private val DIRECTION_TO_ORDINAL = mapOf(
            Direction.NORTH to 0, Direction.EAST to 1, Direction.SOUTH to 2, Direction.WEST to 3
        )
        private val ORDINAL_TO_DIRECTION = mapOf(
            0 to Direction.NORTH, 1 to Direction.EAST, 2 to Direction.SOUTH, 3 to Direction.WEST
        )

        private fun packPos(x: Int, y: Int, z: Int): Long =
            ((x and 0x3FFFFFF).toLong() shl 38) or ((z and 0x3FFFFFF).toLong() shl 12) or (y and 0xFFF).toLong()

        private fun unpackPos(packed: Long) = Triple(
            (packed shr 38).toInt(),
            (packed shl 52 shr 52).toInt(),
            (packed shl 26 shr 38).toInt()
        )

        private fun packInts(high: Int, low: Int): Long =
            (high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL)

        private fun unpackInts(packed: Long): Pair<Int, Int> =
            (packed shr 32).toInt() to packed.toInt()

        private fun UUID.toBytes(): ByteArray = ByteBuffer.allocate(16).apply {
            putLong(mostSignificantBits); putLong(leastSignificantBits)
        }.array()

        private fun ByteArray.toUUID(): UUID = ByteBuffer.wrap(this).let { UUID(it.getLong(), it.getLong()) }
    }
}
