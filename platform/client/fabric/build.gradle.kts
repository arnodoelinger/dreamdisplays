plugins {
    id("net.fabricmc.fabric-loom") apply false
    id("net.fabricmc.fabric-loom-remap") apply false
    id("maven-publish")
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.native-resources")
    id("dreamdisplays.shadow-conventions")
    alias(libs.plugins.platformweaver)
}

// Loom plugin id depends on whether the target Minecraft is obfuscated.
// Legacy releases (1.21.11 and older) ship obfuscated -> fabric-loom-remap.
// Year-versioned releases (26.x) ship deobfuscated -> fabric-loom.
// The plugin version is supplied per Stonecutter version via settings.gradle.kts resolutionStrategy.
run {
    val mcVersion = gradle.extensions.getByType<StonecutterVersions>().get("minecraft.version")
    val isLegacyObfuscated = mcVersion.startsWith("1.")
    if (isLegacyObfuscated) apply(plugin = "net.fabricmc.fabric-loom-remap")
    else apply(plugin = "net.fabricmc.fabric-loom")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.parchmentmc.org")
    maven("https://maven.quiltmc.org/repository/release/")
    maven("https://maven.quiltmc.org/repository/snapshot/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io")
    maven("https://maven.neoforged.net/releases")
}

val scVersions = gradle.extensions.getByType<StonecutterVersions>()
val activeStonecutterVersion = scVersions.active
fun scVersion(name: String): String = scVersions.get(name)

// Legacy (obfuscated) Minecraft targets need layered Mojang+Parchment mappings and modImplementation;
// year-versioned (deobfuscated) targets resolve the source set directly with plain implementation.
val isLegacyObfuscated = scVersion("minecraft.version").startsWith("1.")

fun fancyModLoaderVersion(neoForgeVersion: String): String = when (neoForgeVersion) {
    "21.1.233" -> "4.0.42"
    "21.11.42" -> "10.0.36"
    else -> "11.0.13"
}

sourceSets.main {
    // Consume :platform:server's chiseled output (version directives already resolved) rather than its raw
    // source, so the active Minecraft version's branch is compiled here too.
    kotlin.srcDir(project(":platform:server").layout.buildDirectory.dir("generated/chisel/main/kotlin"))
    // Translations live once in :platform:resources and are pulled in here instead of being duplicated per platform.
    // The lang/client/ split is source-tree organization only; vanilla's language system requires the actual
    // jar to have client lang files directly under assets/dreamdisplays/lang/, so processResources flattens it back.
    resources.srcDir(project(":platform:resources").file("src/main/resources"))
    resources.exclude("assets/dreamdisplays/lang/client/**")
}

platformweaver {
    target = "fabric"
    chameleonsDir = null
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(":platform:server:chiselSource")
}
tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(":platform:server:chiselSource")
}

val sourceClassTweaker = project(":platform:client:common").file("src/main/resources/dreamdisplays.classtweaker")
val classTweakerNamespace = if (isLegacyObfuscated) "named" else "official"
val generatedClassTweaker = layout.buildDirectory.file("generated/classtweaker/dreamdisplays.classtweaker").get().asFile
run {
    val rewritten = sourceClassTweaker.readText().lineSequence().joinToString("\n") { line ->
        if (line.startsWith("classTweaker v1 ")) "classTweaker v1 $classTweakerNamespace" else line
    }
    if (!generatedClassTweaker.exists() || generatedClassTweaker.readText() != rewritten) {
        generatedClassTweaker.parentFile.mkdirs()
        generatedClassTweaker.writeText(rewritten)
    }
}

val loomExt = the<net.fabricmc.loom.api.LoomGradleExtensionAPI>()
loomExt.accessWidenerPath.set(generatedClassTweaker)

// Dev runs get native-library debug logging by default
loomExt.runs.configureEach {
    environmentVariable("DD_NATIVE_LOG", System.getenv("DD_NATIVE_LOG") ?: "debug")
}

configurations.register("mappedFabricApiElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.getByName("modCompileClasspathMapped"))
}

