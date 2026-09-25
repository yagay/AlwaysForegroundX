package com.yagay.MiniWindowGuard;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passive runtime state for OxygenOS / ColorOS FlexibleWindow.
 *
 * MiniWindowGuard never starts apps and never requests a window transition.
 * OxygenOS owns launch, enter/exit flexible mode, surfaces, animations, input
 * and navigation. This class only observes the OEM task and decides whether a
 * package from the "always foreground" list is currently eligible for keepalive.
 */
final class OplusFlexibleWindowController {
    interface Logger {
        void log(String event, String detail);
    }

    private static final long UNLOCK_GRACE_MS = 2500L;
    private static final int EVENT_MINIMIZE_TO_FLOAT_HANDLE = 2002;
    private static final int EVENT_EXIT_TO_BACK = 2003;

    private final Handler handler;
    private final Logger logger;
    private final Map<Integer, Session> sessions =
            new ConcurrentHashMap<>();

    private volatile Context systemContext;
    private volatile boolean running;
    private volatile boolean keyguardShowing;

    OplusFlexibleWindowController(
            Handler handler,
            Context systemContext,
            Logger logger
    ) {
        this.handler = handler;
        this.systemContext = systemContext;
        this.logger = logger;
    }

    void start() {
        if (running) return;
        running = true;

        log(
                "OPLUS_ENGINE_READY",
                "mode=passive"
                        + " foregroundApps="
                        + GuardConfig.stringSet(
                        ConfigKeys.FOREGROUND_PACKAGES)
                        .size()
                        + " backgroundPlaybackApps="
                        + GuardConfig.stringSet(
                        ConfigKeys.BACKGROUND_PLAYBACK_PACKAGES)
                        .size()
                        + " forceSupportApps="
                        + GuardConfig.stringSet(
                        ConfigKeys.FORCE_SUPPORT_PACKAGES)
                        .size());
    }

    void shutdown() {
        running = false;
        sessions.clear();
    }

    int activeSessionCount() {
        int count = 0;

        for (Session session : sessions.values()) {
            if (isSessionProtected(session)) {
                count++;
            }
        }

        return count;
    }

    boolean wantsPackage(String packageName) {
        return GuardConfig.windowKeepalivePackage(
                packageName);
    }

    boolean isKnownPackage(String packageName) {
        return GuardConfig.windowKeepalivePackage(
                packageName)
                || GuardConfig.forceSupportPackage(
                packageName);
    }

    boolean isForceSupportPackage(
            String packageName
    ) {
        return GuardConfig.forceSupportPackage(
                packageName);
    }

    boolean isForegroundPackage(
            String packageName
    ) {
        return GuardConfig.foregroundPackage(
                packageName);
    }

    boolean isBackgroundPlaybackPackage(
            String packageName
    ) {
        return GuardConfig.backgroundPlaybackPackage(
                packageName);
    }

    boolean isProtectedPackage(
            String packageName
    ) {
        if (!GuardConfig.windowKeepalivePackage(
                packageName)) {
            return false;
        }

        for (Session session : sessions.values()) {
            if (session.active
                    && packageName.equals(
                    session.packageName)
                    && isSessionProtected(
                    session)) {
                return true;
            }
        }

        return false;
    }

    String protectedPackageForProcess(
            String processName
    ) {
        if (processName == null
                || processName.isBlank()) {
            return null;
        }

        for (Session session : sessions.values()) {
            if (!session.active
                    || !isSessionProtected(
                    session)) {
                continue;
            }

            String pkg =
                    session.packageName;

            if (processName.equals(pkg)
                    || processName.startsWith(
                    pkg + ":")) {
                return pkg;
            }
        }

        return null;
    }

