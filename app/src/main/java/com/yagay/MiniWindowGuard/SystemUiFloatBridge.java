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
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * Launcher-side bridge to OPlus Shell IZoom.
 *
 * The system_server side only selects the target task. Launcher owns
 * ZoomController/IZoom, so the actual FloatHandle transition is delegated to
 * the same public shell surface used by OPlus:
 *
 *   ZoomController.asZoom()
 *     -> IZoom.requestChangeZoomState(taskId, 5)
 *
 * State 5 is FLOAT/FloatHandle. ZoomStateManager.onZoomEnter is used only as a
 * readiness signal so we never depend on its private implementation.
 */
final class SystemUiFloatBridge {
    static final String ACTION_REQUEST_FLOAT =
            "com.yagay.MiniWindowGuard.action.REQUEST_OPLUS_FLOAT_HANDLE";
    static final String EXTRA_TASK_ID = "task_id";
    static final String EXTRA_PACKAGE_NAME = "package_name";
    static final String EXTRA_ALLOW_IMMEDIATE = "allow_immediate";

    private static final String TAG = "MiniWindowGuardUI";
    private static final String LAUNCHER_PACKAGE =
            "com.android.launcher";
    private static final String SENDER_PERMISSION =
            "android.permission.MANAGE_ACTIVITY_TASKS";

    private static final int OPLUS_ZOOM_STATE_FLOAT = 5;
    private static final long REQUEST_TTL_MS = 5000L;
    private static final long RETRY_MS = 55L;
    private static final int MAX_RETRIES = 60;
    private static final long REINVOKE_MS = 240L;

    private final XposedModule module;

    private volatile Object zoomController;
    private volatile Object iZoom;
    private volatile Context context;
    private volatile Handler handler;
    private volatile BroadcastReceiver receiver;
    private volatile PendingRequest pending;
    private volatile boolean receiverRegistered;

    SystemUiFloatBridge(XposedModule module) {
        this.module = module;
    }

    void install(ClassLoader loader) {
        if (loader == null) {
            uiDiag(
                    "FLOAT_BRIDGE_INSTALL_FAIL",
                    "reason=null-classloader");
            return;
        }

        boolean contextHooked =
                hookLauncherContext();

        boolean controllerHooked =
                hookZoomController(loader);

        boolean zoomEnterHooked =
                hookZoomEnter(loader);

        ensureContext();

        uiDiag(
                "FLOAT_BRIDGE_INSTALLED",
                "contextHooked="
                        + contextHooked
                        + " controllerHooked="
                        + controllerHooked
                        + " zoomEnterHooked="
                        + zoomEnterHooked);
    }

    private boolean hookLauncherContext() {
        try {
            Method attach =
                    android.app.Application.class
                            .getDeclaredMethod(
                                    "attach",
                                    Context.class);

            attach.setAccessible(true);

            module.hook(attach)
                    .intercept(chain -> {
                        Object result =
                                chain.proceed();

                        List<Object> args =
                                chain.getArgs();

                        if (!args.isEmpty()
                                && args.get(0)
                                instanceof Context) {
                            setContext(
                                    (Context) args.get(0),
                                    "Application.attach");
                        }

                        return result;
                    });

            uiDiag(
                    "FLOAT_CONTEXT_HOOKED",
                    "method=Application.attach");

            return true;
        } catch (Throwable t) {
            uiDiag(
                    "FLOAT_CONTEXT_HOOK_FAIL",
                    "error="
                            + t.getClass()
                            .getSimpleName()
                            + ":"
                            + String.valueOf(
                            t.getMessage()));
            return false;
        }
    }

    private boolean hookZoomController(
            ClassLoader loader
    ) {
        try {
            Class<?> controllerClass =
                    Class.forName(
                            "com.oplus.zoom.ZoomController",
                            false,
                            loader);

            int hooked = 0;

            for (Constructor<?> constructor :
                    controllerClass.getDeclaredConstructors()) {
                try {
                    constructor.setAccessible(true);

                    module.hook(constructor)
                            .intercept(chain -> {
                                Object result =
                                        chain.proceed();

                                Object controller =
                                        chain.getThisObject();

                                captureZoomController(
                                        controller,
                                        chain.getArgs(),
                                        "constructor");

                                return result;
                            });

                    hooked++;
                } catch (Throwable t) {
                    uiDiag(
                            "FLOAT_CONTROLLER_CTOR_HOOK_FAIL",
                            "ctor="
                                    + constructor
                                    .toGenericString()
                                    + " error="
                                    + t.getClass()
                                    .getSimpleName());
                }
            }

            for (Method method :
                    controllerClass.getDeclaredMethods()) {
                if (!"asZoom".equals(
                        method.getName())
                        || method.getParameterCount()
                        != 0) {
                    continue;
                }

                try {
                    method.setAccessible(true);

                    module.hook(method)
                            .intercept(chain -> {
                                Object value =
                                        chain.proceed();

                                if (value != null) {
                                    zoomController =
                                            chain.getThisObject();
                                    iZoom = value;

                                    ensureContext();

                                    uiDiag(
                                            "FLOAT_IZOOM_READY",
                                            "source=asZoom"
                                                    + " class="
                                                    + value
                                                    .getClass()
                                                    .getName());

                                    PendingRequest request =
                                            pending;

                                    if (request != null) {
                                        scheduleAttempt(
                                                request,
                                                "asZoom",
                                                0L);
                                    }
                                }

                                return value;
                            });

                    hooked++;
                } catch (Throwable t) {
                    uiDiag(
                            "FLOAT_ASZOOM_HOOK_FAIL",
                            "error="
                                    + t.getClass()
                                    .getSimpleName());
                }
            }

            uiDiag(
                    "FLOAT_CONTROLLER_HOOKED",
                    "class="
                            + controllerClass.getName()
                            + " hooks="
                            + hooked);

            return hooked > 0;
        } catch (Throwable t) {
            uiDiag(
                    "FLOAT_CONTROLLER_HOOK_FAIL",
                    "error="
                            + t.getClass()
                            .getSimpleName()
                            + ":"
                            + String.valueOf(
                            t.getMessage()));
            return false;
        }
    }

