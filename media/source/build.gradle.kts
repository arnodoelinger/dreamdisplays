plugins {
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.serialization-conventions")
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    api(project(":api"))
    api(project(":media:runtime"))
    api(project(":util"))
    api(libs.caffeine)
    api(project(":media:player"))
    api(libs.newpipeExtractor)
    api(libs.commonsCompress)
    api(libs.tukaaniXz)
    api(libs.kotlinxCoroutinesCore)
    api(libs.kotlinxSerializationJson)
    compileOnly(libs.slf4jApi)
    compileOnly(libs.kotlinStdlib)
    testImplementation(libs.kotlinStdlib)
    testImplementation(libs.slf4jApi)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
