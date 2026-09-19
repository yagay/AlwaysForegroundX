package com.yagay.MiniWindowGuard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * OPlus-only system_server bootstrap.
 *
 * OxygenOS owns window lifecycle, visibility, surfaces, focus and navigation.
 * MiniWindowGuard hooks OPlus FlexibleWindow itself, then applies foreground
 * and anti-cleanup policy only while the OEM task is actually flexible/floating.
 */
public final class GuardModule extends XposedModule {
    private static final String TAG = "MiniWindowGuard";
    private static final int PROCESS_STATE_TOP = 2;
    private static final long MODULE_VERSION_CODE =
            BuildConfig.VERSION_CODE;

    private final Set<String> installedHooks =
            ConcurrentHashMap.newKeySet();
    private final Set<String> firstHits =
            ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Integer, String[]> uidPackages =
            new ConcurrentHashMap<>();

    private volatile ClassLoader systemClassLoader;
    private volatile SharedPreferences remotePrefs;
    private volatile EngineBridge engine;

    @Override
    public void onModuleLoaded(
            XposedModuleInterface.ModuleLoadedParam param
    ) {
        try {
            remotePrefs =
                    getRemotePreferences(
                            ConfigKeys.REMOTE_GROUP);
            GuardConfig.initialize(remotePrefs);
            log(
                    Log.INFO,
                    TAG,
                    "SYSTEM_SCOPE remote settings ready");
        } catch (Throwable t) {
            remotePrefs = null;
            GuardConfig.initialize(null);
            log(
                    Log.WARN,
                    TAG,
                    "SYSTEM_SCOPE remote settings unavailable",
                    t);
        }
    }

    @Override
    public void onSystemServerStarting(
            XposedModuleInterface.SystemServerStartingParam param
    ) {
        systemClassLoader = param.getClassLoader();

        Handler handler =
                new Handler(
                        Looper.getMainLooper());

        Context context =
                resolveSystemUiContext(
                        systemClassLoader);

        engine = new EngineBridge(
                handler,
                context,
                remotePrefs,
                systemClassLoader);

        boolean initialLoaded =
                engine.loadInitial();

        installOplusFlexibleWindowHooks(
                systemClassLoader);
        installActivityRecordCaptureHook(
                systemClassLoader);
        installActivityManagerHooks(
                systemClassLoader);
        installActivityTaskManagerHooks(
                systemClassLoader);
        installRemovedTaskServiceGuard(
                systemClassLoader);
        installProcessKillGuard(
                systemClassLoader);

        log(
                Log.INFO,
                TAG,
                "SYSTEM_SCOPE OPlus-only bootstrap ready");

        diag(
                "ENGINE_READY",
                "backend=OPlusFlexibleWindow"
                        + " initialLoaded="
                        + initialLoaded
                        + " engineVersion="
                        + (engine == null
                        ? -1
                        : engine.versionCode())
                        + " bootstrapApi="
                        + EngineBridge.BOOTSTRAP_API
                        + " hooks="
                        + installedHooks.size()
                        + " systemUiContext="
                        + (context != null));

        scheduleEngineHeartbeat(handler);
    }

    private boolean enabled() {
        EngineBridge current = engine;
        return current != null
                && current.enabled();
    }

    private boolean engineBool(String key) {
        EngineBridge current = engine;
        return current != null
                && current.bool(key);
    }

    /**
     * "Managed" means live OPlus flexible/floating state, not merely selected.
     * All foreground/kill policy is gated by this method.
     */
    private boolean isTargetPackage(String packageName) {
        EngineBridge current = engine;

        return packageName != null
                && enabled()
                && current != null
                && current.isManagedPackage(packageName);
    }

    private boolean isKnownPackage(String packageName) {
        EngineBridge current = engine;

        return packageName != null
                && enabled()
                && current != null
                && current.isKnownPackage(packageName);
    }

