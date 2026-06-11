plugins {
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.serialization-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    api(libs.kotlinxSerializationProtobuf)
    compileOnly(libs.kotlinStdlib)
    testImplementation(libs.kotlinStdlib)
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
    mainClass.set("com.dreamdisplays.protocol.SchemaExporterKt")
    args(layout.projectDirectory.file("src/main/proto/dreamdisplays.proto").asFile.absolutePath)
}
