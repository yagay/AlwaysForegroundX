package com.yagay.MiniWindowGuard;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OxygenOS / ColorOS native flexible-window backend.
 *
 * No app content is mirrored and no VirtualDisplay is created. The existing
 * task stays on display 0 and is handed to OPlusActivityTaskManager's native
 * flexible-window implementation. MiniWindowGuard only keeps the task/process
 * logically foreground through the existing system_server hooks.
 */
final class OplusFlexibleWindowController {
    private static final long COMMAND_POLL_MS = 180L;
    private static final long TOGGLE_DEBOUNCE_MS = 1200L;

    private final Handler handler;
    private final VirtualDisplayController.Logger logger;
    private final Map<Integer, Session> sessions =
            new ConcurrentHashMap<>();

    private volatile Context systemContext;
    private volatile Object oplusAtm;
    private volatile Method toggleFlexibleWindow;

    private volatile int lastCommandSeq = Integer.MIN_VALUE;
    private volatile String pendingPackage = "";
    private volatile int pendingState = ConfigKeys.STATE_WINDOW;
    private volatile boolean running;

    OplusFlexibleWindowController(
            Handler handler,
            Context systemContext,
            VirtualDisplayController.Logger logger
    ) {
        this.handler = handler;
        this.systemContext = systemContext;
        this.logger = logger;
    }

    void start() {
        if (running) return;
        running = true;
        resolveOplusApi();
        handler.removeCallbacks(commandPoll);
        handler.post(commandPoll);

        log("OPLUS_ENGINE_READY",
                "api=" + (toggleFlexibleWindow != null)
                        + " manager="
                        + (oplusAtm == null
                        ? "null"
                        : oplusAtm.getClass().getName()));
    }

    void shutdown() {
        running = false;
        handler.removeCallbacks(commandPoll);

        Session[] snapshot =
                sessions.values().toArray(new Session[0]);

        for (Session session : snapshot) {
            try {
                releaseSession(
                        session,
                        true,
                        "engine-reload");
            } catch (Throwable t) {
                log("OPLUS_SHUTDOWN_ERROR",
                        "pkg=" + session.packageName
                                + " taskId=" + session.taskId
                                + " error=" + t);
            }
        }

        sessions.clear();
        pendingPackage = "";
        lastCommandSeq = Integer.MIN_VALUE;
    }

    int activeSessionCount() {
        int count = 0;
        for (Session session : sessions.values()) {
            if (session.active) count++;
        }
        return count;
    }

    boolean wantsPackage(String packageName) {
        return packageName != null
                && (packageName.equals(pendingPackage)
                || isManagedPackage(packageName)
                || immediateCommandState(packageName) != null);
    }

    boolean isManagedPackage(String packageName) {
        if (packageName == null) return false;

        for (Session session : sessions.values()) {
            if (session.active
                    && packageName.equals(session.packageName)) {
                return true;
            }
        }

        return false;
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

        return null;
    }

    boolean isManagedTopActivityRecord(Object activityRecord) {
        if (activityRecord == null) return false;

        String pkg = activityPackage(activityRecord);
        if (!isManagedPackage(pkg)) return false;

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
        return session == null || !session.active
                ? ConfigKeys.STATE_RELEASED
                : session.state;
    }

    void capture(
            Object activityRecord,
            String packageName
    ) {
        if (activityRecord == null
                || packageName == null
                || !GuardConfig.bool(ConfigKeys.AUTO_CONTAINER)
                || !wantsPackage(packageName)) {
            return;
        }

        Object task = activityTask(activityRecord);
        if (task == null) {
            log("OPLUS_CAPTURE_SKIP",
                    "pkg=" + packageName
                            + " reason=no-task");
            return;
        }

        if (systemContext == null) {
            systemContext = deriveSystemContext(task);
        }

        int taskId = taskId(task);
        if (taskId < 0) {
            log("OPLUS_CAPTURE_SKIP",
                    "pkg=" + packageName
                            + " reason=no-task-id");
            return;
        }

        Session existing = sessions.get(taskId);
        if (existing != null && existing.active) {
            existing.activityRecord = activityRecord;
            existing.taskObject = task;
            existing.lastSeenElapsed =
                    SystemClock.elapsedRealtime();
            return;
        }

        Integer immediateState =
                immediateCommandState(packageName);

        int initialState =
                immediateState != null
                        ? immediateState
                        : packageName.equals(pendingPackage)
                        ? pendingState
                        : ConfigKeys.STATE_WINDOW;

        Session session =
                new Session(
                        taskId,
                        packageName,
                        task,
                        activityRecord,
                        initialState);

        sessions.put(taskId, session);

        if (packageName.equals(pendingPackage)) {
            pendingPackage = "";
        }

        if (immediateState != null) {
            log("OPLUS_IMMEDIATE_COMMAND",
                    "pkg=" + packageName
                            + " seq="
                            + GuardConfig.integer(
                            ConfigKeys.CONTAINER_COMMAND_SEQ)
                            + " state=" + initialState);
        }

        log("OPLUS_TASK_CAPTURED",
                "pkg=" + packageName
                        + " taskId=" + taskId
                        + " displayId="
                        + taskDisplayId(task)
                        + " bounds="
                        + taskBounds(task)
                        + " flexible="
                        + isFlexibleTask(task)
                        + " support="
                        + queryOplusSupport(task)
                        + " state="
                        + initialState);

        handler.post(() ->
                applyState(
                        session,
                        initialState,
                        "capture"));
    }

