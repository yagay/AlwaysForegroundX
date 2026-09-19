package com.yagay.MiniWindowGuard;

final class ConfigKeys {
    static final String REMOTE_GROUP = "guard_config";
    static final String LOCAL_PREFS = "guard_local";

    static final String MASTER_ENABLED = "master_enabled";
    static final String ENGINE_AUTO_RELOAD = "engine_auto_reload";
    static final String ENGINE_RELOAD_SEQ = "engine_reload_seq";

    static final String SYSTEM_IMPORTANCE_TOP = "system_importance_top";
    static final String SYSTEM_HAS_RESUMED = "system_has_resumed";
    static final String SYSTEM_KEEP_CONTAINER_RESUMED =
            "system_keep_container_resumed";
    static final String SYSTEM_KEEP_CONTAINER_VISIBLE =
            "system_keep_container_visible";
    static final String SYSTEM_BLOCK_REMOVE_KILL =
            "system_block_remove_kill";

    static final String AUTO_CONTAINER = "auto_container";
    static final String CONTAINER_WIDTH = "container_width";
    static final String CONTAINER_HEIGHT = "container_height";

    static final String CONTAINER_COMMAND_PACKAGE =
            "container_command_package";
    static final String CONTAINER_COMMAND_STATE =
            "container_command_state";
    static final String CONTAINER_COMMAND_SEQ =
            "container_command_seq";

    static final String DIAGNOSTICS_ACTIVE = "diagnostics_active";
    static final String DIAGNOSTICS_STARTED_AT =
            "diagnostics_started_at";

    static final int STATE_WINDOW = 1;
    static final int STATE_ICON = 2;
    static final int STATE_HIDDEN = 3;
    static final int STATE_RELEASED = 4;

    private ConfigKeys() {}

    static boolean defaultBoolean(String key) {
        return switch (key) {
            case MASTER_ENABLED,
                    ENGINE_AUTO_RELOAD,
                    SYSTEM_IMPORTANCE_TOP,
                    SYSTEM_HAS_RESUMED,
                    SYSTEM_KEEP_CONTAINER_RESUMED,
                    SYSTEM_KEEP_CONTAINER_VISIBLE,
                    SYSTEM_BLOCK_REMOVE_KILL,
                    AUTO_CONTAINER -> true;
            default -> false;
        };
    }

    static int defaultInt(String key) {
        return switch (key) {
            case CONTAINER_WIDTH -> 58;
            case CONTAINER_HEIGHT -> 66;
            default -> 0;
        };
    }

    static int sanitizeState(int value) {
        return value >= STATE_WINDOW && value <= STATE_RELEASED
                ? value
                : STATE_WINDOW;
    }

    static int sanitizePercent(int value, int fallback) {
        return value >= 30 && value <= 95
                ? value
                : fallback;
    }
}
