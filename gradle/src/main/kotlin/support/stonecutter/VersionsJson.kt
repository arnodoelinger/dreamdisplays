package support.stonecutter

import groovy.json.JsonSlurper
import java.io.File

class VersionsJson private constructor(
    val active: String,
    private val versions: Map<String, Map<String, String>>,
) {
    fun propertiesFor(version: String): Map<String, String> = versions[version] ?: error("No entry for '$version'.")

    val activeProperties: Map<String, String> get() = propertiesFor(active)
    val allVersions: Set<String> get() = versions.keys

    @Suppress("UNCHECKED_CAST")
    companion object {
        fun load(file: File): VersionsJson {
            val root = JsonSlurper().parse(file) as Map<String, Any?>
            val active = root["active"] as? String?
            val raw = root["versions"] as? Map<String, Map<String, Any?>>?
            val versions = raw?.mapValues { (_, props) -> props.mapValues { (_, v) -> v.toString() } }
            return VersionsJson(active!!, versions!!)
        }
    }
}
