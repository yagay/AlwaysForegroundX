package com.yagay.MiniWindowGuard;

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic app-process playback compatibility layer.
 *
 * system_server is allowed to complete Activity lifecycle transitions normally.
 * This class only suppresses common player pause/stop calls when they are made
 * synchronously from Activity onPause/onStop. Manual pause remains untouched.
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

        guard.installLifecycleHooks();
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

            if (!"callActivityOnPause"
                    .equals(name)
                    && !"callActivityOnStop"
                    .equals(name)) {
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

                            enterLifecycle();

                            try {
                                return chain.proceed();
                            } finally {
                                exitLifecycle();
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

    private void installPlayerHooks() {
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

                        log(
                                Log.INFO,
                                "APP_PLAYER_PAUSE_BLOCK",
                                "pkg=" + packageName
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
