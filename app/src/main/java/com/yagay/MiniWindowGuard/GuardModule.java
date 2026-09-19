package com.yagay.MiniWindowGuard;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * System-scope engine.
 *
 * This module is loaded only into Android/System Framework (system_server). Target apps are never
 * injected. The protected package list comes from MiniWindow Guard's own UI.
 */
public final class GuardModule extends XposedModule {
    private static final String TAG = "MiniWindowGuard";
    private static final String SYSTEM_PACKAGE = "android";
    // android.app.PROCESS_STATE_TOP is hidden from the public SDK.
    private static final int PROCESS_STATE_TOP = 2;

    private final Set<String> installedHooks = ConcurrentHashMap.newKeySet();
    private final Set<String> firstHits = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Integer, String[]> uidPackages = new ConcurrentHashMap<>();

    private volatile ClassLoader systemClassLoader;

    private volatile Object oplusZoomManager;
    private volatile Method getCurrentZoomWindowState;
    private volatile Field zoomPkgField;
    private volatile Field windowTypeField;
    private volatile Field windowShownField;
    private volatile Field zoomRectField;
    private volatile Field cpnNameField;
    private volatile Field cvActionFlagField;
    private volatile Field lastExitMethodField;
    private volatile Field zoomUserIdField;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            SharedPreferences prefs = getRemotePreferences(ConfigKeys.REMOTE_GROUP);
            GuardConfig.initialize(prefs);
            log(Log.INFO, TAG, "SYSTEM_SCOPE remote settings ready");
        } catch (Throwable t) {
            GuardConfig.initialize(null);
            log(Log.WARN, TAG, "SYSTEM_SCOPE remote settings unavailable", t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;
        if (!SYSTEM_PACKAGE.equals(param.getPackageName())) return;

        log(Log.INFO, TAG, "SYSTEM_SCOPE android package loaded");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        if (!SYSTEM_PACKAGE.equals(param.getPackageName())) return;

        systemClassLoader = param.getClassLoader();

        installActivityManagerHooks(systemClassLoader);
        installActivityTaskManagerHooks(systemClassLoader);
        installActivityRecordHooks(systemClassLoader);
        installOplusMultiResumeHooks(systemClassLoader);
        prepareOplusZoomState(systemClassLoader);

        log(Log.INFO, TAG, "SYSTEM_SCOPE system_server hooks ready");
        diag("ENGINE_READY",
                "targets=" + GuardConfig.targetPackages()
                        + " hooks=" + installedHooks.size());
    }

    private boolean enabled() {
        return GuardConfig.enabled();
    }

    private boolean isTargetPackage(String packageName) {
        return packageName != null
                && enabled()
                && GuardConfig.isTargetPackage(packageName);
    }

    private boolean isTargetUid(int uid) {
        if (!enabled() || uid < 10000) return false;

        String[] packages = uidPackages.get(uid);
        if (packages == null) {
            packages = resolvePackagesForUid(uid);
            if (packages != null) uidPackages.put(uid, packages);
        }
        if (packages == null) return false;

        for (String pkg : packages) {
            if (GuardConfig.isTargetPackage(pkg)) return true;
        }
        return false;
    }

    private String[] resolvePackagesForUid(int uid) {
        try {
            Class<?> appGlobals = Class.forName(
                    "android.app.AppGlobals", false, systemClassLoader);
            Object pm = appGlobals.getMethod("getPackageManager").invoke(null);
            if (pm == null) return null;

            Class<?> iPackageManager = Class.forName(
                    "android.content.pm.IPackageManager", false, systemClassLoader);
            Method method = iPackageManager.getMethod("getPackagesForUid", int.class);
            Object result = method.invoke(pm, uid);
            return result instanceof String[] ? (String[]) result : null;
        } catch (Throwable t) {
            logOnce("uid-resolve-" + uid,
                    "SYSTEM_SCOPE unable to resolve uid=" + uid + " error=" + t);
            return null;
        }
    }

    private void installActivityManagerHooks(ClassLoader loader) {
        Class<?> ams = load(loader, "com.android.server.am.ActivityManagerService");
        if (ams == null) return;

        for (Method method : ams.getDeclaredMethods()) {
            String name = method.getName();

            if ("getPackageProcessState".equals(name)
                    && method.getReturnType() == int.class) {
                try {
                    method.setAccessible(true);
                    if (!installedHooks.add(method.toGenericString())) continue;
                    hook(method).intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        if (GuardConfig.bool(ConfigKeys.SYSTEM_IMPORTANCE_TOP)
                                && !args.isEmpty()
                                && args.get(0) instanceof String pkg
                                && isTargetPackage(pkg)) {
                            hit("AMS.getPackageProcessState TOP " + pkg);
                            diag("AMS_PACKAGE_STATE",
                                    "pkg=" + pkg + " forced=" + PROCESS_STATE_TOP
                                            + " args=" + argsSummary(args));
                            return PROCESS_STATE_TOP;
                        }
                        return chain.proceed();
                    });
                    log(Log.INFO, TAG, "SYSTEM_SCOPE installed AMS.getPackageProcessState");
                } catch (Throwable t) {
                    installedHooks.remove(method.toGenericString());
                    log(Log.WARN, TAG, "SYSTEM_SCOPE skipped AMS.getPackageProcessState error=" + t);
                }
                continue;
            }

            if ("getUidProcessState".equals(name)
                    && method.getReturnType() == int.class) {
                try {
                    method.setAccessible(true);
                    if (!installedHooks.add(method.toGenericString())) continue;
                    hook(method).intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        if (GuardConfig.bool(ConfigKeys.SYSTEM_IMPORTANCE_TOP)
                                && !args.isEmpty()
                                && args.get(0) instanceof Integer uid
                                && isTargetUid(uid)) {
                            hit("AMS.getUidProcessState TOP uid=" + uid);
                            diag("AMS_UID_STATE",
                                    "uid=" + uid
                                            + " packages=" + Arrays.toString(resolvePackagesForUid(uid))
                                            + " forced=" + PROCESS_STATE_TOP
                                            + " args=" + argsSummary(args));
                            return PROCESS_STATE_TOP;
                        }
                        return chain.proceed();
                    });
                    log(Log.INFO, TAG, "SYSTEM_SCOPE installed AMS.getUidProcessState");
                } catch (Throwable t) {
                    installedHooks.remove(method.toGenericString());
                    log(Log.WARN, TAG, "SYSTEM_SCOPE skipped AMS.getUidProcessState error=" + t);
                }
                continue;
            }

            if ("isAppForeground".equals(name)
                    && method.getReturnType() == boolean.class) {
                try {
                    method.setAccessible(true);
                    if (!installedHooks.add(method.toGenericString())) continue;
                    hook(method).intercept(chain -> {
                        List<Object> args = chain.getArgs();
                        if (GuardConfig.bool(ConfigKeys.SYSTEM_IMPORTANCE_TOP)
                                && !args.isEmpty()
                                && args.get(0) instanceof Integer uid
                                && isTargetUid(uid)) {
                            hit("AMS.isAppForeground true uid=" + uid);
                            diag("AMS_FOREGROUND",
                                    "uid=" + uid
                                            + " packages=" + Arrays.toString(resolvePackagesForUid(uid))
                                            + " forced=true"
                                            + " args=" + argsSummary(args));
                            return true;
                        }
                        return chain.proceed();
                    });
                    log(Log.INFO, TAG, "SYSTEM_SCOPE installed AMS.isAppForeground");
                } catch (Throwable t) {
                    installedHooks.remove(method.toGenericString());
                    log(Log.WARN, TAG, "SYSTEM_SCOPE skipped AMS.isAppForeground error=" + t);
                }
            }
        }
    }

    private void installActivityTaskManagerHooks(ClassLoader loader) {
        Class<?> atms = load(loader,
                "com.android.server.wm.ActivityTaskManagerService");
        if (atms == null) return;

        for (Method method : atms.getDeclaredMethods()) {
            if (!"hasResumedActivity".equals(method.getName())
                    || method.getReturnType() != boolean.class) {
                continue;
            }

            try {
                method.setAccessible(true);
                if (!installedHooks.add(method.toGenericString())) continue;
                hook(method).intercept(chain -> {
                    List<Object> args = chain.getArgs();
                    if (GuardConfig.bool(ConfigKeys.SYSTEM_HAS_RESUMED)
                            && !args.isEmpty()
                            && args.get(0) instanceof Integer uid
                            && isTargetUid(uid)) {
                        hit("ATMS.hasResumedActivity true uid=" + uid);
                        diag("ATMS_HAS_RESUMED",
                                "uid=" + uid
                                        + " packages=" + Arrays.toString(resolvePackagesForUid(uid))
                                        + " forced=true"
                                        + " args=" + argsSummary(args));
                        return true;
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "SYSTEM_SCOPE installed ATMS.hasResumedActivity");
            } catch (Throwable t) {
                installedHooks.remove(method.toGenericString());
                log(Log.WARN, TAG, "SYSTEM_SCOPE skipped ATMS.hasResumedActivity error=" + t);
            }
        }
    }

    /**
     * Keep an already-resumed protected Activity from being paused only while it belongs to the
     * current OPlus Zoom/Mini task. This avoids globally suppressing Android lifecycle transitions.
     */
    private void installActivityRecordHooks(ClassLoader loader) {
        Class<?> record = load(loader, "com.android.server.wm.ActivityRecord");
        if (record == null) return;

        for (Method method : record.getDeclaredMethods()) {
            if (!"shouldPauseActivity".equals(method.getName())
                    || method.getReturnType() != boolean.class) {
                continue;
            }

            try {
                method.setAccessible(true);
                if (!installedHooks.add(method.toGenericString())) continue;
                hook(method).intercept(chain -> {
                    if (!enabled()
                            || !GuardConfig.bool(ConfigKeys.SYSTEM_KEEP_MINI_RESUMED)) {
                        return chain.proceed();
                    }

                    Object activityRecord = chain.getThisObject();
                    String pkg = activityPackage(activityRecord);
                    if (!isTargetPackage(pkg)) return chain.proceed();

                    boolean zoomActive = isOplusZoomActiveFor(pkg);
                    if (zoomActive) {
                        hit("ActivityRecord.keepResumed zoom=" + pkg);
                        diag("ACTIVITY_KEEP_RESUMED",
                                "pkg=" + pkg
                                        + " record=" + compact(activityRecord)
                                        + " decision=false"
                                        + " stack=" + stackSummary());
                        return false;
                    }

                    diag("ACTIVITY_PAUSE_ALLOWED",
                            "pkg=" + pkg
                                    + " zoomActive=false"
                                    + " record=" + compact(activityRecord));
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "SYSTEM_SCOPE installed ActivityRecord.shouldPauseActivity");
            } catch (Throwable t) {
                installedHooks.remove(method.toGenericString());
                log(Log.WARN, TAG, "SYSTEM_SCOPE skipped ActivityRecord.shouldPauseActivity error=" + t);
            }
        }
    }

    private void installOplusMultiResumeHooks(ClassLoader loader) {
        String[] candidates = {
                "com.android.server.wm.OplusCompactWindowManagerService",
                "com.android.server.wm.OplusZoomWindowManagerService"
        };

        for (String className : candidates) {
            Class<?> clazz = load(loader, className);
            if (clazz == null) continue;

            for (Method method : clazz.getDeclaredMethods()) {
                String lower = method.getName().toLowerCase();

                if (method.getReturnType() == boolean.class
                        && lower.contains("supportmultiresume")) {
                    try {
                        method.setAccessible(true);
                        if (!installedHooks.add(method.toGenericString())) continue;
                        hook(method).intercept(chain -> {
                            if (GuardConfig.bool(ConfigKeys.SYSTEM_OPLUS_MULTI_RESUME)
                                    && containsTargetPackage(chain.getArgs())) {
                                hit("OPlus supportMultiResume=true");
                                diag("OPLUS_MULTI_RESUME",
                                        "method=" + method.getName()
                                                + " forced=true"
                                                + " args=" + argsSummary(chain.getArgs()));
                                return true;
                            }
                            return chain.proceed();
                        });
                        log(Log.INFO, TAG, "SYSTEM_SCOPE installed "
                                + className + "." + method.getName());
                    } catch (Throwable t) {
                        installedHooks.remove(method.toGenericString());
                        log(Log.WARN, TAG, "SYSTEM_SCOPE skipped "
                                + className + "." + method.getName() + " error=" + t);
                    }
                    continue;
                }

                if (method.getReturnType() == boolean.class
                        && (lower.contains("supportzoommode")
                        || lower.contains("supportzoomwindow"))) {
                    try {
                        method.setAccessible(true);
                        if (!installedHooks.add(method.toGenericString())) continue;
                        hook(method).intercept(chain -> {
                            if (GuardConfig.bool(ConfigKeys.SYSTEM_FORCE_ZOOM_SUPPORT)
                                    && containsTargetPackage(chain.getArgs())) {
                                hit("OPlus zoom support=true");
                                diag("OPLUS_ZOOM_SUPPORT",
                                        "method=" + method.getName()
                                                + " forced=true"
                                                + " args=" + argsSummary(chain.getArgs()));
                                return true;
                            }
                            return chain.proceed();
                        });
                        log(Log.INFO, TAG, "SYSTEM_SCOPE installed "
                                + className + "." + method.getName());
                    } catch (Throwable t) {
                        installedHooks.remove(method.toGenericString());
                        log(Log.WARN, TAG, "SYSTEM_SCOPE skipped "
                                + className + "." + method.getName() + " error=" + t);
                    }
                }
            }
        }
    }

    private boolean containsTargetPackage(List<Object> args) {
        if (!enabled()) return false;
        for (Object arg : args) {
            if (arg instanceof String value && isTargetPackage(value)) return true;
            if (arg instanceof ComponentName component
                    && isTargetPackage(component.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private String activityPackage(Object activityRecord) {
        if (activityRecord == null) return null;

        try {
            Field field = findField(activityRecord.getClass(), "packageName");
            if (field != null) {
                field.setAccessible(true);
                Object value = field.get(activityRecord);
                if (value instanceof String) return (String) value;
            }
        } catch (Throwable ignored) {
        }

        try {
            Field field = findField(activityRecord.getClass(), "mActivityComponent");
            if (field != null) {
                field.setAccessible(true);
                Object value = field.get(activityRecord);
                if (value instanceof ComponentName component) {
                    return component.getPackageName();
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private void prepareOplusZoomState(ClassLoader loader) {
        try {
            Class<?> managerClass = Class.forName(
                    "com.oplus.zoomwindow.OplusZoomWindowManager",
                    false,
                    loader);
            oplusZoomManager = managerClass.getMethod("getInstance").invoke(null);
            getCurrentZoomWindowState =
                    managerClass.getMethod("getCurrentZoomWindowState");

            Class<?> infoClass = Class.forName(
                    "com.oplus.zoomwindow.OplusZoomWindowInfo",
                    false,
                    loader);
            zoomPkgField = infoClass.getField("zoomPkg");
            windowTypeField = infoClass.getField("windowType");
            windowShownField = infoClass.getField("windowShown");
            zoomRectField = optionalField(infoClass, "zoomRect");
            cpnNameField = optionalField(infoClass, "cpnName");
            cvActionFlagField = optionalField(infoClass, "cvActionFlag");
            lastExitMethodField = optionalField(infoClass, "lastExitMethod");
            zoomUserIdField = optionalField(infoClass, "zoomUserId");

            log(Log.INFO, TAG, "SYSTEM_SCOPE OPlus Zoom state API ready");
            diag("OPLUS_API_READY", "infoClass=" + infoClass.getName());
        } catch (Throwable t) {
            log(Log.INFO, TAG,
                    "SYSTEM_SCOPE OPlus Zoom state API unavailable: " + t);
        }
    }

    private boolean isOplusZoomActiveFor(String packageName) {
        try {
            Object manager = oplusZoomManager;
            Method getter = getCurrentZoomWindowState;
            Field pkgField = zoomPkgField;
            Field typeField = windowTypeField;
            if (manager == null || getter == null || pkgField == null || typeField == null) {
                return false;
            }

            Object info = getter.invoke(manager);
            if (info == null) return false;

            Object pkg = pkgField.get(info);
            int type = typeField.getInt(info);
            boolean shown = windowShownField != null && windowShownField.getBoolean(info);

            // type > 0 covers Zoom/Mini state. Do not require windowShown so hidden Mini can remain
            // protected while the current zoom task is still retained by the OEM service.
            boolean active = packageName.equals(pkg) && type > 0;
            String snapshot = "requested=" + packageName
                    + " zoomPkg=" + pkg
                    + " type=" + type
                    + " shown=" + shown
                    + " cpn=" + fieldValue(cpnNameField, info)
                    + " rect=" + fieldValue(zoomRectField, info)
                    + " cvActionFlag=" + fieldValue(cvActionFlagField, info)
                    + " lastExitMethod=" + fieldValue(lastExitMethodField, info)
                    + " zoomUserId=" + fieldValue(zoomUserIdField, info)
                    + " active=" + active;

            diag("OPLUS_ZOOM_STATE", snapshot);

            if (active) {
                logOnce("zoom-state-" + packageName + "-" + type + "-" + shown,
                        "SYSTEM_SCOPE zoom active package=" + packageName
                                + " type=" + type + " shown=" + shown);
            }
            return active;
        } catch (Throwable t) {
            logOnce("zoom-state-error",
                    "SYSTEM_SCOPE zoom state query failed: " + t);
            return false;
        }
    }

    private Class<?> load(ClassLoader loader, String className) {
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable t) {
            logOnce("missing-" + className,
                    "SYSTEM_SCOPE class unavailable " + className);
            return null;
        }
    }

    private static Field optionalField(Class<?> type, String name) {
        try {
            Field field = type.getField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object fieldValue(Field field, Object receiver) {
        if (field == null || receiver == null) return null;
        try {
            return field.get(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private boolean diagnosticsActive() {
        return GuardConfig.bool(ConfigKeys.DIAGNOSTICS_ACTIVE);
    }

    private void diag(String event, String detail) {
        if (!diagnosticsActive()) return;

        String message = "DIAG_SYS"
                + " event=" + event
                + " thread=" + Thread.currentThread().getName()
                + " detail=" + detail;
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
    }

    private static String argsSummary(List<Object> args) {
        if (args == null || args.isEmpty()) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(compact(args.get(i)));
            if (out.length() > 1200) {
                out.append("...");
                break;
            }
        }
        return out.append(']').toString();
    }

    private static String stackSummary() {
        StringBuilder out = new StringBuilder();
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int added = 0;
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (cls.equals(Thread.class.getName())
                    || cls.equals(GuardModule.class.getName())) {
                continue;
            }
            if (added++ > 0) out.append(" <- ");
            out.append(cls).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (added >= 10 || out.length() > 1600) break;
        }
        return out.toString();
    }

    private static String compact(Object value) {
        if (value == null) return "null";
        String text;
        try {
            text = String.valueOf(value);
        } catch (Throwable t) {
            text = value.getClass().getName();
        }
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.length() <= 900 ? text : text.substring(0, 900) + "...";
    }

    private void hit(String message) {
        if (diagnosticsActive()) {
            diag("HOOK_HIT", message);
            return;
        }

        String key = "hit-" + message;
        if (firstHits.add(key)) {
            log(Log.INFO, TAG, "SYSTEM_SCOPE HIT " + message);
        }
    }

    private void logOnce(String key, String message) {
        if (firstHits.add("once-" + key)) {
            log(Log.INFO, TAG, message);
        }
    }
}
