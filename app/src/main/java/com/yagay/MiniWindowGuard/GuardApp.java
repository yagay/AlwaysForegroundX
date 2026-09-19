package com.yagay.MiniWindowGuard;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.UserManager;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class GuardApp extends Application {
    private static final String TAG = "MiniWindowGuard";

    private static final String[] BOOLEAN_KEYS = {
            ConfigKeys.MASTER_ENABLED,
            ConfigKeys.ENGINE_AUTO_RELOAD,
            ConfigKeys.SYSTEM_IMPORTANCE_TOP,
            ConfigKeys.SYSTEM_HAS_RESUMED,
            ConfigKeys.SYSTEM_KEEP_CONTAINER_RESUMED,
            ConfigKeys.SYSTEM_KEEP_CONTAINER_VISIBLE,
            ConfigKeys.SYSTEM_BLOCK_REMOVE_KILL,
            ConfigKeys.AUTO_CONTAINER,
            ConfigKeys.DIAGNOSTICS_ACTIVE
    };

    private static final String[] INT_KEYS = {
            ConfigKeys.CONTAINER_WIDTH,
            ConfigKeys.CONTAINER_HEIGHT,
            ConfigKeys.ENGINE_RELOAD_SEQ,
            ConfigKeys.CONTAINER_COMMAND_STATE,
            ConfigKeys.CONTAINER_COMMAND_SEQ
    };

    private static volatile GuardApp instance;
    private static volatile XposedService service;
    private static volatile String frameworkName = "";
    private static final AtomicInteger commandSeq =
            new AtomicInteger();

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        instance = this;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashHandler();

        if (isUserUnlocked()) {
            commandSeq.set(localPrefs().getInt(
                    ConfigKeys.CONTAINER_COMMAND_SEQ,
                    0));
        } else {
            commandSeq.set(0);
        }

        XposedServiceHelper.registerListener(
                new XposedServiceHelper.OnServiceListener() {
                    @Override
                    public void onServiceBind(XposedService bound) {
                        service = bound;
                        try {
                            frameworkName =
                                    bound.getFrameworkName();
                        } catch (Throwable ignored) {
                            frameworkName = "LSPosed";
                        }

                        if (isUserUnlocked()) {
                            syncAll();
                        }

                        Log.i(
                                TAG,
                                "LSPosed service connected: "
                                        + frameworkName);
                    }

                    @Override
                    public void onServiceDied(
                            XposedService dead
                    ) {
                        if (service == dead) {
                            service = null;
                        }
                        frameworkName = "";
                        Log.w(
                                TAG,
                                "LSPosed service disconnected");
                    }
                });
    }

    private void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(
                (thread, throwable) -> {
                    CrashStore.record(
                            getApplicationContext(),
                            "uncaught:"
                                    + (thread == null
                                    ? "unknown"
                                    : thread.getName()),
                            throwable);

                    if (previous != null) {
                        previous.uncaughtException(
                                thread,
                                throwable);
                    }
                });
    }

    static boolean isXposedServiceConnected() {
        return service != null;
    }

    static boolean isUserUnlocked() {
        GuardApp app = instance;
        if (app == null) return false;

        try {
            UserManager manager =
                    app.getSystemService(
                            UserManager.class);
            return manager == null
                    || manager.isUserUnlocked();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static long getExpectedVersionCode() {
        GuardApp app = instance;
        if (app == null) return -1L;

        try {
            return app.getPackageManager()
                    .getPackageInfo(
                            app.getPackageName(),
                            0)
                    .getLongVersionCode();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    static List<String> getFrameworkScope() {
        XposedService current = service;
        if (current == null) {
            return Collections.emptyList();
        }

        try {
            List<String> scope =
                    current.getScope();
            return scope == null
                    ? Collections.emptyList()
                    : scope;
        } catch (Throwable t) {
            Log.w(
                    TAG,
                    "Failed to read LSPosed scope",
                    t);
            return Collections.emptyList();
        }
    }

    static boolean hasSystemScope() {
        return getFrameworkScope().contains("system");
    }

    static EngineStatusProvider.Status getEngineStatus() {
        return EngineStatusProvider.read(instance);
    }

    static long getLoadedEngineVersionCode() {
        return getEngineStatus().versionCode;
    }

    static long getEngineStartedAt() {
        return getEngineStatus().startedAt;
    }

    static int getEnginePid() {
        return getEngineStatus().pid;
    }

    static int getEngineHookCount() {
        return getEngineStatus().hookCount;
    }

    static long getBootstrapVersionCode() {
        return getEngineStatus().bootstrapVersionCode;
    }

    static boolean isHotReloadAvailable() {
        return getEngineStatus().hotReload;
    }

    static long getEngineGeneration() {
        return getEngineStatus().generation;
    }

    static String getEngineReloadMessage() {
        return getEngineStatus().reloadMessage;
    }

    static int getEngineActiveSessions() {
        return getEngineStatus().activeSessions;
    }

    static boolean isSystemEngineActive() {
        EngineStatusProvider.Status status =
                getEngineStatus();

        return service != null
                && hasSystemScope()
                && status.versionCode > 0
                && status.pid > 0
                && status.hookCount > 0
                && status.isFromCurrentBoot();
    }

    static boolean isSystemEngineCurrent() {
        long expected =
                getExpectedVersionCode();
        EngineStatusProvider.Status status =
                getEngineStatus();

        return isSystemEngineActive()
                && expected > 0
                && status.versionCode == expected;
    }

    static boolean isEngineUpdatePending() {
        return isSystemEngineActive()
                && !isSystemEngineCurrent();
    }

    static String getFrameworkName() {
        return frameworkName.isEmpty()
                ? "未连接"
                : frameworkName;
    }

    static SharedPreferences localPrefs() {
        GuardApp app = instance;
        if (app == null) {
            throw new IllegalStateException(
                    "GuardApp not initialized");
        }

        return app.getSharedPreferences(
                ConfigKeys.LOCAL_PREFS,
                MODE_PRIVATE);
    }

    static boolean getBoolean(String key) {
        return localPrefs().getBoolean(
                key,
                ConfigKeys.defaultBoolean(key));
    }

    static int getInt(String key) {
        return localPrefs().getInt(
                key,
                ConfigKeys.defaultInt(key));
    }

    static String getString(String key) {
        String value =
                localPrefs().getString(key, "");
        return value == null ? "" : value;
    }

    static void putBoolean(
            String key,
            boolean value
    ) {
        localPrefs()
                .edit()
                .putBoolean(key, value)
                .apply();
        syncAll();
    }

    static void putInt(
            String key,
            int value
    ) {
        localPrefs()
                .edit()
                .putInt(key, value)
                .apply();
        syncAll();
    }

    static void putString(
            String key,
            String value
    ) {
        localPrefs()
                .edit()
                .putString(
                        key,
                        value == null ? "" : value)
                .apply();
        syncAll();
    }

    static int getContainerState() {
        return ConfigKeys.sanitizeState(
                getInt(
                        ConfigKeys.CONTAINER_COMMAND_STATE));
    }

    static String getContainerPackage() {
        return getString(
                ConfigKeys.CONTAINER_COMMAND_PACKAGE);
    }

    static void sendContainerCommand(
            String packageName,
            int state
    ) {
        int safeState =
                ConfigKeys.sanitizeState(state);

        if (isUserUnlocked()) {
            commandSeq.accumulateAndGet(
                    getInt(
                            ConfigKeys.CONTAINER_COMMAND_SEQ),
                    Math::max);
        }

        int seq = commandSeq.incrementAndGet();

        localPrefs()
                .edit()
                .putString(
                        ConfigKeys.CONTAINER_COMMAND_PACKAGE,
                        packageName == null
                                ? ""
                                : packageName)
                .putInt(
                        ConfigKeys.CONTAINER_COMMAND_STATE,
                        safeState)
                .putInt(
                        ConfigKeys.CONTAINER_COMMAND_SEQ,
                        seq)
                .apply();

        syncAll();
    }


    static boolean requestEngineReload() {
        int seq = getInt(ConfigKeys.ENGINE_RELOAD_SEQ) + 1;

        localPrefs()
                .edit()
                .putInt(
                        ConfigKeys.ENGINE_RELOAD_SEQ,
                        seq)
                .apply();

        return syncAll();
    }

    static void resetDefaults() {
        localPrefs().edit().clear().commit();
        commandSeq.set(0);
        syncAll();
    }

    static synchronized boolean syncAll() {
        XposedService current = service;

        if (current == null || !isUserUnlocked()) {
            return false;
        }

        try {
            SharedPreferences.Editor editor =
                    current.getRemotePreferences(
                                    ConfigKeys.REMOTE_GROUP)
                            .edit();

            for (String key : BOOLEAN_KEYS) {
                editor.putBoolean(
                        key,
                        getBoolean(key));
            }

            for (String key : INT_KEYS) {
                editor.putInt(
                        key,
                        getInt(key));
            }

            editor.putString(
                    ConfigKeys.CONTAINER_COMMAND_PACKAGE,
                    getString(
                            ConfigKeys.CONTAINER_COMMAND_PACKAGE));

            editor.putString(
                    ConfigKeys.DIAGNOSTICS_STARTED_AT,
                    getString(
                            ConfigKeys.DIAGNOSTICS_STARTED_AT));

            return editor.commit();
        } catch (Throwable t) {
            Log.e(
                    TAG,
                    "Failed to sync remote preferences",
                    t);
            return false;
        }
    }
}
