package com.yagay.MiniWindowGuard;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Lightweight controls drawn around a real task on display 0.
 *
 * This overlay never contains or mirrors app content. The app continues to
 * render in its own task/surface tree; the overlay only provides drag/resize
 * and window state controls.
 */
final class NativeTaskOverlay {
    interface Delegate {
        void onMoveBy(int dx, int dy);
        void onResizeTo(int width, int height);
        void onBack();
        void onMinimize();
        void onHide();
        void onClose();
        void onRestore();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Delegate delegate;

    private View toolbar;
    private View resizeHandle;
    private View restoreControl;

    private WindowManager.LayoutParams toolbarParams;
    private WindowManager.LayoutParams resizeParams;
    private WindowManager.LayoutParams restoreParams;

    private Rect bounds = new Rect();

    private float dragStartX;
    private float dragStartY;
    private int dragAccumX;
    private int dragAccumY;

    private float resizeStartX;
    private float resizeStartY;
    private int resizeStartWidth;
    private int resizeStartHeight;

    NativeTaskOverlay(
            Context context,
            Delegate delegate
    ) {
        this.context = context;
        this.delegate = delegate;
        this.windowManager =
                context == null
                        ? null
                        : context.getSystemService(
                                WindowManager.class);
    }

    boolean create(
            String title,
            Rect initialBounds
    ) {
        if (windowManager == null
                || initialBounds == null
                || initialBounds.isEmpty()) {
            return false;
        }

        destroy();

        bounds = new Rect(initialBounds);

        LinearLayout bar =
                new LinearLayout(context);
        bar.setOrientation(
                LinearLayout.HORIZONTAL);
        bar.setGravity(
                Gravity.CENTER_VERTICAL);
        bar.setPadding(
                dp(3),
                0,
                dp(3),
                0);

        GradientDrawable bg =
                new GradientDrawable();
        bg.setColor(0xE8202328);
        bg.setCornerRadius(dp(9));
        bar.setBackground(bg);

        Button back = button("‹", 26f);
        back.setOnClickListener(
                v -> delegate.onBack());

        TextView label =
                new TextView(context);
        label.setText(
                title == null
                        ? "MiniWindowGuard"
                        : title);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(12f);
        label.setSingleLine(true);
        label.setGravity(
                Gravity.CENTER_VERTICAL);
        label.setPadding(
                dp(5),
                0,
                dp(5),
                0);

        label.setOnTouchListener(
                (v, event) ->
                        handleDrag(event));

        Button minimize =
                button("●", 15f);
        minimize.setOnClickListener(
                v -> delegate.onMinimize());

        Button hide =
                button("隐", 13f);
        hide.setOnClickListener(
                v -> delegate.onHide());

        Button close =
                button("×", 23f);
        close.setOnClickListener(
                v -> delegate.onClose());

        int controlWidth = dp(34);
        int barHeight = dp(42);

        bar.addView(
                back,
                new LinearLayout.LayoutParams(
                        controlWidth,
                        barHeight));
        bar.addView(
                label,
                new LinearLayout.LayoutParams(
                        0,
                        barHeight,
                        1f));
        bar.addView(
                minimize,
                new LinearLayout.LayoutParams(
                        controlWidth,
                        barHeight));
        bar.addView(
                hide,
                new LinearLayout.LayoutParams(
                        controlWidth,
                        barHeight));
        bar.addView(
                close,
                new LinearLayout.LayoutParams(
                        controlWidth,
                        barHeight));

        toolbarParams =
                overlayParams(
                        Math.max(
                                dp(170),
                                bounds.width()),
                        barHeight);

        toolbarParams.x = bounds.left;
        toolbarParams.y =
                Math.max(
                        0,
                        bounds.top - barHeight);

        windowManager.addView(
                bar,
                toolbarParams);
        toolbar = bar;

        TextView resize =
                new TextView(context);
        resize.setText("↘");
        resize.setTextColor(0xFFFFFFFF);
        resize.setTextSize(18f);
        resize.setGravity(Gravity.CENTER);
        resize.setBackgroundColor(0xB0202328);
        resize.setOnTouchListener(
                (v, event) ->
                        handleResize(event));

        int handle = dp(42);
        resizeParams =
                overlayParams(
                        handle,
                        handle);
        resizeParams.x =
                Math.max(
                        0,
                        bounds.right - handle);
        resizeParams.y =
                Math.max(
                        0,
                        bounds.bottom - handle);

        windowManager.addView(
                resize,
                resizeParams);
        resizeHandle = resize;

        return true;
    }

