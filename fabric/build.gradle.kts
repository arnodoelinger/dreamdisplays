import net.fabricmc.loom.task.RemapJarTask

plugins {
    id("net.fabricmc.fabric-loom-remap") version libs.versions.loom // remove remap in 26.1
    id("maven-publish")
    id("com.gradleup.shadow") version libs.versions.shadow
}

loom {
    accessWidenerPath.set(project(":common").file("src/main/resources/dreamdisplays.classtweaker"))
}

dependencies {
    minecraft(libs.fabricMinecraft)
    mappings(loom.layered {
        officialMojangMappings()
        parchment(rootProject.property("neoForge.parchment.parchmentArtifact")!!)
    })
    modImplementation(libs.fabricLoader)
    modImplementation(libs.fabricApi)
    shadow(project(":common"))
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
}

java {
    withSourcesJar()
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
}

tasks.jar {
    from(rootProject.file("LICENSE"))
}

tasks.withType<RemapJarTask>().configureEach {
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })

    archiveClassifier = ""
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))

    archiveBaseName = "dreamdisplays-fabric"
    archiveVersion.set(rootProject.version.toString())
}

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    dependencies {
        include(project(":common"))
        include(dependency("me.inotsleep:utils"))
        include(dependency("org.apache.commons:commons-compress"))
        include(dependency("org.tukaani:xz"))
    }
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "me.inotsleep.utils",
        "org.apache.commons.compress",
        "org.tukaani.xz",
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
}
