package com.dreamdisplays.api.protocol

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Protocol constants and compatibility checks.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
object ProtocolVersion {
    /** Minimum subversion of protocol. */
    const val MINIMUM_SUPPORTED = 2

    /** Current subversion of protocol. */
    const val CURRENT = 7

    /** Checks if the given protocol version is compatible with the current version. */
    fun isCompatible(version: Int): Boolean = version in MINIMUM_SUPPORTED..CURRENT

    /** Checks if the given protocol version is outdated. */
    fun isOutdated(version: Int): Boolean = version < CURRENT

    /** Checks if the given protocol version is too new. */
    fun isTooNew(version: Int): Boolean = version > CURRENT
}
