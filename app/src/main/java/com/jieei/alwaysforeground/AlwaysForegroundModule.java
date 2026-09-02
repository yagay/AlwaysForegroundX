package com.jieei.alwaysforeground;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class AlwaysForegroundModule extends XposedModule {
    private static final String TAG = "AlwaysForeground";
    private static final String MODULE_PACKAGE = "com.jieei.alwaysforeground";

    private final Set<String> firstHitLogs = ConcurrentHashMap.newKeySet();
    private volatile String activePackage;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            SharedPreferences prefs = getRemotePreferences(ModeConfig.REMOTE_GROUP);
            TargetConfig.initialize(prefs);
            log(Log.INFO, TAG, "remote preferences ready; mode=" + TargetConfig.getMode());
        } catch (Throwable t) {
            TargetConfig.initialize(null);
            log(Log.WARN, TAG, "remote preferences unavailable; falling back to standard mode", t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;
        if (MODULE_PACKAGE.equals(param.getPackageName())) return;

        activePackage = param.getPackageName();
        log(Log.INFO, TAG, "installing framework hooks for " + activePackage
                + ", mode=" + TargetConfig.getMode());

        installStandardHooks();
        installEnhancedHooks();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        if (MODULE_PACKAGE.equals(param.getPackageName())) return;

        activePackage = param.getPackageName();
        installStrongHooks(param.getClassLoader());
        log(Log.INFO, TAG, "package ready: " + activePackage
                + ", effective mode=" + TargetConfig.getMode());
    }

    private void installStandardHooks() {
        hookBoolean(PowerManager.class, "isInteractive", true, ModeConfig.MODE_STANDARD);
        hookBoolean(PowerManager.class, "isScreenOn", true, ModeConfig.MODE_STANDARD);
        hookBoolean(KeyguardManager.class, "isKeyguardLocked", false, ModeConfig.MODE_STANDARD);
        hookBoolean(KeyguardManager.class, "isDeviceLocked", false, ModeConfig.MODE_STANDARD);
        hookBoolean(KeyguardManager.class, "inKeyguardRestrictedInputMode", false, ModeConfig.MODE_STANDARD);
        hookRunningProcesses();
    }

    private void installEnhancedHooks() {
        hookBoolean(ActivityManager.class, "isBackgroundRestricted", false, ModeConfig.MODE_ENHANCED);
        hookBoolean(PowerManager.class, "isDeviceIdleMode", false, ModeConfig.MODE_ENHANCED);
        hookBoolean(PowerManager.class, "isPowerSaveMode", false, ModeConfig.MODE_ENHANCED);
        hookUidImportance();
        hookMyMemoryState();
    }

    private void installStrongHooks(ClassLoader classLoader) {
        hookAndroidXLifecycle(classLoader);
    }

    private void hookRunningProcesses() {
        try {
            Method method = ActivityManager.class.getDeclaredMethod("getRunningAppProcesses");
            hook(method).intercept(chain -> {
                @SuppressWarnings("unchecked")
                List<ActivityManager.RunningAppProcessInfo> list =
                        (List<ActivityManager.RunningAppProcessInfo>) chain.proceed();
                if (TargetConfig.getMode() < ModeConfig.MODE_STANDARD || list == null) return list;

                int myPid = android.os.Process.myPid();
                boolean changed = false;
                for (ActivityManager.RunningAppProcessInfo info : list) {
                    if (info != null && info.pid == myPid) {
                        markForeground(info);
                        changed = true;
                    }
                }
                if (changed) logFirstHit("ActivityManager.getRunningAppProcesses");
                return list;
            });
            logInstalled("ActivityManager.getRunningAppProcesses");
        } catch (Throwable t) {
            logSkipped("ActivityManager.getRunningAppProcesses", t);
        }
    }

    private void hookUidImportance() {
        try {
            Method method = ActivityManager.class.getDeclaredMethod("getUidImportance", int.class);
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (TargetConfig.getMode() < ModeConfig.MODE_ENHANCED) return result;

                List<Object> args = chain.getArgs();
                if (!args.isEmpty()
                        && args.get(0) instanceof Integer uid
                        && uid == android.os.Process.myUid()) {
                    logFirstHit("ActivityManager.getUidImportance");
                    return ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
                return result;
            });
            logInstalled("ActivityManager.getUidImportance");
        } catch (Throwable t) {
            logSkipped("ActivityManager.getUidImportance", t);
        }
    }

    private void hookMyMemoryState() {
        try {
            Method method = ActivityManager.class.getDeclaredMethod(
                    "getMyMemoryState", ActivityManager.RunningAppProcessInfo.class);
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (TargetConfig.getMode() >= ModeConfig.MODE_ENHANCED) {
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty()
                            && args.get(0) instanceof ActivityManager.RunningAppProcessInfo info) {
                        markForeground(info);
                        logFirstHit("ActivityManager.getMyMemoryState");
                    }
                }
                return result;
            });
            logInstalled("ActivityManager.getMyMemoryState");
        } catch (Throwable t) {
            logSkipped("ActivityManager.getMyMemoryState", t);
        }
    }

    private void hookAndroidXLifecycle(ClassLoader classLoader) {
        try {
            Class<?> lifecycleRegistry = classLoader.loadClass("androidx.lifecycle.LifecycleRegistry");
            Class<?> stateClass = classLoader.loadClass("androidx.lifecycle.Lifecycle$State");
            Class<?> eventClass = classLoader.loadClass("androidx.lifecycle.Lifecycle$Event");

            @SuppressWarnings({"rawtypes", "unchecked"})
            Object resumed = Enum.valueOf((Class<? extends Enum>) stateClass.asSubclass(Enum.class), "RESUMED");

            Method getCurrentState = lifecycleRegistry.getDeclaredMethod("getCurrentState");
            hook(getCurrentState).intercept(chain -> {
                if (TargetConfig.getMode() >= ModeConfig.MODE_STRONG) {
                    logFirstHit("AndroidX LifecycleRegistry.getCurrentState");
                    return resumed;
                }
                return chain.proceed();
            });
            logInstalled("AndroidX LifecycleRegistry.getCurrentState");

            hookLifecycleEventGate(lifecycleRegistry, eventClass, "handleLifecycleEvent");
            hookLifecycleStateGate(lifecycleRegistry, stateClass, "setCurrentState");
            hookLifecycleStateGate(lifecycleRegistry, stateClass, "markState");
        } catch (ClassNotFoundException ignored) {
            log(Log.INFO, TAG, "AndroidX LifecycleRegistry not present in " + activePackage);
        } catch (Throwable t) {
            logSkipped("AndroidX LifecycleRegistry", t);
        }
    }

    private void hookLifecycleEventGate(Class<?> lifecycleRegistry, Class<?> eventClass, String methodName) {
        String label = "AndroidX LifecycleRegistry." + methodName;
        try {
            Method method = lifecycleRegistry.getDeclaredMethod(methodName, eventClass);
            hook(method).intercept(chain -> {
                if (TargetConfig.getMode() >= ModeConfig.MODE_STRONG) {
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty()) {
                        String event = String.valueOf(args.get(0));
                        if ("ON_PAUSE".equals(event) || "ON_STOP".equals(event)) {
                            logFirstHit(label + "[" + event + "] blocked");
                            return null;
                        }
                    }
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (NoSuchMethodException e) {
            log(Log.INFO, TAG, "SKIPPED " + label + ": method not present");
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void hookLifecycleStateGate(Class<?> lifecycleRegistry, Class<?> stateClass, String methodName) {
        String label = "AndroidX LifecycleRegistry." + methodName;
        try {
            Method method = lifecycleRegistry.getDeclaredMethod(methodName, stateClass);
            hook(method).intercept(chain -> {
                if (TargetConfig.getMode() >= ModeConfig.MODE_STRONG) {
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty()) {
                        String state = String.valueOf(args.get(0));
                        if ("STARTED".equals(state) || "CREATED".equals(state) || "INITIALIZED".equals(state)) {
                            logFirstHit(label + "[" + state + "] blocked");
                            return null;
                        }
                    }
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (NoSuchMethodException e) {
            log(Log.INFO, TAG, "SKIPPED " + label + ": method not present");
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void hookBoolean(Class<?> clazz, String methodName, boolean spoofValue, int minMode) {
        String label = clazz.getSimpleName() + "." + methodName;
        try {
            Method method = clazz.getDeclaredMethod(methodName);
            hook(method).intercept(chain -> {
                if (TargetConfig.getMode() >= minMode) {
                    logFirstHit(label);
                    return spoofValue;
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void logInstalled(String hookName) {
        log(Log.INFO, TAG, "INSTALLED " + hookName);
    }

    private void logSkipped(String hookName, Throwable t) {
        log(Log.WARN, TAG, "SKIPPED " + hookName + ": " + t);
    }

    private void logFirstHit(String hookName) {
        String key = activePackage + ':' + hookName;
        if (firstHitLogs.add(key)) {
            log(Log.INFO, TAG, "HIT " + hookName + " package=" + activePackage
                    + " mode=" + TargetConfig.getMode());
        }
    }

    private static void markForeground(ActivityManager.RunningAppProcessInfo info) {
        info.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        info.importanceReasonCode = 0;
        info.lru = 0;
    }
}
