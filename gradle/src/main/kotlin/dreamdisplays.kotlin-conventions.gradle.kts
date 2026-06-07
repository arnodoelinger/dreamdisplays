import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    id("org.jetbrains.kotlin.jvm")
}

private val activeVersion = rootProject.file("versions/active.txt").readText().trim()
private val versionProps = Properties().apply {
    rootProject.file("versions/$activeVersion/gradle.properties").inputStream().use { load(it) }
}
private fun scVersion(name: String): String = versionProps.getProperty(name)
    ?: error("Missing Stonecutter version property '$name' for $activeVersion.")
private val javaVersion = scVersion("java.version").toInt()

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(javaVersion)) }
}

extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(javaVersion)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
    options.release.set(javaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE"))
}

// Stonecutter only versions the root project (dependency selection); the shared Kotlin code lives
// in subprojects, so Stonecutter never processes the `//? if >=26 { ... //?} else /*...*/`
// directives in their source. This transform resolves those directives for the active Minecraft
// version into a generated source directory that the Kotlin source set compiles instead of the
// checked-in source. For 26.x the source is already valid, so the transform is a verbatim copy.
run {
    val minecraftVersion = scVersion("minecraft.version")
    val legacy = minecraftVersion.startsWith("1.")

    val sourceDir = layout.projectDirectory.dir("src/main/kotlin").asFile
    val chiselDir = layout.buildDirectory.dir("generated/chisel/main/kotlin")

    val chiselSource = tasks.register("chiselSource") {
        val outDir = chiselDir.get().asFile
        if (sourceDir.exists()) {
            inputs.dir(sourceDir).withPathSensitivity(PathSensitivity.RELATIVE)
        }
        inputs.property("legacy", legacy)
        outputs.dir(chiselDir)
        doLast {
            outDir.deleteRecursively()
            outDir.mkdirs()
            if (!sourceDir.exists()) return@doLast
            sourceDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val target = outDir.resolve(file.relativeTo(sourceDir).path)
                target.parentFile.mkdirs()
                target.writeText(if (legacy) chiselToLegacy(file.readLines()) else file.readText())
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
