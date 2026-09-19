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
    private OplusFlexibleWindowController oplusContainer;
    private VirtualDisplayController virtualContainer;
    private volatile boolean usingOplus;
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

        usingOplus =
                GuardConfig.useOplusSystemWindow();

        if (usingOplus) {
            oplusContainer =
                    new OplusFlexibleWindowController(
                            handler,
                            context,
                            this::engineLog);
            oplusContainer.start();
        } else {
            virtualContainer =
                    new VirtualDisplayController(
                            handler,
                            context,
                            this::engineLog);
            virtualContainer.start();
        }

        started = true;

        engineLog("ENGINE_START",
                "version=" + versionCode()
                        + " bootstrapApiRequired="
                        + bootstrapApiRequired()
                        + " backend="
                        + (usingOplus
                        ? "OPlusFlexibleWindow"
                        : "VirtualDisplay"));
    }

    public synchronized void stop() {
        if (!started) return;

        try {
            if (oplusContainer != null) {
                oplusContainer.shutdown();
            }
            if (virtualContainer != null) {
                virtualContainer.shutdown();
            }
        } finally {
            oplusContainer = null;
            virtualContainer = null;
            started = false;
            engineLog("ENGINE_STOP",
                    "version=" + versionCode());
        }
    }

    public long versionCode() {
        return BuildConfig.VERSION_CODE;
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
        if (usingOplus) {
            OplusFlexibleWindowController current =
                    oplusContainer;
            return current != null
                    && current.isManagedPackage(
                    packageName);
        }

        VirtualDisplayController current =
                virtualContainer;

        return current != null
                && current.isManagedPackage(
                packageName);
    }

    public boolean isManagedTopActivityRecord(
            Object activityRecord
    ) {
        if (usingOplus) {
            OplusFlexibleWindowController current =
                    oplusContainer;
            return current != null
                    && current.isManagedTopActivityRecord(
                    activityRecord);
        }

        VirtualDisplayController current =
                virtualContainer;

        return current != null
                && current.isManagedTopActivityRecord(
                activityRecord);
    }

    public int stateForPackage(String packageName) {
        if (usingOplus) {
            OplusFlexibleWindowController current =
                    oplusContainer;
            return current == null
                    ? ConfigKeys.STATE_RELEASED
                    : current.stateForPackage(
                    packageName);
        }

        VirtualDisplayController current =
                virtualContainer;

        return current == null
                ? ConfigKeys.STATE_RELEASED
                : current.stateForPackage(
                packageName);
    }

    public boolean wantsPackage(String packageName) {
        if (usingOplus) {
            OplusFlexibleWindowController current =
                    oplusContainer;
            return current != null
                    && current.wantsPackage(
                    packageName);
        }

        VirtualDisplayController current =
                virtualContainer;

        return current != null
                && current.wantsPackage(
                packageName);
    }

    public void capture(
            Object activityRecord,
            String packageName
    ) {
        if (usingOplus) {
            OplusFlexibleWindowController current =
                    oplusContainer;
            if (current != null) {
                current.capture(
                        activityRecord,
                        packageName);
            }
            return;
        }

        VirtualDisplayController current =
                virtualContainer;

        if (current != null) {
            current.capture(
                    activityRecord,
                    packageName);
        }
    }

    public String managedPackageForProcess(
            String processName
    ) {
        if (usingOplus) {
            OplusFlexibleWindowController current =
                    oplusContainer;
            return current == null
                    ? null
                    : current.managedPackageForProcess(
                    processName);
        }

        VirtualDisplayController current =
                virtualContainer;

        return current == null
                ? null
                : current.managedPackageForProcess(
                processName);
    }

    public int activeSessionCount() {
        if (usingOplus) {
            OplusFlexibleWindowController current =
                    oplusContainer;
            return current == null
                    ? 0
                    : current.activeSessionCount();
        }

        VirtualDisplayController current =
                virtualContainer;

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
