package com.jieei.alwaysforeground;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Generic background-runtime compatibility layer.
 *
 * Design rule: never pretend that an Activity/Window is visually foreground. The UI lifecycle,
 * task state and window focus remain real. We only:
 * 1) soften app-side background/power restriction queries; and
 * 2) in strong mode, restore a player that was paused specifically by a genuine app-background
 *    transition, but only after giving the app time to start its own native background player.
 *
 * This avoids the old failure mode where "always foreground" caused apps to open Activities or
 * prevented their own background-player handoff.
 */
public final class AlwaysForegroundModule extends XposedModule {
    private static final String TAG = "AlwaysForeground";
    private static final String MODULE_PACKAGE = "com.jieei.alwaysforeground";
    private static final String HONGGUO_PACKAGE = "com.phoenix.read";

    private static final long BACKGROUND_CONFIRM_MS = 450L;
    private static final long CONTINUITY_RESUME_MS = 700L;
    private static final long ASYNC_LIFECYCLE_CAUSE_MS = 350L;
    private static final long NATIVE_HANDOFF_GRACE_MS = 1800L;
    private static final int MAX_HOOK_EVENTS = 160;

    private static final String[] PLAYER_CLASSES = {
            "com.ss.ttvideoengine.TTVideoEngine",
            "com.ss.ttvideoengine.TTVideoEngineImpl",
            "com.google.android.exoplayer2.ExoPlayerImpl",
            "com.google.android.exoplayer2.SimpleExoPlayer",
            "androidx.media3.exoplayer.ExoPlayerImpl",
            "androidx.media3.exoplayer.SimpleExoPlayer"
    };

    private final Set<String> firstHitLogs = ConcurrentHashMap.newKeySet();
    private final Set<String> endpointHooks = ConcurrentHashMap.newKeySet();
    private final Set<Integer> resumedActivities = ConcurrentHashMap.newKeySet();
    private final Set<Integer> knownPlayingPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Integer, PendingResume> pendingResumes =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastEndpointEvents = new ConcurrentHashMap<>();
    private final AtomicInteger hookEventCounter = new AtomicInteger();
    private final AtomicInteger transitionSerial = new AtomicInteger();
    private final ThreadLocal<Integer> lifecycleCauseDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Boolean> tracingEndpoint = ThreadLocal.withInitial(() -> false);

    private volatile String activePackage;
    private volatile Handler mainHandler;
    private volatile boolean appInBackground;
    private volatile long lastLifecyclePauseElapsed;

    private static final class PendingResume {
        final WeakReference<Object> player;
        final String sink;
        final boolean setPlayWhenReady;
        final int transition;
        final long queuedAt;

