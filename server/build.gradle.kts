import java.util.Properties

plugins {
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.shadow-conventions")
    id("io.papermc.paperweight.userdev") version libs.versions.paperweight
}

repositories {
    mavenCentral()
    maven("https://repo.lostyy.ru/releases")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly(libs.ofratAnnotations)
    "kotlinCompilerPluginClasspath"(libs.ofratPlugin)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-P", "plugin:io.github.arsmotorin.ofrat:platform=paper"
    )
}

dependencies {
    val activeStonecutterVersion = rootProject.file("versions/active.txt").readText().trim()
    val stonecutterVersions = Properties().apply {
        rootProject.file("versions/$activeStonecutterVersion/gradle.properties").inputStream().use { input -> load(input) }
    }
    fun scVersion(name: String): String = stonecutterVersions.getProperty(name)
        ?: error("Missing Stonecutter version property '$name' for $activeStonecutterVersion.")

    paperweight.devBundle("io.papermc.paper", scVersion("paper.api.version"))
    compileOnly(libs.jspecify)
    compileOnly(project(":common"))
    compileOnly("net.fabricmc:fabric-loader:${scVersion("fabric.loader.version")}")
    compileOnly("net.fabricmc.fabric-api:fabric-api:${scVersion("fabric.api.version")}")

    implementation(libs.semver4j)
    implementation(libs.tomlj)
    implementation(libs.exposedCore)
    implementation(libs.exposedJdbc)
    implementation(libs.exposedMigrationJdbc)
    implementation(libs.hikari)
    implementation(libs.sqliteJdbc)
    implementation(libs.kotlinStdlib)
    implementation(libs.bstats)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(providers.gradleProperty("java.version").get().toInt())
}

tasks.processResources {
    val projectVersion = version.toString()
    val activeStonecutterVersion = rootProject.file("versions/active.txt").readText().trim()
    val stonecutterVersions = Properties().apply {
        rootProject.file("versions/$activeStonecutterVersion/gradle.properties").inputStream().use { input -> load(input) }
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

tasks.build {
    dependsOn(tasks.shadowJar)
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
        "org.jetbrains.exposed",
        "com.zaxxer.hikari",
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
    exclude("org/sqlite/native/Linux-Android/**")
    exclude("org/sqlite/native/Linux-Musl/x86/**")
    // exclude("org/sqlite/native/FreeBSD/**")
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
