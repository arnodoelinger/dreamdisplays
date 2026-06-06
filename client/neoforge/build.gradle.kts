import java.util.Properties

plugins {
    id("net.neoforged.moddev")
    id("maven-publish")
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.shadow-conventions")
}

dependencies {
    implementation(project(":common"))
    shadow(project(":common"))
    shadow(libs.kotlinStdlib)
}

val activeStonecutterVersion = rootProject.file("versions/active.txt").readText().trim()
val stonecutterVersions = Properties().apply {
    rootProject.file("versions/$activeStonecutterVersion/gradle.properties").inputStream().use { input -> load(input) }
}
fun scVersion(name: String): String = stonecutterVersions.getProperty(name)
    ?: error("Missing Stonecutter version property '$name' for $activeStonecutterVersion.")

neoForge {
    enable {
        version = scVersion("neoforge.version")
    }
    accessTransformers.from(file("src/main/resources/META-INF/accesstransformer.cfg"))
    runs {
        register("neoClient") {
            client()
        }
    }
}

tasks.processResources {
    val projectVersion = project.version.toString()
    val neoForgeLoaderRange = scVersion("neoforge.loader.range")
    val minecraftRange = scVersion("neoforge.minecraft.range")
    inputs.property("version", projectVersion)
    inputs.property("neoforgeLoaderRange", neoForgeLoaderRange)
    inputs.property("minecraftRange", minecraftRange)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(
            mapOf(
                "version" to projectVersion,
                "neoforgeLoaderRange" to neoForgeLoaderRange,
                "minecraftRange" to minecraftRange,
            )
        )
    }
    filesMatching("assets/dreamdisplays/version.txt") {
        expand(mapOf("version" to projectVersion))
    }
}

java {
    withSourcesJar()
}

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    archiveBaseName.set("dreamdisplays-neoforge")
    archiveVersion.set("${rootProject.version}+mc$activeStonecutterVersion")
    dependencies {
        include(project(":common"))
        include(dependency("org.apache.commons:commons-compress"))
        include(dependency("org.semver4j:semver4j"))
        include(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
        include(dependency("org.jetbrains:annotations"))
    }
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "org.apache.commons.compress",
        "org.tukaani.xz",
        "org.semver4j",
        "kotlin",
        "org.jetbrains.annotations",
        "org.intellij.lang.annotations",
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set("${rootProject.version}+mc$activeStonecutterVersion")
}