        PendingResume(Object player, String sink, boolean setPlayWhenReady,
                      int transition, long queuedAt) {
            this.player = new WeakReference<>(player);
            this.sink = sink;
            this.setPlayWhenReady = setPlayWhenReady;
            this.transition = transition;
            this.queuedAt = queuedAt;
        }
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            SharedPreferences prefs = getRemotePreferences(ModeConfig.REMOTE_GROUP);
            TargetConfig.initialize(prefs);
            log(Log.INFO, TAG, "remote preferences ready; mode=" + TargetConfig.getMode());
        } catch (Throwable t) {
            TargetConfig.initialize(null);
            log(Log.WARN, TAG,
                    "remote preferences unavailable; falling back to standard mode", t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;
        if (MODULE_PACKAGE.equals(param.getPackageName())) return;

        activePackage = param.getPackageName();
        mainHandler = new Handler(Looper.getMainLooper());

        log(Log.INFO, TAG, "installing generic background engine for " + activePackage
                + ", mode=" + TargetConfig.getMode());

        installRestrictionCompatibilityHooks();
        installLifecycleCauseEngine();
        installLifecycleDiagnostics();

        // Intentionally removed from the generic path:
        // PowerManager.isInteractive/isScreenOn spoofing,
        // Keyguard spoofing,
        // ActivityManager process-importance spoofing,
        // Activity.hasWindowFocus spoofing.
        // Those APIs describe real UI/process foreground state and cause app-specific side effects.
        log(Log.INFO, TAG, "GENERIC_UI_STATE real package=" + activePackage);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        if (MODULE_PACKAGE.equals(param.getPackageName())) return;

        installGenericMediaContinuity(param.getClassLoader());
        installDestructivePlaybackDiagnostics(param.getClassLoader());

        if (HONGGUO_PACKAGE.equals(param.getPackageName())) {
            installHongguoFragmentDiagnostics(param.getClassLoader());
        }
    }

    /**
     * App-side restriction hints only. These do not claim that the Activity, Window or process is
     * foreground; they only keep apps from voluntarily degrading behavior because of power/background
     * policy queries.
     */
    private void installRestrictionCompatibilityHooks() {
        hookBoolean(ActivityManager.class, "isBackgroundRestricted",
                false, ModeConfig.MODE_STANDARD);

        hookBoolean(PowerManager.class, "isDeviceIdleMode",
                false, ModeConfig.MODE_ENHANCED);
        hookBoolean(PowerManager.class, "isPowerSaveMode",
                false, ModeConfig.MODE_ENHANCED);
        hookIgnoringBatteryOptimizations();
    }

    private void hookIgnoringBatteryOptimizations() {
        final String label = "PowerManager.isIgnoringBatteryOptimizations";
        try {
            Method method = PowerManager.class.getDeclaredMethod(
                    "isIgnoringBatteryOptimizations", String.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (TargetConfig.getMode() < ModeConfig.MODE_ENHANCED) {
                    return chain.proceed();
                }
                List<Object> args = chain.getArgs();
                if (!args.isEmpty()
                        && args.get(0) instanceof String packageName
                        && packageName.equals(activePackage)) {
                    logFirstHit(label);
                    return true;
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    /**
     * Generic lifecycle-cause engine.
     *
     * A pause is not immediately treated as "the app is background": another Activity in the same
     * task may resume. We wait briefly, then confirm background only if no Activity resumed.
     */
    private void installLifecycleCauseEngine() {
        hookLifecycle("callActivityOnResume", 1);
        hookLifecycle("callActivityOnPause", 2);
        hookLifecycle("callActivityOnStop", 3);
    }

    private void hookLifecycle(String methodName, int event) {
        try {
            Method method = Instrumentation.class.getDeclaredMethod(methodName, Activity.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                Activity activity = !args.isEmpty() && args.get(0) instanceof Activity
                        ? (Activity) args.get(0) : null;
                int id = activity == null ? 0 : System.identityHashCode(activity);

                if (event == 1) {
                    int serial = transitionSerial.incrementAndGet();
                    if (id != 0) resumedActivities.add(id);
                    appInBackground = false;
                    clearPendingResumes("activity-resumed", false);
                    Object result = chain.proceed();
                    logTransitionOnce("GENERIC_FOREGROUND serial=" + serial
                            + " activity=" + className(activity));
                    return result;
                }

                boolean configChange = activity != null && activity.isChangingConfigurations();
                if (event == 2) {
                    if (id != 0) resumedActivities.remove(id);
                    if (!configChange) {
                        lastLifecyclePauseElapsed = SystemClock.elapsedRealtime();
                    }
                }

                int serial = transitionSerial.incrementAndGet();
                int depth = lifecycleCauseDepth.get();
                lifecycleCauseDepth.set(depth + 1);
                try {
                    return chain.proceed();
                } finally {
                    lifecycleCauseDepth.set(depth);
                    if (!configChange) scheduleBackgroundConfirmation(serial);
                }
            });
            logInstalled("generic lifecycle " + methodName);
        } catch (Throwable t) {
            logSkipped("generic lifecycle " + methodName, t);
        }
    }

    private void scheduleBackgroundConfirmation(int serial) {
        Handler handler = mainHandler;
        if (handler == null) return;
        handler.postDelayed(() -> {
            if (serial != transitionSerial.get()) return;
            if (!resumedActivities.isEmpty()) return;

            appInBackground = true;
            logTransitionOnce("GENERIC_BACKGROUND confirmed serial=" + serial
                    + " package=" + activePackage);
        }, BACKGROUND_CONFIRM_MS);
    }

    private boolean isLifecycleTriggeredPause() {
        if (lifecycleCauseDepth.get() > 0) return true;

        long last = lastLifecyclePauseElapsed;
        if (last <= 0L || !resumedActivities.isEmpty()) return false;
        long elapsed = SystemClock.elapsedRealtime() - last;
        return elapsed >= 0L && elapsed <= ASYNC_LIFECYCLE_CAUSE_MS;
    }

    /**
     * Media continuity engine.
     *
     * We never block the original pause. If it was caused by Activity lifecycle while the player
     * was actually playing, queue a possible resume. If the app starts/resumes any player itself
     * during the handoff grace period, all queued resumes are cancelled and the app's native
     * background implementation wins.
     */
    private void installGenericMediaContinuity(ClassLoader classLoader) {
        installMediaPlayerContinuity();

        for (String className : PLAYER_CLASSES) {
            installPlayerClassContinuity(classLoader, className);
        }
    }

    private void installMediaPlayerContinuity() {
        try {
            Method pause = MediaPlayer.class.getDeclaredMethod("pause");
            pause.setAccessible(true);
            installPauseEndpoint(pause, "android.media.MediaPlayer.pause", false);
        } catch (Throwable t) {
            logSkipped("continuity MediaPlayer.pause", t);
        }

        try {
            Method start = MediaPlayer.class.getDeclaredMethod("start");
            start.setAccessible(true);
            installStartEndpoint(start, "android.media.MediaPlayer.start", false);
        } catch (Throwable t) {
            logSkipped("continuity MediaPlayer.start", t);
        }
    }

    private void installPlayerClassContinuity(ClassLoader classLoader, String className) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            int installed = 0;

            for (Method method : clazz.getDeclaredMethods()) {
                String name = method.getName();
                boolean pause = "pause".equals(name)
                        && method.getParameterCount() == 0
                        && method.getReturnType() == void.class;
                boolean start = ("play".equals(name)
                        || "start".equals(name)
                        || "resume".equals(name))
                        && method.getParameterCount() == 0
                        && method.getReturnType() == void.class;
                boolean playWhenReady = "setPlayWhenReady".equals(name)
                        && method.getParameterCount() >= 1
                        && method.getParameterTypes()[0] == boolean.class
                        && method.getReturnType() == void.class;

                if (!pause && !start && !playWhenReady) continue;
                method.setAccessible(true);

                if (pause) {
                    installPauseEndpoint(method, className + ".pause", false);
                } else if (start) {
                    installStartEndpoint(method, className + "." + name, false);
                } else {
                    installBooleanPlayEndpoint(method, className + ".setPlayWhenReady");
                }
                installed++;
            }

            if (installed > 0) {
                logInstalled("generic media class " + className + " methods=" + installed);
            }
        } catch (ClassNotFoundException ignored) {
            // Player framework not bundled by this target.
        } catch (Throwable t) {
            logSkipped("generic media class " + className, t);
        }
    }

    private void installBooleanPlayEndpoint(Method method, String sink) {
        String signature = method.toGenericString();
        if (!endpointHooks.add(signature)) return;

        try {
            hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                boolean requestedPlay = !args.isEmpty() && Boolean.TRUE.equals(args.get(0));

                if (TargetConfig.isDiagnosticsActiveFor(activePackage)) {
                    recordPlaybackEndpoint(chain.getThisObject(), sink, args);
                }

                if (requestedPlay) {
                    Object result = chain.proceed();
                    notePlaybackStarted(chain.getThisObject(), sink);
                    return result;
                }

                Object player = chain.getThisObject();
                int playerId = player == null ? 0 : System.identityHashCode(player);
                boolean lifecycle = TargetConfig.getMode() >= ModeConfig.MODE_STRONG
                        && isLifecycleTriggeredPause();
                boolean wasPlaying = playerId != 0 && knownPlayingPlayers.contains(playerId);
                if (!wasPlaying) {
                    Boolean queried = queryPlaying(player);
                    wasPlaying = Boolean.TRUE.equals(queried);
                }

                Object result = chain.proceed();
                if (playerId != 0) knownPlayingPlayers.remove(playerId);
                if (lifecycle && wasPlaying && player != null) {
                    queuePendingResume(player, sink, true);
                }
                return result;
            });
            logInstalled("continuity endpoint " + sink);
        } catch (Throwable t) {
            endpointHooks.remove(signature);
            logSkipped("continuity endpoint " + sink, t);
        }
    }

