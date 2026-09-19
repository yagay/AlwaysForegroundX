package com.jieei.alwaysforeground;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Generic small-window launcher.
 *
 * Priority:
 * 1) Root bridge -> OEM privileged Zoom/Flexible Window APIs.
 * 2) Direct OEM reflection (works on ROMs that allow the target UID).
 * 3) Android freeform ActivityOptions.
 *
 * LSPosed owns interception/decision; root owns privileged window-manager execution.
 */
final class SmallWindowController {
    private static final String TAG = "AlwaysForeground";
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int OPLUS_START_MINI_BY_THIRD_PARTY_APP = 7;
    private static final int OPLUS_HIDE_ZOOM_WINDOW = 2;

    static final class LaunchResult {
        final boolean handled;
        final String backend;
        final String detail;

        LaunchResult(boolean handled, String backend, String detail) {
            this.handled = handled;
            this.backend = backend;
            this.detail = detail;
        }

        static LaunchResult no(String detail) {
            return new LaunchResult(false, "none", detail);
        }
    }

    LaunchResult launch(Context context, Intent original) {
        int form = TargetConfig.getSmallWindowForm();
        int width = TargetConfig.getSmallWindowWidthPercent();
        int height = TargetConfig.getSmallWindowHeightPercent();

        LaunchResult root = launchRootBridge(context, original, form, width, height);
        if (root.handled) return root;

        LaunchResult oplus = launchOplusDirect(context, original, form, width, height);
        if (oplus.handled) return oplus;

        LaunchResult freeform = launchAospFreeform(context, original, width, height);
        if (freeform.handled) return freeform;

        return LaunchResult.no("root=" + root.detail
                + "; oplus=" + oplus.detail
                + "; freeform=" + freeform.detail);
    }

    /**
     * Run the OEM API from uid 0 so OPlus permission checks are satisfied without weakening
     * system_server permission enforcement.
     */
    private LaunchResult launchRootBridge(
            Context context,
            Intent original,
            int form,
            int widthPercent,
            int heightPercent
    ) {
        try {
            String uri = original.toUri(Intent.URI_INTENT_SCHEME);
            if (uri.length() > 24000) {
                return LaunchResult.no("intent-uri-too-large:" + uri.length());
            }

            String token = TargetConfig.getRootBridgeToken();
            if (token == null || token.isEmpty()) {
                return LaunchResult.no("root-bridge-token-unavailable");
            }

            int userId = Math.max(0, context.getApplicationInfo().uid / 100000);
            Intent request = new Intent(RootSmallWindowReceiver.ACTION);
            request.setClassName(
                    "com.yagay.alwaysforeground",
                    "com.jieei.alwaysforeground.RootSmallWindowReceiver");
            request.putExtra(RootSmallWindowReceiver.EXTRA_TOKEN, token);
            request.putExtra(RootSmallWindowReceiver.EXTRA_INTENT_URI, uri);
            request.putExtra(RootSmallWindowReceiver.EXTRA_USER_ID, userId);
            request.putExtra(RootSmallWindowReceiver.EXTRA_CALLER, context.getPackageName());
            request.putExtra(RootSmallWindowReceiver.EXTRA_FORM, form);
            request.putExtra(RootSmallWindowReceiver.EXTRA_WIDTH, widthPercent);
            request.putExtra(RootSmallWindowReceiver.EXTRA_HEIGHT, heightPercent);

            context.sendBroadcast(request);

            Log.i(TAG, "GENERIC_SMALL_WINDOW bridge dispatched"
                    + " form=" + form
                    + " width=" + widthPercent
                    + " height=" + heightPercent
                    + " target=" + original.getComponent()
                    + " package=" + context.getPackageName());

            return new LaunchResult(
                    true,
                    "root_bridge",
                    "form=" + form + ",size=" + widthPercent + "x" + heightPercent);
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            Log.i(TAG, "GENERIC_SMALL_WINDOW backend unavailable"
                    + " backend=root_bridge"
                    + " package=" + context.getPackageName()
                    + " error=" + root);
            return LaunchResult.no(root.toString());
        }
    }

