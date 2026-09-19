package com.yagay.MiniWindowGuard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.util.Log;

import java.lang.reflect.Method;

import dalvik.system.PathClassLoader;

/**
 * Fixed system_server bootstrap bridge.
 *
 * This class stays resident for the lifetime of system_server. It never owns
 * window implementation state itself; it reflectively forwards to a
 * HotReloadEngine loaded from the currently installed APK.
 */
final class EngineBridge {
    static final int BOOTSTRAP_API = 5;

    private static final String TAG = "MiniWindowGuard";
    private static final String PACKAGE_NAME =
            "com.yagay.MiniWindowGuard";
    private static final String ENGINE_CLASS =
            "com.yagay.MiniWindowGuard.HotReloadEngine";
    private static final String RELOADABLE_PREFIX =
            "com.yagay.MiniWindowGuard.";

    private final Handler handler;
    private final Context context;
    private final SharedPreferences prefs;
    private final ClassLoader systemClassLoader;

    private final Object lock = new Object();

    private volatile Object engine;
    private volatile ClassLoader engineLoader;
    private volatile long engineVersion = -1L;
    private volatile long engineGeneration;
    private volatile String engineApkPath = "";
    private volatile String lastReloadMessage = "not-loaded";

    EngineBridge(
            Handler handler,
            Context context,
            SharedPreferences prefs,
            ClassLoader systemClassLoader
    ) {
        this.handler = handler;
        this.context = context;
        this.prefs = prefs;
        this.systemClassLoader = systemClassLoader;
    }

    boolean loadInitial() {
        return reload("initial-load");
    }

    boolean reload(String reason) {
        synchronized (lock) {
            Object oldEngine = engine;
            long oldVersion = engineVersion;
            ClassLoader oldLoader = engineLoader;

            Object candidate = null;
            ClassLoader candidateLoader = null;

            try {
                String apkPath = resolveInstalledApkPath();
                if (apkPath == null || apkPath.isBlank()) {
                    throw new IllegalStateException(
                            "installed APK path unavailable");
                }

                candidateLoader =
                        new ReloadableEngineClassLoader(
                                apkPath,
                                systemClassLoader);

                Class<?> engineClass = Class.forName(
                        ENGINE_CLASS,
                        true,
                        candidateLoader);

                if (engineClass.getClassLoader()
                        != candidateLoader) {
                    throw new IllegalStateException(
                            "engine class resolved from stale parent loader "
                                    + engineClass.getClassLoader());
                }

                candidate = engineClass
                        .getDeclaredConstructor()
                        .newInstance();

                long candidateVersion =
                        ((Number) invoke(
                                candidate,
                                "versionCode"))
                                .longValue();

                long installedVersion =
                        installedVersionCode();

                if (installedVersion > 0
                        && candidateVersion
                        != installedVersion) {
                    throw new IllegalStateException(
                            "engine version "
                                    + candidateVersion
                                    + " != installed version "
                                    + installedVersion
                                    + " apk="
                                    + apkPath);
                }

                int requiredBootstrap =
                        ((Number) invoke(
                                candidate,
                                "bootstrapApiRequired"))
                                .intValue();

                if (requiredBootstrap > BOOTSTRAP_API) {
                    throw new IllegalStateException(
                            "engine requires bootstrap API "
                                    + requiredBootstrap
                                    + " but running bootstrap is "
                                    + BOOTSTRAP_API);
                }

                // Stop the old engine only after the replacement has been
                // constructed and validated.
                if (oldEngine != null) {
                    safeInvoke(oldEngine, "stop");
                }

                invoke(
                        candidate,
                        "start",
                        new Class<?>[]{
                                Handler.class,
                                Context.class,
                                SharedPreferences.class
                        },
                        handler,
                        context,
                        prefs);

                engine = candidate;
                engineLoader = candidateLoader;
                engineVersion = candidateVersion;
                engineApkPath = apkPath;
                engineGeneration++;
                lastReloadMessage =
                        "success reason=" + reason
                                + " version=" + candidateVersion
                                + " generation=" + engineGeneration;

                Log.i(TAG,
                        "ENGINE_RELOAD_SUCCESS "
                                + lastReloadMessage);
                return true;
            } catch (Throwable error) {
                // If the old engine was already stopped, try to restart it.
                if (oldEngine != null) {
                    try {
                        Boolean oldStarted =
                                (Boolean) safeInvoke(
                                        oldEngine,
                                        "started");
                        if (oldStarted == null || !oldStarted) {
                            invoke(
                                    oldEngine,
                                    "start",
                                    new Class<?>[]{
                                            Handler.class,
                                            Context.class,
                                            SharedPreferences.class
                                    },
                                    handler,
                                    context,
                                    prefs);
                        }
                    } catch (Throwable restartError) {
                        Log.e(TAG,
                                "ENGINE_ROLLBACK_FAILED",
                                restartError);
                    }
                }

                engine = oldEngine;
                engineVersion = oldVersion;
                engineLoader = oldLoader;
                lastReloadMessage =
                        "failed reason=" + reason
                                + " error=" + error;

                Log.e(TAG,
                        "ENGINE_RELOAD_FAILED "
                                + lastReloadMessage,
                        error);
                return false;
            }
        }
    }

