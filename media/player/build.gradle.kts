plugins {
    id("dreamdisplays.kotlin-conventions")
}

dependencies {
    api(project(":api"))
    api(project(":media:runtime"))
    api(project(":util"))
    compileOnly(libs.slf4jApi)
    testImplementation(libs.slf4jApi)
}
