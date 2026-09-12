import support.stonecutter.StonecutterVersions
import support.stonecutter.VersionsJson

val versionsJson = VersionsJson.load(settingsDir.resolve("versions.json"))

settingsDir.resolve("versions/active.txt").apply {
    parentFile.mkdirs()
    writeText(versionsJson.active)
}

gradle.extensions.add("stonecutterVersions", StonecutterVersions(versionsJson.active, versionsJson.activeProperties))
gradle.extensions.add("stonecutterAllVersions", versionsJson.allVersions.toList())
