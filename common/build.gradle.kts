plugins {
    id("net.neoforged.moddev") version libs.versions.moddev
    kotlin("jvm") version libs.versions.kotlin
}

dependencies {
    api(libs.utils)
    api(libs.jspecify)
    api(libs.commonsCompress)
    compileOnly(libs.kotlinStdlib)
}

neoForge {
    enable {
        version = libs.versions.neoforge.get()
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("java.version").get().toInt())) }
}

kotlin {
    jvmToolchain(providers.gradleProperty("java.version").get().toInt())
    compilerOptions {
        freeCompilerArgs.addAll("-jvm-default=enable")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
}

tasks.jar {
    from(rootProject.file("LICENSE"))
}