    void releasePackage(String packageName) {
        Session session = latestSession(packageName);
        if (session != null) {
            handler.post(() ->
                    releaseSession(
                            session,
                            true,
                            "release-package"));
        }
    }

    private Integer immediateCommandState(
            String packageName
    ) {
        if (packageName == null) return null;

        int seq = GuardConfig.integer(
                ConfigKeys.CONTAINER_COMMAND_SEQ);

        if (seq == lastCommandSeq) {
            return null;
        }

        String commandPackage =
                GuardConfig.string(
                        ConfigKeys.CONTAINER_COMMAND_PACKAGE);

        if (!packageName.equals(commandPackage)) {
            return null;
        }

        int state = ConfigKeys.sanitizeState(
                GuardConfig.integer(
                        ConfigKeys.CONTAINER_COMMAND_STATE));

        return state == ConfigKeys.STATE_RELEASED
                ? null
                : state;
    }

    private final Runnable commandPoll = new Runnable() {
        @Override
        public void run() {
            try {
                int seq = GuardConfig.integer(
                        ConfigKeys.CONTAINER_COMMAND_SEQ);

                if (seq != lastCommandSeq) {
                    lastCommandSeq = seq;

                    String pkg = GuardConfig.string(
                            ConfigKeys.CONTAINER_COMMAND_PACKAGE);

                    int state = ConfigKeys.sanitizeState(
                            GuardConfig.integer(
                                    ConfigKeys.CONTAINER_COMMAND_STATE));

                    if (!pkg.isEmpty()) {
                        Session session =
                                latestSession(pkg);

                        if (state == ConfigKeys.STATE_RELEASED) {
                            if (session != null) {
                                releaseSession(
                                        session,
                                        true,
                                        "remote-command");
                            }
                        } else if (session != null) {
                            applyState(
                                    session,
                                    state,
                                    "remote-command");
                        } else {
                            pendingPackage = pkg;
                            pendingState = state;

                            log("OPLUS_COMMAND_PENDING",
                                    "seq=" + seq
                                            + " pkg=" + pkg
                                            + " state=" + state);
                        }
                    }
                }
            } catch (Throwable t) {
                log("OPLUS_COMMAND_ERROR",
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

    private void applyState(
            Session session,
            int state,
            String reason
    ) {
        if (session == null || !session.active) {
            return;
        }

        int safeState =
                ConfigKeys.sanitizeState(state);

        session.state = safeState;
        session.lastSeenElapsed =
                SystemClock.elapsedRealtime();

        if (safeState == ConfigKeys.STATE_RELEASED) {
            releaseSession(
                    session,
                    true,
                    reason);
            return;
        }

        // System flexible window owns minimize/hide/restore UI itself.
        // Any non-released MiniWindowGuard state therefore means "keep this
        // OPlus task protected and ensure it is in native flexible mode".
        ensureFlexible(
                session,
                reason);

        log("OPLUS_STATE",
                "pkg=" + session.packageName
                        + " taskId=" + session.taskId
                        + " state=" + safeState
                        + " reason=" + reason);
    }

    private void ensureFlexible(
            Session session,
            String reason
    ) {
        if (session == null || !session.active) return;

        Object task = session.taskObject;

        if (isFlexibleTask(task)) {
            session.enterConfirmed = true;

            log("OPLUS_FLEX_ALREADY",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " reason=" + reason
                            + " bounds=" + taskBounds(task));
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (now - session.lastToggleElapsed
                < TOGGLE_DEBOUNCE_MS) {
            log("OPLUS_FLEX_PENDING",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " reason=" + reason
                            + " attempts="
                            + session.toggleAttempts);
            return;
        }

        if (!toggleFlexible(
                session,
                true,
                reason)) {
            return;
        }

        scheduleVerification(
                session,
                reason);
    }

    private boolean toggleFlexible(
            Session session,
            boolean enter,
            String reason
    ) {
        if (session == null) return false;

        resolveOplusApi();

        Object manager = oplusAtm;
        Method toggle = toggleFlexibleWindow;

        if (manager == null || toggle == null) {
            log("OPLUS_FLEX_FAILED",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " enter=" + enter
                            + " reason=" + reason
                            + " error=api-unavailable");
            return false;
        }

        try {
            session.lastToggleElapsed =
                    SystemClock.elapsedRealtime();

            if (enter) {
                session.toggleAttempts++;
            }

            Object result =
                    toggle.invoke(
                            manager,
                            null,
                            session.taskId,
                            true,
                            enter);

            log("OPLUS_FLEX_TOGGLE",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " enter=" + enter
                            + " reason=" + reason
                            + " attempt="
                            + session.toggleAttempts
                            + " result=" + result
                            + " boundsBefore="
                            + taskBounds(session.taskObject));

            return true;
        } catch (Throwable t) {
            log("OPLUS_FLEX_FAILED",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " enter=" + enter
                            + " reason=" + reason
                            + " error=" + t);
            return false;
        }
    }

    private void scheduleVerification(
            Session session,
            String reason
    ) {
        long[] delays = {250L, 650L, 1300L};

        for (long delay : delays) {
            handler.postDelayed(() -> {
                if (session == null || !session.active) {
                    return;
                }

                boolean flexible =
                        isFlexibleTask(
                                session.taskObject);

                if (flexible) {
                    session.enterConfirmed = true;
                }

                log("OPLUS_FLEX_VERIFY",
                        "pkg=" + session.packageName
                                + " taskId="
                                + session.taskId
                                + " delayMs=" + delay
                                + " flexible="
                                + flexible
                                + " displayId="
                                + taskDisplayId(
                                session.taskObject)
                                + " mode="
                                + taskWindowingMode(
                                session.taskObject)
                                + " bounds="
                                + taskBounds(
                                session.taskObject));

                if (!flexible
                        && delay >= 1300L
                        && session.toggleAttempts < 2) {
                    ensureFlexible(
                            session,
                            "verify-retry-" + reason);
                }
            }, delay);
        }
    }

    private void releaseSession(
            Session session,
            boolean leaveFlexible,
            String reason
    ) {
        if (session == null || !session.active) {
            return;
        }

        session.active = false;
        session.state = ConfigKeys.STATE_RELEASED;

        if (leaveFlexible
                && isFlexibleTask(
                session.taskObject)) {
            toggleFlexible(
                    session,
                    false,
                    "release-" + reason);
        }

        sessions.remove(
                session.taskId,
                session);

        log("OPLUS_TASK_RELEASED",
                "pkg=" + session.packageName
                        + " taskId=" + session.taskId
                        + " reason=" + reason
                        + " flexible="
                        + isFlexibleTask(
                        session.taskObject));
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

    private synchronized void resolveOplusApi() {
        if (oplusAtm != null
                && toggleFlexibleWindow != null) {
            return;
        }

        try {
            Class<?> cls =
                    loadSystemClass(
                            "android.app.OplusActivityTaskManager");

            Method getInstance =
                    cls.getMethod("getInstance");

            Object manager =
                    getInstance.invoke(null);

            Method toggle = null;

            try {
                toggle = cls.getMethod(
                        "toggleFlexibleWindow",
                        IBinder.class,
                        int.class,
                        boolean.class,
                        boolean.class);
            } catch (Throwable ignored) {
                for (Method method :
                        cls.getMethods()) {
                    if (!"toggleFlexibleWindow"
                            .equals(method.getName())
                            || method.getParameterCount() != 4) {
                        continue;
                    }

                    Class<?>[] p =
                            method.getParameterTypes();

                    if (IBinder.class
                            .isAssignableFrom(p[0])
                            && p[1] == int.class
                            && p[2] == boolean.class
                            && p[3] == boolean.class) {
                        toggle = method;
                        break;
                    }
                }
            }

            if (manager == null || toggle == null) {
                throw new IllegalStateException(
                        "toggleFlexibleWindow unavailable");
            }

            toggle.setAccessible(true);

            oplusAtm = manager;
            toggleFlexibleWindow = toggle;

            log("OPLUS_API_READY",
                    "class=" + cls.getName()
                            + " method="
                            + toggle.toGenericString());
        } catch (Throwable t) {
            oplusAtm = null;
            toggleFlexibleWindow = null;

            log("OPLUS_API_ERROR",
                    String.valueOf(t));
        }
    }

    private boolean queryOplusSupport(Object task) {
        if (task == null) return false;

        try {
            Class<?> utils =
                    loadSystemClass(
                            "com.android.server.wm.FlexibleWindowUtils");

            for (Method method :
                    utils.getDeclaredMethods()) {
                if (!"isSupportFlexibleWindow"
                        .equals(method.getName())
                        || !Modifier.isStatic(
                        method.getModifiers())
                        || method.getParameterCount() != 1
                        || method.getReturnType()
                        != boolean.class) {
                    continue;
                }

                Class<?> parameter =
                        method.getParameterTypes()[0];

                if (!parameter
                        .isAssignableFrom(
                        task.getClass())
                        && !parameter
                        .getName()
                        .equals(
                        task.getClass().getName())) {
                    continue;
                }

                method.setAccessible(true);

                Object value =
                        method.invoke(
                                null,
                                task);

                return value instanceof Boolean
                        && (Boolean) value;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean isFlexibleTask(
            Object task
    ) {
        if (task == null) return false;

        int mode = taskWindowingMode(task);

        if (mode != 0 && mode != 1) {
            return true;
        }

        Rect bounds = taskBounds(task);
        Rect maxBounds = taskMaxBounds(task);

        return bounds != null
                && maxBounds != null
                && !bounds.isEmpty()
                && !maxBounds.isEmpty()
                && !bounds.equals(maxBounds);
    }

    private static int taskWindowingMode(
            Object task
    ) {
        Object value =
                invokeNoArg(
                        task,
                        "getWindowingMode");

        return value instanceof Number
                ? ((Number) value).intValue()
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

        if (windowConfig == null) {
            windowConfig =
                    invokeNoArg(
                            config,
                            "getWindowConfiguration");
        }

        Object value =
                invokeNoArg(
                        windowConfig,
                        "getMaxBounds");

        return value instanceof Rect
                ? new Rect((Rect) value)
                : new Rect();
    }

    private static int taskDisplayId(
            Object task
    ) {
        Object value =
                invokeNoArg(
                        task,
                        "getDisplayId");

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        Object displayArea =
                invokeNoArg(
                        task,
                        "getDisplayArea");

        value =
                invokeNoArg(
                        displayArea,
                        "getDisplayId");

        return value instanceof Number
                ? ((Number) value).intValue()
                : 0;
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
        Object value =
                invokeNoArg(
                        task,
                        "getTaskId");

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        value = fieldValue(
                task,
                "mTaskId");

        return value instanceof Number
                ? ((Number) value).intValue()
                : -1;
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

    private static Class<?> loadSystemClass(
            String name
    ) throws ClassNotFoundException {
        ClassLoader own =
                OplusFlexibleWindowController.class
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

        Method method =
                findMethod(
                        receiver.getClass(),
                        methodName);

        if (method == null) return null;

        try {
            method.setAccessible(true);
            return method.invoke(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(
            Class<?> type,
            String name
    ) {
        Class<?> current = type;

        while (current != null) {
            for (Method method :
                    current.getDeclaredMethods()) {
                if (name.equals(method.getName())
                        && method.getParameterCount() == 0) {
                    return method;
                }
            }

            current =
                    current.getSuperclass();
        }

        try {
            return type.getMethod(name);
        } catch (Throwable ignored) {
            return null;
        }
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

                return field.get(receiver);
            } catch (Throwable ignored) {
                current =
                        current.getSuperclass();
            }
        }

        return null;
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

        volatile int state;
        volatile boolean active = true;
        volatile boolean enterConfirmed;
        volatile int toggleAttempts;
        volatile long lastToggleElapsed;
        volatile long lastSeenElapsed =
                SystemClock.elapsedRealtime();

        Session(
                int taskId,
                String packageName,
                Object taskObject,
                Object activityRecord,
                int state
        ) {
            this.taskId = taskId;
            this.packageName = packageName;
            this.taskObject = taskObject;
            this.activityRecord = activityRecord;
            this.state = state;
        }
    }
}
