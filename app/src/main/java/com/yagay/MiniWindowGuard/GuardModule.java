package com.yagay.MiniWindowGuard;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * System-only engine.
 *
 * Target apps are never injected. The module runs only in android/system_server and directly
 * manages their real Task + SurfaceControl through TaskSurfaceController.
 */
public final class GuardModule extends XposedModule {
    private static final String TAG = "MiniWindowGuard";
    private static final String SYSTEM_PACKAGE = "android";

    // android.app.ActivityManager.PROCESS_STATE_TOP is hidden from the public SDK.
    private static final int PROCESS_STATE_TOP = 2;

    private final Set<String> installedHooks = ConcurrentHashMap.newKeySet();
    private final Set<String> firstHits = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Integer, String[]> uidPackages =
            new ConcurrentHashMap<>();

    private volatile ClassLoader systemClassLoader;
    private volatile Handler systemHandler;
    private volatile TaskSurfaceController container;

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
        systemHandler = new Handler(Looper.getMainLooper());

        Context context = resolveSystemContext(systemClassLoader);
        container = new TaskSurfaceController(
                systemHandler,
                context,
                this::containerLog);
        container.start();

        installActivityManagerHooks(systemClassLoader);
        installActivityTaskManagerHooks(systemClassLoader);
        installActivityRecordHooks(systemClassLoader);

        log(Log.INFO, TAG, "SYSTEM_SCOPE TaskSurface engine ready");
        diag("ENGINE_READY",
                "targets=" + GuardConfig.targetPackages()
                        + " hooks=" + installedHooks.size()
                        + " systemContext=" + (context != null));
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

