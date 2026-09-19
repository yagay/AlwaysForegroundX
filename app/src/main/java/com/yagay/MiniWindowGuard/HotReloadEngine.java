package com.yagay.MiniWindowGuard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;

/**
 * Dynamically loaded OPlus flexible-window engine.
 *
 * Window creation, rendering, resize, minimize and input are all owned by
 * OxygenOS/ColorOS. MiniWindowGuard only requests/observes the OEM window and
 * exposes its live state to the fixed system_server bootstrap.
 */
public final class HotReloadEngine {
    private static final String TAG = "MiniWindowGuardEngine";
    private static final int BOOTSTRAP_API_REQUIRED = 1;

    private OplusFlexibleWindowController controller;
    private volatile boolean started;

    public HotReloadEngine() {}

    public synchronized void start(
            Handler handler,
            Context context,
            SharedPreferences prefs
    ) {
        if (started) return;

        GuardConfig.initialize(prefs);

        controller = new OplusFlexibleWindowController(
                handler,
                context,
                this::engineLog);
        controller.start();

        started = true;

        engineLog(
                "ENGINE_START",
                "version=" + versionCode()
                        + " backend=OPlusFlexibleWindow"
                        + " bootstrapApiRequired="
                        + bootstrapApiRequired());
    }

    public synchronized void stop() {
        if (!started) return;

        try {
            if (controller != null) {
                controller.shutdown();
            }
        } finally {
            controller = null;
            started = false;
            engineLog(
                    "ENGINE_STOP",
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

    public boolean wantsPackage(String packageName) {
        OplusFlexibleWindowController current = controller;
        return current != null
                && current.wantsPackage(packageName);
    }

    public boolean isKnownPackage(String packageName) {
        OplusFlexibleWindowController current = controller;
        return current != null
                && current.isKnownPackage(packageName);
    }

    public boolean isForceSupportPackage(String packageName) {
        OplusFlexibleWindowController current = controller;
        return current != null
                && current.isForceSupportPackage(packageName);
    }

    public boolean isForegroundPackage(String packageName) {
        OplusFlexibleWindowController current = controller;
        return current != null
                && current.isForegroundPackage(packageName);
    }

    public boolean isManagedPackage(String packageName) {
        OplusFlexibleWindowController current = controller;
        return current != null
                && current.isProtectedPackage(packageName);
    }

    public void capture(
            Object activityRecord,
            String packageName
    ) {
        OplusFlexibleWindowController current = controller;
        if (current != null) {
            current.capture(
                    activityRecord,
                    packageName);
        }
    }

    public void onOplusTaskInfoChanged(Object taskInfo) {
        OplusFlexibleWindowController current = controller;
        if (current != null) {
            current.onOplusTaskInfoChanged(taskInfo);
        }
    }

    public void onOplusTaskVanished(Object taskInfo) {
        OplusFlexibleWindowController current = controller;
        if (current != null) {
            current.onOplusTaskVanished(taskInfo);
        }
    }

    public void onKeyguardStateChanged(boolean showing) {
        OplusFlexibleWindowController current = controller;
        if (current != null) {
            current.onKeyguardStateChanged(showing);
        }
    }

    public boolean shouldHoldEdgeTask(Object task) {
        OplusFlexibleWindowController current = controller;
        return current != null
                && current.shouldHoldEdgeTask(task);
    }

    public boolean isEdgeHungTask(Object task) {
        OplusFlexibleWindowController current = controller;
        return current != null
                && current.isEdgeHungTask(task);
    }

    public boolean shouldKeepTaskAwake(Object task) {
        OplusFlexibleWindowController current = controller;
        return current != null
                && current.shouldKeepTaskAwake(task);
    }

    public String managedPackageForProcess(String processName) {
        OplusFlexibleWindowController current = controller;
        return current == null
                ? null
                : current.protectedPackageForProcess(
                processName);
    }

    public int activeSessionCount() {
        OplusFlexibleWindowController current = controller;
        return current == null
                ? 0
                : current.activeSessionCount();
    }

    private void engineLog(String event, String detail) {
        Log.i(
                TAG,
                event + " "
                        + (detail == null ? "" : detail));
    }
}
