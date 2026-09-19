package com.yagay.MiniWindowGuard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

final class WindowLauncher {
    private static final String TAG = "MiniWindowGuard";

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
                    + "com.yagay.MiniWindowGuard.RootWindowBridge "
                    + RootManager.quote(uri) + " "
                    + userId + " "
                    + RootManager.quote(packageName) + " "
                    + ConfigKeys.sanitizeForm(GuardApp.getInt(ConfigKeys.SMALL_WINDOW_FORM)) + " "
                    + ConfigKeys.sanitizePercent(
                            GuardApp.getInt(ConfigKeys.SMALL_WINDOW_WIDTH), 58) + " "
                    + ConfigKeys.sanitizePercent(
                            GuardApp.getInt(ConfigKeys.SMALL_WINDOW_HEIGHT), 66) + " "
                    + (GuardApp.getBoolean(ConfigKeys.AOSP_FREEFORM_FALLBACK) ? "1" : "0");

            StringBuilder detail = GuardApp.getBoolean(ConfigKeys.DIAGNOSTICS_ACTIVE)
                    ? new StringBuilder()
                    : null;
            boolean ok = RootManager.run(command, detail);

            if (detail != null) {
                Log.i(TAG, "DIAG_ROOT_WINDOW"
                        + " package=" + packageName
                        + " form=" + GuardApp.getInt(ConfigKeys.SMALL_WINDOW_FORM)
                        + " size=" + GuardApp.getInt(ConfigKeys.SMALL_WINDOW_WIDTH)
                        + "x" + GuardApp.getInt(ConfigKeys.SMALL_WINDOW_HEIGHT)
                        + " ok=" + ok
                        + " result=" + compact(detail.toString()));
            }
            return ok;
        } catch (Throwable t) {
            if (GuardApp.getBoolean(ConfigKeys.DIAGNOSTICS_ACTIVE)) {
                Log.e(TAG, "DIAG_ROOT_WINDOW failed package=" + packageName, t);
            }
            return false;
        }
    }

    private static String compact(String value) {
        if (value == null) return "";
        String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() <= 1800 ? compact : compact.substring(0, 1800);
    }
}