    void capture(
            Object activityRecord,
            String packageName
    ) {
        if (!running
                || activityRecord == null
                || !GuardConfig.windowKeepalivePackage(
                packageName)) {
            return;
        }

        Object task =
                activityTask(
                        activityRecord);

        if (task == null) {
            log(
                    "OPLUS_TRACK_SKIP",
                    "pkg=" + packageName
                            + " reason=no-task");
            return;
        }

        if (systemContext == null) {
            systemContext =
                    deriveSystemContext(task);
        }

        int taskId =
                taskId(task);

        if (taskId < 0) {
            return;
        }

        Session session =
                sessions.compute(
                        taskId,
                        (id, current) -> {
                            if (current == null
                                    || !current.active
                                    || !packageName.equals(
                                    current.packageName)) {
                                current =
                                        new Session(
                                                taskId,
                                                packageName);
                            }

                            current.taskObject =
                                    task;
                            current.activityRecord =
                                    activityRecord;
                            current.active = true;
                            current.lastSeenElapsed =
                                    SystemClock
                                            .elapsedRealtime();

                            return current;
                        });

        refreshSessionState(
                session,
                "activity-resumed");

        log(
                "OPLUS_TASK_TRACKED",
                "pkg=" + packageName
                        + " taskId=" + taskId
                        + " activity="
                        + activityComponent(
                        activityRecord)
                        + " flexible="
                        + isFlexibleTask(task)
                        + " floating="
                        + isInFloatingList(taskId)
                        + " keyguard="
                        + keyguardShowing
                        + " protected="
                        + isSessionProtected(session));
    }

    void onOplusTaskInfoChanged(
            Object taskInfo
    ) {
        if (!running
                || taskInfo == null) {
            return;
        }

        int taskId =
                taskInfoId(taskInfo);

        if (taskId < 0) return;

        String pkg =
                taskInfoPackage(taskInfo);

        Session session =
                sessions.get(taskId);

        if (session == null) {
            if (!GuardConfig.windowKeepalivePackage(
                    pkg)) {
                return;
            }

            session =
                    new Session(
                            taskId,
                            pkg);
            sessions.put(
                    taskId,
                    session);
        } else if (pkg != null
                && !pkg.equals(
                session.packageName)) {
            if (!GuardConfig.windowKeepalivePackage(
                    pkg)) {
                sessions.remove(
                        taskId,
                        session);
                return;
            }

            session =
                    new Session(
                            taskId,
                            pkg);
            sessions.put(
                    taskId,
                    session);
        }

        boolean embedded =
                booleanField(
                        taskInfo,
                        "isInFlexibleEmbedded",
                        false);

        Rect bounds =
                taskInfoBounds(taskInfo);

        Rect maxBounds =
                taskInfoMaxBounds(taskInfo);

        boolean bounded =
                bounds != null
                        && maxBounds != null
                        && !bounds.isEmpty()
                        && !maxBounds.isEmpty()
                        && !bounds.equals(
                        maxBounds);

        Bundle oplusExtras =
                taskInfoOplusExtras(
                        taskInfo);

        int flexibleState =
                oplusExtras == null
                        ? 0
                        : oplusExtras.getInt(
                        "key_flexible_task_state",
                        0);

        boolean superMini =
                oplusExtras != null
                        && oplusExtras.getBoolean(
                        "flexible_super_mini_state",
                        false);

        session.oemReportedFlexible =
                embedded
                        || bounded
                        || flexibleState != 0
                        || superMini;
        session.lastOplusStateElapsed =
                SystemClock.elapsedRealtime();
        session.active = true;

        if (keyguardShowing
                && session.oemReportedFlexible) {
            session.lockKeepAlive = true;
        }

        refreshSessionState(
                session,
                "task-info");

        log(
                "OPLUS_TASK_INFO",
                "pkg=" + session.packageName
                        + " taskId=" + taskId
                        + " embedded=" + embedded
                        + " bounded=" + bounded
                        + " flexibleState="
                        + flexibleState
                        + " superMini="
                        + superMini
                        + " floating="
                        + isInFloatingList(taskId)
                        + " lockKeepAlive="
                        + session.lockKeepAlive
                        + " bounds=" + bounds
                        + " maxBounds=" + maxBounds
                        + " protected="
                        + isSessionProtected(
                        session));
    }

    void onOplusTaskVanished(
            Object taskInfo
    ) {
        if (taskInfo == null) return;

        int taskId =
                taskInfoId(taskInfo);

        Session session =
                sessions.get(taskId);

        if (session == null) return;

        session.oemReportedFlexible = false;
        session.lastOplusStateElapsed =
                SystemClock.elapsedRealtime();

        boolean taskRunning =
                booleanField(
                        taskInfo,
                        "isRunning",
                        true);

        int displayId =
                intField(
                        taskInfo,
                        "displayId",
                        0);

        if (!taskRunning
                || displayId < 0) {
            session.active = false;
            sessions.remove(
                    taskId,
                    session);
        }

        log(
                "OPLUS_TASK_VANISHED",
                "pkg=" + session.packageName
                        + " taskId=" + taskId
                        + " isRunning="
                        + taskRunning
                        + " displayId="
                        + displayId
                        + " floating="
                        + isInFloatingList(taskId)
                        + " lockKeepAlive="
                        + session.lockKeepAlive);
    }

