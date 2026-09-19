package com.jieei.alwaysforeground;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
import java.lang.reflect.Field;
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

    private static final long BACKGROUND_CONFIRM_MS = 450L;
    // Give onStop/native player handoff enough time to run before generic fallback resumes.
    private static final long CONTINUITY_RESUME_MS = 1000L;
    private static final long ASYNC_LIFECYCLE_CAUSE_MS = 350L;
    private static final long NATIVE_HANDOFF_GRACE_MS = 1800L;
    // A module-triggered resume can synchronously/async echo back through app visibility
    // callbacks and request pause again. Guard only the same resumed player for a short window.
    private static final long CONTINUITY_ECHO_GUARD_MS = 1600L;
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
    private final ConcurrentHashMap<Integer, Long> continuityLeaseUntil =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastEndpointEvents = new ConcurrentHashMap<>();
    private final AtomicInteger hookEventCounter = new AtomicInteger();
    private final AtomicInteger transitionSerial = new AtomicInteger();
    private final ThreadLocal<Integer> lifecycleCauseDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Integer> pauseCascadeDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Boolean> tracingEndpoint = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> selfContinuityResume =
            ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> virtualLaunchBypass =
            ThreadLocal.withInitial(() -> false);
    private final BackgroundVirtualDisplay backgroundVirtualDisplay =
            new BackgroundVirtualDisplay();
    private final SmallWindowController smallWindowController =
            new SmallWindowController();

    private volatile String activePackage;
    private volatile Handler mainHandler;
    private volatile boolean appInBackground;
    private volatile boolean rootVirtualForegroundRequested;
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
        installVirtualForegroundQueryHooks();
        installLifecycleCauseEngine();
        installBackgroundActivityVirtualization();
        installLifecycleDiagnostics();

        log(Log.INFO, TAG, "VIRTUAL_FOREGROUND generic framework hooks ready"
                + " package=" + activePackage);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        if (MODULE_PACKAGE.equals(param.getPackageName())) return;

        installProcessLifecycleVirtualization(param.getClassLoader());
        installGenericMediaContinuity(param.getClassLoader());
        installDestructivePlaybackDiagnostics(param.getClassLoader());
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
     * Generic virtual-foreground query layer.
     *
     * Android's real Activity lifecycle still runs. Only foreground/status queries made inside the
     * target process are virtualized while the app is actually backgrounded in strong mode.
     */
    private void installVirtualForegroundQueryHooks() {
        hookVirtualBoolean(Activity.class, "hasWindowFocus", true);
        hookVirtualBoolean(PowerManager.class, "isInteractive", true);
        hookVirtualBoolean(PowerManager.class, "isScreenOn", true);
        hookVirtualBoolean(KeyguardManager.class, "isKeyguardLocked", false);
        hookVirtualBoolean(KeyguardManager.class, "isDeviceLocked", false);

        hookMyMemoryStateVirtualization();
        hookRunningProcessesVirtualization();
        hookUidImportanceVirtualization();
        hookPackageImportanceVirtualization();
    }

    private boolean isVirtualForegroundActive() {
        return TargetConfig.getMode() >= ModeConfig.MODE_STRONG && appInBackground;
    }

    private void hookVirtualBoolean(
            Class<?> clazz,
            String methodName,
            boolean value
    ) {
        final String label = "VIRTUAL_FOREGROUND "
                + clazz.getSimpleName() + "." + methodName;
        try {
            Method method = clazz.getDeclaredMethod(methodName);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                if (isVirtualForegroundActive()) {
                    logFirstHit(label);
                    return value;
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void hookMyMemoryStateVirtualization() {
        final String label = "VIRTUAL_FOREGROUND ActivityManager.getMyMemoryState";
        try {
            Method method = ActivityManager.class.getDeclaredMethod(
                    "getMyMemoryState",
                    ActivityManager.RunningAppProcessInfo.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (!isVirtualForegroundActive()) return result;

                List<Object> args = chain.getArgs();
                if (!args.isEmpty()
                        && args.get(0) instanceof ActivityManager.RunningAppProcessInfo info) {
                    markProcessForeground(info);
                    logFirstHit(label);
                }
                return result;
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    @SuppressWarnings("unchecked")
    private void hookRunningProcessesVirtualization() {
        final String label = "VIRTUAL_FOREGROUND ActivityManager.getRunningAppProcesses";
        try {
            Method method = ActivityManager.class.getDeclaredMethod(
                    "getRunningAppProcesses");
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (!isVirtualForegroundActive() || !(result instanceof List<?> list)) {
                    return result;
                }

                int pid = android.os.Process.myPid();
                for (Object entry : list) {
                    if (entry instanceof ActivityManager.RunningAppProcessInfo info
                            && info.pid == pid) {
                        markProcessForeground(info);
                        logFirstHit(label);
                        break;
                    }
                }
                return result;
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void hookUidImportanceVirtualization() {
        final String label = "VIRTUAL_FOREGROUND ActivityManager.getUidImportance";
        try {
            Method method = ActivityManager.class.getDeclaredMethod(
                    "getUidImportance", int.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                if (isVirtualForegroundActive()
                        && !args.isEmpty()
                        && args.get(0) instanceof Integer uid
                        && uid == android.os.Process.myUid()) {
                    logFirstHit(label);
                    return ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void hookPackageImportanceVirtualization() {
        final String label = "VIRTUAL_FOREGROUND ActivityManager.getPackageImportance";
        try {
            Method method = ActivityManager.class.getDeclaredMethod(
                    "getPackageImportance", String.class);
            method.setAccessible(true);
            hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                if (isVirtualForegroundActive()
                        && !args.isEmpty()
                        && activePackage.equals(args.get(0))) {
                    logFirstHit(label);
                    return ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private static void markProcessForeground(
            ActivityManager.RunningAppProcessInfo info
    ) {
        info.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        info.importanceReasonCode = ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN;
    }

    private void installProcessLifecycleVirtualization(ClassLoader classLoader) {
        for (String className : new String[] {
                "androidx.lifecycle.LifecycleRegistry",
                "android.arch.lifecycle.LifecycleRegistry"
        }) {
            try {
                Class<?> registry = classLoader.loadClass(className);
                Method method = registry.getDeclaredMethod("getCurrentState");
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    if (!isVirtualForegroundActive()
                            || !isProcessLifecycleRegistry(chain.getThisObject())) {
                        return chain.proceed();
                    }

                    Object resumed = findEnumConstant(
                            method.getReturnType(), "RESUMED");
                    if (resumed != null) {
                        logFirstHit("VIRTUAL_FOREGROUND " + className
                                + ".getCurrentState=RESUMED");
                        return resumed;
                    }
                    return chain.proceed();
                });
                logInstalled("VIRTUAL_FOREGROUND " + className + ".getCurrentState");
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                logSkipped("VIRTUAL_FOREGROUND " + className + ".getCurrentState", t);
            }
        }
    }

    private static boolean isProcessLifecycleRegistry(Object registry) {
        if (registry == null) return false;
        Class<?> type = registry.getClass();

        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(registry);
                    if (value instanceof WeakReference<?> ref) {
                        value = ref.get();
                    }
                    if (value != null
                            && value.getClass().getName().contains("ProcessLifecycleOwner")) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static Object findEnumConstant(Class<?> type, String name) {
        if (type == null || !type.isEnum()) return null;
        Object[] constants = type.getEnumConstants();
        if (constants == null) return null;
        for (Object constant : constants) {
            if (constant instanceof Enum<?> e && name.equals(e.name())) {
                return constant;
            }
        }
        return null;
    }

    private void requestRootVirtualForeground(Context context) {
        if (rootVirtualForegroundRequested
                || context == null
                || TargetConfig.getMode() < ModeConfig.MODE_STRONG) {
            return;
        }

        String token = TargetConfig.getRootBridgeToken();
        if (token == null || token.isEmpty()) return;

        try {
            Intent request = new Intent(RootForegroundReceiver.ACTION);
            request.setClassName(
                    MODULE_PACKAGE,
                    "com.jieei.alwaysforeground.RootForegroundReceiver");
            request.putExtra(RootForegroundReceiver.EXTRA_TOKEN, token);
            request.putExtra(RootForegroundReceiver.EXTRA_PACKAGE, activePackage);
            request.putExtra(
                    RootForegroundReceiver.EXTRA_UID,
                    context.getApplicationInfo().uid);
            context.sendBroadcast(request);
            rootVirtualForegroundRequested = true;
            log(Log.INFO, TAG, "VIRTUAL_FOREGROUND root policy requested"
                    + " package=" + activePackage
                    + " uid=" + context.getApplicationInfo().uid);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "VIRTUAL_FOREGROUND root policy request failed"
                    + " package=" + activePackage, t);
        }
    }

    /**
     * Background self-launch virtualization.
     *
     * When an already-backgrounded process starts one of its own Activities, Android would
     * normally move the task to the primary display. In strong mode route that self-launch to a
     * private virtual display instead. The Activity still receives a normal lifecycle and can
     * host the app's own UI/player state, but the user stays on the primary display.
     */
    private void installBackgroundActivityVirtualization() {
        int installed = 0;

        // Standard Activity path.
        for (Method method : Instrumentation.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("execStartActivity")) continue;

            Class<?>[] types = method.getParameterTypes();
            int intentIndex = -1;
            int contextIndex = -1;
            int requestCodeIndex = -1;

            for (int i = 0; i < types.length; i++) {
                if (types[i] == Intent.class && intentIndex < 0) {
                    intentIndex = i;
                    if (i + 1 < types.length && types[i + 1] == int.class) {
                        requestCodeIndex = i + 1;
                    }
                }
                if (Context.class.isAssignableFrom(types[i]) && contextIndex < 0) {
                    contextIndex = i;
                }
            }

            if (intentIndex < 0 || contextIndex < 0) continue;
            if (method.getReturnType().isPrimitive() && method.getReturnType() != void.class) {
                continue;
            }

            final int finalIntentIndex = intentIndex;
            final int finalContextIndex = contextIndex;
            final int finalRequestCodeIndex = requestCodeIndex;
            final String signature = method.toGenericString();

            try {
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    if (Boolean.TRUE.equals(virtualLaunchBypass.get())
                            || TargetConfig.getMode() < ModeConfig.MODE_STRONG
                            || !appInBackground) {
                        return chain.proceed();
                    }

                    List<Object> args = chain.getArgs();
                    if (finalIntentIndex >= args.size()
                            || finalContextIndex >= args.size()) {
                        return chain.proceed();
                    }

                    Object rawIntent = args.get(finalIntentIndex);
                    Object rawContext = args.get(finalContextIndex);
                    if (!(rawIntent instanceof Intent intent)
                            || !(rawContext instanceof Context context)) {
                        return chain.proceed();
                    }

                    if (finalRequestCodeIndex >= 0
                            && finalRequestCodeIndex < args.size()
                            && args.get(finalRequestCodeIndex) instanceof Integer requestCode
                            && requestCode >= 0) {
                        return chain.proceed();
                    }

                    return routeBackgroundSelfLaunch(context, intent, signature)
                            ? null : chain.proceed();
                });
                installed++;
            } catch (Throwable t) {
                logSkipped("background activity virtualization " + signature, t);
            }
        }

        // Some OEM/app stacks bypass Instrumentation and call ContextImpl.startActivity directly.
        try {
            Class<?> contextImpl = Class.forName("android.app.ContextImpl");
            for (Method method : contextImpl.getDeclaredMethods()) {
                if (!"startActivity".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length < 1 || types[0] != Intent.class) continue;
                if (method.getReturnType() != void.class) continue;

                final String signature = method.toGenericString();
                try {
                    method.setAccessible(true);
                    hook(method).intercept(chain -> {
                        if (Boolean.TRUE.equals(virtualLaunchBypass.get())
                                || TargetConfig.getMode() < ModeConfig.MODE_STRONG
                                || !appInBackground) {
                            return chain.proceed();
                        }

                        List<Object> args = chain.getArgs();
                        if (args.isEmpty() || !(args.get(0) instanceof Intent intent)) {
                            return chain.proceed();
                        }

                        Object receiver = chain.getThisObject();
                        if (!(receiver instanceof Context context)) {
                            return chain.proceed();
                        }

                        return routeBackgroundSelfLaunch(context, intent, signature)
                                ? null : chain.proceed();
                    });
                    installed++;
                } catch (Throwable t) {
                    logSkipped("ContextImpl background launch " + signature, t);
                }
            }
        } catch (Throwable t) {
            logSkipped("ContextImpl background launch hooks", t);
        }

        if (installed > 0) {
            logInstalled("background activity virtualization methods=" + installed);
        }
    }

    private boolean routeBackgroundSelfLaunch(
            Context context,
            Intent intent,
            String source
    ) {
        if (Boolean.TRUE.equals(virtualLaunchBypass.get())
                || TargetConfig.getMode() < ModeConfig.MODE_STRONG
                || !appInBackground) {
            return false;
        }

        if (backgroundVirtualDisplay.owns(context)) {
            return false;
        }

        ComponentName target = resolveOwnActivity(context, intent);
        if (target == null || !activePackage.equals(target.getPackageName())) {
            return false;
        }

        virtualLaunchBypass.set(true);
        try {
            SmallWindowController.LaunchResult small =
                    smallWindowController.launch(context, intent);
            if (small.handled) {
                log(Log.INFO, TAG, "GENERIC_SMALL_WINDOW routed"
                        + " target=" + target.flattenToShortString()
                        + " backend=" + small.backend
                        + " detail=" + small.detail
                        + " via=" + source
                        + " package=" + activePackage);
                return true;
            }

            log(Log.INFO, TAG, "GENERIC_SMALL_WINDOW fallback"
                    + " target=" + target.flattenToShortString()
                    + " reason=" + small.detail
                    + " package=" + activePackage);

            android.app.ActivityOptions options =
                    backgroundVirtualDisplay.makeLaunchOptions(context);
            if (options == null) {
                log(Log.WARN, TAG, "GENERIC_VIRTUAL_LAUNCH unavailable"
                        + " target=" + target.flattenToShortString()
                        + " package=" + activePackage);
                return false;
            }

            Intent virtualIntent = BackgroundVirtualDisplay.virtualizeIntent(intent);
            context.startActivity(virtualIntent, options.toBundle());
            log(Log.INFO, TAG, "GENERIC_VIRTUAL_LAUNCH routed"
                    + " target=" + target.flattenToShortString()
                    + " displayId=" + backgroundVirtualDisplay.getDisplayId()
                    + " via=" + source
                    + " package=" + activePackage);
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "GENERIC_SMALL_WINDOW route failed"
                    + " target=" + target.flattenToShortString()
                    + " package=" + activePackage, t);
            return false;
        } finally {
            virtualLaunchBypass.set(false);
        }
    }

    private ComponentName resolveOwnActivity(Context context, Intent intent) {
        ComponentName explicit = intent.getComponent();
        if (explicit != null) return explicit;

        try {
            android.content.pm.ResolveInfo info = context.getPackageManager()
                    .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (info == null || info.activityInfo == null) return null;
            return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
        } catch (Throwable ignored) {
            return null;
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

                boolean virtualActivity = backgroundVirtualDisplay.owns(activity);

                if (event == 1) {
                    if (virtualActivity) {
                        Object result = chain.proceed();
                        log(Log.INFO, TAG, "GENERIC_VIRTUAL_ACTIVITY resumed"
                                + " activity=" + className(activity)
                                + " displayId=" + backgroundVirtualDisplay.getDisplayId()
                                + " package=" + activePackage);
                        return result;
                    }

                    int serial = transitionSerial.incrementAndGet();
                    if (id != 0) resumedActivities.add(id);
                    requestRootVirtualForeground(
                            activity == null ? null : activity.getApplicationContext());
                    appInBackground = false;
                    continuityLeaseUntil.clear();
                    clearPendingResumes("activity-resumed", false);
                    Object result = chain.proceed();

                    // A real primary-display foreground entry wins over any hidden container.
                    backgroundVirtualDisplay.release();

                    logTransitionOnce("GENERIC_FOREGROUND serial=" + serial
                            + " activity=" + className(activity));
                    return result;
                }

                // Hidden-display Activities are deliberately isolated from the primary-display
                // foreground/background state machine.
                if (virtualActivity) {
                    return chain.proceed();
                }

                boolean configChange = activity != null && activity.isChangingConfigurations();
                if (event == 2) {
                    if (id != 0) resumedActivities.remove(id);
                    if (!configChange) {
                        lastLifecyclePauseElapsed = SystemClock.elapsedRealtime();
                    }
                }

                // onPause starts a new foreground->background transition. onStop belongs to
                // the same transition and must not invalidate a resume queued during onPause.
                int serial = event == 2
                        ? transitionSerial.incrementAndGet()
                        : transitionSerial.get();
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
            log(Log.INFO, TAG, "VIRTUAL_FOREGROUND active package=" + activePackage);
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

                if (shouldSuppressContinuityEcho(playerId, sink)) {
                    return null;
                }

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

                // If this is an immediate visibility/state echo caused by our own background
                // resume, keep the player running. Real user/media-session/audio-focus pauses are
                // still allowed by shouldSuppressContinuityEcho().
                if (shouldSuppressContinuityEcho(playerId, sink)) {
                    return null;
                }

                boolean lifecycle = TargetConfig.getMode() >= ModeConfig.MODE_STRONG
                        && isLifecycleTriggeredPause();
                boolean wasPlaying = playerId != 0 && knownPlayingPlayers.contains(playerId);
                if (!wasPlaying) {
                    Boolean queried = queryPlaying(player);
                    wasPlaying = Boolean.TRUE.equals(queried);
                }

                // Player wrappers commonly call an implementation object's pause(). Treat the
                // entire nested cascade as one logical pause and queue only the outermost player.
                int cascadeDepth = pauseCascadeDepth.get();
                pauseCascadeDepth.set(cascadeDepth + 1);
                Object result;
                try {
                    result = chain.proceed();
                } finally {
                    pauseCascadeDepth.set(cascadeDepth);
                }

                if (playerId != 0) knownPlayingPlayers.remove(playerId);
                if (cascadeDepth == 0 && lifecycle && wasPlaying && player != null) {
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

        // A reflective play/start performed by this module must not be misclassified as an
        // app-native handoff and cancel its own pending resume.
        if (Boolean.TRUE.equals(selfContinuityResume.get())) {
            return;
        }

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
        if (current != pending) {
            logContinuityCancel(pending, "pending-replaced-or-cleared");
            return;
        }

        if (pending.transition != transitionSerial.get()) {
            pendingResumes.remove(playerId, pending);
            logContinuityCancel(pending, "transition-changed");
            return;
        }
        if (!appInBackground) {
            pendingResumes.remove(playerId, pending);
            logContinuityCancel(pending, "not-background");
            return;
        }
        if (!resumedActivities.isEmpty()) {
            pendingResumes.remove(playerId, pending);
            logContinuityCancel(pending, "activity-resumed");
            return;
        }

        Object player = pending.player.get();
        if (player == null) {
            pendingResumes.remove(playerId, pending);
            logContinuityCancel(pending, "player-collected");
            return;
        }

        // Do not re-query isPlaying() here. Several engines (including TTVideoEngine) keep a
        // stale logical "playing" state for a short period after lifecycle pause even though the
        // AudioTrack is already paused. We captured wasPlaying before pause, which is the reliable
        // causal signal. An explicit play/start hook will mark knownPlayingPlayers and cancel us.
        if (knownPlayingPlayers.contains(playerId)) {
            pendingResumes.remove(playerId, pending);
            logContinuityCancel(pending, "explicit-play-already-seen");
            return;
        }

        boolean resumed = false;
        // Install the short lease before invoking play/start because some engines dispatch their
        // state callback immediately. If resume fails, the lease is removed below.
        continuityLeaseUntil.put(playerId,
                SystemClock.elapsedRealtime() + CONTINUITY_ECHO_GUARD_MS);
        selfContinuityResume.set(true);
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
            selfContinuityResume.set(false);
            pendingResumes.remove(playerId, pending);
        }

        if (resumed) {
            knownPlayingPlayers.add(playerId);
            log(Log.INFO, TAG, "GENERIC_CONTINUITY resumed"
                    + " sink=" + pending.sink
                    + " delayMs=" + (SystemClock.elapsedRealtime() - pending.queuedAt)
                    + " package=" + activePackage);
        } else {
            continuityLeaseUntil.remove(playerId);
            log(Log.INFO, TAG, "GENERIC_CONTINUITY no compatible resume method"
                    + " sink=" + pending.sink
                    + " player=" + player.getClass().getName());
        }
    }

    private boolean shouldSuppressContinuityEcho(int playerId, String sink) {
        if (playerId == 0
                || TargetConfig.getMode() < ModeConfig.MODE_STRONG
                || !appInBackground) {
            return false;
        }

        Long until = continuityLeaseUntil.get(playerId);
        if (until == null) return false;

        long now = SystemClock.elapsedRealtime();
        if (now > until) {
            continuityLeaseUntil.remove(playerId, until);
            return false;
        }

        if (isExplicitPlaybackInterruption()) {
            continuityLeaseUntil.remove(playerId);
            log(Log.INFO, TAG, "GENERIC_CONTINUITY echo pause allowed"
                    + " sink=" + sink
                    + " reason=explicit-media-or-audio-interruption"
                    + " package=" + activePackage);
            return false;
        }

        log(Log.INFO, TAG, "GENERIC_CONTINUITY echo pause suppressed"
                + " sink=" + sink
                + " remainingMs=" + Math.max(0L, until - now)
                + " package=" + activePackage);
        return true;
    }

    /**
     * Do not fight deliberate user/media-session controls or audio-focus/telephony interruptions.
     * Everything else inside the short post-resume lease is treated as an app visibility/state
     * echo caused by the module's own resume.
     */
    private static boolean isExplicitPlaybackInterruption() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String value = frame.getClassName() + "." + frame.getMethodName();
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("audiofocus")
                    || lower.contains("onaudiofocuschange")
                    || lower.contains("mediasession")
                    || lower.contains("mediabutton")
                    || lower.contains("transportcontrol")
                    || lower.contains("keyevent")
                    || lower.contains("oncallstate")
                    || lower.contains("telephony")) {
                return true;
            }
        }
        return false;
    }

    private void logContinuityCancel(PendingResume pending, String reason) {
        log(Log.INFO, TAG, "GENERIC_CONTINUITY cancelled"
                + " sink=" + pending.sink
                + " reason=" + reason
                + " queuedTransition=" + pending.transition
                + " currentTransition=" + transitionSerial.get()
                + " background=" + appInBackground
                + " resumedActivities=" + resumedActivities.size()
                + " package=" + activePackage);
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
                    continuityLeaseUntil.remove(id);
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
