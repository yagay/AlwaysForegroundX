package com.jieei.alwaysforeground;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Narrow Hongguo compatibility hook discovered from automatic playback-endpoint tracing.
 *
 * The captured background chain is:
 * AbsFragment.onPause -> onActivityVisibilityChange -> checkVisibility ->
 * PageVisibilityHelper -> VideoFeedTabFragment.onInvisible -> VideoFeedTabFragmentImpl.Q0 ->
 * SeriesBookMallTabFragment.D9 -> fh -> view.adapter.a.x -> x05.w.pause -> TTVideoEngine.pause.
 *
 * DEX analysis shows a.x() is void/no-arg and only does:
 *   player.isPlaying(); if true -> player.pause();
 * It does not perform Fragment or page-state bookkeeping. Therefore only suppress it when the
 * current stack proves this invocation came from the background lifecycle path.
 */
public final class HongguoBackgroundModule extends XposedModule {
    private static final String TAG = "AlwaysForeground";
    private static final String HONGGUO_PACKAGE = "com.phoenix.read";
    private static final String PLAYER_ADAPTER =
            "com.dragon.read.component.shortvideo.impl.v2.view.adapter.a";

    private volatile SharedPreferences preferences;
    private volatile boolean firstBlockedLogged;

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

        try {
            Class<?> clazz = param.getClassLoader().loadClass(PLAYER_ADAPTER);
            Method method = clazz.getDeclaredMethod("x");
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (getMode() >= ModeConfig.MODE_STRONG && isBackgroundPausePath()) {
                    if (!firstBlockedLogged) {
                        firstBlockedLogged = true;
                        log(Log.INFO, TAG,
                                "HIT Hongguo precise background pause blocked "
                                        + PLAYER_ADAPTER + ".x package=" + HONGGUO_PACKAGE);
                    }
                    return null;
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "INSTALLED Hongguo precise background pause "
                    + PLAYER_ADAPTER + ".x");
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

    private static boolean isBackgroundPausePath() {
        boolean fragmentPause = false;
        boolean feedInvisible = false;
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            String method = frame.getMethodName();

            if (("com.dragon.read.base.AbsFragment".equals(cls) && "onPause".equals(method))
                    || ("androidx.fragment.app.Fragment".equals(cls)
                    && "performPause".equals(method))) {
                fragmentPause = true;
            }

            if (("com.dragon.read.component.biz.impl.bookmall.VideoFeedTabFragment".equals(cls)
                    && "onInvisible".equals(method))
                    || ("com.dragon.read.component.shortvideo.impl.feedtab.VideoFeedTabFragmentImpl"
                    .equals(cls) && "Q0".equals(method))) {
                feedInvisible = true;
            }

            if (fragmentPause && feedInvisible) return true;
        }
        return false;
    }
}
