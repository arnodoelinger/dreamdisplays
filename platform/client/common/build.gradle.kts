import support.stonecutter.StonecutterVersions

plugins {
    id("net.neoforged.moddev")
    id("dreamdisplays.kotlin-conventions")
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://prmaven.neoforged.net/NeoForge/pr3403")
}

val scVersions = gradle.extensions.getByType<StonecutterVersions>()
fun scVersion(name: String): String = scVersions.get(name)

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
    if (scVersions.getOrNull("neoform.version") != null) {
        compileOnly("org.spongepowered:mixin:0.8.7")
        compileOnly("org.lwjgl:lwjgl:3.4.3")
        compileOnly("org.lwjgl:lwjgl-glfw:3.4.3")
        compileOnly("org.lwjgl:lwjgl-sdl:3.4.3")
        compileOnly("org.lwjgl:lwjgl-opengl:3.4.3")
    }
}

neoForge {
    enable {
        val neoForm = scVersions.getOrNull("neoform.version")
        if (neoForm != null) {
            neoFormVersion = neoForm
        } else {
            version = scVersion("neoforge.version")
        }
    }
    accessTransformers.from(
        project(":platform:client:neoforge").file("src/main/resources/META-INF/accesstransformer.cfg"),
    )
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-jvm-default=enable")
    }
}
