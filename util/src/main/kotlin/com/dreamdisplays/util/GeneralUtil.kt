package com.dreamdisplays.util

import java.io.IOException

/** General utility functions for the mod. */
object GeneralUtil {
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
}
