package com.yagay.alwaysforeground;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class GuardConfig {
    private static volatile SharedPreferences prefs;

    private GuardConfig() {}

    static void initialize(SharedPreferences remote) {
        prefs = remote;
    }

    static boolean bool(String key) {
        SharedPreferences p = prefs;
        if (p == null) return ConfigKeys.defaultBoolean(key);
        try {
            return p.getBoolean(key, ConfigKeys.defaultBoolean(key));
        } catch (Throwable ignored) {
            return ConfigKeys.defaultBoolean(key);
        }
    }

    static int integer(String key) {
        SharedPreferences p = prefs;
        int fallback = ConfigKeys.defaultInt(key);
        if (p == null) return fallback;
        try {
            return p.getInt(key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    static String string(String key) {
        SharedPreferences p = prefs;
        if (p == null) return "";
        try {
            String value = p.getString(key, "");
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    static boolean enabled() {
        return bool(ConfigKeys.MASTER_ENABLED);
    }

    static Set<String> targetPackages() {
        String raw = string(ConfigKeys.TARGET_PACKAGES);
        if (raw.isBlank()) return Collections.emptySet();

        HashSet<String> result = new HashSet<>();
        for (String line : raw.split("\n")) {
            String pkg = line.trim();
            if (!pkg.isEmpty()) result.add(pkg);
        }
        return result;
    }

    static boolean isTargetPackage(String packageName) {
        return packageName != null
                && enabled()
                && targetPackages().contains(packageName);
    }

    static int windowForm() {
        return ConfigKeys.sanitizeForm(integer(ConfigKeys.SMALL_WINDOW_FORM));
    }

    static int windowWidth() {
        return ConfigKeys.sanitizePercent(
                integer(ConfigKeys.SMALL_WINDOW_WIDTH), 58);
    }

    static int windowHeight() {
        return ConfigKeys.sanitizePercent(
                integer(ConfigKeys.SMALL_WINDOW_HEIGHT), 66);
    }
}
