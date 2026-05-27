package com.dreamdisplays.server.managers

import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.server.FabricConfig
import com.dreamdisplays.server.Main
import com.dreamdisplays.server.Main.Companion.config
import com.dreamdisplays.server.Main.Companion.disablePlugin
import com.dreamdisplays.server.datatypes.FabricDisplayData
import com.dreamdisplays.server.datatypes.PaperDisplayData
import com.dreamdisplays.server.managers.DisplayManager.register
import com.dreamdisplays.server.managers.DisplayManager.save
import com.dreamdisplays.server.meta.Scheduler
import me.inotsleep.utils.logging.LoggingManager.error
import me.inotsleep.utils.storage.StorageSettings
import me.inotsleep.utils.storage.connection.BaseConnection
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.jspecify.annotations.NullMarked
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.ByteBuffer
import java.sql.SQLException
import java.util.*

/**
 * Manages persistence of display data in the database. Handles connection setup, schema
 * migration, and CRUD operations.
 */
@NullMarked class StorageManager {
    var connection: BaseConnection? = null
    var tablePrefix: String? = null

    @PaperOnly lateinit var plugin: Main private set
    @FabricOnly private lateinit var pluginConfig: FabricConfig
    @FabricOnly private lateinit var dataDir: File
    @FabricOnly private val logger = LoggerFactory.getLogger("DreamDisplays/Storage")

    /** Schedules an async DB connect, schema migration, and initial display load. */
    @PaperOnly constructor(plugin: Main) {
        this.plugin = plugin
        val connectTask = Runnable {
            tablePrefix = config.storage.tablePrefix
            try {
                connection = BaseConnection.createConnection(config.storage, plugin.dataFolder)
                connection?.connect() ?: run {
                    error("[StorageManager] Failed to create database connection")
                    disablePlugin()
                    return@Runnable
                }
                paperOnConnect()
            } catch (e: SQLException) {
                error("[StorageManager] Could not connect to database", e)
                disablePlugin()
            }
        }
        Scheduler.runAsync(connectTask)
    }

    /**
     * Creates the displays table if missing, applies in-place column migrations (`lang`, `isLocked`),
     * and loads previously stored displays into the runtime registry.
     */
    @PaperOnly @Throws(SQLException::class)
    private fun paperOnConnect() {
        val conn = connection ?: throw SQLException("[StorageManager] Connection is null.")
        createDisplaysTable(conn)
        applyColumnMigrations(conn)
        register(allDisplays.filterNotNull())
    }

    /** Persists all in-memory displays and closes the database connection on plugin shutdown. */
    @PaperOnly fun onDisable() {
        val conn = connection ?: return
        try {
            save { data: PaperDisplayData -> this.saveDisplay(data) }
            conn.disconnect()
        } catch (e: SQLException) {
            error("[StorageManager] Unable to save data", e)
        }
    }

    /** Upserts the full row for [data] into the displays table. */
    @PaperOnly fun saveDisplay(data: PaperDisplayData) {
        val conn = connection ?: run {
            error("[StorageManager] Cannot save display: connection is null.")
            return
        }
        val world = data.pos1.world ?: run {
            error("[StorageManager] Cannot save display: world is null for display ${data.id}.")
            return
        }
        try {
            conn.executeUpdate(
                upsertSql(),
                uuidToBytes(data.id),
                uuidToBytes(data.ownerId),
                data.url,
                world.name,
                StoragePackUtils.packBlockPos(data.pos1.blockX, data.pos1.blockY, data.pos1.blockZ),
                StoragePackUtils.packBlockPos(data.pos2.blockX, data.pos2.blockY, data.pos2.blockZ),
                StoragePackUtils.pack(data.width, data.height),
                data.facing.ordinal.toByte(),
                data.isSync,
                data.duration,
                data.lang,
                data.isLocked,
            )
        } catch (e: SQLException) {
            error("[StorageManager] Could not save display to database", e)
            disablePlugin()
        }
    }

