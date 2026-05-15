package com.dreamdisplays.media;

import com.dreamdisplays.ytdlp.YtStream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure helpers for parsing stream quality labels and picking video/audio
 * tracks from the candidate lists returned by yt-dlp.
 */
@NullMarked
public final class MediaStreamSelector {

    private MediaStreamSelector() {
    }

    public static int parseQuality(YtStream stream) {
        return parseQualityValue(stream.getResolution(), Integer.MAX_VALUE);
    }

    public static int parseQualityValue(@Nullable String raw, int fallback) {
        if (raw == null) return fallback;
        int i = 0, n = raw.length();
        while (i < n && !Character.isDigit(raw.charAt(i))) i++;
        int start = i;
        while (i < n && Character.isDigit(raw.charAt(i))) i++;
        if (start == i) return fallback;
        try {
            return Integer.parseInt(raw.substring(start, i));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static int[] qualityToDims(int quality) {
        if (quality <= 240) return new int[]{426, 240};
        if (quality <= 360) return new int[]{640, 360};
        if (quality <= 480) return new int[]{854, 480};
        if (quality <= 720) return new int[]{1280, 720};
        if (quality <= 1080) return new int[]{1920, 1080};
        if (quality <= 1440) return new int[]{2560, 1440};
        return new int[]{3840, 2160};
    }

    public static Optional<YtStream> pickVideo(@Nullable List<YtStream> streams, int target) {
        if (streams == null) return Optional.empty();
        return streams.stream()
                .filter(s -> s.getResolution() != null)
                .min(Comparator
                        .comparingInt((YtStream s) -> Math.abs(parseQuality(s) - target))
                        .thenComparingInt(s -> s.isMuxed() ? 0 : 1)
                        .thenComparingInt(s -> s.hasAudio() ? 0 : 1));
    }

    public static Optional<YtStream> pickAudio(
            List<YtStream> audioStreams,
            String lang,
            @Nullable YtStream chosenVideo
    ) {
        Optional<YtStream> preferred = audioStreams.stream()
                .filter(s -> !s.hasVideo())
                .filter(s -> matchesLanguage(s, lang))
                .reduce((f, n) -> n);
        if (preferred.isPresent()) return preferred;

        preferred = audioStreams.stream()
                .filter(s -> !s.hasVideo())
                .filter(s -> s.getAudioTrackId() == null || s.getAudioTrackId().equals("und"))
                .reduce((f, n) -> n);
        if (preferred.isPresent()) return preferred;

        preferred = audioStreams.stream().filter(s -> !s.hasVideo()).reduce((f, n) -> n);
        if (preferred.isPresent()) return preferred;

        if (chosenVideo != null && chosenVideo.hasAudio()) return Optional.of(chosenVideo);

        preferred = audioStreams.stream().filter(s -> matchesLanguage(s, lang)).reduce((f, n) -> n);
        return preferred.isPresent() ? preferred : audioStreams.stream().findFirst();
    }

    public static boolean matchesLanguage(YtStream stream, String lang) {
        return (stream.getAudioTrackId() != null && stream.getAudioTrackId().contains(lang))
                || (stream.getAudioTrackName() != null && stream.getAudioTrackName().contains(lang));
    }
}
