package com.jieei.alwaysforeground;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.SharedPreferences;
import android.os.Bundle;
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
    private static final String HONGGUO_PACKAGE = "com.phoenix.read";

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
        installSafeStrongHooks();
        installLifecycleDiagnostics();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        if (!HONGGUO_PACKAGE.equals(param.getPackageName())) return;
        installHongguoHooks(param.getClassLoader());
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

    private void installSafeStrongHooks() {
        hookBoolean(Activity.class, "hasWindowFocus", true, ModeConfig.MODE_STRONG);
    }

    private void installHongguoHooks(ClassLoader classLoader) {
        installVoidNoArgBlock(classLoader,
                "qv3.r", "n", "Hongguo videoService.n");
        installHongguoPauseBlock(classLoader);
        installHongguoVideoPendantBlock(classLoader);
        installVoidNoArgBlock(classLoader,
                "tp6.c", "onInVisible", "Hongguo tp6.c.onInVisible");
    }

    private void installHongguoPauseBlock(ClassLoader classLoader) {
        final String label = "Hongguo d2.onActivityPause";
        try {
            Class<?> clazz = classLoader.loadClass("com.dragon.read.polaris.video.d2");
            Method method = clazz.getDeclaredMethod("onActivityPause", Activity.class);
            hook(method).intercept(chain -> {
                if (TargetConfig.getMode() >= ModeConfig.MODE_STRONG) {
                    logFirstHit(label + " blocked");
                    return null;
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void installHongguoVideoPendantBlock(ClassLoader classLoader) {
        final String label = "Hongguo videoPendantFacade.h";
        try {
            Class<?> clazz = classLoader.loadClass("t56.x");
            Class<?> model = classLoader.loadClass("com.dragon.read.pages.bookmall.model.RecentReadModel");
            Method method = clazz.getDeclaredMethod("h", model);
            hook(method).intercept(chain -> {
                if (TargetConfig.getMode() >= ModeConfig.MODE_STRONG) {
                    logFirstHit(label + " blocked");
                    return null;
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void installVoidNoArgBlock(
            ClassLoader classLoader, String className, String methodName, String label) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            Method method = clazz.getDeclaredMethod(methodName);
            hook(method).intercept(chain -> {
                if (TargetConfig.getMode() >= ModeConfig.MODE_STRONG) {
                    logFirstHit(label + " blocked");
                    return null;
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void installLifecycleDiagnostics() {
        hookInstrumentationActivity("callActivityOnResume", Activity.class);
        hookInstrumentationActivity("callActivityOnPause", Activity.class);
        hookInstrumentationActivity("callActivityOnStop", Activity.class);
        hookInstrumentationActivity("callActivityOnStart", Activity.class);
        hookInstrumentationActivity("callActivityOnRestart", Activity.class);
        hookInstrumentationActivity("callActivityOnDestroy", Activity.class);
        hookInstrumentationActivity("callActivityOnSaveInstanceState", Activity.class, Bundle.class);
    }

    private void hookInstrumentationActivity(String methodName, Class<?>... parameterTypes) {
        String label = "Instrumentation." + methodName;
        try {
            Method method = Instrumentation.class.getDeclaredMethod(methodName, parameterTypes);
            hook(method).intercept(chain -> {
                if (TargetConfig.getMode() >= ModeConfig.MODE_STRONG) {
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty() && args.get(0) instanceof Activity activity) {
                        log(Log.INFO, TAG, "LIFECYCLE " + methodName
                                + " activity=" + activity.getClass().getName()
                                + " finishing=" + activity.isFinishing()
                                + " changingConfig=" + activity.isChangingConfigurations()
                                + " package=" + activePackage);
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
