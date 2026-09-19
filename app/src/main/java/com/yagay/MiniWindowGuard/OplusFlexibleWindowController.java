package com.yagay.MiniWindowGuard;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime state for OxygenOS / ColorOS FlexibleWindow.
 *
 * This class never creates a window, Surface, VirtualDisplay or overlay.
 * OxygenOS owns all rendering and interaction. We only request the OEM window,
 * remember the requested task, and expose whether the task is *currently*
 * inside the OPlus flexible/floating state.
 */
final class OplusFlexibleWindowController {
    interface Logger {
        void log(String event, String detail);
    }

    private static final long COMMAND_POLL_MS = 180L;
    private static final long TOGGLE_DEBOUNCE_MS = 900L;

    private final Handler handler;
    private final Logger logger;
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
            Logger logger
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

        log(
                "OPLUS_ENGINE_READY",
                "toggleApi=" + (toggleFlexibleWindow != null));
    }

    void shutdown() {
        running = false;
        handler.removeCallbacks(commandPoll);
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
        if (packageName == null || packageName.isBlank()) {
            return false;
        }

        if (packageName.equals(pendingPackage)) {
            return true;
        }

        for (Session session : sessions.values()) {
            if (session.active
                    && packageName.equals(session.packageName)) {
                return true;
            }
        }

        return immediateCommandState(packageName) != null;
    }

    boolean isKnownPackage(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }

        if (packageName.equals(pendingPackage)) {
            return true;
        }

        String commandPackage =
                GuardConfig.string(
                        ConfigKeys.OPLUS_COMMAND_PACKAGE);

        if (packageName.equals(commandPackage)) {
            return true;
        }

        for (Session session : sessions.values()) {
            if (session.active
                    && packageName.equals(session.packageName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Foreground/kill protection is intentionally stricter than "known".
     * A package is protected only while OxygenOS still owns the task as an
     * actual flexible window or its native edge/minimized floating handle.
     */
    boolean isProtectedPackage(String packageName) {
        if (packageName == null) return false;

        for (Session session : sessions.values()) {
            if (!session.active
                    || !packageName.equals(session.packageName)) {
                continue;
            }

            if (isSessionProtected(session)) {
                return true;
            }
        }

        return false;
    }

    String protectedPackageForProcess(String processName) {
        if (processName == null || processName.isBlank()) {
            return null;
        }

        for (Session session : sessions.values()) {
            if (!session.active
                    || !isSessionProtected(session)) {
                continue;
            }

            String pkg = session.packageName;
            if (processName.equals(pkg)
                    || processName.startsWith(pkg + ":")) {
                return pkg;
            }
        }

        return null;
    }

    void capture(
            Object activityRecord,
            String packageName
    ) {
        if (activityRecord == null
                || packageName == null
                || !GuardConfig.enabled()
                || !wantsPackage(packageName)) {
            return;
        }

        Object task = activityTask(activityRecord);
        if (task == null) {
            log(
                    "OPLUS_CAPTURE_SKIP",
                    "pkg=" + packageName
                            + " reason=no-task");
            return;
        }

        if (systemContext == null) {
            systemContext = deriveSystemContext(task);
        }

        int taskId = taskId(task);
        if (taskId < 0) {
            log(
                    "OPLUS_CAPTURE_SKIP",
                    "pkg=" + packageName
                            + " reason=no-task-id");
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

        Session session = sessions.get(taskId);

        if (session == null || !session.active) {
            session = new Session(
                    taskId,
                    packageName,
                    task,
                    activityRecord,
                    initialState);
            sessions.put(taskId, session);

            log(
                    "OPLUS_TASK_TRACKED",
                    "pkg=" + packageName
                            + " taskId=" + taskId
                            + " bounds=" + taskBounds(task)
                            + " maxBounds=" + taskMaxBounds(task)
                            + " flexible="
                            + isFlexibleTask(task)
                            + " floating="
                            + isInFloatingList(taskId));
        } else {
            session.taskObject = task;
            session.activityRecord = activityRecord;
            session.state = initialState;
            session.lastSeenElapsed =
                    SystemClock.elapsedRealtime();

            log(
                    "OPLUS_ACTIVITY_CHANGED",
                    "pkg=" + packageName
                            + " taskId=" + taskId
                            + " activity="
                            + activityComponent(activityRecord)
                            + " flexible="
                            + isFlexibleTask(task));
        }

        if (packageName.equals(pendingPackage)) {
            pendingPackage = "";
        }

        if (initialState == ConfigKeys.STATE_RELEASED) {
            releaseSession(
                    session,
                    "capture-release");
            return;
        }

        final Session target = session;

        // Let the newly resumed Activity finish its own configuration first.
        // This is important for video/series Activities that request fullscreen
        // during launch; OPlus support hooks decide whether it remains eligible.
        handler.postDelayed(
                () -> ensureFlexible(
                        target,
                        "activity-resumed"),
                120L);

        handler.postDelayed(
                () -> verifyState(
                        target,
                        "activity-resumed"),
                650L);
    }

    void onOplusTaskInfoChanged(Object taskInfo) {
        if (taskInfo == null) return;

        int taskId = taskInfoId(taskInfo);
        if (taskId < 0) return;

        Session session = sessions.get(taskId);
        if (session == null || !session.active) {
            return;
        }

        String pkg = taskInfoPackage(taskInfo);
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
                        && !bounds.equals(maxBounds);

        session.oemReportedFlexible =
                embedded || bounded;
        session.lastOplusStateElapsed =
                SystemClock.elapsedRealtime();

        log(
                "OPLUS_TASK_INFO",
                "pkg=" + session.packageName
                        + " reportedPkg=" + pkg
                        + " taskId=" + taskId
                        + " embedded=" + embedded
                        + " bounded=" + bounded
                        + " bounds=" + bounds
                        + " maxBounds=" + maxBounds
                        + " protected="
                        + isSessionProtected(session));
    }

    void onOplusTaskVanished(Object taskInfo) {
        if (taskInfo == null) return;

        int taskId = taskInfoId(taskInfo);
        if (taskId < 0) return;

        Session session = sessions.get(taskId);
        if (session == null) return;

        session.oemReportedFlexible = false;
        session.lastOplusStateElapsed =
                SystemClock.elapsedRealtime();

        boolean runningTask =
                booleanField(
                        taskInfo,
                        "isRunning",
                        true);

        int displayId =
                intField(
                        taskInfo,
                        "displayId",
                        0);

        log(
                "OPLUS_TASK_VANISHED",
                "pkg=" + session.packageName
                        + " taskId=" + taskId
                        + " isRunning=" + runningTask
                        + " displayId=" + displayId);

        if (!runningTask || displayId < 0) {
            releaseSession(
                    session,
                    "task-vanished");
        }
    }

    private boolean isSessionProtected(Session session) {
        if (session == null
                || !session.active
                || session.state == ConfigKeys.STATE_RELEASED) {
            return false;
        }

        if (session.oemReportedFlexible) {
            return true;
        }

        Object task = session.taskObject;

        return isFlexibleTask(task)
                || isInFloatingList(session.taskId);
    }

    private Integer immediateCommandState(String packageName) {
        int seq =
                GuardConfig.integer(
                        ConfigKeys.OPLUS_COMMAND_SEQ);

        if (seq == lastCommandSeq) {
            return null;
        }

        String commandPackage =
                GuardConfig.string(
                        ConfigKeys.OPLUS_COMMAND_PACKAGE);

        if (!packageName.equals(commandPackage)) {
            return null;
        }

        return ConfigKeys.sanitizeState(
                GuardConfig.integer(
                        ConfigKeys.OPLUS_COMMAND_STATE));
    }

    private final Runnable commandPoll = new Runnable() {
        @Override
        public void run() {
            try {
                int seq =
                        GuardConfig.integer(
                                ConfigKeys.OPLUS_COMMAND_SEQ);

                if (seq != lastCommandSeq) {
                    lastCommandSeq = seq;

                    String pkg =
                            GuardConfig.string(
                                    ConfigKeys.OPLUS_COMMAND_PACKAGE);

                    int state =
                            ConfigKeys.sanitizeState(
                                    GuardConfig.integer(
                                            ConfigKeys.OPLUS_COMMAND_STATE));

                    if (!pkg.isEmpty()) {
                        Session session =
                                latestSession(pkg);

                        if (state
                                == ConfigKeys.STATE_RELEASED) {
                            if (session != null) {
                                releaseSession(
                                        session,
                                        "remote-command");
                            }
                        } else if (session != null) {
                            session.state = state;
                            ensureFlexible(
                                    session,
                                    "remote-command");
                        } else {
                            pendingPackage = pkg;
                            pendingState = state;

                            log(
                                    "OPLUS_COMMAND_PENDING",
                                    "seq=" + seq
                                            + " pkg=" + pkg
                                            + " state=" + state);
                        }
                    }
                }
            } catch (Throwable t) {
                log(
                        "OPLUS_COMMAND_ERROR",
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

    private void ensureFlexible(
            Session session,
            String reason
    ) {
        if (session == null
                || !session.active
                || session.state == ConfigKeys.STATE_RELEASED) {
            return;
        }

        if (isSessionProtected(session)) {
            log(
                    "OPLUS_FLEX_ALREADY",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " reason=" + reason);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - session.lastToggleElapsed
                < TOGGLE_DEBOUNCE_MS) {
            return;
        }

        toggleFlexible(
                session,
                true,
                reason);
    }

    private boolean toggleFlexible(
            Session session,
            boolean enter,
            String reason
    ) {
        resolveOplusApi();

        Object manager = oplusAtm;
        Method toggle = toggleFlexibleWindow;

        if (session == null
                || manager == null
                || toggle == null) {
            log(
                    "OPLUS_FLEX_FAILED",
                    "taskId="
                            + (session == null
                            ? -1
                            : session.taskId)
                            + " enter=" + enter
                            + " reason=" + reason
                            + " error=api-unavailable");
            return false;
        }

        try {
            session.lastToggleElapsed =
                    SystemClock.elapsedRealtime();

            Object result =
                    toggle.invoke(
                            manager,
                            null,
                            session.taskId,
                            true,
                            enter);

            log(
                    "OPLUS_FLEX_TOGGLE",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " enter=" + enter
                            + " reason=" + reason
                            + " result=" + result
                            + " bounds="
                            + taskBounds(session.taskObject));

            return true;
        } catch (Throwable t) {
            log(
                    "OPLUS_FLEX_FAILED",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " enter=" + enter
                            + " reason=" + reason
                            + " error=" + t);
            return false;
        }
    }

    private void verifyState(
            Session session,
            String reason
    ) {
        if (session == null || !session.active) {
            return;
        }

        boolean flexible =
                isFlexibleTask(session.taskObject);
        boolean floating =
                isInFloatingList(session.taskId);

        log(
                "OPLUS_FLEX_VERIFY",
                "pkg=" + session.packageName
                        + " taskId=" + session.taskId
                        + " reason=" + reason
                        + " flexible=" + flexible
                        + " floating=" + floating
                        + " oemReported="
                        + session.oemReportedFlexible
                        + " bounds="
                        + taskBounds(session.taskObject)
                        + " maxBounds="
                        + taskMaxBounds(session.taskObject));
    }

    private void releaseSession(
            Session session,
            String reason
    ) {
        if (session == null) return;

        session.active = false;
        session.state = ConfigKeys.STATE_RELEASED;
        session.oemReportedFlexible = false;

        sessions.remove(
                session.taskId,
                session);

        log(
                "OPLUS_TASK_RELEASED",
                "pkg=" + session.packageName
                        + " taskId=" + session.taskId
                        + " reason=" + reason);
    }

    private Session latestSession(String packageName) {
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

            Object manager =
                    cls.getMethod("getInstance")
                            .invoke(null);

            Method toggle =
                    cls.getMethod(
                            "toggleFlexibleWindow",
                            IBinder.class,
                            int.class,
                            boolean.class,
                            boolean.class);

            toggle.setAccessible(true);

            oplusAtm = manager;
            toggleFlexibleWindow = toggle;

            log(
                    "OPLUS_API_READY",
                    toggle.toGenericString());
        } catch (Throwable t) {
            oplusAtm = null;
            toggleFlexibleWindow = null;

            log(
                    "OPLUS_API_ERROR",
                    String.valueOf(t));
        }
    }

    private boolean isInFloatingList(int taskId) {
        if (taskId < 0) return false;

        try {
            Class<?> cls =
                    loadSystemClass(
                            "com.android.server.wm.FloatHandleController");

            Object instance =
                    cls.getMethod("getInstance")
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

    private static boolean isFlexibleTask(Object task) {
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

    private static int taskWindowingMode(Object task) {
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

    private static Rect taskMaxBounds(Object task) {
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

    private static Object activityTask(Object activityRecord) {
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

    private static int taskId(Object task) {
        Object value =
                invokeNoArg(
                        task,
                        "getTaskId");

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        value =
                fieldValue(
                        task,
                        "mTaskId");

        return value instanceof Number
                ? ((Number) value).intValue()
                : -1;
    }

    private static String activityComponent(
            Object activityRecord
    ) {
        Object component =
                fieldValue(
                        activityRecord,
                        "mActivityComponent");

        return String.valueOf(component);
    }

    private static Context deriveSystemContext(Object task) {
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

    private static int taskInfoId(Object taskInfo) {
        return intField(
                taskInfo,
                "taskId",
                -1);
    }

    private static String taskInfoPackage(Object taskInfo) {
        for (String field : new String[]{
                "topActivity",
                "baseActivity",
                "realActivity"
        }) {
            Object value =
                    fieldValue(
                            taskInfo,
                            field);

            if (value instanceof ComponentName) {
                return ((ComponentName) value)
                        .getPackageName();
            }
        }

        Object info =
                fieldValue(
                        taskInfo,
                        "topActivityInfo");

        if (info instanceof ActivityInfo) {
            return ((ActivityInfo) info)
                    .packageName;
        }

        return null;
    }

    private static Rect taskInfoBounds(Object taskInfo) {
        Object config =
                fieldValue(
                        taskInfo,
                        "configuration");

        return configurationBounds(config);
    }

    private static Rect taskInfoMaxBounds(Object taskInfo) {
        Object config =
                fieldValue(
                        taskInfo,
                        "configuration");

        return configurationMaxBounds(config);
    }

    private static Rect configurationBounds(Object config) {
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

    private static Rect configurationMaxBounds(Object config) {
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
                findNoArgMethod(
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

    private static Method findNoArgMethod(
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

            current = current.getSuperclass();
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

                return field.get(receiver);
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

        return value instanceof Boolean
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

        return value instanceof Number
                ? ((Number) value).intValue()
                : fallback;
    }

    private void log(String event, String detail) {
        if (logger != null) {
            logger.log(event, detail);
        }
    }

    private static final class Session {
        final int taskId;
        final String packageName;

        volatile Object taskObject;
        volatile Object activityRecord;

        volatile int state;
        volatile boolean active = true;
        volatile boolean oemReportedFlexible;

        volatile long lastToggleElapsed;
        volatile long lastOplusStateElapsed;
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
