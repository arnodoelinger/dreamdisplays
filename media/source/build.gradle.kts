plugins {
    id("dreamdisplays.kotlin-conventions")
    id("dreamdisplays.serialization-conventions")
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
    testImplementation(libs.slf4jApi)
}
