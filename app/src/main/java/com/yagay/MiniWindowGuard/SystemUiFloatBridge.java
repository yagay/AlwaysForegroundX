package com.yagay.MiniWindowGuard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * SystemUI-side bridge for the native OPlus FloatHandle state machine.
 *
 * system_server only decides which task should become a background playback
 * target. SystemUI owns ZoomStateManager, ZoomPositionInfo, FloatHandleInfo and
 * IZoomUiManager, so the native FLOAT transition must be requested here.
 */
final class SystemUiFloatBridge {
    static final String ACTION_REQUEST_FLOAT =
            "com.yagay.MiniWindowGuard.action.REQUEST_OPLUS_FLOAT_HANDLE";
    static final String EXTRA_TASK_ID = "task_id";
    static final String EXTRA_PACKAGE_NAME = "package_name";

    private static final String TAG = "MiniWindowGuardUI";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String SENDER_PERMISSION =
            "android.permission.MANAGE_ACTIVITY_TASKS";

    private static final int OPLUS_EXTERNAL_STATE_FLOAT = 5;
    private static final long REQUEST_TTL_MS = 5000L;
    private static final long RETRY_MS = 45L;
    private static final int MAX_RETRIES = 50;
    private static final long REINVOKE_MS = 220L;

    private final XposedModule module;

    private volatile Object zoomStateManager;
    private volatile Context context;
    private volatile Handler handler;
    private volatile PendingRequest pending;
    private volatile boolean receiverRegistered;

    SystemUiFloatBridge(XposedModule module) {
        this.module = module;
    }

    void install(ClassLoader loader) {
        if (loader == null) {
            uiDiag("FLOAT_BRIDGE_INSTALL_FAIL", "reason=null-classloader");
            return;
        }

        try {
            Class<?> managerClass =
                    Class.forName(
                            "com.oplus.zoom.zoomstate.ZoomStateManager",
                            false,
                            loader);

            hookConstructors(managerClass);
            hookZoomEnter(managerClass);

            uiDiag(
                    "FLOAT_BRIDGE_INSTALLED",
                    "class=" + managerClass.getName());
        } catch (Throwable t) {
            uiDiag(
                    "FLOAT_BRIDGE_INSTALL_FAIL",
                    "error=" + t.getClass().getSimpleName()
                            + ":" + String.valueOf(t.getMessage()));
        }
    }