    void onOplusFlexibleEvent(
            int taskId,
            int event
    ) {
        if (!running || taskId < 0) {
            return;
        }

        Session session =
                sessions.get(taskId);

        if (session == null
                || !session.active
                || !GuardConfig.windowKeepalivePackage(
                session.packageName)) {
            return;
        }

        if (event
                == EVENT_MINIMIZE_TO_FLOAT_HANDLE) {
            session.edgeMinimizeRequested = true;
            session.edgeHung = true;
            session.lastSeenElapsed =
                    SystemClock.elapsedRealtime();

            log(
                    "OPLUS_EDGE_MINIMIZE_REQUEST",
                    "pkg=" + session.packageName
                            + " taskId=" + taskId
                            + " event=" + event);
            return;
        }

        if (event == EVENT_EXIT_TO_BACK) {
            session.edgeMinimizeRequested = false;
            session.edgeHung = false;

            log(
                    "OPLUS_EDGE_MINIMIZE_CANCEL",
                    "pkg=" + session.packageName
                            + " taskId=" + taskId
                            + " event=" + event);
        }
    }

    void preArmLockKeepAlive(
            String reason
    ) {
        if (!running) return;

        int armed = 0;

        for (Session session :
                sessions.values()) {
            if (!session.active
                    || !GuardConfig
                    .windowKeepalivePackage(
                            session.packageName)) {
                continue;
            }

            if (!isNativeOplusWindow(
                    session)) {
                continue;
            }

            if (!session.lockKeepAlive) {
                session.lockKeepAlive = true;
                armed++;
            }
        }

        log(
                "OPLUS_LOCK_PREARM",
                "reason=" + reason
                        + " armed=" + armed);
    }

    void onKeyguardStateChanged(
            boolean showing
    ) {
        keyguardShowing = showing;

        if (showing) {
            for (Session session :
                    sessions.values()) {
                if (!session.active
                        || !GuardConfig
                        .foregroundPackage(
                                session.packageName)) {
                    continue;
                }

                boolean nativeOplus =
                        isNativeOplusWindow(
                                session);

                session.lockKeepAlive =
                        nativeOplus;

                if (nativeOplus) {
                    log(
                            "OPLUS_LOCK_KEEPALIVE",
                            "pkg="
                                    + session.packageName
                                    + " taskId="
                                    + session.taskId
                                    + " enabled=true");
                }
            }
            return;
        }

        handler.postDelayed(
                () -> {
                    if (keyguardShowing) {
                        return;
                    }

                    for (Session session :
                            sessions.values()) {
                        if (session.lockKeepAlive) {
                            session.lockKeepAlive =
                                    false;

                            log(
                                    "OPLUS_LOCK_KEEPALIVE",
                                    "pkg="
                                            + session.packageName
                                            + " taskId="
                                            + session.taskId
                                            + " enabled=false"
                                            + " reason=unlock");
                        }
                    }
                },
                UNLOCK_GRACE_MS);
    }

    boolean shouldSuppressRecentsPause(
            Object task
    ) {
        Session session =
                sessionForTask(task);

        if (session == null
                || !session.active
                || !GuardConfig.windowKeepalivePackage(
                session.packageName)
                || (!session.edgeMinimizeRequested
                && !session.edgeHung)) {
            return false;
        }

        session.edgeHung = true;
        session.taskObject = task;
        session.lastSeenElapsed =
                SystemClock.elapsedRealtime();

        log(
                "OPLUS_EDGE_PAUSE_SUPPRESS",
                "pkg=" + session.packageName
                        + " taskId="
                        + session.taskId
                        + " minimizeRequested="
                        + session.edgeMinimizeRequested);

        return true;
    }

