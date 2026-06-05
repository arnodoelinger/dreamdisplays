plugins {
    java
    idea
    id("com.gradleup.shadow") version libs.versions.shadow
    kotlin("jvm")
    id("io.papermc.paperweight.userdev") version libs.versions.paperweight
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
    target = "paper"
}

dependencies {
    compileOnly(libs.ofratAnnotations)
    "kotlinCompilerPluginClasspath"(libs.ofratPlugin)
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

val javaVersion = providers.gradleProperty("java.version").get().toInt()
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
    options.release.set(javaVersion)
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
    archiveClassifier.set("")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
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
    mergeServiceFiles()
    exclude("META-INF/versions/9/module-info.class")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")
    exclude("META-INF/*.kotlin_module")
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

idea {
    module {
        // Exclude chameleons: @Chameleon val declarations are not valid Kotlin that the IDE understands.
        // ide-stubs provides proper typealiases that mirror what OFRAT KCP generates at compile time.
        excludeDirs.add(file("src/main/chameleons"))
        sourceDirs.add(file("src/ide-stubs"))
    }
}
