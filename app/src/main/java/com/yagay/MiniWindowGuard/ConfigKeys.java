package com.yagay.MiniWindowGuard;

final class ConfigKeys {
    static final String REMOTE_GROUP = "guard_config";
    static final String LOCAL_PREFS = "guard_local";

    static final String MASTER_ENABLED = "master_enabled";
    static final String ENGINE_AUTO_RELOAD = "engine_auto_reload";

    static final String OPLUS_FORCE_SUPPORT =
            "oplus_force_support";

    static final String SYSTEM_IMPORTANCE_TOP =
            "system_importance_top";
    static final String SYSTEM_HAS_RESUMED =
            "system_has_resumed";
    static final String SYSTEM_BLOCK_REMOVE_KILL =
            "system_block_remove_kill";

    static final String OPLUS_COMMAND_PACKAGE =
            "oplus_command_package";
    static final String OPLUS_COMMAND_STATE =
            "oplus_command_state";
    static final String OPLUS_COMMAND_SEQ =
            "oplus_command_seq";

    static final String DIAGNOSTICS_ACTIVE =
            "diagnostics_active";
    static final String DIAGNOSTICS_STARTED_AT =
            "diagnostics_started_at";

    static final int STATE_WINDOW = 1;
    static final int STATE_RELEASED = 4;

    private ConfigKeys() {}

    static boolean defaultBoolean(String key) {
        return switch (key) {
            case MASTER_ENABLED,
                    ENGINE_AUTO_RELOAD,
                    OPLUS_FORCE_SUPPORT,
                    SYSTEM_IMPORTANCE_TOP,
                    SYSTEM_HAS_RESUMED,
                    SYSTEM_BLOCK_REMOVE_KILL -> true;
            default -> false;
        };
    }

    static int defaultInt(String key) {
        return 0;
    }

    static int sanitizeState(int value) {
        return value == STATE_RELEASED
                ? STATE_RELEASED
                : STATE_WINDOW;
    }
}
