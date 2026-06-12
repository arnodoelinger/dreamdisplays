pluginManagement {
    val activeStonecutterVersion = file("versions/active.txt").readText().trim()
    val stonecutterVersions = java.util.Properties().apply {
        file("versions/$activeStonecutterVersion/gradle.properties").inputStream().use { input -> load(input) }
    }
    fun scVersion(name: String): String = stonecutterVersions.getProperty(name)
        ?: error("Missing Stonecutter version property '$name' for $activeStonecutterVersion.")

    includeBuild("gradle")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.quiltmc.org/repository/release/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "net.fabricmc.fabric-loom", "net.fabricmc.fabric-loom-remap" -> useVersion(scVersion("loom.version"))
                "net.neoforged.moddev" -> useVersion(scVersion("moddev.version"))
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.5"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.quiltmc.org/repository/release/")
        maven("https://maven.quiltmc.org/repository/snapshot/")
        maven("https://repo.lostyy.ru/releases")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://jitpack.io")
    }
}

rootProject.name = "dreamdisplays"
include(":native")
include(":protocol")
include(":common")
include(":client")
include(":client:fabric")
include(":client:neoforge")
include(":server")

stonecutter {
    create(rootProject) {
        versions(
            "1.21.11",
            "26.1.2",
            "26.2-rc.1",
        )
    }
}
