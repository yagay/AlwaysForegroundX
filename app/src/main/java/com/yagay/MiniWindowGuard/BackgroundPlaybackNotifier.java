package com.yagay.MiniWindowGuard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;

final class BackgroundPlaybackNotifier {
    private static final String CHANNEL_ID =
            "background_playback_protection";
    private static final int NOTIFICATION_ID = 5201;
    private static final String STATE_PREFS =
            "background_playback_notification_state";
    private static final String STATE_PREFIX =
            "playing:";

    private BackgroundPlaybackNotifier() {}

    static void update(
            Context context,
            String packageName,
            boolean active,
            String reason
    ) {
        if (context == null
                || packageName == null
                || packageName.isBlank()) {
            return;
        }

        if (active) {
            setPlayingState(
                    context,
                    packageName,
                    true);
            show(
                    context,
                    packageName,
                    true);
            return;
        }

        if (isPausedByNotification(
                context,
                packageName)
                && isAudioOnlyInactiveReason(
                reason)) {
            show(
                    context,
                    packageName,
                    false);
            return;
        }

        clearState(
                context,
                packageName);
        cancel(
                context,
                packageName);
    }

    static void handleControlAction(
            Context context,
            String packageName,
            String action
    ) {
        if (context == null
                || packageName == null
                || packageName.isBlank()
                || action == null) {
            return;
        }

        if (PlaybackControlContract
                .ACTION_DISMISS
                .equals(action)) {
            clearState(
                    context,
                    packageName);
            cancel(
                    context,
                    packageName);
            return;
        }

        String targetAction = action;

        if (PlaybackControlContract
                .ACTION_TOGGLE
                .equals(action)) {
            boolean playing =
                    isPlaying(
                            context,
                            packageName);

            boolean nextPlaying =
                    !playing;

            setPlayingState(
                    context,
                    packageName,
                    nextPlaying);

            show(
                    context,
                    packageName,
                    nextPlaying);

            targetAction =
                    nextPlaying
                            ? PlaybackControlContract
                            .ACTION_PLAY
                            : PlaybackControlContract
                            .ACTION_PAUSE;
        }

        sendTargetCommand(
                context,
                packageName,
                targetAction);
    }

    static void show(
            Context context,
            String packageName
    ) {
        setPlayingState(
                context,
                packageName,
                true);
        show(
                context,
                packageName,
                true);
    }

    private static void show(
            Context context,
            String packageName,
            boolean playing
    ) {
        try {
            NotificationManager manager =
                    context.getSystemService(
                            NotificationManager.class);

            if (manager == null) {
                return;
            }

            ensureChannel(manager);

            PackageManager pm =
                    context.getPackageManager();

            String label = packageName;
            Icon targetIcon = null;

            try {
                ApplicationInfo info =
                        pm.getApplicationInfo(
                                packageName,
                                0);

                CharSequence cs =
                        pm.getApplicationLabel(
                                info);

                if (cs != null
                        && !cs.toString()
                        .isBlank()) {
                    label = cs.toString();
                }

                if (info.icon != 0) {
                    targetIcon =
                            Icon.createWithResource(
                                    packageName,
                                    info.icon);
                }
            } catch (Throwable ignored) {
            }

            Notification.Builder builder =
                    new Notification.Builder(
                            context,
                            CHANNEL_ID)
                            .setSmallIcon(
                                    playing
                                            ? R.drawable
                                            .ic_background_playback
                                            : R.drawable
                                            .ic_notification_pause)
                            .setContentTitle(
                                    label
                                            + (playing
                                            ? " 正在后台播放"
                                            : " 已暂停"))
                            .setContentText(
                                    playing
                                            ? "后台播放保护已启用"
                                            : "已暂停 · 可继续播放或滑动清除")
                            .setCategory(
                                    Notification.CATEGORY_TRANSPORT)
                            .setVisibility(
                                    Notification.VISIBILITY_PUBLIC)
                            .setOnlyAlertOnce(true)
                            .setOngoing(
                                    playing)
                            .setAutoCancel(false)
                            .setShowWhen(false);

            if (targetIcon != null) {
                builder.setLargeIcon(
                        targetIcon);
            }

            builder.addAction(
                    action(
                            context,
                            packageName,
                            PlaybackControlContract
                                    .ACTION_PREVIOUS,
                            R.drawable
                                    .ic_notification_previous,
                            "上一曲"));

            builder.addAction(
                    action(
                            context,
                            packageName,
                            PlaybackControlContract
                                    .ACTION_TOGGLE,
                            playing
                                    ? R.drawable
                                    .ic_background_playback
                                    : R.drawable
                                    .ic_notification_pause,
                            playing
                                    ? "正在播放"
                                    : "已暂停"));

            builder.addAction(
                    action(
                            context,
                            packageName,
                            PlaybackControlContract
                                    .ACTION_NEXT,
                            R.drawable
                                    .ic_notification_next,
                            "下一曲"));

            builder.setStyle(
                    new Notification.MediaStyle()
                            .setShowActionsInCompactView(
                                    0,
                                    1,
                                    2));

            Intent launch =
                    pm.getLaunchIntentForPackage(
                            packageName);

            if (launch != null) {
                launch.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

                PendingIntent pending =
                        PendingIntent.getActivity(
                                context,
                                packageName.hashCode(),
                                launch,
                                PendingIntent.FLAG_UPDATE_CURRENT
                                        | PendingIntent.FLAG_IMMUTABLE);

                builder.setContentIntent(
                        pending);
            }

            Intent dismissIntent =
                    new Intent(
                            context,
                            PlaybackControlReceiver.class)
                            .setAction(
                                    PlaybackControlContract
                                            .ACTION_DISMISS)
                            .putExtra(
                                    PlaybackControlContract
                                            .EXTRA_PACKAGE_NAME,
                                    packageName);

            builder.setDeleteIntent(
                    PendingIntent.getBroadcast(
                            context,
                            requestCode(
                                    packageName,
                                    PlaybackControlContract
                                            .ACTION_DISMISS),
                            dismissIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT
                                    | PendingIntent.FLAG_IMMUTABLE));

            manager.notify(
                    notificationTag(packageName),
                    NOTIFICATION_ID,
                    builder.build());
        } catch (Throwable ignored) {
        }
    }

