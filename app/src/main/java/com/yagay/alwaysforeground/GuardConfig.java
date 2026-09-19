package com.yagay.alwaysforeground;

import android.content.SharedPreferences;

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

    static int windowForm() {
        return ConfigKeys.sanitizeForm(integer(ConfigKeys.SMALL_WINDOW_FORM));
    }

    static int windowWidth() {
        return ConfigKeys.sanitizePercent(
                integer(ConfigKeys.SMALL_WINDOW_WIDTH),
                ConfigKeys.defaultInt(ConfigKeys.SMALL_WINDOW_WIDTH));
    }

    static int windowHeight() {
        return ConfigKeys.sanitizePercent(
                integer(ConfigKeys.SMALL_WINDOW_HEIGHT),
                ConfigKeys.defaultInt(ConfigKeys.SMALL_WINDOW_HEIGHT));
    }

    static int backgroundConfirmMs() {
        return ConfigKeys.sanitizeDelay(
                integer(ConfigKeys.BACKGROUND_CONFIRM_MS),
                ConfigKeys.DEFAULT_BACKGROUND_CONFIRM_MS);
    }

    static int mediaResumeDelayMs() {
        return ConfigKeys.sanitizeDelay(
                integer(ConfigKeys.MEDIA_RESUME_DELAY_MS),
                ConfigKeys.DEFAULT_MEDIA_RESUME_DELAY_MS);
    }

    static int echoGuardMs() {
        return ConfigKeys.sanitizeDelay(
                integer(ConfigKeys.ECHO_GUARD_MS),
                ConfigKeys.DEFAULT_ECHO_GUARD_MS);
    }
}
