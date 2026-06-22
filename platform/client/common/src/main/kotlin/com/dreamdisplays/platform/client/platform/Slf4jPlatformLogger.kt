package com.dreamdisplays.platform.client.platform

import com.dreamdisplays.api.platform.PlatformLogger
import org.slf4j.LoggerFactory

/** [PlatformLogger] over slf4j; [child] loggers nest names as `parent/child`. */
class Slf4jPlatformLogger(private val name: String) : PlatformLogger {
    /** The underlying slf4j logger named [name]. */
    private val delegate = LoggerFactory.getLogger(name)

    /** Logs [message] at `INFO`. */
    override fun info(message: String) = delegate.info(message)

    /** Logs [message] at `WARN`. */
    override fun warn(message: String) = delegate.warn(message)

    /** Logs [message] (and optional [cause]) at `ERROR`. */
    override fun error(message: String, cause: Throwable?) = delegate.error(message, cause)

    /** Logs [message] at `DEBUG`. */
    override fun debug(message: String) = delegate.debug(message)

    /** Returns a child logger named `this/[name]`. */
    override fun child(name: String): PlatformLogger = Slf4jPlatformLogger("${this.name}/$name")
}
