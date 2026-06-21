package com.dreamdisplays.core.protocol

/** Protocol-v2 version constants and compatibility checks. */
object ProtocolVersion {
    const val CURRENT = 5
    const val MINIMUM_SUPPORTED = 2

    fun isCompatible(version: Int): Boolean = version in MINIMUM_SUPPORTED..CURRENT
    fun isOutdated(version: Int): Boolean = version < CURRENT
    fun isTooNew(version: Int): Boolean = version > CURRENT
}
