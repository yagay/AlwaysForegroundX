package com.yagay.MiniWindowGuard;

final class ConfigKeys {
    static final String REMOTE_GROUP = "guard_config";
    static final String LOCAL_PREFS = "guard_local";

    static final String MASTER_ENABLED = "master_enabled";
    static final String ENGINE_AUTO_RELOAD = "engine_auto_reload";
    static final String ENGINE_RELOAD_SEQ = "engine_reload_seq";

    static final String FOREGROUND_PACKAGES =
            "foreground_packages";
    static final String BACKGROUND_PLAYBACK_PACKAGES =
            "background_playback_packages";
    static final String BACKGROUND_PLAYBACK_MODES =
            "background_playback_modes";
    static final String FORCE_SUPPORT_PACKAGES =
            "force_support_packages";

    static final String SYSTEM_BLOCK_REMOVE_KILL =
            "system_block_remove_kill";

    static final String DIAGNOSTICS_ACTIVE =
            "diagnostics_active";
    static final String DIAGNOSTICS_STARTED_AT =
            "diagnostics_started_at";

    private ConfigKeys() {}

    static boolean defaultBoolean(String key) {
        return switch (key) {
            case MASTER_ENABLED,
                    ENGINE_AUTO_RELOAD,
                    SYSTEM_BLOCK_REMOVE_KILL -> true;
            default -> false;
        };
    }

    static int defaultInt(String key) {
        return 0;
    }
}
