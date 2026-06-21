plugins {
    id("dreamdisplays.kotlin-conventions")
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    api(project(":core"))
    api(project(":api"))
    api(project(":protocol"))
    api(project(":util"))
    api(project(":media-player"))
    api(libs.newpipeExtractor)
    api(libs.commonsCompress)
    api(libs.tukaaniXz)
    api(libs.kotlinxCoroutinesCore)
    // gson and slf4j are provided at runtime by the platform; compile-only here.
    compileOnly(libs.gson)
    compileOnly(libs.slf4jApi)
    compileOnly(libs.kotlinStdlib)
    testImplementation(libs.kotlinStdlib)
    testImplementation(libs.gson)
    testImplementation(libs.slf4jApi)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
