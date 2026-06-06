import java.util.Properties

plugins {
    // Both loom ids are declared (without applying) so the type-safe `loom { }` / `loom.layered`
    // accessors are generated; the correct one is applied imperatively below based on the target.
    id("net.fabricmc.fabric-loom") apply false
    id("net.fabricmc.fabric-loom-remap") apply false
    id("maven-publish")
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.shadow-conventions")
}

// Loom plugin id depends on whether the target Minecraft is obfuscated.
// Legacy releases (1.21.11 and older) ship obfuscated -> fabric-loom-remap.
// Year-versioned releases (26.x) ship deobfuscated -> fabric-loom.
// The plugin version is supplied per Stonecutter version via settings.gradle.kts resolutionStrategy.
run {
    val active = rootProject.file("versions/active.txt").readText().trim()
    val props = Properties().apply {
        rootProject.file("versions/$active/gradle.properties").inputStream().use { load(it) }
    }
    val mcVersion = props.getProperty("minecraft.version")
        ?: error("Missing 'minecraft.version' for $active.")
    val isLegacyObfuscated = mcVersion.startsWith("1.")
    if (isLegacyObfuscated) apply(plugin = "net.fabricmc.fabric-loom-remap")
    else apply(plugin = "net.fabricmc.fabric-loom")
}

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

val activeStonecutterVersion = rootProject.file("versions/active.txt").readText().trim()
val stonecutterVersions = Properties().apply {
    rootProject.file("versions/$activeStonecutterVersion/gradle.properties").inputStream().use { input -> load(input) }
}
fun scVersion(name: String): String = stonecutterVersions.getProperty(name)
    ?: error("Missing Stonecutter version property '$name' for $activeStonecutterVersion.")

// Legacy (obfuscated) Minecraft targets need layered Mojang+Parchment mappings and modImplementation;
// year-versioned (deobfuscated) targets resolve the source set directly with plain implementation.
val isLegacyObfuscated = scVersion("minecraft.version").startsWith("1.")

sourceSets.main {
    kotlin.srcDir(project(":server").file("src/main/kotlin"))
}

// Use the extension type directly (not the generated `loom { }` accessor) because the loom plugin
// is applied imperatively above, so the type-safe accessor may not be generated.
val loomExt = the<net.fabricmc.loom.api.LoomGradleExtensionAPI>()
loomExt.accessWidenerPath.set(project(":common").file("src/main/resources/dreamdisplays.classtweaker"))

dependencies {
    compileOnly(libs.ofratAnnotations)
    "kotlinCompilerPluginClasspath"(libs.ofratPlugin)
    compileOnly("io.papermc.paper:paper-api:${scVersion("paper.api.version")}")
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
            parchment("io.papermc.parchment.data:parchment:${scVersion("minecraft.version")}+build.3")
        })
        "modImplementation"("net.fabricmc:fabric-loader:${scVersion("fabric.loader.version")}")
        "modImplementation"("net.fabricmc.fabric-api:fabric-api:${scVersion("fabric.api.version")}")
    } else {
        implementation("net.fabricmc:fabric-loader:${scVersion("fabric.loader.version")}")
        implementation("net.fabricmc.fabric-api:fabric-api:${scVersion("fabric.api.version")}")
    }
    implementation(project(":common"))
    shadow(project(":common"))
    shadow(libs.kotlinStdlib)
    shadow(libs.tomlj)
    shadow(libs.semver4j)
    shadow(libs.sqliteJdbc)
    shadow(libs.exposedCore)
    shadow(libs.exposedJdbc)
    shadow(libs.exposedMigrationJdbc)
    shadow(libs.hikari)
}

tasks.processResources {
    from(project(":common").file("src/main/resources/dreamdisplays.classtweaker"))
    val projectVersion = project.version.toString()
    val fabricMcVer = scVersion("fabric.minecraft.dependency")
    inputs.property("version", projectVersion)
    inputs.property("minecraftVersion", fabricMcVer)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to projectVersion, "minecraftVersion" to fabricMcVer))
    }
    filesMatching("quilt.mod.json") {
        expand(mapOf("version" to projectVersion, "minecraftVersion" to fabricMcVer))
    }
    filesMatching("assets/dreamdisplays/version.txt") {
        expand(mapOf("version" to projectVersion))
    }
}

java {
    withSourcesJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-P", "plugin:io.github.arsmotorin.ofrat:platform=fabric"
    )
}

// Hack: it's a bug in Loom alpha where the validation task expects a named namespace but the classtweaker correctly uses
// official namespaces, so we have to disable the validation until it's fixed.
// TODO: when a stable Loom for 26.1.2/26.2 is released, this should be removed
tasks.findByName("validateAccessWidener")?.enabled = false

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    archiveBaseName.set("dreamdisplays-fabric")
    archiveVersion.set("$activeStonecutterVersion-${rootProject.version}")
    dependencies {
        include(project(":common"))
        include(dependency("org.xerial:sqlite-jdbc"))
        include(dependency("org.apache.commons:commons-compress"))
        include(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
        include(dependency("org.jetbrains:annotations"))
        include(dependency("org.tomlj:tomlj"))
        include(dependency("org.antlr:antlr4-runtime"))
        include(dependency("org.semver4j:semver4j"))
        include(dependency("org.jetbrains.exposed:exposed-core"))
        include(dependency("org.jetbrains.exposed:exposed-jdbc"))
        include(dependency("org.jetbrains.exposed:exposed-migration-core"))
        include(dependency("org.jetbrains.exposed:exposed-migration-jdbc"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm"))
        include(dependency("org.jetbrains.kotlinx:kotlinx-datetime-jvm"))
        include(dependency("com.zaxxer:HikariCP"))
    }
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "org.apache.commons.compress",
        "org.tukaani.xz",
        "kotlin",
        "kotlinx",
        "org.jetbrains.annotations",
        "org.intellij.lang.annotations",
        "org.tomlj",
        "org.antlr",
        "org.semver4j",
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
