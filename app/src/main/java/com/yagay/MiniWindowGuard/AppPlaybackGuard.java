package com.yagay.MiniWindowGuard;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic app-process playback compatibility layer.
 *
 * system_server is allowed to complete Activity lifecycle transitions normally.
 * Automatic mode follows the proven 5.4.5 policy: suppress a player
 * pause/stop only when it is synchronously caused by Activity onPause/onStop.
 * There is no player-state machine and no automatic play()/resume recovery.
 * Native-only mode passes lifecycle pauses through. Manual pause remains
 * untouched. Player tracking exists only for explicit notification controls.
 */
final class AppPlaybackGuard {
    private static final String TAG =
            "MiniWindowGuard";

    private static final Set<String> PROCESS_INSTALLS =
            ConcurrentHashMap.newKeySet();

    private final GuardModule module;
    private final String packageName;
    private final ClassLoader classLoader;

    private final Set<String> installedHooks =
            ConcurrentHashMap.newKeySet();

    private final ThreadLocal<Integer> lifecycleDepth =
            ThreadLocal.withInitial(
                    () -> 0);

    private static final long BACKGROUND_CONFIRM_DELAY_MS = 220L;
    private static final String OPTION_AVOID_MOVE_TO_FRONT =
            "android.activity.avoidMoveToFront";

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private volatile boolean controlReceiverRegistered;
    private volatile boolean appForeground = true;
    private volatile long foregroundStateToken;
    private volatile WeakReference<Object> currentPlayer =
            new WeakReference<>(null);

