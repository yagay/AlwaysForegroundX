package com.yagay.MiniWindowGuard;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
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
    private static final long POLL_MS = 250L;

    private final Handler handler;
    private final Context systemContext;
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
        applyState(managed, managed.state);
        notifyCaptured(managed);

        log("TASK_CAPTURED",
                "pkg=" + packageName
                        + " taskId=" + taskId
                        + " originalBounds=" + originalBounds
                        + " originalWindowingMode=" + originalWindowingMode
                        + " state=" + managed.state);
    }

    void releasePackage(String packageName) {
        ManagedTask task = findLatest(packageName);
        if (task != null) {
            applyState(task, ConfigKeys.STATE_RELEASED);
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
        Object task = managed.taskObject;
        Object lock = globalLock(task);

        Runnable work = () -> {
            try {
                if (state == ConfigKeys.STATE_RELEASED) {
                    restoreTask(managed);
                    managed.state = state;
                    tasks.remove(managed.taskId, managed);
                    notifyCaptured(managed);
                    log("TASK_RELEASED",
                            "pkg=" + managed.packageName
                                    + " taskId=" + managed.taskId);
                    return;
                }

                Rect windowBounds = containerBounds();
                Rect offscreenBounds = offscreenBounds(windowBounds);

                setWindowingMode(task, WINDOWING_MODE_FREEFORM);
                setAlwaysOnTop(task,
                        GuardConfig.bool(ConfigKeys.CONTAINER_ALWAYS_ON_TOP));

                if (state == ConfigKeys.STATE_WINDOW) {
                    setBounds(task, windowBounds);
                    setFocusable(task, true);
                    setSurfaceAlpha(task, 1f);
                } else {
                    // Keep the same width/height to avoid a size configuration change.
                    // Move the task outside the physical display and make its Surface transparent.
                    setBounds(task, offscreenBounds);
                    setFocusable(task, false);
                    setSurfaceAlpha(task, 0f);
                }

                managed.state = state;
                managed.lastBounds = new Rect(
                        state == ConfigKeys.STATE_WINDOW
                                ? windowBounds : offscreenBounds);
                managed.lastSeenElapsed = SystemClock.elapsedRealtime();

                notifyCaptured(managed);
                log("TASK_STATE",
                        "pkg=" + managed.packageName
                                + " taskId=" + managed.taskId
                                + " state=" + state
                                + " bounds=" + managed.lastBounds);
            } catch (Throwable t) {
                log("TASK_STATE_ERROR",
                        "pkg=" + managed.packageName
                                + " taskId=" + managed.taskId
                                + " state=" + state
                                + " error=" + t);
            }
        };

        if (lock != null) {
            synchronized (lock) {
                work.run();
            }
        } else {
            synchronized (task) {
                work.run();
            }
        }
    }

    private void restoreTask(ManagedTask managed) {
        Object task = managed.taskObject;
        setSurfaceAlpha(task, 1f);
        setFocusable(task, true);
        setAlwaysOnTop(task, false);

        Rect original = managed.originalBounds;
        if (original != null) {
            setBounds(task, new Rect(original));
        }

        int originalMode = managed.originalWindowingMode;
        if (originalMode <= 0) originalMode = WINDOWING_MODE_FULLSCREEN;
        setWindowingMode(task, originalMode);
    }

    private Rect containerBounds() {
        android.util.DisplayMetrics dm =
                Resources.getSystem().getDisplayMetrics();

        int screenW = Math.max(1, dm.widthPixels);
        int screenH = Math.max(1, dm.heightPixels);
        float density = Math.max(1f, dm.density);

        int width = Math.round(screenW * GuardConfig.containerWidth() / 100f);
        int height = Math.round(screenH * GuardConfig.containerHeight() / 100f);

        width = Math.min(screenW, Math.max(Math.round(320 * density), width));
        height = Math.min(screenH, Math.max(Math.round(420 * density), height));

        int margin = Math.round(18 * density);
        int headerReserve = Math.round(96 * density);

        int left = Math.max(0, screenW - width - margin);
        int top = Math.min(
                Math.max(margin, headerReserve),
                Math.max(0, screenH - height - margin));

        return new Rect(left, top, left + width, top + height);
    }

    private Rect offscreenBounds(Rect visible) {
        android.util.DisplayMetrics dm =
                Resources.getSystem().getDisplayMetrics();
        int gap = Math.max(64, Math.round(32 * Math.max(1f, dm.density)));
        int left = dm.widthPixels + gap;
        return new Rect(
                left,
                visible.top,
                left + visible.width(),
                visible.top + visible.height());
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

    private void setBounds(Object task, Rect bounds) {
        if (invokeCompatible(task, "setBounds", bounds)) return;
        invokeCompatible(task, "setBoundsUnchecked", bounds);
    }

    private void setWindowingMode(Object task, int mode) {
        invokeCompatible(task, "setWindowingMode", mode);
    }

    private void setFocusable(Object task, boolean focusable) {
        if (invokeCompatible(task, "setFocusable", focusable)) return;
        invokeCompatible(task, "setCanReceiveKeys", focusable);
    }

    private void setAlwaysOnTop(Object task, boolean alwaysOnTop) {
        invokeCompatible(task, "setAlwaysOnTop", alwaysOnTop);
    }

    private void setSurfaceAlpha(Object task, float alpha) {
        Object surface = invokeNoArg(task, "getSurfaceControl");
        if (surface == null) return;

        Object transaction = null;
        try {
            ClassLoader loader = task.getClass().getClassLoader();
            Class<?> transactionClass = Class.forName(
                    "android.view.SurfaceControl$Transaction",
                    false,
                    loader);

            Constructor<?> ctor = transactionClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            transaction = ctor.newInstance();

            invokeCompatible(transaction, "setAlpha", surface, alpha);
            invokeCompatible(transaction, "show", surface);
            invokeCompatible(transaction, "apply");
        } catch (Throwable t) {
            log("SURFACE_ALPHA_ERROR",
                    "alpha=" + alpha + " error=" + t);
        } finally {
            if (transaction != null) {
                invokeCompatible(transaction, "close");
            }
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

    private static Object globalLock(Object task) {
        Object service = fieldValue(task, "mAtmService");
        if (service == null) service = fieldValue(task, "mService");
        if (service == null) return null;

        Object lock = fieldValue(service, "mGlobalLock");
        return lock != null ? lock : service;
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
        Method method = findCompatibleMethod(receiver.getClass(), name, new Object[0]);
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