    void updateBounds(Rect newBounds) {
        if (newBounds == null
                || newBounds.isEmpty()) {
            return;
        }

        bounds = new Rect(newBounds);

        if (toolbar != null
                && toolbarParams != null) {
            toolbarParams.width =
                    Math.max(
                            dp(170),
                            bounds.width());
            toolbarParams.x =
                    bounds.left;
            toolbarParams.y =
                    Math.max(
                            0,
                            bounds.top - dp(42));

            safeUpdate(
                    toolbar,
                    toolbarParams);
        }

        if (resizeHandle != null
                && resizeParams != null) {
            resizeParams.x =
                    Math.max(
                            0,
                            bounds.right - dp(42));
            resizeParams.y =
                    Math.max(
                            0,
                            bounds.bottom - dp(42));

            safeUpdate(
                    resizeHandle,
                    resizeParams);
        }
    }

    void show() {
        setMainControlsVisible(true);
        removeRestoreControl();
    }

    void park(boolean hidden) {
        setMainControlsVisible(false);
        showRestoreControl(hidden);
    }

    void destroy() {
        removeRestoreControl();

        if (windowManager != null) {
            if (toolbar != null) {
                try {
                    windowManager.removeViewImmediate(
                            toolbar);
                } catch (Throwable ignored) {
                }
            }

            if (resizeHandle != null) {
                try {
                    windowManager.removeViewImmediate(
                            resizeHandle);
                } catch (Throwable ignored) {
                }
            }
        }

        toolbar = null;
        resizeHandle = null;
        toolbarParams = null;
        resizeParams = null;
    }

    private boolean handleDrag(
            MotionEvent event
    ) {
        if (event == null) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                dragStartX = event.getRawX();
                dragStartY = event.getRawY();
                dragAccumX = 0;
                dragAccumY = 0;
                return true;
            }

            case MotionEvent.ACTION_MOVE -> {
                int dx =
                        Math.round(
                                event.getRawX()
                                        - dragStartX);
                int dy =
                        Math.round(
                                event.getRawY()
                                        - dragStartY);

                int stepX = dx - dragAccumX;
                int stepY = dy - dragAccumY;

                dragAccumX = dx;
                dragAccumY = dy;

                if (stepX != 0 || stepY != 0) {
                    delegate.onMoveBy(
                            stepX,
                            stepY);
                }

                return true;
            }

            case MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
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
        if (event == null) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                resizeStartX = event.getRawX();
                resizeStartY = event.getRawY();
                resizeStartWidth =
                        bounds.width();
                resizeStartHeight =
                        bounds.height();
                return true;
            }

            case MotionEvent.ACTION_MOVE -> {
                int width =
                        resizeStartWidth
                                + Math.round(
                                event.getRawX()
                                        - resizeStartX);

                int height =
                        resizeStartHeight
                                + Math.round(
                                event.getRawY()
                                        - resizeStartY);

                delegate.onResizeTo(
                        width,
                        height);
                return true;
            }

            case MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                return true;
            }

            default -> {
                return false;
            }
        }
    }

    private void setMainControlsVisible(
            boolean visible
    ) {
        int value =
                visible
                        ? View.VISIBLE
                        : View.GONE;

        if (toolbar != null) {
            toolbar.setVisibility(value);
        }

        if (resizeHandle != null) {
            resizeHandle.setVisibility(value);
        }
    }

    private void showRestoreControl(
            boolean hidden
    ) {
        removeRestoreControl();

        if (windowManager == null) return;

        Button control =
                button(
                        hidden ? "›" : "●",
                        hidden ? 18f : 16f);

        GradientDrawable bg =
                new GradientDrawable();
        bg.setColor(0xE8202328);
        bg.setCornerRadius(
                dp(hidden ? 8 : 28));
        control.setBackground(bg);

        control.setOnClickListener(
                v -> delegate.onRestore());

        int width =
                dp(hidden ? 22 : 54);
        int height = dp(54);

        restoreParams =
                overlayParams(
                        width,
                        height);

        android.util.DisplayMetrics metrics =
                context.getResources()
                        .getDisplayMetrics();

        restoreParams.x =
                Math.max(
                        0,
                        metrics.widthPixels
                                - width
                                - dp(hidden ? 0 : 8));

        restoreParams.y =
                Math.max(
                        dp(80),
                        Math.min(
                                Math.max(
                                        dp(80),
                                        metrics.heightPixels
                                                - height
                                                - dp(80)),
                                bounds.top));

        windowManager.addView(
                control,
                restoreParams);

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

    private Button button(
            String text,
            float size
    ) {
        Button button =
                new Button(context);

        button.setText(text);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(size);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setBackgroundColor(0x00000000);

        return button;
    }

    private WindowManager.LayoutParams overlayParams(
            int width,
            int height
    ) {
        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        width,
                        height,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);

        params.gravity =
                Gravity.TOP | Gravity.START;

        return params;
    }

    private void safeUpdate(
            View view,
            WindowManager.LayoutParams params
    ) {
        try {
            windowManager.updateViewLayout(
                    view,
                    params);
        } catch (Throwable ignored) {
        }
    }

    private int dp(float value) {
        return Math.round(
                value
                        * context.getResources()
                        .getDisplayMetrics()
                        .density);
    }
}