    /** Loads all display rows from the database and constructs [PaperDisplayData] instances. */
    @PaperOnly val allDisplays: MutableList<PaperDisplayData?>
        get() {
            val conn = connection ?: run {
                error("[StorageManager] Cannot fetch displays: connection is null.")
                return mutableListOf()
            }
            val list: MutableList<PaperDisplayData?> = ArrayList()
            try {
                conn.executeQuery(selectAllSql()).use { rs ->
                    while (rs.next()) {
                        val idBytes = rs.getBytes("id") ?: run {
                            error("[StorageManager] Skipping display row with null id."); continue
                        }
                        val ownerBytes = rs.getBytes("ownerId") ?: run {
                            error("[StorageManager] Skipping display row with null ownerId."); continue
                        }
                        val id = runCatching { bytesToUuid(idBytes) }.getOrElse {
                            error("[StorageManager] Invalid UUID bytes for display id, skipping row", it); continue
                        }
                        val ownerId = runCatching { bytesToUuid(ownerBytes) }.getOrElse {
                            error("[StorageManager] Invalid UUID bytes for ownerId, skipping row", it); continue
                        }
                        val videoCode = rs.getString("videoCode") ?: ""
                        val worldName = rs.getString("world") ?: run {
                            error("[StorageManager] Skipping display $id because world name is null. This is very strange."); continue
                        }

                        var world = Bukkit.getWorld(worldName)
                        if (world == null) {
                            runCatching { UUID.fromString(worldName) }.getOrNull()?.let { wuid ->
                                world = Bukkit.getWorld(wuid)
                            }
                        }
                        if (world == null) {
                            error("[StorageManager] Skipping display $id because world '$worldName' not found on server.")
                            continue
                        }

                        val (x1, y1, z1, x2, y2, z2) = unpackPositions(rs)
                        val (width, height) = unpackSize(rs)
                        val facing = BlockFace.entries.getOrNull(rs.getInt("facing")) ?: BlockFace.NORTH

                        val data = PaperDisplayData(
                            id, ownerId,
                            Location(world, x1.toDouble(), y1.toDouble(), z1.toDouble()),
                            Location(world, x2.toDouble(), y2.toDouble(), z2.toDouble()),
                            width, height, facing,
                        )
                        applyCommonFields(data, rs, videoCode)
                        list.add(data)
                    }
                }
            } catch (e: SQLException) {
                error("[StorageManager] Could not fetch from database", e)
                disablePlugin()
            }
            return list
        }

    /** Removes the row corresponding to [data] from the displays table. */
    @PaperOnly fun deleteDisplay(data: PaperDisplayData) {
        val conn = connection ?: run {
            error("[StorageManager] Cannot delete display: connection is null.")
            return
        }
        try {
            conn.executeUpdate("DELETE FROM ${tablePrefix}displays WHERE id = ?", uuidToBytes(data.id))
        } catch (e: SQLException) {
            error("[StorageManager] Could not delete display from database", e)
            disablePlugin()
        }
    }

    /** Spawns a connect thread that prepares JDBC drivers, then opens and migrates the database. */
    @FabricOnly constructor(config: FabricConfig) {
        this.pluginConfig = config
        this.tablePrefix = config.storage.tablePrefix
        this.dataDir = net.fabricmc.loader.api.FabricLoader.getInstance().gameDir.resolve("dreamdisplays").toFile()
        dataDir.mkdirs()
        Thread({ fabricConnect() }, "dreamdisplays-storage").start()
    }

    /** Connects to the database on a background thread, force-loading JDBC drivers first. */
    @FabricOnly private fun fabricConnect() {
        try {
            // Fabric's Knot classloader bypasses the system DriverManager service-loader,
            // so JDBC drivers from the shadow JAR won't auto-register. Force-load them here.
            runCatching { Class.forName("org.sqlite.JDBC") }
            runCatching { Class.forName("com.mysql.cj.jdbc.Driver") }

            val settings = buildStorageSettings()
            connection = BaseConnection.createConnection(settings, dataDir)
            connection?.connect() ?: run {
                logger.error("[StorageManager] Failed to create database connection")
                return
            }
            fabricOnConnect()
        } catch (e: SQLException) {
            logger.error("[StorageManager] Could not connect to database", e)
        }
    }

    /** Builds a [StorageSettings] instance from the Fabric plugin config. */
    @FabricOnly private fun buildStorageSettings(): StorageSettings {
        val s = pluginConfig.storage
        val settings = StorageSettings()
        settings.host = s.host
        settings.port = s.port
        settings.database = s.database
        settings.password = s.password
        settings.username = s.username
        settings.options = "autoReconnect=true&useSSL=false;"
        settings.tablePrefix = s.tablePrefix
        settings.type = StorageSettings.StorageType.valueOf(s.type.uppercase())
        return settings
    }

    /** Runs schema setup, applies column migrations, and loads displays into the registry after connecting. */
    @FabricOnly private fun fabricOnConnect() {
        val conn = connection ?: return
        createDisplaysTable(conn)
        applyColumnMigrations(conn)
        val displays = fetchAllFabricDisplays()
        DisplayManager.register(displays.filterNotNull())
        logger.info("[StorageManager] Loaded ${displays.size} displays from database.")
    }

