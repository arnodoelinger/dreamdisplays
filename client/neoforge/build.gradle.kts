import java.util.Properties

plugins {
    id("net.neoforged.moddev")
    id("maven-publish")
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.shadow-conventions")
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":common"))
    shadow(project(":common"))
    shadow(project(":protocol"))
    shadow(libs.kotlinxSerializationProtobuf)
    shadow(libs.kotlinStdlib)
    shadow(libs.newpipeExtractor)
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
    val javaVersion = scVersion("java.version")
    inputs.property("version", projectVersion)
    inputs.property("neoforgeLoaderRange", neoForgeLoaderRange)
    inputs.property("minecraftRange", minecraftRange)
    inputs.property("javaVersion", javaVersion)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(
            mapOf(
                "version" to projectVersion,
                "neoforgeLoaderRange" to neoForgeLoaderRange,
                "minecraftRange" to minecraftRange,
            )
        )
    }
    filesMatching("dreamdisplays.mixins.json") {
        expand(mapOf("javaVersion" to javaVersion))
    }
    filesMatching("assets/dreamdisplays/version.txt") {
        expand(mapOf("version" to projectVersion))
    }
    // Bundles the optional Rust media library for the host platform when it has been
    // built locally. Multi-platform bundles come from CI.
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val nativeKey = when {
        os.contains("win") -> if (arch.contains("aarch64") || arch.contains("arm")) "windows-aarch64" else "windows-x64"
        os.contains("mac") -> if (arch.contains("aarch64") || arch.contains("arm")) "macos-aarch64" else "macos-x64"
        else -> if (arch.contains("aarch64") || arch.contains("arm")) "linux-aarch64" else "linux-x64"
    }
    val nativeLib = rootProject.file("native/target/release/" + System.mapLibraryName("dreamdisplays_native"))
    if (nativeLib.isFile) {
        from(nativeLib) { into("dreamdisplays-natives/$nativeKey") }
    }
}

java {
    withSourcesJar()
}

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    archiveBaseName.set("dreamdisplays-neoforge")
    archiveVersion.set("$activeStonecutterVersion-${rootProject.version}")
    dependencies {
        include(project(":common"))
        include(project(":protocol"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-serialization-core"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-serialization-protobuf"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-serialization-protobuf-jvm"))
        include(dependency("org.apache.commons:commons-compress"))
        include(dependency("org.tukaani:xz"))
        include(dependency("org.semver4j:semver4j"))
        include(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
        include(dependency("org.jetbrains:annotations"))
        // NewPipeExtractor (in-process YouTube resolver) + its runtime deps. jsr305 is intentionally
        // omitted: its annotations are not needed at runtime and Guava already provides them.
        include(dependency("com.github.TeamNewPipe:NewPipeExtractor"))
        include(dependency("com.github.TeamNewPipe:nanojson"))
        include(dependency("org.jsoup:jsoup"))
        include(dependency("com.google.protobuf:protobuf-javalite"))
        include(dependency("org.mozilla:rhino"))
        include(dependency("org.mozilla:rhino-engine"))
    }
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "org.apache.commons.compress",
        "org.tukaani.xz",
        "org.semver4j",
        "kotlin",
        "kotlinx",
        "org.jetbrains.annotations",
        "org.intellij.lang.annotations",
        "org.schabi.newpipe",
        "com.grack.nanojson",
        "org.jsoup",
        "com.google.protobuf",
        "org.mozilla.javascript",
        "org.mozilla.classfile",
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set("$activeStonecutterVersion-${rootProject.version}")
}
