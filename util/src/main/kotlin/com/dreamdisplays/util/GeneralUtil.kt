package com.dreamdisplays.util

import java.io.IOException

/** General utility functions for the mod. */
object GeneralUtil {
    /** Developer version. */
    private val DEV_VERSION = Regex("""^([0-9]+(?:\.[0-9]+)*)-dev$""")

    /** Preview version (uses .1, .2, etc. index) */
    private val PREVIEW_VERSION = Regex("""^([0-9]+(?:\.[0-9]+)*)-preview\.([0-9]+)$""")

    /** Release version */
    private val RELEASE_VERSION = Regex("""^[0-9]+(?:\.[0-9]+)*$""")

    /** Reads the classpath resource at [resourcePath] and returns its content as a UTF-8 string. */
    @Throws(IOException::class)
    fun readResource(resourcePath: String): String {
        val stream = GeneralUtil::class.java.getResourceAsStream(resourcePath)
            ?: throw IOException("Can't find the resource: $resourcePath.")
        return stream.bufferedReader().use { it.readText() }
    }

    /** Returns the mod version string from `version.txt`, or "unknown" if the resource is missing. */
    fun getModVersion(): String =
        runCatching { readResource("/assets/dreamdisplays/version.txt").trim() }
            .getOrDefault("unknown")

    /**
     * Formats [getModVersion] into a human-readable channel label:
     * `1.8.5-dev` -> `1.8.5 Developer`, `1.9.0-preview.5` -> `1.9.0 Preview 5`,
     * `1.8.4` -> `1.8.4 Release`.
     *
     * Unrecognized versions are returned unchanged.
     */
    fun getPrettyModVersion(): String {
        val version = getModVersion()
        DEV_VERSION.matchEntire(version)?.let { return "${it.groupValues[1]} Developer" }
        PREVIEW_VERSION.matchEntire(version)?.let { return "${it.groupValues[1]} Preview ${it.groupValues[2]}" }
        if (RELEASE_VERSION.matches(version)) return "$version Release"
        return version
    }
}
