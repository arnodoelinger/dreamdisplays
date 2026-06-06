import java.util.Properties

plugins {
    id("net.neoforged.moddev")
    id("dreamdisplays.kotlin-conventions")
}

dependencies {
    api(libs.jspecify)
    api(libs.commonsCompress)
    api(libs.semver4j)
    compileOnly(libs.kotlinStdlib)
}

val activeStonecutterVersion = rootProject.file("versions/active.txt").readText().trim()
val stonecutterVersions = Properties().apply {
    rootProject.file("versions/$activeStonecutterVersion/gradle.properties").inputStream().use { input -> load(input) }
}
fun scVersion(name: String): String = stonecutterVersions.getProperty(name)
    ?: error("Missing Stonecutter version property '$name' for $activeStonecutterVersion.")

neoForge {
    enable {
        version = scVersion("neoforge.version")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-jvm-default=enable")
    }
}
