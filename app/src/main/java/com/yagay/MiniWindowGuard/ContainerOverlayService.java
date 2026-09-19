package com.yagay.MiniWindowGuard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ContainerOverlayService extends Service {
    static final String ACTION_SYNC =
            "com.yagay.MiniWindowGuard.action.CONTAINER_SYNC";
    static final String ACTION_CONTROL =
            "com.yagay.MiniWindowGuard.action.CONTAINER_CONTROL";

    static final String EXTRA_PACKAGE = "package";
    static final String EXTRA_TASK_ID = "task_id";
    static final String EXTRA_STATE = "state";

    private static final String CHANNEL_ID = "container";
    private static final int NOTIFICATION_ID = 4301;

    private WindowManager windowManager;
    private View titleBar;
    private View windowHandle;
    private View miniIcon;
    private View actionMenu;

    private String currentPackage = "";
    private int currentTaskId = -1;
    private int currentState = ConfigKeys.STATE_RELEASED;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = getSystemService(WindowManager.class);
        createChannel();
        startForeground(
                NOTIFICATION_ID,
                buildNotification("", ConfigKeys.STATE_RELEASED));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if (ACTION_CONTROL.equals(action)) {
                String pkg = intent.getStringExtra(EXTRA_PACKAGE);
                int state = ConfigKeys.sanitizeState(
                        intent.getIntExtra(EXTRA_STATE, ConfigKeys.STATE_WINDOW));

                if (pkg != null && !pkg.isEmpty()) {
                    currentPackage = pkg;
                }

                GuardApp.sendContainerCommand(currentPackage, state);
                currentState = state;
            } else if (ACTION_SYNC.equals(action)) {
                String pkg = intent.getStringExtra(EXTRA_PACKAGE);
                if (pkg != null) currentPackage = pkg;

                currentTaskId = intent.getIntExtra(EXTRA_TASK_ID, -1);
                currentState = ConfigKeys.sanitizeState(
                        intent.getIntExtra(EXTRA_STATE, ConfigKeys.STATE_WINDOW));
            }
        }

        render();
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeViews();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void render() {
        if (currentState == ConfigKeys.STATE_RELEASED) {
            removeViews();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        if (!Settings.canDrawOverlays(this) || windowManager == null) {
            return;
        }

        // State changes only replace the target-App presentation. The three-dot
        // controller and an already-open action menu are independent overlays and
        // must survive WINDOW / ICON / HIDDEN transitions.
        removeTaskVisuals();

        if (currentState == ConfigKeys.STATE_WINDOW) {
            showTitleBar();
        } else if (currentState == ConfigKeys.STATE_ICON) {
            showMiniIcon();
        }
        // STATE_HIDDEN deliberately renders no target-App window/icon.

        // Re-create the handle last so it always stays above the window/title bar.
        showWindowHandleOnTop();
    }

    private void showTitleBar() {
        Rect bounds = ContainerGeometry.visibleBounds(
                getResources(),
                ConfigKeys.sanitizePercent(
                        GuardApp.getInt(ConfigKeys.CONTAINER_WIDTH), 58),
                ConfigKeys.sanitizePercent(
                        GuardApp.getInt(ConfigKeys.CONTAINER_HEIGHT), 66));

        int height = ContainerGeometry.dp(getResources(), 42);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(
                ContainerGeometry.dp(getResources(), 10),
                0,
                ContainerGeometry.dp(getResources(), 6),
                0);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6202328);
        background.setCornerRadius(
                ContainerGeometry.dp(getResources(), 12));
        bar.setBackground(background);

        TextView title = new TextView(this);
        title.setText(currentPackage.isEmpty()
                ? "MiniWindowGuard"
                : currentPackage);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(12.5f);
        title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f));

        WindowManager.LayoutParams lp = overlayParams(
                bounds.width(),
                height);
        lp.x = bounds.left;
        lp.y = Math.max(0, bounds.top - height);

        try {
            windowManager.addView(bar, lp);
            titleBar = bar;
        } catch (Throwable t) {
            CrashStore.record(this, "ContainerOverlayService.showTitleBar", t);
        }
    }

    private void showWindowHandleOnTop() {
        if (windowManager == null) return;

        if (windowHandle != null) {
            try {
                windowManager.removeViewImmediate(windowHandle);
            } catch (Throwable ignored) {
            }
            windowHandle = null;
        }

        Rect bounds = ContainerGeometry.visibleBounds(
                getResources(),
                ConfigKeys.sanitizePercent(
                        GuardApp.getInt(ConfigKeys.CONTAINER_WIDTH), 58),
                ConfigKeys.sanitizePercent(
                        GuardApp.getInt(ConfigKeys.CONTAINER_HEIGHT), 66));

        int size = ContainerGeometry.dp(getResources(), 42);

        Button handle = new Button(this);
        handle.setText("⋮");
        handle.setTextSize(22);
        handle.setTextColor(0xFFFFFFFF);
        handle.setAllCaps(false);
        handle.setMinWidth(0);
        handle.setMinimumWidth(0);
        handle.setPadding(0, 0, 0, 0);

        GradientDrawable handleBackground = new GradientDrawable();
        handleBackground.setColor(0xEE202328);
        handleBackground.setCornerRadius(
                ContainerGeometry.dp(getResources(), 12));
        handle.setBackground(handleBackground);

        handle.setOnClickListener(v -> toggleActionMenu());

        WindowManager.LayoutParams lp = overlayParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.max(0, bounds.right - size);
        lp.y = Math.max(0, bounds.top - size);

        try {
            windowManager.addView(handle, lp);
            windowHandle = handle;
        } catch (Throwable t) {
            CrashStore.record(
                    this,
                    "ContainerOverlayService.showWindowHandle",
                    t);
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
        if (windowManager == null || actionMenu != null) return;

        Rect bounds = ContainerGeometry.visibleBounds(
                getResources(),
                ConfigKeys.sanitizePercent(
                        GuardApp.getInt(ConfigKeys.CONTAINER_WIDTH), 58),
                ConfigKeys.sanitizePercent(
                        GuardApp.getInt(ConfigKeys.CONTAINER_HEIGHT), 66));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(
                ContainerGeometry.dp(getResources(), 6),
                ContainerGeometry.dp(getResources(), 6),
                ContainerGeometry.dp(getResources(), 6),
                ContainerGeometry.dp(getResources(), 6));

        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(0xFFF8F9FA);
        panelBackground.setCornerRadius(
                ContainerGeometry.dp(getResources(), 14));
        panel.setBackground(panelBackground);

        panel.addView(menuButton(
                "放大",
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

        int menuWidth = ContainerGeometry.dp(getResources(), 176);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                menuWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.max(
                ContainerGeometry.dp(getResources(), 8),
                bounds.right - menuWidth);
        lp.y = Math.max(
                ContainerGeometry.dp(getResources(), 8),
                bounds.top);

        try {
            windowManager.addView(panel, lp);
            actionMenu = panel;
        } catch (Throwable t) {
            CrashStore.record(
                    this,
                    "ContainerOverlayService.showActionMenu",
                    t);
        }
    }

    private Button menuButton(String text, int state) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(0xFF202124);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(
                ContainerGeometry.dp(getResources(), 14),
                0,
                ContainerGeometry.dp(getResources(), 14),
                0);
        button.setOnClickListener(v -> {
            // WINDOW / ICON / HIDDEN only change the target App presentation.
            // Keep this control menu visible. RELEASED intentionally tears down
            // the whole container and render() removes every controller view.
            sendState(state);
        });
        return button;
    }

    private void dismissActionMenu() {
        if (windowManager == null || actionMenu == null) return;
        try {
            windowManager.removeViewImmediate(actionMenu);
        } catch (Throwable ignored) {
        }
        actionMenu = null;
    }

    private void showMiniIcon() {
        Button icon = new Button(this);
        icon.setText("◉");
        icon.setTextSize(18);
        icon.setAllCaps(false);
        icon.setOnClickListener(v ->
                sendState(ConfigKeys.STATE_WINDOW));
        icon.setOnLongClickListener(v -> {
            sendState(ConfigKeys.STATE_HIDDEN);
            return true;
        });

        int size = ContainerGeometry.dp(getResources(), 54);
        WindowManager.LayoutParams lp = overlayParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = ContainerGeometry.dp(getResources(), 12);
        lp.y = ContainerGeometry.dp(getResources(), 180);

        try {
            windowManager.addView(icon, lp);
            miniIcon = icon;
        } catch (Throwable t) {
            CrashStore.record(this, "ContainerOverlayService.showMiniIcon", t);
        }
    }

    private Button controlButton(String text, int state) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(11);
        button.setTextColor(0xFFFFFFFF);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(
                ContainerGeometry.dp(getResources(), 8),
                0,
                ContainerGeometry.dp(getResources(), 8),
                0);
        button.setOnClickListener(v -> sendState(state));
        return button;
    }

    private void sendState(int state) {
        currentState = ConfigKeys.sanitizeState(state);
        GuardApp.sendContainerCommand(currentPackage, currentState);
        render();
        updateNotification();
    }

    private WindowManager.LayoutParams overlayParams(int width, int height) {
        return new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
    }

    private void removeTaskVisuals() {
        if (windowManager == null) return;

        if (titleBar != null) {
            try {
                windowManager.removeViewImmediate(titleBar);
            } catch (Throwable ignored) {
            }
            titleBar = null;
        }

        if (miniIcon != null) {
            try {
                windowManager.removeViewImmediate(miniIcon);
            } catch (Throwable ignored) {
            }
            miniIcon = null;
        }
    }

    private void removeViews() {
        if (windowManager == null) return;

        removeTaskVisuals();
        dismissActionMenu();

        if (windowHandle != null) {
            try {
                windowManager.removeViewImmediate(windowHandle);
            } catch (Throwable ignored) {
            }
            windowHandle = null;
        }
    }

    private void createChannel() {
        NotificationManager manager =
                getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "小窗守护容器",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持自有 Task 容器和隐藏态恢复入口");
        manager.createNotificationChannel(channel);
    }

    private void updateNotification() {
        NotificationManager manager =
                getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(
                    NOTIFICATION_ID,
                    buildNotification(currentPackage, currentState));
        }
    }

    private Notification buildNotification(String pkg, int state) {
        String title = pkg == null || pkg.isEmpty()
                ? "小窗守护"
                : "小窗守护 · " + pkg;

        String stateText = switch (state) {
            case ConfigKeys.STATE_WINDOW -> "窗口态";
            case ConfigKeys.STATE_ICON -> "图标态";
            case ConfigKeys.STATE_HIDDEN -> "隐藏态";
            default -> "等待容器任务";
        };

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(title)
                .setContentText(stateText)
                .setOngoing(state != ConfigKeys.STATE_RELEASED)
                .setOnlyAlertOnce(true);

        if (pkg != null && !pkg.isEmpty()) {
            builder.addAction(
                    new Notification.Action.Builder(
                            null,
                            "窗口",
                            controlPendingIntent(pkg, ConfigKeys.STATE_WINDOW, 1))
                            .build());
            builder.addAction(
                    new Notification.Action.Builder(
                            null,
                            "图标",
                            controlPendingIntent(pkg, ConfigKeys.STATE_ICON, 2))
                            .build());
            builder.addAction(
                    new Notification.Action.Builder(
                            null,
                            "隐藏",
                            controlPendingIntent(pkg, ConfigKeys.STATE_HIDDEN, 3))
                            .build());
            builder.addAction(
                    new Notification.Action.Builder(
                            null,
                            "释放",
                            controlPendingIntent(pkg, ConfigKeys.STATE_RELEASED, 4))
                            .build());
        }

        return builder.build();
    }

    private PendingIntent controlPendingIntent(
            String pkg,
            int state,
            int requestCode
    ) {
        Intent intent = new Intent(this, ContainerOverlayService.class);
        intent.setAction(ACTION_CONTROL);
        intent.putExtra(EXTRA_PACKAGE, pkg);
        intent.putExtra(EXTRA_STATE, state);

        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
