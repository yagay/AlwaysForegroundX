package com.jieei.alwaysforeground;

import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;

import java.lang.reflect.Method;

/**
 * Privileged helper launched through:
 *   su -c CLASSPATH=<module.apk> app_process /system/bin ...RootSmallWindowBridge
 *
 * The Binder caller is therefore root instead of the target app UID, allowing OEM window-manager
 * APIs to enforce their normal privileged permission checks without weakening system_server.
 */
public final class RootSmallWindowBridge {
    private static final int FORM_WINDOW = ModeConfig.SMALL_WINDOW_FORM_WINDOW;
    private static final int FORM_ICON = ModeConfig.SMALL_WINDOW_FORM_ICON;
    private static final int FORM_HIDDEN = ModeConfig.SMALL_WINDOW_FORM_HIDDEN;

    private static final int OPLUS_START_MINI_BY_THIRD_PARTY_APP = 7;
    private static final int OPLUS_HIDE_ZOOM_WINDOW = 2;

    private RootSmallWindowBridge() {}

    public static void main(String[] args) {
        if (args == null || args.length < 6) {
            out("error=bad-args count=" + (args == null ? -1 : args.length));
            return;
        }

        try {
            String intentUri = args[0];
            int userId = Integer.parseInt(args[1]);
            String callerPackage = args[2];
            int form = Integer.parseInt(args[3]);
            int widthPercent = Integer.parseInt(args[4]);
            int heightPercent = Integer.parseInt(args[5]);

            Intent intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);

            Bundle options = buildOptions(widthPercent, heightPercent);

            Class<?> managerClass = Class.forName(
                    "com.oplus.zoomwindow.OplusZoomWindowManager");
            Object manager = managerClass.getMethod("getInstance").invoke(null);
            if (manager == null) {
                out("error=oplus-manager-null");
                return;
            }

            Method start = managerClass.getMethod(
                    "startZoomWindow",
                    Intent.class,
                    Bundle.class,
                    int.class,
                    String.class);

            Object raw = start.invoke(
                    manager,
                    intent,
                    options,
                    userId,
                    callerPackage);
            int result = raw instanceof Number ? ((Number) raw).intValue() : -1;

            out("launched backend=oplus_zoom result=" + result
                    + " form=" + form
                    + " user=" + userId
                    + " caller=" + callerPackage);

            if (result < 0) return;

            if (form == FORM_ICON) {
                sleep(420L);
                Method mini = managerClass.getMethod("startMiniZoomFromZoom", int.class);
                mini.invoke(manager, OPLUS_START_MINI_BY_THIRD_PARTY_APP);
                out("state=icon action=startMiniZoomFromZoom");
                return;
            }

            if (form == FORM_HIDDEN) {
                // Enter mini first so the task is fully detached from fullscreen/zoom chrome,
                // then hide the mini/zoom surface and handle. The task itself remains alive.
                try {
                    sleep(320L);
                    Method mini = managerClass.getMethod("startMiniZoomFromZoom", int.class);
                    mini.invoke(manager, OPLUS_START_MINI_BY_THIRD_PARTY_APP);
                    out("state=mini-before-hide");
                } catch (Throwable ignored) {
                    out("state=mini-before-hide-unavailable");
                }

                sleep(260L);
                Method hide = managerClass.getMethod("hideZoomWindow", int.class);
                hide.invoke(manager, OPLUS_HIDE_ZOOM_WINDOW);
                out("state=hidden action=hideZoomWindow flag=" + OPLUS_HIDE_ZOOM_WINDOW);
            }
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            out("error=" + root.getClass().getName() + ":" + String.valueOf(root.getMessage()));
        }
    }

    private static Bundle buildOptions(int widthPercent, int heightPercent) {
        ActivityOptions options = ActivityOptions.makeBasic();
        try {
            android.util.DisplayMetrics dm =
                    android.content.res.Resources.getSystem().getDisplayMetrics();

            int width = Math.max(
                    Math.round(360 * dm.density),
                    Math.round(dm.widthPixels * clamp(widthPercent) / 100f));
            int height = Math.max(
                    Math.round(520 * dm.density),
                    Math.round(dm.heightPixels * clamp(heightPercent) / 100f));

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
        return options.toBundle();
    }

    private static int clamp(int value) {
        return Math.max(30, Math.min(100, value));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private static void out(String message) {
        System.out.println("AFX_ROOT_SMALL_WINDOW " + message);
    }
}
