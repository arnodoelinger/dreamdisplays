pluginManagement {
    val scVersions = java.util.Properties().apply {
        val active = file("versions/active.txt").readText().trim()
        file("versions/$active/gradle.properties").inputStream().use(::load)
    }

    fun scVersion(name: String) = scVersions.getProperty(name) ?: error("Missing Stonecutter version property '$name'.")

    includeBuild("gradle")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.parchmentmc.org")
        maven("https://maven.quiltmc.org/repository/release/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    resolutionStrategy {
        eachPlugin {
            val loomVersion = scVersion("loom.version")
            when (requested.id.id) {
                "net.fabricmc.fabric-loom" -> useVersion(loomVersion)
                "net.fabricmc.fabric-loom-remap" ->
                    if (scVersion("minecraft.version").startsWith("1.")) useVersion(loomVersion)
                    else useModule("net.fabricmc:fabric-loom:$loomVersion")

                "net.neoforged.moddev" -> useVersion(scVersion("moddev.version"))
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.6"
    id("dreamdisplays.stonecutter-versions")
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.parchmentmc.org")
        maven("https://maven.quiltmc.org/repository/release/")
        maven("https://maven.quiltmc.org/repository/snapshot/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://jitpack.io")
    }
}

rootProject.name = "dreamdisplays"
include(":native")
include(":core")
include(":api")
include(":media")
include(":media:runtime")
include(":media:player")
include(":media:source")
include(":media:audio")
include(":util")
include(":platform")
include(":platform:resources")
include(":platform:client")
include(":platform:client:common")
include(":platform:client:fabric")
include(":platform:server")

// ModDevGradle issue, ask them wtf is going here
if (!java.lang.Boolean.getBoolean("idea.sync.active")) {
    include(":platform:client:neoforge")
}

stonecutter {
    create(rootProject) {
        versions(
            "1.21.1",
            "1.21.11",
            "26.1.2",
            "26.2",
        )
    }
}
