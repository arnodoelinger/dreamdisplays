subprojects {
    val activeStonecutterVersion = rootProject.file("versions/active.txt").readText().trim()

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        archiveVersion.set("${project.version}+mc$activeStonecutterVersion")
    }

    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.quiltmc.org/repository/release/")
        maven("https://maven.quiltmc.org/repository/snapshot/")
        maven("https://repo.lostyy.ru/releases")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://jitpack.io")
    }
}
