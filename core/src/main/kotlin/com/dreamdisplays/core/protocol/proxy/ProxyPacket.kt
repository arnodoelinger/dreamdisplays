package com.dreamdisplays.core.protocol.proxy

/**
 * Marker for every packet carried on the `dreamdisplays:proxy` channel, between a `BungeeCord` /
 * `Velocity` proxy plugin and the `Dream Displays` backend plugin. Mirrors
 * [com.dreamdisplays.core.protocol.DreamPacket], but for the proxy <-> backend link rather than the
 * client <-> server one — the two are never mixed on the wire or in a single registry.
 */
sealed interface ProxyPacket
