plugins {
    id("net.fabricmc.fabric-loom") version libs.versions.loom
    id("maven-publish")
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.shadow-conventions")
}

sourceSets.main {
    kotlin.srcDir(project(":server").file("src/main/kotlin"))
}

loom {
    accessWidenerPath.set(project(":common").file("src/main/resources/dreamdisplays.classtweaker"))
}

dependencies {
    compileOnly(libs.ofratAnnotations)
    "kotlinCompilerPluginClasspath"(libs.ofratPlugin)
    compileOnly(libs.paperApi)
    implementation(libs.bstats)
    implementation(libs.utils)
    implementation(libs.tomlj)
    implementation(libs.semver4j)
    implementation(libs.exposedCore)
    implementation(libs.exposedJdbc)
    implementation(libs.hikari)
    runtimeOnly(libs.sqliteJdbc)

    minecraft(libs.fabricMinecraft)
    implementation(libs.fabricLoader)
    implementation(libs.fabricApi)
    implementation(project(":common"))
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
    from(project(":common").file("src/main/resources/dreamdisplays.classtweaker"))
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
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-P", "plugin:io.github.arsmotorin.ofrat:platform=fabric"
    )
}

// Hack: it's a bug in Loom alpha where the validation task expects a named namespace but the classtweaker correctly uses
// official namespaces, so we have to disable the validation until it's fixed.
// TODO: when a stable Loom for 26.1.2/26.2 is released, this should be removed
tasks.named("validateAccessWidener") { enabled = false }

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    archiveBaseName = "dreamdisplays-fabric"
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
