package com.dreamdisplays.protocol

/**
 * The direction a [DreamPacket] is allowed to travel. Used at registration time to wire the correct
 * send/receive handlers and to reject packets arriving from the wrong side.
 *
 * @since 1.0.0
 */
enum class PacketDirection {
    /** Sent by the client, handled on the server. */
    CLIENT_TO_SERVER,

    /** Sent by the server, handled on the client. */
    SERVER_TO_CLIENT,

    /** Valid in both directions. */
    BIDIRECTIONAL,
}
