package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.security.CustomMediaPolicy

/**
 * The single place that decides whether a player may point a display at a custom link, shared by
 * the `Paper` and vanilla `setVideo` paths so the two can never drift apart on what is allowed.
 */
internal object CustomMediaGate {
    /** Custom links are switched off for this server. */
    const val KEY_DISABLED = "customMediaDisabled"

    /** The link's host is not allowed here. */
    const val KEY_HOST = "customMediaHostBlocked"

    /** The player lacks the custom-media permission node. */
    const val KEY_PERMISSION = "customMediaNoPermission"

    /** The URL is custom but carries no usable host. */
    const val KEY_INVALID = "invalidURL"

    /**
     * Returns the message key explaining why [url] is refused, or null when it may be applied.
     *
     * [hasPermission] is only consulted for genuinely custom URLs, so a player without the node can
     * still set YouTube and Twitch videos exactly as before - the node gates pasting arbitrary
     * links, not using displays.
     */
    fun refusalKey(url: String, settings: CustomMediaPolicy.Settings, hasPermission: Boolean): String? {
        return when (CustomMediaPolicy.evaluate(url, settings)) {
            CustomMediaPolicy.Verdict.ALLOWED ->
                if (!hasPermission && CustomMediaPolicy.isCustom(url)) KEY_PERMISSION else null

            CustomMediaPolicy.Verdict.DISABLED -> KEY_DISABLED
            CustomMediaPolicy.Verdict.HOST_BLOCKED, CustomMediaPolicy.Verdict.HOST_NOT_ALLOWED -> KEY_HOST
            CustomMediaPolicy.Verdict.MALFORMED -> KEY_INVALID
        }
    }
}
