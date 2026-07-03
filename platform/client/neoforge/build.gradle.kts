import java.util.*

plugins {
    id("net.neoforged.moddev")
    id("maven-publish")
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.native-resources")
    id("dreamdisplays.shadow-conventions")
    id("io.github.arnodoelinger.platformweaver") version libs.versions.platformweaver
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

sourceSets.main {
    kotlin.srcDir(project(":platform:server").layout.buildDirectory.dir("generated/chisel/main/kotlin"))
}

platformweaver {
    target = "neoforge"
    chameleonsDir = null
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(":platform:server:chiselSource")
}
tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(":platform:server:chiselSource")
}

dependencies {
    compileOnly(libs.platformweaverAnnotations)
    implementation(project(":platform:client:common"))
    implementation(libs.tomlj)
    implementation(libs.semver4j)
    implementation(libs.exposedCore)
    implementation(libs.exposedJdbc)
    implementation(libs.exposedMigrationJdbc)
    implementation(libs.hikari)
    runtimeOnly(libs.sqliteJdbc)
    shadow(project(":core"))
    shadow(project(":api"))
    shadow(project(":util"))
    shadow(project(":media:runtime"))
    shadow(project(":media:source"))
    shadow(project(":media:player"))
    shadow(project(":platform:client:common"))
    shadow(libs.kotlinxSerializationProtobuf)
    shadow(libs.kotlinxSerializationJson)
    shadow(libs.kotlinStdlib)
    shadow(libs.tomlj)
    shadow(libs.semver4j)
    shadow(libs.caffeine)
    shadow(libs.okhttp)
    shadow(libs.okio)
    shadow(libs.sqliteJdbc)
    shadow(libs.exposedCore)
    shadow(libs.exposedJdbc)
    shadow(libs.exposedMigrationJdbc)
    shadow(libs.hikari)
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
            // Dev runs get native-library debug logging by default
            environment("DD_NATIVE_LOG", System.getenv("DD_NATIVE_LOG") ?: "debug")
        }
        register("neoServer") {
            server()
            environment("DD_NATIVE_LOG", System.getenv("DD_NATIVE_LOG") ?: "debug")
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
}

java {
    withSourcesJar()
}

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    archiveBaseName.set("dreamdisplays-neoforge")
    archiveVersion.set("$activeStonecutterVersion-${rootProject.version}")
    dependencies {
        include(project(":platform:client:common"))
        include(project(":core"))
        include(project(":api"))
        include(project(":util"))
        include(project(":media:runtime"))
        include(project(":media:source"))
        include(project(":media:player"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-serialization-core"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-serialization-protobuf"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-serialization-protobuf-jvm"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-serialization-json"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm"))
        include(dependency("org.apache.commons:commons-compress"))
        include(dependency("org.tukaani:xz"))
        include(dependency("org.semver4j:semver4j"))
        include(dependency("com.github.ben-manes.caffeine:caffeine"))
        include(dependency("com.squareup.okhttp3:okhttp"))
        include(dependency("com.squareup.okhttp3:okhttp-jvm"))
        include(dependency("com.squareup.okio:okio"))
        include(dependency("com.squareup.okio:okio-jvm"))
        include(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
        include(dependency("org.jetbrains:annotations"))
        include(dependency("com.github.TeamNewPipe:NewPipeExtractor"))
        include(dependency("com.github.TeamNewPipe:nanojson"))
        include(dependency("org.jsoup:jsoup"))
        include(dependency("com.google.protobuf:protobuf-javalite"))
        include(dependency("org.mozilla:rhino"))
        include(dependency("org.mozilla:rhino-engine"))
        include(dependency("org.tomlj:tomlj"))
        include(dependency("org.antlr:antlr4-runtime"))
        include(dependency("org.xerial:sqlite-jdbc"))
        include(dependency("org.jetbrains.exposed:exposed-core"))
        include(dependency("org.jetbrains.exposed:exposed-jdbc"))
        include(dependency("org.jetbrains.exposed:exposed-migration-core"))
        include(dependency("org.jetbrains.exposed:exposed-migration-jdbc"))
        include(dependency("com.zaxxer:HikariCP"))
    }
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "org.apache.commons.compress",
        "org.tukaani.xz",
        "org.semver4j",
        "com.github.benmanes.caffeine",
        "okhttp3",
        "okio",
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
        "org.tomlj",
        "org.antlr",
        "org.jetbrains.exposed",
        "com.zaxxer.hikari",
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
    exclude("org/sqlite/native/Linux-Android/**")
    exclude("org/sqlite/native/Linux-Musl/x86/**")
    exclude("org/sqlite/native/FreeBSD/**")
    exclude("org/sqlite/native/Linux/ppc64/**")
    exclude("org/sqlite/native/Linux/riscv64/**")
    exclude("org/sqlite/native/Linux/arm/**")
    exclude("org/sqlite/native/Linux/armv6/**")
    exclude("org/sqlite/native/Linux/armv7/**")
    exclude("org/sqlite/native/Linux/x86/**")
    exclude("org/sqlite/native/Windows/x86/**")
    exclude("org/sqlite/native/Windows/armv7/**")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set("$activeStonecutterVersion-${rootProject.version}")
}
