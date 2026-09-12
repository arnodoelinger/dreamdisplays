import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import support.chisel.chiselSource
import support.stonecutter.StonecutterVersions
import support.stonecutter.VersionsJson

plugins {
    java
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.atomicfu")
}

private val scVersions = gradle.extensions.getByType<StonecutterVersions>()
private val activeVersion = scVersions.active
private val javaVersion = scVersion("java.version").toInt()
private fun scVersion(name: String): String = scVersions.get(name)

private val allVersionsJson = VersionsJson.load(rootProject.file("versions.json"))
private val javaFloor: Int = allVersionsJson.allVersions
    .mapNotNull { version -> allVersionsJson.propertiesFor(version)["java.version"]?.toInt() }
    .minOrNull() ?: javaVersion

private val platformIndependentModules =
    setOf(
        ":api", ":core", ":util", ":media:audio",
        ":platform:proxy", ":platform:proxy:common", ":platform:proxy:velocity", ":platform:proxy:bungeecord",
    )

private val bytecodeTarget: Int = if (project.path in platformIndependentModules) javaFloor else javaVersion

if (project.path !in platformIndependentModules) {
    layout.buildDirectory.set(layout.projectDirectory.dir("build/$activeVersion"))
}

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

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(javaVersion)) }
}

dependencies {
    "compileOnly"(kotlin("stdlib"))
    "testImplementation"(kotlin("stdlib"))
    "testImplementation"(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(javaVersion)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
    options.release.set(bytecodeTarget)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(bytecodeTarget.toString()))
    compilerOptions.optIn.add("com.dreamdisplays.api.Unstable")
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE"))
}

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
