import java.util.*

/**
 * Reads `versions/active.txt` and that version's `gradle.properties` once, and shares the
 * result as an extension on [org.gradle.api.invocation.Gradle]. A `Settings` and every
 * `Project` in the same build hang off the same `Gradle` instance, so this is reachable later
 * as `gradle.extensions.getByType<StonecutterVersions>()` from any build script, instead of
 * every consumer re-reading and re-parsing the properties file itself.
 *
 * `settings.gradle.kts`'s own `pluginManagement.resolutionStrategy` block is the one exception:
 * it runs before `plugins {}` (and therefore before this plugin can be applied), so it keeps a
 * small local copy of this same read out of structural necessity, not oversight.
 */
class StonecutterVersions internal constructor(val active: String, private val properties: Properties) {
    /** Looks up a required property for the active `Stonecutter` version. */
    fun get(name: String): String = properties.getProperty(name)
        ?: error("Missing Stonecutter version property '$name' for $active.")

    /** Looks up an optional property for the active `Stonecutter` version. */
    fun getOrNull(name: String): String? = properties.getProperty(name)
}