    private void installPauseEndpoint(Method method, String sink, boolean unused) {
        String signature = method.toGenericString();
        if (!endpointHooks.add(signature)) return;

        try {
            hook(method).intercept(chain -> {
                if (TargetConfig.isDiagnosticsActiveFor(activePackage)) {
                    recordPlaybackEndpoint(chain.getThisObject(), sink, chain.getArgs());
                }

                Object player = chain.getThisObject();
                int playerId = player == null ? 0 : System.identityHashCode(player);
                boolean lifecycle = TargetConfig.getMode() >= ModeConfig.MODE_STRONG
                        && isLifecycleTriggeredPause();
                boolean wasPlaying = playerId != 0 && knownPlayingPlayers.contains(playerId);
                if (!wasPlaying) {
                    Boolean queried = queryPlaying(player);
                    wasPlaying = Boolean.TRUE.equals(queried);
                }

                Object result = chain.proceed();
                if (playerId != 0) knownPlayingPlayers.remove(playerId);
                if (lifecycle && wasPlaying && player != null) {
                    queuePendingResume(player, sink, false);
                }
                return result;
            });
            logInstalled("continuity endpoint " + sink);
        } catch (Throwable t) {
            endpointHooks.remove(signature);
            logSkipped("continuity endpoint " + sink, t);
        }
    }