    private boolean isTargetUid(int uid) {
        if (!enabled() || uid < 10000) {
            return false;
        }

        String[] packages =
                uidPackages.get(uid);

        if (packages == null) {
            packages =
                    resolvePackagesForUid(uid);

            if (packages != null) {
                uidPackages.put(
                        uid,
                        packages);
            }
        }

        if (packages == null) return false;

        for (String pkg : packages) {
            if (isTargetPackage(pkg)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Hook the OEM window subsystem itself.
     *
     * 1) Force only the selected/requested app through OPlus support checks.
     * 2) Observe FlexibleWindow task appeared/changed/vanished callbacks.
     * 3) Never rewrite Activity visibility, pause/resume, bounds or surfaces.
     */
    private void installOplusFlexibleWindowHooks(
            ClassLoader loader
    ) {
        installOplusSupportHooks(
                loader,
                "com.android.server.wm.FlexibleWindowUtils");

        installOplusSupportHooks(
                loader,
                "com.android.server.wm.FlexibleTaskController");

        installLegacyZoomSupportHook(loader);

        Class<?> service =
                load(
                        loader,
                        "com.android.server.wm.FlexibleWindowManagerService");

        if (service == null) return;

        for (Method method :
                service.getDeclaredMethods()) {
            String name = method.getName();

            boolean changed =
                    "onFlexibleWindowTaskInfoChanged"
                            .equals(name)
                            || "onFlexibleWindowTaskAppeared"
                            .equals(name);

            boolean vanished =
                    "onFlexibleWindowTaskVanished"
                            .equals(name);

            if (!changed && !vanished) {
                continue;
            }

            try {
                method.setAccessible(true);

                if (!installedHooks.add(
                        method.toGenericString())) {
                    continue;
                }

                hook(method).intercept(chain -> {
                    Object result = chain.proceed();

                    Object taskInfo =
                            findTaskInfoArg(
                                    chain.getArgs());

                    EngineBridge current = engine;

                    if (current != null
                            && taskInfo != null) {
                        if (vanished) {
                            current.onOplusTaskVanished(
                                    taskInfo);
                        } else {
                            current.onOplusTaskInfoChanged(
                                    taskInfo);
                        }

                        diag(
                                vanished
                                        ? "OPLUS_TASK_CALLBACK_VANISHED"
                                        : "OPLUS_TASK_CALLBACK_CHANGED",
                                "method="
                                        + method.getName()
                                        + " taskId="
                                        + intField(
                                        taskInfo,
                                        "taskId",
                                        -1)
                                        + " pkg="
                                        + packageFromObject(
                                        taskInfo));
                    }

                    return result;
                });

                log(
                        Log.INFO,
                        TAG,
                        "SYSTEM_SCOPE installed OPlus callback "
                                + method.toGenericString());
            } catch (Throwable t) {
                installedHooks.remove(
                        method.toGenericString());

                log(
                        Log.WARN,
                        TAG,
                        "SYSTEM_SCOPE skipped OPlus callback "
                                + name
                                + " error=" + t);
            }
        }
    }

    private void installOplusSupportHooks(
            ClassLoader loader,
            String className
    ) {
        Class<?> type =
                load(
                        loader,
                        className);

        if (type == null) return;

        for (Method method :
                type.getDeclaredMethods()) {
            if (method.getReturnType()
                    != boolean.class) {
                continue;
            }

            String name = method.getName();

            boolean supportCheck =
                    "isSupportFlexibleWindow"
                            .equals(name);

            boolean denyCheck =
                    "isInFlexibleWindowBlackList"
                            .equals(name)
                            || "isInMultiWindowFlexibleBlackList"
                            .equals(name)
                            || "isFlexibleTaskInPSBlackList"
                            .equals(name)
                            || "isUnSupportCallerFlexibleWindow"
                            .equals(name);

            if (!supportCheck && !denyCheck) {
                continue;
            }

            try {
                method.setAccessible(true);

                if (!installedHooks.add(
                        method.toGenericString())) {
                    continue;
                }

                hook(method).intercept(chain -> {
                    String pkg =
                            extractKnownPackage(
                                    chain.getArgs());

                    if (engineBool(
                            ConfigKeys.OPLUS_FORCE_SUPPORT)
                            && pkg != null) {
                        boolean forced =
                                supportCheck;

                        diag(
                                supportCheck
                                        ? "OPLUS_SUPPORT_FORCE"
                                        : "OPLUS_RESTRICTION_BYPASS",
                                "pkg=" + pkg
                                        + " class="
                                        + className
                                        + " method="
                                        + method.getName()
                                        + " result="
                                        + forced);

                        return forced;
                    }

                    return chain.proceed();
                });

                log(
                        Log.INFO,
                        TAG,
                        "SYSTEM_SCOPE installed OPlus support hook "
                                + method.toGenericString());
            } catch (Throwable t) {
                installedHooks.remove(
                        method.toGenericString());

                log(
                        Log.WARN,
                        TAG,
                        "SYSTEM_SCOPE skipped OPlus support hook "
                                + method.toGenericString()
                                + " error=" + t);
            }
        }
    }

