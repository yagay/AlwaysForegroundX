package com.yagay.alwaysforeground;

final class ConfigKeys {
    static final String REMOTE_GROUP = "guard_config";
    static final String LOCAL_PREFS = "guard_local";

    static final String MASTER_ENABLED = "master_enabled";

    static final String ROOT_KEEP_ALIVE = "root_keep_alive";
    static final String ROOT_DOZE_WHITELIST = "root_doze_whitelist";
    static final String ROOT_STANDBY_ACTIVE = "root_standby_active";
    static final String ROOT_BACKGROUND_APPOPS = "root_background_appops";
    static final String ROOT_NETWORK_WHITELIST = "root_network_whitelist";
    static final String ROOT_WAKELOCK = "root_wakelock";

    static final String SPOOF_PROCESS_IMPORTANCE = "spoof_process_importance";
    static final String SPOOF_PROCESS_LIFECYCLE = "spoof_process_lifecycle";
    static final String SPOOF_WINDOW_FOCUS = "spoof_window_focus";
    static final String SPOOF_SCREEN_INTERACTIVE = "spoof_screen_interactive";
    static final String SPOOF_KEYGUARD = "spoof_keyguard";
    static final String SPOOF_BACKGROUND_RESTRICTION = "spoof_background_restriction";
    static final String SPOOF_POWER_STATE = "spoof_power_state";

    static final String MEDIA_CONTINUITY = "media_continuity";
    static final String MEDIA_ECHO_GUARD = "media_echo_guard";

    static final String AUTO_SMALL_WINDOW = "auto_small_window";
    static final String SMALL_WINDOW_FORM = "small_window_form";
    static final String SMALL_WINDOW_WIDTH = "small_window_width";
    static final String SMALL_WINDOW_HEIGHT = "small_window_height";
    static final String AOSP_FREEFORM_FALLBACK = "aosp_freeform_fallback";

    static final String BACKGROUND_CONFIRM_MS = "background_confirm_ms";
    static final String MEDIA_RESUME_DELAY_MS = "media_resume_delay_ms";
    static final String ECHO_GUARD_MS = "echo_guard_ms";

    static final String ROOT_BRIDGE_TOKEN = "root_bridge_token";
    static final String DIAGNOSTICS_ACTIVE = "diagnostics_active";
    static final String DIAGNOSTICS_TARGET = "diagnostics_target";

    static final int FORM_WINDOW = 1;
    static final int FORM_ICON = 2;
    static final int FORM_HIDDEN = 3;

    static final int DEFAULT_BACKGROUND_CONFIRM_MS = 450;
    static final int DEFAULT_MEDIA_RESUME_DELAY_MS = 900;
    static final int DEFAULT_ECHO_GUARD_MS = 1600;

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
                    SPOOF_PROCESS_IMPORTANCE,
                    SPOOF_PROCESS_LIFECYCLE,
                    SPOOF_BACKGROUND_RESTRICTION,
                    SPOOF_POWER_STATE,
                    MEDIA_CONTINUITY,
                    MEDIA_ECHO_GUARD,
                    AUTO_SMALL_WINDOW,
                    AOSP_FREEFORM_FALLBACK -> true;
            case SPOOF_WINDOW_FOCUS,
                    SPOOF_SCREEN_INTERACTIVE,
                    SPOOF_KEYGUARD -> false;
            default -> false;
        };
    }

    static int defaultInt(String key) {
        return switch (key) {
            case SMALL_WINDOW_FORM -> FORM_WINDOW;
            case SMALL_WINDOW_WIDTH -> 58;
            case SMALL_WINDOW_HEIGHT -> 66;
            case BACKGROUND_CONFIRM_MS -> DEFAULT_BACKGROUND_CONFIRM_MS;
            case MEDIA_RESUME_DELAY_MS -> DEFAULT_MEDIA_RESUME_DELAY_MS;
            case ECHO_GUARD_MS -> DEFAULT_ECHO_GUARD_MS;
            default -> 0;
        };
    }

    static int sanitizeForm(int value) {
        return value >= FORM_WINDOW && value <= FORM_HIDDEN ? value : FORM_WINDOW;
    }

    static int sanitizePercent(int value, int fallback) {
        return value >= 30 && value <= 100 ? value : fallback;
    }

    static int sanitizeDelay(int value, int fallback) {
        return value >= 100 && value <= 5000 ? value : fallback;
    }
}
