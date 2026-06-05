plugins {
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.shadow-conventions")
    id("io.papermc.paperweight.userdev") version libs.versions.paperweight
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
    paperweight.devBundle("io.papermc.paper", libs.versions.paperApi.get())
    compileOnly(libs.jspecify)
    compileOnly(project(":common"))
    compileOnly(libs.fabricLoader)
    compileOnly(libs.fabricApi)

    implementation(libs.utils)
    implementation(libs.semver4j)
    implementation(libs.tomlj)
    implementation(libs.exposedCore)
    implementation(libs.exposedJdbc)
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
    val props = mapOf("version" to projectVersion)
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
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm"))
        exclude(dependency("org.checkerframework:checker-qual"))
    }
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "me.inotsleep.utils",
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
