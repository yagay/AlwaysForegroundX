package com.yagay.MiniWindowGuard;

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

    static Set<String> stringSet(String key) {
        SharedPreferences p = prefs;
        if (p == null) return Collections.emptySet();

        try {
            Set<String> value =
                    p.getStringSet(
                            key,
                            Collections.emptySet());
            return value == null
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(
                    new HashSet<>(value));
        } catch (Throwable ignored) {
            return Collections.emptySet();
        }
    }

    static boolean enabled() {
        return bool(ConfigKeys.MASTER_ENABLED);
    }

    static boolean foregroundPackage(String packageName) {
        return packageName != null
                && stringSet(
                ConfigKeys.FOREGROUND_PACKAGES)
                .contains(packageName);
    }

    static boolean smallWindowBackgroundPackage(
            String packageName
    ) {
        return packageName != null
                && stringSet(
                ConfigKeys.BACKGROUND_PLAYBACK_PACKAGES)
                .contains(packageName);
    }

    static boolean protectedWindowPackage(
            String packageName
    ) {
        return foregroundPackage(packageName)
                || smallWindowBackgroundPackage(
                packageName);
    }

    static boolean forceSupportPackage(String packageName) {
        return packageName != null
                && stringSet(
                ConfigKeys.FORCE_SUPPORT_PACKAGES)
                .contains(packageName);
    }
}
