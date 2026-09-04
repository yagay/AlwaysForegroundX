package com.jieei.alwaysforeground;

public final class ModeConfig {
    public static final int MODE_STANDARD = 1;
    public static final int MODE_ENHANCED = 2;
    public static final int MODE_STRONG = 3;

    public static final String LOCAL_PREFS = "config";
    public static final String REMOTE_GROUP = "config";
    public static final String KEY_MODE = "mode";
    public static final String KEY_DIAGNOSTICS_ACTIVE = "diagnostics_active";
    public static final String KEY_DIAGNOSTICS_TARGET = "diagnostics_target";
    public static final int DEFAULT_MODE = MODE_STANDARD;

    private ModeConfig() {}

    public static boolean isValid(int mode) {
        return mode >= MODE_STANDARD && mode <= MODE_STRONG;
    }
}
