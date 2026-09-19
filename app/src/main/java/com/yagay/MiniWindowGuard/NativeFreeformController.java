package com.yagay.MiniWindowGuard;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native task/freeform engine.
 *
 * The target task stays on its original display. MiniWindowGuard only changes
 * the task windowing mode and bounds, so SurfaceView/MediaCodec, input focus,
 * and child SurfaceControl composition remain owned by the system window
 * manager. VirtualDisplay is kept only as an automatic compatibility fallback.
 */
final class NativeFreeformController {
    private static final long COMMAND_POLL_MS = 180L;

    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private final Handler handler;
    private final VirtualDisplayController.Logger logger;
    private final Map<Integer, Session> sessions =
            new ConcurrentHashMap<>();

    private volatile Context systemUiContext;
    private volatile Object activityTaskManager;
    private volatile VirtualDisplayController fallback;

    private volatile int lastCommandSeq = Integer.MIN_VALUE;
    private volatile String pendingPackage = "";
    private volatile int pendingState = ConfigKeys.STATE_WINDOW;
    private volatile boolean running;

    NativeFreeformController(
            Handler handler,
            Context systemUiContext,
            VirtualDisplayController.Logger logger
    ) {
        this.handler = handler;
        this.systemUiContext = systemUiContext;
        this.logger = logger;
        this.fallback = new VirtualDisplayController(
                handler,
                systemUiContext,
                logger);
    }

    void start() {
        if (running) return;
        running = true;
        handler.removeCallbacks(commandPoll);
        handler.post(commandPoll);
        log("NATIVE_ENGINE_READY",
                "backend=TaskFreeform"
                        + " fallback=VirtualDisplay");
    }

    void shutdown() {
        running = false;
        handler.removeCallbacks(commandPoll);

        Session[] snapshot =
                sessions.values().toArray(new Session[0]);

        for (Session session : snapshot) {
            try {
                restoreSession(
                        session,
                        "engine-reload");
            } catch (Throwable t) {
                log("NATIVE_SHUTDOWN_ERROR",
                        "pkg=" + session.packageName
                                + " taskId=" + session.taskId
                                + " error=" + t);
            }
        }

        sessions.clear();

        VirtualDisplayController currentFallback = fallback;
        if (currentFallback != null) {
            currentFallback.shutdown();
        }

        pendingPackage = "";
        lastCommandSeq = Integer.MIN_VALUE;
    }

    int activeSessionCount() {
        int count = 0;

        for (Session session : sessions.values()) {
            if (session.active) count++;
        }

        VirtualDisplayController currentFallback = fallback;
        if (currentFallback != null) {
            count += currentFallback.activeSessionCount();
        }

        return count;
    }

    boolean wantsPackage(String packageName) {
        if (packageName == null) return false;

        if (packageName.equals(pendingPackage)
                || isManagedPackage(packageName)) {
            return true;
        }

        VirtualDisplayController currentFallback = fallback;
        return currentFallback != null
                && currentFallback.wantsPackage(packageName);
    }

    boolean isManagedPackage(String packageName) {
        if (packageName == null) return false;

        for (Session session : sessions.values()) {
            if (session.active
                    && packageName.equals(session.packageName)) {
                return true;
            }
        }

        VirtualDisplayController currentFallback = fallback;
        return currentFallback != null
                && currentFallback.isManagedPackage(packageName);
    }

    String managedPackageForProcess(String processName) {
        if (processName == null || processName.isBlank()) {
            return null;
        }

        for (Session session : sessions.values()) {
            if (!session.active) continue;

            String pkg = session.packageName;
            if (processName.equals(pkg)
                    || processName.startsWith(pkg + ":")) {
                return pkg;
            }
        }

        VirtualDisplayController currentFallback = fallback;
        return currentFallback == null
                ? null
                : currentFallback.managedPackageForProcess(
                        processName);
    }

