plugins {
    id("net.neoforged.moddev") version libs.versions.moddev
    kotlin("jvm") version libs.versions.kotlin
}

dependencies {
    api(libs.utils)
    api(libs.jspecify)
    api("org.apache.commons:commons-compress:1.27.1")
    api("org.tukaani:xz:1.10")
    compileOnly(libs.kotlinStdlib)
}

neoForge {
    enable {
        version = libs.versions.neoforge.get()
    }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
}

tasks.jar {
    from(rootProject.file("LICENSE"))
}
