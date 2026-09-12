package support.shadow

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.CopySpec

val dreamDisplaysSharedModules = listOf(
    ":platform:client:common",
    ":core",
    ":api",
    ":util",
    ":media:runtime",
    ":media:source",
    ":media:player",
    ":media:audio",
)

val dreamDisplaysShadedDependencies = listOf(
    "org.jetbrains.kotlinx:kotlinx-serialization-core",
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-serialization-protobuf",
    "org.jetbrains.kotlinx:kotlinx-serialization-protobuf-jvm",
    "org.jetbrains.kotlinx:kotlinx-serialization-json",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-datetime-jvm",
    "org.xerial:sqlite-jdbc",
    "org.apache.commons:commons-compress",
    "org.tukaani:xz",
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains:annotations",
    "org.tomlj:tomlj",
    "org.antlr:antlr4-runtime",
    "org.semver4j:semver4j",
    "com.github.ben-manes.caffeine:caffeine",
    "com.squareup.okhttp3:okhttp",
    "com.squareup.okhttp3:okhttp-jvm",
    "com.squareup.okio:okio",
    "com.squareup.okio:okio-jvm",
    "org.jetbrains.exposed:exposed-core",
    "org.jetbrains.exposed:exposed-jdbc",
    "org.jetbrains.exposed:exposed-migration-core",
    "org.jetbrains.exposed:exposed-migration-jdbc",
    "com.zaxxer:HikariCP",
    "com.github.TeamNewPipe:NewPipeExtractor",
    "com.github.TeamNewPipe:nanojson",
    "org.jsoup:jsoup",
    "com.google.protobuf:protobuf-javalite",
    "org.mozilla:rhino",
    "org.mozilla:rhino-engine",
)

val dreamDisplaysShadedPackages = listOf(
    "org.apache.commons.compress",
    "org.tukaani.xz",
    "kotlin",
    "kotlinx",
    "org.jetbrains.annotations",
    "org.intellij.lang.annotations",
    "org.tomlj",
    "org.antlr",
    "org.semver4j",
    "com.github.benmanes.caffeine",
    "okhttp3",
    "okio",
    "org.jetbrains.exposed",
    "com.zaxxer.hikari",
    "org.schabi.newpipe",
    "com.grack.nanojson",
    "org.jsoup",
    "com.google.protobuf",
    "org.mozilla.javascript",
    "org.mozilla.classfile",
)

val dreamDisplaysSqliteNativeExcludes = listOf(
    "org/sqlite/native/Linux-Android/**",
    "org/sqlite/native/Linux-Musl/x86/**",
    "org/sqlite/native/FreeBSD/**",
    "org/sqlite/native/Linux/ppc64/**",
    "org/sqlite/native/Linux/riscv64/**",
    "org/sqlite/native/Linux/arm/**",
    "org/sqlite/native/Linux/armv6/**",
    "org/sqlite/native/Linux/armv7/**",
    "org/sqlite/native/Linux/x86/**",
    "org/sqlite/native/Windows/x86/**",
    "org/sqlite/native/Windows/armv7/**",
    "org/sqlite/native/Windows/aarch64/**",
)

fun ShadowJar.includeDreamDisplaysSharedContents() {
    dependencies {
        dreamDisplaysSharedModules.forEach { include(project(it)) }
        dreamDisplaysShadedDependencies.forEach { include(dependency(it)) }
    }
}

fun ShadowJar.relocateDreamDisplaysSharedPackages(prefix: String = "com.dreamdisplays.libs") {
    dreamDisplaysShadedPackages.forEach { relocate(it, "$prefix.$it") }
}

fun CopySpec.excludeDreamDisplaysSqliteNativeExtras() {
    dreamDisplaysSqliteNativeExcludes.forEach { exclude(it) }
}
