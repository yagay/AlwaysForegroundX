package com.yagay.alwaysforeground;

import android.app.Activity;
import android.app.ActivityManager;
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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class GuardModule extends XposedModule {
    private static final String TAG = "MiniWindowGuard";
    private static final String COMPANION_PACKAGE = "com.yagay.alwaysforeground";

    private static final String[] PLAYER_CLASSES = {
            "com.google.android.exoplayer2.ExoPlayerImpl",
            "com.google.android.exoplayer2.SimpleExoPlayer",
            "androidx.media3.exoplayer.ExoPlayerImpl",
            "androidx.media3.exoplayer.SimpleExoPlayer",
            "com.ss.ttvideoengine.TTVideoEngine",
            "com.ss.ttvideoengine.TTVideoEngineImpl",
            "tv.danmaku.ijk.media.player.IjkMediaPlayer"
    };

    private final Set<String> installedHooks = ConcurrentHashMap.newKeySet();
    private final Set<String> firstHits = ConcurrentHashMap.newKeySet();
    private final Set<Integer> resumedActivities = ConcurrentHashMap.newKeySet();
    private final Set<Integer> knownPlayingPlayers = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<Integer, PendingResume> pendingResumes =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> continuityLeaseUntil =
            new ConcurrentHashMap<>();

    private final AtomicInteger transitionSerial = new AtomicInteger();

    private final ThreadLocal<Integer> lifecycleDepth =
            ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Integer> pauseCascadeDepth =
            ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Boolean> selfResume =
            ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> launchBypass =
            ThreadLocal.withInitial(() -> false);

    private volatile String activePackage;
    private volatile Handler mainHandler;
    private volatile boolean appInBackground;
    private volatile long lastLifecyclePauseElapsed;
    private volatile long lastRootPolicyRequestElapsed;

    private static final class PendingResume {
        final WeakReference<Object> player;
        final String sink;
        final boolean setPlayWhenReady;
        final int transition;
        final long queuedAt;

        PendingResume(
                Object player,
                String sink,
                boolean setPlayWhenReady,
                int transition,
                long queuedAt
        ) {
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
            SharedPreferences prefs = getRemotePreferences(ConfigKeys.REMOTE_GROUP);
            GuardConfig.initialize(prefs);
            log(Log.INFO, TAG, "Remote settings ready");
        } catch (Throwable t) {
            GuardConfig.initialize(null);
            log(Log.WARN, TAG, "Remote settings unavailable; defaults active", t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;

        String packageName = param.getPackageName();
        if (packageName == null
                || COMPANION_PACKAGE.equals(packageName)
                || "android".equals(packageName)) {
            return;
        }

        activePackage = packageName;
        mainHandler = new Handler(Looper.getMainLooper());

        installRestrictionHooks();
        installVirtualForegroundHooks();
        installLifecycleTracker();
        installBackgroundActivityRouter();

        log(Log.INFO, TAG, "GENERIC_ENGINE ready package=" + activePackage);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        if (!param.getPackageName().equals(activePackage)) return;

        ClassLoader classLoader = param.getClassLoader();
        installProcessLifecycleHooks(classLoader);
        installMediaContinuity(classLoader);
        installDestructiveEndpointHooks(classLoader);

        log(Log.INFO, TAG, "GENERIC_ENGINE package-ready package=" + activePackage);
    }

    private boolean masterEnabled() {
        return GuardConfig.enabled();
    }

    private boolean virtualForegroundActive() {
        return masterEnabled() && appInBackground;
    }

    private void installRestrictionHooks() {
        hookBooleanSetting(
                ActivityManager.class,
                "isBackgroundRestricted",
                false,
                ConfigKeys.SPOOF_BACKGROUND_RESTRICTION,
                false);
        hookBooleanSetting(
                PowerManager.class,
                "isDeviceIdleMode",
                false,
                ConfigKeys.SPOOF_POWER_STATE,
                false);
        hookBooleanSetting(
                PowerManager.class,
                "isPowerSaveMode",
                false,
                ConfigKeys.SPOOF_POWER_STATE,
                false);

        try {
            Method method = PowerManager.class.getDeclaredMethod(
                    "isIgnoringBatteryOptimizations", String.class);
            method.setAccessible(true);
            String signature = method.toGenericString();
            if (!installedHooks.add(signature)) return;

            hook(method).intercept(chain -> {
                if (!masterEnabled()
                        || !GuardConfig.bool(ConfigKeys.SPOOF_POWER_STATE)) {
                    return chain.proceed();
                }

                List<Object> args = chain.getArgs();
                if (!args.isEmpty() && activePackage.equals(args.get(0))) {
                    logFirstHit("PowerManager.isIgnoringBatteryOptimizations");
                    return true;
                }
                return chain.proceed();
            });
            logInstalled("PowerManager.isIgnoringBatteryOptimizations");
        } catch (Throwable t) {
            logSkipped("PowerManager.isIgnoringBatteryOptimizations", t);
        }
    }

    private void installVirtualForegroundHooks() {
        hookBooleanSetting(
                Activity.class,
                "hasWindowFocus",
                true,
                ConfigKeys.SPOOF_WINDOW_FOCUS,
                true);
        hookBooleanSetting(
                PowerManager.class,
                "isInteractive",
                true,
                ConfigKeys.SPOOF_SCREEN_INTERACTIVE,
                true);
        hookBooleanSetting(
                PowerManager.class,
                "isScreenOn",
                true,
                ConfigKeys.SPOOF_SCREEN_INTERACTIVE,
                true);
        hookBooleanSetting(
                KeyguardManager.class,
                "isKeyguardLocked",
                false,
                ConfigKeys.SPOOF_KEYGUARD,
                true);
        hookBooleanSetting(
                KeyguardManager.class,
                "isDeviceLocked",
                false,
                ConfigKeys.SPOOF_KEYGUARD,
                true);

        hookUidImportance();
        hookPackageImportance();
        hookMyMemoryState();
        hookRunningProcesses();
    }

    private void hookUidImportance() {
        final String label = "ActivityManager.getUidImportance";
        try {
            Method method = ActivityManager.class.getDeclaredMethod(
                    "getUidImportance", int.class);
            method.setAccessible(true);
            if (!installedHooks.add(method.toGenericString())) return;

            hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                if (virtualForegroundActive()
                        && GuardConfig.bool(ConfigKeys.SPOOF_PROCESS_IMPORTANCE)
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

    private void hookPackageImportance() {
        final String label = "ActivityManager.getPackageImportance";
        try {
            Method method = ActivityManager.class.getDeclaredMethod(
                    "getPackageImportance", String.class);
            method.setAccessible(true);
            if (!installedHooks.add(method.toGenericString())) return;

            hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                if (virtualForegroundActive()
                        && GuardConfig.bool(ConfigKeys.SPOOF_PROCESS_IMPORTANCE)
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

    private void hookMyMemoryState() {
        final String label = "ActivityManager.getMyMemoryState";
        try {
            Method method = ActivityManager.class.getDeclaredMethod(
                    "getMyMemoryState",
                    ActivityManager.RunningAppProcessInfo.class);
            method.setAccessible(true);
            if (!installedHooks.add(method.toGenericString())) return;

            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (!virtualForegroundActive()
                        || !GuardConfig.bool(ConfigKeys.SPOOF_PROCESS_IMPORTANCE)) {
                    return result;
                }

                List<Object> args = chain.getArgs();
                if (!args.isEmpty()
                        && args.get(0) instanceof ActivityManager.RunningAppProcessInfo info) {
                    markForeground(info);
                    logFirstHit(label);
                }
                return result;
            });
            logInstalled(label);
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void hookRunningProcesses() {
        final String label = "ActivityManager.getRunningAppProcesses";
        try {
            Method method = ActivityManager.class.getDeclaredMethod(
                    "getRunningAppProcesses");
            method.setAccessible(true);
            if (!installedHooks.add(method.toGenericString())) return;

            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (!virtualForegroundActive()
                        || !GuardConfig.bool(ConfigKeys.SPOOF_PROCESS_IMPORTANCE)
                        || !(result instanceof List<?> list)) {
                    return result;
                }

                int pid = android.os.Process.myPid();
                for (Object entry : list) {
                    if (entry instanceof ActivityManager.RunningAppProcessInfo info
                            && info.pid == pid) {
                        markForeground(info);
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

    private static void markForeground(ActivityManager.RunningAppProcessInfo info) {
        info.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        info.importanceReasonCode = ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN;
    }

    private void installLifecycleTracker() {
        hookLifecycle("callActivityOnResume", 1);
        hookLifecycle("callActivityOnPause", 2);
        hookLifecycle("callActivityOnStop", 3);
    }

    private void hookLifecycle(String methodName, int event) {
        try {
            Method method = Instrumentation.class.getDeclaredMethod(
                    methodName, Activity.class);
            method.setAccessible(true);
            if (!installedHooks.add(method.toGenericString())) return;

            hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                Activity activity = !args.isEmpty() && args.get(0) instanceof Activity
                        ? (Activity) args.get(0) : null;
                int id = activity == null ? 0 : System.identityHashCode(activity);

                if (event == 1) {
                    int serial = transitionSerial.incrementAndGet();
                    if (id != 0) resumedActivities.add(id);
                    appInBackground = false;
                    continuityLeaseUntil.clear();
                    pendingResumes.clear();

                    if (activity != null) {
                        requestRootPolicy(activity.getApplicationContext());
                    }

                    Object result = chain.proceed();
                    log(Log.INFO, TAG, "FOREGROUND serial=" + serial
                            + " activity=" + className(activity)
                            + " package=" + activePackage);
                    return result;
                }

                boolean configChange =
                        activity != null && activity.isChangingConfigurations();

                if (event == 2) {
                    if (id != 0) resumedActivities.remove(id);
                    if (!configChange) {
                        lastLifecyclePauseElapsed = SystemClock.elapsedRealtime();
                    }
                }

                int serial = event == 2
                        ? transitionSerial.incrementAndGet()
                        : transitionSerial.get();

                int depth = lifecycleDepth.get();
                lifecycleDepth.set(depth + 1);
                try {
                    return chain.proceed();
                } finally {
                    lifecycleDepth.set(depth);
                    if (!configChange) scheduleBackgroundConfirmation(serial);
                }
            });

            logInstalled("Instrumentation." + methodName);
        } catch (Throwable t) {
            logSkipped("Instrumentation." + methodName, t);
        }
    }

    private void scheduleBackgroundConfirmation(int serial) {
        Handler handler = mainHandler;
        if (handler == null) return;

        handler.postDelayed(() -> {
            if (serial != transitionSerial.get()) return;
            if (!resumedActivities.isEmpty()) return;

            appInBackground = true;
            log(Log.INFO, TAG, "VIRTUAL_FOREGROUND active serial=" + serial
                    + " package=" + activePackage);
        }, GuardConfig.backgroundConfirmMs());
    }

    private boolean lifecycleTriggeredPause() {
        if (lifecycleDepth.get() > 0) return true;
        long last = lastLifecyclePauseElapsed;
        if (last <= 0L || !resumedActivities.isEmpty()) return false;
        long elapsed = SystemClock.elapsedRealtime() - last;
        return elapsed >= 0 && elapsed <= 500L;
    }

    private void requestRootPolicy(Context context) {
        if (context == null) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastRootPolicyRequestElapsed < 20_000L) return;
        lastRootPolicyRequestElapsed = now;

        String token = GuardConfig.string(ConfigKeys.ROOT_BRIDGE_TOKEN);
        if (token.isEmpty()) return;

        boolean enabled = masterEnabled()
                && GuardConfig.bool(ConfigKeys.ROOT_KEEP_ALIVE);

        try {
            Intent request = new Intent(RootBridgeReceiver.ACTION);
            request.setClassName(
                    COMPANION_PACKAGE,
                    "com.yagay.alwaysforeground.RootBridgeReceiver");
            request.putExtra(RootBridgeReceiver.EXTRA_TOKEN, token);
            request.putExtra(RootBridgeReceiver.EXTRA_OP, RootBridgeReceiver.OP_POLICY);
            request.putExtra(RootBridgeReceiver.EXTRA_PACKAGE, activePackage);
            request.putExtra(RootBridgeReceiver.EXTRA_UID, context.getApplicationInfo().uid);
            request.putExtra(RootBridgeReceiver.EXTRA_ENABLED, enabled);
            request.putExtra(
                    RootBridgeReceiver.EXTRA_DOZE,
                    GuardConfig.bool(ConfigKeys.ROOT_DOZE_WHITELIST));
            request.putExtra(
                    RootBridgeReceiver.EXTRA_STANDBY,
                    GuardConfig.bool(ConfigKeys.ROOT_STANDBY_ACTIVE));
            request.putExtra(
                    RootBridgeReceiver.EXTRA_APPOPS,
                    GuardConfig.bool(ConfigKeys.ROOT_BACKGROUND_APPOPS));
            request.putExtra(
                    RootBridgeReceiver.EXTRA_NETWORK,
                    GuardConfig.bool(ConfigKeys.ROOT_NETWORK_WHITELIST));
            request.putExtra(
                    RootBridgeReceiver.EXTRA_WAKELOCK,
                    GuardConfig.bool(ConfigKeys.ROOT_WAKELOCK));

            context.sendBroadcast(request);
            log(Log.INFO, TAG, "ROOT_POLICY requested package=" + activePackage
                    + " enabled=" + enabled);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "ROOT_POLICY request failed package=" + activePackage, t);
        }
    }

    private void installProcessLifecycleHooks(ClassLoader classLoader) {
        for (String className : new String[]{
                "androidx.lifecycle.LifecycleRegistry",
                "android.arch.lifecycle.LifecycleRegistry"
        }) {
            try {
                Class<?> registry = classLoader.loadClass(className);
                Method method = registry.getDeclaredMethod("getCurrentState");
                method.setAccessible(true);
                if (!installedHooks.add(method.toGenericString())) continue;

                hook(method).intercept(chain -> {
                    if (!virtualForegroundActive()
                            || !GuardConfig.bool(ConfigKeys.SPOOF_PROCESS_LIFECYCLE)
                            || !isProcessLifecycleRegistry(chain.getThisObject())) {
                        return chain.proceed();
                    }

                    Object resumed = enumConstant(
                            method.getReturnType(), "RESUMED");
                    if (resumed != null) {
                        logFirstHit(className + ".getCurrentState=RESUMED");
                        return resumed;
                    }
                    return chain.proceed();
                });

                logInstalled(className + ".getCurrentState");
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                logSkipped(className + ".getCurrentState", t);
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

    private static Object enumConstant(Class<?> type, String name) {
        if (type == null || !type.isEnum()) return null;
        Object[] values = type.getEnumConstants();
        if (values == null) return null;
        for (Object value : values) {
            if (value instanceof Enum<?> e && name.equals(e.name())) {
                return value;
            }
        }
        return null;
    }

    private void installBackgroundActivityRouter() {
        installInstrumentationStartHooks();
        installContextImplStartHooks();
    }

    private void installInstrumentationStartHooks() {
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
            if (method.getReturnType().isPrimitive()
                    && method.getReturnType() != void.class) {
                continue;
            }

            String signature = method.toGenericString();
            if (!installedHooks.add(signature)) continue;

            final int fIntent = intentIndex;
            final int fContext = contextIndex;
            final int fRequest = requestCodeIndex;

            try {
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    if (!shouldRouteBackgroundLaunch()) return chain.proceed();

                    List<Object> args = chain.getArgs();
                    if (fIntent >= args.size() || fContext >= args.size()) {
                        return chain.proceed();
                    }
                    if (!(args.get(fIntent) instanceof Intent intent)
                            || !(args.get(fContext) instanceof Context context)) {
                        return chain.proceed();
                    }

                    if (fRequest >= 0
                            && fRequest < args.size()
                            && args.get(fRequest) instanceof Integer requestCode
                            && requestCode >= 0) {
                        return chain.proceed();
                    }

                    if (routeOwnActivity(context, intent)) return null;
                    return chain.proceed();
                });
                logInstalled("Activity launch router " + signature);
            } catch (Throwable t) {
                installedHooks.remove(signature);
                logSkipped("Activity launch router " + signature, t);
            }
        }
    }

    private void installContextImplStartHooks() {
        try {
            Class<?> contextImpl = Class.forName("android.app.ContextImpl");
            for (Method method : contextImpl.getDeclaredMethods()) {
                if (!"startActivity".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length < 1 || types[0] != Intent.class) continue;
                if (method.getReturnType() != void.class) continue;

                String signature = method.toGenericString();
                if (!installedHooks.add(signature)) continue;

                try {
                    method.setAccessible(true);
                    hook(method).intercept(chain -> {
                        if (!shouldRouteBackgroundLaunch()) return chain.proceed();

                        List<Object> args = chain.getArgs();
                        Object receiver = chain.getThisObject();
                        if (args.isEmpty()
                                || !(args.get(0) instanceof Intent intent)
                                || !(receiver instanceof Context context)) {
                            return chain.proceed();
                        }

                        if (routeOwnActivity(context, intent)) return null;
                        return chain.proceed();
                    });
                    logInstalled("ContextImpl launch router " + signature);
                } catch (Throwable t) {
                    installedHooks.remove(signature);
                    logSkipped("ContextImpl launch router " + signature, t);
                }
            }
        } catch (Throwable t) {
            logSkipped("ContextImpl launch router", t);
        }
    }

    private boolean shouldRouteBackgroundLaunch() {
        return masterEnabled()
                && appInBackground
                && GuardConfig.bool(ConfigKeys.AUTO_SMALL_WINDOW)
                && !Boolean.TRUE.equals(launchBypass.get());
    }

    private boolean routeOwnActivity(Context context, Intent intent) {
        ComponentName target = resolveActivity(context, intent);
        if (target == null || !activePackage.equals(target.getPackageName())) {
            return false;
        }

        launchBypass.set(true);
        try {
            boolean routed = SmallWindowClient.route(context, intent);
            if (routed) {
                log(Log.INFO, TAG, "SMALL_WINDOW intercepted target="
                        + target.flattenToShortString()
                        + " package=" + activePackage);
            }
            return routed;
        } finally {
            launchBypass.set(false);
        }
    }

    private static ComponentName resolveActivity(Context context, Intent intent) {
        ComponentName explicit = intent.getComponent();
        if (explicit != null) return explicit;

        try {
            android.content.pm.ResolveInfo info = context.getPackageManager()
                    .resolveActivity(
                            intent,
                            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (info == null || info.activityInfo == null) return null;
            return new ComponentName(
                    info.activityInfo.packageName,
                    info.activityInfo.name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void installMediaContinuity(ClassLoader classLoader) {
        installMediaPlayerEndpoints();

        for (String className : PLAYER_CLASSES) {
            try {
                Class<?> clazz = classLoader.loadClass(className);
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
                        hookPauseEndpoint(method, className + ".pause", false);
                    } else if (start) {
                        hookStartEndpoint(method, className + "." + name);
                    } else {
                        hookBooleanPlayEndpoint(
                                method,
                                className + ".setPlayWhenReady");
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                logSkipped("Media class " + className, t);
            }
        }
    }

    private void installMediaPlayerEndpoints() {
        try {
            Method pause = MediaPlayer.class.getDeclaredMethod("pause");
            pause.setAccessible(true);
            hookPauseEndpoint(pause, "android.media.MediaPlayer.pause", false);
        } catch (Throwable t) {
            logSkipped("MediaPlayer.pause", t);
        }

        try {
            Method start = MediaPlayer.class.getDeclaredMethod("start");
            start.setAccessible(true);
            hookStartEndpoint(start, "android.media.MediaPlayer.start");
        } catch (Throwable t) {
            logSkipped("MediaPlayer.start", t);
        }
    }

    private void hookBooleanPlayEndpoint(Method method, String sink) {
        String signature = method.toGenericString();
        if (!installedHooks.add(signature)) return;

        try {
            hook(method).intercept(chain -> {
                List<Object> args = chain.getArgs();
                boolean requestedPlay =
                        !args.isEmpty() && Boolean.TRUE.equals(args.get(0));

                if (requestedPlay) {
                    Object result = chain.proceed();
                    notePlaybackStarted(chain.getThisObject(), sink);
                    return result;
                }

                Object player = chain.getThisObject();
                int id = player == null ? 0 : System.identityHashCode(player);

                if (shouldSuppressEcho(id, sink)) return null;

                boolean lifecycle = mediaProtectionActive()
                        && lifecycleTriggeredPause();
                boolean wasPlaying = wasPlaying(player, id);

                Object result = chain.proceed();
                if (id != 0) knownPlayingPlayers.remove(id);

                if (lifecycle && wasPlaying && player != null) {
                    queueResume(player, sink, true);
                }
                return result;
            });
            logInstalled("Media endpoint " + sink);
        } catch (Throwable t) {
            installedHooks.remove(signature);
            logSkipped("Media endpoint " + sink, t);
        }
    }

    private void hookPauseEndpoint(Method method, String sink, boolean unused) {
        String signature = method.toGenericString();
        if (!installedHooks.add(signature)) return;

        try {
            hook(method).intercept(chain -> {
                Object player = chain.getThisObject();
                int id = player == null ? 0 : System.identityHashCode(player);

                if (shouldSuppressEcho(id, sink)) return null;

                boolean lifecycle = mediaProtectionActive()
                        && lifecycleTriggeredPause();
                boolean wasPlaying = wasPlaying(player, id);

                int cascade = pauseCascadeDepth.get();
                pauseCascadeDepth.set(cascade + 1);
                Object result;
                try {
                    result = chain.proceed();
                } finally {
                    pauseCascadeDepth.set(cascade);
                }

                if (id != 0) knownPlayingPlayers.remove(id);

                if (cascade == 0 && lifecycle && wasPlaying && player != null) {
                    queueResume(player, sink, false);
                }
                return result;
            });
            logInstalled("Media endpoint " + sink);
        } catch (Throwable t) {
            installedHooks.remove(signature);
            logSkipped("Media endpoint " + sink, t);
        }
    }

    private void hookStartEndpoint(Method method, String sink) {
        String signature = method.toGenericString();
        if (!installedHooks.add(signature)) return;

        try {
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                notePlaybackStarted(chain.getThisObject(), sink);
                return result;
            });
            logInstalled("Media start " + sink);
        } catch (Throwable t) {
            installedHooks.remove(signature);
            logSkipped("Media start " + sink, t);
        }
    }

    private boolean mediaProtectionActive() {
        return masterEnabled()
                && GuardConfig.bool(ConfigKeys.MEDIA_CONTINUITY);
    }

    private boolean wasPlaying(Object player, int id) {
        if (id != 0 && knownPlayingPlayers.contains(id)) return true;
        return Boolean.TRUE.equals(queryPlaying(player));
    }

    private void notePlaybackStarted(Object player, String sink) {
        if (player == null) return;

        int id = System.identityHashCode(player);
        knownPlayingPlayers.add(id);

        if (Boolean.TRUE.equals(selfResume.get())) return;

        PendingResume pending = pendingResumes.remove(id);
        if (pending != null) {
            log(Log.INFO, TAG, "MEDIA_CONTINUITY native-start-cancel"
                    + " sink=" + sink + " package=" + activePackage);
        }
    }

    private void queueResume(Object player, String sink, boolean setPlayWhenReady) {
        int id = System.identityHashCode(player);
        PendingResume pending = new PendingResume(
                player,
                sink,
                setPlayWhenReady,
                transitionSerial.get(),
                SystemClock.elapsedRealtime());
        pendingResumes.put(id, pending);

        log(Log.INFO, TAG, "MEDIA_CONTINUITY queued sink=" + sink
                + " package=" + activePackage);

        Handler handler = mainHandler;
        if (handler == null) return;
        handler.postDelayed(
                () -> tryResume(id, pending),
                GuardConfig.mediaResumeDelayMs());
    }

    private void tryResume(int id, PendingResume pending) {
        if (pendingResumes.get(id) != pending) return;

        if (!appInBackground
                || !resumedActivities.isEmpty()
                || pending.transition != transitionSerial.get()) {
            pendingResumes.remove(id, pending);
            log(Log.INFO, TAG, "MEDIA_CONTINUITY cancelled sink=" + pending.sink
                    + " reason=foreground-or-transition package=" + activePackage);
            return;
        }

        Object player = pending.player.get();
        if (player == null) {
            pendingResumes.remove(id, pending);
            return;
        }

        long leaseUntil = SystemClock.elapsedRealtime() + GuardConfig.echoGuardMs();
        continuityLeaseUntil.put(id, leaseUntil);

        boolean resumed = false;
        selfResume.set(true);
        try {
            if (pending.setPlayWhenReady) {
                resumed = invokeBooleanPlay(player, true);
            } else {
                resumed = invokeNoArg(player, "play")
                        || invokeNoArg(player, "start")
                        || invokeNoArg(player, "resume");
            }
        } finally {
            selfResume.set(false);
            pendingResumes.remove(id, pending);
        }

        if (resumed) {
            knownPlayingPlayers.add(id);
            log(Log.INFO, TAG, "MEDIA_CONTINUITY resumed sink=" + pending.sink
                    + " delayMs="
                    + (SystemClock.elapsedRealtime() - pending.queuedAt)
                    + " package=" + activePackage);
        } else {
            continuityLeaseUntil.remove(id);
            log(Log.INFO, TAG, "MEDIA_CONTINUITY resume-unavailable sink="
                    + pending.sink + " player=" + player.getClass().getName());
        }
    }

    private boolean shouldSuppressEcho(int playerId, String sink) {
        if (playerId == 0
                || !appInBackground
                || !mediaProtectionActive()
                || !GuardConfig.bool(ConfigKeys.MEDIA_ECHO_GUARD)) {
            return false;
        }

        Long until = continuityLeaseUntil.get(playerId);
        if (until == null) return false;

        long now = SystemClock.elapsedRealtime();
        if (now > until) {
            continuityLeaseUntil.remove(playerId, until);
            return false;
        }

        if (explicitPlaybackInterruption()) {
            continuityLeaseUntil.remove(playerId);
            return false;
        }

        log(Log.INFO, TAG, "MEDIA_CONTINUITY echo-suppressed sink=" + sink
                + " remainingMs=" + Math.max(0L, until - now)
                + " package=" + activePackage);
        return true;
    }

    private static boolean explicitPlaybackInterruption() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String value = (frame.getClassName() + "." + frame.getMethodName())
                    .toLowerCase(Locale.ROOT);
            if (value.contains("audiofocus")
                    || value.contains("onaudiofocuschange")
                    || value.contains("mediasession")
                    || value.contains("mediabutton")
                    || value.contains("transportcontrol")
                    || value.contains("keyevent")
                    || value.contains("telephony")
                    || value.contains("oncallstate")) {
                return true;
            }
        }
        return false;
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

        for (String name : new String[]{
                "isPlaying", "getPlayWhenReady", "isPlayWhenReady", "isStarted"
        }) {
            try {
                Method method = findMethod(player.getClass(), name);
                if (method == null || method.getParameterCount() != 0) continue;
                method.setAccessible(true);
                Object value = method.invoke(player);
                if (value instanceof Boolean b) return b;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean invokeNoArg(Object receiver, String name) {
        try {
            Method method = findMethod(receiver.getClass(), name);
            if (method == null || method.getParameterCount() != 0) return false;
            method.setAccessible(true);
            method.invoke(receiver);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean invokeBooleanPlay(Object receiver, boolean value) {
        Class<?> type = receiver.getClass();

        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (!"setPlayWhenReady".equals(method.getName())) continue;
                if (method.getParameterCount() < 1) continue;
                if (method.getParameterTypes()[0] != boolean.class) continue;

                try {
                    Object[] args = new Object[method.getParameterCount()];
                    args[0] = value;
                    for (int i = 1; i < args.length; i++) {
                        Class<?> p = method.getParameterTypes()[i];
                        if (p == boolean.class) args[i] = false;
                        else if (p == int.class) args[i] = 0;
                        else if (p == long.class) args[i] = 0L;
                        else if (p == float.class) args[i] = 0f;
                        else if (p == double.class) args[i] = 0d;
                        else args[i] = null;
                    }
                    method.setAccessible(true);
                    method.invoke(receiver, args);
                    return true;
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static Method findMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private void installDestructiveEndpointHooks(ClassLoader classLoader) {
        installDestructive(MediaPlayer.class, "stop");
        installDestructive(MediaPlayer.class, "reset");
        installDestructive(MediaPlayer.class, "release");
        installDestructive(AudioTrack.class, "stop");
        installDestructive(AudioTrack.class, "release");

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
                    hookDestructiveMethod(
                            method,
                            className + "." + name);
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                logSkipped("Destructive endpoints " + className, t);
            }
        }
    }

    private void installDestructive(Class<?> clazz, String methodName) {
        try {
            Method method = clazz.getDeclaredMethod(methodName);
            method.setAccessible(true);
            hookDestructiveMethod(
                    method,
                    clazz.getName() + "." + methodName);
        } catch (Throwable t) {
            logSkipped(clazz.getSimpleName() + "." + methodName, t);
        }
    }

    private void hookDestructiveMethod(Method method, String sink) {
        String signature = method.toGenericString();
        if (!installedHooks.add(signature)) return;

        try {
            hook(method).intercept(chain -> {
                Object receiver = chain.getThisObject();
                Object result = chain.proceed();
                if (receiver != null) {
                    int id = System.identityHashCode(receiver);
                    knownPlayingPlayers.remove(id);
                    pendingResumes.remove(id);
                    continuityLeaseUntil.remove(id);
                }
                return result;
            });
            logInstalled("Destructive endpoint " + sink);
        } catch (Throwable t) {
            installedHooks.remove(signature);
            logSkipped("Destructive endpoint " + sink, t);
        }
    }

    private void hookBooleanSetting(
            Class<?> clazz,
            String methodName,
            boolean value,
            String settingKey,
            boolean backgroundOnly
    ) {
        String label = clazz.getSimpleName() + "." + methodName;
        try {
            Method method = clazz.getDeclaredMethod(methodName);
            method.setAccessible(true);
            if (!installedHooks.add(method.toGenericString())) return;

            hook(method).intercept(chain -> {
                boolean stateOk = backgroundOnly
                        ? virtualForegroundActive()
                        : masterEnabled();

                if (stateOk && GuardConfig.bool(settingKey)) {
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

    private void logInstalled(String name) {
        log(Log.INFO, TAG, "INSTALLED " + name + " package=" + activePackage);
    }

    private void logSkipped(String name, Throwable t) {
        log(Log.WARN, TAG, "SKIPPED " + name
                + " package=" + activePackage + " error=" + t);
    }

    private void logFirstHit(String name) {
        String key = activePackage + "|" + name;
        if (firstHits.add(key)) {
            log(Log.INFO, TAG, "HIT " + name + " package=" + activePackage);
        }
    }

    private static String className(Activity activity) {
        return activity == null ? "null" : activity.getClass().getName();
    }
}
