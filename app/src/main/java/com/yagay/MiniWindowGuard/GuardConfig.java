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

    static int containerInitialScale() {
        return ConfigKeys.sanitizePercent(
                integer(ConfigKeys.CONTAINER_WIDTH),
                58);
    }

    static int containerWidth() {
        return containerInitialScale();
    }

    static int containerHeight() {
        // OxygenOS may fail to produce the first VirtualDisplay frame when
        // initial width/height percentages differ. The first attach therefore
        // always uses the same scale on both axes. Once the window is alive,
        // interactive resize remains completely free-form.
        return containerInitialScale();
    }
}
