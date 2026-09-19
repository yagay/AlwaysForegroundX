package com.yagay.alwaysforeground;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.SecureRandom;
import java.util.UUID;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class GuardApp extends Application {
    private static final String TAG = "MiniWindowGuard";

    private static final String[] BOOLEAN_KEYS = {
            ConfigKeys.MASTER_ENABLED,
            ConfigKeys.ROOT_KEEP_ALIVE,
            ConfigKeys.ROOT_DOZE_WHITELIST,
            ConfigKeys.ROOT_STANDBY_ACTIVE,
            ConfigKeys.ROOT_BACKGROUND_APPOPS,
            ConfigKeys.ROOT_NETWORK_WHITELIST,
            ConfigKeys.ROOT_WAKELOCK,
            ConfigKeys.SPOOF_PROCESS_IMPORTANCE,
            ConfigKeys.SPOOF_PROCESS_LIFECYCLE,
            ConfigKeys.SPOOF_WINDOW_FOCUS,
            ConfigKeys.SPOOF_SCREEN_INTERACTIVE,
            ConfigKeys.SPOOF_KEYGUARD,
            ConfigKeys.SPOOF_BACKGROUND_RESTRICTION,
            ConfigKeys.SPOOF_POWER_STATE,
            ConfigKeys.MEDIA_CONTINUITY,
            ConfigKeys.MEDIA_ECHO_GUARD,
            ConfigKeys.AUTO_SMALL_WINDOW,
            ConfigKeys.AOSP_FREEFORM_FALLBACK,
            ConfigKeys.DIAGNOSTICS_ACTIVE
    };

    private static final String[] INT_KEYS = {
            ConfigKeys.SMALL_WINDOW_FORM,
            ConfigKeys.SMALL_WINDOW_WIDTH,
            ConfigKeys.SMALL_WINDOW_HEIGHT,
            ConfigKeys.BACKGROUND_CONFIRM_MS,
            ConfigKeys.MEDIA_RESUME_DELAY_MS,
            ConfigKeys.ECHO_GUARD_MS
    };

    private static volatile GuardApp instance;
    private static volatile XposedService service;
    private static volatile String frameworkName = "";

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        instance = this;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureBridgeToken();

        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService bound) {
                service = bound;
                try {
                    frameworkName = bound.getFrameworkName();
                } catch (Throwable ignored) {
                    frameworkName = "LSPosed";
                }
                syncAll();
                Log.i(TAG, "LSPosed service connected: " + frameworkName);
            }

            @Override
            public void onServiceDied(XposedService dead) {
                if (service == dead) service = null;
                frameworkName = "";
                Log.w(TAG, "LSPosed service disconnected");
            }
        });
    }

    static boolean isXposedServiceConnected() {
        return service != null;
    }

    static String getFrameworkName() {
        return frameworkName.isEmpty() ? "未连接" : frameworkName;
    }

    static SharedPreferences localPrefs() {
        GuardApp app = instance;
        if (app == null) throw new IllegalStateException("GuardApp not initialized");
        return app.getSharedPreferences(ConfigKeys.LOCAL_PREFS, MODE_PRIVATE);
    }

    static boolean getBoolean(String key) {
        return localPrefs().getBoolean(key, ConfigKeys.defaultBoolean(key));
    }

    static int getInt(String key) {
        return localPrefs().getInt(key, ConfigKeys.defaultInt(key));
    }

    static String getString(String key) {
        String value = localPrefs().getString(key, "");
        return value == null ? "" : value;
    }

    static void putBoolean(String key, boolean value) {
        localPrefs().edit().putBoolean(key, value).apply();
        syncAll();
    }

    static void putInt(String key, int value) {
        localPrefs().edit().putInt(key, value).apply();
        syncAll();
    }

    static void putString(String key, String value) {
        localPrefs().edit().putString(key, value == null ? "" : value).apply();
        syncAll();
    }

    static void resetDefaults() {
        String token = ensureBridgeToken();
        localPrefs().edit().clear().putString(ConfigKeys.ROOT_BRIDGE_TOKEN, token).commit();
        syncAll();
    }

    static String bridgeToken() {
        return ensureBridgeToken();
    }

    static synchronized boolean syncAll() {
        XposedService current = service;
        if (current == null) return false;

        try {
            SharedPreferences.Editor editor =
                    current.getRemotePreferences(ConfigKeys.REMOTE_GROUP).edit();

            for (String key : BOOLEAN_KEYS) {
                editor.putBoolean(key, getBoolean(key));
            }
            for (String key : INT_KEYS) {
                editor.putInt(key, getInt(key));
            }

            editor.putString(ConfigKeys.ROOT_BRIDGE_TOKEN, ensureBridgeToken());
            editor.putString(
                    ConfigKeys.DIAGNOSTICS_TARGET,
                    getString(ConfigKeys.DIAGNOSTICS_TARGET));

            return editor.commit();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to sync remote preferences", t);
            return false;
        }
    }

    private static String ensureBridgeToken() {
        SharedPreferences prefs = localPrefs();
        String existing = prefs.getString(ConfigKeys.ROOT_BRIDGE_TOKEN, "");
        if (existing != null && !existing.isEmpty()) return existing;

        String token = UUID.randomUUID()
                + "-"
                + Long.toHexString(new SecureRandom().nextLong());
        prefs.edit().putString(ConfigKeys.ROOT_BRIDGE_TOKEN, token).commit();
        return token;
    }
}
