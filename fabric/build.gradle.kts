plugins {
    id("net.fabricmc.fabric-loom") version libs.versions.loom
    id("maven-publish")
    id("com.gradleup.shadow") version libs.versions.shadow
    kotlin("jvm") version libs.versions.kotlin
}

kotlin { jvmToolchain(25) }

sourceSets.main {
    kotlin.srcDir("../server/src/main/kotlin")
}

loom {
    accessWidenerPath.set(project(":common").file("src/main/resources/dreamdisplays.classtweaker"))
}

dependencies {
    compileOnly(libs.ofratAnnotations)
    "kotlinCompilerPluginClasspath"(libs.ofratPlugin)
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.65-stable")
    compileOnly("org.bstats:bstats-bukkit:3.2.1")
    compileOnly("me.inotsleep:utils:1.4.10")
    compileOnly("com.moandjiezana.toml:toml4j:0.7.2")
    compileOnly("com.github.zafarkhaja:java-semver:0.10.2")

    minecraft(libs.fabricMinecraft)
    implementation(libs.fabricLoader)
    implementation(libs.fabricApi)
    shadow(project(":common"))
    shadow(libs.kotlinStdlib)
    shadow("com.moandjiezana.toml:toml4j:0.7.2") {
        exclude(group = "com.google.code.gson", module = "gson")
    }
    shadow("com.github.zafarkhaja:java-semver:0.10.2")
    shadow("org.xerial:sqlite-jdbc:3.49.1.0")
}

tasks.processResources {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to projectVersion))
    }
    filesMatching("quilt.mod.json") {
        expand(mapOf("version" to projectVersion))
    }
    filesMatching("assets/dreamdisplays/version.txt") {
        expand(mapOf("version" to projectVersion))
    }
}

java {
    withSourcesJar()
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-P", "plugin:io.github.arsmotorin.ofrat:platform=fabric"
    )
}

tasks.jar {
    from(rootProject.file("LICENSE"))
}

// Hack: it's a bug in Loom alpha where the validation task expects a named namespace but the classtweaker correctly uses
// official namespaces, so we have to disable the validation until it's fixed.
// TODO: when a stable Loom for 26.1.2/26.2 is released, this should be removed
tasks.named("validateAccessWidener") { enabled = false }

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    archiveBaseName = "dreamdisplays-fabric"
    archiveClassifier = ""
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
    dependencies {
        include(project(":common"))
        include(dependency("me.inotsleep:utils"))
        include(dependency("org.xerial:sqlite-jdbc"))
        include(dependency("org.apache.commons:commons-compress"))
        include(dependency("org.tukaani:xz"))
        include(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
        include(dependency("org.jetbrains:annotations"))
        include(dependency("com.moandjiezana.toml:toml4j"))
        include(dependency("com.github.zafarkhaja:java-semver"))
    }
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "me.inotsleep.utils",
        "org.apache.commons.compress",
        "org.tukaani.xz",
        "kotlin",
        "org.jetbrains.annotations",
        "org.intellij.lang.annotations",
        "com.moandjiezana.toml",
        "com.github.zafarkhaja.semver",
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
    mergeServiceFiles()
    exclude("META-INF/versions/9/module-info.class")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")
    exclude("META-INF/*.kotlin_module")
}
