package com.dreamdisplays.core.protocol.proxy

/**
 * The direction a `dreamdisplays:proxy` packet is allowed to travel. Registry metadata used for
 * send-side validation; never serialized. Deliberately separate from
 * [com.dreamdisplays.api.protocol.PacketDirection] — that one describes the client <-> server
 * `dreamdisplays:v2` channel, this one the proxy <-> backend `dreamdisplays:proxy` channel. The two
 * channels, id spaces, and packet sets never mix, so a client can never decode a proxy packet.
 */
enum class ProxyPacketDirection {
    /** Sent by a backend server, handled on the proxy. */
    BACKEND_TO_PROXY,

    /** Sent by the proxy, handled on a backend server. */
    PROXY_TO_BACKEND,

    /** Valid in both directions. */
    BIDIRECTIONAL,
}
