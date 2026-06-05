plugins {
    id("net.neoforged.moddev") version libs.versions.moddev
    id("maven-publish")
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.shadow-conventions")
}

dependencies {
    implementation(project(":common"))
    shadow(project(":common"))
    shadow(libs.kotlinStdlib)
}

neoForge {
    enable {
        version = libs.versions.neoforge.get()
    }
    accessTransformers.from(file("src/main/resources/META-INF/accesstransformer.cfg"))
    runs {
        register("neoClient") {
            client()
        }
    }
}

tasks.processResources {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(mapOf("version" to projectVersion))
    }
    filesMatching("assets/dreamdisplays/version.txt") {
        expand(mapOf("version" to projectVersion))
    }
}

java {
    withSourcesJar()
}

tasks.shadowJar {
    configurations = listOf(project.configurations.getByName("shadow"))
    archiveBaseName = "dreamdisplays-neoforge"
    dependencies {
        include(project(":common"))
        include(dependency("me.inotsleep:utils"))
        include(dependency("org.apache.commons:commons-compress"))
        include(dependency("org.semver4j:semver4j"))
        include(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
        include(dependency("org.jetbrains:annotations"))
    }
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "me.inotsleep.utils",
        "org.apache.commons.compress",
        "org.tukaani.xz",
        "org.semver4j",
        "kotlin",
        "org.jetbrains.annotations",
        "org.intellij.lang.annotations",
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
}