    boolean shouldHoldEdgeTask(Object task) {
        Session session =
                sessionForTask(task);

        if (session == null
                || !session.active
                || !GuardConfig
                .windowKeepalivePackage(
                        session.packageName)
                || !isInFloatingList(
                session.taskId)) {
            return false;
        }

        session.edgeHung = true;
        session.edgeMinimizeRequested = false;
        session.taskObject = task;
        session.lastSeenElapsed =
                SystemClock.elapsedRealtime();

        log(
                "OPLUS_EDGE_KEEPALIVE",
                "pkg=" + session.packageName
                        + " taskId="
                        + session.taskId
                        + " enabled=true");

        return true;
    }

    boolean isEdgeHungTask(Object task) {
        Session session =
                sessionForTask(task);

        if (session == null
                || !session.edgeHung) {
            return false;
        }

        if (!isInFloatingList(
                session.taskId)
                && !session.edgeMinimizeRequested) {
            session.edgeHung = false;

            log(
                    "OPLUS_EDGE_KEEPALIVE",
                    "pkg="
                            + session.packageName
                            + " taskId="
                            + session.taskId
                            + " enabled=false"
                            + " reason=left-floating-list");

            return false;
        }

        return true;
    }

    boolean shouldKeepTaskAwake(Object task) {
        Session session =
                sessionForTask(task);

        return session != null
                && session.active
                && GuardConfig
                .windowKeepalivePackage(
                        session.packageName)
                && (isNativeOplusWindow(session)
                || session.lockKeepAlive
                || session.edgeHung
                || session.edgeMinimizeRequested);
    }

    private Session sessionForTask(Object task) {
        if (task == null) return null;

        int id = taskId(task);

        Session session =
                sessions.get(id);

        if (session != null) {
            session.taskObject = task;
            return session;
        }

        String pkg =
                taskPackage(task);

        if (!GuardConfig.windowKeepalivePackage(pkg)
                || id < 0) {
            return null;
        }

        session =
                new Session(
                        id,
                        pkg);

        session.taskObject = task;

        sessions.put(
                id,
                session);

        return session;
    }

    private void refreshSessionState(
            Session session,
            String reason
    ) {
        if (session == null
                || !session.active) {
            return;
        }

        boolean floating =
                isInFloatingList(
                        session.taskId);

        if (!floating
                && session.edgeHung
                && !session.edgeMinimizeRequested) {
            session.edgeHung = false;
        }

        log(
                "OPLUS_STATE",
                "pkg=" + session.packageName
                        + " taskId="
                        + session.taskId
                        + " reason=" + reason
                        + " flexible="
                        + isFlexibleTask(
                        session.taskObject)
                        + " oem="
                        + session.oemReportedFlexible
                        + " floating="
                        + floating
                        + " edgeHung="
                        + session.edgeHung
                        + " lock="
                        + session.lockKeepAlive);
    }

    private boolean isSessionProtected(
            Session session
    ) {
        if (session == null
                || !session.active
                || !GuardConfig
                .windowKeepalivePackage(
                        session.packageName)) {
            return false;
        }

        return session.lockKeepAlive
                || session.edgeHung
                || isNativeOplusWindow(
                session);
    }

    private boolean isNativeOplusWindow(
            Session session
    ) {
        if (session == null) {
            return false;
        }

        return session.oemReportedFlexible
                || isFlexibleTask(
                session.taskObject)
                || isInFloatingList(
                session.taskId);
    }

