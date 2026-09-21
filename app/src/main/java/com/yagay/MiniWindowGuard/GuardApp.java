package com.yagay.MiniWindowGuard;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.UserManager;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class GuardApp extends Application {
    private static final String TAG = "MiniWindowGuard";

    private static final String[] BOOLEAN_KEYS = {
            ConfigKeys.MASTER_ENABLED,
            ConfigKeys.ENGINE_AUTO_RELOAD,
            ConfigKeys.SYSTEM_IMPORTANCE_TOP,
            ConfigKeys.SYSTEM_HAS_RESUMED,
            ConfigKeys.SYSTEM_BLOCK_REMOVE_KILL,
            ConfigKeys.DIAGNOSTICS_ACTIVE
    };

    private static final String[] INT_KEYS = {
            ConfigKeys.ENGINE_RELOAD_SEQ
    };

    private static final String[] STRING_SET_KEYS = {
            ConfigKeys.FOREGROUND_PACKAGES,
            ConfigKeys.BACKGROUND_PLAYBACK_PACKAGES,
            ConfigKeys.FORCE_SUPPORT_PACKAGES
    };

    private static final String MANAGED_PLAYBACK_SCOPES =
            "managed_playback_scopes";

    private static final Set<String> pendingScopeRequests =
            ConcurrentHashMap.newKeySet();

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
        installCrashHandler();

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
                            reconcileBackgroundPlaybackScopes();
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

    static Set<String> getStringSet(String key) {
        Set<String> value =
                localPrefs().getStringSet(
                        key,
                        Collections.emptySet());
        return value == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(
                new HashSet<>(value));
    }

    static void putStringSet(
            String key,
            Set<String> value
    ) {
        localPrefs()
                .edit()
                .putStringSet(
                        key,
                        value == null
                                ? Collections.emptySet()
                                : new HashSet<>(value))
                .apply();
        syncAll();

        if (ConfigKeys.BACKGROUND_PLAYBACK_PACKAGES
                .equals(key)) {
            reconcileBackgroundPlaybackScopes();
        }
    }

    static boolean packageSelected(
            String key,
            String packageName
    ) {
        return packageName != null
                && getStringSet(key)
                .contains(packageName);
    }

    static boolean hasPlaybackScope(
            String packageName
    ) {
        return packageName != null
                && getFrameworkScope()
                .contains(packageName);
    }

    private static Set<String> managedPlaybackScopes() {
        Set<String> value =
                localPrefs().getStringSet(
                        MANAGED_PLAYBACK_SCOPES,
                        Collections.emptySet());

        return value == null
                ? new HashSet<>()
                : new HashSet<>(value);
    }

    private static void saveManagedPlaybackScopes(
            Set<String> value
    ) {
        localPrefs()
                .edit()
                .putStringSet(
                        MANAGED_PLAYBACK_SCOPES,
                        value == null
                                ? Collections.emptySet()
                                : new HashSet<>(value))
                .apply();
    }

    static synchronized void reconcileBackgroundPlaybackScopes() {
        XposedService current = service;

        if (current == null
                || !isUserUnlocked()) {
            return;
        }

        Set<String> selected =
                new HashSet<>(
                        getStringSet(
                                ConfigKeys
                                        .BACKGROUND_PLAYBACK_PACKAGES));

        Set<String> actual;

        try {
            actual =
                    new HashSet<>(
                            current.getScope());
        } catch (Throwable t) {
            Log.w(
                    TAG,
                    "Failed to read playback scopes",
                    t);
            return;
        }

        Set<String> managed =
                managedPlaybackScopes();

        for (String packageName : selected) {
            if (packageName == null
                    || packageName.isBlank()
                    || actual.contains(packageName)
                    || !pendingScopeRequests
                    .add(packageName)) {
                continue;
            }

            try {
                current.requestScope(
                        packageName,
                        new XposedService
                                .OnScopeEventListener() {
                            @Override
                            public void onScopeRequestApproved(
                                    String approved
                            ) {
                                pendingScopeRequests.remove(
                                        approved);

                                Set<String> owned =
                                        managedPlaybackScopes();
                                owned.add(approved);
                                saveManagedPlaybackScopes(
                                        owned);

                                Log.i(
                                        TAG,
                                        "Playback scope approved: "
                                                + approved);
                            }

                            @Override
                            public void onScopeRequestDenied(
                                    String denied
                            ) {
                                pendingScopeRequests.remove(
                                        denied);
                                Log.w(
                                        TAG,
                                        "Playback scope denied: "
                                                + denied);
                            }

                            @Override
                            public void onScopeRequestTimeout(
                                    String timedOut
                            ) {
                                pendingScopeRequests.remove(
                                        timedOut);
                                Log.w(
                                        TAG,
                                        "Playback scope timeout: "
                                                + timedOut);
                            }

                            @Override
                            public void onScopeRequestFailed(
                                    String failed,
                                    String message
                            ) {
                                pendingScopeRequests.remove(
                                        failed);
                                Log.w(
                                        TAG,
                                        "Playback scope failed: "
                                                + failed
                                                + " error="
                                                + message);
                            }
                        });
            } catch (Throwable t) {
                pendingScopeRequests.remove(
                        packageName);

                Log.w(
                        TAG,
                        "Failed to request playback scope "
                                + packageName,
                        t);
            }
        }

        boolean managedChanged = false;

        for (String packageName :
                new HashSet<>(managed)) {
            if (selected.contains(packageName)) {
                continue;
            }

            try {
                String error =
                        current.removeScope(
                                packageName);

                if (error == null) {
                    managed.remove(
                            packageName);
                    managedChanged = true;

                    Log.i(
                            TAG,
                            "Playback scope removed: "
                                    + packageName);
                } else {
                    Log.w(
                            TAG,
                            "Playback scope remove failed: "
                                    + packageName
                                    + " error="
                                    + error);
                }
            } catch (Throwable t) {
                Log.w(
                        TAG,
                        "Failed to remove playback scope "
                                + packageName,
                        t);
            }
        }

        if (managedChanged) {
            saveManagedPlaybackScopes(
                    managed);
        }
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

            for (String key : STRING_SET_KEYS) {
                editor.putStringSet(
                        key,
                        new HashSet<>(
                                getStringSet(key)));
            }

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
