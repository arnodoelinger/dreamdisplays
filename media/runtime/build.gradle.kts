plugins {
    id("dreamdisplays.kotlin-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":api"))
    api(project(":util"))
    compileOnly(libs.slf4jApi)
    compileOnly(libs.kotlinStdlib)
    testImplementation(libs.kotlinStdlib)
    testImplementation(libs.slf4jApi)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
