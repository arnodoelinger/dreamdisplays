plugins {
    id("dreamdisplays.kotlin-conventions")
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":core"))
    api(libs.caffeine)
    api(libs.kotlinxCoroutinesCore)
    api(libs.kotlinxSerializationJson)
    implementation(libs.okhttp)
    compileOnly(libs.slf4jApi)
    compileOnly(libs.semver4j)
    compileOnly(libs.kotlinStdlib)
    testImplementation(libs.kotlinStdlib)
    testImplementation(libs.slf4jApi)
    testImplementation(libs.semver4j)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
