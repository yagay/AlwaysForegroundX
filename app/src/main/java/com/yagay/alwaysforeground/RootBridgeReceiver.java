package com.yagay.alwaysforeground;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class RootBridgeReceiver extends BroadcastReceiver {
    static final String ACTION = "com.yagay.alwaysforeground.action.ROOT_BRIDGE";

    static final String OP_POLICY = "policy";
    static final String OP_WINDOW = "window";

    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_OP = "op";
    static final String EXTRA_PACKAGE = "package";
    static final String EXTRA_UID = "uid";
    static final String EXTRA_ENABLED = "enabled";

    static final String EXTRA_DOZE = "doze";
    static final String EXTRA_STANDBY = "standby";
    static final String EXTRA_APPOPS = "appops";
    static final String EXTRA_NETWORK = "network";
    static final String EXTRA_WAKELOCK = "wakelock";

    static final String EXTRA_INTENT_URI = "intent_uri";
    static final String EXTRA_USER_ID = "user_id";
    static final String EXTRA_FORM = "form";
    static final String EXTRA_WIDTH = "width";
    static final String EXTRA_HEIGHT = "height";
    static final String EXTRA_FREEFORM = "freeform";

    private static final String TAG = "MiniWindowGuard";

    @Override
    public void onReceive(Context context, Intent request) {
        if (request == null || !ACTION.equals(request.getAction())) return;
        if (!validToken(request.getStringExtra(EXTRA_TOKEN))) {
            Log.w(TAG, "Root bridge rejected: invalid token");
            return;
        }

        String packageName = request.getStringExtra(EXTRA_PACKAGE);
        int uid = request.getIntExtra(EXTRA_UID, -1);
        if (!validTarget(context, packageName, uid)) {
            Log.w(TAG, "Root bridge rejected: invalid target " + packageName + "/" + uid);
            return;
        }

        String op = request.getStringExtra(EXTRA_OP);
        PendingResult pending = goAsync();
        Intent copy = new Intent(request);
        Context app = context.getApplicationContext();

        new Thread(() -> {
            try {
                if (OP_POLICY.equals(op)) {
                    applyPolicy(copy, packageName, uid);
                } else if (OP_WINDOW.equals(op)) {
                    launchWindow(app, copy, packageName);
                } else {
                    Log.w(TAG, "Root bridge rejected: unknown op=" + op);
                }
            } finally {
                pending.finish();
            }
        }, "MWG-RootBridge").start();
    }

    private static boolean validToken(String supplied) {
        String expected = GuardApp.bridgeToken();
        if (supplied == null || expected.length() != supplied.length()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ supplied.charAt(i);
        }
        return diff == 0;
    }

    private static boolean validTarget(Context context, String packageName, int uid) {
        if (packageName == null || packageName.isEmpty() || uid < 10000) return false;
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            return info.uid == uid;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void applyPolicy(Intent request, String packageName, int uid) {
        boolean enabled = request.getBooleanExtra(EXTRA_ENABLED, true);

        if (!enabled) {
            runPolicy("cmd deviceidle whitelist -" + RootManager.quote(packageName), packageName);
            runPolicy("cmd appops set " + RootManager.quote(packageName)
                    + " RUN_ANY_IN_BACKGROUND default", packageName);
            runPolicy("cmd appops set " + RootManager.quote(packageName)
                    + " RUN_IN_BACKGROUND default", packageName);
            runPolicy("cmd appops set " + RootManager.quote(packageName)
                    + " WAKE_LOCK default", packageName);
            runPolicy("cmd netpolicy remove restrict-background-whitelist " + uid, packageName);
            Log.i(TAG, "ROOT_POLICY removed package=" + packageName + " uid=" + uid);
            return;
        }

        int success = 0;
        int attempted = 0;

        if (request.getBooleanExtra(EXTRA_DOZE, true)) {
            attempted++;
            if (runPolicy("cmd deviceidle whitelist +"
                    + RootManager.quote(packageName), packageName)) success++;
        }

        if (request.getBooleanExtra(EXTRA_STANDBY, true)) {
            attempted++;
            if (runPolicy("am set-inactive "
                    + RootManager.quote(packageName) + " false", packageName)) success++;
            attempted++;
            if (runPolicy("am set-standby-bucket "
                    + RootManager.quote(packageName) + " active", packageName)) success++;
        }

        if (request.getBooleanExtra(EXTRA_APPOPS, true)) {
            attempted++;
            if (runPolicy("cmd appops set " + RootManager.quote(packageName)
                    + " RUN_ANY_IN_BACKGROUND allow", packageName)) success++;
            attempted++;
            if (runPolicy("cmd appops set " + RootManager.quote(packageName)
                    + " RUN_IN_BACKGROUND allow", packageName)) success++;
        }

        if (request.getBooleanExtra(EXTRA_WAKELOCK, true)) {
            attempted++;
            if (runPolicy("cmd appops set " + RootManager.quote(packageName)
                    + " WAKE_LOCK allow", packageName)) success++;
        }

        if (request.getBooleanExtra(EXTRA_NETWORK, true)) {
            attempted++;
            if (runPolicy("cmd netpolicy add restrict-background-whitelist "
                    + uid, packageName)) success++;
        }

        Log.i(TAG, "ROOT_POLICY applied package=" + packageName
                + " uid=" + uid + " success=" + success + "/" + attempted);
    }

    private static boolean runPolicy(String command, String packageName) {
        StringBuilder detail = new StringBuilder();
        boolean ok = RootManager.run(command, detail);
        if (ok) {
            Log.i(TAG, "ROOT_POLICY ok command=" + command);
        } else {
            Log.i(TAG, "ROOT_POLICY skipped package=" + packageName
                    + " command=" + command + " detail=" + detail);
        }
        return ok;
    }

    private static void launchWindow(Context context, Intent request, String packageName) {
        String uri = request.getStringExtra(EXTRA_INTENT_URI);
        if (uri == null || uri.isEmpty() || uri.length() > 32000) {
            Log.w(TAG, "ROOT_WINDOW rejected: invalid intent uri");
            return;
        }

        try {
            Intent target = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            ComponentName component = target.getComponent();
            if (component != null && !packageName.equals(component.getPackageName())) {
                Log.w(TAG, "ROOT_WINDOW rejected: cross-package target=" + component);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "ROOT_WINDOW rejected: unparsable intent", t);
            return;
        }

        int userId = request.getIntExtra(EXTRA_USER_ID, 0);
        int form = ConfigKeys.sanitizeForm(
                request.getIntExtra(EXTRA_FORM, ConfigKeys.FORM_WINDOW));
        int width = ConfigKeys.sanitizePercent(
                request.getIntExtra(EXTRA_WIDTH, 58), 58);
        int height = ConfigKeys.sanitizePercent(
                request.getIntExtra(EXTRA_HEIGHT, 66), 66);
        boolean freeform = request.getBooleanExtra(EXTRA_FREEFORM, true);

        String command =
                "CLASSPATH=" + RootManager.quote(context.getApplicationInfo().sourceDir)
                + " app_process /system/bin "
                + "com.yagay.alwaysforeground.RootWindowBridge "
                + RootManager.quote(uri) + " "
                + userId + " "
                + RootManager.quote(packageName) + " "
                + form + " "
                + width + " "
                + height + " "
                + (freeform ? "1" : "0");

        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
                 BufferedReader errors = new BufferedReader(
                         new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() < 1500) out.append(line).append(" | ");
                }
                while ((line = errors.readLine()) != null) {
                    if (out.length() < 1500) out.append("ERR:").append(line).append(" | ");
                }
            }
            int exit = process.waitFor();
            Log.i(TAG, "ROOT_WINDOW exit=" + exit
                    + " package=" + packageName + " output=" + out);
        } catch (Throwable t) {
            Log.e(TAG, "ROOT_WINDOW failed package=" + packageName, t);
        } finally {
            if (process != null) process.destroy();
        }
    }
}
