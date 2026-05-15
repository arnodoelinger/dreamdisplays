package com.dreamdisplays.meta;

import com.dreamdisplays.util.GeneralUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.inotsleep.utils.logging.LoggingManager;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@NullMarked
public final class UpdateCheck {

    private static final String API =
            "https://api.github.com/repos/arsmotorin/dreamdisplays/releases/latest";

    private static volatile boolean checked = false;
    private static volatile boolean updateAvailable = false;
    private static volatile @Nullable String latestVersion = null;

    private UpdateCheck() {
    }

    public static boolean isUpdateAvailable() {
        if (!checked) startCheck();
        return updateAvailable;
    }

    public static boolean shouldShowArrow() {
        if (!checked) startCheck();
        String latest = latestVersion;
        if (latest == null) return false;
        String current = GeneralUtil.getModVersion();
        return !latest.equalsIgnoreCase(current);
    }

    public static String latestVersion() {
        String v = latestVersion;
        return v == null ? GeneralUtil.getModVersion() : v;
    }

    private static synchronized void startCheck() {
        if (checked) return;
        checked = true;
        Thread t = new Thread(UpdateCheck::doCheck, "dreamdisplays-update-check");
        t.setDaemon(true);
        t.start();
    }

    private static void doCheck() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(API).toURL().openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(8_000);
            conn.setRequestProperty("User-Agent",
                    "DreamDisplays/" + GeneralUtil.getModVersion() + " (+github.com/arsmotorin/dreamdisplays)");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code != 200) return;
            String body;
            try (InputStream in = conn.getInputStream()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonElement root = JsonParser.parseString(body);
            String tag = null;
            if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                tag = optString(obj, "tag_name");
                if (tag == null) tag = optString(obj, "name");
            } else if (root.isJsonArray()) {
                JsonArray arr = root.getAsJsonArray();
                if (!arr.isEmpty() && arr.get(0).isJsonObject()) {
                    tag = optString(arr.get(0).getAsJsonObject(), "tag_name");
                }
            }
            if (tag == null) return;
            if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
            latestVersion = tag;
            if (compareVersions(tag, GeneralUtil.getModVersion()) > 0) {
                updateAvailable = true;
            }
        } catch (Exception e) {
            LoggingManager.warn("Update check failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static @Nullable String optString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int compareVersions(String a, String b) {
        try {
            String[] aa = a.split("[.\\-+]");
            String[] bb = b.split("[.\\-+]");
            int n = Math.min(aa.length, bb.length);
            for (int i = 0; i < n; i++) {
                if (aa[i].equals(bb[i])) continue;
                try {
                    int ai = Integer.parseInt(aa[i]);
                    int bi = Integer.parseInt(bb[i]);
                    if (ai != bi) return Integer.compare(ai, bi);
                } catch (NumberFormatException e) {
                    return aa[i].compareTo(bb[i]);
                }
            }
            return Integer.compare(aa.length, bb.length);
        } catch (Exception e) {
            return a.compareTo(b);
        }
    }
}
