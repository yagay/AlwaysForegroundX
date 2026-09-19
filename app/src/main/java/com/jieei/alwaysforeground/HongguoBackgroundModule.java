package com.jieei.alwaysforeground;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hongguo compatibility shim.
 *
 * The generic engine owns normal background/media continuity. This class contains only the one
 * app-specific opt-in that Red Fruit 7.3.5.32 requires to activate its own native background
 * series player, plus observation hooks used by diagnostics.
 *
 * No player pause/stop/start call is blocked or forced here.
 */
public final class HongguoBackgroundModule extends XposedModule {
    private static final String TAG = "AlwaysForeground";
    private static final String HONGGUO_PACKAGE = "com.phoenix.read";
    private static final String SERIES_FRAGMENT =
            "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment";
    private static final String SERIES_LIFECYCLE_OBSERVER =
            "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment$g";
    private static final String NATIVE_BACKGROUND_PLAYER = "z05.b";

    private volatile SharedPreferences preferences;
    private volatile boolean activityPaused;

    private volatile boolean firstEligibilityLogged;
    private volatile boolean firstHandoffLogged;
    private volatile boolean firstResumeLogged;
    private volatile boolean firstCompleteLogged;
    private volatile boolean firstNextLogged;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            preferences = getRemotePreferences(ModeConfig.REMOTE_GROUP);
        } catch (Throwable t) {
            preferences = null;
            log(Log.WARN, TAG, "Hongguo compatibility: remote preferences unavailable", t);
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        if (!HONGGUO_PACKAGE.equals(param.getPackageName())) return;
        if (!isMainProcess()) {
            log(Log.INFO, TAG, "SKIPPED Hongguo compatibility in process=" + safeProcessName());
            return;
        }

        ClassLoader classLoader = param.getClassLoader();
        installRealBackgroundTracker();
        installNativeSeriesEligibility(classLoader);
        installNativeSeriesDiagnostics(classLoader);

        log(Log.INFO, TAG, "INSTALLED Hongguo compatibility shim process=" + safeProcessName());
    }

    /**
     * Preserve the real Activity state. The eligibility override is only allowed after the
     * Activity has genuinely paused; it never spoofs Activity/Window foreground state.
     */
    private void installRealBackgroundTracker() {
        hookActivityState("callActivityOnPause", true);
        hookActivityState("callActivityOnStop", true);
        hookActivityState("callActivityOnResume", false);
    }

    private void hookActivityState(String methodName, boolean background) {
        try {
            Method method = Instrumentation.class.getDeclaredMethod(methodName, Activity.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                Activity activity = !args.isEmpty() && args.get(0) instanceof Activity
                        ? (Activity) args.get(0) : null;

                if (background) {
                    if (activity == null || !activity.isChangingConfigurations()) {
                        activityPaused = true;
                    }
                    return chain.proceed();
                }

                activityPaused = false;
                return chain.proceed();
            });
            log(Log.INFO, TAG, "INSTALLED Hongguo real-state tracker " + methodName);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "SKIPPED Hongguo real-state tracker "
                    + methodName + ": " + t, t);
        }
    }

    /**
     * APK 7.3.5.32:
     * ShortSeriesSingleFragment.vh() reads key_exit_with_audio_player.
     * The series lifecycle observer checks it before deciding whether to execute
     * gh() -> z05.b.resume() or release the background player.
     *
     * This is deliberately narrow: strong mode + real background + exact lifecycle call chain.
     */
    private void installNativeSeriesEligibility(ClassLoader classLoader) {
        try {
            Class<?> fragment = classLoader.loadClass(SERIES_FRAGMENT);
            Method method = fragment.getDeclaredMethod("vh");
            if (method.getParameterCount() != 0 || method.getReturnType() != boolean.class) {
                log(Log.WARN, TAG, "SKIPPED Hongguo native eligibility: unexpected " + method);
                return;
            }

            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (getMode() >= ModeConfig.MODE_STRONG
                        && activityPaused
                        && isSeriesLifecycleEligibilityCall()) {
                    if (!firstEligibilityLogged) {
                        firstEligibilityLogged = true;
                        log(Log.INFO, TAG,
                                "HIT Hongguo native background eligibility "
                                        + SERIES_FRAGMENT + ".vh");
                    }
                    return true;
                }
                return chain.proceed();
            });

            log(Log.INFO, TAG, "INSTALLED Hongguo native eligibility "
                    + SERIES_FRAGMENT + ".vh");
        } catch (Throwable t) {
            log(Log.INFO, TAG, "SKIPPED Hongguo native eligibility: " + t);
        }
    }

    private static boolean isSeriesLifecycleEligibilityCall() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cls = frame.getClassName();
            String method = frame.getMethodName();

            if (SERIES_LIFECYCLE_OBSERVER.equals(cls) && "a".equals(method)) {
                return true;
            }
            if ("gp4.d".equals(cls) && "onLifeCycleOnPause".equals(method)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Observation only: confirms whether Hongguo's own native background-series pipeline wins.
     */
    private void installNativeSeriesDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> fragment = classLoader.loadClass(SERIES_FRAGMENT);
            Method handoff = fragment.getDeclaredMethod("gh");
            handoff.setAccessible(true);
            hook(handoff).intercept(chain -> {
                Object result = chain.proceed();
                if (getMode() >= ModeConfig.MODE_STRONG && !firstHandoffLogged) {
                    firstHandoffLogged = true;
                    log(Log.INFO, TAG, "HIT Hongguo native series handoff "
                            + SERIES_FRAGMENT + ".gh");
                }
                return result;
            });
            log(Log.INFO, TAG, "INSTALLED Hongguo handoff observer " + SERIES_FRAGMENT + ".gh");
        } catch (Throwable t) {
            log(Log.INFO, TAG, "SKIPPED Hongguo handoff observer: " + t);
        }

        try {
            Class<?> player = classLoader.loadClass(NATIVE_BACKGROUND_PLAYER);
            hookObserver(player, "resume", 0);
            hookObserver(player, "U1", 1);
            hookObserver(player, "playNext", 0);
        } catch (Throwable t) {
            log(Log.INFO, TAG, "SKIPPED Hongguo native-player observers: " + t);
        }
    }

    private void hookObserver(Class<?> clazz, String name, int parameterCount) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (!name.equals(method.getName())) continue;
            if (method.getParameterCount() != parameterCount) continue;

            try {
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    if (getMode() >= ModeConfig.MODE_STRONG) {
                        if ("resume".equals(name) && !firstResumeLogged) {
                            firstResumeLogged = true;
                            log(Log.INFO, TAG, "HIT Hongguo native background player resume");
                        } else if ("U1".equals(name) && !firstCompleteLogged) {
                            firstCompleteLogged = true;
                            log(Log.INFO, TAG, "HIT Hongguo native background episode complete");
                        } else if ("playNext".equals(name) && !firstNextLogged) {
                            firstNextLogged = true;
                            log(Log.INFO, TAG, "HIT Hongguo native background playNext");
                        }
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "INSTALLED Hongguo native observer "
                        + NATIVE_BACKGROUND_PLAYER + "." + name);
            } catch (Throwable t) {
                log(Log.INFO, TAG, "SKIPPED Hongguo native observer "
                        + NATIVE_BACKGROUND_PLAYER + "." + name + ": " + t);
            }
        }
    }

    private int getMode() {
        SharedPreferences prefs = preferences;
        if (prefs == null) return ModeConfig.DEFAULT_MODE;
        try {
            int mode = prefs.getInt(ModeConfig.KEY_MODE, ModeConfig.DEFAULT_MODE);
            return ModeConfig.isValid(mode) ? mode : ModeConfig.DEFAULT_MODE;
        } catch (Throwable ignored) {
            return ModeConfig.DEFAULT_MODE;
        }
    }

    private static boolean isMainProcess() {
        return HONGGUO_PACKAGE.equals(safeProcessName());
    }

    private static String safeProcessName() {
        try {
            String process = Application.getProcessName();
            return process == null ? "unknown" : process;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
