plugins {
    id("dreamdisplays.kotlin-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":core"))
    // gson and slf4j are provided at runtime by the Minecraft/Paper platform; compile-only here
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
