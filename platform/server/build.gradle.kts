import java.util.*

plugins {
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.serialization-conventions")
    id("dreamdisplays.shadow-conventions")
    id("io.papermc.paperweight.userdev") version libs.versions.paperweight
    id("io.github.arnodoelinger.platformweaver") version libs.versions.platformweaver
}

val activeStonecutterVersion = rootProject.file("versions/active.txt").readText().trim()
val stonecutterVersions = Properties().apply {
    rootProject.file("versions/$activeStonecutterVersion/gradle.properties").inputStream().use { input -> load(input) }
}

fun scVersion(name: String): String = stonecutterVersions.getProperty(name)
    ?: error("Missing Stonecutter version property '$name' for $activeStonecutterVersion.")

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
    doFirst {
        require(activeStonecutterVersion == paperPinVersion) {
            "The Paper jar must be compiled with the active Stonecutter version pinned to $paperPinVersion " +
                "(active is $activeStonecutterVersion). Run the root ':platform:server:buildPaper' task instead " +
                "of building this module's tasks directly, or switch with " +
                "./gradlew \"Set active project to $paperPinVersion\" first."
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
        // The nested invocation below always compiles at paperPinVersion (a legacy/obfuscated target),
        // which shares :core, :util, :platform:client:common and :platform:client:fabric's chiseled
        // source + compiled-classes directories with whatever *this* outer invocation is doing at the
        // currently active version. Two Gradle processes writing Stonecutter-chiseled output for two
        // different Minecraft versions into the same directory at once corrupts it silently (observed:
        // client UI widgets resolving the wrong `//? if >=26` branch). Block until every task already
        // in this invocation's graph for those shared projects has finished before starting the nested
        // build, so the two never touch the same directory concurrently.
        mustRunAfter(
            rootProject.project(":core").tasks,
            rootProject.project(":util").tasks,
            rootProject.project(":platform:client:common").tasks,
            rootProject.project(":platform:client:fabric").tasks,
        )
        doLast {
            val activeFile = rootProject.file("versions/active.txt")
            val previousVersion = activeFile.readText()
            activeFile.writeText(paperPinVersion)
            try {
                val exitCode = ProcessBuilder(rootProject.file("gradlew").absolutePath, ":platform:server:shadowJar")
                    .directory(rootProject.projectDir)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                    .waitFor()
                check(exitCode == 0) { "Nested Gradle build for the pinned Paper jar failed with exit code $exitCode." }
            } finally {
                activeFile.writeText(previousVersion)
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
    maven("https://repo.lostyy.ru/releases")
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
    // Translations live once in :platform:resources and are pulled in here instead of being duplicated per platform.
    // Only the server-message strings are needed here; client (Minecraft GUI) translations don't apply to Paper.
    resources.srcDir(project(":platform:resources").file("src/main/resources"))
    resources.exclude("assets/dreamdisplays/lang/client/**")
}

tasks.processResources {
    // Paper's plugin.getResource("config.toml") looks up the resource root, unlike Fabric / NeoForge's
    // classloader lookup under assets/dreamdisplays/lang/server/; both read the same shared template.
    from(project(":platform:resources").file("src/main/resources/assets/dreamdisplays/lang/server/config.toml"))
    val projectVersion = version.toString()
    val activeStonecutterVersion = rootProject.file("versions/active.txt").readText().trim()
    val stonecutterVersions = Properties().apply {
        rootProject.file("versions/$activeStonecutterVersion/gradle.properties").inputStream()
            .use { input -> load(input) }
    }
    val props = mapOf(
        "version" to projectVersion,
        "paperMinecraftApi" to stonecutterVersions.getProperty("paper.minecraft.api"),
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
        "com.zaxxer.hikari",
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
    exclude("org/sqlite/native/Linux-Android/**")
    exclude("org/sqlite/native/Linux-Musl/x86/**")
    exclude("org/sqlite/native/Linux/ppc64/**")
    exclude("org/sqlite/native/Linux/riscv64/**")
    exclude("org/sqlite/native/Linux/arm/**")
    exclude("org/sqlite/native/Linux/armv6/**")
    exclude("org/sqlite/native/Linux/armv7/**")
    exclude("org/sqlite/native/Linux/x86/**")
    exclude("org/sqlite/native/Windows/x86/**")
    exclude("org/sqlite/native/Windows/armv7/**")
    exclude("org/sqlite/native/Windows/aarch64/**")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set(rootProject.version.toString())
}
