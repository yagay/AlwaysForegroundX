package com.yagay.alwaysforeground;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.DisplayMetrics;

import java.lang.reflect.Method;

public final class RootWindowBridge {
    private static final int OPLUS_MINI_START_WAY = 7;
    private static final int OPLUS_HIDE_FLAG = 2;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private RootWindowBridge() {}

    public static void main(String[] args) {
        if (args == null || args.length < 7) {
            out("error=bad-args");
            return;
        }

        try {
            Intent intent = Intent.parseUri(args[0], Intent.URI_INTENT_SCHEME);
            int userId = Integer.parseInt(args[1]);
            String callerPackage = args[2];
            int form = ConfigKeys.sanitizeForm(Integer.parseInt(args[3]));
            int width = ConfigKeys.sanitizePercent(Integer.parseInt(args[4]), 58);
            int height = ConfigKeys.sanitizePercent(Integer.parseInt(args[5]), 66);
            boolean freeform = "1".equals(args[6]);

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            Bundle options = makeOptions(width, height).toBundle();

            Throwable oplusError = null;
            try {
                Class<?> managerClass = Class.forName(
                        "com.oplus.zoomwindow.OplusZoomWindowManager");
                Object manager = managerClass.getMethod("getInstance").invoke(null);
                if (manager != null) {
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
                    out("backend=oplus result=" + result + " form=" + form);

                    if (result >= 0) {
                        if (form == ConfigKeys.FORM_ICON) {
                            sleep(420);
                            managerClass.getMethod(
                                    "startMiniZoomFromZoom", int.class)
                                    .invoke(manager, OPLUS_MINI_START_WAY);
                            out("state=icon");
                        } else if (form == ConfigKeys.FORM_HIDDEN) {
                            try {
                                sleep(320);
                                managerClass.getMethod(
                                        "startMiniZoomFromZoom", int.class)
                                        .invoke(manager, OPLUS_MINI_START_WAY);
                            } catch (Throwable ignored) {
                            }
                            sleep(260);
                            managerClass.getMethod("hideZoomWindow", int.class)
                                    .invoke(manager, OPLUS_HIDE_FLAG);
                            out("state=hidden");
                        }
                        return;
                    }
                }
            } catch (Throwable t) {
                oplusError = unwrap(t);
                out("oplus-error=" + oplusError);
            }

            if (!freeform) {
                out("error=no-oem-backend"
                        + (oplusError == null ? "" : " " + oplusError));
                return;
            }

            launchAospFreeform(intent, width, height);
            out("backend=aosp-freeform");
        } catch (Throwable t) {
            out("error=" + unwrap(t));
        }
    }

    private static void launchAospFreeform(
            Intent intent,
            int widthPercent,
            int heightPercent
    ) throws Exception {
        ActivityOptions options = makeOptions(widthPercent, heightPercent);
        Method setMode = ActivityOptions.class.getDeclaredMethod(
                "setLaunchWindowingMode", int.class);
        setMode.setAccessible(true);
        setMode.invoke(options, WINDOWING_MODE_FREEFORM);

        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);

        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Context context = (Context) getSystemContext.invoke(thread);
        context.startActivity(intent, options.toBundle());
    }

    private static ActivityOptions makeOptions(int widthPercent, int heightPercent) {
        ActivityOptions options = ActivityOptions.makeBasic();
        try {
            DisplayMetrics dm = android.content.res.Resources
                    .getSystem().getDisplayMetrics();
            int width = Math.max(
                    Math.round(320 * dm.density),
                    Math.round(dm.widthPixels * widthPercent / 100f));
            int height = Math.max(
                    Math.round(480 * dm.density),
                    Math.round(dm.heightPixels * heightPercent / 100f));
            width = Math.min(width, dm.widthPixels);
            height = Math.min(height, dm.heightPixels);

            int margin = Math.round(18 * dm.density);
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
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

    private static void out(String value) {
        System.out.println("MWG_ROOT_WINDOW " + value);
    }
}
