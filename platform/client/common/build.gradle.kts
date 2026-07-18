plugins {
    id("net.neoforged.moddev")
    id("dreamdisplays.kotlin-conventions")
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    api(project(":core"))
    api(project(":api"))
    api(project(":media:runtime"))
    api(project(":media:player"))
    api(project(":media:source"))
    api(project(":media:audio"))
    api(project(":util"))
    api(libs.jspecify)
    api(libs.commonsCompress)
    api(libs.caffeine)
    api(libs.tukaaniXz)
    api(libs.semver4j)
    api(libs.newpipeExtractor)
    api(libs.kotlinxCoroutinesCore)
    compileOnly(libs.kotlinStdlib)
}

val scVersions = gradle.extensions.getByType<StonecutterVersions>()
fun scVersion(name: String): String = scVersions.get(name)

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
