plugins {
    java
    id("com.gradleup.shadow") version libs.versions.shadow
    kotlin("jvm")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.63")
    compileOnly(libs.jspecify)
    implementation("me.inotsleep:utils:1.4.10")
    implementation("com.github.zafarkhaja:java-semver:0.10.2")
    implementation("com.moandjiezana.toml:toml4j:0.7.2") {
        exclude(group = "com.google.code.gson", module = "gson")
    }
    implementation(kotlin("stdlib-jdk8:2.4.0-RC"))
    implementation("org.bstats:bstats-bukkit:3.2.1")
}

val targetJavaVersion = 25
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
    options.release.set(targetJavaVersion)
}

tasks.processResources {
    val projectVersion = version.toString()
    val props = mapOf("version" to projectVersion)
    inputs.properties(props)
    filteringCharset = Charsets.UTF_8.name()
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.shadowJar {
    archiveClassifier.set("")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
    archiveBaseName.set("dreamdisplays-paper")
    archiveVersion.set(rootProject.version.toString())
    manifest {
        attributes(
            "paperweight-mappings-namespace" to "mojang",
        )
    }
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "me.inotsleep.utils",
        "org.bstats",
        "com.moandjiezana.toml",
        "com.github.zafarkhaja.semver",
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
}
