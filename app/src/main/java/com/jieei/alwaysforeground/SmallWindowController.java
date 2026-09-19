package com.jieei.alwaysforeground;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Generic small-window launcher with pluggable OEM/AOSP backends.
 *
 * Backends are runtime-reflected so the module does not link against vendor framework classes.
 * Current priority:
 * 1) OPlus/ColorOS/OxygenOS Zoom Window
 * 2) Android freeform windowing mode
 *
 * PiP is intentionally not used as a launch backend because arbitrary third-party Activities must
 * opt in to PiP in their manifest. Freeform/Zoom can host ordinary Activities.
 */
final class SmallWindowController {
    private static final String TAG = "AlwaysForeground";
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int OPLUS_START_MINI_BY_THIRD_PARTY_APP = 7;

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
        Intent intent = new Intent(original);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);

        LaunchResult oplus = launchOplusZoom(context, intent);
        if (oplus.handled) return oplus;

        LaunchResult freeform = launchAospFreeform(context, intent);
        if (freeform.handled) return freeform;

        return LaunchResult.no("oplus=" + oplus.detail + "; freeform=" + freeform.detail);
    }

    private LaunchResult launchOplusZoom(Context context, Intent intent) {
        try {
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

            Bundle options = ActivityOptions.makeBasic().toBundle();
            Object raw = start.invoke(
                    manager,
                    intent,
                    options,
                    android.os.UserHandle.myUserId(),
                    context.getPackageName());

            int result = raw instanceof Number ? ((Number) raw).intValue() : -1;
            // OEM implementations historically return >= 0 for an accepted request.
            if (result < 0) {
                return LaunchResult.no("OPlus startZoomWindow result=" + result);
            }

            Log.i(TAG, "GENERIC_SMALL_WINDOW launched"
                    + " backend=oplus_zoom"
                    + " result=" + result
                    + " target=" + intent.getComponent()
                    + " package=" + context.getPackageName());

            // Best effort: collapse Zoom to the system mini/icon state after the task exists.
            try {
                Method mini = managerClass.getMethod("startMiniZoomFromZoom", int.class);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        mini.invoke(manager, OPLUS_START_MINI_BY_THIRD_PARTY_APP);
                        Log.i(TAG, "GENERIC_SMALL_WINDOW minimized"
                                + " backend=oplus_mini"
                                + " startWay=" + OPLUS_START_MINI_BY_THIRD_PARTY_APP
                                + " package=" + context.getPackageName());
                    } catch (Throwable t) {
                        Log.w(TAG, "GENERIC_SMALL_WINDOW mini failed package="
                                + context.getPackageName(), unwrap(t));
                    }
                }, 350L);
            } catch (Throwable t) {
                Log.i(TAG, "GENERIC_SMALL_WINDOW mini unavailable package="
                        + context.getPackageName() + " error=" + unwrap(t));
            }

            return new LaunchResult(true, "oplus_zoom", "result=" + result);
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            Log.i(TAG, "GENERIC_SMALL_WINDOW backend unavailable"
                    + " backend=oplus_zoom"
                    + " package=" + context.getPackageName()
                    + " error=" + root);
            return LaunchResult.no(root.toString());
        }
    }

    private LaunchResult launchAospFreeform(Context context, Intent intent) {
        try {
            ActivityOptions options = ActivityOptions.makeBasic();

            Method setMode = ActivityOptions.class.getDeclaredMethod(
                    "setLaunchWindowingMode", int.class);
            setMode.setAccessible(true);
            setMode.invoke(options, WINDOWING_MODE_FREEFORM);

            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int width = Math.max(420, Math.round(dm.widthPixels * 0.58f));
            int height = Math.max(680, Math.round(dm.heightPixels * 0.66f));
            int left = Math.max(0, dm.widthPixels - width - Math.round(24 * dm.density));
            int top = Math.max(0, Math.round(84 * dm.density));
            options.setLaunchBounds(new Rect(left, top, left + width, top + height));

            context.startActivity(intent, options.toBundle());

            Log.i(TAG, "GENERIC_SMALL_WINDOW launched"
                    + " backend=aosp_freeform"
                    + " bounds=" + left + "," + top + "," + (left + width) + "," + (top + height)
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

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof java.lang.reflect.InvocationTargetException
                && ((java.lang.reflect.InvocationTargetException) cur).getCause() != null) {
            cur = ((java.lang.reflect.InvocationTargetException) cur).getCause();
        }
        return cur;
    }
}
