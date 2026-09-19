package com.yagay.alwaysforeground;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

final class WindowLauncher {
    private WindowLauncher() {}

    static boolean launch(Context context, String packageName) {
        try {
            Intent target = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (target == null) return false;

            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            String uri = target.toUri(Intent.URI_INTENT_SCHEME);
            if (uri.length() > 32000) return false;

            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            int userId = Math.max(0, info.uid / 100000);

            String command =
                    "CLASSPATH=" + RootManager.quote(context.getApplicationInfo().sourceDir)
                    + " app_process /system/bin "
                    + "com.yagay.alwaysforeground.RootWindowBridge "
                    + RootManager.quote(uri) + " "
                    + userId + " "
                    + RootManager.quote(packageName) + " "
                    + ConfigKeys.sanitizeForm(GuardApp.getInt(ConfigKeys.SMALL_WINDOW_FORM)) + " "
                    + ConfigKeys.sanitizePercent(
                            GuardApp.getInt(ConfigKeys.SMALL_WINDOW_WIDTH), 58) + " "
                    + ConfigKeys.sanitizePercent(
                            GuardApp.getInt(ConfigKeys.SMALL_WINDOW_HEIGHT), 66) + " "
                    + (GuardApp.getBoolean(ConfigKeys.AOSP_FREEFORM_FALLBACK) ? "1" : "0");

            return RootManager.run(command, null);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
