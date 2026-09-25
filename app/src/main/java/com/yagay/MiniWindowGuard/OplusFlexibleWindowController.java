package com.yagay.MiniWindowGuard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passive runtime state for OxygenOS / ColorOS FlexibleWindow.
 *
 * MiniWindowGuard never starts apps and never requests a window transition.
 * OxygenOS owns launch, enter/exit flexible mode, surfaces, animations, input
 * and navigation. This class only observes the OEM task and decides whether a
 * package from the "always foreground" list is currently eligible for keepalive.
 */
final class OplusFlexibleWindowController {
    interface Logger {
        void log(String event, String detail);
    }

    private static final int EVENT_MINIMIZE_TO_FLOAT_HANDLE = 2002;
    private static final int EVENT_EXIT_TO_BACK = 2003;
    private static final Uri ENGINE_STATUS_URI =
            Uri.parse(
                    "content://com.yagay.MiniWindowGuard.engine_status");
    private static final String METHOD_BACKGROUND_PLAYBACK =
            "backgroundPlaybackNotification";
    private static final String KEY_PACKAGE_NAME = "package_name";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_REASON = "reason";

    private final Handler handler;
    private final Logger logger;
    private final Map<Integer, Session> sessions =
            new ConcurrentHashMap<>();
    private final Map<String, Boolean> backgroundNotificationStates =
            new ConcurrentHashMap<>();

    private volatile Context systemContext;
    private volatile boolean running;
    private volatile boolean keyguardShowing;
    private volatile String focusedPackage;
    private volatile boolean focusKnown;
    private volatile AudioManager audioManager;
    private volatile boolean audioCallbackRegistered;
    private volatile HandlerThread notificationThread;
    private volatile Handler notificationHandler;

