package com.dreamdisplays.api.addon

/**
 * Optional lifecycle hooks for an addon. An addon works without it when it only needs annotated
 * handlers.
 *
 * @since 1.10.x
 */
interface AddonLifecycle {
    /** Called when the addon is enabled. Everything started through [context] ends on [onDisable]. */
    fun onEnable(context: AddonContext) {}

    /** Called when the addon is disabled, before its subscriptions are closed. */
    fun onDisable() {}
}

/**
 * Optional lifecycle hooks for an addon. An addon works without it when it only needs annotated
 * handlers.
 *
 * *Deprecated. Use [AddonLifecycle].*
 *
 * @since 1.10.x
 */
@Deprecated(
    "Renamed to AddonLifecycle.",
    ReplaceWith("AddonLifecycle", "com.dreamdisplays.api.addon.AddonLifecycle"),
)
interface DreamDisplaysAddon : AddonLifecycle

/**
 * What an addon gets when it is enabled.
 *
 * @since 1.10.x
 */
interface AddonContext {
    /** The addon ID. */
    val id: String

    /** Where this addon runs now: [AddonSide.CLIENT] or [AddonSide.SERVER]. */
    val side: AddonSide

    /**
     * Makes [resource] close together with the addon.
     *
     * @return [resource].
     */
    fun <T : AutoCloseable> own(resource: T): T
}
