package com.dreamdisplays.client.core

/**
 * Immutable state of the client, which can be observed by modules but not updated directly.
 * This is used to expose the current state of the client to modules without allowing them to modify it directly,
 * ensuring that all state changes go through the appropriate channels and can be properly managed and validated.
 *
 * @since 1.8.0
 */
interface ClientState {
    val isOnScreen: Boolean
    val focusMode: Boolean
    val displaysEnabled: Boolean
    val isPremium: Boolean
    val isAdmin: Boolean
    val isReportingEnabled: Boolean
    val connectedServerId: String?
}
