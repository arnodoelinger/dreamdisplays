import org.gradle.api.GradleException
import org.gradle.language.jvm.tasks.ProcessResources

private val nativePlatformKeys = listOf(
    "linux-x64",
    "linux-aarch64",
    "macos-x64",
    "macos-aarch64",
    "windows-x64",
    "windows-aarch64",
)

private val nativeLibraryBaseNames = listOf(
    "dreamdisplays_native",
    "dreamdisplays_lav",
)

private fun nativeLibraryName(platformKey: String, baseName: String): String = when {
    platformKey.startsWith("windows-") -> "$baseName.dll"
    platformKey.startsWith("macos-") -> "lib$baseName.dylib"
    else -> "lib$baseName.so"
}

private fun hostNativeKey(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("win") -> if (arch.contains("aarch64") || arch.contains("arm")) "windows-aarch64" else "windows-x64"
        os.contains("mac") -> if (arch.contains("aarch64") || arch.contains("arm")) "macos-aarch64" else "macos-x64"
        else -> if (arch.contains("aarch64") || arch.contains("arm")) "linux-aarch64" else "linux-x64"
    }
}

private fun String.toStrictBoolean(): Boolean = equals("true", ignoreCase = true)

private fun String.toPlatformList(): List<String> =
    split(',', ' ', '\n', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

tasks.withType<ProcessResources>().configureEach {
    val nativeBundleDir = rootProject.file("native/build/ci-bundle/dreamdisplays-natives")
    val requireNatives = providers.gradleProperty("dreamdisplays.requireNatives")
        .orElse(providers.environmentVariable("DREAMDISPLAYS_REQUIRE_NATIVES"))
        .map { it.toStrictBoolean() }
        .getOrElse(false)
    val requiredNativePlatforms = providers.gradleProperty("dreamdisplays.requiredNativePlatforms")
        .orElse(providers.environmentVariable("DREAMDISPLAYS_REQUIRED_NATIVE_PLATFORMS"))
        .map { it.toPlatformList() }
        .getOrElse(nativePlatformKeys)
    val unknownNativePlatforms = requiredNativePlatforms.filterNot { it in nativePlatformKeys }
    if (unknownNativePlatforms.isNotEmpty()) {
        throw GradleException("Unknown required native platforms: ${unknownNativePlatforms.joinToString()}.")
    }

    inputs.property("dreamdisplaysRequireNatives", requireNatives)
    inputs.property("dreamdisplaysRequiredNativePlatforms", requiredNativePlatforms.joinToString(","))
    inputs.files(rootProject.fileTree(nativeBundleDir)).optional()

    if (nativeBundleDir.isDirectory) {
        from(nativeBundleDir) {
            into("dreamdisplays-natives")
        }
    } else {
        val nativeKey = hostNativeKey()
        val hostReleaseNatives = nativeLibraryBaseNames.map { libBaseName ->
            rootProject.file("native/target/release/" + System.mapLibraryName(libBaseName))
        }
        inputs.files(hostReleaseNatives).optional()
        from({ hostReleaseNatives.filter { it.isFile } }) {
            into("dreamdisplays-natives/$nativeKey")
        }
    }

    doFirst {
        if (!requireNatives) return@doFirst
        if (!nativeBundleDir.isDirectory) {
            throw GradleException(
                "Native bundle is required, but $nativeBundleDir does not exist. " +
                    "Run the CI native matrix first or disable DREAMDISPLAYS_REQUIRE_NATIVES."
            )
        }

        val missingNativeLibraries = requiredNativePlatforms.flatMap { platformKey ->
            nativeLibraryBaseNames.map { libBaseName ->
                File(nativeBundleDir, "$platformKey/${nativeLibraryName(platformKey, libBaseName)}")
            }
        }.filterNot { it.isFile }

        val missingLicenseFiles = requiredNativePlatforms
            .map { platformKey -> File(nativeBundleDir, "$platformKey/licenses/ffmpeg-license.txt") }
            .filterNot { it.isFile && it.length() > 0L }

        if (missingNativeLibraries.isNotEmpty() || missingLicenseFiles.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Native bundle is incomplete.")
                    if (missingNativeLibraries.isNotEmpty()) {
                        appendLine("Missing required DreamDisplays libraries:")
                        missingNativeLibraries.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir)}") }
                    }
                    if (missingLicenseFiles.isNotEmpty()) {
                        appendLine("Missing required FFmpeg license metadata:")
                        missingLicenseFiles.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir)}") }
                    }
                }
            )
        }
    }
}
