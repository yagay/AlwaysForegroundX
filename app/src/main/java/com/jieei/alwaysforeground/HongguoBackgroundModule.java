package com.jieei.alwaysforeground;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hongguo background-play compatibility.
 *
 * Upgrade-resistant design:
 * 1) Observe framework Activity pause/stop/resume as the stable cause signal.
 * 2) Suppress only pause-like calls that arrive during the short background-transition window.
 * 3) Hook stable player endpoints (TTVideoEngine / ExoPlayer / Media3 / MediaPlayer).
 * 4) Keep version-specific Hongguo hooks only as fallbacks, never as the primary strategy.
 *
 * Manual pause remains available because an ordinary player pause outside a background transition
 * is allowed to proceed.
 */
public final class HongguoBackgroundModule extends XposedModule {
    private static final String TAG = "AlwaysForeground";
    private static final String HONGGUO_PACKAGE = "com.phoenix.read";

    // A short window also covers pause calls posted asynchronously from Activity/Fragment pause.
    private static final long BACKGROUND_TRANSITION_WINDOW_MS = 3000L;

    // Current-version fallbacks. These may change after an app update without breaking the
    // framework-lifecycle + player-endpoint primary strategy.
    private static final String PLAYER_ADAPTER =
            "com.dragon.read.component.shortvideo.impl.v2.view.adapter.a";
    private static final String SERIES_FRAGMENT =
            "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment";
    private static final String SERIES_LIFECYCLE_OBSERVER =
            "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment$g";

    private static final String[] PLAYER_CLASSES = {
            "com.ss.ttvideoengine.TTVideoEngine",
            "com.ss.ttvideoengine.TTVideoEngineImpl",
            "com.google.android.exoplayer2.ExoPlayerImpl",
            "com.google.android.exoplayer2.SimpleExoPlayer",
            "androidx.media3.exoplayer.ExoPlayerImpl",
            "androidx.media3.exoplayer.SimpleExoPlayer"
    };

    private final Set<String> installedPlayerHooks = ConcurrentHashMap.newKeySet();
    private final Set<String> discoveryLogs = ConcurrentHashMap.newKeySet();

    private volatile SharedPreferences preferences;
    private volatile boolean activityPaused;
    private volatile long lastBackgroundTransitionMs;

    private volatile boolean firstLifecycleBlockedLogged;
    private volatile boolean firstFeedBlockedLogged;
    private volatile boolean firstEpisodeBlockedLogged;
    private volatile boolean firstLandscapeBlockedLogged;
    private volatile boolean firstGenericBlockedLogged;
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

        ClassLoader classLoader = param.getClassLoader();

        // Primary, version-resistant path.
        installActivityBackgroundTracking();
        installStablePlayerEndpoints(classLoader);

        // Current-version compatibility fallbacks.
        installEpisodeLifecyclePauseFallback(classLoader);
        installPauseOnlyAdapterFallback(classLoader);

