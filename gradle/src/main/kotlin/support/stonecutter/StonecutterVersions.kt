package support.stonecutter

class StonecutterVersions internal constructor(val active: String, private val properties: Map<String, String>) {
    fun get(name: String): String = properties[name]
        ?: error("Missing Stonecutter version property '$name' for $active.")

    fun getOrNull(name: String): String? = properties[name]
}
