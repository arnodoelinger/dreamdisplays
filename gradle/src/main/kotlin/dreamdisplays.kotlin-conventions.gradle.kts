import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*

/** Stonecutter conventions for Kotlin projects. */
plugins {
    java
    id("org.jetbrains.kotlin.jvm")
}

/** Active Minecraft version. */
private val activeVersion = rootProject.file("versions/active.txt").readText().trim()

/** Properties for the active Minecraft version. */
private val versionProps = Properties().apply {
    rootProject.file("versions/$activeVersion/gradle.properties").inputStream().use { load(it) }
}

/** Gets a Stonecutter version property for the active Minecraft version. */
private fun scVersion(name: String): String = versionProps.getProperty(name)
    ?: error("Missing Stonecutter version property '$name' for $activeVersion.")

/** The Java version to target for the active Minecraft version. */
private val javaVersion = scVersion("java.version").toInt()

/** The lowest Java version to target for all Minecraft versions. */
private val javaFloor: Int = rootProject.file("versions").listFiles()
    ?.filter { it.isDirectory }
    ?.mapNotNull { dir ->
        dir.resolve("gradle.properties").takeIf { it.isFile }?.let { file ->
            Properties().apply { file.inputStream().use { load(it) } }.getProperty("java.version")?.toInt()
        }
    }
    ?.minOrNull() ?: javaVersion

/** The bytecode target for the active Minecraft version. */
private val platformIndependentModules = setOf(":api", ":core", ":util", ":media:audio")

/** The bytecode target for all Minecraft versions. */
private val bytecodeTarget: Int = if (project.path in platformIndependentModules) javaFloor else javaVersion

/**
 * Some legacy Minecraft targets (e.g., 1.21.1) ship minecraft-dependencies with strictly pins on
 * `Apache Commons` that conflict with the project's newer versions. When a target declares the pinned
 * commons-compress version, force the whole `Apache Commons` set to the Minecraft-bundled versions so
 * resolution succeeds with a single version instead of failing on strictly vs. newer.
 */
versionProps.getProperty("commons.compress.version")?.let { commonsCompressVersion ->
    configurations.all {
        resolutionStrategy.force(
            "org.apache.commons:commons-compress:$commonsCompressVersion",
            "commons-codec:commons-codec:1.16.0",
            "commons-io:commons-io:2.15.1",
            "org.apache.commons:commons-lang3:3.14.0",
        )
    }
}

/** Java and Kotlin conventions. */
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(javaVersion)) }
}

/** Kotlin conventions. */
extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(javaVersion)
}

/** Project conventions. */
tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
    options.release.set(bytecodeTarget)
}

/** Unstable API opt-in. */
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(bytecodeTarget.toString()))
    compilerOptions.optIn.add("com.dreamdisplays.api.DreamDisplaysUnstableApi")
}

/**
 * The `LICENSE` file is not included in the source JAR by default, so add it to all JARs.
 * This is required for compliance with the Apache 2.0 license.
 */
tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE"))
}

/**
 * Stonecutter only versions the root project (dependency selection); the shared Kotlin code lives
 * in subprojects, so Stonecutter never processes the `//? if >=26.2 { ... //?} else /*...*/`
 * directives in their source. This transform resolves those directives for the active Minecraft
 * version into a generated source directory that the Kotlin source set compiles instead of the
 * checked-in source.
 */
run {
    val minecraftVersion = scVersion("minecraft.version")

    val sourceDir = layout.projectDirectory.dir("src/main/kotlin").asFile
    val chiselDir = layout.buildDirectory.dir("generated/chisel/main/kotlin")

    val chiselSource = tasks.register("chiselSource") {
        val outDir = chiselDir.get().asFile
        if (sourceDir.exists()) {
            inputs.dir(sourceDir).withPathSensitivity(PathSensitivity.RELATIVE)
        }
        inputs.property("minecraftVersion", minecraftVersion)
        outputs.dir(chiselDir)
        doLast {
            outDir.deleteRecursively()
            outDir.mkdirs()
            if (!sourceDir.exists()) return@doLast
            sourceDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val target = outDir.resolve(file.relativeTo(sourceDir).path)
                target.parentFile.mkdirs()
                target.writeText(chiselSource(file.readLines(), minecraftVersion))
            }
        }
    }

    extensions.configure<KotlinJvmProjectExtension> {
        sourceSets.named("main") {
            kotlin.setSrcDirs(listOf(chiselDir))
        }
    }

    tasks.withType<KotlinCompile>().configureEach { dependsOn(chiselSource) }
    tasks.matching { it.name == "sourcesJar" }.configureEach { dependsOn(chiselSource) }
}
