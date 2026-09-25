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
 * System-server keepalive bootstrap.
 *
 * OPlus FlexibleWindow protection stays OEM-state-driven. A second independent
 * BACKGROUND_PROTECTED state keeps selected normal fullscreen tasks alive when
 * focus moves to Home/another package or the display sleeps, without timers.
 */
public final class GuardModule extends XposedModule {
    private static final String TAG = "MiniWindowGuard";
    private static final long SYSTEM_MINI_RETRY_DELAY_MS = 80L;
    private static final int SYSTEM_MINI_MAX_RETRIES = 8;
    private static final int OPLUS_MINI_START_FROM_FULLSCREEN = 1;
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
    private volatile Handler systemHandler;
    private volatile String processName = "";

    @Override
    public void onModuleLoaded(
            XposedModuleInterface.ModuleLoadedParam param
    ) {
        try {
            processName =
                    param == null
                            ? ""
                            : String.valueOf(
                            param.getProcessName());
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
    public void onPackageReady(
            XposedModuleInterface.PackageReadyParam param
    ) {
        if (param == null
                || !param.isFirstPackage()) {
            return;
        }

        String packageName =
                param.getPackageName();

        if (packageName == null
                || packageName.isBlank()
                || "system".equals(processName)
                || !packageName.equals(processName)) {
            return;
        }

        try {
            GuardConfig.initialize(
                    remotePrefs);

            if (!GuardConfig.enabled()
                    || !GuardConfig
                    .backgroundPlaybackPackage(
                            packageName)) {
                return;
            }

            AppPlaybackGuard.install(
                    this,
                    packageName,
                    param.getClassLoader());

            log(
                    Log.INFO,
                    TAG,
                    "APP_SCOPE playback guard ready pkg="
                            + packageName);
        } catch (Throwable t) {
            log(
                    Log.WARN,
                    TAG,
                    "APP_SCOPE playback guard failed pkg="
                            + packageName,
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

        systemHandler = handler;

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
        installOplusEdgeKeepaliveHooks(
                systemClassLoader);
        installBackgroundStopKeepaliveHook(
                systemClassLoader);
        installOplusLockKeepaliveHooks(
                systemClassLoader);
        installActivityRecordCaptureHook(
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
                && current.isForceSupportPackage(packageName);
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
        installOplusFlexibleEventHook(loader);
        installFloatHandleRestoreHook(loader);

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

                    if (pkg != null) {
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
    private void installOplusFlexibleEventHook(
            ClassLoader loader
    ) {
        Class<?> controller =
                load(
                        loader,
                        "com.android.server.wm.FlexibleTaskController");

        if (controller == null) return;

        for (Method method :
                controller.getDeclaredMethods()) {
            if (!"notifyFlexibleTaskEvent"
                    .equals(method.getName())) {
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

                    int event = -1;

                    for (Object arg : args) {
                        if (arg instanceof Integer value
                                && (value == 2002
                                || value == 2003)) {
                            event = value;
                            break;
                        }
                    }

                    if (event != -1) {
                        Object task =
                                findTaskArg(args);

                        int id =
                                task == null
                                        ? -1
                                        : taskId(task);

                        if (id < 0) {
                            for (Object arg : args) {
                                if (arg instanceof Integer value
                                        && value > 0
                                        && value != event) {
                                    id = value;
                                    break;
                                }
                            }
                        }

                        EngineBridge current = engine;

                        if (current != null
                                && id >= 0) {
                            current.onOplusFlexibleEvent(
                                    id,
                                    event);

                            diag(
                                    "OPLUS_FLEX_EVENT",
                                    "taskId=" + id
                                            + " event="
                                            + event);
                        }
                    }

                    return chain.proceed();
                });

                log(
                        Log.INFO,
                        TAG,
                        "SYSTEM_SCOPE installed OPlus flexible event hook "
                                + method.toGenericString());
            } catch (Throwable t) {
                installedHooks.remove(
                        method.toGenericString());
            }
        }
    }

    private void installFloatHandleRestoreHook(
            ClassLoader loader
    ) {
        Class<?> controller =
                load(
                        loader,
                        "com.android.server.wm.FloatHandleController");

        if (controller == null) return;

        for (Method method :
                controller.getDeclaredMethods()) {
            if (!"startActivityByFloatInfo"
                    .equals(method.getName())) {
                continue;
            }

            try {
                method.setAccessible(true);

                String hookKey =
                        "float-handle-restore:"
                                + method.toGenericString();

                if (!installedHooks.add(hookKey)) {
                    continue;
                }

                hook(method).intercept(chain -> {
                    int taskId = -1;

                    for (Object arg :
                            chain.getArgs()) {
                        if (arg instanceof Integer value
                                && value > 0) {
                            taskId = value;
                            break;
                        }

                        if (arg == null) {
                            continue;
                        }

                        taskId =
                                intField(
                                        arg,
                                        "taskId",
                                        -1);

                        if (taskId < 0) {
                            taskId =
                                    intField(
                                            arg,
                                            "mTaskId",
                                            -1);
                        }

                        if (taskId >= 0) {
                            break;
                        }
                    }

                    EngineBridge current = engine;

                    if (current != null
                            && taskId >= 0) {
                        current.onFloatHandleOpened(
                                taskId);

                        diag(
                                "OPLUS_EDGE_RESTORE",
                                "taskId=" + taskId
                                        + " source=startActivityByFloatInfo");
                    }

                    return chain.proceed();
                });

                log(
                        Log.INFO,
                        TAG,
                        "SYSTEM_SCOPE installed FloatHandle restore hook "
                                + method.toGenericString());
            } catch (Throwable t) {
                installedHooks.remove(
                        "float-handle-restore:"
                                + method.toGenericString());
            }
        }
    }

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

                    if (pkg != null) {
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
     * OPlus edge/minimize keepalive.
     *
     * Let FlexibleTaskController finish its own animation and FloatHandle state,
     * but skip the final Task.moveTaskToBack for a selected app. Focus is moved
     * to the task underneath so the hidden edge task does not consume input.
     */
    private void installOplusEdgeKeepaliveHooks(
            ClassLoader loader
    ) {
        Class<?> pauseTaskFragment =
                load(
                        loader,
                        "com.android.server.wm.TaskFragment");

        if (pauseTaskFragment != null) {
            for (Method method :
                    pauseTaskFragment.getDeclaredMethods()) {
                if (!"startPausing"
                        .equals(method.getName())
                        || method.getReturnType()
                        != boolean.class) {
                    continue;
                }

                boolean hasReason = false;

                for (Class<?> parameter :
                        method.getParameterTypes()) {
                    if (parameter == String.class) {
                        hasReason = true;
                        break;
                    }
                }

                if (!hasReason) {
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

                        String reason = null;
                        Object resumingActivity = null;
                        int booleanCount = 0;
                        boolean firstBoolean = false;
                        boolean secondBoolean = false;

                        for (Object arg : args) {
                            if (arg instanceof String) {
                                reason = (String) arg;
                            } else if (arg instanceof Boolean value) {
                                if (booleanCount == 0) {
                                    firstBoolean = value;
                                } else if (booleanCount == 1) {
                                    secondBoolean = value;
                                }
                                booleanCount++;
                            } else if (arg != null
                                    && arg.getClass()
                                    .getName()
                                    .endsWith(
                                            ".ActivityRecord")) {
                                resumingActivity = arg;
                            }
                        }

                        boolean userLeaving =
                                booleanCount >= 2
                                        && firstBoolean;
                        boolean uiSleeping =
                                booleanCount >= 2
                                        ? secondBoolean
                                        : booleanCount == 1
                                        && firstBoolean;

                        Object task =
                                taskFromContainer(
                                        chain.getThisObject());

                        EngineBridge current = engine;

                        if (reason != null
                                && reason.contains(
                                "pauseInRecentsAnim")
                                && current != null
                                && task != null
                                && current
                                .shouldSuppressRecentsPause(
                                        task)) {
                            diag(
                                    "OPLUS_EDGE_PAUSE_BLOCK",
                                    "taskId="
                                            + taskId(task)
                                            + " pkg="
                                            + packageFromObject(task)
                                            + " reason=" + reason);
                            return false;
                        }

                        if (current != null
                                && task != null) {
                            Object resumedActivity =
                                    fieldValue(
                                            chain.getThisObject(),
                                            "mResumedActivity");

                            boolean finishing =
                                    Boolean.TRUE.equals(
                                            fieldValue(
                                                    resumedActivity,
                                                    "finishing"));

                            String resumingPackage =
                                    packageFromObject(
                                            resumingActivity);

                            boolean suppressBackgroundPause =
                                    current
                                    .shouldSuppressBackgroundPause(
                                            task,
                                            resumingPackage,
                                            userLeaving,
                                            uiSleeping,
                                            reason,
                                            finishing);

                            if (!uiSleeping
                                    && current
                                    .shouldAutoMiniWindow(
                                            task)) {
                                scheduleAutoMiniWindow(
                                        task,
                                        packageFromObject(
                                                task));
                            }

                            if (suppressBackgroundPause) {
                                diag(
                                        "BACKGROUND_PAUSE_BLOCK",
                                        "taskId=" + taskId(task)
                                                + " pkg="
                                                + packageFromObject(task)
                                                + " resumingPkg="
                                                + resumingPackage
                                                + " userLeaving="
                                                + userLeaving
                                                + " uiSleeping="
                                                + uiSleeping
                                                + " reason="
                                                + reason);
                                return false;
                            }
                        }

                        return chain.proceed();
                    });

                    log(
                            Log.INFO,
                            TAG,
                            "SYSTEM_SCOPE installed recents pause guard "
                                    + method.toGenericString());
                } catch (Throwable t) {
                    installedHooks.remove(
                            method.toGenericString());
                }
            }
        }

        Class<?> taskExt =
                load(
                        loader,
                        "com.android.server.wm.TaskExtImpl");

        if (taskExt != null) {
            for (Method method :
                    taskExt.getDeclaredMethods()) {
                if (!"moveTaskToBackForPanorama"
                        .equals(method.getName())) {
                    continue;
                }

                try {
                    method.setAccessible(true);

                    if (!installedHooks.add(
                            method.toGenericString())) {
                        continue;
                    }

                    hook(method).intercept(chain -> {
                        Object task =
                                findTaskArg(
                                        chain.getArgs());

                        EngineBridge current = engine;

                        if (current != null
                                && task != null
                                && current.shouldHoldEdgeTask(
                                task)) {
                            focusTaskBehind(task);

                            diag(
                                    "OPLUS_EDGE_MOVE_BACK_BLOCK",
                                    "taskId="
                                            + taskId(task)
                                            + " pkg="
                                            + packageFromObject(task));

                            return null;
                        }

                        return chain.proceed();
                    });
                } catch (Throwable t) {
                    installedHooks.remove(
                            method.toGenericString());
                }
            }
        }

        Class<?> taskClass =
                load(
                        loader,
                        "com.android.server.wm.Task");

        if (taskClass != null) {
            for (Method method :
                    taskClass.getDeclaredMethods()) {
                if (!"prepareSurfaces"
                        .equals(method.getName())
                        || method.getParameterCount()
                        != 0) {
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

                        Object task =
                                chain.getThisObject();

                        EngineBridge current = engine;

                        if (current != null
                                && current.isEdgeHungTask(
                                task)) {
                            Object surface =
                                    invokeNoArg(
                                            task,
                                            "getSurfaceControl");

                            Object valid =
                                    invokeNoArg(
                                            surface,
                                            "isValid");

                            if (Boolean.TRUE.equals(valid)) {
                                Object transaction =
                                        invokeNoArg(
                                                task,
                                                "getSyncTransaction");

                                invokeMethod(
                                        transaction,
                                        "hide",
                                        surface);

                                diag(
                                        "OPLUS_EDGE_SURFACE_HIDE",
                                        "taskId="
                                                + taskId(task)
                                                + " pkg="
                                                + packageFromObject(task));
                            }
                        }

                        return result;
                    });
                } catch (Throwable t) {
                    installedHooks.remove(
                            method.toGenericString());
                }
            }
        }

        Class<?> displayContent =
                load(
                        loader,
                        "com.android.server.wm.DisplayContent");

        if (displayContent != null) {
            for (Method method :
                    displayContent.getDeclaredMethods()) {
                if (!"setFocusedApp"
                        .equals(method.getName())
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
                        List<Object> args =
                                chain.getArgs();

                        if (!args.isEmpty()
                                && args.get(0) != null) {
                            Object task =
                                    invokeNoArg(
                                            args.get(0),
                                            "getTask");

                            EngineBridge current = engine;

                            if (current != null) {
                                current.onFocusedActivity(
                                        args.get(0));
                            }

                            if (current != null
                                    && current.isEdgeHungTask(
                                    task)) {
                                Object behind =
                                        findBehindActivity(
                                                task);

                                if (behind != null) {
                                    try {
                                        args.set(
                                                0,
                                                behind);

                                        diag(
                                                "OPLUS_EDGE_FOCUS_REDIRECT",
                                                "taskId="
                                                        + taskId(task)
                                                        + " pkg="
                                                        + packageFromObject(task));
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        }

                        return chain.proceed();
                    });
                } catch (Throwable t) {
                    installedHooks.remove(
                            method.toGenericString());
                }
            }
        }
    }

    private void installOplusLockKeepaliveHooks(
            ClassLoader loader
    ) {
        Class<?> flexible =
                load(
                        loader,
                        "com.android.server.wm.FlexibleTaskController");

        if (flexible != null) {
            for (Method method :
                    flexible.getDeclaredMethods()) {
                String name = method.getName();

                if (!"notifyKeyguardStateChanged"
                        .equals(name)
                        && !"onScreenLockedChanged"
                        .equals(name)) {
                    continue;
                }

                try {
                    method.setAccessible(true);

                    if (!installedHooks.add(
                            method.toGenericString())) {
                        continue;
                    }

                    if ("onScreenLockedChanged"
                            .equals(name)) {
                        hook(method).intercept(chain -> {
                            EngineBridge current = engine;

                            if (current != null) {
                                // OPlus reaches this callback before it hides the
                                // flexible Activity. Arm keepalive here so all
                                // downstream sleep/freeze hooks see the protected
                                // state in the same lock transaction.
                                current.preArmLockKeepAlive(
                                        "FlexibleTaskController.onScreenLockedChanged");

                                diag(
                                        "OPLUS_LOCK_PREARM",
                                        "source=onScreenLockedChanged");
                            }

                            return chain.proceed();
                        });

                        continue;
                    }

                    hook(method).intercept(chain -> {
                        List<Object> args =
                                chain.getArgs();

                        Boolean showing = null;

                        if (args.size() >= 2
                                && args.get(1)
                                instanceof Boolean) {
                            showing =
                                    (Boolean) args.get(1);
                        } else {
                            int booleanIndex = 0;

                            for (Object arg : args) {
                                if (arg instanceof Boolean) {
                                    if (booleanIndex++ == 1) {
                                        showing =
                                                (Boolean) arg;
                                        break;
                                    }
                                }
                            }
                        }

                        EngineBridge current = engine;

                        if (current != null
                                && showing != null) {
                            // Keep as a second OPlus-native signal. The early
                            // onScreenLockedChanged hook already armed the task.
                            current.onKeyguardStateChanged(
                                    showing);

                            diag(
                                    "OPLUS_KEYGUARD_STATE",
                                    "showing="
                                            + showing);
                        }

                        return chain.proceed();
                    });
                } catch (Throwable t) {
                    installedHooks.remove(
                            method.toGenericString());
                }
            }
        }

        // Do not suppress ActivityRecord.setVisibility(false) or
        // makeInvisible() during keyguard. Playback keepalive and window
        // visibility are separate: the task can stay alive while Android hides
        // its surface behind the lock screen. Sleep/freezer/kill guards below
        // remain active, and the app-process playback guard handles
        // lifecycle-triggered player pause calls.

        Class<?> taskFragment =
                load(
                        loader,
                        "com.android.server.wm.TaskFragment");

        if (taskFragment != null) {
            for (Method method :
                    taskFragment.getDeclaredMethods()) {
                if (!"sleepIfPossible"
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
                        for (Object arg :
                                chain.getArgs()) {
                            if (Boolean.TRUE.equals(arg)) {
                                return chain.proceed();
                            }
                        }

                        Object task =
                                taskFromContainer(
                                        chain.getThisObject());

                        EngineBridge current = engine;

                        if (current != null
                                && task != null
                                && current.shouldKeepTaskAwake(
                                task)) {
                            diag(
                                    "OPLUS_LOCK_SLEEP_BLOCK",
                                    "taskId="
                                            + taskId(task)
                                            + " pkg="
                                            + packageFromObject(task));

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

        Class<?> hans =
                load(
                        loader,
                        "com.android.server.hans.freeze.HansCGroup");

        if (hans != null) {
            for (Method method :
                    hans.getDeclaredMethods()) {
                if (!"hansFreezeLocked"
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
                        int uid =
                                extractUid(
                                        chain.getArgs());

                        if (uid >= 10000
                                && isTargetUid(uid)) {
                            diag(
                                    "OPLUS_HANS_FREEZE_BLOCK",
                                    "uid=" + uid);
                            return false;
                        }

                        return chain.proceed();
                    });
                } catch (Throwable t) {
                    installedHooks.remove(
                            method.toGenericString());
                }
            }
        }

        Class<?> optimizer =
                load(
                        loader,
                        "com.android.server.am.CachedAppOptimizer");

        if (optimizer != null) {
            for (Method method :
                    optimizer.getDeclaredMethods()) {
                if (!"freezeAppAsyncInternalLSP"
                        .equals(method.getName())) {
                    continue;
                }

                try {
                    method.setAccessible(true);

                    if (!installedHooks.add(
                            method.toGenericString())) {
                        continue;
                    }

                    hook(method).intercept(chain -> {
                        Object process =
                                findProcessRecordArg(
                                        chain.getArgs());

                        String processName =
                                processName(
                                        process);

                        String pkg =
                                targetPackageForProcess(
                                        processName);

                        if (pkg != null) {
                            diag(
                                    "OPLUS_AOSP_FREEZE_BLOCK",
                                    "pkg=" + pkg
                                            + " process="
                                            + processName);
                            return null;
                        }

                        return chain.proceed();
                    });
                } catch (Throwable t) {
                    installedHooks.remove(
                            method.toGenericString());
                }
            }
        }

        Class<?> ams =
                load(
                        loader,
                        "com.android.server.am.ActivityManagerService");

        if (ams != null) {
            for (Method method :
                    ams.getDeclaredMethods()) {
                if (!"doStopUidLocked"
                        .equals(method.getName())) {
                    continue;
                }

                try {
                    method.setAccessible(true);

                    if (!installedHooks.add(
                            method.toGenericString())) {
                        continue;
                    }

                    hook(method).intercept(chain -> {
                        int uid =
                                extractUid(
                                        chain.getArgs());

                        if (uid >= 10000
                                && isTargetUid(uid)) {
                            diag(
                                    "OPLUS_STOP_UID_BLOCK",
                                    "uid=" + uid);
                            return null;
                        }

                        return chain.proceed();
                    });
                } catch (Throwable t) {
                    installedHooks.remove(
                            method.toGenericString());
                }
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

                    try {
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

                        EngineBridge current =
                                engine;

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
                    } catch (Throwable t) {
                        log(
                                Log.WARN,
                                TAG,
                                "SYSTEM_SCOPE ActivityRecord.setState observer fail-open",
                                t);
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

    private void installBackgroundStopKeepaliveHook(
            ClassLoader loader
    ) {
        Class<?> record =
                load(
                        loader,
                        "com.android.server.wm.ActivityRecord");

        if (record == null) return;

        for (Method method :
                record.getDeclaredMethods()) {
            if (!"stopIfPossible"
                    .equals(method.getName())
                    || method.getReturnType()
                    != void.class) {
                continue;
            }

            try {
                method.setAccessible(true);

                String hookKey =
                        "background-stop:"
                                + method.toGenericString();

                if (!installedHooks.add(hookKey)) {
                    continue;
                }

                hook(method).intercept(chain -> {
                    try {
                        Object activityRecord =
                                chain.getThisObject();

                        String pkg =
                                activityPackage(
                                        activityRecord);

                        EngineBridge current =
                                engine;

                        if (current == null
                                || !current
                                .isBackgroundPlaybackPackage(
                                        pkg)) {
                            return chain.proceed();
                        }

                        Object task =
                                invokeNoArg(
                                        activityRecord,
                                        "getTask");

                        boolean finishing =
                                Boolean.TRUE.equals(
                                        fieldValue(
                                                activityRecord,
                                                "finishing"));

                        if (task == null
                                || !current
                                .shouldBlockBackgroundStop(
                                        task,
                                        finishing)) {
                            return chain.proceed();
                        }

                        invokeNoArg(
                                activityRecord,
                                "resumeKeyDispatchingLocked");

                        diag(
                                "BACKGROUND_STOP_BLOCK",
                                "taskId="
                                        + taskId(task)
                                        + " pkg="
                                        + pkg
                                        + " activity="
                                        + fieldValue(
                                        activityRecord,
                                        "mActivityComponent"));

                        return null;
                    } catch (Throwable t) {
                        log(
                                Log.WARN,
                                TAG,
                                "BACKGROUND_STOP_GUARD fail-open",
                                t);

                        return chain.proceed();
                    }
                });

                log(
                        Log.INFO,
                        TAG,
                        "SYSTEM_SCOPE installed background stop guard "
                                + method.toGenericString());
            } catch (Throwable t) {
                installedHooks.remove(
                        hookKey(method));
            }
        }
    }

    private static String hookKey(
            Method method
    ) {
        return "background-stop:"
                + (method == null
                ? ""
                : method.toGenericString());
    }

    private void scheduleAutoMiniWindow(
            Object task,
            String packageName
    ) {
        Handler handler =
                systemHandler;

        if (handler == null
                || task == null
                || packageName == null
                || packageName.isBlank()) {
            return;
        }

        int id =
                taskId(task);

        if (id < 0) {
            return;
        }

        handler.post(
                () -> {
                    try {
                        EngineBridge current =
                                engine;

                        if (current == null
                                || !current
                                .isBackgroundPlaybackPackage(
                                        packageName)) {
                            return;
                        }

                        boolean requested =
                                requestSystemFlexibleWindow(
                                        id,
                                        packageName);

                        diag(
                                "BACKGROUND_SYSTEM_MINI_TOGGLE",
                                "pkg=" + packageName
                                        + " taskId="
                                        + id
                                        + " requested="
                                        + requested);

                        if (!requested) {
                            return;
                        }

                        waitForSystemFlexibleThenMini(
                                task,
                                packageName,
                                0);
                    } catch (Throwable t) {
                        log(
                                Log.WARN,
                                TAG,
                                "BACKGROUND_SYSTEM_MINI fail-open pkg="
                                        + packageName,
                                t);
                    }
                });
    }

    /**
     * Enter the exact OxygenOS flexible-window path for an already existing
     * task. This uses the OEM ActivityTaskManager API instead of constructing
     * ActivityOptions/windowingMode bundles ourselves, so bounds, caption,
     * corner radius, input, animations and restore behavior stay system-owned.
     */
    private boolean requestSystemFlexibleWindow(
            int taskId,
            String packageName
    ) {
        try {
            Class<?> type =
                    load(
                            systemClassLoader,
                            "android.app.OplusActivityTaskManager");

            if (type == null) {
                diag(
                        "BACKGROUND_SYSTEM_MINI_TOGGLE_FAIL",
                        "pkg=" + packageName
                                + " taskId="
                                + taskId
                                + " reason=class-missing");
                return false;
            }

            Method getInstance =
                    type.getMethod(
                            "getInstance");

            Object instance =
                    getInstance.invoke(
                            null);

            if (instance == null) {
                return false;
            }

            Method toggle =
                    type.getMethod(
                            "toggleFlexibleWindow",
                            android.os.IBinder.class,
                            int.class,
                            boolean.class,
                            boolean.class);

            Object result =
                    toggle.invoke(
                            instance,
                            null,
                            taskId,
                            true,
                            true);

            boolean accepted =
                    toggle.getReturnType()
                            != boolean.class
                            || !Boolean.FALSE.equals(
                            result);

            diag(
                    accepted
                            ? "BACKGROUND_SYSTEM_FLEXIBLE_REQUEST"
                            : "BACKGROUND_SYSTEM_FLEXIBLE_REJECTED",
                    "pkg=" + packageName
                            + " taskId="
                            + taskId
                            + " method="
                            + toggle.toGenericString()
                            + " result="
                            + result);

            return accepted;
        } catch (Throwable t) {
            diag(
                    "BACKGROUND_SYSTEM_MINI_TOGGLE_FAIL",
                    "pkg=" + packageName
                            + " taskId="
                            + taskId
                            + " error="
                            + t.getClass()
                            .getSimpleName());

            return false;
        }
    }

    /**
     * The OEM mini API expects a real Zoom/FlexibleWindow to exist first.
     * Poll only the OEM-reported state; do not synthesize bounds or window
     * configuration. As soon as OxygenOS reports the task flexible, hand the
     * second step back to its own mini/float-handle implementation.
     */
    private void waitForSystemFlexibleThenMini(
            Object task,
            String packageName,
            int attempt
    ) {
        Handler handler =
                systemHandler;

        if (handler == null
                || task == null) {
            return;
        }

        handler.postDelayed(
                () -> {
                    try {
                        EngineBridge current =
                                engine;

                        if (current == null
                                || !current
                                .isBackgroundPlaybackPackage(
                                        packageName)) {
                            return;
                        }

                        if (current
                                .isOplusFlexibleTask(
                                        task)) {
                            requestSystemMiniIcon(
                                    packageName,
                                    taskId(task));
                            return;
                        }

                        if (attempt
                                >= SYSTEM_MINI_MAX_RETRIES) {
                            diag(
                                    "BACKGROUND_SYSTEM_MINI_TIMEOUT",
                                    "pkg=" + packageName
                                            + " taskId="
                                            + taskId(task)
                                            + " phase=wait-flexible");
                            return;
                        }

                        waitForSystemFlexibleThenMini(
                                task,
                                packageName,
                                attempt + 1);
                    } catch (Throwable t) {
                        log(
                                Log.WARN,
                                TAG,
                                "BACKGROUND_SYSTEM_MINI wait fail-open pkg="
                                        + packageName,
                                t);
                    }
                },
                SYSTEM_MINI_RETRY_DELAY_MS);
    }

    /**
     * Ask OxygenOS to convert its real small window into the stock mini/float
     * icon. startWay=1 is the OEM "from fullscreen" path used for the
     * fullscreen -> zoom -> mini transition.
     */
    private boolean requestSystemMiniIcon(
            String packageName,
            int taskId
    ) {
        try {
            Class<?> type =
                    load(
                            systemClassLoader,
                            "android.app.OplusActivityTaskManager");

            if (type == null) {
                return false;
            }

            Method getInstance =
                    type.getMethod(
                            "getInstance");

            Object instance =
                    getInstance.invoke(
                            null);

            if (instance == null) {
                return false;
            }

            Method mini =
                    type.getMethod(
                            "startMiniZoomFromZoom",
                            int.class);

            mini.invoke(
                    instance,
                    OPLUS_MINI_START_FROM_FULLSCREEN);

            diag(
                    "BACKGROUND_SYSTEM_MINI_ICON",
                    "pkg=" + packageName
                            + " taskId="
                            + taskId
                            + " startWay="
                            + OPLUS_MINI_START_FROM_FULLSCREEN
                            + " method="
                            + mini.toGenericString());

            return true;
        } catch (Throwable t) {
            diag(
                    "BACKGROUND_SYSTEM_MINI_ICON_FAIL",
                    "pkg=" + packageName
                            + " taskId="
                            + taskId
                            + " error="
                            + t.getClass()
                            .getSimpleName());

            return false;
        }
    }

    private static Object fieldValueByTypeSuffix(
            Object receiver,
            String typeSuffix
    ) {
        if (receiver == null
                || typeSuffix == null) {
            return null;
        }

        for (Class<?> current =
             receiver.getClass();
             current != null;
             current = current.getSuperclass()) {
            for (Field field :
                    current.getDeclaredFields()) {
                if (!field.getType()
                        .getName()
                        .endsWith(typeSuffix)) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    return field.get(
                            receiver);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }

        return null;
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

                    EngineBridge current = engine;

                    if (!isTargetPackage(pkg)
                            || current == null
                            || !current.shouldBlockTaskRemoval(
                            pkg)) {
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

                    EngineBridge current = engine;

                    if (current == null
                            || !current.shouldBlockTaskRemoval(
                            targetPackage)) {
                        diag(
                                "KILL_GUARD_PASS",
                                "reason=ordinary-background-task-removal"
                                        + " pkg=" + targetPackage
                                        + " pid=" + pid
                                        + " process=" + processName);
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

    private Object findBehindActivity(
            Object floatTask
    ) {
        try {
            Object controller =
                    getFlexibleTaskController();

            if (controller == null) {
                return null;
            }

            Object behind =
                    invokeMethod(
                            controller,
                            "getTaskUnderFlexible",
                            floatTask);

            if (behind == null
                    || !Boolean.TRUE.equals(
                    invokeNoArg(
                            behind,
                            "isTopActivityFocusable"))
                    || !Boolean.TRUE.equals(
                    invokeNoArg(
                            behind,
                            "isVisible"))) {
                return null;
            }

            return invokeNoArg(
                    behind,
                    "getTopNonFinishingActivity");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object getFlexibleTaskController() {
        try {
            Class<?> service =
                    load(
                            systemClassLoader,
                            "com.android.server.wm.FlexibleWindowManagerService");

            if (service == null) {
                return null;
            }

            Method getInstance = null;

            for (Method method :
                    service.getDeclaredMethods()) {
                if ("getInstance"
                        .equals(method.getName())
                        && java.lang.reflect.Modifier
                        .isStatic(
                                method.getModifiers())) {
                    getInstance = method;
                    break;
                }
            }

            if (getInstance == null) {
                return null;
            }

            getInstance.setAccessible(true);

            Object instance;

            if (getInstance.getParameterCount()
                    == 0) {
                instance =
                        getInstance.invoke(null);
            } else {
                Object[] args =
                        new Object[
                                getInstance
                                        .getParameterCount()];
                instance =
                        getInstance.invoke(
                                null,
                                args);
            }

            return invokeNoArg(
                    instance,
                    "getFlexibleTaskController");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void focusTaskBehind(Object task) {
        try {
            Object behind =
                    findBehindActivity(task);

            if (behind == null) {
                return;
            }

            Object displayContent =
                    fieldValue(
                            task,
                            "mDisplayContent");

            if (displayContent == null) {
                return;
            }

            invokeMethod(
                    displayContent,
                    "setFocusedApp",
                    behind);

            Object wmService =
                    fieldValue(
                            displayContent,
                            "mWmService");

            invokeMethod(
                    wmService,
                    "updateFocusedWindowLocked",
                    0,
                    true);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isSleepingActivity(
            Object activityRecord
    ) {
        Object taskFragment =
                invokeNoArg(
                        activityRecord,
                        "getTaskFragment");

        Object sleeping =
                invokeNoArg(
                        taskFragment,
                        "shouldSleepActivities");

        if (sleeping instanceof Boolean) {
            return (Boolean) sleeping;
        }

        Object displayContent =
                fieldValue(
                        activityRecord,
                        "mDisplayContent");

        sleeping =
                invokeNoArg(
                        displayContent,
                        "isSleeping");

        return Boolean.TRUE.equals(
                sleeping);
    }

    private static Object taskFromContainer(
            Object container
    ) {
        if (container == null) {
            return null;
        }

        if ("com.android.server.wm.Task"
                .equals(
                        container.getClass()
                                .getName())) {
            return container;
        }

        Object task =
                invokeNoArg(
                        container,
                        "getTask");

        if (task != null) {
            return task;
        }

        return fieldValue(
                container,
                "mTask");
    }

    private static Object findTaskArg(
            List<Object> args
    ) {
        if (args == null) {
            return null;
        }

        for (Object arg : args) {
            if (arg == null) continue;

            if ("com.android.server.wm.Task"
                    .equals(
                            arg.getClass()
                                    .getName())
                    || findField(
                    arg.getClass(),
                    "mTaskId") != null) {
                return arg;
            }
        }

        return null;
    }

    private static Object findProcessRecordArg(
            List<Object> args
    ) {
        if (args == null) {
            return null;
        }

        for (Object arg : args) {
            if (arg == null) continue;

            String name =
                    arg.getClass()
                            .getName();

            if (name.endsWith(
                    ".ProcessRecord")
                    || findField(
                    arg.getClass(),
                    "processName") != null
                    || findField(
                    arg.getClass(),
                    "mProcessName") != null) {
                return arg;
            }
        }

        return null;
    }

    private static String processName(
            Object processRecord
    ) {
        if (processRecord == null) {
            return null;
        }

        for (String field : new String[]{
                "processName",
                "mProcessName"
        }) {
            Object value =
                    fieldValue(
                            processRecord,
                            field);

            if (value instanceof String) {
                return (String) value;
            }
        }

        return null;
    }

    private static int extractUid(
            List<Object> args
    ) {
        if (args == null) {
            return -1;
        }

        for (Object arg : args) {
            if (arg instanceof Integer) {
                int value =
                        (Integer) arg;

                if (value >= 10000) {
                    return value;
                }
            }

            if (arg == null) continue;

            Object uid =
                    invokeNoArg(
                            arg,
                            "getUid");

            if (uid instanceof Number) {
                int value =
                        ((Number) uid)
                                .intValue();

                if (value >= 10000) {
                    return value;
                }
            }

            for (String field :
                    new String[]{
                            "mUid",
                            "uid"
                    }) {
                Object value =
                        fieldValue(
                                arg,
                                field);

                if (value instanceof Number) {
                    int parsed =
                            ((Number) value)
                                    .intValue();

                    if (parsed >= 10000) {
                        return parsed;
                    }
                }
            }
        }

        return -1;
    }

    private static int taskId(Object task) {
        if (task == null) {
            return -1;
        }

        Object id =
                invokeNoArg(
                        task,
                        "getTaskId");

        if (id instanceof Number) {
            return ((Number) id)
                    .intValue();
        }

        return intField(
                task,
                "mTaskId",
                -1);
    }

    private static Object invokeMethod(
            Object receiver,
            String name,
            Object... args
    ) {
        if (receiver == null) {
            return null;
        }

        Class<?> current =
                receiver.getClass();

        while (current != null) {
            for (Method method :
                    current.getDeclaredMethods()) {
                if (!name.equals(
                        method.getName())
                        || method.getParameterCount()
                        != args.length) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    return method.invoke(
                            receiver,
                            args);
                } catch (IllegalArgumentException ignored) {
                } catch (Throwable ignored) {
                    return null;
                }
            }

            current =
                    current.getSuperclass();
        }

        return null;
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
