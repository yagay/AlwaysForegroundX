package com.jieei.alwaysforeground;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Authenticated companion-side root bridge for generic background-runtime protection.
 *
 * The injected target process only sends package/uid. Root is granted once to AlwaysForegroundX,
 * not to every target app.
 */
public final class RootForegroundReceiver extends BroadcastReceiver {
    static final String ACTION =
            "com.yagay.alwaysforeground.action.ROOT_VIRTUAL_FOREGROUND";

    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_PACKAGE = "package";
    static final String EXTRA_UID = "uid";

    private static final String TAG = "AlwaysForeground";

    @Override
    public void onReceive(Context context, Intent request) {
        if (request == null || !ACTION.equals(request.getAction())) return;

        String expected = AlwaysForegroundApp.getRootBridgeTokenLocal();
        String token = request.getStringExtra(EXTRA_TOKEN);
        if (expected.isEmpty() || token == null || !constantTimeEquals(expected, token)) {
            Log.w(TAG, "VIRTUAL_FOREGROUND root bridge rejected: bad token");
            return;
        }

        String packageName = request.getStringExtra(EXTRA_PACKAGE);
        int uid = request.getIntExtra(EXTRA_UID, -1);
        if (packageName == null || packageName.isEmpty() || uid < 10000) {
            Log.w(TAG, "VIRTUAL_FOREGROUND root bridge rejected: invalid target");
            return;
        }

        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                applyRootPolicy(packageName, uid);
            } finally {
                pending.finish();
            }
        }, "AFX-VirtualForegroundRoot").start();
    }

    private static void applyRootPolicy(String packageName, int uid) {
        // These are framework-level runtime allowances. Individual commands are best-effort
        // because vendors expose slightly different appops/netpolicy sets.
        String[] commands = new String[] {
                "cmd deviceidle whitelist +" + shellQuote(packageName),
                "am set-inactive " + shellQuote(packageName) + " false",
                "am set-standby-bucket " + shellQuote(packageName) + " active",
                "cmd appops set " + shellQuote(packageName)
                        + " RUN_ANY_IN_BACKGROUND allow",
                "cmd appops set " + shellQuote(packageName)
                        + " RUN_IN_BACKGROUND allow",
                "cmd appops set " + shellQuote(packageName)
                        + " WAKE_LOCK allow",
                "cmd netpolicy add restrict-background-whitelist " + uid
        };

        int success = 0;
        for (String command : commands) {
            if (runRoot(command, packageName)) success++;
        }

        Log.i(TAG, "VIRTUAL_FOREGROUND root policy applied"
                + " package=" + packageName
                + " uid=" + uid
                + " success=" + success + "/" + commands.length);
    }

    private static boolean runRoot(String command, String packageName) {
        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {"su", "-c", command});

            StringBuilder error = new StringBuilder();
            try (BufferedReader stderr = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = stderr.readLine()) != null) {
                    if (error.length() < 320) {
                        if (error.length() > 0) error.append(" | ");
                        error.append(line);
                    }
                }
            }

            int exit = process.waitFor();
            if (exit == 0) {
                Log.i(TAG, "VIRTUAL_FOREGROUND root ok command=" + command);
                return true;
            }

            Log.i(TAG, "VIRTUAL_FOREGROUND root skipped"
                    + " exit=" + exit
                    + " command=" + command
                    + " stderr=" + error
                    + " package=" + packageName);
        } catch (Throwable t) {
            Log.w(TAG, "VIRTUAL_FOREGROUND root failed"
                    + " command=" + command
                    + " package=" + packageName, t);
        } finally {
            if (process != null) process.destroy();
        }
        return false;
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
