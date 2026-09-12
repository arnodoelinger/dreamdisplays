import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
    mergeServiceFiles()
    exclude("META-INF/versions/9/module-info.class")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")
    exclude("META-INF/*.kotlin_module")
}