    static void hide(
            Context context,
            String packageName
    ) {
        if (context == null
                || packageName == null
                || packageName.isBlank()) {
            return;
        }

        clearState(
                context,
                packageName);
        cancel(
                context,
                packageName);
    }

    private static Notification.Action action(
            Context context,
            String packageName,
            String action,
            int iconRes,
            String title
    ) {
        Intent intent =
                new Intent(
                        context,
                        PlaybackControlReceiver.class)
                        .setAction(action)
                        .putExtra(
                                PlaybackControlContract
                                        .EXTRA_PACKAGE_NAME,
                                packageName);

        PendingIntent pending =
                PendingIntent.getBroadcast(
                        context,
                        requestCode(
                                packageName,
                                action),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Action.Builder(
                Icon.createWithResource(
                        context,
                        iconRes),
                title,
                pending)
                .build();
    }

    private static void sendTargetCommand(
            Context context,
            String packageName,
            String action
    ) {
        if (!PlaybackControlContract
                .ACTION_PLAY
                .equals(action)
                && !PlaybackControlContract
                .ACTION_PAUSE
                .equals(action)
                && !PlaybackControlContract
                .ACTION_PREVIOUS
                .equals(action)
                && !PlaybackControlContract
                .ACTION_NEXT
                .equals(action)) {
            return;
        }

        try {
            Intent command =
                    new Intent(action)
                            .setPackage(
                                    packageName)
                            .putExtra(
                                    PlaybackControlContract
                                            .EXTRA_PACKAGE_NAME,
                                    packageName);

            context.sendBroadcast(
                    command,
                    PlaybackControlContract
                            .PERMISSION_CONTROL_PLAYBACK);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isPlaying(
            Context context,
            String packageName
    ) {
        return statePrefs(context)
                .getBoolean(
                        STATE_PREFIX
                                + packageName,
                        true);
    }

    private static boolean isPausedByNotification(
            Context context,
            String packageName
    ) {
        SharedPreferences prefs =
                statePrefs(context);

        return prefs.contains(
                STATE_PREFIX
                        + packageName)
                && !prefs.getBoolean(
                STATE_PREFIX
                        + packageName,
                true);
    }

    private static void setPlayingState(
            Context context,
            String packageName,
            boolean playing
    ) {
        statePrefs(context)
                .edit()
                .putBoolean(
                        STATE_PREFIX
                                + packageName,
                        playing)
                .apply();
    }

    private static void clearState(
            Context context,
            String packageName
    ) {
        statePrefs(context)
                .edit()
                .remove(
                        STATE_PREFIX
                                + packageName)
                .apply();
    }

    private static SharedPreferences statePrefs(
            Context context
    ) {
        Context dp =
                context.createDeviceProtectedStorageContext();

        return dp.getSharedPreferences(
                STATE_PREFS,
                Context.MODE_PRIVATE);
    }

    private static boolean isAudioOnlyInactiveReason(
            String reason
    ) {
        return reason != null
                && reason.contains(
                "audio");
    }

    private static void cancel(
            Context context,
            String packageName
    ) {
        try {
            NotificationManager manager =
                    context.getSystemService(
                            NotificationManager.class);

            if (manager != null) {
                manager.cancel(
                        notificationTag(packageName),
                        NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureChannel(
            NotificationManager manager
    ) {
        NotificationChannel existing =
                manager.getNotificationChannel(
                        CHANNEL_ID);

        if (existing != null) {
            return;
        }

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        "后台播放保护",
                        NotificationManager.IMPORTANCE_LOW);

        channel.setDescription(
                "受保护应用在普通后台播放或暂停时显示");
        channel.setShowBadge(false);

        manager.createNotificationChannel(
                channel);
    }

    private static int requestCode(
            String packageName,
            String action
    ) {
        return 31
                * packageName.hashCode()
                + action.hashCode();
    }

    private static String notificationTag(
            String packageName
    ) {
        return "background-playback:"
                + packageName;
    }
}
