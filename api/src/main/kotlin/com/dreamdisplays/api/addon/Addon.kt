package com.dreamdisplays.api.addon

/**
 * Marks a class as a `Dream Displays` addon.
 *
 * Register an instance with `DreamDisplays.register(...)` on the client or
 * `DreamDisplaysServer.register(...)` on the server; the class may also implement [AddonLifecycle].
 * ```
 * @Addon(
 *     id = "my-addon",
 *     name = "My Addon",
 *     version = "1.0.0"
* )
 * class MyAddon {
 *     @OnDisplay
 *     fun track(display: Display) { ... }
 * }
 * ```
 *
 * Addons are experimental until 1.11.0 and may change.
 *
 * @since 1.10.x
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Addon(
    /** Unique ID, lowercase with dashes. */
    val id: String,

    /** Display name; defaults to [id]. */
    val name: String = "",

    /** Addon version text. */
    val version: String = "",

    /** One-line description. */
    val description: String = "",

    /** Where the addon runs. */
    val side: AddonSide = AddonSide.BOTH,

    /**
     * IDs of addons that must be enabled first.
     *
     * This addon is enabled after them and disabled before them; if one is
     * missing, fails or does not run on the same side, this addon stays disabled.
     */
    val dependsOn: Array<String> = [],
)
