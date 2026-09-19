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
    static final String SYSTEM_KEEP_CONTAINER_RESUMED = "system_keep_container_resumed";
    static final String SYSTEM_KEEP_CONTAINER_VISIBLE = "system_keep_container_visible";
    static final String SYSTEM_BLOCK_REMOVE_KILL = "system_block_remove_kill";

    static final String AUTO_CONTAINER = "auto_container";
    static final String CONTAINER_DEFAULT_STATE = "container_default_state";
    static final String CONTAINER_WIDTH = "container_width";
    static final String CONTAINER_HEIGHT = "container_height";
    static final String CONTAINER_ALWAYS_ON_TOP = "container_always_on_top";

    static final String CONTAINER_COMMAND_PACKAGE = "container_command_package";
    static final String CONTAINER_COMMAND_STATE = "container_command_state";
    static final String CONTAINER_COMMAND_SEQ = "container_command_seq";

    static final String DIAGNOSTICS_ACTIVE = "diagnostics_active";
    static final String DIAGNOSTICS_STARTED_AT = "diagnostics_started_at";


    static final int STATE_WINDOW = 1;
    static final int STATE_ICON = 2;
    static final int STATE_HIDDEN = 3;
    static final int STATE_RELEASED = 4;

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
                    SYSTEM_KEEP_CONTAINER_RESUMED,
                    SYSTEM_KEEP_CONTAINER_VISIBLE,
                    SYSTEM_BLOCK_REMOVE_KILL,
                    AUTO_CONTAINER,
                    CONTAINER_ALWAYS_ON_TOP -> true;
            default -> false;
        };
    }

    static int defaultInt(String key) {
        return switch (key) {
            case CONTAINER_DEFAULT_STATE -> STATE_WINDOW;
            case CONTAINER_WIDTH -> 58;
            case CONTAINER_HEIGHT -> 66;
            default -> 0;
        };
    }

    static int sanitizeState(int value) {
        return value >= STATE_WINDOW && value <= STATE_RELEASED
                ? value : STATE_WINDOW;
    }

    static int sanitizePercent(int value, int fallback) {
        return value >= 30 && value <= 95 ? value : fallback;
    }
}