    /** Persists all in-memory displays and closes the database connection on plugin shutdown. */
    @FabricOnly fun onDisable(unused: Unit = Unit) {
        val conn = connection ?: return
        try {
            DisplayManager.save { data: FabricDisplayData -> saveDisplay(data) }
            conn.disconnect()
        } catch (e: SQLException) {
            logger.error("[StorageManager] Unable to save data", e)
        }
    }

    /** Upserts the full row for [data] into the displays table. */
    @FabricOnly fun saveDisplay(data: FabricDisplayData) {
        val conn = connection ?: run {
            logger.error("[StorageManager] Cannot save display: connection is null.")
            return
        }
        try {
            conn.executeUpdate(
                upsertSql(),
                uuidToBytes(data.id),
                uuidToBytes(data.ownerId),
                data.url,
                data.worldKey,
                StoragePackUtils.packBlockPos(data.pos1.x, data.pos1.y, data.pos1.z),
                StoragePackUtils.packBlockPos(data.pos2.x, data.pos2.y, data.pos2.z),
                StoragePackUtils.pack(data.width, data.height),
                directionToOrdinal(data.facing).toByte(),
                data.isSync,
                data.duration,
                data.lang,
                data.isLocked,
            )
        } catch (e: SQLException) {
            logger.error("[StorageManager] Could not save display to database", e)
        }
    }

    /** Loads all display rows from the database and constructs [FabricDisplayData] instances. */
    @FabricOnly private fun fetchAllFabricDisplays(): List<FabricDisplayData?> {
        val conn = connection ?: run {
            logger.error("[StorageManager] Cannot fetch displays: connection is null.")
            return emptyList()
        }
        val list = mutableListOf<FabricDisplayData?>()
        try {
            conn.executeQuery(selectAllSql()).use { rs ->
                while (rs.next()) {
                    val idBytes = rs.getBytes("id") ?: continue
                    val ownerBytes = rs.getBytes("ownerId") ?: continue
                    val id = runCatching { bytesToUuid(idBytes) }.getOrElse { continue }
                    val ownerId = runCatching { bytesToUuid(ownerBytes) }.getOrElse { continue }

                    val videoCode = rs.getString("videoCode") ?: ""
                    val worldName = rs.getString("world") ?: continue
                    val (x1, y1, z1, x2, y2, z2) = unpackPositions(rs)
                    val (width, height) = unpackSize(rs)
                    val facing = ordinalToDirection(rs.getInt("facing"))

                    val data = FabricDisplayData(
                        id, ownerId, worldName,
                        BlockPos(x1, y1, z1), BlockPos(x2, y2, z2),
                        width, height, facing,
                    )
                    applyCommonFields(data, rs, videoCode)
                    list.add(data)
                }
            }
        } catch (e: SQLException) {
            logger.error("[StorageManager] Could not fetch from database", e)
        }
        return list
    }

    /** Removes the row corresponding to [data] from the displays table. */
    @FabricOnly fun deleteDisplay(data: FabricDisplayData) {
        val conn = connection ?: run {
            logger.error("[StorageManager] Cannot delete display: connection is null.")
            return
        }
        try {
            conn.executeUpdate("DELETE FROM ${tablePrefix}displays WHERE id = ?", uuidToBytes(data.id))
        } catch (e: SQLException) {
            logger.error("[StorageManager] Could not delete display from database", e)
        }
    }

    /** Maps a stored ordinal (0–3) to a [Direction], defaulting to [Direction.NORTH] for unknown values. */
    @FabricOnly private fun ordinalToDirection(ordinal: Int): Direction = when (ordinal) {
        0 -> Direction.NORTH; 1 -> Direction.EAST; 2 -> Direction.SOUTH; 3 -> Direction.WEST
        else -> Direction.NORTH
    }

    /** Maps a [Direction] to its storage ordinal (0 = N, 1 = E, 2 = S, 3 = W). */
    @FabricOnly private fun directionToOrdinal(direction: Direction): Int = when (direction) {
        Direction.NORTH -> 0; Direction.EAST -> 1; Direction.SOUTH -> 2; Direction.WEST -> 3
        else -> 0
    }

    /** Issues the CREATE TABLE for the displays schema. */
    private fun createDisplaysTable(conn: BaseConnection) {
        conn.executeUpdate(
            "CREATE TABLE IF NOT EXISTS ${tablePrefix}displays (" +
                "id BINARY(16) PRIMARY KEY NOT NULL, " +
                "ownerId BINARY(16) NOT NULL, " +
                "videoCode CHAR(11) NULL, " +
                "world CHAR(255) NOT NULL, " +
                "pos1 BIGINT NOT NULL, " +
                "pos2 BIGINT NOT NULL, " +
                "size BIGINT NOT NULL, " +
                "facing TINYINT UNSIGNED NOT NULL, " +
                "isSync BOOLEAN NOT NULL," +
                "duration BIGINT NULL" +
                ");"
        )
    }