    boolean isManagedTopActivityRecord(Object activityRecord) {
        String pkg = activityPackage(activityRecord);

        if (pkg == null) return false;

        Session session = latestSession(pkg);
        if (session == null) {
            VirtualDisplayController currentFallback =
                    fallback;
            return currentFallback != null
                    && currentFallback
                    .isManagedTopActivityRecord(
                            activityRecord);
        }

        Object task = activityTask(activityRecord);
        if (task == null) return false;

        Object top = invokeNoArg(
                task,
                "topRunningActivity");

        if (top == null) {
            top = invokeNoArg(
                    task,
                    "getTopResumedActivity");
        }

        return top == null || top == activityRecord;
    }

    int stateForPackage(String packageName) {
        Session session = latestSession(packageName);

        if (session != null && session.active) {
            return session.state;
        }

        VirtualDisplayController currentFallback = fallback;
        return currentFallback == null
                ? ConfigKeys.STATE_RELEASED
                : currentFallback.stateForPackage(packageName);
    }

    void capture(
            Object activityRecord,
            String packageName
    ) {
        if (activityRecord == null
                || packageName == null
                || !GuardConfig.bool(
                        ConfigKeys.AUTO_CONTAINER)
                || !wantsPackage(packageName)) {
            return;
        }

        Object task = activityTask(activityRecord);
        if (task == null) {
            fallback(
                    activityRecord,
                    packageName,
                    pendingState,
                    "no-task");
            return;
        }

        if (systemUiContext == null) {
            systemUiContext =
                    deriveSystemContext(task);
        }

        int id = taskId(task);
        if (id < 0) {
            fallback(
                    activityRecord,
                    packageName,
                    pendingState,
                    "no-task-id");
            return;
        }

        Session existing = sessions.get(id);
        if (existing != null && existing.active) {
            existing.lastSeenElapsed =
                    SystemClock.elapsedRealtime();
            existing.activityRecord =
                    activityRecord;
            return;
        }

        VirtualDisplayController currentFallback =
                fallback;
        if (currentFallback != null
                && currentFallback.isManagedPackage(
                        packageName)) {
            currentFallback.captureFallback(
                    activityRecord,
                    packageName,
                    pendingState);
            return;
        }

        int initialState =
                packageName.equals(pendingPackage)
                        ? pendingState
                        : ConfigKeys.STATE_WINDOW;

        int originalMode =
                intValue(
                        invokeNoArg(
                                task,
                                "getWindowingMode"),
                        WINDOWING_MODE_FULLSCREEN);

        Rect originalBounds =
                copyRect(
                        invokeNoArg(
                                task,
                                "getBounds"));

        Session session =
                new Session(
                        id,
                        packageName,
                        task,
                        activityRecord,
                        taskDisplayId(task),
                        originalMode,
                        originalBounds,
                        initialState);

        sessions.put(id, session);

        if (packageName.equals(pendingPackage)) {
            pendingPackage = "";
        }

        log("NATIVE_TASK_CAPTURED",
                "pkg=" + packageName
                        + " taskId=" + id
                        + " displayId="
                        + session.originalDisplayId
                        + " originalMode="
                        + originalMode
                        + " originalBounds="
                        + originalBounds
                        + " state="
                        + initialState);

        handler.post(() ->
                openSession(session));
    }

    void releasePackage(String packageName) {
        Session session =
                latestSession(packageName);

        if (session != null) {
            handler.post(() ->
                    restoreSession(
                            session,
                            "release-package"));
            return;
        }

        VirtualDisplayController currentFallback = fallback;
        if (currentFallback != null) {
            currentFallback.releasePackage(
                    packageName);
        }
    }

