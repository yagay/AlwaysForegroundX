package com.jieei.alwaysforeground;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.SharedPreferences;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class AlwaysForegroundModule extends XposedModule {
    private static final String TAG = "AlwaysForeground";
    private static final String MODULE_PACKAGE = "com.jieei.alwaysforeground";
    private static final String HONGGUO_PACKAGE = "com.phoenix.read";
    private static final int MAX_HOOK_EVENTS = 120;

    private final Set<String> firstHitLogs = ConcurrentHashMap.newKeySet();
    private final Set<String> endpointHooks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> lastEndpointEvents = new ConcurrentHashMap<>();
    private final AtomicInteger hookEventCounter = new AtomicInteger();
    private final ThreadLocal<Boolean> tracingEndpoint = ThreadLocal.withInitial(() -> false);
    private volatile String activePackage;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            SharedPreferences prefs = getRemotePreferences(ModeConfig.REMOTE_GROUP);
            TargetConfig.initialize(prefs);
            log(Log.INFO, TAG, "remote preferences ready; mode=" + TargetConfig.getMode());
        } catch (Throwable t) {
            TargetConfig.initialize(null);
            log(Log.WARN, TAG, "remote preferences unavailable; falling back to standard mode", t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;
        if (MODULE_PACKAGE.equals(param.getPackageName())) return;

        activePackage = param.getPackageName();
        log(Log.INFO, TAG, "installing framework hooks for " + activePackage
                + ", mode=" + TargetConfig.getMode());

        installStandardHooks();
        installEnhancedHooks();
        installSafeStrongHooks();
        installLifecycleDiagnostics();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        if (MODULE_PACKAGE.equals(param.getPackageName())) return;

        // Playback endpoint hooks are safe diagnostics: they always proceed and only emit
        // stack traces while an explicit diagnostic session targets this package.
        installPlaybackEndpointDiagnostics(param.getClassLoader());

        if (HONGGUO_PACKAGE.equals(param.getPackageName())) {
            installHongguoFragmentDiagnostics(param.getClassLoader());
        }
    }

    private void installStandardHooks() {
        hookBoolean(PowerManager.class, "isInteractive", true, ModeConfig.MODE_STANDARD);
        hookBoolean(PowerManager.class, "isScreenOn", true, ModeConfig.MODE_STANDARD);
        hookBoolean(KeyguardManager.class, "isKeyguardLocked", false, ModeConfig.MODE_STANDARD);
        hookBoolean(KeyguardManager.class, "isDeviceLocked", false, ModeConfig.MODE_STANDARD);
        hookBoolean(KeyguardManager.class, "inKeyguardRestrictedInputMode", false, ModeConfig.MODE_STANDARD);
        hookRunningProcesses();
    }

    private void installEnhancedHooks() {
        hookBoolean(ActivityManager.class, "isBackgroundRestricted", false, ModeConfig.MODE_ENHANCED);
        hookBoolean(PowerManager.class, "isDeviceIdleMode", false, ModeConfig.MODE_ENHANCED);
        hookBoolean(PowerManager.class, "isPowerSaveMode", false, ModeConfig.MODE_ENHANCED);
        hookUidImportance();
        hookMyMemoryState();
    }

    private void installSafeStrongHooks() {
        hookBoolean(Activity.class, "hasWindowFocus", true, ModeConfig.MODE_STRONG);
    }

    private void installPlaybackEndpointDiagnostics(ClassLoader classLoader) {
        hookExactEndpoint(MediaPlayer.class, "pause");
        hookExactEndpoint(MediaPlayer.class, "stop");
        hookExactEndpoint(MediaPlayer.class, "reset");
        hookExactEndpoint(MediaPlayer.class, "release");
        hookExactEndpoint(AudioTrack.class, "pause");
        hookExactEndpoint(AudioTrack.class, "stop");
        hookExactEndpoint(AudioTrack.class, "flush");
        hookExactEndpoint(AudioTrack.class, "release");

        hookPlayerClassByName(classLoader, "com.ss.ttvideoengine.TTVideoEngine");
        hookPlayerClassByName(classLoader, "com.ss.ttvideoengine.TTVideoEngineImpl");
        hookPlayerClassByName(classLoader, "com.google.android.exoplayer2.ExoPlayerImpl");
        hookPlayerClassByName(classLoader, "com.google.android.exoplayer2.SimpleExoPlayer");
        hookPlayerClassByName(classLoader, "androidx.media3.exoplayer.ExoPlayerImpl");
        hookPlayerClassByName(classLoader, "androidx.media3.exoplayer.SimpleExoPlayer");
    }

    private void hookExactEndpoint(Class<?> clazz, String methodName) {
        try {
            Method method = clazz.getDeclaredMethod(methodName);
            hookPlaybackEndpoint(method, clazz.getName() + '.' + methodName, false);
        } catch (Throwable t) {
            logSkipped("endpoint " + clazz.getName() + '.' + methodName, t);
        }
    }

    private void hookPlayerClassByName(ClassLoader classLoader, String className) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            int installed = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                String name = method.getName();
                boolean stopLike = name.equals("pause") || name.equals("stop")
                        || name.equals("release") || name.equals("reset");
                boolean playWhenReady = name.equals("setPlayWhenReady")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == boolean.class;
                if (!stopLike && !playWhenReady) continue;
                method.setAccessible(true);
                hookPlaybackEndpoint(method, className + '.' + name, playWhenReady);
                installed++;
            }
            if (installed > 0) {
                logInstalled("playback endpoint class " + className + " methods=" + installed);
            }
        } catch (ClassNotFoundException ignored) {
            // Expected for player frameworks not bundled by this target app.
        } catch (Throwable t) {
            logSkipped("playback endpoint class " + className, t);
        }
    }

    private void hookPlaybackEndpoint(Method method, String sink, boolean onlyWhenFalseArg0) {
        String signature = method.toGenericString();
        if (!endpointHooks.add(signature)) return;
        try {
            hook(method).intercept(chain -> {
                if (TargetConfig.isDiagnosticsActiveFor(activePackage)) {
                    boolean shouldTrace = true;
                    if (onlyWhenFalseArg0) {
                        List<Object> args = chain.getArgs();
                        shouldTrace = !args.isEmpty() && Boolean.FALSE.equals(args.get(0));
                    }
                    if (shouldTrace) recordPlaybackEndpoint(chain.getThisObject(), sink, chain.getArgs());
                }
                return chain.proceed();
            });
            logInstalled("playback endpoint " + sink);
        } catch (Throwable t) {
            endpointHooks.remove(signature);
            logSkipped("playback endpoint " + sink, t);
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
            if (previous != null && now - previous < 750L) return;

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
                if (outputIndex >= 28) break;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "HOOK_TRACE_FAILED sink=" + sink + " error=" + t);
        } finally {
            tracingEndpoint.set(false);
        }
    }

    private static String findSuggestedCaller(StackTraceElement[] stack) {
        String best = null;
        int bestScore = -1;
        for (StackTraceElement frame : stack) {
            String candidate = frame.getClassName() + '.' + frame.getMethodName();
            int score = scoreFrame(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore >= 70 ? best : null;
    }

    private static int scoreFrame(String frame) {
        if (frame == null) return 0;
        if (frame.startsWith("com.dragon.read.") || frame.startsWith("com.phoenix.")) return 100;
        if (frame.startsWith("qv") || frame.startsWith("tp") || frame.startsWith("uf")
                || frame.startsWith("vo") || frame.startsWith("oo") || frame.startsWith("t56")) return 92;
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
            } else if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
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
                if (TargetConfig.getMode() >= ModeConfig.MODE_STRONG) {
                    Object fragment = chain.getThisObject();
                    if (fragment != null) {
                        logFirstHit("FRAGMENT " + methodName + " class="
                                + fragment.getClass().getName());
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
        hookInstrumentationActivity("callActivityOnResume", Activity.class);
        hookInstrumentationActivity("callActivityOnPause", Activity.class);
        hookInstrumentationActivity("callActivityOnStop", Activity.class);
        hookInstrumentationActivity("callActivityOnStart", Activity.class);
        hookInstrumentationActivity("callActivityOnRestart", Activity.class);
        hookInstrumentationActivity("callActivityOnDestroy", Activity.class);
        hookInstrumentationActivity("callActivityOnSaveInstanceState", Activity.class, Bundle.class);
    }

    private void hookInstrumentationActivity(String methodName, Class<?>... parameterTypes) {
        String label = "Instrumentation." + methodName;
        try {
            Method method = Instrumentation.class.getDeclaredMethod(methodName, parameterTypes);
            hook(method).intercept(chain -> {
                if (TargetConfig.getMode() >= ModeConfig.MODE_STRONG) {
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty() && args.get(0) instanceof Activity activity) {
                        log(Log.INFO, TAG, "LIFECYCLE " + methodName
                                + " activity=" + activity.getClass().getName()
                                + " finishing=" + activity.isFinishing()
                                + " changingConfig=" + activity.isChangingConfigurations()
                                + " package=" + activePackage);
                    }
                }
                return chain.proceed();
            });
            logInstalled(label);
        } catch (NoSuchMethodException e) {
            log(Log.INFO, TAG, "SKIPPED " + label + ": method not present");
        } catch (Throwable t) {
            logSkipped(label, t);
        }
    }

    private void hookRunningProcesses() {
        try {
            Method method = ActivityManager.class.getDeclaredMethod("getRunningAppProcesses");
            hook(method).intercept(chain -> {
                @SuppressWarnings("unchecked")
                List<ActivityManager.RunningAppProcessInfo> list =
                        (List<ActivityManager.RunningAppProcessInfo>) chain.proceed();
                if (TargetConfig.getMode() < ModeConfig.MODE_STANDARD || list == null) return list;

                int myPid = android.os.Process.myPid();
                boolean changed = false;
                for (ActivityManager.RunningAppProcessInfo info : list) {
                    if (info != null && info.pid == myPid) {
                        markForeground(info);
                        changed = true;
                    }
                }
                if (changed) logFirstHit("ActivityManager.getRunningAppProcesses");
                return list;
            });
            logInstalled("ActivityManager.getRunningAppProcesses");
        } catch (Throwable t) {
            logSkipped("ActivityManager.getRunningAppProcesses", t);
        }
    }

    private void hookUidImportance() {
        try {
            Method method = ActivityManager.class.getDeclaredMethod("getUidImportance", int.class);
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (TargetConfig.getMode() < ModeConfig.MODE_ENHANCED) return result;

                List<Object> args = chain.getArgs();
                if (!args.isEmpty()
                        && args.get(0) instanceof Integer uid
                        && uid == android.os.Process.myUid()) {
                    logFirstHit("ActivityManager.getUidImportance");
                    return ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
                return result;
            });
            logInstalled("ActivityManager.getUidImportance");
        } catch (Throwable t) {
            logSkipped("ActivityManager.getUidImportance", t);
        }
    }

    private void hookMyMemoryState() {
        try {
            Method method = ActivityManager.class.getDeclaredMethod(
                    "getMyMemoryState", ActivityManager.RunningAppProcessInfo.class);
            hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (TargetConfig.getMode() >= ModeConfig.MODE_ENHANCED) {
                    List<Object> args = chain.getArgs();
                    if (!args.isEmpty()
                            && args.get(0) instanceof ActivityManager.RunningAppProcessInfo info) {
                        markForeground(info);
                        logFirstHit("ActivityManager.getMyMemoryState");
                    }
                }
                return result;
            });
            logInstalled("ActivityManager.getMyMemoryState");
        } catch (Throwable t) {
            logSkipped("ActivityManager.getMyMemoryState", t);
        }
    }

    private void hookBoolean(Class<?> clazz, String methodName, boolean spoofValue, int minMode) {
        String label = clazz.getSimpleName() + "." + methodName;
        try {
            Method method = clazz.getDeclaredMethod(methodName);
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
            log(Log.INFO, TAG, "HIT " + hookName + " package=" + activePackage
                    + " mode=" + TargetConfig.getMode());
        }
    }

    private static void markForeground(ActivityManager.RunningAppProcessInfo info) {
        info.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        info.importanceReasonCode = 0;
        info.lru = 0;
    }
}
