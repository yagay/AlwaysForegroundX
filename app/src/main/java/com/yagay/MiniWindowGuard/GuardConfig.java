package com.yagay.MiniWindowGuard;

import android.content.SharedPreferences;

final class GuardConfig {
    private static volatile SharedPreferences prefs;

    private GuardConfig() {}

    static void initialize(SharedPreferences remote) {
        prefs = remote;
    }

    static boolean bool(String key) {
        SharedPreferences p = prefs;
        if (p == null) {
            return ConfigKeys.defaultBoolean(key);
        }

        try {
            return p.getBoolean(
                    key,
                    ConfigKeys.defaultBoolean(key));
        } catch (Throwable ignored) {
            return ConfigKeys.defaultBoolean(key);
        }
    }

    static int integer(String key) {
        SharedPreferences p = prefs;
        int fallback =
                ConfigKeys.defaultInt(key);

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
            String value =
                    p.getString(key, "");
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    static boolean enabled() {
        return bool(ConfigKeys.MASTER_ENABLED);
    }

    static int containerWidth() {
        return ConfigKeys.sanitizePercent(
                integer(ConfigKeys.CONTAINER_WIDTH),
                58);
    }

    static int containerHeight() {
        return ConfigKeys.sanitizePercent(
                integer(ConfigKeys.CONTAINER_HEIGHT),
                66);
    }

    static boolean fixedInternalDisplay() {
        return bool(ConfigKeys.FIXED_INTERNAL_DISPLAY);
    }

    static int internalDisplayScale() {
        return ConfigKeys.sanitizePercent(
                integer(ConfigKeys.INTERNAL_DISPLAY_SCALE),
                48);
    }

    static int outerMinWidthDp() {
        return clamp(
                integer(ConfigKeys.OUTER_MIN_WIDTH_DP),
                120,
                320);
    }

    static int outerMinHeightDp() {
        return clamp(
                integer(ConfigKeys.OUTER_MIN_HEIGHT_DP),
                160,
                480);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
