package support.natives

import java.io.File

val nativePlatformKeys = listOf(
    "linux-x64",
    "linux-aarch64",
    "macos-x64",
    "macos-aarch64",
    "windows-x64",
    "windows-aarch64",
)

val nativeLibraryBaseNames = listOf(
    "dreamdisplays_native",
    "dreamdisplays_lav",
)

fun nativeLibraryName(platformKey: String, baseName: String): String = when {
    platformKey.startsWith("windows-") -> "$baseName.dll"
    platformKey.startsWith("macos-") -> "lib$baseName.dylib"
    else -> "lib$baseName.so"
}

fun hostNativeKey(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("win") -> if (arch.contains("aarch64") || arch.contains("arm")) "windows-aarch64" else "windows-x64"
        os.contains("mac") -> if (arch.contains("aarch64") || arch.contains("arm")) "macos-aarch64" else "macos-x64"
        else -> if (arch.contains("aarch64") || arch.contains("arm")) "linux-aarch64" else "linux-x64"
    }
}

fun String.toStrictBoolean(): Boolean = equals("true", ignoreCase = true)

fun cargoAvailable(): Boolean {
    if (File(System.getProperty("user.home"), ".cargo/bin/cargo").canExecute()) return true
    val path = System.getenv("PATH") ?: return false
    return path.split(File.pathSeparator).any {
        File(it, "cargo").canExecute() || File(it, "cargo.exe").canExecute()
    }
}

fun String.toPlatformList(): List<String> =
    split(',', ' ', '\n', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
