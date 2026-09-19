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

    public static final String KEY_SMALL_WINDOW_FORM = "small_window_form";
    public static final String KEY_SMALL_WINDOW_WIDTH = "small_window_width";
    public static final String KEY_SMALL_WINDOW_HEIGHT = "small_window_height";

    public static final int SMALL_WINDOW_FORM_WINDOW = 1;
    public static final int SMALL_WINDOW_FORM_ICON = 2;
    public static final int SMALL_WINDOW_FORM_HIDDEN = 3;

    public static final int DEFAULT_MODE = MODE_STANDARD;
    public static final int DEFAULT_SMALL_WINDOW_FORM = SMALL_WINDOW_FORM_WINDOW;
    public static final int DEFAULT_SMALL_WINDOW_WIDTH = 58;
    public static final int DEFAULT_SMALL_WINDOW_HEIGHT = 66;

    private ModeConfig() {}

    public static boolean isValid(int mode) {
        return mode >= MODE_STANDARD && mode <= MODE_STRONG;
    }

    public static boolean isValidSmallWindowForm(int form) {
        return form >= SMALL_WINDOW_FORM_WINDOW && form <= SMALL_WINDOW_FORM_HIDDEN;
    }

    public static int clampPercent(int value, int fallback) {
        if (value < 30 || value > 100) return fallback;
        return value;
    }
}
