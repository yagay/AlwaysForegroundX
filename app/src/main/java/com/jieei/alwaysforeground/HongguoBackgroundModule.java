package com.jieei.alwaysforeground;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Narrow Hongguo compatibility hook discovered from automatic playback-endpoint tracing.
 *
 * Home/feed background chain:
 * AbsFragment.onPause -> VideoFeedTabFragment.onInvisible -> VideoFeedTabFragmentImpl.Q0 ->
 * SeriesBookMallTabFragment.D9 -> view.adapter.a.x -> x05.w.pause -> TTVideoEngine.pause.
 *
 * Episode/detail has been observed through two background lifecycle paths:
 * 1) Fragment.performPause/LifecycleRegistry -> gp4.d.onLifeCycleOnPause ->
 *    ShortSeriesSingleFragment.P0 -> view.adapter.f.x -> view.adapter.a.x -> pause.
 * 2) Fragment.performStop -> ShortSeriesSingleFragment.onStop ->
 *    ShortSeriesSingleFragment.P0 -> view.adapter.f.x -> view.adapter.a.x -> pause.
 *
 * DEX analysis shows a.x() is void/no-arg and only does:
 *   player.isPlaying(); if true -> player.pause();
 * It does not perform Fragment or page-state bookkeeping. Therefore only suppress it when the
 * current stack proves this invocation came from one of the captured background lifecycle paths.
 */
public final class HongguoBackgroundModule extends XposedModule {
    private static final String TAG = "AlwaysForeground";
    private static final String HONGGUO_PACKAGE = "com.phoenix.read";
    private static final String PLAYER_ADAPTER =
            "com.dragon.read.component.shortvideo.impl.v2.view.adapter.a";

    private volatile SharedPreferences preferences;
    private volatile boolean firstFeedBlockedLogged;
    private volatile boolean firstEpisodeBlockedLogged;

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
            log(Log.INFO, TAG, "SKIPPED Hongguo precise background pause in process="
                    + safeProcessName());
            return;
        }

        try {
            Class<?> clazz = param.getClassLoader().loadClass(PLAYER_ADAPTER);
            Method method = clazz.getDeclaredMethod("x");
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (getMode() < ModeConfig.MODE_STRONG) return chain.proceed();

                int path = backgroundPausePath();
                if (path == 1) {
                    if (!firstFeedBlockedLogged) {
                        firstFeedBlockedLogged = true;
                        log(Log.INFO, TAG,
                                "HIT Hongguo feed background pause blocked "
                                        + PLAYER_ADAPTER + ".x package=" + HONGGUO_PACKAGE);
                    }
                    return null;
                }
                if (path == 2) {
                    if (!firstEpisodeBlockedLogged) {
                        firstEpisodeBlockedLogged = true;
                        log(Log.INFO, TAG,
                                "HIT Hongguo episode background pause blocked "
                                        + PLAYER_ADAPTER + ".x package=" + HONGGUO_PACKAGE);
                    }
                    return null;
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "INSTALLED Hongguo precise background pause "
                    + PLAYER_ADAPTER + ".x process=" + safeProcessName());
        } catch (Throwable t) {
            log(Log.WARN, TAG, "SKIPPED Hongguo precise background pause: " + t, t);
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
     * @return 0 = unrelated pause, 1 = home/feed background path, 2 = episode/detail path.
     */
    private static int backgroundPausePath() {
        boolean fragmentPause = false;
        boolean fragmentStop = false;
        boolean feedInvisible = false;
        boolean shortSeriesSingleP0 = false;
        boolean shortSeriesSingleStop = false;
        boolean lifecyclePause = false;

        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            String method = frame.getMethodName();

            if (("com.dragon.read.base.AbsFragment".equals(cls) && "onPause".equals(method))
                    || ("androidx.fragment.app.Fragment".equals(cls)
                    && "performPause".equals(method))) {
                fragmentPause = true;
            }

            if (("androidx.fragment.app.Fragment".equals(cls) && "performStop".equals(method))
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

            if ("com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment"
                    .equals(cls)) {
                if ("P0".equals(method)) shortSeriesSingleP0 = true;
                if ("onStop".equals(method)) shortSeriesSingleStop = true;
            }

            if (("gp4.d".equals(cls) && "onLifeCycleOnPause".equals(method))
                    || ("androidx.lifecycle.LifecycleRegistry".equals(cls)
                    && ("handleLifecycleEvent".equals(method)
                    || "backwardPass".equals(method)))) {
                lifecyclePause = true;
            }
        }

        if (fragmentPause && feedInvisible) return 1;

        boolean episodePausePath = fragmentPause && shortSeriesSingleP0 && lifecyclePause;
        boolean episodeStopPath = fragmentStop && shortSeriesSingleP0 && shortSeriesSingleStop;
        if (episodePausePath || episodeStopPath) return 2;

        return 0;
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