    private boolean isInFloatingList(
            int taskId
    ) {
        if (taskId < 0) return false;

        try {
            Class<?> cls =
                    loadSystemClass(
                            "com.android.server.wm.FloatHandleController");

            Object instance =
                    cls.getMethod(
                            "getInstance")
                            .invoke(null);

            Object result =
                    cls.getMethod(
                            "isInFloatingList",
                            int.class)
                            .invoke(
                                    instance,
                                    taskId);

            return result instanceof Boolean
                    && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isFlexibleTask(
            Object task
    ) {
        if (task == null) return false;

        int mode =
                taskWindowingMode(task);

        if (mode != 0 && mode != 1) {
            return true;
        }

        Rect bounds =
                taskBounds(task);

        Rect maxBounds =
                taskMaxBounds(task);

        return bounds != null
                && maxBounds != null
                && !bounds.isEmpty()
                && !maxBounds.isEmpty()
                && !bounds.equals(
                maxBounds);
    }

    private static int taskWindowingMode(
            Object task
    ) {
        Object value =
                invokeNoArg(
                        task,
                        "getWindowingMode");

        return value instanceof Number
                ? ((Number) value)
                .intValue()
                : 1;
    }

    private static Rect taskBounds(Object task) {
        Object value =
                invokeNoArg(
                        task,
                        "getBounds");

        return value instanceof Rect
                ? new Rect((Rect) value)
                : new Rect();
    }

    private static Rect taskMaxBounds(
            Object task
    ) {
        Object config =
                invokeNoArg(
                        task,
                        "getConfiguration");

        Object windowConfig =
                fieldValue(
                        config,
                        "windowConfiguration");

        Object value =
                invokeNoArg(
                        windowConfig,
                        "getMaxBounds");

        return value instanceof Rect
                ? new Rect((Rect) value)
                : new Rect();
    }

    private static Object activityTask(
            Object activityRecord
    ) {
        Object task =
                invokeNoArg(
                        activityRecord,
                        "getTask");

        if (task != null) return task;

        task =
                fieldValue(
                        activityRecord,
                        "task");

        return task != null
                ? task
                : fieldValue(
                activityRecord,
                "mTask");
    }

    private static String taskPackage(
            Object task
    ) {
        if (task == null) return null;

        Object top =
                invokeNoArg(
                        task,
                        "topRunningActivity");

        if (top == null) {
            top =
                    invokeNoArg(
                            task,
                            "getTopNonFinishingActivity");
        }

        String pkg =
                objectPackage(top);

        if (pkg != null) return pkg;

        Object realActivity =
                fieldValue(
                        task,
                        "realActivity");

        if (realActivity
                instanceof ComponentName) {
            return ((ComponentName) realActivity)
                    .getPackageName();
        }

        return null;
    }

    private static int taskId(Object task) {
        Object value =
                invokeNoArg(
                        task,
                        "getTaskId");

        if (value instanceof Number) {
            return ((Number) value)
                    .intValue();
        }

        value =
                fieldValue(
                        task,
                        "mTaskId");

        return value instanceof Number
                ? ((Number) value)
                .intValue()
                : -1;
    }

    private static String activityComponent(
            Object activityRecord
    ) {
        Object component =
                fieldValue(
                        activityRecord,
                        "mActivityComponent");

        return String.valueOf(
                component);
    }

    private static Context deriveSystemContext(
            Object task
    ) {
        Object service =
                fieldValue(
                        task,
                        "mAtmService");

        if (service == null) {
            service =
                    fieldValue(
                            task,
                            "mService");
        }

        Object context =
                fieldValue(
                        service,
                        "mUiContext");

        if (!(context
                instanceof Context)) {
            context =
                    fieldValue(
                            service,
                            "mContext");
        }

        return context
                instanceof Context
                ? (Context) context
                : null;
    }

    private static int taskInfoId(
            Object taskInfo
    ) {
        return intField(
                taskInfo,
                "taskId",
                -1);
    }

    private static String taskInfoPackage(
            Object taskInfo
    ) {
        return objectPackage(
                taskInfo);
    }

    private static String objectPackage(
            Object value
    ) {
        if (value == null) return null;

        if (value
                instanceof ComponentName) {
            return ((ComponentName) value)
                    .getPackageName();
        }

        if (value
                instanceof ActivityInfo) {
            return ((ActivityInfo) value)
                    .packageName;
        }

        Object packageName =
                fieldValue(
                        value,
                        "packageName");

        if (packageName
                instanceof String) {
            return (String) packageName;
        }

        Object component =
                fieldValue(
                        value,
                        "mActivityComponent");

        if (component
                instanceof ComponentName) {
            return ((ComponentName) component)
                    .getPackageName();
        }

        for (String field :
                new String[]{
                        "topActivity",
                        "baseActivity",
                        "realActivity"
                }) {
            component =
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

        if (info
                instanceof ActivityInfo) {
            return ((ActivityInfo) info)
                    .packageName;
        }

        return null;
    }

    private static Rect taskInfoBounds(
            Object taskInfo
    ) {
        Object config =
                fieldValue(
                        taskInfo,
                        "configuration");

        return configurationBounds(
                config);
    }

    private static Rect taskInfoMaxBounds(
            Object taskInfo
    ) {
        Object config =
                fieldValue(
                        taskInfo,
                        "configuration");

        return configurationMaxBounds(
                config);
    }

    private static Rect configurationBounds(
            Object config
    ) {
        Object wc =
                fieldValue(
                        config,
                        "windowConfiguration");

        Object value =
                invokeNoArg(
                        wc,
                        "getBounds");

        return value instanceof Rect
                ? new Rect((Rect) value)
                : new Rect();
    }

    private static Rect configurationMaxBounds(
            Object config
    ) {
        Object wc =
                fieldValue(
                        config,
                        "windowConfiguration");

        Object value =
                invokeNoArg(
                        wc,
                        "getMaxBounds");

        return value instanceof Rect
                ? new Rect((Rect) value)
                : new Rect();
    }

    private static Class<?> loadSystemClass(
            String name
    ) throws ClassNotFoundException {
        ClassLoader own =
                OplusFlexibleWindowController
                        .class
                        .getClassLoader();

        if (own != null) {
            try {
                return Class.forName(
                        name,
                        false,
                        own);
            } catch (ClassNotFoundException ignored) {
            }
        }

        return Class.forName(name);
    }

    private static Object invokeNoArg(
            Object receiver,
            String methodName
    ) {
        if (receiver == null) return null;

        Class<?> current =
                receiver.getClass();

        while (current != null) {
            for (Method method :
                    current.getDeclaredMethods()) {
                if (!methodName.equals(
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

    private static Bundle taskInfoOplusExtras(
            Object taskInfo
    ) {
        if (taskInfo == null) {
            return null;
        }

        Object direct =
                fieldValue(
                        taskInfo,
                        "mOplusExtraBundle");

        if (direct instanceof Bundle) {
            return (Bundle) direct;
        }

        direct =
                fieldValue(
                        taskInfo,
                        "oplusExtraBundle");

        if (direct instanceof Bundle) {
            return (Bundle) direct;
        }

        for (Class<?> current =
             taskInfo.getClass();
             current != null;
             current = current.getSuperclass()) {
            for (Field field :
                    current.getDeclaredFields()) {
                if (!Bundle.class
                        .isAssignableFrom(
                                field.getType())) {
                    continue;
                }

                try {
                    field.setAccessible(true);

                    Object value =
                            field.get(
                                    taskInfo);

                    if (!(value instanceof Bundle)) {
                        continue;
                    }

                    Bundle bundle =
                            (Bundle) value;

                    if (bundle.containsKey(
                            "key_flexible_task_state")
                            || bundle.containsKey(
                            "flexible_super_mini_state")
                            || bundle.containsKey(
                            "androidx.flexible.taskFrame")) {
                        return bundle;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return null;
    }

    private static Object fieldValue(
            Object receiver,
            String fieldName
    ) {
        if (receiver == null) return null;

        Class<?> current =
                receiver.getClass();

        while (current != null) {
            try {
                Field field =
                        current.getDeclaredField(
                                fieldName);

                field.setAccessible(true);

                return field.get(
                        receiver);
            } catch (Throwable ignored) {
                current =
                        current.getSuperclass();
            }
        }

        return null;
    }

    private static boolean booleanField(
            Object receiver,
            String name,
            boolean fallback
    ) {
        Object value =
                fieldValue(
                        receiver,
                        name);

        return value
                instanceof Boolean
                ? (Boolean) value
                : fallback;
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

        return value
                instanceof Number
                ? ((Number) value)
                .intValue()
                : fallback;
    }

    private void log(
            String event,
            String detail
    ) {
        if (logger != null) {
            logger.log(
                    event,
                    detail);
        }
    }

    private static final class Session {
        final int taskId;
        final String packageName;

        volatile Object taskObject;
        volatile Object activityRecord;

        volatile boolean active = true;
        volatile boolean oemReportedFlexible;
        volatile boolean edgeMinimizeRequested;
        volatile boolean edgeHung;
        volatile boolean lockKeepAlive;

        volatile long lastOplusStateElapsed;
        volatile long lastSeenElapsed =
                SystemClock.elapsedRealtime();

        Session(
                int taskId,
                String packageName
        ) {
            this.taskId = taskId;
            this.packageName = packageName;
        }
    }
}
