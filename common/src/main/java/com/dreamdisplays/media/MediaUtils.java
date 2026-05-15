package com.dreamdisplays.media;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;

/**
 * Stateless helpers used by the media pipeline.
 */
@NullMarked
public final class MediaUtils {

    private MediaUtils() {
    }

    public static boolean isInterestingStderr(String line) {
        if (line.contains("Broken pipe")) return false;
        if (line.contains("Error muxing a packet")) return false;
        if (line.contains("Error submitting a packet to the muxer")) return false;
        if (line.contains("Error writing trailer")) return false;
        if (line.contains("Error closing file")) return false;
        if (line.contains("Terminating thread with return code")) return false;
        if (line.contains("Task finished with error code")) return false;
        return !line.contains("Last message repeated");
    }

    public static String truncate(@Nullable String s) {
        if (s == null) return "null";
        return s.length() <= 120 ? s : s.substring(0, 120) + "...(" + s.length() + ")";
    }

    public static int readFull(InputStream in, byte[] buf, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, total, len - total);
            if (n < 0) return total;
            total += n;
        }
        return total;
    }

    public static boolean isTransientError(String stderr) {
        return stderr.contains("403") || stderr.contains("Forbidden")
                || stderr.contains("404") || stderr.contains("Not Found")
                || stderr.contains("429") || stderr.contains("Too Many Requests")
                || stderr.contains("503") || stderr.contains("Service Unavailable")
                || stderr.contains("502") || stderr.contains("Bad Gateway")
                || stderr.contains("Connection reset")
                || stderr.contains("Connection refused")
                || stderr.contains("Connection timed out")
                || stderr.contains("Network is unreachable")
                || stderr.contains("Operation timed out")
                || stderr.contains("Server returned");
    }
}