    private final AudioManager.AudioPlaybackCallback
            audioPlaybackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(
                        List<AudioPlaybackConfiguration> configs
                ) {
                    if (!running) return;

                    updateAllBackgroundNotifications(
                            configs,
                            "audio-callback");
                }
            };

    OplusFlexibleWindowController(
            Handler handler,
            Context systemContext,
            Logger logger
    ) {
        this.handler = handler;
        this.systemContext = systemContext;
        this.logger = logger;
    }

    void start() {
        if (running) return;
        running = true;

        ensureNotificationWorker();
        ensureAudioPlaybackMonitor();

        log(
                "OPLUS_ENGINE_READY",
                "mode=passive"
                        + " foregroundApps="
                        + GuardConfig.stringSet(
                        ConfigKeys.FOREGROUND_PACKAGES)
                        .size()
                        + " forceSupportApps="
                        + GuardConfig.stringSet(
                        ConfigKeys.FORCE_SUPPORT_PACKAGES)
                        .size()
                        + " backgroundPlaybackApps="
                        + GuardConfig.stringSet(
                        ConfigKeys.BACKGROUND_PLAYBACK_PACKAGES)
                        .size());
    }

    void shutdown() {
        running = false;

        for (String packageName :
                GuardConfig.stringSet(
                        ConfigKeys.BACKGROUND_PLAYBACK_PACKAGES)) {
            setBackgroundNotification(
                    packageName,
                    false,
                    "engine-shutdown");
        }

        for (String packageName :
                backgroundNotificationStates.keySet()) {
            setBackgroundNotification(
                    packageName,
                    false,
                    "engine-shutdown");
        }

        if (audioCallbackRegistered
                && audioManager != null) {
            try {
                audioManager
                        .unregisterAudioPlaybackCallback(
                                audioPlaybackCallback);
            } catch (Throwable ignored) {
            }
        }

        audioCallbackRegistered = false;
        audioManager = null;
        sessions.clear();

        Handler worker = notificationHandler;
        HandlerThread workerThread = notificationThread;

        if (worker != null
                && workerThread != null) {
            worker.post(
                    () -> {
                        if (notificationHandler == worker) {
                            notificationHandler = null;
                        }
                        if (notificationThread == workerThread) {
                            notificationThread = null;
                        }
                        workerThread.quitSafely();
                    });
        } else {
            notificationHandler = null;
            notificationThread = null;
        }
    }

    int activeSessionCount() {
        int count = 0;

        for (Session session : sessions.values()) {
            if (isSessionProtected(session)) {
                count++;
            }
        }

        return count;
    }

    boolean wantsPackage(String packageName) {
        return GuardConfig.foregroundPackage(
                packageName)
                || GuardConfig.backgroundPlaybackPackage(
                packageName);
    }

    boolean isKnownPackage(String packageName) {
        return GuardConfig.foregroundPackage(
                packageName)
                || GuardConfig.forceSupportPackage(
                packageName);
    }

    boolean isForceSupportPackage(
            String packageName
    ) {
        return GuardConfig.forceSupportPackage(
                packageName);
    }

    boolean isForegroundPackage(
            String packageName
    ) {
        return GuardConfig.foregroundPackage(
                packageName);
    }

    boolean isBackgroundPlaybackPackage(
            String packageName
    ) {
        return GuardConfig.backgroundPlaybackPackage(
                packageName);
    }

    boolean isProtectedPackage(
            String packageName
    ) {
        if (packageName == null) {
            return false;
        }

        for (Session session : sessions.values()) {
            if (session.active
                    && packageName.equals(
                    session.packageName)
                    && isSessionProtected(
                    session)) {
                return true;
            }
        }

        return false;
    }

    String protectedPackageForProcess(
            String processName
    ) {
        if (processName == null
                || processName.isBlank()) {
            return null;
        }

        for (Session session : sessions.values()) {
            if (!session.active
                    || !isSessionProtected(
                    session)) {
                continue;
            }

            String pkg =
                    session.packageName;

            if (processName.equals(pkg)
                    || processName.startsWith(
                    pkg + ":")) {
                return pkg;
            }
        }

        return null;
    }

    void capture(
            Object activityRecord,
            String packageName
    ) {
        if (!running
                || activityRecord == null
                || !wantsPackage(
                packageName)) {
            return;
        }

        Object task =
                activityTask(
                        activityRecord);

        if (task == null) {
            log(
                    "OPLUS_TRACK_SKIP",
                    "pkg=" + packageName
                            + " reason=no-task");
            return;
        }

        if (systemContext == null) {
            systemContext =
                    deriveSystemContext(task);
        }

        ensureAudioPlaybackMonitor();

        int taskId =
                taskId(task);

        if (taskId < 0) {
            return;
        }

        Session session =
                sessions.compute(
                        taskId,
                        (id, current) -> {
                            if (current == null
                                    || !current.active
                                    || !packageName.equals(
                                    current.packageName)) {
                                current =
                                        new Session(
                                                taskId,
                                                packageName);
                            }

                            current.taskObject =
                                    task;
                            current.activityRecord =
                                    activityRecord;
                            current.active = true;
                            current.lastSeenElapsed =
                                    SystemClock
                                            .elapsedRealtime();

                            return current;
                        });

        if (session.backgroundProtected) {
            session.backgroundProtected = false;
            session.backgroundNotificationEligible = false;
            setBackgroundNotification(
                    session,
                    false,
                    "activity-resumed");

            log(
                    "BACKGROUND_PROTECTED",
                    "pkg=" + session.packageName
                            + " taskId=" + session.taskId
                            + " enabled=false"
                            + " reason=activity-resumed");
        }

        refreshSessionState(
                session,
                "activity-resumed");

        log(
                "OPLUS_TASK_TRACKED",
                "pkg=" + packageName
                        + " taskId=" + taskId
                        + " activity="
                        + activityComponent(
                        activityRecord)
                        + " flexible="
                        + isFlexibleTask(task)
                        + " floating="
                        + isInFloatingList(taskId)
                        + " keyguard="
                        + keyguardShowing
                        + " protected="
                        + isSessionProtected(session));
    }

    void onOplusTaskInfoChanged(
            Object taskInfo
    ) {
        if (!running
                || taskInfo == null) {
            return;
        }

        int taskId =
                taskInfoId(taskInfo);

        if (taskId < 0) return;

        String pkg =
                taskInfoPackage(taskInfo);

        Session session =
                sessions.get(taskId);

        if (session == null) {
            if (!wantsPackage(
                    pkg)) {
                return;
            }

            session =
                    new Session(
                            taskId,
                            pkg);
            sessions.put(
                    taskId,
                    session);
        } else if (pkg != null
                && !pkg.equals(
                session.packageName)) {
            setBackgroundNotification(
                    session,
                    false,
                    "task-package-changed");

            if (!wantsPackage(
                    pkg)) {
                sessions.remove(
                        taskId,
                        session);
                return;
            }

            session =
                    new Session(
                            taskId,
                            pkg);
            sessions.put(
                    taskId,
                    session);
        }

        boolean embedded =
                booleanField(
                        taskInfo,
                        "isInFlexibleEmbedded",
                        false);

        Rect bounds =
                taskInfoBounds(taskInfo);

        Rect maxBounds =
                taskInfoMaxBounds(taskInfo);

        boolean bounded =
                bounds != null
                        && maxBounds != null
                        && !bounds.isEmpty()
                        && !maxBounds.isEmpty()
                        && !bounds.equals(
                        maxBounds);

        session.oemReportedFlexible =
                embedded || bounded;
        session.lastOplusStateElapsed =
                SystemClock.elapsedRealtime();
        session.active = true;

        if (keyguardShowing
                && session.oemReportedFlexible) {
            session.lockKeepAlive = true;
        }

        refreshSessionState(
                session,
                "task-info");

        updateAllBackgroundNotifications(
                null,
                "task-info");

        log(
                "OPLUS_TASK_INFO",
                "pkg=" + session.packageName
                        + " taskId=" + taskId
                        + " embedded=" + embedded
                        + " bounded=" + bounded
                        + " floating="
                        + isInFloatingList(taskId)
                        + " lockKeepAlive="
                        + session.lockKeepAlive
                        + " bounds=" + bounds
                        + " maxBounds=" + maxBounds
                        + " protected="
                        + isSessionProtected(
                        session));
    }

    void onOplusTaskVanished(
            Object taskInfo
    ) {
        if (taskInfo == null) return;

        int taskId =
                taskInfoId(taskInfo);

        Session session =
                sessions.get(taskId);

        if (session == null) return;

        session.oemReportedFlexible = false;
        session.lastOplusStateElapsed =
                SystemClock.elapsedRealtime();

        boolean taskRunning =
                booleanField(
                        taskInfo,
                        "isRunning",
                        true);

        int displayId =
                intField(
                        taskInfo,
                        "displayId",
                        0);

        if (!taskRunning
                || displayId < 0) {
            setBackgroundNotification(
                    session,
                    false,
                    "task-vanished");
            session.active = false;
            sessions.remove(
                    taskId,
                    session);
        }

        log(
                "OPLUS_TASK_VANISHED",
                "pkg=" + session.packageName
                        + " taskId=" + taskId
                        + " isRunning="
                        + taskRunning
                        + " displayId="
                        + displayId
                        + " floating="
                        + isInFloatingList(taskId)
                        + " lockKeepAlive="
                        + session.lockKeepAlive);
    }

    void onFloatHandleOpened(
            int taskId
    ) {
        if (!running || taskId < 0) {
            return;
        }

        Session session =
                sessions.get(taskId);

        if (session == null
                || !session.active) {
            return;
        }

        boolean hadEdgeState =
                session.edgeMinimizeRequested
                        || session.edgeHung;

        session.edgeMinimizeRequested = false;
        session.edgeHung = false;
        session.lastSeenElapsed =
                SystemClock.elapsedRealtime();

        log(
                "OPLUS_EDGE_RESTORE",
                "pkg=" + session.packageName
                        + " taskId=" + taskId
                        + " cleared="
                        + hadEdgeState);
    }

    void onOplusFlexibleEvent(
            int taskId,
            int event
    ) {
        if (!running || taskId < 0) {
            return;
        }

        Session session =
                sessions.get(taskId);

        if (session == null
                || !session.active
                || !GuardConfig.foregroundPackage(
                session.packageName)) {
            return;
        }

        if (event
                == EVENT_MINIMIZE_TO_FLOAT_HANDLE) {
            session.edgeMinimizeRequested = true;
            session.edgeHung = true;
            session.lastSeenElapsed =
                    SystemClock.elapsedRealtime();

            log(
                    "OPLUS_EDGE_MINIMIZE_REQUEST",
                    "pkg=" + session.packageName
                            + " taskId=" + taskId
                            + " event=" + event);
            return;
        }

        if (event == EVENT_EXIT_TO_BACK) {
            session.edgeMinimizeRequested = false;
            session.edgeHung = false;

            log(
                    "OPLUS_EDGE_MINIMIZE_CANCEL",
                    "pkg=" + session.packageName
                            + " taskId=" + taskId
                            + " event=" + event);
        }
    }

    void preArmLockKeepAlive(
            String reason
    ) {
        if (!running) return;

        int armed = 0;

        for (Session session :
                sessions.values()) {
            if (!session.active
                    || !GuardConfig
                    .foregroundPackage(
                            session.packageName)) {
                continue;
            }

            if (!isNativeOplusWindow(
                    session)) {
                continue;
            }

            if (!session.lockKeepAlive) {
                session.lockKeepAlive = true;
                armed++;
            }
        }

        log(
                "OPLUS_LOCK_PREARM",
                "reason=" + reason
                        + " armed=" + armed);
    }

    void onKeyguardStateChanged(
            boolean showing
    ) {
        keyguardShowing = showing;

        updateAllBackgroundNotifications(
                null,
                showing
                        ? "keyguard-showing"
                        : "keyguard-hidden");

        if (showing) {
            for (Session session :
                    sessions.values()) {
                if (!session.active
                        || !GuardConfig
                        .foregroundPackage(
                                session.packageName)) {
                    continue;
                }

                boolean nativeOplus =
                        isNativeOplusWindow(
                                session);

                session.lockKeepAlive =
                        nativeOplus;

                if (nativeOplus) {
                    log(
                            "OPLUS_LOCK_KEEPALIVE",
                            "pkg="
                                    + session.packageName
                                    + " taskId="
                                    + session.taskId
                                    + " enabled=true");
                }
            }
            return;
        }

        for (Session session :
                sessions.values()) {
            if (session.lockKeepAlive) {
                session.lockKeepAlive =
                        false;

                log(
                        "OPLUS_LOCK_KEEPALIVE",
                        "pkg="
                                + session.packageName
                                + " taskId="
                                + session.taskId
                                + " enabled=false"
                                + " reason=keyguard-hidden");
            }
        }
    }

    boolean shouldSuppressBackgroundPause(
            Object task,
            String resumingPackage,
            boolean userLeaving,
            boolean uiSleeping,
            String reason,
            boolean finishing
    ) {
        Session session =
                sessionForTask(task);

        if (session == null
                || !session.active
                || !GuardConfig.backgroundPlaybackPackage(
                session.packageName)
                || GuardConfig.PLAYBACK_MODE_NATIVE.equals(
                GuardConfig.backgroundPlaybackMode(
                        session.packageName))) {
            return false;
        }

        if (finishing) {
            session.backgroundProtected = false;
            session.backgroundNotificationEligible = false;
            setBackgroundNotification(
                    session,
                    false,
                    "finishing");
            return false;
        }

        if (resumingPackage != null
                && session.packageName.equals(
                resumingPackage)) {
            if (session.backgroundProtected) {
                session.backgroundProtected = false;
                session.backgroundNotificationEligible = false;
                setBackgroundNotification(
                        session,
                        false,
                        "same-package-resume");
                log(
                        "BACKGROUND_PROTECTED",
                        "pkg=" + session.packageName
                                + " taskId=" + session.taskId
                                + " enabled=false"
                                + " reason=same-package-resume");
            }
            return false;
        }

        boolean switchingPackage =
                resumingPackage != null
                        && !session.packageName.equals(
                        resumingPackage);
        boolean explicitLeave =
                userLeaving
                        && resumingPackage == null;

        boolean recentsBackground =
                reason != null
                        && (reason.contains(
                                "pauseInRecentsAnim")
                        || reason.contains(
                                "pauseBackTasks"));

        if (!session.backgroundProtected
                && !switchingPackage
                && !explicitLeave
                && !uiSleeping
                && !recentsBackground) {
            return false;
        }

        boolean ordinaryBackground =
                switchingPackage
                        || explicitLeave
                        || recentsBackground;

        session.backgroundProtected = true;

        if (ordinaryBackground) {
            session.backgroundNotificationEligible = true;
        }

        session.taskObject = task;
        session.lastSeenElapsed =
                SystemClock.elapsedRealtime();

        updateBackgroundNotification(
                session,
                null,
                "background-enter");

        // Let Android complete Activity pause for Home, app switching and
        // display sleep/keyguard. The app-process Universal Playback Guard
        // keeps selected players running while framework visibility handling
        // is free to hide the video surface.
        boolean suppressFrameworkPause =
                false;

        log(
                suppressFrameworkPause
                        ? "BACKGROUND_PAUSE_SUPPRESS"
                        : "BACKGROUND_PAUSE_ALLOW",
                "pkg=" + session.packageName
                        + " taskId=" + session.taskId
                        + " resumingPkg=" + resumingPackage
                        + " userLeaving=" + userLeaving
                        + " uiSleeping=" + uiSleeping
                        + " recentsBackground="
                        + recentsBackground
                        + " frameworkPauseSuppressed="
                        + suppressFrameworkPause
                        + " reason=" + reason);

        return suppressFrameworkPause;
    }

    void onFocusedActivity(
            Object activityRecord
    ) {
        if (activityRecord == null) return;

        String newFocusedPackage =
                objectPackage(
                        activityRecord);

        if (newFocusedPackage != null) {
            focusedPackage = newFocusedPackage;
            focusKnown = true;

            updateAllBackgroundNotifications(
                    null,
                    "focus=" + newFocusedPackage);
        }

        Object task =
                activityTask(
                        activityRecord);
        if (task == null) return;

        Session session =
                sessions.get(
                        taskId(task));

        if (session == null
                || !session.backgroundProtected
                || !session.packageName.equals(
                objectPackage(activityRecord))) {
            return;
        }

        session.backgroundProtected = false;
        session.backgroundNotificationEligible = false;
        setBackgroundNotification(
                session,
                false,
                "focused");
        session.activityRecord = activityRecord;
        session.taskObject = task;
        session.lastSeenElapsed =
                SystemClock.elapsedRealtime();

        log(
                "BACKGROUND_PROTECTED",
                "pkg=" + session.packageName
                        + " taskId=" + session.taskId
                        + " enabled=false"
                        + " reason=focused");
    }

    boolean shouldBlockBackgroundStop(
            Object task,
            boolean finishing
    ) {
        Session session =
                sessionForTask(task);

        if (session == null
                || !session.active
                || !GuardConfig.backgroundPlaybackPackage(
                session.packageName)) {
            return false;
        }

        if (finishing) {
            session.backgroundProtected = false;
            session.backgroundNotificationEligible = false;
            setBackgroundNotification(
                    session,
                    false,
                    "finishing");
            return false;
        }

        if (!session.backgroundProtected) {
            return false;
        }

        session.taskObject = task;
        session.lastSeenElapsed =
                SystemClock.elapsedRealtime();

        log(
                "BACKGROUND_STOP_SUPPRESS",
                "pkg=" + session.packageName
                        + " taskId="
                        + session.taskId);

        return true;
    }

    private synchronized void ensureNotificationWorker() {
        if (notificationHandler != null
                && notificationThread != null
                && notificationThread.isAlive()) {
            return;
        }

        HandlerThread thread =
                new HandlerThread(
                        "MiniWindowGuard-Notifier",
                        Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();

        notificationThread = thread;
        notificationHandler =
                new Handler(
                        thread.getLooper());

        log(
                "BACKGROUND_NOTIFICATION_WORKER",
                "enabled=true");
    }

    private Handler notificationWorker() {
        Handler worker = notificationHandler;

        if (worker == null) {
            ensureNotificationWorker();
            worker = notificationHandler;
        }

        return worker;
    }

    private static List<AudioPlaybackConfiguration> copyPlaybackConfigs(
            List<AudioPlaybackConfiguration> configs
    ) {
        return configs == null
                ? null
                : List.copyOf(configs);
    }

    private void ensureAudioPlaybackMonitor() {
        if (audioCallbackRegistered
                || systemContext == null) {
            return;
        }

        try {
            AudioManager manager =
                    systemContext.getSystemService(
                            AudioManager.class);

            if (manager == null) return;

            manager.registerAudioPlaybackCallback(
                    audioPlaybackCallback,
                    handler);

            audioManager = manager;
            audioCallbackRegistered = true;

            log(
                    "BACKGROUND_AUDIO_MONITOR",
                    "enabled=true");
        } catch (Throwable t) {
            log(
                    "BACKGROUND_AUDIO_MONITOR",
                    "enabled=false error="
                            + t.getClass()
                            .getSimpleName());
        }
    }

    private void updateBackgroundNotification(
            Session session,
            List<AudioPlaybackConfiguration> configs,
            String reason
    ) {
        if (session == null) return;

        String packageName = session.packageName;
        List<AudioPlaybackConfiguration> snapshot =
                copyPlaybackConfigs(configs);
        Handler worker = notificationWorker();

        if (worker == null) return;

        worker.post(
                () -> updateBackgroundNotification(
                        packageName,
                        snapshot,
                        reason));
    }

    private void updateAllBackgroundNotifications(
            List<AudioPlaybackConfiguration> configs,
            String reason
    ) {
        List<AudioPlaybackConfiguration> snapshot =
                copyPlaybackConfigs(configs);
        Handler worker = notificationWorker();

        if (worker == null) return;

        worker.post(
                () -> updateAllBackgroundNotificationsOnWorker(
                        snapshot,
                        reason));
    }

    private void updateAllBackgroundNotificationsOnWorker(
            List<AudioPlaybackConfiguration> configs,
            String reason
    ) {
        for (String packageName :
                GuardConfig.stringSet(
                        ConfigKeys.BACKGROUND_PLAYBACK_PACKAGES)) {
            updateBackgroundNotification(
                    packageName,
                    configs,
                    reason);
        }

        for (String packageName :
                backgroundNotificationStates.keySet()) {
            if (!GuardConfig.backgroundPlaybackPackage(
                    packageName)) {
                setBackgroundNotification(
                        packageName,
                        false,
                        "removed-from-list");
            }
        }
    }

    private void updateBackgroundNotification(
            String packageName,
            List<AudioPlaybackConfiguration> configs,
            String reason
    ) {
        if (packageName == null
                || packageName.isBlank()) {
            return;
        }

        boolean shouldShow =
                focusKnown
                        && !keyguardShowing
                        && !packageName.equals(
                                focusedPackage)
                        && !hasNativeOplusWindowForPackage(
                                packageName)
                        && isPackagePlaybackActive(
                                packageName,
                                configs);

        setBackgroundNotification(
                packageName,
                shouldShow,
                reason);
    }

    private boolean hasNativeOplusWindowForPackage(
            String packageName
    ) {
        for (Session session :
                sessions.values()) {
            if (session != null
                    && session.active
                    && packageName.equals(
                            session.packageName)
                    && isNativeOplusWindow(
                            session)) {
                return true;
            }
        }

        return false;
    }

    private boolean isPackagePlaybackActive(
            String packageName,
            List<AudioPlaybackConfiguration> configs
    ) {
        if (systemContext == null
                || packageName == null
                || packageName.isBlank()) {
            return false;
        }

        try {
            int uid =
                    systemContext
                            .getPackageManager()
                            .getPackageUid(
                                    packageName,
                                    0);

            List<AudioPlaybackConfiguration> current =
                    configs;

            if (current == null) {
                AudioManager manager =
                        audioManager;

                if (manager == null) return false;

                current =
                        manager
                                .getActivePlaybackConfigurations();
            }

            if (current == null) return false;

            for (AudioPlaybackConfiguration config :
                    current) {
                if (config == null) {
                    continue;
                }

                Object activeValue =
                        invokeNoArg(
                                config,
                                "isActive");

                boolean active =
                        Boolean.TRUE.equals(
                                activeValue);

                if (activeValue == null) {
                    Object state =
                            invokeNoArg(
                                    config,
                                    "getPlayerState");

                    if (!(state
                            instanceof Number)) {
                        state =
                                fieldValue(
                                        config,
                                        "mPlayerState");
                    }

                    active =
                            state instanceof Number
                                    && ((Number) state)
                                    .intValue() == 2;
                }

                Object clientUid =
                        invokeNoArg(
                                config,
                                "getClientUid");

                if (!(clientUid
                        instanceof Number)) {
                    clientUid =
                            fieldValue(
                                    config,
                                    "mClientUid");
                }

                if (active
                        && clientUid
                        instanceof Number
                        && ((Number) clientUid)
                        .intValue() == uid) {
                    return true;
                }
            }
        } catch (Throwable t) {
            log(
                    "BACKGROUND_AUDIO_QUERY",
                    "pkg=" + packageName
                            + " error="
                            + t.getClass()
                            .getSimpleName());
        }

        return false;
    }

    private void setBackgroundNotification(
            Session session,
            boolean active,
            String reason
    ) {
        if (session == null) return;

        setBackgroundNotification(
                session.packageName,
                active,
                reason);
    }

    private void setBackgroundNotification(
            String packageName,
            boolean active,
            String reason
    ) {
        if (packageName == null
                || packageName.isBlank()) {
            return;
        }

        Handler worker = notificationWorker();

        if (worker == null) return;

        Runnable action =
                () -> applyBackgroundNotification(
                        packageName,
                        active,
                        reason);

        if (Looper.myLooper()
                == worker.getLooper()) {
            action.run();
        } else {
            worker.post(action);
        }
    }

    private void applyBackgroundNotification(
            String packageName,
            boolean active,
            String reason
    ) {
        if (packageName == null
                || packageName.isBlank()) {
            return;
        }

        boolean visible =
                Boolean.TRUE.equals(
                        backgroundNotificationStates
                                .get(packageName));

        // A notification manually paused by the user can remain visible
        // while audio is inactive. Always forward inactive events so the app
        // process can distinguish audio-only inactivity from real cleanup.
        if (active && visible) {
            return;
        }

        Context context =
                systemContext;

        if (context == null) return;

        try {
            Bundle extras =
                    new Bundle();

            extras.putString(
                    KEY_PACKAGE_NAME,
                    packageName);
            extras.putBoolean(
                    KEY_ACTIVE,
                    active);
            extras.putString(
                    KEY_REASON,
                    reason == null
                            ? ""
                            : reason);

            context.getContentResolver()
                    .call(
                            ENGINE_STATUS_URI,
                            METHOD_BACKGROUND_PLAYBACK,
                            null,
                            extras);

            if (active) {
                backgroundNotificationStates.put(
                        packageName,
                        true);
            } else {
                backgroundNotificationStates.remove(
                        packageName);
            }

            log(
                    active
                            ? "BACKGROUND_NOTIFICATION_SHOW"
                            : "BACKGROUND_NOTIFICATION_HIDE",
                    "pkg=" + packageName
                            + " focused="
                            + focusedPackage
                            + " keyguard="
                            + keyguardShowing
                            + " reason="
                            + reason);
        } catch (Throwable t) {
            log(
                    "BACKGROUND_NOTIFICATION_ERROR",
                    "pkg=" + packageName
                            + " active=" + active
                            + " error="
                            + t.getClass()
                            .getSimpleName());
        }
    }

    boolean shouldSuppressRecentsPause(
            Object task
    ) {
        Session session =
                sessionForTask(task);

        if (session == null
                || !session.active
                || !GuardConfig.foregroundPackage(
                session.packageName)
                || (!session.edgeMinimizeRequested
                && !session.edgeHung)) {
            return false;
        }

        session.edgeHung = true;
        session.taskObject = task;
        session.lastSeenElapsed =
                SystemClock.elapsedRealtime();

        log(
                "OPLUS_EDGE_PAUSE_SUPPRESS",
                "pkg=" + session.packageName
                        + " taskId="
                        + session.taskId
                        + " minimizeRequested="
                        + session.edgeMinimizeRequested);

        return true;
    }

    boolean shouldHoldEdgeTask(Object task) {
        Session session =
                sessionForTask(task);

        if (session == null
                || !session.active
                || !GuardConfig
                .foregroundPackage(
                        session.packageName)
                || !isInFloatingList(
                session.taskId)) {
            return false;
        }

        session.edgeHung = true;
        session.edgeMinimizeRequested = false;
        session.taskObject = task;
        session.lastSeenElapsed =
                SystemClock.elapsedRealtime();

        log(
                "OPLUS_EDGE_KEEPALIVE",
                "pkg=" + session.packageName
                        + " taskId="
                        + session.taskId
                        + " enabled=true");

        return true;
    }

    boolean isEdgeHungTask(Object task) {
        Session session =
                sessionForTask(task);

        if (session == null
                || !session.edgeHung) {
            return false;
        }

        if (!isInFloatingList(
                session.taskId)
                && !session.edgeMinimizeRequested) {
            session.edgeHung = false;

            log(
                    "OPLUS_EDGE_KEEPALIVE",
                    "pkg="
                            + session.packageName
                            + " taskId="
                            + session.taskId
                            + " enabled=false"
                            + " reason=left-floating-list");

            return false;
        }

        return true;
    }

    boolean shouldKeepTaskAwake(Object task) {
        Session session =
                sessionForTask(task);

        if (session == null
                || !session.active) {
            return false;
        }

        boolean oplusProtected =
                GuardConfig.foregroundPackage(
                        session.packageName)
                        && (isNativeOplusWindow(session)
                        || session.lockKeepAlive
                        || session.edgeHung
                        || session.edgeMinimizeRequested);

        boolean backgroundProtected =
                GuardConfig.backgroundPlaybackPackage(
                        session.packageName)
                        && session.backgroundProtected;

        return oplusProtected
                || backgroundProtected;
    }

    private Session sessionForTask(Object task) {
        if (task == null) return null;

        int id = taskId(task);

        Session session =
                sessions.get(id);

        if (session != null) {
            session.taskObject = task;
            return session;
        }

        String pkg =
                taskPackage(task);

        if ((!GuardConfig.foregroundPackage(pkg)
                && !GuardConfig.backgroundPlaybackPackage(pkg))
                || id < 0) {
            return null;
        }

        session =
                new Session(
                        id,
                        pkg);

        session.taskObject = task;

        sessions.put(
                id,
                session);

        return session;
    }

    private void refreshSessionState(
            Session session,
            String reason
    ) {
        if (session == null
                || !session.active) {
            return;
        }

        boolean floating =
                isInFloatingList(
                        session.taskId);

        if (!floating
                && session.edgeHung
                && !session.edgeMinimizeRequested) {
            session.edgeHung = false;
        }

        log(
                "OPLUS_STATE",
                "pkg=" + session.packageName
                        + " taskId="
                        + session.taskId
                        + " reason=" + reason
                        + " flexible="
                        + isFlexibleTask(
                        session.taskObject)
                        + " oem="
                        + session.oemReportedFlexible
                        + " floating="
                        + floating
                        + " edgeHung="
                        + session.edgeHung
                        + " lock="
                        + session.lockKeepAlive
                        + " background="
                        + session.backgroundProtected);
    }

    private boolean isSessionProtected(
            Session session
    ) {
        if (session == null
                || !session.active) {
            return false;
        }

        return isOplusSessionProtected(session)
                || (GuardConfig.backgroundPlaybackPackage(
                session.packageName)
                && session.backgroundProtected);
    }

    private boolean isOplusSessionProtected(
            Session session
    ) {
        return session != null
                && session.active
                && GuardConfig.foregroundPackage(
                session.packageName)
                && (session.lockKeepAlive
                || session.edgeHung
                || session.edgeMinimizeRequested
                || isNativeOplusWindow(session));
    }

    boolean shouldBlockTaskRemoval(
            String packageName
    ) {
        if (packageName == null) return false;

        for (Session session : sessions.values()) {
            if (packageName.equals(
                    session.packageName)
                    && isOplusSessionProtected(
                    session)) {
                return true;
            }
        }

        return false;
    }

    private boolean isNativeOplusWindow(
            Session session
    ) {
        if (session == null) {
            return false;
        }

        return session.oemReportedFlexible
                || isInFloatingList(
                session.taskId);
    }

    private boolean isInFloatingList(
            int taskId
    ) {
        if (taskId < 0) return false;

        try {
            Class<?> cls =
                    loadSystemClass(
                            "com.android.server.wm.FloatHandleController");

            Object instance =
                    cls.getMethod(
                            "getInstance")
                            .invoke(null);

            Object result =
                    cls.getMethod(
                            "isInFloatingList",
                            int.class)
                            .invoke(
                                    instance,
                                    taskId);

            return result instanceof Boolean
                    && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isFlexibleTask(
            Object task
    ) {
        if (task == null) return false;

        int mode =
                taskWindowingMode(task);

        if (mode != 0 && mode != 1) {
            return true;
        }

        Rect bounds =
                taskBounds(task);

        Rect maxBounds =
                taskMaxBounds(task);

        return bounds != null
                && maxBounds != null
                && !bounds.isEmpty()
                && !maxBounds.isEmpty()
                && !bounds.equals(
                maxBounds);
    }

    private static int taskWindowingMode(
            Object task
    ) {
        Object value =
                invokeNoArg(
                        task,
                        "getWindowingMode");

        return value instanceof Number
                ? ((Number) value)
                .intValue()
                : 1;
    }

    private static Rect taskBounds(Object task) {
        Object value =
                invokeNoArg(
                        task,
                        "getBounds");

        return value instanceof Rect
                ? new Rect((Rect) value)
                : new Rect();
    }

    private static Rect taskMaxBounds(
            Object task
    ) {
        Object config =
                invokeNoArg(
                        task,
                        "getConfiguration");

        Object windowConfig =
                fieldValue(
                        config,
                        "windowConfiguration");

        Object value =
                invokeNoArg(
                        windowConfig,
                        "getMaxBounds");

        return value instanceof Rect
                ? new Rect((Rect) value)
                : new Rect();
    }

    private static Object activityTask(
            Object activityRecord
    ) {
        Object task =
                invokeNoArg(
                        activityRecord,
                        "getTask");

        if (task != null) return task;

        task =
                fieldValue(
                        activityRecord,
                        "task");

        return task != null
                ? task
                : fieldValue(
                activityRecord,
                "mTask");
    }

    private static String taskPackage(
            Object task
    ) {
        if (task == null) return null;

        Object top =
                invokeNoArg(
                        task,
                        "topRunningActivity");

        if (top == null) {
            top =
                    invokeNoArg(
                            task,
                            "getTopNonFinishingActivity");
        }

        String pkg =
                objectPackage(top);

        if (pkg != null) return pkg;

        pkg =
                objectPackage(
                        task);

        if (pkg != null) return pkg;

        Object lastPaused =
                fieldValue(
                        task,
                        "mLastPausedActivity");

        pkg =
                objectPackage(
                        lastPaused);

        if (pkg != null) return pkg;

        Object realActivity =
                fieldValue(
                        task,
                        "realActivity");

        if (realActivity
                instanceof ComponentName) {
            return ((ComponentName) realActivity)
                    .getPackageName();
        }

        return null;
    }

    private static int taskId(Object task) {
        Object value =
                invokeNoArg(
                        task,
                        "getTaskId");

        if (value instanceof Number) {
            return ((Number) value)
                    .intValue();
        }

        value =
                fieldValue(
                        task,
                        "mTaskId");

        return value instanceof Number
                ? ((Number) value)
                .intValue()
                : -1;
    }

    private static String activityComponent(
            Object activityRecord
    ) {
        Object component =
                fieldValue(
                        activityRecord,
                        "mActivityComponent");

        return String.valueOf(
                component);
    }

    private static Context deriveSystemContext(
            Object task
    ) {
        Object service =
                fieldValue(
                        task,
                        "mAtmService");

        if (service == null) {
            service =
                    fieldValue(
                            task,
                            "mService");
        }

        Object context =
                fieldValue(
                        service,
                        "mUiContext");

        if (!(context
                instanceof Context)) {
            context =
                    fieldValue(
                            service,
                            "mContext");
        }

        return context
                instanceof Context
                ? (Context) context
                : null;
    }

    private static int taskInfoId(
            Object taskInfo
    ) {
        return intField(
                taskInfo,
                "taskId",
                -1);
    }

    private static String taskInfoPackage(
            Object taskInfo
    ) {
        return objectPackage(
                taskInfo);
    }

    private static String objectPackage(
            Object value
    ) {
        if (value == null) return null;

        if (value instanceof String) {
            return normalizePackage(
                    (String) value);
        }

        if (value
                instanceof ComponentName) {
            return ((ComponentName) value)
                    .getPackageName();
        }

        if (value
                instanceof Intent) {
            Intent intent =
                    (Intent) value;

            ComponentName component =
                    intent.getComponent();

            if (component != null) {
                return component.getPackageName();
            }

            return normalizePackage(
                    intent.getPackage());
        }

        if (value
                instanceof ActivityInfo) {
            return normalizePackage(
                    ((ActivityInfo) value)
                            .packageName);
        }

        for (String field :
                new String[]{
                        "packageName",
                        "mPackageName"
                }) {
            Object packageName =
                    fieldValue(
                            value,
                            field);

            if (packageName
                    instanceof String) {
                String normalized =
                        normalizePackage(
                                (String) packageName);

                if (normalized != null) {
                    return normalized;
                }
            }
        }

        for (String field :
                new String[]{
                        "mActivityComponent",
                        "topActivity",
                        "baseActivity",
                        "realActivity"
                }) {
            Object component =
                    fieldValue(
                            value,
                            field);

            if (component
                    instanceof ComponentName) {
                return ((ComponentName) component)
                        .getPackageName();
            }
        }

        for (String field :
                new String[]{
                        "baseIntent",
                        "intent",
                        "mIntent"
                }) {
            Object intent =
                    fieldValue(
                            value,
                            field);

            if (intent
                    instanceof Intent) {
                String pkg =
                        objectPackage(
                                intent);

                if (pkg != null) {
                    return pkg;
                }
            }
        }

        Object info =
                fieldValue(
                        value,
                        "topActivityInfo");

        if (info
                instanceof ActivityInfo) {
            String pkg =
                    normalizePackage(
                            ((ActivityInfo) info)
                                    .packageName);

            if (pkg != null) {
                return pkg;
            }
        }

        Object top =
                invokeNoArg(
                        value,
                        "topRunningActivity");

        if (top != null
                && top != value) {
            String pkg =
                    objectPackage(
                            top);

            if (pkg != null) {
                return pkg;
            }
        }

        top =
                invokeNoArg(
                        value,
                        "getTopNonFinishingActivity");

        if (top != null
                && top != value) {
            String pkg =
                    objectPackage(
                            top);

            if (pkg != null) {
                return pkg;
            }
        }

        return null;
    }

    private static String normalizePackage(
            String value
    ) {
        if (value == null) return null;

        String normalized =
                value.trim();

        if (normalized.isEmpty()) {
            return null;
        }

        int slash =
                normalized.indexOf('/');

        if (slash > 0) {
            normalized =
                    normalized.substring(
                            0,
                            slash);
        }

        return normalized;
    }

    private static Rect taskInfoBounds(
            Object taskInfo
    ) {
        Object config =
                fieldValue(
                        taskInfo,
                        "configuration");

        return configurationBounds(
                config);
    }

    private static Rect taskInfoMaxBounds(
            Object taskInfo
    ) {
        Object config =
                fieldValue(
                        taskInfo,
                        "configuration");

        return configurationMaxBounds(
                config);
    }

    private static Rect configurationBounds(
            Object config
    ) {
        Object wc =
                fieldValue(
                        config,
                        "windowConfiguration");

        Object value =
                invokeNoArg(
                        wc,
                        "getBounds");

        return value instanceof Rect
                ? new Rect((Rect) value)
                : new Rect();
    }

    private static Rect configurationMaxBounds(
            Object config
    ) {
        Object wc =
                fieldValue(
                        config,
                        "windowConfiguration");

        Object value =
                invokeNoArg(
                        wc,
                        "getMaxBounds");

        return value instanceof Rect
                ? new Rect((Rect) value)
                : new Rect();
    }

    private static Class<?> loadSystemClass(
            String name
    ) throws ClassNotFoundException {
        ClassLoader own =
                OplusFlexibleWindowController
                        .class
                        .getClassLoader();

        if (own != null) {
            try {
                return Class.forName(
                        name,
                        false,
                        own);
            } catch (ClassNotFoundException ignored) {
            }
        }

        return Class.forName(name);
    }

    private static Object invokeNoArg(
            Object receiver,
            String methodName
    ) {
        if (receiver == null) return null;

        Class<?> current =
                receiver.getClass();

        while (current != null) {
            for (Method method :
                    current.getDeclaredMethods()) {
                if (!methodName.equals(
                        method.getName())
                        || method.getParameterCount()
                        != 0) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    return method.invoke(
                            receiver);
                } catch (Throwable ignored) {
                    return null;
                }
            }

            current =
                    current.getSuperclass();
        }

        return null;
    }

    private static Object fieldValue(
            Object receiver,
            String fieldName
    ) {
        if (receiver == null) return null;

        Class<?> current =
                receiver.getClass();

        while (current != null) {
            try {
                Field field =
                        current.getDeclaredField(
                                fieldName);

                field.setAccessible(true);

                return field.get(
                        receiver);
            } catch (Throwable ignored) {
                current =
                        current.getSuperclass();
            }
        }

        return null;
    }

    private static boolean booleanField(
            Object receiver,
            String name,
            boolean fallback
    ) {
        Object value =
                fieldValue(
                        receiver,
                        name);

        return value
                instanceof Boolean
                ? (Boolean) value
                : fallback;
    }

    private static int intField(
            Object receiver,
            String name,
            int fallback
    ) {
        Object value =
                fieldValue(
                        receiver,
                        name);

        return value
                instanceof Number
                ? ((Number) value)
                .intValue()
                : fallback;
    }

    private void log(
            String event,
            String detail
    ) {
        if (logger != null) {
            logger.log(
                    event,
                    detail);
        }
    }

    private static final class Session {
        final int taskId;
        final String packageName;

        volatile Object taskObject;
        volatile Object activityRecord;

        volatile boolean active = true;
        volatile boolean oemReportedFlexible;
        volatile boolean edgeMinimizeRequested;
        volatile boolean edgeHung;
        volatile boolean lockKeepAlive;
        volatile boolean backgroundProtected;
        volatile boolean backgroundNotificationEligible;

        volatile long lastOplusStateElapsed;
        volatile long lastSeenElapsed =
                SystemClock.elapsedRealtime();

        Session(
                int taskId,
                String packageName
        ) {
            this.taskId = taskId;
            this.packageName = packageName;
        }
    }
}
