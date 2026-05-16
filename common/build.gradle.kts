plugins {
    id("net.neoforged.moddev") version libs.versions.moddev
    kotlin("jvm") version libs.versions.kotlin
}

dependencies {
    api(libs.utils)
    api(libs.jspecify)
    api("org.apache.commons:commons-compress:1.28.0")
    api("org.tukaani:xz:1.12")
    compileOnly(libs.kotlinStdlib)
}

neoForge {
    enable {
        version = libs.versions.neoforge.get()
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

kotlin {
    jvmToolchain(25)
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
