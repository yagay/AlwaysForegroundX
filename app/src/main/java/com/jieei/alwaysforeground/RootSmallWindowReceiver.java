package com.jieei.alwaysforeground;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Exported only as a narrow authenticated IPC bridge from LSPosed-injected target processes.
 * The target app never receives root. Only the AlwaysForegroundX companion process executes su.
 */
public final class RootSmallWindowReceiver extends BroadcastReceiver {
    static final String ACTION = "com.yagay.alwaysforeground.action.ROOT_SMALL_WINDOW";

    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_INTENT_URI = "intent_uri";
    static final String EXTRA_USER_ID = "user_id";
    static final String EXTRA_CALLER = "caller";
    static final String EXTRA_FORM = "form";
    static final String EXTRA_WIDTH = "width";
    static final String EXTRA_HEIGHT = "height";

    private static final String TAG = "AlwaysForeground";

    @Override
    public void onReceive(Context context, Intent request) {
        if (request == null || !ACTION.equals(request.getAction())) return;

        String expected = AlwaysForegroundApp.getRootBridgeTokenLocal();
        String token = request.getStringExtra(EXTRA_TOKEN);
        if (expected.isEmpty() || token == null || !constantTimeEquals(expected, token)) {
            Log.w(TAG, "GENERIC_SMALL_WINDOW bridge rejected: bad token");
            return;
        }

        String intentUri = request.getStringExtra(EXTRA_INTENT_URI);
        String caller = request.getStringExtra(EXTRA_CALLER);
        int userId = request.getIntExtra(EXTRA_USER_ID, 0);
        int form = request.getIntExtra(
                EXTRA_FORM, ModeConfig.DEFAULT_SMALL_WINDOW_FORM);
        int width = ModeConfig.clampPercent(
                request.getIntExtra(
                        EXTRA_WIDTH, ModeConfig.DEFAULT_SMALL_WINDOW_WIDTH),
                ModeConfig.DEFAULT_SMALL_WINDOW_WIDTH);
        int height = ModeConfig.clampPercent(
                request.getIntExtra(
                        EXTRA_HEIGHT, ModeConfig.DEFAULT_SMALL_WINDOW_HEIGHT),
                ModeConfig.DEFAULT_SMALL_WINDOW_HEIGHT);

        if (intentUri == null || intentUri.isEmpty() || intentUri.length() > 24000) {
            Log.w(TAG, "GENERIC_SMALL_WINDOW bridge rejected: invalid intent uri");
            return;
        }
        if (caller == null || caller.isEmpty()) {
            Log.w(TAG, "GENERIC_SMALL_WINDOW bridge rejected: invalid caller");
            return;
        }

        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                executeRootBridge(app, intentUri, userId, caller, form, width, height);
            } finally {
                pending.finish();
            }
        }, "AFX-RootBridgeReceiver").start();
    }

    private static void executeRootBridge(
            Context context,
            String intentUri,
            int userId,
            String caller,
            int form,
            int width,
            int height
    ) {
        try {
            String apk = context.getApplicationInfo().sourceDir;
            String command =
                    "CLASSPATH=" + shellQuote(apk)
                    + " app_process /system/bin "
                    + "com.jieei.alwaysforeground.RootSmallWindowBridge "
                    + shellQuote(intentUri) + " "
                    + userId + " "
                    + shellQuote(caller) + " "
                    + form + " "
                    + width + " "
                    + height;

            java.lang.Process process = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", command});

            try (BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
                 BufferedReader stderr = new BufferedReader(
                         new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = stdout.readLine()) != null) {
                    Log.i(TAG, "GENERIC_SMALL_WINDOW root " + line);
                }
                while ((line = stderr.readLine()) != null) {
                    Log.w(TAG, "GENERIC_SMALL_WINDOW root stderr=" + line);
                }
            }

            int exit = process.waitFor();
            Log.i(TAG, "GENERIC_SMALL_WINDOW root exit=" + exit
                    + " caller=" + caller
                    + " form=" + form
                    + " size=" + width + "x" + height);
        } catch (Throwable t) {
            Log.e(TAG, "GENERIC_SMALL_WINDOW root bridge failed caller=" + caller, t);
        }
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
