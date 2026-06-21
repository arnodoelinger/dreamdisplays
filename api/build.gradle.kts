plugins {
    id("dreamdisplays.kotlin-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":core"))
    api(project(":media"))
    compileOnly(libs.kotlinStdlib)
    testImplementation(libs.kotlinStdlib)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
