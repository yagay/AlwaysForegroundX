package com.jieei.alwaysforeground;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hongguo / Tomato short-video background-play compatibility.
 *
 * The old implementation relied on one very specific stack:
 *   Fragment.onPause/onStop -> ... -> adapter.a.x() -> player.pause()
 *
 * In newer Hongguo builds adapter.a.x() is still the small pause-only endpoint, but new
 * lifecycle paths (for example ShortSeriesLandFragment.onPause) can reach it without the
 * exact historical stack.  Keep the narrow pause endpoint hook, but classify background
 * lifecycle calls by stable lifecycle/component markers instead of one complete call chain.
 *
 * A TTVideoEngine.pause() fallback is also installed.  It only suppresses pause when the
 * current stack proves the call came from a Hongguo short-video background lifecycle path,
 * so manual pause / explicit user actions still proceed normally.
 */
public final class HongguoBackgroundModule extends XposedModule {
    private static final String TAG = "AlwaysForeground";
    private static final String HONGGUO_PACKAGE = "com.phoenix.read";
    private static final String PLAYER_ADAPTER =
            "com.dragon.read.component.shortvideo.impl.v2.view.adapter.a";
    private static final String SERIES_FRAGMENT =
            "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment";
    private static final String SERIES_LIFECYCLE_OBSERVER =
            "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment$g";

    private static final String[] VIDEO_ENGINE_CLASSES = {
            "com.ss.ttvideoengine.TTVideoEngine",
            "com.ss.ttvideoengine.TTVideoEngineImpl"
    };

    private volatile SharedPreferences preferences;