    private void hookConstructors(Class<?> managerClass) {
        for (Constructor<?> constructor :
                managerClass.getDeclaredConstructors()) {
            try {
                constructor.setAccessible(true);

                module.hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();

                    Object manager =
                            chain.getThisObject();

                    List<Object> args =
                            chain.getArgs();

                    Context ctx = null;
                    if (!args.isEmpty()
                            && args.get(0) instanceof Context) {
                        ctx = (Context) args.get(0);
                    }

                    onManagerReady(
                            manager,
                            ctx,
                            "constructor");

                    return result;
                });
            } catch (Throwable t) {
                uiDiag(
                        "FLOAT_BRIDGE_CONSTRUCTOR_HOOK_FAIL",
                        "ctor=" + constructor.toGenericString()
                                + " error="
                                + t.getClass().getSimpleName());
            }
        }
    }

    private void hookZoomEnter(Class<?> managerClass) {
        for (Method method :
                managerClass.getDeclaredMethods()) {
            if (!"onZoomEnter".equals(method.getName())
                    || method.getParameterCount() < 1) {
                continue;
            }

            try {
                method.setAccessible(true);

                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();

                    Object manager =
                            chain.getThisObject();

                    if (manager != null) {
                        zoomStateManager = manager;
                    }

                    Object zoomTaskInfo =
                            chain.getArgs().isEmpty()
                                    ? null
                                    : chain.getArgs().get(0);

                    PendingRequest request = pending;

                    if (request != null
                            && zoomTaskMatches(
                            zoomTaskInfo,
                            request)) {
                        uiDiag(
                                "FLOAT_ON_ZOOM_ENTER",
                                "pkg=" + request.packageName
                                        + " taskId="
                                        + request.taskId);

                        scheduleAttempt(
                                request,
                                "onZoomEnter",
                                0L);
                    }

                    return result;
                });
            } catch (Throwable t) {
                uiDiag(
                        "FLOAT_BRIDGE_ZOOM_ENTER_HOOK_FAIL",
                        "method=" + method.toGenericString()
                                + " error="
                                + t.getClass().getSimpleName());
            }
        }
    }

    private void onManagerReady(
            Object manager,
            Context ctx,
            String source
    ) {
        if (manager == null) {
            return;
        }

        zoomStateManager = manager;

        if (ctx != null) {
            Context app =
                    ctx.getApplicationContext();
            context = app != null
                    ? app
                    : ctx;

            if (handler == null) {
                handler =
                        new Handler(
                                Looper.getMainLooper());
            }

            registerReceiverIfNeeded();
        }

        uiDiag(
                "FLOAT_MANAGER_READY",
                "source=" + source
                        + " context="
                        + (context != null));

        PendingRequest request = pending;
        if (request != null) {
            scheduleAttempt(
                    request,
                    "manager-ready",
                    0L);
        }
    }

    private void registerReceiverIfNeeded() {
        if (receiverRegistered
                || context == null) {
            return;
        }

        synchronized (this) {
            if (receiverRegistered
                    || context == null) {
                return;
            }

            BroadcastReceiver receiver =
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(
                                Context receiverContext,
                                Intent intent
                        ) {
                            if (intent == null
                                    || !ACTION_REQUEST_FLOAT
                                    .equals(
                                            intent.getAction())) {
                                return;
                            }

                            int taskId =
                                    intent.getIntExtra(
                                            EXTRA_TASK_ID,
                                            -1);

                            String packageName =
                                    intent.getStringExtra(
                                            EXTRA_PACKAGE_NAME);

                            if (taskId < 0
                                    || packageName == null
                                    || packageName.isBlank()) {
                                uiDiag(
                                        "FLOAT_REQUEST_REJECTED",
                                        "reason=bad-extra taskId="
                                                + taskId
                                                + " pkg="
                                                + packageName);
                                return;
                            }

                            PendingRequest request =
                                    new PendingRequest(
                                            taskId,
                                            packageName);

                            pending = request;

                            uiDiag(
                                    "FLOAT_REQUEST_RECEIVED",
                                    "pkg=" + packageName
                                            + " taskId="
                                            + taskId);

                            scheduleAttempt(
                                    request,
                                    "broadcast",
                                    0L);
                        }
                    };

            IntentFilter filter =
                    new IntentFilter(
                            ACTION_REQUEST_FLOAT);

            context.registerReceiver(
                    receiver,
                    filter,
                    SENDER_PERMISSION,
                    handler,
                    Context.RECEIVER_EXPORTED);

            systemUiReceiver = receiver;
            receiverRegistered = true;

            uiDiag(
                    "FLOAT_RECEIVER_READY",
                    "package=" + SYSTEMUI_PACKAGE);
        }
    }

    private void scheduleAttempt(
            PendingRequest request,
            String source,
            long delayMs
    ) {
        Handler currentHandler = handler;

        if (currentHandler == null
                || request == null) {
            return;
        }

        currentHandler.postDelayed(
                () -> attemptFloat(
                        request,
                        source),
                delayMs);
    }

    private void attemptFloat(
            PendingRequest request,
            String source
    ) {
        if (request == null
                || pending != request) {
            return;
        }

        long now =
                SystemClock.elapsedRealtime();

        if (now - request.createdAt
                > REQUEST_TTL_MS) {
            if (pending == request) {
                pending = null;
            }

            uiDiag(
                    "FLOAT_REQUEST_TIMEOUT",
                    "pkg=" + request.packageName
                            + " taskId="
                            + request.taskId
                            + " attempts="
                            + request.attempts);
            return;
        }

        Object manager =
                zoomStateManager;

        if (manager == null) {
            retry(
                    request,
                    "manager-null");
            return;
        }

        Object zoomTaskInfo =
                invokeNoArg(
                        manager,
                        "getZoomTaskInfo");

        if (!zoomTaskMatches(
                zoomTaskInfo,
                request)) {
            retry(
                    request,
                    "zoom-task-not-ready");
            return;
        }

        if (booleanNoArg(
                manager,
                "isExiting")) {
            retry(
                    request,
                    "zoom-exiting");
            return;
        }

        Object uiManager =
                invokeNoArg(
                        manager,
                        "getUiManager");

        Object existingInfo =
                invoke(
                        uiManager,
                        "getFloatHandleInfo",
                        request.taskId);

        if (existingInfo != null) {
            pending = null;

            uiDiag(
                    "FLOAT_HANDLE_READY",
                    "pkg=" + request.packageName
                            + " taskId="
                            + request.taskId
                            + " source="
                            + source
                            + " attempts="
                            + request.attempts);
            return;
        }

        if (now - request.lastInvokeAt
                >= REINVOKE_MS) {
            Method change =
                    findMethod(
                            manager.getClass(),
                            "requestChangeZoomStateFromOutside",
                            int.class,
                            boolean.class,
                            boolean.class);

            if (change == null) {
                pending = null;

                uiDiag(
                        "FLOAT_REQUEST_FAIL",
                        "pkg=" + request.packageName
                                + " taskId="
                                + request.taskId
                                + " reason=method-missing");
                return;
            }

            try {
                change.setAccessible(true);
                change.invoke(
                        manager,
                        OPLUS_EXTERNAL_STATE_FLOAT,
                        true,
                        true);

                request.lastInvokeAt = now;
                request.invokeCount++;

                uiDiag(
                        "FLOAT_STATE_REQUEST",
                        "pkg=" + request.packageName
                                + " taskId="
                                + request.taskId
                                + " state="
                                + OPLUS_EXTERNAL_STATE_FLOAT
                                + " animate=true"
                                + " requestTaskChange=true"
                                + " invoke="
                                + request.invokeCount
                                + " source="
                                + source);
            } catch (Throwable t) {
                uiDiag(
                        "FLOAT_REQUEST_INVOKE_FAIL",
                        "pkg=" + request.packageName
                                + " taskId="
                                + request.taskId
                                + " error="
                                + t.getClass()
                                .getSimpleName()
                                + ":"
                                + String.valueOf(
                                t.getMessage()));
            }
        }

        retry(
                request,
                "wait-float-handle");
    }

    private void retry(
            PendingRequest request,
            String reason
    ) {
        if (request == null
                || pending != request) {
            return;
        }

        request.attempts++;

        if (request.attempts
                > MAX_RETRIES) {
            pending = null;

            uiDiag(
                    "FLOAT_REQUEST_TIMEOUT",
                    "pkg=" + request.packageName
                            + " taskId="
                            + request.taskId
                            + " reason="
                            + reason
                            + " invokes="
                            + request.invokeCount);
            return;
        }

        if (request.attempts == 1
                || request.attempts % 10 == 0) {
            uiDiag(
                    "FLOAT_REQUEST_WAIT",
                    "pkg=" + request.packageName
                            + " taskId="
                            + request.taskId
                            + " reason="
                            + reason
                            + " attempt="
                            + request.attempts);
        }

        scheduleAttempt(
                request,
                reason,
                RETRY_MS);
    }

    private static boolean zoomTaskMatches(
            Object zoomTaskInfo,
            PendingRequest request
    ) {
        if (zoomTaskInfo == null
                || request == null) {
            return false;
        }

        int taskId =
                intField(
                        zoomTaskInfo,
                        "taskId",
                        -1);

        String pkg =
                stringField(
                        zoomTaskInfo,
                        "pkgName");

        return taskId == request.taskId
                && (pkg == null
                || pkg.isBlank()
                || request.packageName.equals(
                pkg));
    }

    private static boolean booleanNoArg(
            Object receiver,
            String name
    ) {
        Object value =
                invokeNoArg(
                        receiver,
                        name);

        return value instanceof Boolean
                && (Boolean) value;
    }

    private static Object invokeNoArg(
            Object receiver,
            String name
    ) {
        return invoke(
                receiver,
                name);
    }

    private static Object invoke(
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

    private static Method findMethod(
            Class<?> type,
            String name,
            Class<?>... parameterTypes
    ) {
        Class<?> current = type;

        while (current != null) {
            try {
                return current.getDeclaredMethod(
                        name,
                        parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current =
                        current.getSuperclass();
            }
        }

        return null;
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

    private static String stringField(
            Object receiver,
            String name
    ) {
        Object value =
                fieldValue(
                        receiver,
                        name);

        return value instanceof String
                ? (String) value
                : null;
    }

    private static Object fieldValue(
            Object receiver,
            String name
    ) {
        if (receiver == null) {
            return null;
        }

        Class<?> current =
                receiver.getClass();

        while (current != null) {
            try {
                Field field =
                        current.getDeclaredField(
                                name);
                field.setAccessible(true);
                return field.get(
                        receiver);
            } catch (NoSuchFieldException ignored) {
                current =
                        current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }

        return null;
    }

    private void uiDiag(
            String event,
            String detail
    ) {
        String message =
                "DIAG_UI event="
                        + event
                        + " detail="
                        + detail;

        Log.i(
                TAG,
                message);

        try {
            module.log(
                    Log.INFO,
                    TAG,
                    message);
        } catch (Throwable ignored) {
        }
    }

    private static final class PendingRequest {
        final int taskId;
        final String packageName;
        final long createdAt =
                SystemClock.elapsedRealtime();

        int attempts;
        int invokeCount;
        long lastInvokeAt;

        PendingRequest(
                int taskId,
                String packageName
        ) {
            this.taskId = taskId;
            this.packageName = packageName;
        }
    }
}
