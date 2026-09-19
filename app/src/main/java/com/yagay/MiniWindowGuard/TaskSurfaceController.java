package com.yagay.MiniWindowGuard;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class TaskSurfaceController {
    static final String ACTION_TASK_CAPTURED =
            "com.yagay.MiniWindowGuard.action.TASK_CAPTURED";
    static final String EXTRA_PACKAGE = "package";
    static final String EXTRA_TASK_ID = "task_id";
    static final String EXTRA_STATE = "state";

    interface Logger {
        void log(String event, String detail);
    }

    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int WINDOWING_MODE_MULTI_WINDOW = 6;

    private static final long POLL_MS = 250L;
    private static final long VERIFY_MS = 650L;

    private final Handler handler;
    private volatile Context systemContext;
    private final Logger logger;
    private final Map<Integer, ManagedTask> tasks = new ConcurrentHashMap<>();

    private volatile int lastCommandSeq = Integer.MIN_VALUE;

    TaskSurfaceController(Handler handler, Context systemContext, Logger logger) {
        this.handler = handler;
        this.systemContext = systemContext;
        this.logger = logger;
    }

    void start() {
        handler.post(commandPoll);
    }

    boolean isManagedPackage(String packageName) {
        if (packageName == null) return false;
        for (ManagedTask task : tasks.values()) {
            if (packageName.equals(task.packageName)
                    && task.state != ConfigKeys.STATE_RELEASED) {
                return true;
            }
        }
        return false;
    }

    boolean isManagedActivityRecord(Object activityRecord) {
        String pkg = activityPackage(activityRecord);
        return isManagedPackage(pkg);
    }

    int stateForPackage(String packageName) {
        ManagedTask task = findLatest(packageName);
        return task == null ? ConfigKeys.STATE_RELEASED : task.state;
    }

    void capture(Object activityRecord, String packageName) {
        if (activityRecord == null
                || packageName == null
                || !GuardConfig.bool(ConfigKeys.AUTO_CONTAINER)
                || !GuardConfig.isTargetPackage(packageName)) {
            return;
        }

        Object taskObject = getTask(activityRecord);
        if (taskObject == null) {
            log("TASK_CAPTURE_SKIP", "pkg=" + packageName + " reason=no-task");
            return;
        }

        if (systemContext == null) {
            systemContext = deriveSystemContext(taskObject);
        }

        int taskId = taskId(taskObject);
        if (taskId < 0) {
            log("TASK_CAPTURE_SKIP", "pkg=" + packageName + " reason=no-task-id");
            return;
        }

        ManagedTask existing = tasks.get(taskId);
        if (existing != null) {
            existing.lastSeenElapsed = SystemClock.elapsedRealtime();
            return;
        }

        Rect originalBounds = readBounds(taskObject);
        int originalWindowingMode = readWindowingMode(taskObject);

        ManagedTask managed = new ManagedTask(
                taskId,
                packageName,
                taskObject,
                originalBounds,
                originalWindowingMode,
                GuardConfig.defaultContainerState());

        tasks.put(taskId, managed);

        log("TASK_CAPTURED",
                "pkg=" + packageName
                        + " taskId=" + taskId
                        + " originalBounds=" + originalBounds
                        + " originalWindowingMode=" + originalWindowingMode
                        + " state=" + managed.state);

        // ActivityRecord.setState can be called while WM owns its global lock.
        // Run WindowOrganizer transactions after that call returns.
        handler.post(() -> applyState(managed, managed.state));
    }

    void releasePackage(String packageName) {
        ManagedTask task = findLatest(packageName);
        if (task != null) {
            handler.post(() -> applyState(task, ConfigKeys.STATE_RELEASED));
        }
    }

    private final Runnable commandPoll = new Runnable() {
        @Override
        public void run() {
            try {
                int seq = GuardConfig.integer(ConfigKeys.CONTAINER_COMMAND_SEQ);
                if (seq != lastCommandSeq) {
                    lastCommandSeq = seq;
                    String pkg = GuardConfig.string(
                            ConfigKeys.CONTAINER_COMMAND_PACKAGE);
                    int state = ConfigKeys.sanitizeState(
                            GuardConfig.integer(ConfigKeys.CONTAINER_COMMAND_STATE));

                    if (!pkg.isEmpty()) {
                        ManagedTask task = findLatest(pkg);
                        if (task != null) {
                            applyState(task, state);
                        } else {
                            log("TASK_COMMAND_MISS",
                                    "seq=" + seq
                                            + " pkg=" + pkg
                                            + " state=" + state);
                        }
                    }
                }
            } catch (Throwable t) {
                log("TASK_COMMAND_ERROR", String.valueOf(t));
            } finally {
                handler.postDelayed(this, POLL_MS);
            }
        }
    };

    private ManagedTask findLatest(String packageName) {
        ManagedTask newest = null;
        for (ManagedTask task : tasks.values()) {
            if (!task.packageName.equals(packageName)) continue;
            if (newest == null || task.lastSeenElapsed > newest.lastSeenElapsed) {
                newest = task;
            }
        }
        return newest;
    }

    private void applyState(ManagedTask managed, int requestedState) {
        int state = ConfigKeys.sanitizeState(requestedState);

        try {
            if (state == ConfigKeys.STATE_RELEASED) {
                restoreTask(managed);
                managed.state = state;
                managed.lastSeenElapsed = SystemClock.elapsedRealtime();
                tasks.remove(managed.taskId, managed);
                notifyCaptured(managed);
                log("TASK_RELEASED",
                        "pkg=" + managed.packageName
                                + " taskId=" + managed.taskId);
                return;
            }

            if (state == ConfigKeys.STATE_WINDOW) {
                applyWindowState(managed, WINDOWING_MODE_MULTI_WINDOW, 1);
                return;
            }

            applyBackgroundState(managed, state);
        } catch (Throwable t) {
            log("TASK_STATE_ERROR",
                    "pkg=" + managed.packageName
                            + " taskId=" + managed.taskId
                            + " state=" + state
                            + " error=" + t);
        }
    }

    private void applyWindowState(
            ManagedTask managed,
            int windowingMode,
            int attempt
    ) {
        Rect bounds = containerBounds();
        boolean ok = applyWindowContainerTransaction(
                managed.taskObject,
                windowingMode,
                bounds,
                true,
                GuardConfig.bool(ConfigKeys.CONTAINER_ALWAYS_ON_TOP),
                true);

        if (!ok) {
            log("TASK_WCT_ERROR",
                    "pkg=" + managed.packageName
                            + " taskId=" + managed.taskId
                            + " mode=" + windowingMode
                            + " reason=apply-failed");
            return;
        }

        managed.state = ConfigKeys.STATE_WINDOW;
        managed.lastBounds = new Rect(bounds);
        managed.lastSeenElapsed = SystemClock.elapsedRealtime();

        notifyCaptured(managed);
        log("TASK_STATE",
                "pkg=" + managed.packageName
                        + " taskId=" + managed.taskId
                        + " state=" + ConfigKeys.STATE_WINDOW
                        + " backend=WCT"
                        + " requestedMode=" + windowingMode
                        + " bounds=" + bounds
                        + " attempt=" + attempt);

        handler.postDelayed(
                () -> verifyWindowState(managed, windowingMode, attempt),
                VERIFY_MS);
    }

    private void verifyWindowState(
            ManagedTask managed,
            int requestedMode,
            int attempt
    ) {
        if (managed.state != ConfigKeys.STATE_WINDOW
                || !tasks.containsKey(managed.taskId)) {
            return;
        }

        int actualMode = readWindowingMode(managed.taskObject);
        Rect actualBounds = readBounds(managed.taskObject);
        Rect requestedBounds = managed.lastBounds;

        boolean boundsOk = boundsApproximatelyEqual(
                requestedBounds,
                actualBounds,
                12);
        boolean modeOk = actualMode == requestedMode
                || actualMode == WINDOWING_MODE_MULTI_WINDOW
                || actualMode == WINDOWING_MODE_FREEFORM;

        log("TASK_VERIFY",
                "pkg=" + managed.packageName
                        + " taskId=" + managed.taskId
                        + " requestedMode=" + requestedMode
                        + " actualMode=" + actualMode
                        + " requestedBounds=" + requestedBounds
                        + " actualBounds=" + actualBounds
                        + " modeOk=" + modeOk
                        + " boundsOk=" + boundsOk
                        + " attempt=" + attempt);

        if (modeOk && boundsOk) return;

        if (attempt == 1 && requestedMode != WINDOWING_MODE_FREEFORM) {
            log("TASK_BACKEND_FALLBACK",
                    "pkg=" + managed.packageName
                            + " taskId=" + managed.taskId
                            + " from=MULTI_WINDOW to=FREEFORM");
            applyWindowState(managed, WINDOWING_MODE_FREEFORM, 2);
            return;
        }

        log("TASK_WINDOW_UNSUPPORTED",
                "pkg=" + managed.packageName
                        + " taskId=" + managed.taskId
                        + " actualMode=" + actualMode
                        + " actualBounds=" + actualBounds);
    }

    private void applyBackgroundState(ManagedTask managed, int state) {
        // Never make the currently focused top task transparent/off-screen.
        // A transparent top task leaves no visible activity behind it and produces a black screen.
        boolean ok = applyWindowContainerTransaction(
                managed.taskObject,
                readWindowingMode(managed.taskObject),
                null,
                false,
                false,
                false);

        if (!ok) {
            log("TASK_BACKGROUND_ERROR",
                    "pkg=" + managed.packageName
                            + " taskId=" + managed.taskId
                            + " state=" + state
                            + " reason=reorder-failed");
            return;
        }

        managed.state = state;
        managed.lastSeenElapsed = SystemClock.elapsedRealtime();

        notifyCaptured(managed);
        log("TASK_STATE",
                "pkg=" + managed.packageName
                        + " taskId=" + managed.taskId
                        + " state=" + state
                        + " backend=WCT"
                        + " action=reorder-to-back");
    }

    private void restoreTask(ManagedTask managed) {
        Rect original = managed.originalBounds;
        int originalMode = managed.originalWindowingMode;
        if (originalMode <= 0) originalMode = WINDOWING_MODE_FULLSCREEN;

        applyWindowContainerTransaction(
                managed.taskObject,
                originalMode,
                original,
                true,
                false,
                true);
    }

    private Rect containerBounds() {
        return ContainerGeometry.visibleBounds(
                Resources.getSystem(),
                GuardConfig.containerWidth(),
                GuardConfig.containerHeight());
    }

    /**
     * Uses the same WindowContainerTransaction path used by AOSP TaskView/Desktop mode.
     * Direct Task.setBounds()/setWindowingMode() is not stable because Shell/TaskOrganizer
     * can overwrite those internal changes immediately.
     */
    private boolean applyWindowContainerTransaction(
            Object task,
            int windowingMode,
            Rect bounds,
            boolean focusable,
            boolean alwaysOnTop,
            boolean onTop
    ) {
        if (task == null) return false;

        try {
            Object token = taskWindowContainerToken(task);
            Object atm = fieldValue(task, "mAtmService");
            if (atm == null) atm = fieldValue(task, "mService");
            Object organizer = fieldValue(atm, "mWindowOrganizerController");

            if (token == null || organizer == null) {
                log("TASK_WCT_ERROR",
                        "token=" + token + " organizer=" + organizer);
                return false;
            }

            ClassLoader loader = task.getClass().getClassLoader();
            Class<?> wctClass = Class.forName(
                    "android.window.WindowContainerTransaction",
                    false,
                    loader);

            Object wct = wctClass.getDeclaredConstructor().newInstance();

            if (windowingMode > 0) {
                invokeCompatible(
                        wct,
                        "setWindowingMode",
                        token,
                        windowingMode);
            }

            if (bounds != null) {
                invokeCompatible(
                        wct,
                        "setBounds",
                        token,
                        new Rect(bounds));
            }

            invokeCompatible(
                    wct,
                    "setFocusable",
                    token,
                    focusable);

            invokeCompatible(
                    wct,
                    "setAlwaysOnTop",
                    token,
                    alwaysOnTop);

            boolean reordered = invokeCompatible(
                    wct,
                    "reorder",
                    token,
                    onTop,
                    true);
            if (!reordered) {
                invokeCompatible(
                        wct,
                        "reorder",
                        token,
                        onTop);
            }

            Method apply = findCompatibleMethod(
                    organizer.getClass(),
                    "applyTransaction",
                    new Object[]{wct});
            if (apply == null) {
                log("TASK_WCT_ERROR",
                        "reason=no-applyTransaction organizer="
                                + organizer.getClass().getName());
                return false;
            }

            apply.setAccessible(true);
            apply.invoke(organizer, wct);
            return true;
        } catch (Throwable t) {
            log("TASK_WCT_ERROR", "error=" + t);
            return false;
        }
    }

    private static Object taskWindowContainerToken(Object task) {
        Object remoteToken = fieldValue(task, "mRemoteToken");
        if (remoteToken == null) return null;
        return invokeNoArg(remoteToken, "toWindowContainerToken");
    }

    private void notifyCaptured(ManagedTask task) {
        Context context = systemContext;
        if (context == null) return;

        try {
            Intent event = new Intent(ACTION_TASK_CAPTURED);
            event.setPackage("com.yagay.MiniWindowGuard");
            event.putExtra(EXTRA_PACKAGE, task.packageName);
            event.putExtra(EXTRA_TASK_ID, task.taskId);
            event.putExtra(EXTRA_STATE, task.state);
            context.sendBroadcast(event);
        } catch (Throwable t) {
            log("TASK_EVENT_ERROR", String.valueOf(t));
        }
    }

    private static Object getTask(Object activityRecord) {
        Object task = invokeNoArg(activityRecord, "getTask");
        if (task != null) return task;

        Object field = fieldValue(activityRecord, "task");
        if (field != null) return field;
        return fieldValue(activityRecord, "mTask");
    }

    private static int taskId(Object task) {
        Object value = invokeNoArg(task, "getTaskId");
        if (value instanceof Integer) return (Integer) value;

        Object field = fieldValue(task, "mTaskId");
        return field instanceof Integer ? (Integer) field : -1;
    }

    private static Rect readBounds(Object task) {
        Object value = invokeNoArg(task, "getBounds");
        if (value instanceof Rect) return new Rect((Rect) value);

        Object field = fieldValue(task, "mBounds");
        return field instanceof Rect ? new Rect((Rect) field) : null;
    }

    private static int readWindowingMode(Object task) {
        Object value = invokeNoArg(task, "getWindowingMode");
        return value instanceof Integer ? (Integer) value : WINDOWING_MODE_FULLSCREEN;
    }

    private static Context deriveSystemContext(Object task) {
        Object service = fieldValue(task, "mAtmService");
        if (service == null) service = fieldValue(task, "mService");
        Object context = fieldValue(service, "mContext");
        return context instanceof Context ? (Context) context : null;
    }

    private static String activityPackage(Object activityRecord) {
        Object value = fieldValue(activityRecord, "packageName");
        if (value instanceof String) return (String) value;

        Object component = fieldValue(activityRecord, "mActivityComponent");
        if (component instanceof android.content.ComponentName) {
            return ((android.content.ComponentName) component).getPackageName();
        }
        return null;
    }

    private static boolean boundsApproximatelyEqual(
            Rect expected,
            Rect actual,
            int tolerance
    ) {
        if (expected == null || actual == null) return false;
        return Math.abs(expected.left - actual.left) <= tolerance
                && Math.abs(expected.top - actual.top) <= tolerance
                && Math.abs(expected.right - actual.right) <= tolerance
                && Math.abs(expected.bottom - actual.bottom) <= tolerance;
    }

    private static Object fieldValue(Object receiver, String name) {
        if (receiver == null) return null;
        Field field = findField(receiver.getClass(), name);
        if (field == null) return null;
        try {
            field.setAccessible(true);
            return field.get(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object receiver, String name) {
        if (receiver == null) return null;
        Method method = findCompatibleMethod(
                receiver.getClass(),
                name,
                new Object[0]);
        if (method == null) return null;

        try {
            method.setAccessible(true);
            return method.invoke(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeCompatible(
            Object receiver,
            String name,
            Object... args
    ) {
        if (receiver == null) return false;
        Method method = findCompatibleMethod(receiver.getClass(), name, args);
        if (method == null) return false;

        try {
            method.setAccessible(true);
            method.invoke(receiver, args);
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
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!name.equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != args.length) continue;

                boolean compatible = true;
                for (int i = 0; i < params.length; i++) {
                    if (!isCompatible(params[i], args[i])) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) return method;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static boolean isCompatible(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        Class<?> valueType = value.getClass();
        if (type.isAssignableFrom(valueType)) return true;

        if (!type.isPrimitive()) return false;
        return (type == int.class && valueType == Integer.class)
                || (type == boolean.class && valueType == Boolean.class)
                || (type == float.class && valueType == Float.class)
                || (type == long.class && valueType == Long.class)
                || (type == double.class && valueType == Double.class);
    }

    private void log(String event, String detail) {
        if (logger != null) logger.log(event, detail);
    }

    private static final class ManagedTask {
        final int taskId;
        final String packageName;
        final Object taskObject;
        final Rect originalBounds;
        final int originalWindowingMode;

        volatile int state;
        volatile Rect lastBounds;
        volatile long lastSeenElapsed;

        ManagedTask(
                int taskId,
                String packageName,
                Object taskObject,
                Rect originalBounds,
                int originalWindowingMode,
                int state
        ) {
            this.taskId = taskId;
            this.packageName = packageName;
            this.taskObject = taskObject;
            this.originalBounds = originalBounds == null
                    ? null : new Rect(originalBounds);
            this.originalWindowingMode = originalWindowingMode;
            this.state = state;
            this.lastSeenElapsed = SystemClock.elapsedRealtime();
        }
    }
}
