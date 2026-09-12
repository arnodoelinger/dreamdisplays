package com.dreamdisplays.core.protocol.proxy

/** Direction a `dreamdisplays:proxy` packet travels (registry metadata, never serialized). */
enum class ProxyPacketDirection {
    BACKEND_TO_PROXY,
    PROXY_TO_BACKEND,
    BIDIRECTIONAL,
}
