package com.yagay.MiniWindowGuard;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
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
import android.widget.LinearLayout;
import android.widget.TextView;

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

    private static final long POLL_MS = 200L;

    // Same virtual-display capabilities used by YAMF/YAMF²:
    // SECURE | ROTATES_WITH_CONTENT | SHOULD_SHOW_SYSTEM_DECORATIONS | TRUSTED.
    private static final int VIRTUAL_DISPLAY_FLAGS = 1668;

    private final Handler handler;
    private volatile Context systemContext;
    private final Logger logger;
    private final Map<Integer, ManagedTask> tasks = new ConcurrentHashMap<>();

    private volatile int lastCommandSeq = Integer.MIN_VALUE;
    private volatile String pendingPackage = "";
    private volatile int pendingState = ConfigKeys.STATE_WINDOW;

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

    boolean wantsPackage(String packageName) {
        return packageName != null
                && (packageName.equals(pendingPackage)
                || isManagedPackage(packageName));
    }

    String managedPackageForProcess(String processName) {
        if (processName == null || processName.isBlank()) return null;

        for (ManagedTask task : tasks.values()) {
            if (task.state == ConfigKeys.STATE_RELEASED) continue;

            String pkg = task.packageName;
            if (processName.equals(pkg)
                    || processName.startsWith(pkg + ":")) {
                return pkg;
            }
        }
        return null;
    }

    boolean isManagedActivityRecord(Object activityRecord) {
        String pkg = activityPackage(activityRecord);
        return isManagedPackage(pkg);
    }

    boolean isManagedTask(Object task) {
        if (task == null) return false;
        int id = taskId(task);
        ManagedTask managed = id < 0 ? null : tasks.get(id);
        return managed != null && managed.state != ConfigKeys.STATE_RELEASED;
    }

    boolean isManagedTopActivityRecord(Object activityRecord) {
        if (!isManagedActivityRecord(activityRecord)) return false;

        Object task = getTask(activityRecord);
        if (task == null) return false;

        Object top = invokeNoArg(task, "topRunningActivity");
        if (top == null) top = invokeNoArg(task, "getTopResumedActivity");
        return top == null || top == activityRecord;
    }

    int stateForPackage(String packageName) {
        ManagedTask task = findLatest(packageName);
        return task == null ? ConfigKeys.STATE_RELEASED : task.state;
    }

    void capture(Object activityRecord, String packageName) {
        if (activityRecord == null
                || packageName == null
                || !GuardConfig.bool(ConfigKeys.AUTO_CONTAINER)
                || !wantsPackage(packageName)) {
            return;
        }

        Object taskObject = getTask(activityRecord);
        if (taskObject == null) {
            log("VD_CAPTURE_SKIP", "pkg=" + packageName + " reason=no-task");
            return;
        }

        if (systemContext == null) {
            systemContext = deriveSystemContext(taskObject);
        }

        int taskId = taskId(taskObject);
        if (taskId < 0) {
            log("VD_CAPTURE_SKIP", "pkg=" + packageName + " reason=no-task-id");
            return;
        }

        ManagedTask existing = tasks.get(taskId);
        if (existing != null) {
            existing.lastSeenElapsed = SystemClock.elapsedRealtime();
            return;
        }

        int originalDisplayId = readDisplayId(taskObject);
        ManagedTask managed = new ManagedTask(
                taskId,
                packageName,
                taskObject,
                originalDisplayId,
                pendingPackage.equals(packageName)
                        ? pendingState
                        : ConfigKeys.STATE_WINDOW);

        tasks.put(taskId, managed);
        if (pendingPackage.equals(packageName)) {
            pendingPackage = "";
        }

        log("VD_TASK_CAPTURED",
                "pkg=" + packageName
                        + " taskId=" + taskId
                        + " originalDisplay=" + originalDisplayId
                        + " state=" + managed.state);

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
        if (managed == null) return;

        int state = ConfigKeys.sanitizeState(requestedState);

        try {
            if (state == ConfigKeys.STATE_RELEASED) {
                managed.state = ConfigKeys.STATE_RELEASED;
                if (managed.window != null) {
                    managed.window.destroy(true);
                    managed.window = null;
                } else {
                    moveTaskToDisplay(
                            managed.taskId,
                            Math.max(0, managed.originalDisplayId));
                }
                tasks.remove(managed.taskId, managed);
                log("VD_TASK_RELEASED",
                        "pkg=" + managed.packageName
                                + " taskId=" + managed.taskId);
                return;
            }

            if (managed.window == null) {
                VirtualWindow window = new VirtualWindow(managed);
                if (!window.create()) {
                    tasks.remove(managed.taskId, managed);
                    managed.state = ConfigKeys.STATE_RELEASED;
                    log("VD_WINDOW_CREATE_FAIL",
                            "pkg=" + managed.packageName
                                    + " taskId=" + managed.taskId);
                    return;
                }
                managed.window = window;
            }

            managed.state = state;
            managed.lastSeenElapsed = SystemClock.elapsedRealtime();
            managed.window.applyVisualState(state);

            log("VD_TASK_STATE",
                    "pkg=" + managed.packageName
                            + " taskId=" + managed.taskId
                            + " displayId=" + managed.window.displayId
                            + " state=" + state);
        } catch (Throwable t) {
            log("VD_STATE_ERROR",
                    "pkg=" + managed.packageName
                            + " taskId=" + managed.taskId
                            + " state=" + state
                            + " error=" + t);
        }
    }

    private final class VirtualWindow
            implements TextureView.SurfaceTextureListener {

        private final ManagedTask managed;

        private Context context;
        private WindowManager windowManager;
        private DisplayManager displayManager;
        private Object inputManager;

        private VirtualDisplay virtualDisplay;
        private Surface renderSurface;

        private LinearLayout root;
        private LinearLayout topBar;
        private TextView title;
        private Button backButton;
        private Button moreButton;
        private Button resizeButton;
        private TextureView surfaceView;
        private View actionMenu;

        private WindowManager.LayoutParams windowParams;

        private int displayId = -1;
        private int densityDpi;
        private int expandedWidth;
        private int expandedHeight;
        private int barHeight;

        private float dragStartRawX;
        private float dragStartRawY;
        private int dragStartX;
        private int dragStartY;

        private float resizeStartRawX;
        private float resizeStartRawY;
        private int resizeStartWidth;
        private int resizeStartHeight;

        VirtualWindow(ManagedTask managed) {
            this.managed = managed;
        }

        boolean create() {
            try {
                context = systemContext;
                if (context == null) return false;

                windowManager = context.getSystemService(WindowManager.class);
                displayManager = context.getSystemService(DisplayManager.class);
                inputManager = context.getSystemService("input");

                if (windowManager == null || displayManager == null) {
                    log("VD_CREATE_ERROR",
                            "pkg=" + managed.packageName
                                    + " wm=" + windowManager
                                    + " dm=" + displayManager);
                    return false;
                }

                DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                densityDpi = Math.max(160, metrics.densityDpi);
                expandedWidth = clamp(
                        metrics.widthPixels * GuardConfig.containerWidth() / 100,
                        dp(260),
                        Math.max(dp(260), metrics.widthPixels - dp(24)));
                expandedHeight = clamp(
                        metrics.heightPixels * GuardConfig.containerHeight() / 100,
                        dp(360),
                        Math.max(dp(360), metrics.heightPixels - dp(120)));
                barHeight = dp(44);

                virtualDisplay = displayManager.createVirtualDisplay(
                        "MiniWindowGuard-" + managed.taskId,
                        expandedWidth,
                        expandedHeight,
                        densityDpi,
                        null,
                        VIRTUAL_DISPLAY_FLAGS);

                if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                    log("VD_CREATE_ERROR",
                            "pkg=" + managed.packageName
                                    + " reason=createVirtualDisplay-null");
                    return false;
                }

                displayId = virtualDisplay.getDisplay().getDisplayId();
                buildOverlay();

                if (!moveTaskToDisplay(managed.taskId, displayId)) {
                    log("VD_MOVE_ERROR",
                            "pkg=" + managed.packageName
                                    + " taskId=" + managed.taskId
                                    + " displayId=" + displayId);
                    destroy(false);
                    return false;
                }

                log("VD_WINDOW_CREATED",
                        "pkg=" + managed.packageName
                                + " taskId=" + managed.taskId
                                + " displayId=" + displayId
                                + " size=" + expandedWidth + "x" + expandedHeight
                                + " density=" + densityDpi
                                + " flags=" + VIRTUAL_DISPLAY_FLAGS);
                return true;
            } catch (Throwable t) {
                log("VD_CREATE_ERROR",
                        "pkg=" + managed.packageName + " error=" + t);
                destroy(false);
                return false;
            }
        }

        private void buildOverlay() {
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);

            topBar = new LinearLayout(context);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setPadding(dp(4), 0, dp(4), 0);

            GradientDrawable barBg = new GradientDrawable();
            barBg.setColor(0xEE202328);
            barBg.setCornerRadius(dp(12));
            topBar.setBackground(barBg);

            backButton = smallButton("‹");
            backButton.setTextSize(26);
            backButton.setOnClickListener(v -> injectBack());

            title = new TextView(context);
            title.setText(managed.packageName);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(12.5f);
            title.setSingleLine(true);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setPadding(dp(6), 0, dp(6), 0);
            title.setOnTouchListener((v, event) -> handleDrag(event));

            moreButton = smallButton("⋮");
            moreButton.setTextSize(22);
            moreButton.setOnClickListener(v -> toggleActionMenu());

            resizeButton = smallButton("↘");
            resizeButton.setTextSize(18);
            resizeButton.setOnTouchListener((v, event) -> handleResize(event));

            topBar.addView(backButton,
                    new LinearLayout.LayoutParams(barHeight, barHeight));
            topBar.addView(title,
                    new LinearLayout.LayoutParams(
                            0,
                            barHeight,
                            1f));
            topBar.addView(moreButton,
                    new LinearLayout.LayoutParams(barHeight, barHeight));
            topBar.addView(resizeButton,
                    new LinearLayout.LayoutParams(barHeight, barHeight));

            surfaceView = new TextureView(context);
            surfaceView.setOpaque(true);
            surfaceView.setSurfaceTextureListener(this);
            surfaceView.setOnTouchListener((v, event) -> {
                if (managed.state != ConfigKeys.STATE_WINDOW) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    handler.post(this::bringToFront);
                }
                return injectMotionEvent(event);
            });
            surfaceView.setOnGenericMotionListener((v, event) ->
                    managed.state == ConfigKeys.STATE_WINDOW
                            && injectMotionEvent(event));

            root.addView(topBar,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            barHeight));
            root.addView(surfaceView,
                    new LinearLayout.LayoutParams(
                            expandedWidth,
                            expandedHeight));

            windowParams = new WindowManager.LayoutParams(
                    expandedWidth,
                    expandedHeight + barHeight,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            windowParams.gravity = Gravity.TOP | Gravity.START;
            windowParams.x = dp(12);
            windowParams.y = dp(72);

            windowManager.addView(root, windowParams);
        }

        void applyVisualState(int state) {
            if (root == null) return;

            if (state == ConfigKeys.STATE_WINDOW) {
                showExpanded();
            } else {
                showControllerOnly(state);
            }

            updateMenuPosition();
        }

        private void showExpanded() {
            title.setVisibility(View.VISIBLE);
            backButton.setVisibility(View.VISIBLE);
            resizeButton.setVisibility(View.VISIBLE);
            moreButton.setText("⋮");

            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    expandedWidth,
                    expandedHeight);
            surfaceView.setLayoutParams(sp);
            surfaceView.setAlpha(1f);
            surfaceView.setVisibility(View.VISIBLE);

            windowParams.width = expandedWidth;
            windowParams.height = expandedHeight + barHeight;
            clampWindowPosition();
            safeUpdateRoot();

            resizeVirtualDisplay(expandedWidth, expandedHeight);
            bringToFront();
        }

        private void showControllerOnly(int state) {
            title.setVisibility(View.GONE);
            backButton.setVisibility(View.GONE);
            resizeButton.setVisibility(View.GONE);
            moreButton.setText("⋮");

            // Keep TextureView, SurfaceTexture and the VirtualDisplay buffer at
            // their full size. Only the host overlay is collapsed and the pixels
            // are made transparent, so the remote task keeps a live display surface.
            LinearLayout.LayoutParams sp =
                    new LinearLayout.LayoutParams(
                            expandedWidth,
                            expandedHeight);
            surfaceView.setLayoutParams(sp);
            surfaceView.setAlpha(0f);
            surfaceView.setVisibility(View.VISIBLE);

            windowParams.width = barHeight + dp(8);
            windowParams.height = barHeight;
            clampWindowPosition();
            safeUpdateRoot();
            bringToFront();

            log("VD_SURFACE_HIDDEN",
                    "pkg=" + managed.packageName
                            + " displayId=" + displayId
                            + " state=" + state
                            + " surfaceAlive=true");
        }

        private Button smallButton(String text) {
            Button b = new Button(context);
            b.setText(text);
            b.setTextColor(0xFFFFFFFF);
            b.setAllCaps(false);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(0, 0, 0, 0);
            b.setBackgroundColor(0x00000000);
            return b;
        }

        private boolean handleDrag(MotionEvent event) {
            if (windowParams == null) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.getRawX();
                    dragStartRawY = event.getRawY();
                    dragStartX = windowParams.x;
                    dragStartY = windowParams.y;
                    handler.post(this::bringToFront);
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    windowParams.x = dragStartX
                            + Math.round(event.getRawX() - dragStartRawX);
                    windowParams.y = dragStartY
                            + Math.round(event.getRawY() - dragStartRawY);
                    clampWindowPosition();
                    safeUpdateRoot();
                    updateMenuPosition();
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampWindowPosition();
                    safeUpdateRoot();
                    updateMenuPosition();
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private boolean handleResize(MotionEvent event) {
            if (managed.state != ConfigKeys.STATE_WINDOW) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    resizeStartRawX = event.getRawX();
                    resizeStartRawY = event.getRawY();
                    resizeStartWidth = expandedWidth;
                    resizeStartHeight = expandedHeight;
                    handler.post(this::bringToFront);
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    DisplayMetrics metrics =
                            context.getResources().getDisplayMetrics();

                    expandedWidth = clamp(
                            resizeStartWidth
                                    + Math.round(event.getRawX() - resizeStartRawX),
                            dp(240),
                            Math.max(dp(240), metrics.widthPixels - dp(16)));
                    expandedHeight = clamp(
                            resizeStartHeight
                                    + Math.round(event.getRawY() - resizeStartRawY),
                            dp(300),
                            Math.max(dp(300), metrics.heightPixels - dp(100)));

                    showExpanded();
                    updateMenuPosition();
                    return true;
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    showExpanded();
                    updateMenuPosition();
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private void toggleActionMenu() {
            if (actionMenu != null) {
                dismissActionMenu();
            } else {
                showActionMenu();
            }
        }

        private void showActionMenu() {
            if (actionMenu != null || windowManager == null) return;

            LinearLayout panel = new LinearLayout(context);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(6), dp(6), dp(6), dp(6));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFF8F9FA);
            bg.setCornerRadius(dp(14));
            panel.setBackground(bg);

            panel.addView(menuButton(
                    "放大（全屏）",
                    ConfigKeys.STATE_RELEASED));
            panel.addView(menuButton(
                    "小窗",
                    ConfigKeys.STATE_WINDOW));
            panel.addView(menuButton(
                    "图标",
                    ConfigKeys.STATE_ICON));
            panel.addView(menuButton(
                    "隐藏",
                    ConfigKeys.STATE_HIDDEN));

            WindowManager.LayoutParams lp = menuLayoutParams();
            windowManager.addView(panel, lp);
            actionMenu = panel;
        }

        private Button menuButton(String text, int state) {
            Button b = new Button(context);
            b.setText(text);
            b.setTextSize(14);
            b.setTextColor(0xFF202124);
            b.setAllCaps(false);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(14), 0, dp(14), 0);
            b.setOnClickListener(v -> {
                // Menu stays available for WINDOW / ICON / HIDDEN.
                // RELEASE destroys the whole VirtualDisplay container.
                handler.post(() -> applyState(managed, state));
            });
            return b;
        }

        private WindowManager.LayoutParams menuLayoutParams() {
            int menuWidth = dp(176);
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    menuWidth,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;

            int anchorRight = windowParams.x + Math.max(windowParams.width, barHeight);
            lp.x = clamp(
                    anchorRight - menuWidth,
                    dp(4),
                    Math.max(dp(4), metrics.widthPixels - menuWidth - dp(4)));
            lp.y = clamp(
                    windowParams.y + barHeight,
                    dp(4),
                    Math.max(dp(4), metrics.heightPixels - dp(260)));
            return lp;
        }

        private void updateMenuPosition() {
            if (actionMenu == null || windowManager == null) return;
            try {
                windowManager.updateViewLayout(
                        actionMenu,
                        menuLayoutParams());
            } catch (Throwable t) {
                log("VD_MENU_UPDATE_ERROR", String.valueOf(t));
            }
        }

        private void dismissActionMenu() {
            if (actionMenu == null || windowManager == null) return;
            try {
                windowManager.removeViewImmediate(actionMenu);
            } catch (Throwable ignored) {
            }
            actionMenu = null;
        }

        private void bringToFront() {
            if (root == null || windowManager == null) return;

            try {
                windowManager.removeViewImmediate(root);
                windowManager.addView(root, windowParams);
            } catch (Throwable ignored) {
                safeUpdateRoot();
            }

            if (actionMenu != null) {
                View menu = actionMenu;
                WindowManager.LayoutParams lp = menuLayoutParams();
                try {
                    windowManager.removeViewImmediate(menu);
                    windowManager.addView(menu, lp);
                } catch (Throwable ignored) {
                }
            }
        }

        private void safeUpdateRoot() {
            if (root == null || windowManager == null) return;
            try {
                windowManager.updateViewLayout(root, windowParams);
            } catch (Throwable t) {
                log("VD_WINDOW_UPDATE_ERROR", String.valueOf(t));
            }
        }

        private void clampWindowPosition() {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int maxX = Math.max(0, metrics.widthPixels - Math.max(barHeight, windowParams.width));
            int maxY = Math.max(0, metrics.heightPixels - Math.max(barHeight, windowParams.height));
            windowParams.x = clamp(windowParams.x, 0, maxX);
            windowParams.y = clamp(windowParams.y, 0, maxY);
        }

        private void resizeVirtualDisplay(int width, int height) {
            if (virtualDisplay == null) return;
            try {
                virtualDisplay.resize(
                        Math.max(1, width),
                        Math.max(1, height),
                        densityDpi);

                SurfaceTexture st = surfaceView == null
                        ? null : surfaceView.getSurfaceTexture();
                if (st != null) {
                    st.setDefaultBufferSize(
                            Math.max(1, width),
                            Math.max(1, height));
                }

                log("VD_RESIZE",
                        "pkg=" + managed.packageName
                                + " displayId=" + displayId
                                + " size=" + width + "x" + height);
            } catch (Throwable t) {
                log("VD_RESIZE_ERROR", String.valueOf(t));
            }
        }

        private boolean injectMotionEvent(MotionEvent source) {
            if (source == null || displayId < 0 || inputManager == null) {
                return false;
            }

            MotionEvent copy = MotionEvent.obtain(source);
            try {
                setInputEventDisplayId(copy, displayId);
                boolean ok = invokeCompatible(
                        inputManager,
                        "injectInputEvent",
                        copy,
                        0);
                if (!ok) {
                    log("VD_INPUT_ERROR",
                            "pkg=" + managed.packageName
                                    + " displayId=" + displayId
                                    + " reason=injectInputEvent-unavailable");
                }
                return ok;
            } finally {
                copy.recycle();
            }
        }

        private void injectBack() {
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
            invokeCompatible(inputManager, "injectInputEvent", down, 0);
            invokeCompatible(inputManager, "injectInputEvent", up, 0);
        }

        private void setInputEventDisplayId(Object event, int id) {
            if (event == null) return;
            invokeCompatible(event, "setDisplayId", id);
        }

        void destroy(boolean restoreTask) {
            dismissActionMenu();

            if (restoreTask) {
                moveTaskToDisplay(
                        managed.taskId,
                        Math.max(0, managed.originalDisplayId));
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
                virtualDisplay = null;
            }

            root = null;
            surfaceView = null;
            displayId = -1;
        }

        @Override
        public void onSurfaceTextureAvailable(
                SurfaceTexture surface,
                int width,
                int height
        ) {
            if (virtualDisplay == null) return;

            try {
                surface.setDefaultBufferSize(
                        Math.max(1, expandedWidth),
                        Math.max(1, expandedHeight));

                if (renderSurface != null) {
                    try {
                        renderSurface.release();
                    } catch (Throwable ignored) {
                    }
                }

                renderSurface = new Surface(surface);
                virtualDisplay.setSurface(renderSurface);
                resizeVirtualDisplay(expandedWidth, expandedHeight);

                log("VD_SURFACE_READY",
                        "pkg=" + managed.packageName
                                + " displayId=" + displayId
                                + " texture=" + width + "x" + height);
            } catch (Throwable t) {
                log("VD_SURFACE_ERROR", String.valueOf(t));
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(
                SurfaceTexture surface,
                int width,
                int height
        ) {
            if (managed.state == ConfigKeys.STATE_WINDOW) {
                resizeVirtualDisplay(
                        Math.max(1, expandedWidth),
                        Math.max(1, expandedHeight));
            }
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
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
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }

        private int dp(float value) {
            return Math.round(
                    value * context.getResources().getDisplayMetrics().density);
        }
    }

    private boolean moveTaskToDisplay(int taskId, int displayId) {
        if (taskId < 0 || displayId < 0) return false;

        try {
            Object atm = getActivityTaskManagerService();
            if (atm == null) {
                log("VD_MOVE_ERROR", "reason=no-activity-task-manager");
                return false;
            }

            Method move = findCompatibleMethod(
                    atm.getClass(),
                    "moveRootTaskToDisplay",
                    new Object[]{taskId, displayId});
            if (move == null) {
                log("VD_MOVE_ERROR",
                        "reason=no-moveRootTaskToDisplay"
                                + " taskId=" + taskId
                                + " displayId=" + displayId);
                return false;
            }

            move.setAccessible(true);
            move.invoke(atm, taskId, displayId);

            try {
                Context context = systemContext;
                ActivityManager am = context == null
                        ? null : context.getSystemService(ActivityManager.class);
                if (am != null) {
                    am.moveTaskToFront(taskId, 0);
                }
            } catch (Throwable ignored) {
            }

            log("VD_TASK_MOVED",
                    "taskId=" + taskId + " displayId=" + displayId);
            return true;
        } catch (Throwable t) {
            log("VD_MOVE_ERROR",
                    "taskId=" + taskId
                            + " displayId=" + displayId
                            + " error=" + t);
            return false;
        }
    }

    private Object getActivityTaskManagerService() {
        try {
            ClassLoader loader = TaskSurfaceController.class.getClassLoader();
            Class<?> serviceManager = Class.forName(
                    "android.os.ServiceManager",
                    false,
                    loader);
            Method getService = serviceManager.getDeclaredMethod(
                    "getService",
                    String.class);
            getService.setAccessible(true);

            Object binder = getService.invoke(null, "activity_task");
            if (!(binder instanceof IBinder)) return null;

            Class<?> stub = Class.forName(
                    "android.app.IActivityTaskManager$Stub",
                    false,
                    loader);
            Method asInterface = stub.getDeclaredMethod(
                    "asInterface",
                    IBinder.class);
            asInterface.setAccessible(true);
            return asInterface.invoke(null, binder);
        } catch (Throwable t) {
            log("VD_ATM_ERROR", String.valueOf(t));
            return null;
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

    private static int readDisplayId(Object task) {
        Object value = invokeNoArg(task, "getDisplayId");
        if (value instanceof Integer) return (Integer) value;

        Object displayArea = invokeNoArg(task, "getDisplayArea");
        Object id = invokeNoArg(displayArea, "getDisplayId");
        return id instanceof Integer ? (Integer) id : 0;
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

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
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

        Method method = findCompatibleMethod(
                receiver.getClass(),
                name,
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

    private static Method findCompatibleMethod(
            Class<?> type,
            String name,
            Object[] args
    ) {
        if (type == null) return null;

        for (Method method : type.getMethods()) {
            if (methodMatches(method, name, args)) return method;
        }

        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (methodMatches(method, name, args)) return method;
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
        if (!name.equals(method.getName())) return false;

        Class<?>[] params = method.getParameterTypes();
        if (params.length != args.length) return false;

        for (int i = 0; i < params.length; i++) {
            if (!isCompatible(params[i], args[i])) return false;
        }
        return true;
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
        final int originalDisplayId;

        volatile int state;
        volatile long lastSeenElapsed;
        volatile VirtualWindow window;

        ManagedTask(
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
            this.lastSeenElapsed = SystemClock.elapsedRealtime();
        }
    }
}
