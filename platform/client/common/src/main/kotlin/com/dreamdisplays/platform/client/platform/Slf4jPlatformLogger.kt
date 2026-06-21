package com.dreamdisplays.platform.client.platform

import com.dreamdisplays.platform.api.PlatformLogger
import org.slf4j.LoggerFactory

/** [PlatformLogger] over slf4j; [child] loggers nest names as `parent/child`. */
class Slf4jPlatformLogger(private val name: String) : PlatformLogger {

    private val delegate = LoggerFactory.getLogger(name)

    override fun info(message: String) = delegate.info(message)
    override fun warn(message: String) = delegate.warn(message)
    override fun error(message: String, cause: Throwable?) = delegate.error(message, cause)
    override fun debug(message: String) = delegate.debug(message)

    override fun child(name: String): PlatformLogger = Slf4jPlatformLogger("${this.name}/$name")
}
