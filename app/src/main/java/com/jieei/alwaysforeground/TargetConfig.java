package com.jieei.alwaysforeground;

import android.content.SharedPreferences;

final class TargetConfig {
    private static volatile SharedPreferences remotePreferences;

    private TargetConfig() {}

    static void initialize(SharedPreferences preferences) {
        remotePreferences = preferences;
    }

    static int getMode() {
        SharedPreferences prefs = remotePreferences;
        if (prefs == null) return ModeConfig.DEFAULT_MODE;
        try {
            int mode = prefs.getInt(ModeConfig.KEY_MODE, ModeConfig.DEFAULT_MODE);
            return ModeConfig.isValid(mode) ? mode : ModeConfig.DEFAULT_MODE;
        } catch (Throwable ignored) {
            return ModeConfig.DEFAULT_MODE;
        }
    }

    static boolean isDiagnosticsActiveFor(String packageName) {
        SharedPreferences prefs = remotePreferences;
        if (prefs == null) return false;
        try {
            if (!prefs.getBoolean(ModeConfig.KEY_DIAGNOSTICS_ACTIVE, false)) return false;
            String target = prefs.getString(ModeConfig.KEY_DIAGNOSTICS_TARGET, "");
            return target == null || target.isEmpty() || target.equals(packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static long getDiagnosticsStartMs() {
        SharedPreferences prefs = remotePreferences;
        if (prefs == null) return 0L;
        try {
            return prefs.getLong(ModeConfig.KEY_DIAGNOSTICS_START_MS, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
