package com.dreamdisplays.ffmpeg;

import me.inotsleep.utils.logging.LoggingManager;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@NullMarked
public class FFmpegBinary {

    private static final String CACHE_ROOT = "./dreamdisplays/ffmpeg";
    private static final String BTBN_BASE =
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest";

    private static volatile @Nullable String cachedPath = null;

    public static @Nullable String getPath() {
        if (cachedPath != null) return cachedPath;
        synchronized (FFmpegBinary.class) {
            if (cachedPath != null) return cachedPath;
            cachedPath = resolve();
            return cachedPath;
        }
    }

    public static void prewarmAsync() {
        Thread t = new Thread(() -> {
            try { getPath(); }
            catch (Exception e) { LoggingManager.warn("[Ffmpeg] prewarm failed", e); }
        }, "Ffmpeg-prewarm");
        t.setDaemon(true);
        t.start();
    }

    private static @Nullable String resolve() {
        Platform p = detectPlatform();
        if (p == null) {
            LoggingManager.warn("[Ffmpeg] No bundled binary URL for this OS/arch; trying system ffmpeg");
            return findSystemFfmpeg();
        }

        File cacheDir = new File(CACHE_ROOT + "/" + p.key);
        File binary = new File(cacheDir, p.binaryName);

        if (binary.isFile() && binary.length() > 0 && binary.canExecute()) {
            LoggingManager.info("[Ffmpeg] Using cached binary: " + binary.getAbsolutePath());
            return binary.getAbsolutePath();
        }

        try {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw new IOException("Cannot create cache dir: " + cacheDir);
            }
            downloadAndExtract(p, binary);
            if (!binary.isFile() || binary.length() == 0) {
                throw new IOException("Extracted binary is missing or empty");
            }
            markExecutable(binary);
            removeMacQuarantine(binary);
            LoggingManager.info("[Ffmpeg] Ready at " + binary.getAbsolutePath()
                    + " (" + binary.length() + " bytes)");
            return binary.getAbsolutePath();
        } catch (Exception e) {
            LoggingManager.error("[Ffmpeg] Download failed, falling back to system ffmpeg", e);
            return findSystemFfmpeg();
        }
    }

    private static void downloadAndExtract(Platform p, File destBinary) throws IOException {
        LoggingManager.info("[Ffmpeg] Downloading " + p.url);
        File parent = destBinary.getParentFile();
        File tempArchive = new File(parent, "_download" + (p.isTarXz ? ".tar.xz" : ".zip"));
        try {
            downloadWithRedirects(p.url, tempArchive);
            LoggingManager.info("[Ffmpeg] Downloaded " + tempArchive.length()
                    + " bytes, extracting '" + p.entrySuffix + "'");
            if (p.isTarXz) {
                extractFromTarXz(tempArchive, p.entrySuffix, destBinary);
            } else {
                extractFromZip(tempArchive, p.entrySuffix, destBinary);
            }
        } finally {
            if (tempArchive.exists() && !tempArchive.delete()) {
                tempArchive.deleteOnExit();
            }
        }
    }

    private static void downloadWithRedirects(String url, File dest) throws IOException {
        String currentUrl = url;
        for (int hops = 0; hops < 10; hops++) {
            HttpURLConnection conn = (HttpURLConnection) URI.create(currentUrl).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "DreamDisplays-ffmpeg-bootstrap");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(300_000);
            int status = conn.getResponseCode();
            if (status >= 300 && status < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) throw new IOException("Redirect without Location at " + currentUrl);
                currentUrl = loc;
                continue;
            }
            if (status != 200) {
                conn.disconnect();
                throw new IOException("HTTP " + status + " for " + currentUrl);
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                in.transferTo(out);
            } finally {
                conn.disconnect();
            }
            return;
        }
        throw new IOException("Too many redirects: " + url);
    }

    private static void extractFromZip(File archive, String suffix, File dest) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().endsWith(suffix)) {
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                        zis.transferTo(out);
                    }
                    return;
                }
                zis.closeEntry();
            }
        }
        throw new IOException("'" + suffix + "' not found in " + archive.getName());
    }

    private static void extractFromTarXz(File archive, String suffix, File dest) throws IOException {
        try (InputStream fis = new BufferedInputStream(new FileInputStream(archive));
             XZCompressorInputStream xz = new XZCompressorInputStream(fis);
             TarArchiveInputStream tar = new TarArchiveInputStream(xz)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().endsWith(suffix)) {
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                        tar.transferTo(out);
                    }
                    return;
                }
            }
        }
        throw new IOException("'" + suffix + "' not found in " + archive.getName());
    }

    private static void markExecutable(File binary) {
        Path path = binary.toPath();
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            binary.setExecutable(true, false);
        }
    }

    private static void removeMacQuarantine(File binary) {
        if (!isMac()) return;
        try {
            new ProcessBuilder("xattr", "-d", "com.apple.quarantine", binary.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private static @Nullable String findSystemFfmpeg() {
        String[] candidates = {"ffmpeg", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"};
        for (String candidate : candidates) {
            try {
                Process p = new ProcessBuilder(candidate, "-version")
                        .redirectErrorStream(true)
                        .start();
                Thread drainer = new Thread(() -> {
                    try { p.getInputStream().transferTo(OutputStream.nullOutputStream()); }
                    catch (Exception ignored) {}
                });
                drainer.setDaemon(true);
                drainer.start();
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    LoggingManager.info("[Ffmpeg] Using system ffmpeg: " + candidate);
                    return candidate;
                }
                p.destroyForcibly();
            } catch (Exception ignored) {}
        }
        LoggingManager.error("[Ffmpeg] ffmpeg not found (no download succeeded, no system binary)");
        return null;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).contains("mac");
    }

    private static @Nullable Platform detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ENGLISH);
        boolean isArm = arch.contains("aarch64") || arch.contains("arm64") || arch.equals("arm");

        if (os.contains("win")) {
            if (isArm) return null;
            return new Platform("windows-x64",
                    BTBN_BASE + "/ffmpeg-master-latest-win64-gpl.zip",
                    "ffmpeg.exe", "/bin/ffmpeg.exe", false);
        }
        if (os.contains("mac")) {
            return isArm
                    ? new Platform("macos-aarch64",
                        "https://www.osxexperts.net/ffmpeg71arm.zip",
                        "ffmpeg", "ffmpeg", false)
                    : new Platform("macos-x64",
                        "https://evermeet.cx/ffmpeg/getrelease/zip",
                        "ffmpeg", "ffmpeg", false);
        }
        return isArm
                ? new Platform("linux-aarch64",
                    BTBN_BASE + "/ffmpeg-master-latest-linuxarm64-gpl.tar.xz",
                    "ffmpeg", "/bin/ffmpeg", true)
                : new Platform("linux-x64",
                    BTBN_BASE + "/ffmpeg-master-latest-linux64-gpl.tar.xz",
                    "ffmpeg", "/bin/ffmpeg", true);
    }

    private static final class Platform {
        final String key;
        final String url;
        final String binaryName;
        final String entrySuffix;
        final boolean isTarXz;

        Platform(String key, String url, String binaryName, String entrySuffix, boolean isTarXz) {
            this.key = key;
            this.url = url;
            this.binaryName = binaryName;
            this.entrySuffix = entrySuffix;
            this.isTarXz = isTarXz;
        }
    }
}
