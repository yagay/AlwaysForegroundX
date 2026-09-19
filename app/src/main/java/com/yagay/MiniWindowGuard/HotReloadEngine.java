package com.yagay.MiniWindowGuard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;

/**
 * Dynamically loaded engine body.
 *
 * This class intentionally has no dependency on libxposed APIs. The fixed
 * system_server bootstrap loads it from the currently installed APK and calls
 * it only through reflection, so replacing the APK can replace this engine
 * without restarting system_server.
 */
public final class HotReloadEngine {
    private static final String TAG = "MiniWindowGuardEngine";
    private static final int BOOTSTRAP_API_REQUIRED = 1;

    private Handler handler;
    private Context context;
    private SharedPreferences prefs;
    private VirtualDisplayController container;
    private volatile boolean started;

    public HotReloadEngine() {
    }

    public synchronized void start(
            Handler handler,
            Context context,
            SharedPreferences prefs
    ) {
        if (started) return;

        this.handler = handler;
        this.context = context;
        this.prefs = prefs;

        GuardConfig.initialize(prefs);

        container = new VirtualDisplayController(
                handler,
                context,
                this::engineLog);
        container.start();

        started = true;

        engineLog("ENGINE_START",
                "version=" + versionCode()
                        + " bootstrapApiRequired="
                        + bootstrapApiRequired());
    }

    public synchronized void stop() {
        if (!started) return;

        try {
            if (container != null) {
                container.shutdown();
            }
        } finally {
            container = null;
            started = false;
            engineLog("ENGINE_STOP",
                    "version=" + versionCode());
        }
    }

    public long versionCode() {
        return 64L;
    }

    public int bootstrapApiRequired() {
        return BOOTSTRAP_API_REQUIRED;
    }

    public boolean started() {
        return started;
    }

    public boolean enabled() {
        return started && GuardConfig.enabled();
    }

    public boolean bool(String key) {
        return GuardConfig.bool(key);
    }

    public boolean isManagedPackage(String packageName) {
        VirtualDisplayController current = container;
        return current != null
                && current.isManagedPackage(packageName);
    }

    public boolean isManagedTopActivityRecord(Object activityRecord) {
        VirtualDisplayController current = container;
        return current != null
                && current.isManagedTopActivityRecord(activityRecord);
    }

    public int stateForPackage(String packageName) {
        VirtualDisplayController current = container;
        return current == null
                ? ConfigKeys.STATE_RELEASED
                : current.stateForPackage(packageName);
    }

    public boolean wantsPackage(String packageName) {
        VirtualDisplayController current = container;
        return current != null
                && current.wantsPackage(packageName);
    }

    public void capture(Object activityRecord, String packageName) {
        VirtualDisplayController current = container;
        if (current != null) {
            current.capture(activityRecord, packageName);
        }
    }

    public String managedPackageForProcess(String processName) {
        VirtualDisplayController current = container;
        return current == null
                ? null
                : current.managedPackageForProcess(processName);
    }

    public int activeSessionCount() {
        VirtualDisplayController current = container;
        return current == null
                ? 0
                : current.activeSessionCount();
    }

    private void engineLog(String event, String detail) {
        Log.i(TAG,
                event
                        + " "
                        + (detail == null ? "" : detail));
    }
}
