package com.dreamdisplays.platform.server.storage

import java.io.Closeable
import java.io.PrintWriter
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.*
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * `SQLite` connections served by a private copy of `sqlite-jdbc`, loaded from the jar nested in ours
 * into its own class loader.
 *
 * Mod loaders would otherwise hand our `org.sqlite` classes to every other mod that bundles the
 * driver, and vice versa: `Fabric` has one flat classpath, `NeoForge` shares the newest nested copy.
 */
class IsolatedSqliteDataSource private constructor(
    private val url: String,
    private val driver: Driver,
    private val loader: URLClassLoader,
    private val jar: Path,
) : DataSource, Closeable {
    override fun getConnection(): Connection =
        driver.connect(url, Properties()) ?: throw SQLException("Isolated SQLite driver rejected $url.")

    override fun getConnection(username: String?, password: String?): Connection = connection

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()

    override fun <T : Any?> unwrap(iface: Class<T>): T = throw SQLException("Not a wrapper for ${iface.name}.")

    override fun isWrapperFor(iface: Class<*>): Boolean = false

    override fun close() {
        runCatching { loader.close() }
        runCatching { Files.deleteIfExists(jar) }
    }

    companion object {
        private const val NESTED_JAR = "META-INF/dreamdisplays/sqlite-jdbc.jar"

        fun createOrNull(url: String): IsolatedSqliteDataSource? {
            val nested = IsolatedSqliteDataSource::class.java.getResourceAsStream("/$NESTED_JAR") ?: return null
            val jar = Files.createTempFile("dreamdisplays-sqlite-jdbc-", ".jar")
            nested.use { Files.copy(it, jar, StandardCopyOption.REPLACE_EXISTING) }
            jar.toFile().deleteOnExit()
            val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader())
            val driver = loader.loadClass("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as Driver
            return IsolatedSqliteDataSource(url, driver, loader, jar)
        }
    }
}
