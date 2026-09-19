package com.yagay.MiniWindowGuard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class SystemEventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !TaskSurfaceController.ACTION_TASK_CAPTURED.equals(intent.getAction())) {
            return;
        }

        String pkg = intent.getStringExtra(TaskSurfaceController.EXTRA_PACKAGE);
        int state = intent.getIntExtra(
                TaskSurfaceController.EXTRA_STATE,
                ConfigKeys.STATE_WINDOW);
        int taskId = intent.getIntExtra(TaskSurfaceController.EXTRA_TASK_ID, -1);

        Intent service = new Intent(context, ContainerOverlayService.class);
        service.setAction(ContainerOverlayService.ACTION_SYNC);
        service.putExtra(ContainerOverlayService.EXTRA_PACKAGE, pkg);
        service.putExtra(ContainerOverlayService.EXTRA_STATE, state);
        service.putExtra(ContainerOverlayService.EXTRA_TASK_ID, taskId);

        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