dependencies {
    compileOnly(libs.platformweaverAnnotations)
    compileOnly(libs.luckpermsApi)
    compileOnly("io.papermc.paper:paper-api:${scVersion("paper.api.version")}")
    compileOnly("net.neoforged:neoforge:${scVersion("neoforge.version")}:universal")
    compileOnly("net.neoforged:bus:8.0.5")
    compileOnly("net.neoforged.fancymodloader:loader:${fancyModLoaderVersion(scVersion("neoforge.version"))}")
    implementation(libs.bstats)
    implementation(libs.tomlj)
    implementation(libs.semver4j)
    implementation(libs.exposedCore)
    implementation(libs.exposedJdbc)
    implementation(libs.exposedMigrationJdbc)
    implementation(libs.hikari)
    runtimeOnly(libs.sqliteJdbc)

    "minecraft"("com.mojang:minecraft:${scVersion("fabric.minecraft.version")}")
    if (isLegacyObfuscated) {
        "mappings"(loomExt.layered {
            officialMojangMappings()
            // Older legacy targets (1.21.1) predate the io.papermc.parchment.data coordinates and
            // ship under org.parchmentmc.data; allow the version to override the default artifact.
            parchment(
                scVersions.getOrNull("parchment.dependency")
                    ?: "io.papermc.parchment.data:parchment:${scVersion("minecraft.version")}+build.3"
            )
        })
        "modImplementation"("net.fabricmc:fabric-loader:${scVersion("fabric.loader.version")}")
        "modImplementation"("net.fabricmc.fabric-api:fabric-api:${scVersion("fabric.api.version")}")
    } else {
        implementation("net.fabricmc:fabric-loader:${scVersion("fabric.loader.version")}")
        implementation("net.fabricmc.fabric-api:fabric-api:${scVersion("fabric.api.version")}")
    }
    implementation(project(":platform:client:common"))
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

tasks.processResources {
    from(generatedClassTweaker)
    from(project(":platform:resources").file("src/main/resources/assets/dreamdisplays/lang/client")) {
        into("assets/dreamdisplays/lang")
    }
    val projectVersion = project.version.toString()
    val fabricMcVer = scVersion("fabric.minecraft.dependency")
    val javaVersion = scVersion("java.version")
    inputs.property("version", projectVersion)
    inputs.property("minecraftVersion", fabricMcVer)
    inputs.property("javaVersion", javaVersion)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to projectVersion, "minecraftVersion" to fabricMcVer, "javaVersion" to javaVersion))
    }
    filesMatching("quilt.mod.json") {
        expand(mapOf("version" to projectVersion, "minecraftVersion" to fabricMcVer, "javaVersion" to javaVersion))
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

// Hack: it's a bug in Loom alpha where the validation task expects a named namespace but the classtweaker correctly uses
// official namespaces, so we have to disable the validation until it's fixed.
// TODO: when a stable Loom for 26.1.2/26.2 is released, this should be removed
tasks.findByName("validateAccessWidener")?.enabled = false

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    archiveBaseName.set("dreamdisplays-fabric")
    archiveVersion.set("$activeStonecutterVersion-${rootProject.version}")
    if (isLegacyObfuscated) {
        archiveClassifier.set("dev-shadow")
        destinationDirectory.set(layout.buildDirectory.dir("devlibs"))
    }
    includeDreamDisplaysSharedContents()
    relocateDreamDisplaysSharedPackages()
    excludeDreamDisplaysSqliteNativeExtras()
}

// If it's a legacy version (like 1.21.11 where the shadow jar is obfuscated), we need to remap the shadow jar with
// loom's remapJar task to get proper mappings in the final artifact.
if (isLegacyObfuscated) {
    tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
        dependsOn(tasks.shadowJar)
        inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
        addNestedDependencies.set(false)
        archiveBaseName.set("dreamdisplays-fabric")
        archiveVersion.set("$activeStonecutterVersion-${rootProject.version}")
        archiveClassifier.set("")
        destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
    }
}

tasks.register("publishJar") {
    dependsOn(if (isLegacyObfuscated) "remapJar" else "shadowJar")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set("$activeStonecutterVersion-${rootProject.version}")
}
