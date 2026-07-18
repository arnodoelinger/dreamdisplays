import java.util.*

plugins {
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.serialization-conventions")
    id("dreamdisplays.shadow-conventions")
    alias(libs.plugins.paperweight)
    alias(libs.plugins.platformweaver)
}

val scVersions = gradle.extensions.getByType<StonecutterVersions>()
val activeStonecutterVersion = scVersions.active
fun scVersion(name: String): String = scVersions.get(name)

val isLegacyObfuscatedMinecraft = scVersion("minecraft.version").startsWith("1.")

fun fancyModLoaderVersion(neoForgeVersion: String): String = when (neoForgeVersion) {
    "21.1.233" -> "4.0.42"
    "21.11.42" -> "10.0.36"
    else -> "11.0.13"
}

if (isLegacyObfuscatedMinecraft) {
    evaluationDependsOn(":platform:client:fabric")
}

// The Paper jar is one cross-version artifact (dispatches 1.21.1 through 26.x at runtime via
// ServerVersion), so it must always be compiled against this pinned Minecraft version: the oldest
// one on the Java 21 toolchain, matching the paper_build_version calc in .github/workflows/_build.yml.
// Building it on a newer, Java 25-only version (e.g. 26.2) would bake Java 25 bytecode into the one
// jar every supported server loads, breaking every Java 21 server (Paper 1.21.1 / 1.21.11).
val paperPinVersion = "1.21.11"
run {
    val pinnedJavaVersion = Properties().apply {
        rootProject.file("versions/$paperPinVersion/gradle.properties").inputStream().use { input -> load(input) }
    }.getProperty("java.version")
    check(pinnedJavaVersion == "21") {
        "versions/$paperPinVersion/gradle.properties has java.version=$pinnedJavaVersion, expected 21. " +
            "The paperPinVersion in platform/server/build.gradle.kts must point at a Java 21 version."
    }
}

tasks.named("compileKotlin") {
    // Captured as plain values here, not referenced live inside doFirst below: referencing a value
    // declared in this script from within a task action captures the whole script object, which
    // the configuration cache can't serialize.
    val active = activeStonecutterVersion
    val pin = paperPinVersion
    doFirst {
        require(active == pin) {
            "The Paper jar must be compiled with the active Stonecutter version pinned to $pin " +
                "(active is $active). Run the root ':platform:server:buildPaper' task instead " +
                "of building this module's tasks directly, or switch with " +
                "./gradlew \"Set active project to $pin\" first."
        }
    }
}

if (activeStonecutterVersion == paperPinVersion) {
    tasks.build {
        dependsOn(tasks.shadowJar)
    }
} else {
    val buildPaper = tasks.register("buildPaper") {
        group = "build"
        description = "Builds the cross-version Paper jar, pinning the active Stonecutter version to " +
            "$paperPinVersion (currently $activeStonecutterVersion) for a nested Gradle invocation."
        val activeVersionFile = rootProject.file("versions/active.txt")
        val gradlewPath = rootProject.file("gradlew").absolutePath
        val rootDir = rootProject.projectDir
        val pinVersion = paperPinVersion
        doLast {
            val previousVersion = activeVersionFile.readText()
            activeVersionFile.writeText(pinVersion)
            try {
                val exitCode = ProcessBuilder(gradlewPath, ":platform:server:shadowJar")
                    .directory(rootDir)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                    .waitFor()
                check(exitCode == 0) { "Nested Gradle build for the pinned Paper jar failed with exit code $exitCode." }
            } finally {
                activeVersionFile.writeText(previousVersion)
            }
        }
    }
    tasks.named("build") {
        setDependsOn(listOf(buildPaper))
    }
}

repositories {
    if (isLegacyObfuscatedMinecraft) {
        maven(rootProject.layout.projectDirectory.dir(".gradle/loom-cache/remapped_mods"))
    }
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io")
    maven("https://maven.neoforged.net/releases")
}

platformweaver {
    target = "paper"
    chameleonsDir = null
}

dependencies {
    compileOnly(libs.platformweaverAnnotations)
}

dependencies {
    paperweight.devBundle("io.papermc.paper", scVersion("paper.api.version"))
    compileOnly(libs.jspecify)
    compileOnly(libs.luckpermsApi)
    compileOnly(project(":core"))
    compileOnly(project(":platform:client:common"))
    compileOnly("net.fabricmc:fabric-loader:${scVersion("fabric.loader.version")}")
    compileOnly("net.neoforged:neoforge:${scVersion("neoforge.version")}:universal")
    compileOnly("net.neoforged:bus:8.0.5")
    compileOnly("net.neoforged.fancymodloader:loader:${fancyModLoaderVersion(scVersion("neoforge.version"))}")
    if (isLegacyObfuscatedMinecraft) {
        compileOnly(project(path = ":platform:client:fabric", configuration = "mappedFabricApiElements"))
    } else {
        compileOnly("net.fabricmc.fabric-api:fabric-api:${scVersion("fabric.api.version")}")
    }

    implementation(project(":core"))
    implementation(project(":util"))
    implementation(libs.kotlinxSerializationProtobuf)
    implementation(libs.kotlinxSerializationJson)
    implementation(libs.kotlinxCoroutinesCore)
    implementation(libs.kotlinxIoCore)
    implementation(libs.semver4j)
    implementation(libs.tomlj)
    implementation(libs.exposedCore)
    implementation(libs.exposedJdbc)
    implementation(libs.exposedMigrationJdbc)
    implementation(libs.hikari)
    implementation(libs.sqliteJdbc)
    implementation(libs.kotlinStdlib)
    implementation(libs.bstats)
    implementation(libs.caffeine)
}

sourceSets.main {
    resources.srcDir(project(":platform:resources").file("src/main/resources"))
    resources.exclude("assets/dreamdisplays/lang/client/**")
}

tasks.processResources {
    from(project(":platform:resources").file("src/main/resources/assets/dreamdisplays/lang/server/config.toml"))
    val projectVersion = version.toString()
    val props = mapOf(
        "version" to projectVersion,
        "paperMinecraftApi" to scVersion("paper.minecraft.api"),
    )
    inputs.properties(props)
    filteringCharset = Charsets.UTF_8.name()
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("dreamdisplays-paper")
    archiveVersion.set(rootProject.version.toString())
    manifest {
        attributes(
            "paperweight-mappings-namespace" to "mojang",
        )
    }
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:kotlin-reflect"))
        exclude(dependency("org.checkerframework:checker-qual"))
    }
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "org.bstats",
        "org.tomlj",
        "org.semver4j",
        "com.github.benmanes.caffeine",
        "okhttp3",
        "okio",
        "org.jetbrains.exposed",
        "kotlinx.serialization",
        "kotlinx.io",
        "com.zaxxer.hikari",
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
    excludeDreamDisplaysSqliteNativeExtras()
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set(rootProject.version.toString())
}
