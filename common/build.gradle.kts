plugins {
    id("net.neoforged.moddev") version libs.versions.moddev
}

dependencies {
    api(libs.utils)
    api(libs.jspecify)
    api("org.apache.commons:commons-compress:1.27.1")
    api("org.tukaani:xz:1.10")
}

neoForge {
    enable {
        version = libs.versions.neoforge.get()
    }
}
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
}

tasks.jar {
    from(rootProject.file("LICENSE"))
}
