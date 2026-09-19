package com.yagay.MiniWindowGuard;

final class ConfigKeys {
    static final String REMOTE_GROUP = "guard_config";
    static final String LOCAL_PREFS = "guard_local";

    static final String MASTER_ENABLED = "master_enabled";
    static final String TARGET_PACKAGES = "target_packages";

    static final String ROOT_KEEP_ALIVE = "root_keep_alive";
    static final String ROOT_DOZE_WHITELIST = "root_doze_whitelist";
    static final String ROOT_STANDBY_ACTIVE = "root_standby_active";
    static final String ROOT_BACKGROUND_APPOPS = "root_background_appops";
    static final String ROOT_NETWORK_WHITELIST = "root_network_whitelist";
    static final String ROOT_WAKELOCK = "root_wakelock";

    static final String SYSTEM_IMPORTANCE_TOP = "system_importance_top";
    static final String SYSTEM_HAS_RESUMED = "system_has_resumed";
    static final String SYSTEM_KEEP_MINI_RESUMED = "system_keep_mini_resumed";
    static final String SYSTEM_OPLUS_MULTI_RESUME = "system_oplus_multi_resume";
    static final String SYSTEM_FORCE_ZOOM_SUPPORT = "system_force_zoom_support";

    static final String SMALL_WINDOW_FORM = "small_window_form";
    static final String SMALL_WINDOW_WIDTH = "small_window_width";
    static final String SMALL_WINDOW_HEIGHT = "small_window_height";
    static final String AOSP_FREEFORM_FALLBACK = "aosp_freeform_fallback";

    static final String DIAGNOSTICS_ACTIVE = "diagnostics_active";
    static final String DIAGNOSTICS_STARTED_AT = "diagnostics_started_at";

    static final int FORM_WINDOW = 1;
    static final int FORM_ICON = 2;
    static final int FORM_HIDDEN = 3;

    private ConfigKeys() {}

    static boolean defaultBoolean(String key) {
        return switch (key) {
            case MASTER_ENABLED,
                    ROOT_KEEP_ALIVE,
                    ROOT_DOZE_WHITELIST,
                    ROOT_STANDBY_ACTIVE,
                    ROOT_BACKGROUND_APPOPS,
                    ROOT_NETWORK_WHITELIST,
                    ROOT_WAKELOCK,
                    SYSTEM_IMPORTANCE_TOP,
                    SYSTEM_HAS_RESUMED,
                    SYSTEM_KEEP_MINI_RESUMED,
                    SYSTEM_OPLUS_MULTI_RESUME,
                    SYSTEM_FORCE_ZOOM_SUPPORT,
                    AOSP_FREEFORM_FALLBACK -> true;
            default -> false;
        };
    }

    static int defaultInt(String key) {
        return switch (key) {
            case SMALL_WINDOW_FORM -> FORM_WINDOW;
            case SMALL_WINDOW_WIDTH -> 58;
            case SMALL_WINDOW_HEIGHT -> 66;
            default -> 0;
        };
    }

    static int sanitizeForm(int value) {
        return value >= FORM_WINDOW && value <= FORM_HIDDEN ? value : FORM_WINDOW;
    }

    static int sanitizePercent(int value, int fallback) {
        return value >= 30 && value <= 100 ? value : fallback;
    }
}
