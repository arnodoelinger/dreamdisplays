plugins {
    id("dreamdisplays.kotlin-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(libs.kotlinStdlib)
    testImplementation(libs.kotlinStdlib)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
