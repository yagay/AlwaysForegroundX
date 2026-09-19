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

    static int getSmallWindowForm() {
        SharedPreferences prefs = remotePreferences;
        if (prefs == null) return ModeConfig.DEFAULT_SMALL_WINDOW_FORM;
        try {
            int form = prefs.getInt(
                    ModeConfig.KEY_SMALL_WINDOW_FORM,
                    ModeConfig.DEFAULT_SMALL_WINDOW_FORM);
            return ModeConfig.isValidSmallWindowForm(form)
                    ? form : ModeConfig.DEFAULT_SMALL_WINDOW_FORM;
        } catch (Throwable ignored) {
            return ModeConfig.DEFAULT_SMALL_WINDOW_FORM;
        }
    }

    static int getSmallWindowWidthPercent() {
        SharedPreferences prefs = remotePreferences;
        if (prefs == null) return ModeConfig.DEFAULT_SMALL_WINDOW_WIDTH;
        try {
            return ModeConfig.clampPercent(
                    prefs.getInt(
                            ModeConfig.KEY_SMALL_WINDOW_WIDTH,
                            ModeConfig.DEFAULT_SMALL_WINDOW_WIDTH),
                    ModeConfig.DEFAULT_SMALL_WINDOW_WIDTH);
        } catch (Throwable ignored) {
            return ModeConfig.DEFAULT_SMALL_WINDOW_WIDTH;
        }
    }

    static int getSmallWindowHeightPercent() {
        SharedPreferences prefs = remotePreferences;
        if (prefs == null) return ModeConfig.DEFAULT_SMALL_WINDOW_HEIGHT;
        try {
            return ModeConfig.clampPercent(
                    prefs.getInt(
                            ModeConfig.KEY_SMALL_WINDOW_HEIGHT,
                            ModeConfig.DEFAULT_SMALL_WINDOW_HEIGHT),
                    ModeConfig.DEFAULT_SMALL_WINDOW_HEIGHT);
        } catch (Throwable ignored) {
            return ModeConfig.DEFAULT_SMALL_WINDOW_HEIGHT;
        }
    }

    static String getRootBridgeToken() {
        SharedPreferences prefs = remotePreferences;
        if (prefs == null) return "";
        try {
            String token = prefs.getString(ModeConfig.KEY_ROOT_BRIDGE_TOKEN, "");
            return token == null ? "" : token;
        } catch (Throwable ignored) {
            return "";
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
}