    private void captureZoomController(
            Object controller,
            List<Object> args,
            String source
    ) {
        if (controller == null) {
            return;
        }

        zoomController = controller;

        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Context) {
                    setContext(
                            (Context) arg,
                            source + "-arg");
                    break;
                }
            }
        }

        ensureContext();

        Object value =
                invokeNoArg(
                        controller,
                        "asZoom");

        if (value != null) {
            iZoom = value;

            uiDiag(
                    "FLOAT_CONTROLLER_READY",
                    "source=" + source
                            + " controller="
                            + controller.getClass()
                            .getName()
                            + " iZoom="
                            + value.getClass()
                            .getName());
        } else {
            uiDiag(
                    "FLOAT_CONTROLLER_READY",
                    "source=" + source
                            + " controller="
                            + controller.getClass()
                            .getName()
                            + " iZoom=null");
        }

        PendingRequest request =
                pending;

        if (request != null) {
            scheduleAttempt(
                    request,
                    "controller-ready",
                    0L);
        }
    }

    private boolean hookZoomEnter(
            ClassLoader loader
    ) {
        try {
            Class<?> managerClass =
                    Class.forName(
                            "com.oplus.zoom.zoomstate.ZoomStateManager",
                            false,
                            loader);

            int hooked = 0;

            for (Method method :
                    managerClass.getDeclaredMethods()) {
                if (!"onZoomEnter".equals(
                        method.getName())
                        || method.getParameterCount()
                        < 1) {
                    continue;
                }

                method.setAccessible(true);

                module.hook(method)
                        .intercept(chain -> {
                            Object result =
                                    chain.proceed();

                            PendingRequest request =
                                    pending;

                            if (request == null) {
                                return result;
                            }

                            Object taskInfo =
                                    chain.getArgs()
                                            .isEmpty()
                                            ? null
                                            : chain.getArgs()
                                            .get(0);

                            int id =
                                    taskIdFromObject(
                                            taskInfo);

                            if (id == request.taskId) {
                                request.zoomReady = true;

                                uiDiag(
                                        "FLOAT_ON_ZOOM_ENTER",
                                        "pkg="
                                                + request.packageName
                                                + " taskId="
                                                + request.taskId);

                                scheduleAttempt(
                                        request,
                                        "onZoomEnter",
                                        0L);
                            }

                            return result;
                        });

                hooked++;
            }

            uiDiag(
                    "FLOAT_ZOOM_ENTER_HOOKED",
                    "hooks=" + hooked);

            return hooked > 0;
        } catch (Throwable t) {
            uiDiag(
                    "FLOAT_ZOOM_ENTER_HOOK_FAIL",
                    "error="
                            + t.getClass()
                            .getSimpleName()
                            + ":"
                            + String.valueOf(
                            t.getMessage()));
            return false;
        }
    }

    private void ensureContext() {
        if (context != null) {
            registerReceiverIfNeeded();
            return;
        }

        try {
            Class<?> activityThread =
                    Class.forName(
                            "android.app.ActivityThread");

            Method currentApplication =
                    activityThread.getDeclaredMethod(
                            "currentApplication");

            currentApplication.setAccessible(true);

            Object app =
                    currentApplication.invoke(
                            null);

            if (app instanceof Context) {
                setContext(
                        (Context) app,
                        "ActivityThread.currentApplication");
            }
        } catch (Throwable ignored) {
        }
    }

    private void setContext(
            Context value,
            String source
    ) {
        if (value == null) {
            return;
        }

        Context app =
                value.getApplicationContext();

        context =
                app != null
                        ? app
                        : value;

        if (handler == null) {
            handler =
                    new Handler(
                            Looper.getMainLooper());
        }

        registerReceiverIfNeeded();

        uiDiag(
                "FLOAT_LAUNCHER_READY",
                "source=" + source
                        + " package="
                        + context.getPackageName());
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

            BroadcastReceiver created =
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
                                        "taskId="
                                                + taskId
                                                + " pkg="
                                                + packageName);
                                return;
                            }

                            PendingRequest request =
                                    new PendingRequest(
                                            taskId,
                                            packageName,
                                            intent.getBooleanExtra(
                                                    EXTRA_ALLOW_IMMEDIATE,
                                                    false));

                            pending = request;

                            uiDiag(
                                    "FLOAT_REQUEST_RECEIVED",
                                    "pkg="
                                            + packageName
                                            + " taskId="
                                            + taskId);

                            scheduleAttempt(
                                    request,
                                    request.allowImmediate
                                            ? "broadcast-immediate"
                                            : "broadcast-wait",
                                    0L);
                        }
                    };

            IntentFilter filter =
                    new IntentFilter(
                            ACTION_REQUEST_FLOAT);

            context.registerReceiver(
                    created,
                    filter,
                    SENDER_PERMISSION,
                    handler,
                    Context.RECEIVER_EXPORTED);

            receiver = created;
            receiverRegistered = true;

            uiDiag(
                    "FLOAT_RECEIVER_READY",
                    "package="
                            + LAUNCHER_PACKAGE);
        }
    }

    private void scheduleAttempt(
            PendingRequest request,
            String source,
            long delayMs
    ) {
        Handler current =
                handler;

        if (current == null
                || request == null) {
            ensureContext();
            current = handler;
        }

        if (current == null) {
            return;
        }

        Handler target = current;

        target.postDelayed(
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
            pending = null;

            uiDiag(
                    "FLOAT_REQUEST_TIMEOUT",
                    "pkg="
                            + request.packageName
                            + " taskId="
                            + request.taskId
                            + " invokes="
                            + request.invokeCount
                            + " attempts="
                            + request.attempts);
            return;
        }

        if (!request.allowImmediate
                && !request.zoomReady) {
            retry(
                    request,
                    "wait-zoom-enter");
            return;
        }

        Object surface =
                iZoom;

        if (surface == null) {
            Object controller =
                    zoomController;

            if (controller != null) {
                surface =
                        invokeNoArg(
                                controller,
                                "asZoom");

                if (surface != null) {
                    iZoom = surface;
                }
            }
        }

        if (surface == null) {
            retry(
                    request,
                    "iZoom-null");
            return;
        }

        if (now - request.lastInvokeAt
                >= REINVOKE_MS) {
            Method change =
                    findMethod(
                            surface.getClass(),
                            "requestChangeZoomState",
                            int.class,
                            int.class);

            if (change == null) {
                pending = null;

                uiDiag(
                        "FLOAT_REQUEST_FAIL",
                        "pkg="
                                + request.packageName
                                + " taskId="
                                + request.taskId
                                + " reason=requestChangeZoomState-missing"
                                + " iZoomClass="
                                + surface.getClass()
                                .getName());
                return;
            }

            try {
                change.setAccessible(true);

                change.invoke(
                        surface,
                        request.taskId,
                        OPLUS_ZOOM_STATE_FLOAT);

                request.lastInvokeAt = now;
                request.invokeCount++;

                uiDiag(
                        "FLOAT_IZOOM_REQUEST",
                        "pkg="
                                + request.packageName
                                + " taskId="
                                + request.taskId
                                + " state="
                                + OPLUS_ZOOM_STATE_FLOAT
                                + " invoke="
                                + request.invokeCount
                                + " source="
                                + source);
            } catch (Throwable t) {
                uiDiag(
                        "FLOAT_REQUEST_INVOKE_FAIL",
                        "pkg="
                                + request.packageName
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
                "wait-native-float");
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
                    "pkg="
                            + request.packageName
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
                    "pkg="
                            + request.packageName
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

    private static int taskIdFromObject(
            Object value
    ) {
        if (value == null) {
            return -1;
        }

        Object result =
                fieldValue(
                        value,
                        "taskId");

        if (result instanceof Number) {
            return ((Number) result)
                    .intValue();
        }

        result =
                invokeNoArg(
                        value,
                        "getTaskId");

        if (result instanceof Number) {
            return ((Number) result)
                    .intValue();
        }

        return -1;
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
                java.lang.reflect.Field field =
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

    private static Object invokeNoArg(
            Object receiver,
            String name
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

    private static Method findMethod(
            Class<?> type,
            String name,
            Class<?>... parameterTypes
    ) {
        Class<?> current =
                type;

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
        final boolean allowImmediate;

        volatile boolean zoomReady;
        int attempts;
        int invokeCount;
        long lastInvokeAt;

        PendingRequest(
                int taskId,
                String packageName,
                boolean allowImmediate
        ) {
            this.taskId = taskId;
            this.packageName = packageName;
            this.allowImmediate = allowImmediate;
        }
    }
}
