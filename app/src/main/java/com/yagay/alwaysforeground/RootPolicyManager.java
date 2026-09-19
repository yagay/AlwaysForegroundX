package com.yagay.alwaysforeground;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.util.Set;

final class RootPolicyManager {
    private RootPolicyManager() {}

    static void reconcile(Context context, Set<String> before, Set<String> after) {
        for (String pkg : before) {
            if (!after.contains(pkg)) remove(context, pkg);
        }
        for (String pkg : after) {
            apply(context, pkg);
        }
    }

    static void applyAll(Context context) {
        for (String pkg : GuardApp.getTargetPackages()) {
            apply(context, pkg);
        }
    }

    static void apply(Context context, String pkg) {
        if (!GuardApp.getBoolean(ConfigKeys.ROOT_KEEP_ALIVE)) return;
        int uid = uidOf(context, pkg);
        if (uid < 10000) return;

        if (GuardApp.getBoolean(ConfigKeys.ROOT_DOZE_WHITELIST)) {
            run("cmd deviceidle whitelist +" + RootManager.quote(pkg));
        }
        if (GuardApp.getBoolean(ConfigKeys.ROOT_STANDBY_ACTIVE)) {
            run("am set-inactive " + RootManager.quote(pkg) + " false");
            run("am set-standby-bucket " + RootManager.quote(pkg) + " active");
        }
        if (GuardApp.getBoolean(ConfigKeys.ROOT_BACKGROUND_APPOPS)) {
            run("cmd appops set " + RootManager.quote(pkg) + " RUN_ANY_IN_BACKGROUND allow");
            run("cmd appops set " + RootManager.quote(pkg) + " RUN_IN_BACKGROUND allow");
        }
        if (GuardApp.getBoolean(ConfigKeys.ROOT_WAKELOCK)) {
            run("cmd appops set " + RootManager.quote(pkg) + " WAKE_LOCK allow");
        }
        if (GuardApp.getBoolean(ConfigKeys.ROOT_NETWORK_WHITELIST)) {
            run("cmd netpolicy add restrict-background-whitelist " + uid);
        }
    }

    static void remove(Context context, String pkg) {
        int uid = uidOf(context, pkg);
        run("cmd deviceidle whitelist -" + RootManager.quote(pkg));
        run("cmd appops set " + RootManager.quote(pkg) + " RUN_ANY_IN_BACKGROUND default");
        run("cmd appops set " + RootManager.quote(pkg) + " RUN_IN_BACKGROUND default");
        run("cmd appops set " + RootManager.quote(pkg) + " WAKE_LOCK default");
        if (uid >= 10000) {
            run("cmd netpolicy remove restrict-background-whitelist " + uid);
        }
    }

    private static int uidOf(Context context, String pkg) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            return info.uid;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void run(String command) {
        RootManager.run(command, null);
    }
}