    /**
     * Some ColorOS/OxygenOS branches still consult the older Zoom config
     * before FlexibleWindow. Force support only for the requested app.
     */
    private void installLegacyZoomSupportHook(
            ClassLoader loader
    ) {
        Class<?> config =
                load(
                        loader,
                        "com.android.server.wm.OplusZoomWindowConfig");

        if (config == null) return;

        for (Method method :
                config.getDeclaredMethods()) {
            if (!"isSupportZoomMode"
                    .equals(method.getName())
                    || method.getReturnType()
                    != boolean.class) {
                continue;
            }

            try {
                method.setAccessible(true);

                if (!installedHooks.add(
                        method.toGenericString())) {
                    continue;
                }

                hook(method).intercept(chain -> {
                    String pkg =
                            extractKnownPackage(
                                    chain.getArgs());

                    if (engineBool(
                            ConfigKeys.OPLUS_FORCE_SUPPORT)
                            && pkg != null) {
                        diag(
                                "OPLUS_ZOOM_SUPPORT_FORCE",
                                "pkg=" + pkg);
                        return true;
                    }

                    return chain.proceed();
                });
            } catch (Throwable t) {
                installedHooks.remove(
                        method.toGenericString());
            }
        }
    }

    /**
     * The only Activity lifecycle hook left: observe RESUMED so the selected
     * task can be handed to OPlus. It never blocks or changes lifecycle state.
     */
    private void installActivityRecordCaptureHook(
            ClassLoader loader
    ) {
        Class<?> record =
                load(
                        loader,
                        "com.android.server.wm.ActivityRecord");

        if (record == null) return;

        for (Method method :
                record.getDeclaredMethods()) {
            if (!"setState"
                    .equals(method.getName())
                    || method.getReturnType()
                    != void.class
                    || method.getParameterCount() < 1) {
                continue;
            }

            try {
                method.setAccessible(true);

                if (!installedHooks.add(
                        method.toGenericString())) {
                    continue;
                }

                hook(method).intercept(chain -> {
                    Object result =
                            chain.proceed();

                    if (!enabled()) {
                        return result;
                    }

                    List<Object> args =
                            chain.getArgs();

                    if (args.isEmpty()
                            || !"RESUMED".equals(
                            String.valueOf(
                                    args.get(0)))) {
                        return result;
                    }

                    Object activityRecord =
                            chain.getThisObject();

                    String pkg =
                            activityPackage(
                                    activityRecord);

                    EngineBridge current = engine;

                    if (current != null
                            && current.wantsPackage(pkg)) {
                        diag(
                                "OPLUS_ACTIVITY_RESUMED",
                                "pkg=" + pkg
                                        + " activity="
                                        + fieldValue(
                                        activityRecord,
                                        "mActivityComponent"));

                        current.capture(
                                activityRecord,
                                pkg);
                    }

                    return result;
                });

                log(
                        Log.INFO,
                        TAG,
                        "SYSTEM_SCOPE installed ActivityRecord.setState observer");
            } catch (Throwable t) {
                installedHooks.remove(
                        method.toGenericString());

                log(
                        Log.WARN,
                        TAG,
                        "SYSTEM_SCOPE skipped ActivityRecord.setState observer error="
                                + t);
            }
        }
    }

    private void installActivityManagerHooks(
            ClassLoader loader
    ) {
        Class<?> ams =
                load(
                        loader,
                        "com.android.server.am.ActivityManagerService");

        if (ams == null) return;

        for (Method method :
                ams.getDeclaredMethods()) {
            String name = method.getName();

            if ("getPackageProcessState"
                    .equals(name)
                    && method.getReturnType()
                    == int.class) {
                installPackageProcessStateHook(method);
                continue;
            }

            if ("getUidProcessState"
                    .equals(name)
                    && method.getReturnType()
                    == int.class) {
                installUidProcessStateHook(method);
                continue;
            }

            if ("isAppForeground"
                    .equals(name)
                    && method.getReturnType()
                    == boolean.class) {
                installIsAppForegroundHook(method);
            }
        }
    }

