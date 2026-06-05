import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    java
    id("org.jetbrains.kotlin.jvm")
}

private val javaVersion = providers.gradleProperty("java.version").get().toInt()

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(javaVersion)) }
}

extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(javaVersion)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE"))
}