    boolean shouldAutoReload() {
        if (prefs == null) return false;

        try {
            return prefs.getBoolean(
                    "engine_auto_reload",
                    true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    int reloadSequence() {
        if (prefs == null) return 0;

        try {
            return prefs.getInt(
                    "engine_reload_seq",
                    0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    long installedVersionCode() {
        if (context == null) return -1L;

        try {
            PackageInfo info =
                    context.getPackageManager()
                            .getPackageInfo(
                                    PACKAGE_NAME,
                                    0);
            return info.getLongVersionCode();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    long versionCode() {
        return engineVersion;
    }

    long generation() {
        return engineGeneration;
    }

    String lastReloadMessage() {
        return lastReloadMessage;
    }

    String apkPath() {
        return engineApkPath;
    }

    boolean enabled() {
        return invokeBoolean("enabled", false);
    }

    boolean bool(String key) {
        Object value = safeInvoke(
                currentEngine(),
                "bool",
                new Class<?>[]{String.class},
                key);
        return value instanceof Boolean && (Boolean) value;
    }

    boolean isManagedPackage(String packageName) {
        Object value = safeInvoke(
                currentEngine(),
                "isManagedPackage",
                new Class<?>[]{String.class},
                packageName);
        return value instanceof Boolean && (Boolean) value;
    }

    boolean isKnownPackage(String packageName) {
        Object value = safeInvoke(
                currentEngine(),
                "isKnownPackage",
                new Class<?>[]{String.class},
                packageName);
        return value instanceof Boolean && (Boolean) value;
    }

    boolean isForceSupportPackage(String packageName) {
        Object value = safeInvoke(
                currentEngine(),
                "isForceSupportPackage",
                new Class<?>[]{String.class},
                packageName);
        return value instanceof Boolean
                && (Boolean) value;
    }

    boolean isForegroundPackage(String packageName) {
        Object value = safeInvoke(
                currentEngine(),
                "isForegroundPackage",
                new Class<?>[]{String.class},
                packageName);
        return value instanceof Boolean
                && (Boolean) value;
    }

    boolean isBackgroundPlaybackPackage(
            String packageName
    ) {
        Object value = safeInvoke(
                currentEngine(),
                "isBackgroundPlaybackPackage",
                new Class<?>[]{String.class},
                packageName);
        return value instanceof Boolean
                && (Boolean) value;
    }

    boolean wantsPackage(String packageName) {
        Object value = safeInvoke(
                currentEngine(),
                "wantsPackage",
                new Class<?>[]{String.class},
                packageName);
        return value instanceof Boolean && (Boolean) value;
    }

    void capture(Object activityRecord, String packageName) {
        safeInvoke(
                currentEngine(),
                "capture",
                new Class<?>[]{
                        Object.class,
                        String.class
                },
                activityRecord,
                packageName);
    }

    void onOplusTaskInfoChanged(Object taskInfo) {
        safeInvoke(
                currentEngine(),
                "onOplusTaskInfoChanged",
                new Class<?>[]{Object.class},
                taskInfo);
    }

    void onOplusTaskVanished(Object taskInfo) {
        safeInvoke(
                currentEngine(),
                "onOplusTaskVanished",
                new Class<?>[]{Object.class},
                taskInfo);
    }

    void onFloatHandleOpened(
            int taskId
    ) {
        safeInvoke(
                currentEngine(),
                "onFloatHandleOpened",
                new Class<?>[]{int.class},
                taskId);
    }

    void onOplusFlexibleEvent(
            int taskId,
            int event
    ) {
        safeInvoke(
                currentEngine(),
                "onOplusFlexibleEvent",
                new Class<?>[]{
                        int.class,
                        int.class
                },
                taskId,
                event);
    }

    boolean shouldSuppressBackgroundPause(
            Object task,
            String resumingPackage,
            boolean userLeaving,
            boolean uiSleeping,
            String reason,
            boolean finishing
    ) {
        Object value = safeInvoke(
                currentEngine(),
                "shouldSuppressBackgroundPause",
                new Class<?>[]{
                        Object.class,
                        String.class,
                        boolean.class,
                        boolean.class,
                        String.class,
                        boolean.class
                },
                task,
                resumingPackage,
                userLeaving,
                uiSleeping,
                reason,
                finishing);
        return value instanceof Boolean
                && (Boolean) value;
    }

    void onFocusedActivity(
            Object activityRecord
    ) {
        safeInvoke(
                currentEngine(),
                "onFocusedActivity",
                new Class<?>[]{Object.class},
                activityRecord);
    }

    boolean shouldBlockBackgroundStop(
            Object task,
            boolean finishing
    ) {
        Object value = safeInvoke(
                currentEngine(),
                "shouldBlockBackgroundStop",
                new Class<?>[]{
                        Object.class,
                        boolean.class
                },
                task,
                finishing);
        return value instanceof Boolean
                && (Boolean) value;
    }

    boolean shouldSuppressRecentsPause(
            Object task
    ) {
        Object value = safeInvoke(
                currentEngine(),
                "shouldSuppressRecentsPause",
                new Class<?>[]{Object.class},
                task);
        return value instanceof Boolean
                && (Boolean) value;
    }

    void preArmLockKeepAlive(String reason) {
        safeInvoke(
                currentEngine(),
                "preArmLockKeepAlive",
                new Class<?>[]{String.class},
                reason);
    }

    void onKeyguardStateChanged(boolean showing) {
        safeInvoke(
                currentEngine(),
                "onKeyguardStateChanged",
                new Class<?>[]{boolean.class},
                showing);
    }

    boolean shouldHoldEdgeTask(Object task) {
        Object value = safeInvoke(
                currentEngine(),
                "shouldHoldEdgeTask",
                new Class<?>[]{Object.class},
                task);
        return value instanceof Boolean
                && (Boolean) value;
    }

    boolean isEdgeHungTask(Object task) {
        Object value = safeInvoke(
                currentEngine(),
                "isEdgeHungTask",
                new Class<?>[]{Object.class},
                task);
        return value instanceof Boolean
                && (Boolean) value;
    }

    boolean shouldKeepTaskAwake(Object task) {
        Object value = safeInvoke(
                currentEngine(),
                "shouldKeepTaskAwake",
                new Class<?>[]{Object.class},
                task);
        return value instanceof Boolean
                && (Boolean) value;
    }

    boolean shouldBlockTaskRemoval(
            String packageName
    ) {
        Object value = safeInvoke(
                currentEngine(),
                "shouldBlockTaskRemoval",
                new Class<?>[]{String.class},
                packageName);
        return value instanceof Boolean
                && (Boolean) value;
    }

    String managedPackageForProcess(String processName) {
        Object value = safeInvoke(
                currentEngine(),
                "managedPackageForProcess",
                new Class<?>[]{String.class},
                processName);
        return value instanceof String
                ? (String) value
                : null;
    }

    int activeSessionCount() {
        Object value = safeInvoke(
                currentEngine(),
                "activeSessionCount");
        return value instanceof Number
                ? ((Number) value).intValue()
                : 0;
    }

    private Object currentEngine() {
        return engine;
    }

    private boolean invokeBoolean(
            String method,
            boolean fallback
    ) {
        Object value = safeInvoke(
                currentEngine(),
                method);
        return value instanceof Boolean
                ? (Boolean) value
                : fallback;
    }

    private static final class ReloadableEngineClassLoader
            extends PathClassLoader {
        ReloadableEngineClassLoader(
                String dexPath,
                ClassLoader parent
        ) {
            super(
                    dexPath,
                    parent);
        }

        @Override
        protected Class<?> loadClass(
                String name,
                boolean resolve
        ) throws ClassNotFoundException {
            synchronized (
                    getClassLoadingLock(
                            name)) {
                Class<?> loaded =
                        findLoadedClass(
                                name);

                if (loaded == null
                        && name.startsWith(
                                RELOADABLE_PREFIX)) {
                    try {
                        loaded =
                                findClass(
                                        name);
                    } catch (ClassNotFoundException ignored) {
                    }
                }

                if (loaded == null) {
                    loaded =
                            super.loadClass(
                                    name,
                                    false);
                }

                if (resolve) {
                    resolveClass(
                            loaded);
                }

                return loaded;
            }
        }
    }

    private String resolveInstalledApkPath() throws Exception {
        ApplicationInfo info =
                context.getPackageManager()
                        .getApplicationInfo(
                                PACKAGE_NAME,
                                0);

        if (info == null
                || info.sourceDir == null
                || info.sourceDir.isBlank()) {
            return null;
        }

        return info.sourceDir;
    }

    private static Object invoke(
            Object receiver,
            String name,
            Object... args
    ) throws Exception {
        Class<?>[] types =
                new Class<?>[args.length];

        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null
                    ? Object.class
                    : args[i].getClass();
        }

        return invoke(
                receiver,
                name,
                types,
                args);
    }

    private static Object invoke(
            Object receiver,
            String name,
            Class<?>[] parameterTypes,
            Object... args
    ) throws Exception {
        if (receiver == null) {
            throw new IllegalStateException(
                    "engine not loaded");
        }

        Method method =
                receiver.getClass()
                        .getMethod(
                                name,
                                parameterTypes);
        method.setAccessible(true);
        return method.invoke(
                receiver,
                args);
    }

    private static Object safeInvoke(
            Object receiver,
            String name,
            Object... args
    ) {
        if (receiver == null) return null;

        try {
            return invoke(
                    receiver,
                    name,
                    args);
        } catch (Throwable error) {
            Log.w(TAG,
                    "ENGINE_CALL_FAILED method="
                            + name
                            + " error=" + error);
            return null;
        }
    }

    private static Object safeInvoke(
            Object receiver,
            String name,
            Class<?>[] parameterTypes,
            Object... args
    ) {
        if (receiver == null) return null;

        try {
            return invoke(
                    receiver,
                    name,
                    parameterTypes,
                    args);
        } catch (Throwable error) {
            Log.w(TAG,
                    "ENGINE_CALL_FAILED method="
                            + name
                            + " error=" + error);
            return null;
        }
    }
}