    private void installPackageProcessStateHook(
            Method method
    ) {
        try {
            method.setAccessible(true);

            if (!installedHooks.add(
                    method.toGenericString())) {
                return;
            }

            hook(method).intercept(chain -> {
                List<Object> args =
                        chain.getArgs();

                if (engineBool(
                        ConfigKeys.SYSTEM_IMPORTANCE_TOP)
                        && !args.isEmpty()
                        && args.get(0)
                        instanceof String pkg
                        && isTargetPackage(pkg)) {
                    diag(
                            "AMS_PACKAGE_STATE",
                            "pkg=" + pkg
                                    + " forced="
                                    + PROCESS_STATE_TOP);

                    return PROCESS_STATE_TOP;
                }

                return chain.proceed();
            });
        } catch (Throwable t) {
            installedHooks.remove(
                    method.toGenericString());
        }
    }

    private void installUidProcessStateHook(
            Method method
    ) {
        try {
            method.setAccessible(true);

            if (!installedHooks.add(
                    method.toGenericString())) {
                return;
            }

            hook(method).intercept(chain -> {
                List<Object> args =
                        chain.getArgs();

                if (engineBool(
                        ConfigKeys.SYSTEM_IMPORTANCE_TOP)
                        && !args.isEmpty()
                        && args.get(0)
                        instanceof Integer uid
                        && isTargetUid(uid)) {
                    diag(
                            "AMS_UID_STATE",
                            "uid=" + uid
                                    + " packages="
                                    + Arrays.toString(
                                    resolvePackagesForUid(uid))
                                    + " forced="
                                    + PROCESS_STATE_TOP);

                    return PROCESS_STATE_TOP;
                }

                return chain.proceed();
            });
        } catch (Throwable t) {
            installedHooks.remove(
                    method.toGenericString());
        }
    }

    private void installIsAppForegroundHook(
            Method method
    ) {
        try {
            method.setAccessible(true);

            if (!installedHooks.add(
                    method.toGenericString())) {
                return;
            }

            hook(method).intercept(chain -> {
                List<Object> args =
                        chain.getArgs();

                if (engineBool(
                        ConfigKeys.SYSTEM_IMPORTANCE_TOP)
                        && !args.isEmpty()
                        && args.get(0)
                        instanceof Integer uid
                        && isTargetUid(uid)) {
                    diag(
                            "AMS_FOREGROUND",
                            "uid=" + uid
                                    + " forced=true");
                    return true;
                }

                return chain.proceed();
            });
        } catch (Throwable t) {
            installedHooks.remove(
                    method.toGenericString());
        }
    }

    private void installActivityTaskManagerHooks(
            ClassLoader loader
    ) {
        Class<?> atms =
                load(
                        loader,
                        "com.android.server.wm.ActivityTaskManagerService");

        if (atms == null) return;

        for (Method method :
                atms.getDeclaredMethods()) {
            if (!"hasResumedActivity"
                    .equals(method.getName())
                    || method.getReturnType()
                    != boolean.class) {
                continue;
            }

            try {
                method.setAccessible(true);

                if (!installedHooks.add(
                        method.toGenericString())) {
                    continue;
                }

                hook(method).intercept(chain -> {
                    List<Object> args =
                            chain.getArgs();

                    if (engineBool(
                            ConfigKeys.SYSTEM_HAS_RESUMED)
                            && !args.isEmpty()
                            && args.get(0)
                            instanceof Integer uid
                            && isTargetUid(uid)) {
                        diag(
                                "ATMS_HAS_RESUMED",
                                "uid=" + uid
                                        + " packages="
                                        + Arrays.toString(
                                        resolvePackagesForUid(uid))
                                        + " forced=true");

                        return true;
                    }

                    return chain.proceed();
                });
            } catch (Throwable t) {
                installedHooks.remove(
                        method.toGenericString());
            }
        }
    }

