package com.dreamdisplays.player.util

import java.io.IOException
import java.io.InputStream

/** Stateless helpers used by the media pipeline. */
object MediaUtil {
    private val BORING_STDERR = arrayOf(
        "Broken pipe",
        "Error muxing a packet",
        "Error submitting a packet to the muxer",
        "Error writing trailer",
        "Error closing file",
        "Terminating thread with return code",
        "Task finished with error code",
        "Last message repeated",
    )

    private val TRANSIENT_MARKERS = arrayOf(
        "403", "Forbidden",
        "404", "Not Found",
        "429", "Too Many Requests",
        "503", "Service Unavailable",
        "502", "Bad Gateway",
        "Connection reset",
        "Connection refused",
        "Connection timed out",
        "Network is unreachable",
        "Operation timed out",
        "Server returned",
    )

    /** Returns true if [line] is not a known benign `FFmpeg` error that can be safely ignored. */
    fun isInterestingStderr(line: String): Boolean = BORING_STDERR.none { it in line }

    /** Truncates [s] to 120 chars for logging, appending the original length if it was truncated. */
    fun truncate(s: String?): String = when {
        s == null -> "null"
        s.length <= 120 -> s
        else -> s.substring(0, 120) + "...(${s.length})"
    }

    /**
     * Reads exactly [len] bytes from [input] into [buf], blocking until all bytes are read or the end of the stream is reached.
     * @throws IOException if an I/O error occurs. Returns the total number of bytes read, which may be less than [len] if
     * the end of the stream is reached.
     */
    @Throws(IOException::class)
    fun readFull(input: InputStream, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, total, len - total)
            if (n < 0) return total
            total += n
        }
        return total
    }

    /**
     * Returns true if [stderr] contains any known markers of a transient error that may succeed on retry, such as network
     * errors or HTTP 5xx / 429 responses.
     */
    fun isTransientError(stderr: String): Boolean = TRANSIENT_MARKERS.any { it in stderr }
}