    private LaunchResult launchOplusDirect(
            Context context,
            Intent original,
            int form,
            int widthPercent,
            int heightPercent
    ) {
        try {
            Intent intent = new Intent(original);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);

            Class<?> managerClass = Class.forName(
                    "com.oplus.zoomwindow.OplusZoomWindowManager",
                    false,
                    context.getClassLoader());
            Method getInstance = managerClass.getMethod("getInstance");
            Object manager = getInstance.invoke(null);
            if (manager == null) return LaunchResult.no("OPlus manager=null");

            Method start = managerClass.getMethod(
                    "startZoomWindow",
                    Intent.class,
                    Bundle.class,
                    int.class,
                    String.class);

            Bundle options = makeOptions(context, widthPercent, heightPercent).toBundle();
            Object raw = start.invoke(
                    manager,
                    intent,
                    options,
                    Math.max(0, context.getApplicationInfo().uid / 100000),
                    context.getPackageName());

            int result = raw instanceof Number ? ((Number) raw).intValue() : -1;
            if (result < 0) {
                return LaunchResult.no("OPlus startZoomWindow result=" + result);
            }

            applyDirectForm(managerClass, manager, form, context.getPackageName());

            Log.i(TAG, "GENERIC_SMALL_WINDOW launched"
                    + " backend=oplus_direct"
                    + " result=" + result
                    + " form=" + form
                    + " target=" + intent.getComponent()
                    + " package=" + context.getPackageName());
            return new LaunchResult(true, "oplus_direct", "result=" + result);
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            Log.i(TAG, "GENERIC_SMALL_WINDOW backend unavailable"
                    + " backend=oplus_direct"
                    + " package=" + context.getPackageName()
                    + " error=" + root);
            return LaunchResult.no(root.toString());
        }
    }

    private void applyDirectForm(
            Class<?> managerClass,
            Object manager,
            int form,
            String packageName
    ) {
        if (form == ModeConfig.SMALL_WINDOW_FORM_WINDOW) return;

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (form == ModeConfig.SMALL_WINDOW_FORM_ICON) {
                    Method mini = managerClass.getMethod("startMiniZoomFromZoom", int.class);
                    mini.invoke(manager, OPLUS_START_MINI_BY_THIRD_PARTY_APP);
                    Log.i(TAG, "GENERIC_SMALL_WINDOW state=icon backend=oplus_direct"
                            + " package=" + packageName);
                    return;
                }

                if (form == ModeConfig.SMALL_WINDOW_FORM_HIDDEN) {
                    try {
                        Method mini = managerClass.getMethod(
                                "startMiniZoomFromZoom", int.class);
                        mini.invoke(manager, OPLUS_START_MINI_BY_THIRD_PARTY_APP);
                    } catch (Throwable ignored) {
                    }

                    Method hide = managerClass.getMethod("hideZoomWindow", int.class);
                    hide.invoke(manager, OPLUS_HIDE_ZOOM_WINDOW);
                    Log.i(TAG, "GENERIC_SMALL_WINDOW state=hidden backend=oplus_direct"
                            + " package=" + packageName);
                }
            } catch (Throwable t) {
                Log.w(TAG, "GENERIC_SMALL_WINDOW direct state failed package="
                        + packageName, unwrap(t));
            }
        }, 420L);
    }

    private LaunchResult launchAospFreeform(
            Context context,
            Intent original,
            int widthPercent,
            int heightPercent
    ) {
        try {
            Intent intent = new Intent(original);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);

            ActivityOptions options = makeOptions(context, widthPercent, heightPercent);
            Method setMode = ActivityOptions.class.getDeclaredMethod(
                    "setLaunchWindowingMode", int.class);
            setMode.setAccessible(true);
            setMode.invoke(options, WINDOWING_MODE_FREEFORM);

            context.startActivity(intent, options.toBundle());

            Log.i(TAG, "GENERIC_SMALL_WINDOW launched"
                    + " backend=aosp_freeform"
                    + " size=" + widthPercent + "x" + heightPercent
                    + " target=" + intent.getComponent()
                    + " package=" + context.getPackageName());
            return new LaunchResult(true, "aosp_freeform", "ok");
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            Log.i(TAG, "GENERIC_SMALL_WINDOW backend unavailable"
                    + " backend=aosp_freeform"
                    + " package=" + context.getPackageName()
                    + " error=" + root);
            return LaunchResult.no(root.toString());
        }
    }

    private static ActivityOptions makeOptions(
            Context context,
            int widthPercent,
            int heightPercent
    ) {
        ActivityOptions options = ActivityOptions.makeBasic();
        try {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int width = Math.max(
                    Math.round(360 * dm.density),
                    Math.round(dm.widthPixels * widthPercent / 100f));
            int height = Math.max(
                    Math.round(520 * dm.density),
                    Math.round(dm.heightPixels * heightPercent / 100f));

            width = Math.min(width, dm.widthPixels);
            height = Math.min(height, dm.heightPixels);

            int margin = Math.round(20 * dm.density);
            int left = Math.max(0, dm.widthPixels - width - margin);
            int top = Math.max(0, Math.round(72 * dm.density));
            if (top + height > dm.heightPixels) {
                top = Math.max(0, dm.heightPixels - height - margin);
            }

            options.setLaunchBounds(new Rect(left, top, left + width, top + height));
        } catch (Throwable ignored) {
        }
        return options;
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof java.lang.reflect.InvocationTargetException
                && ((java.lang.reflect.InvocationTargetException) cur).getCause() != null) {
            cur = ((java.lang.reflect.InvocationTargetException) cur).getCause();
        }
        return cur;
    }
}
