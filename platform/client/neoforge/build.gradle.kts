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
    maven("https://maven.fabricmc.net/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven(rootProject.layout.projectDirectory.dir(".gradle/loom-cache/remapped_mods")) {
        name = "fabricLoomRemappedMods"
    }
    maven("https://thedarkcolour.github.io/KotlinForForge/")
}

sourceSets.main {
    kotlin.srcDir(project(":platform:server").layout.buildDirectory.dir("generated/chisel/main/kotlin"))
    // Translations live once in :platform:shared and are pulled in here instead of being duplicated per platform.
    // The lang/client/ split is source-tree organization only; vanilla's language system requires the actual
    // jar to have client lang files directly under assets/dreamdisplays/lang/, so processResources flattens it back.
    resources.srcDir(project(":platform:shared").file("src/main/resources"))
    resources.exclude("assets/dreamdisplays/lang/client/**")
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

val activeStonecutterVersion = rootProject.file("versions/active.txt").readText().trim()
val stonecutterVersions = Properties().apply {
    rootProject.file("versions/$activeStonecutterVersion/gradle.properties").inputStream().use { input -> load(input) }
}

fun scVersion(name: String): String = stonecutterVersions.getProperty(name)
    ?: error("Missing Stonecutter version property '$name' for $activeStonecutterVersion.")

fun mainSourceSetOf(path: String): SourceSet =
    project(path).extensions.getByType(SourceSetContainer::class.java).getByName("main")

fun kotlinForForgeVersion(): String = if (scVersion("minecraft.version") == "1.21.1") "5.12.0" else "6.3.0"

val vendoredLibraries: Configuration = configurations.create("vendoredLibraries") {
    isCanBeConsumed = false
    isCanBeResolved = true
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "com.google.code.findbugs", module = "jsr305")
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    exclude(group = "com.google.guava")
    exclude(group = "com.google.j2objc", module = "j2objc-annotations")
    exclude(group = "org.checkerframework", module = "checker-qual")
    exclude(group = "org.antlr", module = "antlr4-runtime")
    exclude(group = "org.slf4j")
}

val unpackVendoredLibraries = tasks.register<Sync>("unpackVendoredLibraries") {
    from(vendoredLibraries.elements.map { files -> files.map { zipTree(it) } })
    into(layout.buildDirectory.dir("vendoredLibraries"))
    exclude("module-info.class", "META-INF/versions/**")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

sourceSets.create("vendoredLibraries") {
    output.dir(unpackVendoredLibraries.map { it.destinationDir })
}

val isLegacyObfuscated = scVersion("minecraft.version").startsWith("1.")
if (isLegacyObfuscated) {
    evaluationDependsOn(":platform:client:fabric")
}

configurations.all {
    resolutionStrategy.force("org.slf4j:slf4j-api:2.0.9")
}

dependencies {
    compileOnly(libs.platformweaverAnnotations)
    compileOnly(libs.luckpermsApi)
    compileOnly(libs.bstats)
    compileOnly("io.papermc.paper:paper-api:${scVersion("paper.api.version")}")
    compileOnly("net.fabricmc:fabric-loader:${scVersion("fabric.loader.version")}")
    if (isLegacyObfuscated) {
        compileOnly(project(path = ":platform:client:fabric", configuration = "mappedFabricApiElements"))
    } else {
        compileOnly("net.fabricmc.fabric-api:fabric-api:${scVersion("fabric.api.version")}")
    }
    implementation(project(":platform:client:common"))
    implementation("thedarkcolour:kotlinforforge-neoforge:${kotlinForForgeVersion()}")
    implementation(libs.tomlj)
    implementation(libs.semver4j)
    implementation(libs.exposedCore)
    implementation(libs.exposedJdbc)
    implementation(libs.exposedMigrationJdbc)
    implementation(libs.hikari)
    runtimeOnly(libs.sqliteJdbc)
    vendoredLibraries(libs.tomlj)
    vendoredLibraries(libs.semver4j)
    vendoredLibraries(libs.caffeine)
    vendoredLibraries(libs.okhttp)
    vendoredLibraries(libs.okio)
    vendoredLibraries(libs.sqliteJdbc)
    vendoredLibraries(libs.exposedCore)
    vendoredLibraries(libs.exposedJdbc)
    vendoredLibraries(libs.exposedMigrationJdbc)
    vendoredLibraries(libs.hikari)
    vendoredLibraries(libs.newpipeExtractor)
    shadow(project(":core"))
    shadow(project(":api"))
    shadow(project(":util"))
    shadow(project(":media:runtime"))
    shadow(project(":media:source"))
    shadow(project(":media:player"))
    shadow(project(":media:audio"))
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

neoForge {
    enable {
        version = scVersion("neoforge.version")
    }
    accessTransformers.from(file("src/main/resources/META-INF/accesstransformer.cfg"))
    mods {
        register("dreamdisplays") {
            sourceSet(sourceSets.main.get())
            sourceSet(mainSourceSetOf(":platform:client:common"))
            sourceSet(mainSourceSetOf(":core"))
            sourceSet(mainSourceSetOf(":api"))
            sourceSet(mainSourceSetOf(":util"))
            sourceSet(mainSourceSetOf(":media:runtime"))
            sourceSet(mainSourceSetOf(":media:source"))
            sourceSet(mainSourceSetOf(":media:player"))
            sourceSet(mainSourceSetOf(":media:audio"))
            sourceSet(sourceSets["vendoredLibraries"])
        }
    }
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
    from(project(":platform:shared").file("src/main/resources/assets/dreamdisplays/lang/client")) {
        into("assets/dreamdisplays/lang")
    }
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
        include(project(":media:audio"))
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
