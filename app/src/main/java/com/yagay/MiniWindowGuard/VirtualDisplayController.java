package com.yagay.MiniWindowGuard;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MiniWindowGuard's own VirtualDisplay window engine.
 *
 * Public projects were used only to understand Android architecture. This
 * implementation is written for MiniWindowGuard and does not reuse their
 * window implementation source.
 */
final class VirtualDisplayController {
    interface Logger {
        void log(String event, String detail);
    }

    private static final long COMMAND_POLL_MS = 180L;

    private static final int VD_FLAG_SECURE = 1 << 2;
    private static final int VD_FLAG_OWN_CONTENT_ONLY = 1 << 3;
    private static final int VD_FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int VD_FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    private static final int VD_FLAG_TRUSTED = 1 << 10;
    private static final int VD_FLAG_OWN_FOCUS = 1 << 14;
    private static final int VD_FLAG_STEAL_TOP_FOCUS_DISABLED = 1 << 16;

    private static final int VIRTUAL_DISPLAY_FLAGS =
            VD_FLAG_SECURE
                    | VD_FLAG_OWN_CONTENT_ONLY
                    | VD_FLAG_SUPPORTS_TOUCH
                    | VD_FLAG_ROTATES_WITH_CONTENT
                    | VD_FLAG_TRUSTED
                    | VD_FLAG_OWN_FOCUS
                    | VD_FLAG_STEAL_TOP_FOCUS_DISABLED;

    private final Handler handler;
    private final Logger logger;
    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    private volatile Context systemUiContext;
    private volatile Object activityTaskManager;
    private volatile Object inputManager;

    private volatile int lastCommandSeq = Integer.MIN_VALUE;
    private volatile String pendingPackage = "";
    private volatile int pendingState = ConfigKeys.STATE_WINDOW;
    private volatile boolean running;

    VirtualDisplayController(
            Handler handler,
            Context systemUiContext,
            Logger logger
    ) {
        this.handler = handler;
        this.systemUiContext = systemUiContext;
        this.logger = logger;
    }

    void start() {
        if (running) return;
        running = true;
        handler.removeCallbacks(commandPoll);
        handler.post(commandPoll);
    }

    void shutdown() {
        running = false;
        handler.removeCallbacks(commandPoll);

        Session[] snapshot =
                sessions.values().toArray(new Session[0]);
        for (Session session : snapshot) {
            try {
                closeSession(session, true, "engine-reload");
            } catch (Throwable t) {
                log("VD_SHUTDOWN_ERROR",
                        "pkg=" + session.packageName
                                + " taskId=" + session.taskId
                                + " error=" + t);
            }
        }

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
                || isManagedPackage(packageName));
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
        if (processName == null || processName.isBlank()) return null;

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

    boolean isManagedActivityRecord(Object activityRecord) {
        return isManagedPackage(activityPackage(activityRecord));
    }

    boolean isManagedTask(Object task) {
        int id = taskId(task);
        Session session = id < 0 ? null : sessions.get(id);
        return session != null && session.active;
    }

    boolean isManagedTopActivityRecord(Object activityRecord) {
        if (!isManagedActivityRecord(activityRecord)) return false;

        Object task = activityTask(activityRecord);
        if (task == null) return false;

        Object top = invokeNoArg(task, "topRunningActivity");
        if (top == null) {
            top = invokeNoArg(task, "getTopResumedActivity");
        }
        return top == null || top == activityRecord;
    }

    int stateForPackage(String packageName) {
        Session session = latestSession(packageName);
        return session == null || !session.active
                ? ConfigKeys.STATE_RELEASED
                : session.state;
    }

    void capture(Object activityRecord, String packageName) {
        if (activityRecord == null
                || packageName == null
                || !GuardConfig.bool(ConfigKeys.AUTO_CONTAINER)
                || !wantsPackage(packageName)) {
            return;
        }

        Object task = activityTask(activityRecord);
        if (task == null) {
            log("VD_CAPTURE_SKIP",
                    "pkg=" + packageName + " reason=no-task");
            return;
        }

        if (systemUiContext == null) {
            systemUiContext = deriveSystemContext(task);
        }

        int id = taskId(task);
        if (id < 0) {
            log("VD_CAPTURE_SKIP",
                    "pkg=" + packageName + " reason=no-task-id");
            return;
        }

        Session existing = sessions.get(id);
        if (existing != null && existing.active) {
            existing.lastSeenElapsed = SystemClock.elapsedRealtime();
            return;
        }

        int initialState = packageName.equals(pendingPackage)
                ? pendingState
                : ConfigKeys.STATE_WINDOW;

        Session session = new Session(
                id,
                packageName,
                task,
                taskDisplayId(task),
                initialState);

        sessions.put(id, session);

        if (packageName.equals(pendingPackage)) {
            pendingPackage = "";
        }

        log("VD_TASK_CAPTURED",
                "pkg=" + packageName
                        + " taskId=" + id
                        + " originalDisplay=" + session.originalDisplayId
                        + " state=" + initialState);

        handler.post(() -> openSession(session));
    }

