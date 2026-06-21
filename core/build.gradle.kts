plugins {
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.serialization-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":media"))
    api(libs.kotlinxSerializationProtobuf)
    // slf4j is provided at runtime by the Minecraft/Paper platform; compile-only here.
    compileOnly(libs.slf4jApi)
    compileOnly(libs.kotlinStdlib)
    testImplementation(libs.kotlinStdlib)
    testImplementation(libs.slf4jApi)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Regenerates the committed .proto schema artifact from the @Serializable wire classes.
tasks.register<JavaExec>("generateProto") {
    group = "build"
    description = "Regenerates src/main/proto/dreamdisplays.proto from the packet classes."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.dreamdisplays.core.protocol.SchemaExporterKt")
    args(layout.projectDirectory.file("src/main/proto/dreamdisplays.proto").asFile.absolutePath)
}
