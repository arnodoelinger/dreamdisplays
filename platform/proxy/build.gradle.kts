plugins {
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.serialization-conventions")
    id("dreamdisplays.shadow-conventions")
    alias(libs.plugins.platformweaver)
}

platformweaver {
    target = "velocity"
    chameleonsDir = null
}

dependencies {
    compileOnly(libs.platformweaverAnnotations)
    compileOnly(libs.velocityApi)
    compileOnly(libs.slf4jApi)

    implementation(project(":core"))
    implementation(libs.kotlinStdlib)
    implementation(libs.kotlinxSerializationProtobuf)
}

tasks.processResources {
    val props = mapOf("version" to rootProject.version.toString())
    inputs.properties(props)
    filteringCharset = Charsets.UTF_8.name()
    filesMatching("velocity-plugin.json") {
        expand(props)
    }
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("dreamdisplays-velocity")
    val prefix = "com.dreamdisplays.libs"
    listOf(
        "kotlin",
        "kotlinx.serialization",
        "kotlinx.io",
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set(rootProject.version.toString())
}
