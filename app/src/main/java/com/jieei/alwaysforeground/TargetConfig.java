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
}
