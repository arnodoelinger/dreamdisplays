import java.util.*

val active = settingsDir.resolve("versions/active.txt").readText().trim()

val properties = Properties().apply {
    settingsDir.resolve("versions/$active/gradle.properties").inputStream().use { load(it) }
}

gradle.extensions.add("stonecutterVersions", StonecutterVersions(active, properties))
