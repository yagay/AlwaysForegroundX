package com.yagay.MiniWindowGuard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;

final class BackgroundPlaybackNotifier {
    private static final String CHANNEL_ID =
            "background_playback_protection";
    private static final int NOTIFICATION_ID = 5201;

    private BackgroundPlaybackNotifier() {}

    static void show(
            Context context,
            String packageName
    ) {
        if (context == null
                || packageName == null
                || packageName.isBlank()) {
            return;
        }

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
                                    R.drawable.ic_background_playback)
                            .setContentTitle(
                                    label + " 正在后台播放")
                            .setContentText(
                                    "后台播放保护已启用 · 点击返回应用")
                            .setCategory(
                                    Notification.CATEGORY_SERVICE)
                            .setVisibility(
                                    Notification.VISIBILITY_PUBLIC)
                            .setOnlyAlertOnce(true)
                            .setOngoing(true)
                            .setAutoCancel(false)
                            .setShowWhen(false);

            if (targetIcon != null) {
                builder.setLargeIcon(
                        targetIcon);
            }

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
                "受保护应用在普通后台继续播放时显示");
        channel.setShowBadge(false);

        manager.createNotificationChannel(
                channel);
    }

    private static String notificationTag(
            String packageName
    ) {
        return "background-playback:"
                + packageName;
    }
}
