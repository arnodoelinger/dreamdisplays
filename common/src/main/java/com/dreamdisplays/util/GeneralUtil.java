package com.dreamdisplays.util;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers.
 */
@NullMarked
public class GeneralUtil {

    // Detects the current operating system platform
    public static String detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
        if (os.contains("win")) {
            return "windows";
        } else if (os.contains("mac")) {
            return "macos";
        } else if (
                os.contains("nux") || os.contains("nix") || os.contains("aix")
        ) {
            return "linux";
        }
        throw new UnsupportedOperationException("Unsupported OS: " + os);
    }

    // Extracts video ID from various YouTube URL formats
    @Nullable
    public static String extractVideoId(String youtubeUrl) {
        if (youtubeUrl.isEmpty()) {
            return null;
        }

        try {
            URI uri = new URI(youtubeUrl);
            String host = uri.getHost();

            // Handle youtu.be shortened URLs
            if (host != null && host.contains("youtu.be")) {
                String path = uri.getPath();
                if (path != null && path.length() > 1) {
                    String videoId = path.substring(1);
                    // Remove any query parameters from the video ID
                    videoId = videoId.split("[?#]")[0];
                    return videoId.isEmpty() ? null : videoId;
                }
            }

            // Handle youtube.com URLs
            if (host != null && host.contains("youtube.com")) {
                String query = uri.getQuery();

                // Extract video ID from v parameter
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] pair = param.split("=", 2);
                        if (pair.length == 2 && pair[0].equals("v")) {
                            return pair[1];
                        }
                    }
                }

                // Handle YouTube Shorts URLs
                String path = uri.getPath();
                if (path != null && (path.contains("shorts") || path.contains("/live/"))) {
                    String[] pathSegments = path.split("/");
                    for (String segment : pathSegments) {
                        if (!segment.isEmpty() && !segment.equals("shorts") && !segment.equals("live")) {
                            // Remove any query parameters
                            String videoId = segment.split("[?#]")[0];
                            return videoId.isEmpty() ? null : videoId;
                        }
                    }
                }
            }
        } catch (URISyntaxException ignored) {
        }

        // Additional pattern for direct video IDs (11 character alphanumeric)
        String directIdRegex = "[a-zA-Z0-9_-]{11}";
        Matcher matcher = Pattern.compile(directIdRegex).matcher(youtubeUrl);
        if (matcher.find()) {
            return matcher.group();
        }

        return null;
    }

    public static String readResource(String resourcePath) throws IOException {
        try (InputStream in = GeneralUtil.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException(
                        "Can't find the resource: " + resourcePath
                );
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in)
            );
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    // Reads the mod version from the appropriate metadata file
    public static String getModVersion() {
        // Fabric
        try {
            String fabricJson = readResource("/fabric.mod.json");
            Pattern pattern = Pattern.compile(
                    "\"version\"\\s*:\\s*\"([^\"]+)\""
            );
            Matcher matcher = pattern.matcher(fabricJson);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (IOException ignored) {
        }

        // NeoForge/Forge
        try {
            String neoforgeToml = readResource("/META-INF/neoforge.mods.toml");
            Pattern pattern = Pattern.compile("version\\s*=\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(neoforgeToml);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (IOException ignored) {
        }

        return "unknown";
    }
}