        log(Log.INFO, TAG, "INSTALLED Hongguo resilient background-play strategy"
                + " windowMs=" + BACKGROUND_TRANSITION_WINDOW_MS
                + " process=" + safeProcessName());
    }

    /**
     * Stable framework lifecycle signal.
     *
     * The marker is set BEFORE Activity.onPause/onStop executes, so synchronous Fragment/player
     * pauses inside that lifecycle are visible to player endpoint hooks. Resume clears it.
     */
    private void installActivityBackgroundTracking() {
        hookInstrumentationLifecycle("callActivityOnPause", true);
        hookInstrumentationLifecycle("callActivityOnStop", true);
        hookInstrumentationLifecycle("callActivityOnResume", false);
    }

    private void hookInstrumentationLifecycle(String methodName, boolean enteringBackground) {
        try {
            Method method = Instrumentation.class.getDeclaredMethod(methodName, Activity.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                Activity activity = !args.isEmpty() && args.get(0) instanceof Activity
                        ? (Activity) args.get(0) : null;

                if (enteringBackground) {
                    // Configuration changes are not a genuine background transition.
                    if (activity == null || !activity.isChangingConfigurations()) {
                        activityPaused = true;
                        lastBackgroundTransitionMs = SystemClock.elapsedRealtime();
                    }
                } else {
                    activityPaused = false;
                    lastBackgroundTransitionMs = 0L;
                }

                return chain.proceed();
            });
            log(Log.INFO, TAG, "INSTALLED Hongguo lifecycle marker Instrumentation."
                    + methodName);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "SKIPPED Hongguo lifecycle marker Instrumentation."
                    + methodName + ": " + t, t);
        }
    }

    /**
     * Primary player sinks. App-internal classes may be renamed freely as long as playback still
     * reaches one of these stable player APIs.
     */
    private void installStablePlayerEndpoints(ClassLoader classLoader) {
        installMediaPlayerPause();

        for (String className : PLAYER_CLASSES) {
            installPlayerClass(classLoader, className);
        }
    }

    private void installMediaPlayerPause() {
        try {
            Method method = MediaPlayer.class.getDeclaredMethod("pause");
            method.setAccessible(true);
            installPauseEndpoint(method, "android.media.MediaPlayer.pause", false);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "SKIPPED Hongguo MediaPlayer.pause: " + t, t);
        }
    }

    private void installPlayerClass(ClassLoader classLoader, String className) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            int installed = 0;

            for (Method method : clazz.getDeclaredMethods()) {
                String name = method.getName();

                boolean pause = "pause".equals(name)
                        && method.getParameterCount() == 0
                        && method.getReturnType() == void.class;

                boolean setPlayWhenReady = "setPlayWhenReady".equals(name)
                        && method.getParameterCount() >= 1
                        && method.getParameterTypes()[0] == boolean.class
                        && method.getReturnType() == void.class;

                if (!pause && !setPlayWhenReady) continue;

                method.setAccessible(true);
                installPauseEndpoint(
                        method,
                        className + "." + name,
                        setPlayWhenReady
                );
                installed++;
            }

            if (installed > 0) {
                log(Log.INFO, TAG, "INSTALLED Hongguo stable player class "
                        + className + " methods=" + installed);
            }
        } catch (ClassNotFoundException ignored) {
            // Expected when a particular playback framework is not bundled in this app version.
        } catch (Throwable t) {
            log(Log.WARN, TAG, "SKIPPED Hongguo stable player class "
                    + className + ": " + t, t);
        }
    }

    private void installPauseEndpoint(Method method, String sink, boolean falseBooleanArg) {
        String signature = method.toGenericString();
        if (!installedPlayerHooks.add(signature)) return;

        try {
            hook(method).intercept(chain -> {
                if (getMode() < ModeConfig.MODE_STRONG) return chain.proceed();

                if (falseBooleanArg) {
                    List<Object> args = chain.getArgs();
                    if (args.isEmpty() || !Boolean.FALSE.equals(args.get(0))) {
                        return chain.proceed();
                    }
                }

                int explicitPath = backgroundPausePath();
                boolean stableBackgroundCause = isRecentBackgroundTransition();

                // Do not use Fragment/P0 lifecycle evidence by itself. Episode-to-episode
                // transitions also stop/pause the previous Fragment and must be allowed so
                // autoplay can advance. Only an actual recent Activity background transition
                // authorizes suppressing the player pause.
                if (!stableBackgroundCause) {
                    return chain.proceed();
                }

                if (!firstLifecycleBlockedLogged) {
                    firstLifecycleBlockedLogged = true;
                    log(Log.INFO, TAG, "HIT Hongguo stable player background pause blocked"
                            + " sink=" + sink
                            + " cause=" + (explicitPath != 0
                            ? pathName(explicitPath) : "activity-background-transition")
                            + " package=" + HONGGUO_PACKAGE);
                }

                logDiscoveryOnce(sink);
                return null;
            });

            log(Log.INFO, TAG, "INSTALLED Hongguo stable pause endpoint " + sink);
        } catch (Throwable t) {
            installedPlayerHooks.remove(signature);
            log(Log.WARN, TAG, "SKIPPED Hongguo stable pause endpoint "
                    + sink + ": " + t, t);
        }
    }

    private boolean isRecentBackgroundTransition() {
        if (!activityPaused) return false;

        long started = lastBackgroundTransitionMs;
        if (started <= 0L) return false;

        long elapsed = SystemClock.elapsedRealtime() - started;
        return elapsed >= 0L && elapsed <= BACKGROUND_TRANSITION_WINDOW_MS;
    }

    /**
     * Auto-discovery logging: if a future version changes its internal pause caller, the first
     * app-owned frame is recorded automatically while the generic endpoint still handles it.
     */
    private void logDiscoveryOnce(String sink) {
        StackTraceElement caller = firstAppOwnedCaller();
        String callerText = caller == null ? "unknown" : caller.toString();
        String key = sink + "|" + callerText;
        if (!discoveryLogs.add(key)) return;

        log(Log.INFO, TAG, "AUTO_DISCOVERY Hongguo pause"
                + " sink=" + sink
                + " caller=" + callerText
                + " process=" + safeProcessName());
    }

    private static StackTraceElement firstAppOwnedCaller() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.startsWith("com.dragon.read.")
                    || cls.startsWith("com.phoenix.read.")
                    || cls.startsWith("com.phoenix.")) {
                return frame;
            }
        }
        return null;
    }

    /**
     * Red Fruit 7.3.5.32 fallback:
     * ShortSeriesSingleFragment$g.a / onStop -> P0 -> adapter -> player.pause().
     *
     * Kept intentionally as a fallback. If P0 is renamed later, the stable lifecycle/player
     * strategy above continues to work.
     */
    private void installEpisodeLifecyclePauseFallback(ClassLoader classLoader) {
        try {
            Class<?> clazz = classLoader.loadClass(SERIES_FRAGMENT);
            Method method = clazz.getDeclaredMethod("P0");
            if (method.getParameterCount() != 0 || method.getReturnType() != void.class) {
                return;
            }

            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (getMode() < ModeConfig.MODE_STRONG) return chain.proceed();

                int source = episodeP0BackgroundSource();
                // P0 is also used while replacing an episode Fragment. Never block it solely
                // because its caller is onStop/lifecycle; require the app-level background
                // transition marker so autoplay/next-episode remains intact.
                if (!isRecentBackgroundTransition()) {
                    return chain.proceed();
                }

                if (!firstEpisodeP0BlockedLogged) {
                    firstEpisodeP0BlockedLogged = true;
                    log(Log.INFO, TAG, "HIT Hongguo episode P0 fallback blocked"
                            + " source=" + source
                            + " package=" + HONGGUO_PACKAGE);
                }
                return null;
            });

            log(Log.INFO, TAG, "INSTALLED Hongguo episode P0 fallback "
                    + SERIES_FRAGMENT + ".P0");
        } catch (Throwable t) {
            log(Log.INFO, TAG, "SKIPPED Hongguo episode P0 fallback: " + t);
        }
    }

    /**
     * Secondary fallback for current builds where adapter.a.x() is a pause-only method.
     */
    private void installPauseOnlyAdapterFallback(ClassLoader classLoader) {
        try {
            Class<?> clazz = classLoader.loadClass(PLAYER_ADAPTER);
            Method method = clazz.getDeclaredMethod("x");
            if (method.getParameterCount() != 0 || method.getReturnType() != void.class) {
                return;
            }

            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (getMode() < ModeConfig.MODE_STRONG) return chain.proceed();

                int path = backgroundPausePath();
                // adapter.a.x() is reached during normal episode replacement too. Restrict this
                // fallback to the app-level background transition window.
                if (!isRecentBackgroundTransition()) {
                    return chain.proceed();
                }

                logBlockedPathOnce(
                        path == 0 ? 4 : path,
                        PLAYER_ADAPTER + ".x"
                );
                return null;
            });

            log(Log.INFO, TAG, "INSTALLED Hongguo adapter fallback "
                    + PLAYER_ADAPTER + ".x");
        } catch (Throwable t) {
            log(Log.INFO, TAG, "SKIPPED Hongguo adapter fallback: " + t);
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
     * Existing explicit stack classifier. It is now supplementary evidence rather than the
     * primary compatibility mechanism.
     *
     * @return 0 = unrelated/user pause
     *         1 = home/feed background path
     *         2 = episode/detail background path
     *         3 = landscape/fullscreen background path
     *         4 = other verified short-video lifecycle path
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

        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
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

    /**
     * @return 0 = unrelated P0 call, 1 = lifecycle observer, 2 = Fragment.onStop.
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
