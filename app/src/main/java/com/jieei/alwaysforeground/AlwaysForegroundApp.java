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
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                xposedService = service;
                syncPendingMode(service);
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

    static boolean setDiagnosticsState(boolean active, String target, long startMs) {
        SharedPreferences local = getInstancePrefs();
        local.edit()
                .putBoolean(ModeConfig.KEY_DIAGNOSTICS_ACTIVE, active)
                .putString(ModeConfig.KEY_DIAGNOSTICS_TARGET, target == null ? "" : target)
                .putLong(ModeConfig.KEY_DIAGNOSTICS_START_MS, startMs)
                .apply();
        XposedService service = xposedService;
        if (service == null) return false;
        return writeDiagnostics(service, active, target, startMs);
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

    private static void syncPendingDiagnostics(XposedService service) {
        SharedPreferences local = getInstancePrefs();
        writeDiagnostics(service,
                local.getBoolean(ModeConfig.KEY_DIAGNOSTICS_ACTIVE, false),
                local.getString(ModeConfig.KEY_DIAGNOSTICS_TARGET, ""),
                local.getLong(ModeConfig.KEY_DIAGNOSTICS_START_MS, 0L));
    }

    private static boolean writeDiagnostics(XposedService service, boolean active,
                                            String target, long startMs) {
        try {
            return service.getRemotePreferences(ModeConfig.REMOTE_GROUP)
                    .edit()
                    .putBoolean(ModeConfig.KEY_DIAGNOSTICS_ACTIVE, active)
                    .putString(ModeConfig.KEY_DIAGNOSTICS_TARGET, target == null ? "" : target)
                    .putLong(ModeConfig.KEY_DIAGNOSTICS_START_MS, startMs)
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