    private final BroadcastReceiver controlReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(
                        Context context,
                        Intent intent
                ) {
                    if (intent == null) {
                        return;
                    }

                    String target =
                            intent.getStringExtra(
                                    PlaybackControlContract
                                            .EXTRA_PACKAGE_NAME);

                    if (!packageName.equals(
                            target)
                            || !selected()) {
                        return;
                    }

                    String action =
                            intent.getAction();

                    if (PlaybackControlContract
                            .ACTION_PAUSE
                            .equals(action)) {
                        controlFromNotification(
                                false);
                        return;
                    }

                    if (PlaybackControlContract
                            .ACTION_PLAY
                            .equals(action)) {
                        controlFromNotification(
                                true);
                        return;
                    }

                    if (PlaybackControlContract
                            .ACTION_PREVIOUS
                            .equals(action)) {
                        skipFromNotification(
                                false);
                        return;
                    }

                    if (PlaybackControlContract
                            .ACTION_NEXT
                            .equals(action)) {
                        skipFromNotification(
                                true);
                    }
                }
            };

    private AppPlaybackGuard(
            GuardModule module,
            String packageName,
            ClassLoader classLoader
    ) {
        this.module = module;
        this.packageName = packageName;
        this.classLoader = classLoader;
    }

    static void install(
            GuardModule module,
            String packageName,
            ClassLoader classLoader
    ) {
        if (module == null
                || packageName == null
                || packageName.isBlank()
                || classLoader == null
                || !PROCESS_INSTALLS.add(
                        packageName)) {
            return;
        }

        AppPlaybackGuard guard =
                new AppPlaybackGuard(
                        module,
                        packageName,
                        classLoader);

        guard.ensureControlReceiver(null);
        guard.installLifecycleHooks();
        guard.installBackgroundActivityLaunchGuard();
        guard.installPlayerHooks();

        guard.log(
                Log.INFO,
                "APP_PLAYBACK_GUARD_READY",
                "pkg=" + packageName
                        + " hooks="
                        + guard.installedHooks.size());
    }

    private void installLifecycleHooks() {
        Class<?> instrumentation =
                load("android.app.Instrumentation");

        if (instrumentation == null) {
            return;
        }

        for (Method method :
                instrumentation
                        .getDeclaredMethods()) {
            String name =
                    method.getName();

            boolean resume =
                    "callActivityOnResume"
                            .equals(name);

            boolean background =
                    "callActivityOnPause"
                            .equals(name)
                            || "callActivityOnStop"
                            .equals(name);

            if (!resume && !background) {
                continue;
            }

            try {
                method.setAccessible(true);

                String key =
                        "lifecycle:"
                                + method.toGenericString();

                if (!installedHooks.add(key)) {
                    continue;
                }

                module.hook(method)
                        .intercept(chain -> {
                            if (!selected()) {
                                return chain.proceed();
                            }

                            ensureControlReceiver(
                                    chain.getArgs());

                            if (resume) {
                                appForeground = true;
                                foregroundStateToken++;

                                return chain.proceed();
                            }

                            long token =
                                    ++foregroundStateToken;

                            enterLifecycle();

                            try {
                                return chain.proceed();
                            } finally {
                                exitLifecycle();

                                mainHandler.postDelayed(
                                        () -> {
                                            if (token
                                                    != foregroundStateToken) {
                                                return;
                                            }

                                            appForeground = false;

                                            log(
                                                    Log.INFO,
                                                    "APP_BACKGROUND_CONFIRMED",
                                                    "pkg="
                                                            + packageName);
                                        },
                                        BACKGROUND_CONFIRM_DELAY_MS);
                            }
                        });
            } catch (Throwable t) {
                log(
                        Log.WARN,
                        "APP_LIFECYCLE_HOOK_FAILED",
                        "method=" + method
                                + " error="
                                + t.getClass()
                                .getSimpleName());
            }
        }
    }

    private void installBackgroundActivityLaunchGuard() {
        Class<?> instrumentation =
                load("android.app.Instrumentation");

        if (instrumentation == null) {
            return;
        }

        for (Method method :
                instrumentation.getDeclaredMethods()) {
            if (!"execStartActivity"
                    .equals(method.getName())) {
                continue;
            }

            Class<?>[] parameterTypes =
                    method.getParameterTypes();

            int intentIndex = -1;
            int optionsIndex = -1;

            for (int i = 0;
                 i < parameterTypes.length;
                 i++) {
                if (Intent.class
                        .isAssignableFrom(
                                parameterTypes[i])) {
                    intentIndex = i;
                } else if (Bundle.class
                        .isAssignableFrom(
                                parameterTypes[i])) {
                    optionsIndex = i;
                }
            }

            if (intentIndex < 0
                    || optionsIndex < 0) {
                continue;
            }

            final int finalIntentIndex =
                    intentIndex;
            final int finalOptionsIndex =
                    optionsIndex;

            try {
                method.setAccessible(true);

                String key =
                        "background-launch:"
                                + method.toGenericString();

                if (!installedHooks.add(key)) {
                    continue;
                }

                module.hook(method)
                        .intercept(chain -> {
                            if (!shouldKeepSelfLaunchInBackground()) {
                                return chain.proceed();
                            }

                            Object value =
                                    chain.getArg(
                                            finalIntentIndex);

                            if (!(value instanceof Intent intent)
                                    || !isSamePackageIntent(
                                            intent)) {
                                return chain.proceed();
                            }

                            Object[] args =
                                    chain.getArgs()
                                            .toArray();

                            Bundle original =
                                    args[finalOptionsIndex]
                                            instanceof Bundle bundle
                                            ? bundle
                                            : null;

                            Bundle guarded =
                                    original == null
                                            ? new Bundle()
                                            : new Bundle(
                                                    original);

                            guarded.putBoolean(
                                    OPTION_AVOID_MOVE_TO_FRONT,
                                    true);

                            args[finalOptionsIndex] =
                                    guarded;

                            log(
                                    Log.INFO,
                                    "APP_BACKGROUND_SELF_LAUNCH",
                                    "pkg="
                                            + packageName
                                            + " target="
                                            + intent
                                            .getComponent()
                                            + " mode="
                                            + GuardConfig
                                            .backgroundPlaybackMode(
                                                    packageName)
                                            + " avoidMoveToFront=true");

                            return chain.proceed(
                                    args);
                        });
            } catch (Throwable t) {
                installedHooks.remove(
                        "background-launch:"
                                + method.toGenericString());

                log(
                        Log.WARN,
                        "APP_BACKGROUND_LAUNCH_HOOK_FAILED",
                        "pkg=" + packageName
                                + " method="
                                + method.getName()
                                + " error="
                                + t.getClass()
                                .getSimpleName());
            }
        }
    }

    private void installPlayerHooks() {
        hookPlayMethod(
                "android.media.MediaPlayer",
                "start");
        hookPlayMethod(
                "android.widget.VideoView",
                "start");
        hookPlayMethod(
                "androidx.media3.common.BasePlayer",
                "play");
        hookBooleanPlayMethod(
                "androidx.media3.exoplayer.ExoPlayerImpl",
                "setPlayWhenReady");
        hookBooleanPlayMethod(
                "androidx.media3.exoplayer.SimpleExoPlayer",
                "setPlayWhenReady");
        hookPlayMethod(
                "com.google.android.exoplayer2.BasePlayer",
                "play");
        hookBooleanPlayMethod(
                "com.google.android.exoplayer2.ExoPlayerImpl",
                "setPlayWhenReady");
        hookBooleanPlayMethod(
                "com.google.android.exoplayer2.SimpleExoPlayer",
                "setPlayWhenReady");
        hookPlayMethod(
                "tv.danmaku.ijk.media.player.IjkMediaPlayer",
                "start");
        hookPlayMethod(
                "com.ss.ttvideoengine.TTVideoEngine",
                "play");
        hookPlayMethod(
                "com.ss.ttvideoengine.MediaPlayerWrapper",
                "start");
        hookPlayMethod(
                "com.ss.android.videoshop.mediaview.SimpleMediaView",
                "play");
        hookPlayMethod(
                "com.ss.android.videoshop.mediaview.LayerHostMediaLayout",
                "play");
        hookPlayMethod(
                "com.ss.android.videoshop.context.VideoContext",
                "play");
        hookPlayMethod(
                "org.videolan.libvlc.MediaPlayer",
                "play");
        hookPlayMethod(
                "com.tencent.rtmp.TXVodPlayer",
                "resume");
        hookPlayMethod(
                "com.tencent.rtmp.TXLivePlayer",
                "resume");
        hookPlayMethod(
                "com.pili.pldroid.player.PLMediaPlayer",
                "start");
        hookPlayMethod(
                "com.baidu.cloud.media.player.BDCloudMediaPlayer",
                "start");
        hookPlayMethod(
                "com.ksyun.media.player.KSYMediaPlayer",
                "start");

        hookZeroArgVoid(
                "android.media.MediaPlayer",
                "pause",
                "stop");

        hookZeroArgVoid(
                "android.widget.VideoView",
                "pause",
                "stopPlayback");

        hookZeroArgVoid(
                "androidx.media3.common.BasePlayer",
                "pause",
                "stop");

        hookBooleanFalseVoid(
                "androidx.media3.exoplayer.ExoPlayerImpl",
                "setPlayWhenReady");

        hookBooleanFalseVoid(
                "androidx.media3.exoplayer.SimpleExoPlayer",
                "setPlayWhenReady");

        hookZeroArgVoid(
                "com.google.android.exoplayer2.BasePlayer",
                "pause",
                "stop");

        hookBooleanFalseVoid(
                "com.google.android.exoplayer2.ExoPlayerImpl",
                "setPlayWhenReady");

        hookBooleanFalseVoid(
                "com.google.android.exoplayer2.SimpleExoPlayer",
                "setPlayWhenReady");

        hookZeroArgVoid(
                "tv.danmaku.ijk.media.player.IjkMediaPlayer",
                "pause",
                "stop");

        hookZeroArgVoid(
                "com.ss.ttvideoengine.TTVideoEngine",
                "pause",
                "stop");

        hookZeroArgVoid(
                "com.ss.ttvideoengine.MediaPlayerWrapper",
                "pause",
                "stop");

        hookZeroArgVoid(
                "com.ss.android.videoshop.mediaview.SimpleMediaView",
                "pause");

        hookZeroArgVoid(
                "com.ss.android.videoshop.mediaview.LayerHostMediaLayout",
                "pause");

        hookZeroArgVoid(
                "com.ss.android.videoshop.context.VideoContext",
                "pause");

        hookZeroArgVoid(
                "org.videolan.libvlc.MediaPlayer",
                "pause",
                "stop");

        hookZeroArgVoid(
                "com.tencent.rtmp.TXVodPlayer",
                "pause");

        hookZeroArgVoid(
                "com.tencent.rtmp.TXLivePlayer",
                "pause");

        hookZeroArgVoid(
                "com.pili.pldroid.player.PLMediaPlayer",
                "pause",
                "stop");

        hookZeroArgVoid(
                "com.baidu.cloud.media.player.BDCloudMediaPlayer",
                "pause",
                "stop");

        hookZeroArgVoid(
                "com.ksyun.media.player.KSYMediaPlayer",
                "pause",
                "stop");
    }

    private void hookZeroArgVoid(
            String className,
            String... methodNames
    ) {
        Class<?> type =
                load(className);

        if (type == null) {
            return;
        }

        for (Method method :
                type.getDeclaredMethods()) {
            if (method.getParameterCount() != 0
                    || method.getReturnType()
                    != void.class
                    || Modifier.isAbstract(
                            method.getModifiers())) {
                continue;
            }

            boolean match = false;

            for (String methodName :
                    methodNames) {
                if (methodName.equals(
                        method.getName())) {
                    match = true;
                    break;
                }
            }

            if (!match) {
                continue;
            }

            installPauseMethod(
                    className,
                    method,
                    null);
        }
    }

    private void hookBooleanFalseVoid(
            String className,
            String methodName
    ) {
        Class<?> type =
                load(className);

        if (type == null) {
            return;
        }

        for (Method method :
                type.getDeclaredMethods()) {
            if (!methodName.equals(
                    method.getName())
                    || method.getReturnType()
                    != void.class
                    || method.getParameterCount()
                    != 1
                    || method.getParameterTypes()[0]
                    != boolean.class
                    || Modifier.isAbstract(
                            method.getModifiers())) {
                continue;
            }

            installPauseMethod(
                    className,
                    method,
                    Boolean.FALSE);
        }
    }

    private void installPauseMethod(
            String className,
            Method method,
            Boolean onlyBooleanValue
    ) {
        try {
            method.setAccessible(true);

            String key =
                    "player:"
                            + method.toGenericString();

            if (!installedHooks.add(key)) {
                return;
            }

            module.hook(method)
                    .intercept(chain -> {
                        if (!selected()
                                || !lifecyclePauseActive()) {
                            return chain.proceed();
                        }

                        if (onlyBooleanValue != null) {
                            if (chain.getArgs().isEmpty()
                                    || !(chain.getArgs()
                                    .get(0)
                                    instanceof Boolean value)
                                    || value
                                    != onlyBooleanValue) {
                                return chain.proceed();
                            }
                        }

                        String configuredMode =
                                GuardConfig
                                        .backgroundPlaybackMode(
                                                packageName);

                        if (GuardConfig
                                .PLAYBACK_MODE_NATIVE
                                .equals(configuredMode)) {
                            log(
                                    Log.INFO,
                                    "APP_PLAYER_PAUSE_NATIVE",
                                    "pkg=" + packageName
                                            + " player="
                                            + className
                                            + " method="
                                            + method.getName());

                            return chain.proceed();
                        }

                        rememberPlayer(
                                chain.getThisObject());

                        log(
                                Log.INFO,
                                "APP_PLAYER_PAUSE_BLOCK",
                                "pkg=" + packageName
                                        + " mode="
                                        + configuredMode
                                        + " player="
                                        + className
                                        + " method="
                                        + method.getName());

                        return null;
                    });
        } catch (Throwable t) {
            installedHooks.remove(
                    "player:"
                            + method.toGenericString());

            log(
                    Log.WARN,
                    "APP_PLAYER_HOOK_FAILED",
                    "pkg=" + packageName
                            + " class="
                            + className
                            + " method="
                            + method.getName()
                            + " error="
                            + t.getClass()
                            .getSimpleName());
        }
    }

    private void hookPlayMethod(
            String className,
            String methodName
    ) {
        Class<?> type =
                load(className);

        if (type == null) {
            return;
        }

        for (Method method :
                type.getDeclaredMethods()) {
            if (!methodName.equals(
                    method.getName())
                    || method.getParameterCount() != 0
                    || Modifier.isAbstract(
                            method.getModifiers())) {
                continue;
            }

            try {
                method.setAccessible(true);

                String key =
                        "play-track:"
                                + method.toGenericString();

                if (!installedHooks.add(key)) {
                    continue;
                }

                module.hook(method)
                        .intercept(chain -> {
                            Object result =
                                    chain.proceed();

                            if (selected()) {
                                rememberPlayer(
                                        chain.getThisObject());
                            }

                            return result;
                        });
            } catch (Throwable t) {
                installedHooks.remove(
                        "play-track:"
                                + method.toGenericString());
            }
        }
    }

    private void hookBooleanPlayMethod(
            String className,
            String methodName
    ) {
        Class<?> type =
                load(className);

        if (type == null) {
            return;
        }

        for (Method method :
                type.getDeclaredMethods()) {
            if (!methodName.equals(
                    method.getName())
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0]
                    != boolean.class
                    || Modifier.isAbstract(
                            method.getModifiers())) {
                continue;
            }

            try {
                method.setAccessible(true);

                String key =
                        "play-track:"
                                + method.toGenericString();

                if (!installedHooks.add(key)) {
                    continue;
                }

                module.hook(method)
                        .intercept(chain -> {
                            Object result =
                                    chain.proceed();

                            if (selected()
                                    && !chain.getArgs().isEmpty()
                                    && Boolean.TRUE.equals(
                                    chain.getArgs().get(0))) {
                                rememberPlayer(
                                        chain.getThisObject());
                            }

                            return result;
                        });
            } catch (Throwable t) {
                installedHooks.remove(
                        "play-track:"
                                + method.toGenericString());
            }
        }
    }

    private void rememberPlayer(
            Object player
    ) {
        if (player != null) {
            currentPlayer =
                    new WeakReference<>(
                            player);
            ensureControlReceiver(null);
        }
    }

    private void ensureControlReceiver(
            java.util.List<Object> args
    ) {
        if (controlReceiverRegistered) {
            return;
        }

        Context context = null;

        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Activity activity) {
                    context =
                            activity
                                    .getApplicationContext();
                    break;
                }

                if (arg instanceof Context ctx) {
                    context =
                            ctx.getApplicationContext();
                    break;
                }
            }
        }

        if (context == null) {
            context = resolveApplicationContext();
        }

        if (context == null) {
            return;
        }

        synchronized (this) {
            if (controlReceiverRegistered) {
                return;
            }

            try {
                IntentFilter filter =
                        new IntentFilter();
                filter.addAction(
                        PlaybackControlContract
                                .ACTION_PAUSE);
                filter.addAction(
                        PlaybackControlContract
                                .ACTION_PLAY);
                filter.addAction(
                        PlaybackControlContract
                                .ACTION_PREVIOUS);
                filter.addAction(
                        PlaybackControlContract
                                .ACTION_NEXT);

                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(
                            controlReceiver,
                            filter,
                            PlaybackControlContract
                                    .PERMISSION_CONTROL_PLAYBACK,
                            null,
                            Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(
                            controlReceiver,
                            filter,
                            PlaybackControlContract
                                    .PERMISSION_CONTROL_PLAYBACK,
                            null);
                }

                controlReceiverRegistered = true;

                log(
                        Log.INFO,
                        "APP_PLAYBACK_CONTROL_READY",
                        "pkg=" + packageName);
            } catch (Throwable t) {
                log(
                        Log.WARN,
                        "APP_PLAYBACK_CONTROL_FAILED",
                        "pkg=" + packageName
                                + " error="
                                + t.getClass()
                                .getSimpleName());
            }
        }
    }

    private void controlFromNotification(
            boolean play
    ) {
        Object player =
                currentPlayer.get();

        boolean direct =
                player != null
                        && (play
                        ? invokePlay(player)
                        : invokePause(player));

        log(
                direct ? Log.INFO : Log.WARN,
                play
                        ? "APP_PLAYBACK_CONTROL_PLAY"
                        : "APP_PLAYBACK_CONTROL_PAUSE",
                "pkg=" + packageName
                        + " mode="
                        + (direct
                        ? "direct"
                        : "no-target-player"));
    }

    private void skipFromNotification(
            boolean next
    ) {
        Object player =
                currentPlayer.get();

        boolean direct =
                player != null
                        && invokeSkip(
                        player,
                        next);

        log(
                direct ? Log.INFO : Log.WARN,
                next
                        ? "APP_PLAYBACK_CONTROL_NEXT"
                        : "APP_PLAYBACK_CONTROL_PREVIOUS",
                "pkg=" + packageName
                        + " mode="
                        + (direct
                        ? "direct"
                        : "no-target-player"));
    }

    private boolean invokePause(
            Object player
    ) {
        if (invokeNoArg(
                player,
                "pause")) {
            return true;
        }

        return invokeBoolean(
                player,
                "setPlayWhenReady",
                false);
    }

    private boolean invokePlay(
            Object player
    ) {
        if (invokeNoArg(
                player,
                "play")) {
            return true;
        }

        if (invokeNoArg(
                player,
                "start")) {
            return true;
        }

        if (invokeNoArg(
                player,
                "resume")) {
            return true;
        }

        return invokeBoolean(
                player,
                "setPlayWhenReady",
                true);
    }

    private boolean invokeSkip(
            Object player,
            boolean next
    ) {
        String[] names =
                next
                        ? new String[]{
                        "seekToNextMediaItem",
                        "seekToNext",
                        "next",
                        "playNext",
                        "skipToNext"
                }
                        : new String[]{
                        "seekToPreviousMediaItem",
                        "seekToPrevious",
                        "previous",
                        "playPrevious",
                        "skipToPrevious"
                };

        for (String name : names) {
            if (invokeNoArg(
                    player,
                    name)) {
                return true;
            }
        }

        return false;
    }

    private boolean invokeNoArg(
            Object target,
            String methodName
    ) {
        if (target == null) {
            return false;
        }

        for (Class<?> current =
             target.getClass();
             current != null;
             current = current.getSuperclass()) {
            try {
                Method method =
                        current.getDeclaredMethod(
                                methodName);
                method.setAccessible(true);
                method.invoke(target);
                return true;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                return false;
            }
        }

        return false;
    }

    private boolean invokeBoolean(
            Object target,
            String methodName,
            boolean value
    ) {
        if (target == null) {
            return false;
        }

        for (Class<?> current =
             target.getClass();
             current != null;
             current = current.getSuperclass()) {
            try {
                Method method =
                        current.getDeclaredMethod(
                                methodName,
                                boolean.class);
                method.setAccessible(true);
                method.invoke(
                        target,
                        value);
                return true;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                return false;
            }
        }

        return false;
    }

    private boolean shouldKeepSelfLaunchInBackground() {
        if (!selected()
                || appForeground) {
            return false;
        }

        return !GuardConfig
                .PLAYBACK_MODE_NATIVE
                .equals(
                        GuardConfig
                        .backgroundPlaybackMode(
                                packageName));
    }

    private boolean isSamePackageIntent(
            Intent intent
    ) {
        if (intent == null) {
            return false;
        }

        if (intent.getComponent() != null) {
            return packageName.equals(
                    intent.getComponent()
                            .getPackageName());
        }

        if (intent.getPackage() != null) {
            return packageName.equals(
                    intent.getPackage());
        }

        Context context =
                resolveApplicationContext();

        if (context == null) {
            return false;
        }

        try {
            android.content.ComponentName resolved =
                    intent.resolveActivity(
                            context
                                    .getPackageManager());

            return resolved != null
                    && packageName.equals(
                            resolved
                                    .getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean selected() {
        return GuardConfig.enabled()
                && GuardConfig
                .backgroundPlaybackPackage(
                        packageName);
    }

    private void enterLifecycle() {
        lifecycleDepth.set(
                lifecycleDepth.get() + 1);
    }

    private void exitLifecycle() {
        int value =
                lifecycleDepth.get() - 1;

        if (value <= 0) {
            lifecycleDepth.remove();
        } else {
            lifecycleDepth.set(value);
        }
    }

    private boolean lifecyclePauseActive() {
        if (lifecycleDepth.get() > 0) {
            return true;
        }

        // Fallback for OEM/framework variants that bypass Instrumentation
        // but still execute the player call synchronously from ActivityThread.
        for (StackTraceElement frame :
                Thread.currentThread()
                        .getStackTrace()) {
            String className =
                    frame.getClassName();
            String methodName =
                    frame.getMethodName();

            if ("android.app.ActivityThread"
                    .equals(className)
                    && (methodName.contains(
                            "performPauseActivity")
                    || methodName.contains(
                            "performStopActivity"))) {
                return true;
            }

            if ("android.app.Activity"
                    .equals(className)
                    && (methodName.contains(
                            "performPause")
                    || methodName.contains(
                            "performStop"))) {
                return true;
            }

            if ("android.app.Instrumentation"
                    .equals(className)
                    && ("callActivityOnPause"
                    .equals(methodName)
                    || "callActivityOnStop"
                    .equals(methodName))) {
                return true;
            }
        }

        return false;
    }

    private Context resolveApplicationContext() {
        try {
            Class<?> activityThread =
                    load("android.app.ActivityThread");
            if (activityThread == null) {
                return null;
            }

            Method currentApplication =
                    activityThread.getDeclaredMethod(
                            "currentApplication");
            currentApplication.setAccessible(true);

            Object value =
                    currentApplication.invoke(null);

            if (value instanceof Context context) {
                Context app =
                        context.getApplicationContext();
                return app == null
                        ? context
                        : app;
            }
        } catch (Throwable t) {
            log(
                    Log.WARN,
                    "APP_PLAYBACK_CONTEXT_UNAVAILABLE",
                    "pkg=" + packageName
                            + " process="
                            + android.app.Application
                            .getProcessName()
                            + " error="
                            + t.getClass()
                            .getSimpleName());
        }

        return null;
    }

    private Class<?> load(
            String className
    ) {
        try {
            return Class.forName(
                    className,
                    false,
                    classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void log(
            int level,
            String event,
            String detail
    ) {
        try {
            module.log(
                    level,
                    TAG,
                    event + " "
                            + detail);
        } catch (Throwable ignored) {
        }
    }
}
