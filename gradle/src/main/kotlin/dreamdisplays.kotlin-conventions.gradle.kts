import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.*

/** Stonecutter conventions for Kotlin projects. */
plugins {
    java
    id("org.jetbrains.kotlin.jvm")
}

/** Shared `Stonecutter` version reader, published once by the dreamdisplays.stonecutter-versions settings plugin. */
private val scVersions = gradle.extensions.getByType<StonecutterVersions>()

/** Active Minecraft version. */
private val activeVersion = scVersions.active

/** Gets a `Stonecutter` version property for the active Minecraft version. */
private fun scVersion(name: String): String = scVersions.get(name)

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
 * Version-sensitive modules (everything outside [platformIndependentModules]: `:platform:server`
 * and its client loaders) get their own build directory per Minecraft version. Without this,
 * switching the active version without a `clean` left Kotlin's incremental-compilation history and
 * the generated chisel output pointed at another version's stale intermediate state, producing
 * mixed-platform "unresolved reference" compile errors — isolating the build directory per version
 * means there's no shared state left to go stale. Version-independent modules keep one shared build
 * directory, since their output never varies by Minecraft version.
 */
if (project.path !in platformIndependentModules) {
    layout.buildDirectory.set(layout.projectDirectory.dir("build/$activeVersion"))
}

/**
 * Some legacy Minecraft targets (e.g., 1.21.1) ship minecraft-dependencies with strictly pins on
 * `Apache Commons` that conflict with the project's newer versions. When a target declares the pinned
 * commons-compress version, force the whole `Apache Commons` set to the Minecraft-bundled versions so
 * resolution succeeds with a single version instead of failing on strictly vs. newer.
 */
scVersions.getOrNull("commons.compress.version")?.let { commonsCompressVersion ->
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

/** Every module compiles against the Kotlin stdlib and tests on JUnit 5 with kotlin-test. */
dependencies {
    "compileOnly"(kotlin("stdlib"))
    "testImplementation"(kotlin("stdlib"))
    "testImplementation"(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
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
 * `Stonecutter` only versions the root project (dependency selection); the shared Kotlin code lives
 * in subprojects, so Stonecutter never processes the `//? if >=26.2 { ... //?} else /*...*/`
 * directives in their source. This transform resolves those directives for the active Minecraft
 * version into a generated source directory that `compileKotlin` compiles instead of the checked-in
 * source.
 *
 * Deliberately does *not* repoint the "main" Kotlin source set at the generated directory (an
 * earlier version of this did, via `sourceSets.main.kotlin.setSrcDirs(...)`): IDEs discover a
 * module's Kotlin source roots from that same source-set model, so doing that told every IDE that
 * `src/main/kotlin` isn't source at all — only the generated copy under `build/` is. Overriding
 * just the compile task's own input keeps `src/main/kotlin` as the real, indexable, editable source
 * root, while still compiling the version-resolved copy.
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

    // Only the "main" compilation reads the chiseled copy; compileTestKotlin keeps compiling the
    // real src/test/kotlin untouched (tasks.withType<KotlinCompile>() would also catch it here).
    //
    // Deferred to afterEvaluate so this runs once the "main" Kotlin source set is fully settled,
    // including anything a consuming project added on top of the default (e.g. platform:client:fabric
    // adds platform:server's own already-chiseled directory here too). Only this project's own raw
    // sourceDir is swapped for its chiselDir; any other directory in the source set — cross-project
    // chisel output — is passed through untouched instead of being silently dropped.
    afterEvaluate {
        val kotlinExtension = extensions.getByType<KotlinJvmProjectExtension>()
        val mainSourceDirs = kotlinExtension.sourceSets.getByName("main").kotlin.srcDirs
        val otherDirs = mainSourceDirs.filterNot { it.absolutePath == sourceDir.absolutePath }
        tasks.named<KotlinCompile>("compileKotlin") {
            dependsOn(chiselSource)
            setSource(listOf(chiselDir) + otherDirs)
        }
    }
}
