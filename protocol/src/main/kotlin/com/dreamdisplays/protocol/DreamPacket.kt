package com.dreamdisplays.protocol

/**
 * Marker for every protocol-v2 packet. The sealed hierarchy exists for exhaustive `when` dispatch
 * in handlers; it is never serialized polymorphically — the wire envelope carries an explicit
 * type id resolved through [PacketRegistry].
 */
sealed interface DreamPacket

/**
 * The direction a [DreamPacket] is allowed to travel. Registry metadata used for send-side
 * validation; never serialized.
 */
enum class PacketDirection {
    /** Sent by the client, handled on the server. */
    CLIENT_TO_SERVER,

    /** Sent by the server, handled on the client. */
    SERVER_TO_CLIENT,

    /** Valid in both directions. */
    BIDIRECTIONAL,
}
