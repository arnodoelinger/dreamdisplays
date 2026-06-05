plugins {
    idea
    id("net.fabricmc.fabric-loom") version libs.versions.loom
    id("maven-publish")
    id("com.gradleup.shadow") version libs.versions.shadow
    kotlin("jvm") version libs.versions.kotlin
}

buildscript {
    repositories {
        maven("https://jitpack.io")
    }
    dependencies {
        classpath(libs.ofratPlugin)
    }
}

apply(plugin = "io.github.arsmotorin.ofrat")

configure<io.github.arsmotorin.ofrat.gradle.OfratExtension> {
    target = "fabric"
    chameleonsDir = "../server/src/main/chameleons"
}

kotlin { jvmToolchain(providers.gradleProperty("java.version").get().toInt()) }

sourceSets.main {
    kotlin.srcDir("../server/src/main/kotlin")
}

loom {
    accessWidenerPath.set(project(":common").file("src/main/resources/dreamdisplays.classtweaker"))
}

dependencies {
    compileOnly(libs.ofratAnnotations)
    "kotlinCompilerPluginClasspath"(libs.ofratPlugin)
    compileOnly(libs.paperApi)
    compileOnly(libs.bstats)
    compileOnly(libs.utils)
    compileOnly(libs.tomlj)
    compileOnly(libs.semver4j)
    compileOnly(libs.exposedCore)
    compileOnly(libs.exposedJdbc)
    compileOnly(libs.hikari)

    minecraft(libs.fabricMinecraft)
    implementation(libs.fabricLoader)
    implementation(libs.fabricApi)
    shadow(project(":common"))
    shadow(libs.kotlinStdlib)
    shadow(libs.tomlj)
    shadow(libs.semver4j)
    shadow(libs.sqliteJdbc)
    shadow(libs.exposedCore)
    shadow(libs.exposedJdbc)
    shadow(libs.hikari)
}

tasks.processResources {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to projectVersion))
    }
    filesMatching("quilt.mod.json") {
        expand(mapOf("version" to projectVersion))
    }
    filesMatching("assets/dreamdisplays/version.txt") {
        expand(mapOf("version" to projectVersion))
    }
}

java {
    withSourcesJar()
    toolchain { languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("java.version").get().toInt())) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
}

tasks.jar {
    from(rootProject.file("LICENSE"))
}

// Hack: it's a bug in Loom alpha where the validation task expects a named namespace but the classtweaker correctly uses
// official namespaces, so we have to disable the validation until it's fixed.
// TODO: when a stable Loom for 26.1.2/26.2 is released, this should be removed
tasks.named("validateAccessWidener") { enabled = false }

idea {
    module {
        excludeDirs.add(rootProject.file("server/src/main/chameleons"))
        sourceDirs.add(file("src/ide-stubs"))
    }
}

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    archiveBaseName = "dreamdisplays-fabric"
    archiveClassifier = ""
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
    dependencies {
        include(project(":common"))
        include(dependency("me.inotsleep:utils"))
        include(dependency("org.xerial:sqlite-jdbc"))
        include(dependency("org.apache.commons:commons-compress"))
        include(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
        include(dependency("org.jetbrains:annotations"))
        include(dependency("org.tomlj:tomlj"))
        include(dependency("org.antlr:antlr4-runtime"))
        include(dependency("org.semver4j:semver4j"))
        include(dependency("org.jetbrains.exposed:exposed-core"))
        include(dependency("org.jetbrains.exposed:exposed-jdbc"))
        include(dependency("com.zaxxer:HikariCP"))
    }
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "me.inotsleep.utils",
        "org.apache.commons.compress",
        "org.tukaani.xz",
        "kotlin",
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
    mergeServiceFiles()
    exclude("META-INF/versions/9/module-info.class")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")
    exclude("META-INF/*.kotlin_module")
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
