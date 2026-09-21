package com.yagay.MiniWindowGuard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class PlaybackControlReceiver
        extends BroadcastReceiver {
    @Override
    public void onReceive(
            Context context,
            Intent intent
    ) {
        if (context == null
                || intent == null) {
            return;
        }

        String packageName =
                intent.getStringExtra(
                        PlaybackControlContract
                                .EXTRA_PACKAGE_NAME);

        if (packageName == null
                || packageName.isBlank()) {
            return;
        }

        BackgroundPlaybackNotifier
                .handleControlAction(
                        context,
                        packageName,
                        intent.getAction());
    }
}