    private void installRemovedTaskServiceGuard(
            ClassLoader loader
    ) {
        Class<?> localService =
                load(
                        loader,
                        "com.android.server.am.ActivityManagerService$LocalService");

        if (localService == null) return;

        for (Method method :
                localService.getDeclaredMethods()) {
            if (!"cleanUpServices"
                    .equals(method.getName())
                    || method.getReturnType()
                    != void.class) {
                continue;
            }

            try {
                method.setAccessible(true);

                if (!installedHooks.add(
                        method.toGenericString())) {
                    continue;
                }

                hook(method).intercept(chain -> {
                    if (!enabled()
                            || !engineBool(
                            ConfigKeys.SYSTEM_BLOCK_REMOVE_KILL)) {
                        return chain.proceed();
                    }

                    ComponentName component = null;

                    for (Object arg :
                            chain.getArgs()) {
                        if (arg
                                instanceof ComponentName) {
                            component =
                                    (ComponentName) arg;
                            break;
                        }
                    }

                    String pkg =
                            component == null
                                    ? null
                                    : component.getPackageName();

                    if (!isTargetPackage(pkg)) {
                        return chain.proceed();
                    }

                    diag(
                            "REMOVE_TASK_SERVICES_BLOCK",
                            "pkg=" + pkg
                                    + " component="
                                    + component
                                    + " stack="
                                    + stackSummary());

                    return null;
                });
            } catch (Throwable t) {
                installedHooks.remove(
                        method.toGenericString());
            }
        }
    }

    private void installProcessKillGuard(
            ClassLoader loader
    ) {
        Class<?> processClass =
                load(
                        loader,
                        "android.os.Process");

        if (processClass == null) return;

        for (Method method :
                processClass.getDeclaredMethods()) {
            String name = method.getName();

            if (!("sendSignal".equals(name)
                    || "sendSignalQuiet".equals(name))
                    || method.getReturnType()
                    != void.class
                    || method.getParameterCount()
                    != 2
                    || method.getParameterTypes()[0]
                    != int.class
                    || method.getParameterTypes()[1]
                    != int.class) {
                continue;
            }

            try {
                method.setAccessible(true);

                if (!installedHooks.add(
                        method.toGenericString())) {
                    continue;
                }

                hook(method).intercept(chain -> {
                    if (!enabled()
                            || !engineBool(
                            ConfigKeys.SYSTEM_BLOCK_REMOVE_KILL)) {
                        return chain.proceed();
                    }

                    List<Object> args =
                            chain.getArgs();

                    if (args.size() < 2
                            || !(args.get(0)
                            instanceof Integer pid)
                            || !(args.get(1)
                            instanceof Integer signal)
                            || signal != 9) {
                        return chain.proceed();
                    }

                    String processName =
                            readProcessName(pid);

                    String targetPackage =
                            targetPackageForProcess(
                                    processName);

                    if (targetPackage == null) {
                        return chain.proceed();
                    }

                    String stack =
                            stackSummary();

                    if (isExplicitStopOrUpdateStack(
                            stack)) {
                        diag(
                                "KILL_GUARD_PASS",
                                "reason=explicit-stop-or-update"
                                        + " pid=" + pid
                                        + " process="
                                        + processName
                                        + " stack=" + stack);

                        return chain.proceed();
                    }

                    if (!isTaskRemovalKillStack(
                            stack)) {
                        return chain.proceed();
                    }

                    diag(
                            "KILL_GUARD_BLOCK",
                            "pkg=" + targetPackage
                                    + " pid=" + pid
                                    + " process="
                                    + processName
                                    + " stack=" + stack);

                    return null;
                });
            } catch (Throwable t) {
                installedHooks.remove(
                        method.toGenericString());
            }
        }
    }

    private String targetPackageForProcess(
            String processName
    ) {
        EngineBridge current = engine;

        return current == null
                ? null
                : current.managedPackageForProcess(
                processName);
    }