    private final Runnable commandPoll =
            new Runnable() {
                @Override
                public void run() {
                    try {
                        int seq = GuardConfig.integer(
                                ConfigKeys.CONTAINER_COMMAND_SEQ);

                        if (seq != lastCommandSeq) {
                            lastCommandSeq = seq;

                            String pkg =
                                    GuardConfig.string(
                                            ConfigKeys.CONTAINER_COMMAND_PACKAGE);

                            int state =
                                    ConfigKeys.sanitizeState(
                                            GuardConfig.integer(
                                                    ConfigKeys.CONTAINER_COMMAND_STATE));

                            if (!pkg.isEmpty()) {
                                Session session =
                                        latestSession(pkg);

                                VirtualDisplayController
                                        currentFallback =
                                        fallback;

                                if (session != null) {
                                    if (state
                                            == ConfigKeys.STATE_RELEASED) {
                                        restoreSession(
                                                session,
                                                "command-release");
                                    } else {
                                        applyState(
                                                session,
                                                state,
                                                "command");
                                    }
                                } else if (currentFallback
                                        != null
                                        && currentFallback
                                        .isManagedPackage(pkg)) {
                                    currentFallback.setPackageState(
                                            pkg,
                                            state,
                                            "native-command-fallback");
                                } else if (state
                                        != ConfigKeys.STATE_RELEASED) {
                                    pendingPackage = pkg;
                                    pendingState = state;

                                    log("NATIVE_COMMAND_PENDING",
                                            "seq=" + seq
                                                    + " pkg=" + pkg
                                                    + " state=" + state);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        log("NATIVE_COMMAND_ERROR",
                                String.valueOf(t));
                    } finally {
                        if (running) {
                            handler.postDelayed(
                                    this,
                                    COMMAND_POLL_MS);
                        }
                    }
                }
            };

    private void openSession(Session session) {
        if (session == null || !session.active) {
            return;
        }

        Rect bounds = defaultBounds();

        if (!applyFreeform(
                session,
                bounds,
                "initial")) {
            sessions.remove(
                    session.taskId,
                    session);
            session.active = false;

            fallback(
                    session.activityRecord,
                    session.packageName,
                    session.state,
                    "native-freeform-apply-failed");
            return;
        }

        session.freeformBounds =
                new Rect(bounds);

        if (session.state
                == ConfigKeys.STATE_ICON
                || session.state
                == ConfigKeys.STATE_HIDDEN) {
            moveToBack(
                    session,
                    "initial-state");
        }

        log("NATIVE_WINDOW_READY",
                "pkg=" + session.packageName
                        + " taskId=" + session.taskId
                        + " displayId="
                        + taskDisplayId(
                                session.taskObject)
                        + " mode="
                        + taskWindowingMode(
                                session.taskObject)
                        + " bounds="
                        + taskBounds(
                                session.taskObject));
    }

    private boolean applyFreeform(
            Session session,
            Rect bounds,
            String reason
    ) {
        if (session == null
                || !session.active
                || bounds == null
                || bounds.isEmpty()) {
            return false;
        }

        Object task = session.taskObject;
        Object atm = activityTaskManager();

        boolean modeRequested = false;
        boolean boundsRequested = false;

        modeRequested |= invokeVoidLike(
                task,
                "setWindowingMode",
                WINDOWING_MODE_FREEFORM);

        if (atm != null) {
            modeRequested |= invokeVoidLike(
                    atm,
                    "setTaskWindowingMode",
                    session.taskId,
                    WINDOWING_MODE_FREEFORM,
                    true);
        }

        boundsRequested |= invokeVoidLike(
                task,
                "setBounds",
                new Rect(bounds));

        if (atm != null) {
            boundsRequested |= invokeVoidLike(
                    atm,
                    "resizeTask",
                    session.taskId,
                    new Rect(bounds),
                    0);
        }

        invokeVoidLike(
                task,
                "setAlwaysOnTop",
                true);

        focusTask(
                session,
                "apply-" + reason);

        int mode =
                taskWindowingMode(task);
        Rect actual =
                taskBounds(task);

        boolean multiWindow =
                booleanValue(
                        invokeNoArg(
                                task,
                                "inMultiWindowMode"),
                        false);

        boolean boundsChanged =
                actual != null
                        && !actual.isEmpty()
                        && closeEnough(
                        actual,
                        bounds,
                        dp(10));

        boolean success =
                (mode == WINDOWING_MODE_FREEFORM
                        || multiWindow)
                        && boundsChanged;

        log(success
                        ? "NATIVE_FREEFORM_APPLIED"
                        : "NATIVE_FREEFORM_FAILED",
                "pkg=" + session.packageName
                        + " taskId=" + session.taskId
                        + " reason=" + reason
                        + " modeRequested="
                        + modeRequested
                        + " boundsRequested="
                        + boundsRequested
                        + " actualMode=" + mode
                        + " multiWindow="
                        + multiWindow
                        + " requestedBounds="
                        + bounds
                        + " actualBounds="
                        + actual);

        return success;
    }

    private void applyState(
            Session session,
            int state,
            String reason
    ) {
        if (session == null || !session.active) {
            return;
        }

        int safe =
                ConfigKeys.sanitizeState(state);

        session.state = safe;
        session.lastSeenElapsed =
                SystemClock.elapsedRealtime();

        if (safe == ConfigKeys.STATE_RELEASED) {
            restoreSession(
                    session,
                    reason);
            return;
        }

        if (safe == ConfigKeys.STATE_WINDOW) {
            Rect bounds =
                    session.freeformBounds;

            if (bounds == null
                    || bounds.isEmpty()) {
                bounds = defaultBounds();
            }

            applyFreeform(
                    session,
                    new Rect(bounds),
                    "restore-" + reason);

            focusTask(
                    session,
                    "restore-" + reason);
        } else {
            moveToBack(
                    session,
                    reason);
        }

        log("NATIVE_STATE",
                "pkg=" + session.packageName
                        + " taskId=" + session.taskId
                        + " state=" + safe
                        + " reason=" + reason);
    }

    private void restoreSession(
            Session session,
            String reason
    ) {
        if (session == null || !session.active) {
            return;
        }

        session.active = false;
        session.state =
                ConfigKeys.STATE_RELEASED;

        Object task =
                session.taskObject;

        invokeVoidLike(
                task,
                "setAlwaysOnTop",
                false);

        int restoreMode =
                session.originalWindowingMode > 0
                        ? session.originalWindowingMode
                        : WINDOWING_MODE_FULLSCREEN;

        invokeVoidLike(
                task,
                "setWindowingMode",
                restoreMode);

        Object atm = activityTaskManager();

        if (atm != null) {
            invokeVoidLike(
                    atm,
                    "setTaskWindowingMode",
                    session.taskId,
                    restoreMode,
                    true);
        }

        Rect original =
                session.originalBounds;

        if (original != null
                && !original.isEmpty()) {
            invokeVoidLike(
                    task,
                    "setBounds",
                    new Rect(original));

            if (atm != null) {
                invokeVoidLike(
                        atm,
                        "resizeTask",
                        session.taskId,
                        new Rect(original),
                        0);
            }
        }

        sessions.remove(
                session.taskId,
                session);

        log("NATIVE_TASK_RELEASED",
                "pkg=" + session.packageName
                        + " taskId=" + session.taskId
                        + " reason=" + reason
                        + " restoreMode="
                        + restoreMode
                        + " restoreBounds="
                        + original);
    }

    private void moveToBack(
            Session session,
            String reason
    ) {
        if (session == null
                || !session.active) {
            return;
        }

        Object task =
                session.taskObject;

        invokeVoidLike(
                task,
                "setAlwaysOnTop",
                false);

        boolean moved =
                invokeVoidLike(
                        task,
                        "moveToBack",
                        "MiniWindowGuard:" + reason,
                        null);

        if (!moved) {
            Object displayArea =
                    invokeNoArg(
                            task,
                            "getDisplayArea");

            moved = invokeVoidLike(
                    displayArea,
                    "positionTaskBehindHome",
                    task);
        }

        log("NATIVE_MOVE_BACK",
                "pkg=" + session.packageName
                        + " taskId="
                        + session.taskId
                        + " reason=" + reason
                        + " moved=" + moved);
    }

    private void focusTask(
            Session session,
            String reason
    ) {
        if (session == null
                || !session.active) {
            return;
        }

        Object atm =
                activityTaskManager();

        boolean focusedTask = false;
        boolean focusedRoot = false;
        boolean movedFront = false;

        if (atm != null) {
            focusedTask =
                    invokeVoidLike(
                            atm,
                            "setFocusedTask",
                            session.taskId);

            focusedRoot =
                    invokeVoidLike(
                            atm,
                            "setFocusedRootTask",
                            session.taskId);
        }

        Context context =
                systemUiContext;

        if (context != null) {
            try {
                ActivityManager am =
                        context.getSystemService(
                                ActivityManager.class);

                if (am != null) {
                    am.moveTaskToFront(
                            session.taskId,
                            0);
                    movedFront = true;
                }
            } catch (Throwable ignored) {
            }
        }

        invokeVoidLike(
                session.taskObject,
                "setAlwaysOnTop",
                true);

        log("NATIVE_FOCUS",
                "pkg=" + session.packageName
                        + " taskId=" + session.taskId
                        + " reason=" + reason
                        + " focusedTask="
                        + focusedTask
                        + " focusedRoot="
                        + focusedRoot
                        + " movedFront="
                        + movedFront);
    }

    private void fallback(
            Object activityRecord,
            String packageName,
            int state,
            String reason
    ) {
        VirtualDisplayController currentFallback =
                fallback;

        if (currentFallback == null) {
            log("NATIVE_FALLBACK_UNAVAILABLE",
                    "pkg=" + packageName
                            + " reason=" + reason);
            return;
        }

        log("NATIVE_FALLBACK_TO_VD",
                "pkg=" + packageName
                        + " state=" + state
                        + " reason=" + reason);

        currentFallback.captureFallback(
                activityRecord,
                packageName,
                state);
    }

    private Rect defaultBounds() {
        Context context =
                systemUiContext;

        if (context == null) {
            return new Rect(
                    100,
                    200,
                    900,
                    1600);
        }

        DisplayMetrics metrics =
                context.getResources()
                        .getDisplayMetrics();

        int minWidth =
                dp(GuardConfig.outerMinWidthDp());

        int minHeight =
                dp(GuardConfig.outerMinHeightDp());

        int width =
                clamp(
                        metrics.widthPixels
                                * GuardConfig.containerWidth()
                                / 100,
                        minWidth,
                        Math.max(
                                minWidth,
                                metrics.widthPixels
                                        - dp(24)));

        int height =
                clamp(
                        metrics.heightPixels
                                * GuardConfig.containerHeight()
                                / 100,
                        minHeight,
                        Math.max(
                                minHeight,
                                metrics.heightPixels
                                        - dp(140)));

        int left =
                Math.max(
                        dp(12),
                        (metrics.widthPixels - width)
                                / 2);

        int top =
                Math.max(
                        dp(84),
                        (metrics.heightPixels - height)
                                / 3);

        int right =
                Math.min(
                        metrics.widthPixels - dp(12),
                        left + width);

        int bottom =
                Math.min(
                        metrics.heightPixels - dp(60),
                        top + height);

        return new Rect(
                left,
                top,
                right,
                bottom);
    }

    private Object activityTaskManager() {
        Object cached =
                activityTaskManager;

        if (cached != null) return cached;

        synchronized (this) {
            if (activityTaskManager == null) {
                activityTaskManager =
                        resolveBinderInterface(
                                "activity_task",
                                "android.app.IActivityTaskManager$Stub");
            }

            return activityTaskManager;
        }
    }

    private Object resolveBinderInterface(
            String serviceName,
            String stubClassName
    ) {
        try {
            ClassLoader loader =
                    NativeFreeformController.class
                            .getClassLoader();

            Class<?> serviceManagerClass =
                    Class.forName(
                            "android.os.ServiceManager",
                            false,
                            loader);

            Method getService =
                    serviceManagerClass.getDeclaredMethod(
                            "getService",
                            String.class);

            getService.setAccessible(true);

            Object binder =
                    getService.invoke(
                            null,
                            serviceName);

            if (!(binder instanceof IBinder)) {
                return null;
            }

            Class<?> stub =
                    Class.forName(
                            stubClassName,
                            false,
                            loader);

            Method asInterface =
                    stub.getDeclaredMethod(
                            "asInterface",
                            IBinder.class);

            asInterface.setAccessible(true);

            return asInterface.invoke(
                    null,
                    binder);
        } catch (Throwable t) {
            log("NATIVE_BINDER_ERROR",
                    "service=" + serviceName
                            + " stub="
                            + stubClassName
                            + " error=" + t);
            return null;
        }
    }

    private static Object activityTask(
            Object activityRecord
    ) {
        Object task =
                invokeNoArg(
                        activityRecord,
                        "getTask");

        if (task != null) return task;

        task = fieldValue(
                activityRecord,
                "task");

        if (task != null) return task;

        return fieldValue(
                activityRecord,
                "mTask");
    }

    private static int taskId(Object task) {
        if (task == null) return -1;

        Object value =
                invokeNoArg(
                        task,
                        "getTaskId");

        if (value instanceof Integer) {
            return (Integer) value;
        }

        value = fieldValue(
                task,
                "mTaskId");

        return value instanceof Integer
                ? (Integer) value
                : -1;
    }

    private static int taskDisplayId(Object task) {
        Object value =
                invokeNoArg(
                        task,
                        "getDisplayId");

        if (value instanceof Integer) {
            return (Integer) value;
        }

        Object displayArea =
                invokeNoArg(
                        task,
                        "getDisplayArea");

        Object displayId =
                invokeNoArg(
                        displayArea,
                        "getDisplayId");

        return displayId instanceof Integer
                ? (Integer) displayId
                : 0;
    }

    private static int taskWindowingMode(
            Object task
    ) {
        return intValue(
                invokeNoArg(
                        task,
                        "getWindowingMode"),
                -1);
    }

    private static Rect taskBounds(Object task) {
        return copyRect(
                invokeNoArg(
                        task,
                        "getBounds"));
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

        if (!(context instanceof Context)) {
            context =
                    fieldValue(
                            service,
                            "mContext");
        }

        return context instanceof Context
                ? (Context) context
                : null;
    }

    private static String activityPackage(
            Object activityRecord
    ) {
        Object value =
                fieldValue(
                        activityRecord,
                        "packageName");

        if (value instanceof String) {
            return (String) value;
        }

        Object component =
                fieldValue(
                        activityRecord,
                        "mActivityComponent");

        if (component instanceof ComponentName) {
            return ((ComponentName) component)
                    .getPackageName();
        }

        return null;
    }

    private Session latestSession(
            String packageName
    ) {
        if (packageName == null) return null;

        Session latest = null;

        for (Session session : sessions.values()) {
            if (!session.active
                    || !packageName.equals(
                            session.packageName)) {
                continue;
            }

            if (latest == null
                    || session.lastSeenElapsed
                    > latest.lastSeenElapsed) {
                latest = session;
            }
        }

        return latest;
    }

    private int dp(float value) {
        Context context =
                systemUiContext;

        if (context == null) {
            return Math.round(value * 3f);
        }

        return Math.round(
                value
                        * context.getResources()
                        .getDisplayMetrics()
                        .density);
    }

    private static boolean closeEnough(
            Rect a,
            Rect b,
            int tolerance
    ) {
        if (a == null || b == null) {
            return false;
        }

        return Math.abs(a.left - b.left) <= tolerance
                && Math.abs(a.top - b.top) <= tolerance
                && Math.abs(a.right - b.right) <= tolerance
                && Math.abs(a.bottom - b.bottom) <= tolerance;
    }

    private static Rect copyRect(Object value) {
        if (value instanceof Rect) {
            return new Rect((Rect) value);
        }

        return new Rect();
    }

    private static int intValue(
            Object value,
            int fallback
    ) {
        return value instanceof Number
                ? ((Number) value).intValue()
                : fallback;
    }

    private static boolean booleanValue(
            Object value,
            boolean fallback
    ) {
        return value instanceof Boolean
                ? (Boolean) value
                : fallback;
    }

    private static Object invokeNoArg(
            Object receiver,
            String methodName
    ) {
        if (receiver == null) return null;

        Method method =
                findCompatibleMethod(
                        receiver.getClass(),
                        methodName,
                        new Object[0]);

        if (method == null) return null;

        try {
            method.setAccessible(true);
            return method.invoke(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeVoidLike(
            Object receiver,
            String methodName,
            Object... args
    ) {
        if (receiver == null) return false;

        Method method =
                findCompatibleMethod(
                        receiver.getClass(),
                        methodName,
                        args);

        if (method == null) return false;

        try {
            method.setAccessible(true);
            method.invoke(
                    receiver,
                    args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method findCompatibleMethod(
            Class<?> type,
            String name,
            Object[] args
    ) {
        if (type == null) return null;

        for (Method method : type.getMethods()) {
            if (methodMatches(
                    method,
                    name,
                    args)) {
                return method;
            }
        }

        Class<?> current = type;

        while (current != null) {
            for (Method method :
                    current.getDeclaredMethods()) {
                if (methodMatches(
                        method,
                        name,
                        args)) {
                    return method;
                }
            }

            current =
                    current.getSuperclass();
        }

        return null;
    }

    private static boolean methodMatches(
            Method method,
            String name,
            Object[] args
    ) {
        if (!name.equals(method.getName())) {
            return false;
        }

        Class<?>[] parameters =
                method.getParameterTypes();

        if (parameters.length != args.length) {
            return false;
        }

        for (int i = 0;
             i < parameters.length;
             i++) {
            if (!compatible(
                    parameters[i],
                    args[i])) {
                return false;
            }
        }

        return true;
    }

    private static boolean compatible(
            Class<?> expected,
            Object value
    ) {
        if (value == null) {
            return !expected.isPrimitive();
        }

        Class<?> actual =
                value.getClass();

        if (expected.isAssignableFrom(actual)) {
            return true;
        }

        if (!expected.isPrimitive()) {
            return false;
        }

        return (expected == int.class
                && actual == Integer.class)
                || (expected == boolean.class
                && actual == Boolean.class)
                || (expected == long.class
                && actual == Long.class)
                || (expected == float.class
                && actual == Float.class)
                || (expected == double.class
                && actual == Double.class);
    }

    private static Object fieldValue(
            Object receiver,
            String fieldName
    ) {
        if (receiver == null) return null;

        Field field =
                findField(
                        receiver.getClass(),
                        fieldName);

        if (field == null) return null;

        try {
            field.setAccessible(true);
            return field.get(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(
            Class<?> type,
            String name
    ) {
        Class<?> current =
                type;

        while (current != null) {
            try {
                return current
                        .getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current =
                        current.getSuperclass();
            }
        }

        return null;
    }

    private static int clamp(
            int value,
            int min,
            int max
    ) {
        if (max < min) return min;
        return Math.max(
                min,
                Math.min(
                        max,
                        value));
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
        final Object taskObject;
        final int originalDisplayId;
        final int originalWindowingMode;
        final Rect originalBounds;

        volatile Object activityRecord;
        volatile Rect freeformBounds;
        volatile boolean active = true;
        volatile int state;
        volatile long lastSeenElapsed =
                SystemClock.elapsedRealtime();

        Session(
                int taskId,
                String packageName,
                Object taskObject,
                Object activityRecord,
                int originalDisplayId,
                int originalWindowingMode,
                Rect originalBounds,
                int state
        ) {
            this.taskId = taskId;
            this.packageName = packageName;
            this.taskObject = taskObject;
            this.activityRecord = activityRecord;
            this.originalDisplayId = originalDisplayId;
            this.originalWindowingMode = originalWindowingMode;
            this.originalBounds =
                    originalBounds == null
                            ? new Rect()
                            : new Rect(originalBounds);
            this.state = state;
        }
    }
}
