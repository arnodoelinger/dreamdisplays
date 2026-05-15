package com.dreamdisplays.media;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Builds FFmpeg process invocations for the media pipeline and handles their
 * graceful shutdown.
 */
@NullMarked
public final class MediaProcess {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private MediaProcess() {
    }

    public static Process buildVideo(
            String ffmpeg, String url, int w, int h, long offsetNanos
    ) throws IOException {
        List<String> cmd = baseCommand(ffmpeg, url, offsetNanos);
        cmd.addAll(List.of("-an",
                "-vf", "scale=" + w + ":" + h,
                "-f", "rawvideo", "-pix_fmt", "rgba", "-"));
        return new ProcessBuilder(cmd).start();
    }

    public static Process buildAudio(
            String ffmpeg, String url, long offsetNanos, int sampleRate
    ) throws IOException {
        List<String> cmd = baseCommand(ffmpeg, url, offsetNanos);
        cmd.addAll(List.of("-vn",
                "-f", "s16le", "-ar", String.valueOf(sampleRate), "-ac", "2", "-"));
        return new ProcessBuilder(cmd).start();
    }

    public static void gracefulDestroy(@Nullable Process proc) {
        if (proc == null) return;
        try {
            OutputStream stdin = proc.getOutputStream();
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
        }
        proc.destroy();
        try {
            if (!proc.waitFor(1, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> baseCommand(String ffmpeg, String url, long offsetNanos) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.addAll(List.of("-hide_banner", "-loglevel", "error", "-nostats"));
        cmd.addAll(List.of("-headers",
                "User-Agent: " + USER_AGENT + "\r\nReferer: https://www.youtube.com/\r\n"));
        cmd.addAll(List.of(
                "-reconnect", "1",
                "-reconnect_streamed", "1",
                "-reconnect_delay_max", "10",
                "-reconnect_on_network_error", "1",
                "-reconnect_on_http_error", "4xx,5xx"
        ));
        cmd.addAll(List.of("-rw_timeout", "15000000"));
        if (offsetNanos > 0) {
            cmd.addAll(List.of("-ss",
                    String.format(Locale.US, "%.6f", offsetNanos / 1e9)));
        }
        cmd.addAll(List.of("-re", "-i", url));
        return cmd;
    }
}
