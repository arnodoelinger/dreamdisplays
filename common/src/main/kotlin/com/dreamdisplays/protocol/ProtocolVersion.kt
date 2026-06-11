package com.dreamdisplays.protocol

object ProtocolVersion {
    const val CURRENT = 4
    const val MINIMUM_SUPPORTED = 2

    fun isCompatible(version: Int): Boolean = version in MINIMUM_SUPPORTED..CURRENT
    fun isOutdated(version: Int): Boolean = version < CURRENT
    fun isTooNew(version: Int): Boolean = version > CURRENT
}
