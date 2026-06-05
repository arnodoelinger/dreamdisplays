plugins {
    id("net.neoforged.moddev") version libs.versions.moddev
    id("dreamdisplays.kotlin-conventions")
}

dependencies {
    api(libs.utils)
    api(libs.jspecify)
    api(libs.commonsCompress)
    api(libs.semver4j)
    compileOnly(libs.kotlinStdlib)
}

neoForge {
    enable {
        version = libs.versions.neoforge.get()
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-jvm-default=enable")
    }
}
