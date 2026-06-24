package com.dreamdisplays.platform.client.core

/**
 * Immutable state of the client, which can be observed by modules but not updated directly.
 * This is used to expose the current state of the client to modules without allowing them to modify it directly,
 * ensuring that all state changes go through the appropriate channels and can be properly managed and validated.
 */
interface ClientState {
    /** Whether the client is currently on screen. This may be used to pause or reduce resource usage when the client is not visible. */
    val isOnScreen: Boolean

    /** Whether the client is currently in focus mode. */
    val focusMode: Boolean

    /** Whether displays are currently enabled. */
    val displaysEnabled: Boolean

    /** Whether the client is a premium user (has `dreamdisplays.premium` permission). */
    val isPremium: Boolean

    /** Whether the client is an admin user (has OP permissions). */
    val isAdmin: Boolean

    /** Whether the client can report videos to the server. */
    val isReportingEnabled: Boolean

    /** The ID of the server the client is currently connected to, or `null` if not connected to any server. */
    val connectedServerId: String?
}
