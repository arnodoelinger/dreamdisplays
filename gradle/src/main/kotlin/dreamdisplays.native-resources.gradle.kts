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

private val ffmpegSharedLibraryComponents = listOf(
    "avutil",
    "swresample",
    "swscale",
    "avcodec",
    "avformat",
    "avfilter",
    "avdevice",
)

private fun nativeLibraryName(platformKey: String, baseName: String): String = when {
    platformKey.startsWith("windows-") -> "$baseName.dll"
    platformKey.startsWith("macos-") -> "lib$baseName.dylib"
    else -> "lib$baseName.so"
}

private fun isSharedLibraryName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".dll") || lower.endsWith(".dylib") || lower.contains(".so")
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

tasks.withType<ProcessResources>().configureEach {
    val nativeBundleDir = rootProject.file("native/build/ci-bundle/dreamdisplays-natives")
    val requireNatives = providers.gradleProperty("dreamdisplays.requireNatives")
        .orElse(providers.environmentVariable("DREAMDISPLAYS_REQUIRE_NATIVES"))
        .map { it.toStrictBoolean() }
        .getOrElse(false)

    inputs.property("dreamdisplaysRequireNatives", requireNatives)
    inputs.files(rootProject.fileTree(nativeBundleDir)).optional()

    if (nativeBundleDir.isDirectory) {
        from(nativeBundleDir) {
            into("dreamdisplays-natives")
        }
    } else {
        val nativeKey = hostNativeKey()
        nativeLibraryBaseNames.forEach { libBaseName ->
            val nativeLib = rootProject.file("native/target/release/" + System.mapLibraryName(libBaseName))
            if (nativeLib.isFile) {
                from(nativeLib) {
                    into("dreamdisplays-natives/$nativeKey")
                }
            }
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

        val missingNativeLibraries = nativePlatformKeys.flatMap { platformKey ->
            nativeLibraryBaseNames.map { libBaseName ->
                File(nativeBundleDir, "$platformKey/${nativeLibraryName(platformKey, libBaseName)}")
            }
        }.filterNot { it.isFile }

        val missingFfmpegComponents = nativePlatformKeys.flatMap { platformKey ->
            val platformDir = File(nativeBundleDir, platformKey)
            val bundledLibraries = platformDir.listFiles()?.filter { it.isFile && isSharedLibraryName(it.name) }.orEmpty()
            ffmpegSharedLibraryComponents
                .filter { component -> bundledLibraries.none { it.name.lowercase().contains(component) } }
                .map { component -> "$platformKey/$component" }
        }

        if (missingNativeLibraries.isNotEmpty() || missingFfmpegComponents.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Native bundle is incomplete.")
                    if (missingNativeLibraries.isNotEmpty()) {
                        appendLine("Missing required DreamDisplays libraries:")
                        missingNativeLibraries.forEach { appendLine(" - ${it.relativeTo(rootProject.projectDir)}") }
                    }
                    if (missingFfmpegComponents.isNotEmpty()) {
                        appendLine("Missing required FFmpeg shared library components:")
                        missingFfmpegComponents.forEach { appendLine(" - $it") }
                    }
                }
            )
        }
    }
}