    private void installStartEndpoint(Method method, String sink, boolean unused) {
        String signature = method.toGenericString();
        if (!endpointHooks.add(signature)) return;

        try {
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                notePlaybackStarted(chain.getThisObject(), sink);
                return result;
            });
            logInstalled("continuity start endpoint " + sink);
        } catch (Throwable t) {
            endpointHooks.remove(signature);
            logSkipped("continuity start endpoint " + sink, t);
        }
    }

    private void notePlaybackStarted(Object player, String sink) {
        if (player == null) return;
        int playerId = System.identityHashCode(player);
        knownPlayingPlayers.add(playerId);

        long elapsed = SystemClock.elapsedRealtime() - lastLifecyclePauseElapsed;
        if (lastLifecyclePauseElapsed > 0L
                && elapsed >= 0L
                && elapsed <= NATIVE_HANDOFF_GRACE_MS
                && !pendingResumes.isEmpty()) {
            int count = pendingResumes.size();
            clearPendingResumes("native-player-start:" + sink, false);
            log(Log.INFO, TAG, "GENERIC_CONTINUITY native handoff won"
                    + " sink=" + sink
                    + " cancelled=" + count
                    + " package=" + activePackage);
        } else {
            pendingResumes.remove(playerId);
        }
    }

    private void queuePendingResume(Object player, String sink, boolean setPlayWhenReady) {
        int playerId = System.identityHashCode(player);
        int serial = transitionSerial.get();
        PendingResume pending = new PendingResume(
                player,
                sink,
                setPlayWhenReady,
                serial,
                SystemClock.elapsedRealtime());
        pendingResumes.put(playerId, pending);

        log(Log.INFO, TAG, "GENERIC_CONTINUITY queued"
                + " sink=" + sink
                + " serial=" + serial
                + " package=" + activePackage);

        Handler handler = mainHandler;
        if (handler == null) return;
        handler.postDelayed(() -> tryResumePending(playerId, pending), CONTINUITY_RESUME_MS);
    }

    private void tryResumePending(int playerId, PendingResume pending) {
        PendingResume current = pendingResumes.get(playerId);
        if (current != pending) return;

        if (pending.transition != transitionSerial.get()) {
            pendingResumes.remove(playerId, pending);
            return;
        }
        if (!appInBackground || !resumedActivities.isEmpty()) {
            pendingResumes.remove(playerId, pending);
            return;
        }

        Object player = pending.player.get();
        if (player == null) {
            pendingResumes.remove(playerId, pending);
            return;
        }

        Boolean alreadyPlaying = queryPlaying(player);
        if (Boolean.TRUE.equals(alreadyPlaying)
                || knownPlayingPlayers.contains(playerId)) {
            pendingResumes.remove(playerId, pending);
            return;
        }

        boolean resumed = false;
        try {
            if (pending.setPlayWhenReady) {
                resumed = invokeBooleanMethod(player, "setPlayWhenReady", true);
            } else {
                resumed = invokeNoArg(player, "play")
                        || invokeNoArg(player, "start")
                        || invokeNoArg(player, "resume");
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "GENERIC_CONTINUITY resume failed"
                    + " sink=" + pending.sink + " error=" + t);
        } finally {
            pendingResumes.remove(playerId, pending);
        }

        if (resumed) {
            knownPlayingPlayers.add(playerId);
            log(Log.INFO, TAG, "GENERIC_CONTINUITY resumed"
                    + " sink=" + pending.sink
                    + " delayMs=" + (SystemClock.elapsedRealtime() - pending.queuedAt)
                    + " package=" + activePackage);
        } else {
            log(Log.INFO, TAG, "GENERIC_CONTINUITY no compatible resume method"
                    + " sink=" + pending.sink
                    + " player=" + player.getClass().getName());
        }
    }

    private static Boolean queryPlaying(Object player) {
        if (player == null) return null;
        if (player instanceof MediaPlayer mediaPlayer) {
            try {
                return mediaPlayer.isPlaying();
            } catch (Throwable ignored) {
                return null;
            }
        }

        for (String methodName : new String[]{
                "isPlaying", "getPlayWhenReady", "isPlayWhenReady", "isStarted"
        }) {
            try {
                Method method = player.getClass().getMethod(methodName);
                if (method.getParameterCount() != 0) continue;
                Object value = method.invoke(player);
                if (value instanceof Boolean b) return b;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean invokeNoArg(Object receiver, String methodName) {
        try {
            Method method = findMethod(receiver.getClass(), methodName);
            if (method == null || method.getParameterCount() != 0) return false;
            method.setAccessible(true);
            method.invoke(receiver);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean invokeBooleanMethod(Object receiver, String methodName, boolean value) {
        try {
            Class<?> c = receiver.getClass();
            while (c != null) {
                for (Method method : c.getDeclaredMethods()) {
                    if (!methodName.equals(method.getName())) continue;
                    if (method.getParameterCount() < 1) continue;
                    if (method.getParameterTypes()[0] != boolean.class) continue;
                    Object[] args = new Object[method.getParameterCount()];
                    args[0] = value;
                    for (int i = 1; i < args.length; i++) {
                        Class<?> type = method.getParameterTypes()[i];
                        if (type == boolean.class) args[i] = false;
                        else if (type == int.class) args[i] = 0;
                        else if (type == long.class) args[i] = 0L;
                        else if (type == float.class) args[i] = 0f;
                        else if (type == double.class) args[i] = 0d;
                        else args[i] = null;
                    }
                    method.setAccessible(true);
                    method.invoke(receiver, args);
                    return true;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private void clearPendingResumes(String reason, boolean logAlways) {
        int count = pendingResumes.size();
        pendingResumes.clear();
        if (logAlways || count > 0) {
            log(Log.INFO, TAG, "GENERIC_CONTINUITY cleared"
                    + " reason=" + reason
                    + " count=" + count
                    + " package=" + activePackage);
        }
    }

    /**
     * Keep destructive endpoints diagnostic-only and mark players as no longer resumable.
     */
    private void installDestructivePlaybackDiagnostics(ClassLoader classLoader) {
        installExactDiagnostic(MediaPlayer.class, "stop");
        installExactDiagnostic(MediaPlayer.class, "reset");
        installExactDiagnostic(MediaPlayer.class, "release");
        installExactDiagnostic(AudioTrack.class, "pause");
        installExactDiagnostic(AudioTrack.class, "stop");
        installExactDiagnostic(AudioTrack.class, "flush");
        installExactDiagnostic(AudioTrack.class, "release");

        for (String className : PLAYER_CLASSES) {
            try {
                Class<?> clazz = classLoader.loadClass(className);
                for (Method method : clazz.getDeclaredMethods()) {
                    String name = method.getName();
                    if (!"stop".equals(name)
                            && !"reset".equals(name)
                            && !"release".equals(name)) {
                        continue;
                    }
                    method.setAccessible(true);
                    installDiagnosticEndpoint(method, className + "." + name);
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                logSkipped("destructive diagnostics " + className, t);
            }
        }
    }

    private void installExactDiagnostic(Class<?> clazz, String methodName) {
        try {
            Method method = clazz.getDeclaredMethod(methodName);
            method.setAccessible(true);
            installDiagnosticEndpoint(method, clazz.getName() + "." + methodName);
        } catch (Throwable t) {
            logSkipped("diagnostic " + clazz.getName() + "." + methodName, t);
        }
    }

    private void installDiagnosticEndpoint(Method method, String sink) {
        String signature = method.toGenericString();
        if (!endpointHooks.add(signature)) return;

        try {
            hook(method).intercept(chain -> {
                Object player = chain.getThisObject();
                if (TargetConfig.isDiagnosticsActiveFor(activePackage)) {
                    recordPlaybackEndpoint(player, sink, chain.getArgs());
                }
                Object result = chain.proceed();
                if (player != null) {
                    int id = System.identityHashCode(player);
                    knownPlayingPlayers.remove(id);
                    pendingResumes.remove(id);
                }
                return result;
            });
            logInstalled("diagnostic endpoint " + sink);
        } catch (Throwable t) {
            endpointHooks.remove(signature);
            logSkipped("diagnostic endpoint " + sink, t);
        }
    }

    private void recordPlaybackEndpoint(Object receiver, String sink, List<Object> args) {
        if (Boolean.TRUE.equals(tracingEndpoint.get())) return;
        if (hookEventCounter.get() >= MAX_HOOK_EVENTS) return;
        tracingEndpoint.set(true);
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            String suggestion = findSuggestedCaller(stack);
            String dedupeKey = sink + '|' + suggestion;
            long now = System.currentTimeMillis();
            Long previous = lastEndpointEvents.put(dedupeKey, now);
            if (previous != null && now - previous < 500L) return;

            int event = hookEventCounter.incrementAndGet();
            if (event > MAX_HOOK_EVENTS) return;

            String process;
            try {
                process = Application.getProcessName();
            } catch (Throwable ignored) {
                process = activePackage;
            }

            String receiverClass = receiver == null ? "static" : receiver.getClass().getName();
            log(Log.INFO, TAG, "HOOK_CANDIDATE event=" + event
                    + " sink=" + sink
                    + " receiver=" + receiverClass
                    + " args=" + summarizeArgs(args)
                    + " background=" + appInBackground
                    + " lifecycleCause=" + isLifecycleTriggeredPause()
                    + " package=" + activePackage
                    + " process=" + process
                    + " pid=" + android.os.Process.myPid()
                    + " thread=" + Thread.currentThread().getName()
                    + " timeMs=" + now);

            if (suggestion != null) {
                log(Log.INFO, TAG, "HOOK_SUGGEST event=" + event
                        + " score=" + scoreFrame(suggestion)
                        + " caller=" + suggestion);
            }

            int outputIndex = 0;
            for (StackTraceElement frame : stack) {
                String className = frame.getClassName();
                if (className.equals(Thread.class.getName())) continue;
                if (className.equals(AlwaysForegroundModule.class.getName())) continue;
                int score = scoreFrame(className + "." + frame.getMethodName());
                log(Log.INFO, TAG, "HOOK_STACK event=" + event
                        + " #" + outputIndex
                        + " score=" + score
                        + " " + frame);
                outputIndex++;
                if (outputIndex >= 30) break;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "HOOK_TRACE_FAILED sink=" + sink + " error=" + t);
        } finally {
            tracingEndpoint.set(false);
        }
    }

    private String findSuggestedCaller(StackTraceElement[] stack) {
        String best = null;
        int bestScore = -1;
        for (StackTraceElement frame : stack) {
            String candidate = frame.getClassName() + "." + frame.getMethodName();
            int score = scoreFrame(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore >= 70 ? best : null;
    }

    private int scoreFrame(String frame) {
        if (frame == null) return 0;
        if (activePackage != null && frame.startsWith(activePackage + ".")) return 110;
        if (frame.startsWith("com.dragon.read.") || frame.startsWith("com.phoenix.")) return 100;
        if (frame.startsWith("com.ss.ttvideoengine.")
                || frame.startsWith("com.google.android.exoplayer2.")
                || frame.startsWith("androidx.media3.")) return 35;
        if (frame.startsWith("android.media.")) return 20;
        if (frame.startsWith("android.") || frame.startsWith("androidx.")
                || frame.startsWith("java.") || frame.startsWith("javax.")
                || frame.startsWith("kotlin.") || frame.startsWith("dalvik.")
                || frame.startsWith("libcore.") || frame.startsWith("io.github.libxposed.")
                || frame.startsWith("org.lsposed.")
                || frame.startsWith("com.jieei.alwaysforeground.")) return 0;
        return 70;
    }

    private static String summarizeArgs(List<Object> args) {
        if (args == null || args.isEmpty()) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < args.size() && i < 4; i++) {
            if (i > 0) out.append(',');
            Object value = args.get(i);
            if (value == null) {
                out.append("null");
            } else if (value instanceof Number
                    || value instanceof Boolean
                    || value instanceof CharSequence) {
                String text = String.valueOf(value);
                out.append(text.length() > 80 ? text.substring(0, 80) : text);
            } else {
                out.append(value.getClass().getName());
            }
        }
        if (args.size() > 4) out.append(",...");
        return out.append(']').toString();
    }

    private void installHongguoFragmentDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> fragmentClass = classLoader.loadClass("androidx.fragment.app.Fragment");
            hookFragmentLifecycle(fragmentClass, "performResume");
            hookFragmentLifecycle(fragmentClass, "performPause");
            hookFragmentLifecycle(fragmentClass, "performStop");
        } catch (Throwable t) {
            logSkipped("Hongguo Fragment diagnostics", t);
        }
    }

    private void hookFragmentLifecycle(Class<?> fragmentClass, String methodName) {
        final String label = "Hongguo Fragment." + methodName;
        try {
            Method method = fragmentClass.getDeclaredMethod(methodName);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (TargetConfig.isDiagnosticsActiveFor(activePackage)) {
                    Object fragment = chain.getThisObject();
                    if (fragment != null) {
                        log(Log.INFO, TAG, "FRAGMENT " + methodName
                                + " class=" + fragment.getClass().getName());
                    }
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void installLifecycleDiagnostics() {
        hookInstrumentationDiagnostic("callActivityOnStart", Activity.class);
        hookInstrumentationDiagnostic("callActivityOnRestart", Activity.class);
        hookInstrumentationDiagnostic("callActivityOnDestroy", Activity.class);
        hookInstrumentationDiagnostic("callActivityOnSaveInstanceState",
                Activity.class, Bundle.class);
    }

    private void hookInstrumentationDiagnostic(String methodName, Class<?>... parameterTypes) {
        String label = "Instrumentation." + methodName;
        try {
            Method method = Instrumentation.class.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (TargetConfig.isDiagnosticsActiveFor(activePackage)) {
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty() && args.get(0) instanceof Activity activity) {
                        log(Log.INFO, TAG, "LIFECYCLE " + methodName
                                + " activity=" + activity.getClass().getName()
                                + " finishing=" + activity.isFinishing()
                                + " changingConfig=" + activity.isChangingConfigurations()
                                + " background=" + appInBackground
                                + " package=" + activePackage);
                    }
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void hookBoolean(Class<?> clazz, String methodName, boolean spoofValue, int minMode) {
        String label = clazz.getSimpleName() + "." + methodName;
        try {
            Method method = clazz.getDeclaredMethod(methodName);
            method.setAccessible(true);
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
            log(Log.INFO, TAG, "HIT " + hookName
                    + " package=" + activePackage
                    + " mode=" + TargetConfig.getMode());
        }
    }

    private void logTransitionOnce(String message) {
        String key = activePackage + ':' + message;
        if (firstHitLogs.add(key)) log(Log.INFO, TAG, message);
    }

    private static String className(Activity activity) {
        return activity == null ? "null" : activity.getClass().getName();
    }
}