    private static String readProcessName(int pid) {
        if (pid <= 0) return null;

        byte[] buffer = new byte[512];

        try (FileInputStream in =
                     new FileInputStream(
                             "/proc/" + pid + "/cmdline")) {
            int length = in.read(buffer);
            if (length <= 0) return null;

            int end = 0;
            while (end < length
                    && buffer[end] != 0) {
                end++;
            }

            if (end <= 0) return null;

            String value =
                    new String(
                            buffer,
                            0,
                            end,
                            StandardCharsets.UTF_8)
                            .trim();

            return value.isEmpty()
                    ? null
                    : value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isTaskRemovalKillStack(
            String stack
    ) {
        if (stack == null) return false;

        return stack.contains(
                "killProcessesForRemovedTask")
                || stack.contains(
                "cleanUpRemovedTask")
                || stack.contains("removeTask")
                || stack.contains(
                "SwipeUpClearAction")
                || stack.contains(
                "OplusClearSystemService")
                || stack.contains(
                "AthenaKiller")
                || stack.contains(
                "com.oplus.athena")
                || stack.contains(
                "com.coloros.athena");
    }

    private static boolean isExplicitStopOrUpdateStack(
            String stack
    ) {
        if (stack == null) return false;

        return stack.contains(
                "forceStopPackage")
                || stack.contains(
                "forceStopPackageLocked")
                || stack.contains(
                "PackageInstaller")
                || stack.contains(
                "PackageManagerService")
                || stack.contains(
                "PackageManagerShellCommand")
                || stack.contains(
                "deletePackage")
                || stack.contains(
                "installPackage");
    }

    private String[] resolvePackagesForUid(int uid) {
        try {
            Class<?> appGlobals =
                    Class.forName(
                            "android.app.AppGlobals",
                            false,
                            systemClassLoader);

            Object pm =
                    appGlobals
                            .getMethod(
                                    "getPackageManager")
                            .invoke(null);

            if (pm == null) return null;

            Class<?> iPackageManager =
                    Class.forName(
                            "android.content.pm.IPackageManager",
                            false,
                            systemClassLoader);

            Method method =
                    iPackageManager.getMethod(
                            "getPackagesForUid",
                            int.class);

            Object result =
                    method.invoke(
                            pm,
                            uid);

            return result instanceof String[]
                    ? (String[]) result
                    : null;
        } catch (Throwable t) {
            logOnce(
                    "uid-resolve-" + uid,
                    "SYSTEM_SCOPE unable to resolve uid="
                            + uid
                            + " error=" + t);

            return null;
        }
    }

    private void scheduleEngineHeartbeat(
            Handler handler
    ) {
        handler.postDelayed(
                new Runnable() {
                    private int lastReloadSeq =
                            Integer.MIN_VALUE;
                    private long lastAutoAttemptVersion =
                            Long.MIN_VALUE;
                    private long lastAutoAttemptElapsed;

                    @Override
                    public void run() {
                        EngineBridge current = engine;

                        try {
                            if (current != null) {
                                int seq =
                                        current.reloadSequence();

                                if (lastReloadSeq
                                        == Integer.MIN_VALUE) {
                                    lastReloadSeq = seq;
                                } else if (seq
                                        != lastReloadSeq) {
                                    lastReloadSeq = seq;

                                    diag(
                                            "ENGINE_RELOAD_BEGIN",
                                            "reason=manual"
                                                    + " seq=" + seq
                                                    + " oldVersion="
                                                    + current.versionCode());

                                    boolean reloaded =
                                            current.reload(
                                                    "manual-seq-"
                                                            + seq);

                                    diag(
                                            reloaded
                                                    ? "ENGINE_RELOAD_SUCCESS"
                                                    : "ENGINE_RELOAD_FAILED",
                                            current.lastReloadMessage());
                                }

                                long installed =
                                        current.installedVersionCode();

                                long loaded =
                                        current.versionCode();

                                if (current.shouldAutoReload()
                                        && installed > 0
                                        && installed != loaded) {
                                    int active =
                                            current.activeSessionCount();

                                    long now =
                                            android.os.SystemClock
                                                    .elapsedRealtime();

                                    if (active == 0
                                            && (installed
                                            != lastAutoAttemptVersion
                                            || now
                                            - lastAutoAttemptElapsed
                                            > 30_000L)) {
                                        lastAutoAttemptVersion =
                                                installed;
                                        lastAutoAttemptElapsed =
                                                now;

                                        boolean reloaded =
                                                current.reload(
                                                        "auto-apk-update");

                                        diag(
                                                reloaded
                                                        ? "ENGINE_RELOAD_SUCCESS"
                                                        : "ENGINE_RELOAD_FAILED",
                                                current.lastReloadMessage());
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            log(
                                    Log.WARN,
                                    TAG,
                                    "ENGINE_RELOAD_WATCH error="
                                            + t);
                        }

                        Context context =
                                resolveSystemContext(
                                        systemClassLoader);

                        if (context != null) {
                            try {
                                EngineBridge currentEngine =
                                        engine;

                                Bundle extras =
                                        new Bundle();

                                extras.putLong(
                                        EngineStatusProvider.KEY_VERSION,
                                        currentEngine == null
                                                ? -1L
                                                : currentEngine.versionCode());

                                extras.putLong(
                                        EngineStatusProvider.KEY_BOOTSTRAP_VERSION,
                                        MODULE_VERSION_CODE);

                                extras.putBoolean(
                                        EngineStatusProvider.KEY_HOT_RELOAD,
                                        true);

                                extras.putLong(
                                        EngineStatusProvider.KEY_GENERATION,
                                        currentEngine == null
                                                ? 0L
                                                : currentEngine.generation());

                                extras.putString(
                                        EngineStatusProvider.KEY_RELOAD_MESSAGE,
                                        currentEngine == null
                                                ? "engine unavailable"
                                                : currentEngine.lastReloadMessage());

                                extras.putInt(
                                        EngineStatusProvider.KEY_ACTIVE_SESSIONS,
                                        currentEngine == null
                                                ? -1
                                                : currentEngine.activeSessionCount());

                                extras.putInt(
                                        EngineStatusProvider.KEY_PID,
                                        Process.myPid());

                                extras.putInt(
                                        EngineStatusProvider.KEY_HOOKS,
                                        installedHooks.size());

                                context.getContentResolver()
                                        .call(
                                                EngineStatusProvider.URI,
                                                EngineStatusProvider.METHOD_MARK,
                                                null,
                                                extras);
                            } catch (Throwable t) {
                                log(
                                        Log.WARN,
                                        TAG,
                                        "SYSTEM_SCOPE heartbeat failed error="
                                                + t);
                            }
                        }

                        handler.postDelayed(
                                this,
                                2_000L);
                    }
                },
                2_000L);
    }

    private String extractKnownPackage(
            List<Object> args
    ) {
        if (args == null) return null;

        for (Object arg : args) {
            String pkg =
                    packageFromObject(arg);

            if (pkg != null
                    && isKnownPackage(pkg)) {
                return pkg;
            }
        }

        return null;
    }

    private String packageFromObject(Object value) {
        if (value == null) return null;

        if (value instanceof String) {
            return normalizePackage(
                    (String) value);
        }

        if (value instanceof ComponentName) {
            return ((ComponentName) value)
                    .getPackageName();
        }

        if (value instanceof Intent) {
            Intent intent =
                    (Intent) value;

            ComponentName component =
                    intent.getComponent();

            if (component != null) {
                return component.getPackageName();
            }

            return intent.getPackage();
        }

        if (value instanceof ActivityInfo) {
            return ((ActivityInfo) value)
                    .packageName;
        }

        for (String field : new String[]{
                "packageName",
                "mPackageName"
        }) {
            Object pkg =
                    fieldValue(
                            value,
                            field);

            if (pkg instanceof String) {
                return normalizePackage(
                        (String) pkg);
            }
        }

        for (String field : new String[]{
                "mActivityComponent",
                "topActivity",
                "baseActivity",
                "realActivity"
        }) {
            Object component =
                    fieldValue(
                            value,
                            field);

            if (component
                    instanceof ComponentName) {
                return ((ComponentName) component)
                        .getPackageName();
            }
        }

        Object info =
                fieldValue(
                        value,
                        "topActivityInfo");

        if (info instanceof ActivityInfo) {
            return ((ActivityInfo) info)
                    .packageName;
        }

        Object top =
                invokeNoArg(
                        value,
                        "topRunningActivity");

        if (top != null
                && top != value) {
            return packageFromObject(top);
        }

        return null;
    }

    private static String normalizePackage(String value) {
        if (value == null) return null;

        String normalized =
                value.trim();

        if (normalized.isEmpty()) {
            return null;
        }

        int slash =
                normalized.indexOf('/');

        if (slash > 0) {
            normalized =
                    normalized.substring(
                            0,
                            slash);
        }

        return normalized;
    }

    private static Object findTaskInfoArg(
            List<Object> args
    ) {
        if (args == null) return null;

        for (Object arg : args) {
            if (arg == null) continue;

            String name =
                    arg.getClass().getName();

            if (name.contains("TaskInfo")
                    || findField(
                    arg.getClass(),
                    "taskId") != null) {
                return arg;
            }
        }

        return null;
    }

    private Context resolveSystemUiContext(
            ClassLoader loader
    ) {
        try {
            Class<?> activityThread =
                    Class.forName(
                            "android.app.ActivityThread",
                            false,
                            loader);

            Method current =
                    activityThread.getDeclaredMethod(
                            "currentActivityThread");

            current.setAccessible(true);

            Object thread =
                    current.invoke(null);

            if (thread != null) {
                try {
                    Method getSystemUiContext =
                            activityThread.getDeclaredMethod(
                                    "getSystemUiContext");

                    getSystemUiContext.setAccessible(true);

                    Object value =
                            getSystemUiContext.invoke(
                                    thread);

                    if (value instanceof Context) {
                        return (Context) value;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            diag(
                    "SYSTEM_UI_CONTEXT_FAIL",
                    String.valueOf(t));
        }

        return resolveSystemContext(loader);
    }

    private Context resolveSystemContext(
            ClassLoader loader
    ) {
        try {
            Class<?> activityThread =
                    Class.forName(
                            "android.app.ActivityThread",
                            false,
                            loader);

            Method current =
                    activityThread.getDeclaredMethod(
                            "currentActivityThread");

            current.setAccessible(true);

            Object thread =
                    current.invoke(null);

            if (thread != null) {
                Method getSystemContext =
                        activityThread.getDeclaredMethod(
                                "getSystemContext");

                getSystemContext.setAccessible(true);

                Object value =
                        getSystemContext.invoke(
                                thread);

                if (value instanceof Context) {
                    return (Context) value;
                }
            }
        } catch (Throwable t) {
            diag(
                    "SYSTEM_CONTEXT_FAIL",
                    String.valueOf(t));
        }

        return null;
    }

    private String activityPackage(
            Object activityRecord
    ) {
        return packageFromObject(
                activityRecord);
    }

    private Class<?> load(
            ClassLoader loader,
            String className
    ) {
        try {
            return Class.forName(
                    className,
                    false,
                    loader);
        } catch (Throwable t) {
            logOnce(
                    "missing-" + className,
                    "SYSTEM_SCOPE class unavailable "
                            + className);
            return null;
        }
    }

    private static Object invokeNoArg(
            Object receiver,
            String name
    ) {
        if (receiver == null) return null;

        Class<?> current =
                receiver.getClass();

        while (current != null) {
            for (Method method :
                    current.getDeclaredMethods()) {
                if (!name.equals(
                        method.getName())
                        || method.getParameterCount()
                        != 0) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    return method.invoke(
                            receiver);
                } catch (Throwable ignored) {
                    return null;
                }
            }

            current =
                    current.getSuperclass();
        }

        return null;
    }

    private static Object fieldValue(
            Object receiver,
            String name
    ) {
        if (receiver == null) return null;

        Field field =
                findField(
                        receiver.getClass(),
                        name);

        if (field == null) return null;

        try {
            field.setAccessible(true);
            return field.get(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int intField(
            Object receiver,
            String name,
            int fallback
    ) {
        Object value =
                fieldValue(
                        receiver,
                        name);

        return value instanceof Number
                ? ((Number) value).intValue()
                : fallback;
    }

    private static Field findField(
            Class<?> type,
            String name
    ) {
        Class<?> current = type;

        while (current != null) {
            try {
                return current.getDeclaredField(
                        name);
            } catch (NoSuchFieldException ignored) {
                current =
                        current.getSuperclass();
            }
        }

        return null;
    }

    private boolean diagnosticsActive() {
        return engineBool(
                ConfigKeys.DIAGNOSTICS_ACTIVE);
    }

    private void diag(
            String event,
            String detail
    ) {
        if (!diagnosticsActive()) return;

        String message =
                "DIAG_SYS"
                        + " event=" + event
                        + " thread="
                        + Thread.currentThread().getName()
                        + " detail=" + detail;

        Log.i(TAG, message);
        log(
                Log.INFO,
                TAG,
                message);
    }

    private static String stackSummary() {
        StringBuilder out =
                new StringBuilder();

        StackTraceElement[] stack =
                Thread.currentThread()
                        .getStackTrace();

        int added = 0;

        for (StackTraceElement frame : stack) {
            String cls =
                    frame.getClassName();

            if (cls.equals(
                    Thread.class.getName())
                    || cls.equals(
                    GuardModule.class.getName())) {
                continue;
            }

            if (added++ > 0) {
                out.append(" <- ");
            }

            out.append(cls)
                    .append('.')
                    .append(frame.getMethodName())
                    .append(':')
                    .append(frame.getLineNumber());

            if (added >= 10
                    || out.length() > 1600) {
                break;
            }
        }

        return out.toString();
    }

    private void logOnce(
            String key,
            String message
    ) {
        if (firstHits.add(
                "once-" + key)) {
            log(
                    Log.INFO,
                    TAG,
                    message);
        }
    }
}
