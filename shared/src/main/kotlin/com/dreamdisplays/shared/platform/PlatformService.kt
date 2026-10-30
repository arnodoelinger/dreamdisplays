package com.dreamdisplays.shared.platform

import com.dreamdisplays.api.platform.identity.Platform

/** Supplies the [Platform] adapter. */
fun interface PlatformService {
    /** Creates the platform adapter instance. */
    fun create(): Platform
}