    private volatile boolean firstFeedBlockedLogged;
    private volatile boolean firstEpisodeBlockedLogged;
    private volatile boolean firstLandscapeBlockedLogged;
    private volatile boolean firstGenericBlockedLogged;
    private volatile boolean firstEngineBlockedLogged;
    private volatile boolean firstEpisodeP0BlockedLogged;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            preferences = getRemotePreferences(ModeConfig.REMOTE_GROUP);
        } catch (Throwable t) {
            preferences = null;
            log(Log.WARN, TAG, "Hongguo hook: remote preferences unavailable", t);
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        if (!HONGGUO_PACKAGE.equals(param.getPackageName())) return;
        if (!isMainProcess()) {
            log(Log.INFO, TAG, "SKIPPED Hongguo background-play hooks in process="
                    + safeProcessName());
            return;
        }

        installEpisodeLifecyclePauseHook(param.getClassLoader());
        installPauseOnlyAdapterHook(param.getClassLoader());
        installVideoEngineFallbacks(param.getClassLoader());
    }

    /**
     * Red Fruit 7.3.5.32 episode/detail background chain:
     *
     * ShortSeriesSingleFragment$g.a -> ShortSeriesSingleFragment.P0 ->
     * view.adapter.f.x -> view.adapter.a.x -> player.pause()
     *
     * and:
     *
     * ShortSeriesSingleFragment.onStop -> ShortSeriesSingleFragment.P0 ->
     * view.adapter.f.x -> view.adapter.a.x -> player.pause()
     *
     * Intercept P0 only when the caller is the lifecycle pause observer or Fragment.onStop.
     * This moves the decision before the deep player stack, which is much less sensitive to
     * ART/JIT stack-frame changes. Other P0 calls and manual pause operations still proceed.
     */
    private void installEpisodeLifecyclePauseHook(ClassLoader classLoader) {
        try {
            Class<?> clazz = classLoader.loadClass(SERIES_FRAGMENT);
            Method method = clazz.getDeclaredMethod("P0");
            if (method.getParameterCount() != 0 || method.getReturnType() != void.class) {
                log(Log.WARN, TAG, "SKIPPED Hongguo episode P0 endpoint: unexpected signature "
                        + method);
                return;
            }

            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (getMode() < ModeConfig.MODE_STRONG) return chain.proceed();

                int source = episodeP0BackgroundSource();
                if (source == 0) return chain.proceed();

                if (!firstEpisodeP0BlockedLogged) {
                    firstEpisodeP0BlockedLogged = true;
                    log(Log.INFO, TAG, "HIT Hongguo episode P0 background pause blocked source="
                            + (source == 1 ? "lifecyclePause" : "fragmentStop")
                            + " package=" + HONGGUO_PACKAGE);
                }
                return null;
            });

            log(Log.INFO, TAG, "INSTALLED Hongguo episode P0 endpoint "
                    + SERIES_FRAGMENT + ".P0 process=" + safeProcessName());
        } catch (Throwable t) {
            log(Log.WARN, TAG, "SKIPPED Hongguo episode P0 endpoint: " + t, t);
        }
    }

    /**
     * @return 0 = unrelated P0 call, 1 = lifecycle pause observer, 2 = Fragment.onStop.
     */
    private static int episodeP0BackgroundSource() {
        boolean lifecyclePause = false;
        boolean fragmentStop = false;

        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cls = frame.getClassName();
            String method = frame.getMethodName();

            if ((SERIES_LIFECYCLE_OBSERVER.equals(cls) && "a".equals(method))
                    || ("gp4.d".equals(cls) && "onLifeCycleOnPause".equals(method))) {
                lifecyclePause = true;
            }

            if (SERIES_FRAGMENT.equals(cls) && "onStop".equals(method)) {
                fragmentStop = true;
            }
        }

        if (lifecyclePause) return 1;
        if (fragmentStop) return 2;
        return 0;
    }

    /**
     * Primary fallback hook.  In the current APK adapter.a.x() still only performs:
     *   player.isPlaying(); if (true) player.pause();
     *
     * That makes suppressing this method safe when the stack is a proven background lifecycle
     * transition.  We deliberately do not suppress unrelated invocations so manual pause keeps
     * working.
     */
    private void installPauseOnlyAdapterHook(ClassLoader classLoader) {
        try {
            Class<?> clazz = classLoader.loadClass(PLAYER_ADAPTER);
            Method method = clazz.getDeclaredMethod("x");
            if (method.getParameterCount() != 0 || method.getReturnType() != void.class) {
                log(Log.WARN, TAG, "SKIPPED Hongguo adapter pause endpoint: unexpected signature "
                        + method);
                return;
            }

            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (getMode() < ModeConfig.MODE_STRONG) return chain.proceed();

                int path = backgroundPausePath();
                if (path == 0) return chain.proceed();

                logBlockedPathOnce(path, PLAYER_ADAPTER + ".x");
                return null;
            });

            log(Log.INFO, TAG, "INSTALLED Hongguo pause-only endpoint "
                    + PLAYER_ADAPTER + ".x process=" + safeProcessName());
        } catch (Throwable t) {
            // Do not fail the whole compatibility layer.  New versions may rename the adapter;
            // the engine-level fallback below can still keep background playback alive.
            log(Log.WARN, TAG, "SKIPPED Hongguo adapter pause endpoint: " + t, t);
        }
    }

    /**
     * Future-proof fallback for adapter/method renames.  Only no-arg void pause() methods are
     * considered and they are suppressed only when the stack contains a verified Hongguo
     * short-video background lifecycle path.
     */
    private void installVideoEngineFallbacks(ClassLoader classLoader) {
        for (String className : VIDEO_ENGINE_CLASSES) {
            installVideoEngineFallback(classLoader, className);
        }
    }

    private void installVideoEngineFallback(ClassLoader classLoader, String className) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            int installed = 0;

            for (Method method : clazz.getDeclaredMethods()) {
                if (!"pause".equals(method.getName())) continue;
                if (method.getParameterCount() != 0) continue;
                if (method.getReturnType() != void.class) continue;

                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    if (getMode() < ModeConfig.MODE_STRONG) return chain.proceed();

                    int path = backgroundPausePath();
                    if (path == 0) return chain.proceed();

                    if (!firstEngineBlockedLogged) {
                        firstEngineBlockedLogged = true;
                        log(Log.INFO, TAG, "HIT Hongguo engine background pause blocked "
                                + className + ".pause path=" + pathName(path)
                                + " package=" + HONGGUO_PACKAGE);
                    }
                    return null;
                });
                installed++;
            }

            if (installed > 0) {
                log(Log.INFO, TAG, "INSTALLED Hongguo engine pause fallback "
                        + className + " methods=" + installed);
            }
        } catch (ClassNotFoundException ignored) {
            // Some Hongguo versions package only one TTVideoEngine implementation.
        } catch (Throwable t) {
            log(Log.WARN, TAG, "SKIPPED Hongguo engine pause fallback "
                    + className + ": " + t, t);
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

    /**
     * @return 0 = unrelated/user pause
     *         1 = home/feed background path
     *         2 = episode/detail background path
     *         3 = landscape/fullscreen background path
     *         4 = other verified short-video Fragment background lifecycle path
     */
    private static int backgroundPausePath() {
        boolean fragmentPause = false;
        boolean fragmentStop = false;
        boolean feedInvisible = false;
        boolean shortSeriesSingleP0 = false;
        boolean shortSeriesSingleStop = false;
        boolean lifecyclePause = false;
        boolean landscapePause = false;
        boolean shortVideoLifecycle = false;

        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            String method = frame.getMethodName();

            if (("com.dragon.read.base.AbsFragment".equals(cls)
                    && ("onPause".equals(method) || "onStop".equals(method)))
                    || ("androidx.fragment.app.Fragment".equals(cls)
                    && "performPause".equals(method))) {
                fragmentPause = true;
            }

            if (("androidx.fragment.app.Fragment".equals(cls)
                    && "performStop".equals(method))
                    || ("androidx.fragment.app.FragmentManager".equals(cls)
                    && "dispatchStop".equals(method))) {
                fragmentStop = true;
            }

            if (("com.dragon.read.component.biz.impl.bookmall.VideoFeedTabFragment".equals(cls)
                    && "onInvisible".equals(method))
                    || ("com.dragon.read.component.shortvideo.impl.feedtab.VideoFeedTabFragmentImpl"
                    .equals(cls) && "Q0".equals(method))) {
                feedInvisible = true;
            }

            if (SERIES_FRAGMENT.equals(cls)) {
                if ("P0".equals(method)) shortSeriesSingleP0 = true;
                if ("onStop".equals(method)) shortSeriesSingleStop = true;
            }

            if ("com.dragon.read.component.shortvideo.impl.fullscreen.ShortSeriesLandFragment"
                    .equals(cls) && "onPause".equals(method)) {
                landscapePause = true;
            }

            if (("gp4.d".equals(cls) && "onLifeCycleOnPause".equals(method))
                    || (SERIES_LIFECYCLE_OBSERVER.equals(cls) && "a".equals(method))
                    || ("androidx.lifecycle.LifecycleRegistry".equals(cls)
                    && ("handleLifecycleEvent".equals(method)
                    || "backwardPass".equals(method)))) {
                lifecyclePause = true;
            }

            // Stable future-compatible rule: only lifecycle callbacks from Hongguo's own
            // short-video component qualify.  Button-click/manual pause stacks do not contain
            // these lifecycle method names.
            if (cls.startsWith("com.dragon.read.component.shortvideo.")
                    && ("onPause".equals(method)
                    || "onStop".equals(method)
                    || "onInvisible".equals(method))) {
                shortVideoLifecycle = true;
            }
        }

        if (fragmentPause && feedInvisible) return 1;

        boolean episodePausePath = shortSeriesSingleP0 && lifecyclePause;
        boolean episodeStopPath = shortSeriesSingleP0 && shortSeriesSingleStop;
        if (episodePausePath || episodeStopPath) return 2;

        if (landscapePause && (fragmentPause || fragmentStop)) return 3;

        if ((fragmentPause || fragmentStop) && shortVideoLifecycle) return 4;

        return 0;
    }

    private void logBlockedPathOnce(int path, String endpoint) {
        switch (path) {
            case 1 -> {
                if (firstFeedBlockedLogged) return;
                firstFeedBlockedLogged = true;
            }
            case 2 -> {
                if (firstEpisodeBlockedLogged) return;
                firstEpisodeBlockedLogged = true;
            }
            case 3 -> {
                if (firstLandscapeBlockedLogged) return;
                firstLandscapeBlockedLogged = true;
            }
            default -> {
                if (firstGenericBlockedLogged) return;
                firstGenericBlockedLogged = true;
            }
        }

        log(Log.INFO, TAG, "HIT Hongguo background pause blocked endpoint="
                + endpoint + " path=" + pathName(path)
                + " package=" + HONGGUO_PACKAGE);
    }

    private static String pathName(int path) {
        return switch (path) {
            case 1 -> "feed";
            case 2 -> "episode";
            case 3 -> "landscape";
            case 4 -> "shortvideo-lifecycle";
            default -> "unknown";
        };
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
