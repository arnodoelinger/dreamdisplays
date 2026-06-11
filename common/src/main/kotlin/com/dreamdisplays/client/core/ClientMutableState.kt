package com.dreamdisplays.client.core

/**
 * Mutable state of the client, which can be updated by modules and observed by other modules.
 *
 * @since 1.8.0
 */
interface ClientMutableState : ClientState {
    /** Whether the client is currently on screen. This may be used to pause or reduce resource usage when the client is not visible. */
    override var isOnScreen: Boolean

    // TODO: implement focus mode
    override var focusMode: Boolean

    /** Enables or disables the display. */
    override var displaysEnabled: Boolean

    /** Whether the client is a premium user (has `dreamdisplays.premium` permission) */
    override var isPremium: Boolean

    /** Whether the client is an admin user (has OP permissions) */
    override var isAdmin: Boolean

    /** Whether the client can report videos to the server. */
    override var isReportingEnabled: Boolean

    /** The ID of the server the client is currently connected to, or `null` if not connected to any server. */
    override var connectedServerId: String?
}