    void releasePackage(String packageName) {
        Session session = latestSession(packageName);
        if (session != null) {
            handler.post(() ->
                    closeSession(session, true, "release-package"));
        }
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
                        Session session = latestSession(pkg);

                        if (state == ConfigKeys.STATE_RELEASED) {
                            if (session != null) {
                                closeSession(
                                        session,
                                        true,
                                        "remote-command");
                            }
                        } else if (session != null) {
                            applyState(session, state, "remote-command");
                        } else {
                            pendingPackage = pkg;
                            pendingState = state;
                            log("VD_COMMAND_PENDING",
                                    "seq=" + seq
                                            + " pkg=" + pkg
                                            + " state=" + state);
                        }
                    }
                }
            } catch (Throwable t) {
                log("VD_COMMAND_ERROR", String.valueOf(t));
            } finally {
                if (running) {
                    handler.postDelayed(this, COMMAND_POLL_MS);
                }
            }
        }
    };

    private void openSession(Session session) {
        if (session == null || !session.active) return;

        try {
            WindowHost host = new WindowHost(session);
            session.host = host;

            if (!host.create()) {
                sessions.remove(session.taskId, session);
                session.active = false;
                log("VD_WINDOW_CREATE_FAIL",
                        "pkg=" + session.packageName
                                + " taskId=" + session.taskId);
            }
        } catch (Throwable t) {
            sessions.remove(session.taskId, session);
            session.active = false;
            log("VD_WINDOW_CREATE_ERROR",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " error=" + t);
        }
    }

    private void applyState(
            Session session,
            int state,
            String reason
    ) {
        if (session == null || !session.active) return;

        int safeState = ConfigKeys.sanitizeState(state);
        session.state = safeState;
        session.lastSeenElapsed = SystemClock.elapsedRealtime();

        if (safeState == ConfigKeys.STATE_RELEASED) {
            closeSession(session, true, reason);
            return;
        }

        if (session.host != null) {
            session.host.applyState(safeState);
        }

        log("VD_STATE",
                "pkg=" + session.packageName
                        + " taskId=" + session.taskId
                        + " state=" + safeState
                        + " reason=" + reason);
    }

    private void closeSession(
            Session session,
            boolean restoreTask,
            String reason
    ) {
        if (session == null || !session.active) return;

        session.active = false;
        session.state = ConfigKeys.STATE_RELEASED;

        try {
            if (session.host != null) {
                session.host.destroy(restoreTask);
            } else if (restoreTask) {
                moveTaskToDisplay(
                        session.taskId,
                        Math.max(0, session.originalDisplayId));
            }
        } finally {
            sessions.remove(session.taskId, session);
            log("VD_TASK_RELEASED",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " reason=" + reason);
        }
    }

    private Session latestSession(String packageName) {
        if (packageName == null) return null;

        Session latest = null;
        for (Session session : sessions.values()) {
            if (!session.active
                    || !packageName.equals(session.packageName)) {
                continue;
            }

            if (latest == null
                    || session.lastSeenElapsed > latest.lastSeenElapsed) {
                latest = session;
            }
        }
        return latest;
    }

    private final class WindowHost
            implements TextureView.SurfaceTextureListener {

        private final Session session;

        private Context context;
        private WindowManager windowManager;
        private DisplayManager displayManager;

        private VirtualDisplay virtualDisplay;
        private Surface renderSurface;
        private int displayId = -1;
        private int densityDpi;

        private LinearLayout root;
        private LinearLayout titleBar;
        private TextureView textureView;
        private View resizeHandle;
        private View restoreControl;

        private WindowManager.LayoutParams windowParams;
        private WindowManager.LayoutParams restoreParams;

        private int contentWidth;
        private int contentHeight;
        private int titleHeight;

        private int savedWindowX;
        private int savedWindowY;

        private float dragStartRawX;
        private float dragStartRawY;
        private int dragStartWindowX;
        private int dragStartWindowY;

        private float resizeStartRawX;
        private float resizeStartRawY;
        private int resizeStartWidth;
        private int resizeStartHeight;

        private boolean taskMoved;
        private boolean surfaceReady;
        private boolean destroyed;

        WindowHost(Session session) {
            this.session = session;
        }

        boolean create() {
            try {
                context = systemUiContext;
                if (context == null) {
                    log("VD_CREATE_ERROR",
                            "pkg=" + session.packageName
                                    + " reason=no-system-ui-context");
                    return false;
                }

                windowManager =
                        context.getSystemService(WindowManager.class);
                displayManager =
                        context.getSystemService(DisplayManager.class);

                if (windowManager == null
                        || displayManager == null) {
                    log("VD_CREATE_ERROR",
                            "pkg=" + session.packageName
                                    + " reason=missing-system-service");
                    return false;
                }

                DisplayMetrics metrics =
                        context.getResources().getDisplayMetrics();

                densityDpi = Math.max(160, metrics.densityDpi);
                titleHeight = dp(42);

                contentWidth = clamp(
                        metrics.widthPixels
                                * GuardConfig.containerWidth()
                                / 100,
                        dp(260),
                        Math.max(dp(260),
                                metrics.widthPixels - dp(20)));

                contentHeight = clamp(
                        metrics.heightPixels
                                * GuardConfig.containerHeight()
                                / 100,
                        dp(320),
                        Math.max(dp(320),
                                metrics.heightPixels - dp(110)));

                virtualDisplay =
                        displayManager.createVirtualDisplay(
                                "MiniWindowGuard-" + session.taskId,
                                contentWidth,
                                contentHeight,
                                densityDpi,
                                null,
                                VIRTUAL_DISPLAY_FLAGS);

                if (virtualDisplay == null
                        || virtualDisplay.getDisplay() == null) {
                    log("VD_CREATE_ERROR",
                            "pkg=" + session.packageName
                                    + " reason=virtual-display-null");
                    return false;
                }

                displayId =
                        virtualDisplay.getDisplay().getDisplayId();

                buildWindow();

                log("VD_WINDOW_CREATED",
                        "pkg=" + session.packageName
                                + " taskId=" + session.taskId
                                + " displayId=" + displayId
                                + " size=" + contentWidth
                                + "x" + contentHeight
                                + " density=" + densityDpi
                                + " flags=" + VIRTUAL_DISPLAY_FLAGS
                                + " renderer=TextureView"
                                + " surfaceReady=false");

                return true;
            } catch (Throwable t) {
                log("VD_CREATE_ERROR",
                        "pkg=" + session.packageName
                                + " error=" + t);
                destroy(false);
                return false;
            }
        }

        private void buildWindow() {
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xFF000000);

            titleBar = new LinearLayout(context);
            titleBar.setOrientation(LinearLayout.HORIZONTAL);
            titleBar.setGravity(Gravity.CENTER_VERTICAL);
            titleBar.setPadding(dp(3), 0, dp(3), 0);

            GradientDrawable titleBackground =
                    new GradientDrawable();
            titleBackground.setColor(0xEE202328);
            titleBackground.setCornerRadius(dp(9));
            titleBar.setBackground(titleBackground);

            Button back = toolbarButton("‹");
            back.setTextSize(26);
            back.setOnClickListener(v -> injectBack());

            TextView title = new TextView(context);
            title.setText(session.packageName);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(12f);
            title.setSingleLine(true);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setPadding(dp(5), 0, dp(5), 0);
            title.setOnTouchListener(
                    (v, event) -> handleWindowDrag(event));

            Button minimize = toolbarButton("●");
            minimize.setTextSize(15);
            minimize.setOnClickListener(v ->
                    VirtualDisplayController.this.applyState(
                            session,
                            ConfigKeys.STATE_ICON,
                            "minimize-button"));

            Button hide = toolbarButton("隐");
            hide.setTextSize(13);
            hide.setOnClickListener(v ->
                    VirtualDisplayController.this.applyState(
                            session,
                            ConfigKeys.STATE_HIDDEN,
                            "hide-button"));

            Button close = toolbarButton("×");
            close.setTextSize(23);
            close.setOnClickListener(v ->
                    handler.post(() ->
                            closeSession(
                                    session,
                                    true,
                                    "close-button")));

            titleBar.addView(
                    back,
                    new LinearLayout.LayoutParams(
                            titleHeight,
                            titleHeight));

            titleBar.addView(
                    title,
                    new LinearLayout.LayoutParams(
                            0,
                            titleHeight,
                            1f));

            titleBar.addView(
                    minimize,
                    new LinearLayout.LayoutParams(
                            titleHeight,
                            titleHeight));

            titleBar.addView(
                    hide,
                    new LinearLayout.LayoutParams(
                            titleHeight,
                            titleHeight));

            titleBar.addView(
                    close,
                    new LinearLayout.LayoutParams(
                            titleHeight,
                            titleHeight));

            FrameLayout content =
                    new FrameLayout(context);

            textureView =
                    new TextureView(context);
            textureView.setOpaque(true);
            textureView.setSurfaceTextureListener(this);
            textureView.setOnTouchListener(
                    (v, event) -> {
                        if (event.getActionMasked()
                                == MotionEvent.ACTION_DOWN) {
                            focusRemoteTask("touch-down");
                        }
                        return forwardMotionEvent(event);
                    });

            textureView.setOnGenericMotionListener(
                    (v, event) ->
                            forwardMotionEvent(event));

            FrameLayout.LayoutParams textureParams =
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
            content.addView(textureView, textureParams);

            TextView resize =
                    new TextView(context);
            resize.setText("↘");
            resize.setTextSize(18);
            resize.setTextColor(0xFFFFFFFF);
            resize.setGravity(Gravity.CENTER);
            resize.setBackgroundColor(0x99000000);
            resize.setOnTouchListener(
                    (v, event) -> handleResize(event));
            resizeHandle = resize;

            FrameLayout.LayoutParams resizeParams =
                    new FrameLayout.LayoutParams(
                            dp(42),
                            dp(42),
                            Gravity.END | Gravity.BOTTOM);
            content.addView(resize, resizeParams);

            root.addView(
                    titleBar,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            titleHeight));

            root.addView(
                    content,
                    new LinearLayout.LayoutParams(
                            contentWidth,
                            contentHeight));

            windowParams =
                    new WindowManager.LayoutParams(
                            contentWidth,
                            contentHeight + titleHeight,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                            PixelFormat.TRANSLUCENT);

            windowParams.gravity =
                    Gravity.TOP | Gravity.START;
            windowParams.x = dp(12);
            windowParams.y = dp(72);

            savedWindowX = windowParams.x;
            savedWindowY = windowParams.y;

            windowManager.addView(root, windowParams);
        }

        private Button toolbarButton(String text) {
            Button button = new Button(context);
            button.setText(text);
            button.setTextColor(0xFFFFFFFF);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(0, 0, 0, 0);
            button.setBackgroundColor(0x00000000);
            return button;
        }

        void applyState(int state) {
            if (destroyed) return;

            if (state == ConfigKeys.STATE_WINDOW) {
                restoreWindow();
            } else if (state == ConfigKeys.STATE_ICON) {
                parkWindow(false);
            } else if (state == ConfigKeys.STATE_HIDDEN) {
                parkWindow(true);
            }
        }

        private void restoreWindow() {
            removeRestoreControl();

            windowParams.flags &=
                    ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

            windowParams.x = savedWindowX;
            windowParams.y = savedWindowY;
            clampWindowPosition();
            updateHostLayout();

            focusRemoteTask("restore");

            log("VD_RESTORE",
                    "pkg=" + session.packageName
                            + " displayId=" + displayId);
        }

        private void parkWindow(boolean hidden) {
            if (windowParams == null) return;

            DisplayMetrics metrics =
                    context.getResources().getDisplayMetrics();

            if (windowParams.x >= 0
                    && windowParams.x < metrics.widthPixels
                    && windowParams.y >= 0
                    && windowParams.y < metrics.heightPixels) {
                savedWindowX = windowParams.x;
                savedWindowY = windowParams.y;
            }

            windowParams.flags |=
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

            // Keep the TextureView attached and its SurfaceTexture alive, but move
            // the host completely outside display 0 so it cannot occlude or eat
            // touches from the foreground app.
            windowParams.x =
                    metrics.widthPixels
                            + contentWidth
                            + dp(80);
            windowParams.y = 0;
            updateHostLayout();

            showRestoreControl(hidden);

            log(hidden
                            ? "VD_HIDDEN"
                            : "VD_MINIMIZED",
                    "pkg=" + session.packageName
                            + " displayId=" + displayId
                            + " surfaceReady=" + surfaceReady);
        }

        private void showRestoreControl(boolean hidden) {
            removeRestoreControl();

            Button control = new Button(context);
            control.setAllCaps(false);
            control.setText(hidden ? "›" : "●");
            control.setTextSize(hidden ? 18 : 16);
            control.setTextColor(0xFFFFFFFF);
            control.setMinWidth(0);
            control.setMinimumWidth(0);
            control.setPadding(0, 0, 0, 0);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEE202328);
            bg.setCornerRadius(dp(hidden ? 8 : 28));
            control.setBackground(bg);

            control.setOnClickListener(v ->
                    VirtualDisplayController.this.applyState(
                            session,
                            ConfigKeys.STATE_WINDOW,
                            "restore-control"));

            int width = dp(hidden ? 22 : 54);
            int height = dp(hidden ? 54 : 54);

            restoreParams =
                    new WindowManager.LayoutParams(
                            width,
                            height,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT);

            restoreParams.gravity =
                    Gravity.TOP | Gravity.START;

            DisplayMetrics metrics =
                    context.getResources().getDisplayMetrics();

            restoreParams.x =
                    Math.max(0,
                            metrics.widthPixels
                                    - width
                                    - dp(hidden ? 0 : 8));

            restoreParams.y =
                    clamp(
                            savedWindowY,
                            dp(80),
                            Math.max(
                                    dp(80),
                                    metrics.heightPixels
                                            - height
                                            - dp(80)));

            windowManager.addView(control, restoreParams);
            restoreControl = control;
        }

        private void removeRestoreControl() {
            if (restoreControl == null
                    || windowManager == null) {
                return;
            }

            try {
                windowManager.removeViewImmediate(
                        restoreControl);
            } catch (Throwable ignored) {
            }

            restoreControl = null;
            restoreParams = null;
        }

        private boolean handleWindowDrag(
                MotionEvent event
        ) {
            if (windowParams == null
                    || session.state != ConfigKeys.STATE_WINDOW) {
                return false;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.getRawX();
                    dragStartRawY = event.getRawY();
                    dragStartWindowX = windowParams.x;
                    dragStartWindowY = windowParams.y;
                    return true;
                }

                case MotionEvent.ACTION_MOVE -> {
                    windowParams.x =
                            dragStartWindowX
                                    + Math.round(
                                    event.getRawX()
                                            - dragStartRawX);

                    windowParams.y =
                            dragStartWindowY
                                    + Math.round(
                                    event.getRawY()
                                            - dragStartRawY);

                    clampWindowPosition();
                    updateHostLayout();
                    return true;
                }

                case MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                    clampWindowPosition();
                    savedWindowX = windowParams.x;
                    savedWindowY = windowParams.y;
                    updateHostLayout();
                    return true;
                }

                default -> {
                    return false;
                }
            }
        }

        private boolean handleResize(
                MotionEvent event
        ) {
            if (session.state != ConfigKeys.STATE_WINDOW) {
                return false;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    resizeStartRawX = event.getRawX();
                    resizeStartRawY = event.getRawY();
                    resizeStartWidth = contentWidth;
                    resizeStartHeight = contentHeight;
                    return true;
                }

                case MotionEvent.ACTION_MOVE -> {
                    DisplayMetrics metrics =
                            context.getResources()
                                    .getDisplayMetrics();

                    contentWidth = clamp(
                            resizeStartWidth
                                    + Math.round(
                                    event.getRawX()
                                            - resizeStartRawX),
                            dp(240),
                            Math.max(
                                    dp(240),
                                    metrics.widthPixels - dp(12)));

                    contentHeight = clamp(
                            resizeStartHeight
                                    + Math.round(
                                    event.getRawY()
                                            - resizeStartRawY),
                            dp(280),
                            Math.max(
                                    dp(280),
                                    metrics.heightPixels - dp(90)));

                    // Preview only. TextureView scales the last VirtualDisplay frame.
                    // Do NOT resize the VirtualDisplay here: every resize is a display
                    // configuration change and causes expensive OEM transitions.
                    updateWindowSizeOnly();
                    return true;
                }

                case MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                    updateWindowSizeOnly();
                    commitDisplaySize();
                    return true;
                }

                default -> {
                    return false;
                }
            }
        }

        private void updateWindowSizeOnly() {
            if (windowParams == null || root == null) return;

            windowParams.width = contentWidth;
            windowParams.height =
                    contentHeight + titleHeight;

            View content = root.getChildAt(1);
            if (content != null) {
                content.setLayoutParams(
                        new LinearLayout.LayoutParams(
                                contentWidth,
                                contentHeight));
            }

            clampWindowPosition();
            updateHostLayout();
        }

        private void commitDisplaySize() {
            if (virtualDisplay == null
                    || destroyed
                    || !surfaceReady) {
                return;
            }

            try {
                SurfaceTexture texture =
                        textureView == null
                                ? null
                                : textureView.getSurfaceTexture();

                if (texture != null) {
                    texture.setDefaultBufferSize(
                            Math.max(1, contentWidth),
                            Math.max(1, contentHeight));
                }

                virtualDisplay.resize(
                        Math.max(1, contentWidth),
                        Math.max(1, contentHeight),
                        densityDpi);

                log("VD_RESIZE_COMMIT",
                        "pkg=" + session.packageName
                                + " displayId=" + displayId
                                + " size=" + contentWidth
                                + "x" + contentHeight);
            } catch (Throwable t) {
                log("VD_RESIZE_ERROR",
                        "pkg=" + session.packageName
                                + " displayId=" + displayId
                                + " error=" + t);
            }
        }

        private void updateHostLayout() {
            if (windowManager == null
                    || root == null
                    || destroyed) {
                return;
            }

            try {
                windowManager.updateViewLayout(
                        root,
                        windowParams);
            } catch (Throwable t) {
                log("VD_HOST_LAYOUT_ERROR",
                        "pkg=" + session.packageName
                                + " displayId=" + displayId
                                + " error=" + t);
            }
        }

        private boolean forwardMotionEvent(
                MotionEvent source
        ) {
            if (source == null
                    || displayId < 0
                    || destroyed
                    || !taskMoved
                    || !surfaceReady) {
                return false;
            }

            Object im = inputManager();
            if (im == null) {
                log("VD_INPUT_ERROR",
                        "pkg=" + session.packageName
                                + " displayId=" + displayId
                                + " reason=no-input-manager");
                return false;
            }

            int count = source.getPointerCount();

            MotionEvent.PointerProperties[] properties =
                    new MotionEvent.PointerProperties[count];
            MotionEvent.PointerCoords[] coords =
                    new MotionEvent.PointerCoords[count];

            for (int i = 0; i < count; i++) {
                MotionEvent.PointerProperties property =
                        new MotionEvent.PointerProperties();
                source.getPointerProperties(i, property);
                properties[i] = property;

                MotionEvent.PointerCoords coord =
                        new MotionEvent.PointerCoords();
                source.getPointerCoords(i, coord);
                coords[i] = coord;
            }

            MotionEvent forwarded =
                    MotionEvent.obtain(
                            source.getDownTime(),
                            source.getEventTime(),
                            source.getAction(),
                            count,
                            properties,
                            coords,
                            source.getMetaState(),
                            source.getButtonState(),
                            source.getXPrecision(),
                            source.getYPrecision(),
                            source.getDeviceId(),
                            source.getEdgeFlags(),
                            source.getSource(),
                            source.getFlags());

            try {
                setInputEventDisplayId(
                        forwarded,
                        displayId);

                Boolean accepted =
                        invokeForBoolean(
                                im,
                                "injectInputEvent",
                                forwarded,
                                0);

                if (accepted == null || !accepted) {
                    log("VD_INPUT_ERROR",
                            "pkg=" + session.packageName
                                    + " displayId=" + displayId
                                    + " action=" + source.getActionMasked()
                                    + " accepted=" + accepted);
                    return false;
                }

                if (source.getActionMasked()
                        == MotionEvent.ACTION_DOWN) {
                    log("VD_INPUT_DOWN",
                            "pkg=" + session.packageName
                                    + " displayId=" + displayId
                                    + " x=" + source.getX()
                                    + " y=" + source.getY());
                }

                return true;
            } finally {
                forwarded.recycle();
            }
        }

        private void injectBack() {
            if (displayId < 0
                    || destroyed
                    || !taskMoved) {
                return;
            }

            focusRemoteTask("back");

            long now = SystemClock.uptimeMillis();

            KeyEvent down = new KeyEvent(
                    now,
                    now,
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_BACK,
                    0);

            KeyEvent up = new KeyEvent(
                    now,
                    now,
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_BACK,
                    0);

            setInputEventDisplayId(down, displayId);
            setInputEventDisplayId(up, displayId);

            Object im = inputManager();
            if (im != null) {
                invokeForBoolean(
                        im,
                        "injectInputEvent",
                        down,
                        0);
                invokeForBoolean(
                        im,
                        "injectInputEvent",
                        up,
                        0);
            }
        }

        private void focusRemoteTask(String reason) {
            if (!session.active
                    || !taskMoved) {
                return;
            }

            Object atm = activityTaskManager();
            boolean requested = false;

            if (atm != null) {
                requested |= invokeVoidLike(
                        atm,
                        "setFocusedTask",
                        session.taskId);

                requested |= invokeVoidLike(
                        atm,
                        "setFocusedRootTask",
                        session.taskId);
            }

            try {
                ActivityManager am =
                        context.getSystemService(
                                ActivityManager.class);

                if (am != null) {
                    am.moveTaskToFront(
                            session.taskId,
                            0);
                    requested = true;
                }
            } catch (Throwable ignored) {
            }

            log("VD_FOCUS",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " displayId=" + displayId
                            + " reason=" + reason
                            + " requested=" + requested);
        }

        private void clampWindowPosition() {
            DisplayMetrics metrics =
                    context.getResources()
                            .getDisplayMetrics();

            int maxX = Math.max(
                    0,
                    metrics.widthPixels
                            - Math.max(
                            titleHeight,
                            windowParams.width));

            int maxY = Math.max(
                    0,
                    metrics.heightPixels
                            - Math.max(
                            titleHeight,
                            windowParams.height));

            windowParams.x =
                    clamp(windowParams.x, 0, maxX);
            windowParams.y =
                    clamp(windowParams.y, 0, maxY);
        }

        @Override
        public void onSurfaceTextureAvailable(
                SurfaceTexture texture,
                int width,
                int height
        ) {
            if (destroyed || virtualDisplay == null) return;

            try {
                texture.setDefaultBufferSize(
                        Math.max(1, contentWidth),
                        Math.max(1, contentHeight));

                if (renderSurface != null) {
                    try {
                        renderSurface.release();
                    } catch (Throwable ignored) {
                    }
                }

                renderSurface = new Surface(texture);
                virtualDisplay.setSurface(renderSurface);
                surfaceReady = true;

                log("VD_SURFACE_READY",
                        "pkg=" + session.packageName
                                + " taskId=" + session.taskId
                                + " displayId=" + displayId
                                + " texture=" + width
                                + "x" + height
                                + " buffer=" + contentWidth
                                + "x" + contentHeight);

                if (!taskMoved) {
                    taskMoved =
                            moveTaskToDisplay(
                                    session.taskId,
                                    displayId);

                    if (!taskMoved) {
                        handler.post(() ->
                                closeSession(
                                        session,
                                        false,
                                        "move-failed"));
                        return;
                    }

                    focusRemoteTask("surface-ready");
                    VirtualDisplayController.this.applyState(session, session.state, "initial");
                }
            } catch (Throwable t) {
                log("VD_SURFACE_ERROR",
                        "pkg=" + session.packageName
                                + " displayId=" + displayId
                                + " error=" + t);
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(
                SurfaceTexture texture,
                int width,
                int height
        ) {
            // Host preview resize only. VirtualDisplay is committed once at the
            // end of the resize gesture to avoid continuous display transitions.
        }

        @Override
        public boolean onSurfaceTextureDestroyed(
                SurfaceTexture texture
        ) {
            surfaceReady = false;

            if (virtualDisplay != null) {
                try {
                    virtualDisplay.setSurface(null);
                } catch (Throwable ignored) {
                }
            }

            if (renderSurface != null) {
                try {
                    renderSurface.release();
                } catch (Throwable ignored) {
                }
                renderSurface = null;
            }

            log("VD_SURFACE_LOST",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " displayId=" + displayId
                            + " hostDestroyed=" + destroyed);

            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(
                SurfaceTexture texture
        ) {
        }

        void destroy(boolean restoreTask) {
            if (destroyed) return;
            destroyed = true;

            removeRestoreControl();

            if (restoreTask && taskMoved) {
                moveTaskToDisplay(
                        session.taskId,
                        Math.max(0, session.originalDisplayId));
            }

            if (windowManager != null && root != null) {
                try {
                    windowManager.removeViewImmediate(root);
                } catch (Throwable ignored) {
                }
            }

            if (virtualDisplay != null) {
                try {
                    virtualDisplay.setSurface(null);
                } catch (Throwable ignored) {
                }
            }

            if (renderSurface != null) {
                try {
                    renderSurface.release();
                } catch (Throwable ignored) {
                }
                renderSurface = null;
            }

            if (virtualDisplay != null) {
                try {
                    virtualDisplay.release();
                } catch (Throwable ignored) {
                }
            }

            root = null;
            textureView = null;
            virtualDisplay = null;
            displayId = -1;
        }

        private int dp(float value) {
            return Math.round(
                    value
                            * context.getResources()
                            .getDisplayMetrics()
                            .density);
        }
    }

    private boolean moveTaskToDisplay(
            int taskId,
            int displayId
    ) {
        if (taskId < 0 || displayId < 0) {
            return false;
        }

        Object atm = activityTaskManager();
        if (atm == null) {
            log("VD_MOVE_ERROR",
                    "taskId=" + taskId
                            + " displayId=" + displayId
                            + " reason=no-activity-task-manager");
            return false;
        }

        try {
            Method move =
                    findCompatibleMethod(
                            atm.getClass(),
                            "moveRootTaskToDisplay",
                            new Object[]{taskId, displayId});

            if (move == null) {
                log("VD_MOVE_ERROR",
                        "taskId=" + taskId
                                + " displayId=" + displayId
                                + " reason=no-moveRootTaskToDisplay");
                return false;
            }

            move.setAccessible(true);
            move.invoke(atm, taskId, displayId);

            log("VD_TASK_MOVED",
                    "taskId=" + taskId
                            + " displayId=" + displayId);
            return true;
        } catch (Throwable t) {
            log("VD_MOVE_ERROR",
                    "taskId=" + taskId
                            + " displayId=" + displayId
                            + " error=" + t);
            return false;
        }
    }

    private Object activityTaskManager() {
        Object cached = activityTaskManager;
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

    private Object inputManager() {
        Object cached = inputManager;
        if (cached != null) return cached;

        synchronized (this) {
            if (inputManager == null) {
                inputManager =
                        resolveBinderInterface(
                                "input",
                                "android.hardware.input.IInputManager$Stub");

                if (inputManager == null) {
                    inputManager =
                            resolveBinderInterface(
                                    "input",
                                    "android.view.IInputManager$Stub");
                }
            }
            return inputManager;
        }
    }

    private Object resolveBinderInterface(
            String serviceName,
            String stubClassName
    ) {
        try {
            ClassLoader loader =
                    VirtualDisplayController.class.getClassLoader();

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

            return asInterface.invoke(null, binder);
        } catch (Throwable t) {
            log("VD_BINDER_ERROR",
                    "service=" + serviceName
                            + " stub=" + stubClassName
                            + " error=" + t);
            return null;
        }
    }

    private static Object activityTask(Object activityRecord) {
        Object task = invokeNoArg(activityRecord, "getTask");
        if (task != null) return task;

        task = fieldValue(activityRecord, "task");
        if (task != null) return task;

        return fieldValue(activityRecord, "mTask");
    }

    private static int taskId(Object task) {
        if (task == null) return -1;

        Object value = invokeNoArg(task, "getTaskId");
        if (value instanceof Integer) {
            return (Integer) value;
        }

        value = fieldValue(task, "mTaskId");
        return value instanceof Integer ? (Integer) value : -1;
    }

    private static int taskDisplayId(Object task) {
        Object value = invokeNoArg(task, "getDisplayId");
        if (value instanceof Integer) {
            return (Integer) value;
        }

        Object displayArea = invokeNoArg(task, "getDisplayArea");
        Object displayId = invokeNoArg(displayArea, "getDisplayId");

        return displayId instanceof Integer
                ? (Integer) displayId
                : 0;
    }

    private static Context deriveSystemContext(Object task) {
        Object service = fieldValue(task, "mAtmService");
        if (service == null) {
            service = fieldValue(task, "mService");
        }

        Object context = fieldValue(service, "mUiContext");
        if (!(context instanceof Context)) {
            context = fieldValue(service, "mContext");
        }

        return context instanceof Context
                ? (Context) context
                : null;
    }

    private static String activityPackage(Object activityRecord) {
        Object value = fieldValue(activityRecord, "packageName");
        if (value instanceof String) {
            return (String) value;
        }

        Object component =
                fieldValue(activityRecord, "mActivityComponent");

        if (component instanceof ComponentName) {
            return ((ComponentName) component).getPackageName();
        }

        return null;
    }

    private static void setInputEventDisplayId(
            Object event,
            int displayId
    ) {
        if (event == null) return;

        Method method =
                findCompatibleMethod(
                        event.getClass(),
                        "setDisplayId",
                        new Object[]{displayId});

        if (method == null) return;

        try {
            method.setAccessible(true);
            method.invoke(event, displayId);
        } catch (Throwable ignored) {
        }
    }

    private static Boolean invokeForBoolean(
            Object receiver,
            String methodName,
            Object... args
    ) {
        if (receiver == null) return null;

        Method method =
                findCompatibleMethod(
                        receiver.getClass(),
                        methodName,
                        args);

        if (method == null) return null;

        try {
            method.setAccessible(true);
            Object result = method.invoke(receiver, args);

            if (result instanceof Boolean) {
                return (Boolean) result;
            }

            return true;
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
            method.invoke(receiver, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
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

    private static Method findCompatibleMethod(
            Class<?> type,
            String name,
            Object[] args
    ) {
        if (type == null) return null;

        for (Method method : type.getMethods()) {
            if (methodMatches(method, name, args)) {
                return method;
            }
        }

        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (methodMatches(method, name, args)) {
                    return method;
                }
            }
            current = current.getSuperclass();
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

        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length != args.length) {
            return false;
        }

        for (int i = 0; i < parameters.length; i++) {
            if (!compatible(parameters[i], args[i])) {
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

        Class<?> actual = value.getClass();

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

    private static int clamp(
            int value,
            int min,
            int max
    ) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private void log(String event, String detail) {
        if (logger != null) {
            logger.log(event, detail);
        }
    }

    private static final class Session {
        final int taskId;
        final String packageName;
        final Object taskObject;
        final int originalDisplayId;

        volatile boolean active = true;
        volatile int state;
        volatile long lastSeenElapsed =
                SystemClock.elapsedRealtime();
        volatile WindowHost host;

        Session(
                int taskId,
                String packageName,
                Object taskObject,
                int originalDisplayId,
                int state
        ) {
            this.taskId = taskId;
            this.packageName = packageName;
            this.taskObject = taskObject;
            this.originalDisplayId = originalDisplayId;
            this.state = state;
        }
    }
}
