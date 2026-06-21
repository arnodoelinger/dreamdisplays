package com.dreamdisplays.api.platform

interface PlatformLogger {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, cause: Throwable? = null)
    fun debug(message: String)
    fun child(name: String): PlatformLogger
}
