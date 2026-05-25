package com.dreamdisplays.server.managers

import com.dreamdisplays.server.Config
import com.dreamdisplays.server.datatypes.DisplayData
import me.inotsleep.utils.storage.StorageSettings
import me.inotsleep.utils.storage.connection.BaseConnection
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.ByteBuffer
import java.sql.SQLException
import java.util.*

/**
 * Manages storage and retrieval of display data from the database.
 *
 * `Fabric server` implementation.
 */
class StorageManager(private val config: Config) {
    // Paper start
    private val logger = LoggerFactory.getLogger("DreamDisplays/Storage")

    var connection: BaseConnection? = null
    private val tablePrefix: String = config.storage.tablePrefix

    // Fabric server start
    private val dataDir: File = FabricLoader.getInstance().gameDir.resolve("dreamdisplays").toFile()
    // Fabric server end

    init {
        dataDir.mkdirs()
        // Fabric server start
        Thread({
            connect()
        }, "dreamdisplays-storage").start()
        // Fabric server end
    }

    private fun connect() {
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
            onConnect()
        } catch (e: SQLException) {
            logger.error("[StorageManager] Could not connect to database", e)
        }
    }

    // Fabric server start
    private fun buildStorageSettings(): StorageSettings {
        val s = config.storage
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
    // Fabric server end

    private fun onConnect() {
        val conn = connection ?: return
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

        val meta = conn.metaData
        meta.getColumns(null, null, "${tablePrefix}displays", "lang").use { cols ->
            if (!cols.next()) {
                conn.executeUpdate(
                    "ALTER TABLE ${tablePrefix}displays " +
                            "ADD COLUMN lang VARCHAR(255) DEFAULT '' NOT NULL"
                )
            }
        }
        meta.getColumns(null, null, "${tablePrefix}displays", "isLocked").use { cols ->
            if (!cols.next()) {
                conn.executeUpdate(
                    "ALTER TABLE ${tablePrefix}displays " +
                            "ADD COLUMN isLocked BOOLEAN NOT NULL DEFAULT 1"
                )
            }
        }

        val allDisplays = fetchAllDisplays()
        DisplayManager.register(allDisplays.filterNotNull())
        logger.info("[StorageManager] Loaded ${allDisplays.size} displays from database.")
    }

    fun onDisable() {
        val conn = connection ?: return
        try {
            DisplayManager.save { saveDisplay(it) }
            conn.disconnect()
        } catch (e: SQLException) {
            logger.error("[StorageManager] Unable to save data", e)
        }
    }

    fun saveDisplay(data: DisplayData) {
        val conn = connection ?: run {
            logger.error("[StorageManager] Cannot save display: connection is null.")
            return
        }

        val sql = "REPLACE INTO ${tablePrefix}displays " +
                "(id, ownerId, videoCode, world, pos1, pos2, size, facing, isSync, duration, lang, isLocked) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"

        try {
            conn.executeUpdate(
                sql,
                uuidToBytes(data.id),
                uuidToBytes(data.ownerId),
                data.url,
                // Fabric server start
                data.worldKey,
                // Fabric server end
                packBlockPos(data.pos1.x, data.pos1.y, data.pos1.z),
                packBlockPos(data.pos2.x, data.pos2.y, data.pos2.z),
                pack(data.width, data.height),
                // Fabric server start
                directionToOrdinal(data.facing).toByte(),
                // Fabric server end
                data.isSync,
                data.duration,
                data.lang,
                data.isLocked
            )
        } catch (e: SQLException) {
            logger.error("[StorageManager] Could not save display to database", e)
        }
    }

    private fun fetchAllDisplays(): List<DisplayData?> {
        val conn = connection ?: run {
            logger.error("[StorageManager] Cannot fetch displays: connection is null.")
            return emptyList()
        }

        val sql = "SELECT id, ownerId, videoCode, world, pos1, pos2, size, facing, isSync, duration, lang, isLocked " +
                "FROM ${tablePrefix}displays"
        val list = mutableListOf<DisplayData?>()

        try {
            conn.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val idBytes = rs.getBytes("id") ?: continue
                    val ownerBytes = rs.getBytes("ownerId") ?: continue

                    val id = runCatching { bytesToUuid(idBytes) }.getOrElse { continue }
                    val ownerId = runCatching { bytesToUuid(ownerBytes) }.getOrElse { continue }

                    val videoCode = rs.getString("videoCode") ?: ""
                    val worldName = rs.getString("world") ?: continue

                    val packed1 = rs.getLong("pos1")
                    val packed2 = rs.getLong("pos2")
                    val x1 = unpackX(packed1); val y1 = unpackY(packed1); val z1 = unpackZ(packed1)
                    val x2 = unpackX(packed2); val y2 = unpackY(packed2); val z2 = unpackZ(packed2)

                    val sizePacked = rs.getLong("size")
                    val width = unpackHigh(sizePacked)
                    val height = unpackLow(sizePacked)

                    val facingIndex = rs.getInt("facing")
                    // Fabric server start
                    val facing = ordinalToDirection(facingIndex)

                    val data = DisplayData(
                        id, ownerId, worldName,
                        BlockPos(x1, y1, z1), BlockPos(x2, y2, z2),
                        width, height, facing
                    )
                    // Fabric server end
                    data.url = videoCode
                    data.isSync = rs.getBoolean("isSync")
                    data.isLocked = rs.getBoolean("isLocked")
                    val dur = rs.getLong("duration")
                    if (!rs.wasNull()) data.duration = dur
                    data.lang = rs.getString("lang") ?: ""
                    list.add(data)
                }
            }
        } catch (e: SQLException) {
            logger.error("[StorageManager] Could not fetch from database", e)
        }

        return list
    }

    fun deleteDisplay(data: DisplayData) {
        val conn = connection ?: run {
            logger.error("[StorageManager] Cannot delete display: connection is null.")
            return
        }
        try {
            conn.executeUpdate(
                "DELETE FROM ${tablePrefix}displays WHERE id = ?",
                uuidToBytes(data.id)
            )
        } catch (e: SQLException) {
            logger.error("[StorageManager] Could not delete display from database", e)
        }
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        return buf.array()
    }

    private fun bytesToUuid(bytes: ByteArray): UUID {
        require(bytes.size == 16) { "UUID byte array must be 16 bytes" }
        val buf = ByteBuffer.wrap(bytes)
        return UUID(buf.getLong(), buf.getLong())
    }

    // Fabric server start
    private fun ordinalToDirection(ordinal: Int): Direction {
        return when (ordinal) {
            0 -> Direction.NORTH
            1 -> Direction.EAST
            2 -> Direction.SOUTH
            3 -> Direction.WEST
            else -> Direction.NORTH
        }
    }

    private fun directionToOrdinal(direction: Direction): Int {
        return when (direction) {
            Direction.NORTH -> 0
            Direction.EAST -> 1
            Direction.SOUTH -> 2
            Direction.WEST -> 3
            else -> 0
        }
    }
    // Fabric server end

    companion object {
        fun packBlockPos(x: Int, y: Int, z: Int): Long =
            ((x and 0x3FFFFFF).toLong() shl 38) or ((z and 0x3FFFFFF).toLong() shl 12) or (y and 0xFFF).toLong()

        fun unpackX(packed: Long): Int = (packed shr 38).toInt()
        fun unpackY(packed: Long): Int = (packed shl 52 shr 52).toInt()
        fun unpackZ(packed: Long): Int = (packed shl 26 shr 38).toInt()

        fun pack(high: Int, low: Int): Long =
            ((high.toLong()) shl 32) or (low.toLong() and 0xFFFFFFFFL)

        fun unpackHigh(packed: Long): Int = (packed shr 32).toInt()
        fun unpackLow(packed: Long): Int = packed.toInt()
    }
    // Paper end
}
