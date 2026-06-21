plugins {
    id("dreamdisplays.kotlin-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":core"))
    api(project(":api"))
    api(libs.commonsCompress)
    api(libs.tukaaniXz)
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