    /** Backfills `lang` and `isLocked` columns on older installs. */
    private fun applyColumnMigrations(conn: BaseConnection) {
        conn.metaData.getColumns(null, null, "${tablePrefix}displays", "lang").use { cols ->
            if (!cols.next()) {
                conn.executeUpdate("ALTER TABLE ${tablePrefix}displays ADD COLUMN lang VARCHAR(255) DEFAULT '' NOT NULL")
            }
        }
        conn.metaData.getColumns(null, null, "${tablePrefix}displays", "isLocked").use { cols ->
            if (!cols.next()) {
                conn.executeUpdate("ALTER TABLE ${tablePrefix}displays ADD COLUMN isLocked BOOLEAN NOT NULL DEFAULT 1")
            }
        }
    }

    private fun upsertSql() = "REPLACE INTO ${tablePrefix}displays " +
        "(id, ownerId, videoCode, world, pos1, pos2, size, facing, isSync, duration, lang, isLocked) " +
        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"

    private fun selectAllSql() = "SELECT id, ownerId, videoCode, world, pos1, pos2, size, facing, isSync, duration, lang, isLocked " +
        "FROM ${tablePrefix}displays"

    private fun unpackPositions(rs: java.sql.ResultSet): SixInts {
        val p1 = rs.getLong("pos1"); val p2 = rs.getLong("pos2")
        return SixInts(
            StoragePackUtils.unpackX(p1), StoragePackUtils.unpackY(p1), StoragePackUtils.unpackZ(p1),
            StoragePackUtils.unpackX(p2), StoragePackUtils.unpackY(p2), StoragePackUtils.unpackZ(p2),
        )
    }

    private fun unpackSize(rs: java.sql.ResultSet): Pair<Int, Int> {
        val s = rs.getLong("size")
        return StoragePackUtils.unpackHigh(s) to StoragePackUtils.unpackLow(s)
    }

    /** Pre-existing common state (url, isSync, duration, lang, isLocked) on a freshly loaded display. */
    private fun applyCommonFields(data: com.dreamdisplays.server.datatypes.DisplayData, rs: java.sql.ResultSet, videoCode: String) {
        data.url = videoCode
        data.isSync = rs.getBoolean("isSync")
        val dur = rs.getLong("duration")
        if (!rs.wasNull()) data.duration = dur
        data.lang = rs.getString("lang") ?: ""
        val isLockedVal = rs.getBoolean("isLocked")
        data.isLocked = if (rs.wasNull()) true else isLockedVal
    }

    /** Serializes [uuid] into its canonical 16-byte big-endian form for database storage. */
    private fun uuidToBytes(uuid: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        return buf.array()
    }

    /** Reconstructs a [UUID] from a 16-byte big-endian array; throws for any other length. */
    private fun bytesToUuid(bytes: ByteArray): UUID {
        require(bytes.size == 16) { "[StorageManager] UUID byte array must be 16 bytes, got ${bytes.size}." }
        val buf = ByteBuffer.wrap(bytes)
        return UUID(buf.getLong(), buf.getLong())
    }

    private data class SixInts(val x1: Int, val y1: Int, val z1: Int, val x2: Int, val y2: Int, val z2: Int)
}

/** Shared block-position packing utilities used by [StorageManager]. */
internal object StoragePackUtils {
    /** Packs a block position into a single Long (26 bits X, 26 Z, 12 Y), Minecraft's standard layout. */
    fun packBlockPos(x: Int, y: Int, z: Int): Long =
        ((x and 0x3FFFFFF).toLong() shl 38) or ((z and 0x3FFFFFF).toLong() shl 12) or (y and 0xFFF).toLong()

    /** Extracts the X coordinate from a packed block position. */
    fun unpackX(packed: Long): Int = (packed shr 38).toInt()

    /** Extracts the Y coordinate from a packed block position (sign-extended from 12 bits). */
    fun unpackY(packed: Long): Int = (packed shl 52 shr 52).toInt()

    /** Extracts the Z coordinate from a packed block position (sign-extended from 26 bits). */
    fun unpackZ(packed: Long): Int = (packed shl 26 shr 38).toInt()

    /** Packs two 32-bit ints into a single Long (used for width / height storage). */
    fun pack(high: Int, low: Int): Long =
        ((high.toLong()) shl 32) or (low.toLong() and 0xFFFFFFFFL)

    /** Returns the high 32 bits of a value packed by [pack]. */
    fun unpackHigh(packed: Long): Int = (packed shr 32).toInt()

    /** Returns the low 32 bits of a value packed by [pack]. */
    fun unpackLow(packed: Long): Int = packed.toInt()
}