    private void installActivityManagerHooks(ClassLoader loader) {
        Class<?> ams = load(loader, "com.android.server.am.ActivityManagerService");
        if (ams == null) return;

        for (Method method : ams.getDeclaredMethods()) {
            String name = method.getName();

            if ("getPackageProcessState".equals(name)
                    && method.getReturnType() == int.class) {
                install(method, "AMS.getPackageProcessState", chain -> {
                    List<Object> args = chain.getArgs();
                    if (GuardConfig.bool(ConfigKeys.SYSTEM_IMPORTANCE_TOP)
                            && !args.isEmpty()
                            && args.get(0) instanceof String pkg
                            && isTargetPackage(pkg)) {
                        diag("AMS_PACKAGE_STATE",
                                "pkg=" + pkg + " forced=" + PROCESS_STATE_TOP);
                        return PROCESS_STATE_TOP;
                    }
                    return chain.proceed();
                });
                continue;
            }

            if ("getUidProcessState".equals(name)
                    && method.getReturnType() == int.class) {
                install(method, "AMS.getUidProcessState", chain -> {
                    List<Object> args = chain.getArgs();
                    if (GuardConfig.bool(ConfigKeys.SYSTEM_IMPORTANCE_TOP)
                            && !args.isEmpty()
                            && args.get(0) instanceof Integer uid
                            && isTargetUid(uid)) {
                        diag("AMS_UID_STATE",
                                "uid=" + uid
                                        + " packages="
                                        + Arrays.toString(resolvePackagesForUid(uid))
                                        + " forced=" + PROCESS_STATE_TOP);
                        return PROCESS_STATE_TOP;
                    }
                    return chain.proceed();
                });
                continue;
            }

            if ("isAppForeground".equals(name)
                    && method.getReturnType() == boolean.class) {
                install(method, "AMS.isAppForeground", chain -> {
                    List<Object> args = chain.getArgs();
                    if (GuardConfig.bool(ConfigKeys.SYSTEM_IMPORTANCE_TOP)
                            && !args.isEmpty()
                            && args.get(0) instanceof Integer uid
                            && isTargetUid(uid)) {
                        diag("AMS_FOREGROUND", "uid=" + uid + " forced=true");
                        return true;
                    }
                    return chain.proceed();
                });
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

            install(method, "ATMS.hasResumedActivity", chain -> {
                List<Object> args = chain.getArgs();
                if (GuardConfig.bool(ConfigKeys.SYSTEM_HAS_RESUMED)
                        && !args.isEmpty()
                        && args.get(0) instanceof Integer uid
                        && isTargetUid(uid)) {
                    diag("ATMS_HAS_RESUMED",
                            "uid=" + uid
                                    + " packages="
                                    + Arrays.toString(resolvePackagesForUid(uid))
                                    + " forced=true");
                    return true;
                }
                return chain.proceed();
            });
        }
    }

    private void installActivityRecordHooks(ClassLoader loader) {
        Class<?> record = load(loader, "com.android.server.wm.ActivityRecord");
        if (record == null) return;

        for (Method method : record.getDeclaredMethods()) {
            String name = method.getName();

            if ("shouldPauseActivity".equals(name)
                    && method.getReturnType() == boolean.class) {
                install(method, "ActivityRecord.shouldPauseActivity", chain -> {
                    TaskSurfaceController current = container;
                    if (!enabled()
                            || current == null
                            || !GuardConfig.bool(
                                    ConfigKeys.SYSTEM_KEEP_CONTAINER_RESUMED)
                            || !current.isManagedActivityRecord(chain.getThisObject())) {
                        return chain.proceed();
                    }

                    String pkg = activityPackage(chain.getThisObject());
                    int state = current.stateForPackage(pkg);
                    if (state == ConfigKeys.STATE_RELEASED) {
                        return chain.proceed();
                    }

                    diag("ACTIVITY_KEEP_RESUMED",
                            "pkg=" + pkg
                                    + " state=" + state
                                    + " stack=" + stackSummary());
                    return false;
                });
                continue;
            }

            if (("shouldBeVisible".equals(name)
                    || "isVisibleRequested".equals(name))
                    && method.getReturnType() == boolean.class) {
                install(method, "ActivityRecord." + name, chain -> {
                    TaskSurfaceController current = container;
                    if (!enabled()
                            || current == null
                            || !GuardConfig.bool(
                                    ConfigKeys.SYSTEM_KEEP_CONTAINER_VISIBLE)
                            || !current.isManagedActivityRecord(chain.getThisObject())) {
                        return chain.proceed();
                    }

                    String pkg = activityPackage(chain.getThisObject());
                    int state = current.stateForPackage(pkg);
                    if (state != ConfigKeys.STATE_RELEASED) {
                        diag("ACTIVITY_KEEP_VISIBLE",
                                "pkg=" + pkg
                                        + " state=" + state
                                        + " method=" + name);
                        return true;
                    }
                    return chain.proceed();
                });
                continue;
            }

            if ("setState".equals(name)
                    && method.getReturnType() == void.class
                    && method.getParameterCount() >= 1) {
                install(method, "ActivityRecord.setState", chain -> {
                    Object result = chain.proceed();

                    if (!enabled()
                            || !GuardConfig.bool(ConfigKeys.AUTO_CONTAINER)) {
                        return result;
                    }

                    List<Object> args = chain.getArgs();
                    if (args.isEmpty()
                            || !"RESUMED".equals(String.valueOf(args.get(0)))) {
                        return result;
                    }

                    Object activityRecord = chain.getThisObject();
                    String pkg = activityPackage(activityRecord);
                    if (!isTargetPackage(pkg)) return result;

                    TaskSurfaceController current = container;
                    if (current != null) {
                        current.capture(activityRecord, pkg);
                    }
                    return result;
                });
            }
        }
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

    private Context resolveSystemContext(ClassLoader loader) {
        try {
            Class<?> activityThread = Class.forName(
                    "android.app.ActivityThread", false, loader);
            Method current = activityThread.getDeclaredMethod(
                    "currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread != null) {
                Method getSystemContext =
                        activityThread.getDeclaredMethod("getSystemContext");
                getSystemContext.setAccessible(true);
                Object value = getSystemContext.invoke(thread);
                if (value instanceof Context) return (Context) value;
            }
        } catch (Throwable t) {
            diag("SYSTEM_CONTEXT_FAIL", String.valueOf(t));
        }
        return null;
    }

    private String activityPackage(Object activityRecord) {
        if (activityRecord == null) return null;

        Object value = fieldValue(activityRecord, "packageName");
        if (value instanceof String) return (String) value;

        Object component = fieldValue(activityRecord, "mActivityComponent");
        if (component instanceof ComponentName) {
            return ((ComponentName) component).getPackageName();
        }
        return null;
    }

    private interface HookBody {
        Object run(io.github.libxposed.api.XposedInterface.Chain chain)
                throws Throwable;
    }

    private void install(Method method, String label, HookBody body) {
        String signature = method.toGenericString();
        if (!installedHooks.add(signature)) return;

        try {
            method.setAccessible(true);
            hook(method).intercept(body::run);
            log(Log.INFO, TAG, "SYSTEM_SCOPE installed " + label);
        } catch (Throwable t) {
            installedHooks.remove(signature);
            log(Log.WARN, TAG,
                    "SYSTEM_SCOPE skipped " + label + " error=" + t);
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

    private static Object fieldValue(Object receiver, String name) {
        if (receiver == null) return null;
        Field field = findField(receiver.getClass(), name);
        if (field == null) return null;

        try {
            field.setAccessible(true);
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

    private void containerLog(String event, String detail) {
        String message = "CONTAINER"
                + " event=" + event
                + " detail=" + detail;
        log(Log.INFO, TAG, message);
        if (diagnosticsActive()) {
            Log.i(TAG, "DIAG_SYS " + message);
        }
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

    private void logOnce(String key, String message) {
        if (firstHits.add("once-" + key)) {
            log(Log.INFO, TAG, message);
        }
    }
}
