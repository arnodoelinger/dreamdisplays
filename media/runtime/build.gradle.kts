plugins {
    id("dreamdisplays.kotlin-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":media"))
    api(project(":core"))
    compileOnly(libs.kotlinStdlib)
    testImplementation(libs.kotlinStdlib)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
