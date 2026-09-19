package com.jieei.alwaysforeground;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class AlwaysForegroundApp extends Application {
    private static final String TAG = "AlwaysForeground";
    private static volatile XposedService xposedService;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureRootBridgeToken();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                xposedService = service;
                syncPendingMode(service);
                syncPendingSmallWindow(service);
                syncRootBridgeToken(service);
                syncPendingDiagnostics(service);
                Log.i(TAG, "Xposed service connected: " + service.getFrameworkName());
            }

            @Override
            public void onServiceDied(XposedService service) {
                if (xposedService == service) xposedService = null;
                Log.w(TAG, "Xposed service disconnected");
            }
        });
    }

    static boolean isServiceConnected() {
        return xposedService != null;
    }

    static int getConfiguredMode() {
        SharedPreferences local = getInstancePrefs();
        return sanitize(local.getInt(ModeConfig.KEY_MODE, ModeConfig.DEFAULT_MODE));
    }

    static boolean setConfiguredMode(int mode) {
        int safeMode = sanitize(mode);
        getInstancePrefs().edit().putInt(ModeConfig.KEY_MODE, safeMode).apply();

        XposedService service = xposedService;
        if (service == null) return false;
        try {
            return service.getRemotePreferences(ModeConfig.REMOTE_GROUP)
                    .edit()
                    .putInt(ModeConfig.KEY_MODE, safeMode)
                    .commit();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to write remote preferences", t);
            return false;
        }
    }

    static int getConfiguredSmallWindowForm() {
        SharedPreferences local = getInstancePrefs();
        int form = local.getInt(
                ModeConfig.KEY_SMALL_WINDOW_FORM,
                ModeConfig.DEFAULT_SMALL_WINDOW_FORM);
        return ModeConfig.isValidSmallWindowForm(form)
                ? form : ModeConfig.DEFAULT_SMALL_WINDOW_FORM;
    }

    static int getConfiguredSmallWindowWidth() {
        return ModeConfig.clampPercent(
                getInstancePrefs().getInt(
                        ModeConfig.KEY_SMALL_WINDOW_WIDTH,
                        ModeConfig.DEFAULT_SMALL_WINDOW_WIDTH),
                ModeConfig.DEFAULT_SMALL_WINDOW_WIDTH);
    }

    static int getConfiguredSmallWindowHeight() {
        return ModeConfig.clampPercent(
                getInstancePrefs().getInt(
                        ModeConfig.KEY_SMALL_WINDOW_HEIGHT,
                        ModeConfig.DEFAULT_SMALL_WINDOW_HEIGHT),
                ModeConfig.DEFAULT_SMALL_WINDOW_HEIGHT);
    }

    static boolean setSmallWindowConfig(int form, int width, int height) {
        int safeForm = ModeConfig.isValidSmallWindowForm(form)
                ? form : ModeConfig.DEFAULT_SMALL_WINDOW_FORM;
        int safeWidth = ModeConfig.clampPercent(
                width, ModeConfig.DEFAULT_SMALL_WINDOW_WIDTH);
        int safeHeight = ModeConfig.clampPercent(
                height, ModeConfig.DEFAULT_SMALL_WINDOW_HEIGHT);

        getInstancePrefs().edit()
                .putInt(ModeConfig.KEY_SMALL_WINDOW_FORM, safeForm)
                .putInt(ModeConfig.KEY_SMALL_WINDOW_WIDTH, safeWidth)
                .putInt(ModeConfig.KEY_SMALL_WINDOW_HEIGHT, safeHeight)
                .apply();

        XposedService service = xposedService;
        if (service == null) return false;
        return writeSmallWindow(service, safeForm, safeWidth, safeHeight);
    }

    static boolean setDiagnosticsState(boolean active, String target) {
        String safeTarget = target == null ? "" : target;
        getInstancePrefs().edit()
                .putBoolean(ModeConfig.KEY_DIAGNOSTICS_ACTIVE, active)
                .putString(ModeConfig.KEY_DIAGNOSTICS_TARGET, safeTarget)
                .apply();

        XposedService service = xposedService;
        if (service == null) return false;
        return writeDiagnostics(service, active, safeTarget);
    }

    private static void syncPendingMode(XposedService service) {
        try {
            int mode = getConfiguredMode();
            service.getRemotePreferences(ModeConfig.REMOTE_GROUP)
                    .edit()
                    .putInt(ModeConfig.KEY_MODE, mode)
                    .apply();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to sync remote preferences", t);
        }
    }

    private static void syncPendingSmallWindow(XposedService service) {
        writeSmallWindow(
                service,
                getConfiguredSmallWindowForm(),
                getConfiguredSmallWindowWidth(),
                getConfiguredSmallWindowHeight());
    }

    private static boolean writeSmallWindow(
            XposedService service,
            int form,
            int width,
            int height
    ) {
        try {
            return service.getRemotePreferences(ModeConfig.REMOTE_GROUP)
                    .edit()
                    .putInt(ModeConfig.KEY_SMALL_WINDOW_FORM, form)
                    .putInt(ModeConfig.KEY_SMALL_WINDOW_WIDTH, width)
                    .putInt(ModeConfig.KEY_SMALL_WINDOW_HEIGHT, height)
                    .commit();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to sync small-window settings", t);
            return false;
        }
    }

    static String getRootBridgeTokenLocal() {
        return ensureRootBridgeToken();
    }

    private static String ensureRootBridgeToken() {
        SharedPreferences prefs = getInstancePrefs();
        String existing = prefs.getString(ModeConfig.KEY_ROOT_BRIDGE_TOKEN, "");
        if (existing != null && !existing.isEmpty()) return existing;

        String token = java.util.UUID.randomUUID().toString()
                + "-" + Long.toHexString(new java.security.SecureRandom().nextLong());
        prefs.edit().putString(ModeConfig.KEY_ROOT_BRIDGE_TOKEN, token).commit();
        return token;
    }

    private static void syncRootBridgeToken(XposedService service) {
        try {
            service.getRemotePreferences(ModeConfig.REMOTE_GROUP)
                    .edit()
                    .putString(ModeConfig.KEY_ROOT_BRIDGE_TOKEN, ensureRootBridgeToken())
                    .commit();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to sync root bridge token", t);
        }
    }

    private static void syncPendingDiagnostics(XposedService service) {
        SharedPreferences local = getInstancePrefs();
        writeDiagnostics(service,
                local.getBoolean(ModeConfig.KEY_DIAGNOSTICS_ACTIVE, false),
                local.getString(ModeConfig.KEY_DIAGNOSTICS_TARGET, ""));
    }

    private static boolean writeDiagnostics(XposedService service, boolean active, String target) {
        try {
            return service.getRemotePreferences(ModeConfig.REMOTE_GROUP)
                    .edit()
                    .putBoolean(ModeConfig.KEY_DIAGNOSTICS_ACTIVE, active)
                    .putString(ModeConfig.KEY_DIAGNOSTICS_TARGET, target == null ? "" : target)
                    .commit();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to sync diagnostic state", t);
            return false;
        }
    }

    private static SharedPreferences getInstancePrefs() {
        AlwaysForegroundApp app = Holder.instance;
        if (app == null) throw new IllegalStateException("Application not initialized");
        return app.getSharedPreferences(ModeConfig.LOCAL_PREFS, MODE_PRIVATE);
    }

    private static int sanitize(int mode) {
        return ModeConfig.isValid(mode) ? mode : ModeConfig.DEFAULT_MODE;
    }

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        Holder.instance = this;
    }

    private static final class Holder {
        private static volatile AlwaysForegroundApp instance;
    }
}
