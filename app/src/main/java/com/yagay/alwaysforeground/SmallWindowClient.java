package com.yagay.alwaysforeground;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

final class SmallWindowClient {
    private static final String TAG = "MiniWindowGuard";
    private static final String COMPANION_PACKAGE = "com.yagay.alwaysforeground";

    private SmallWindowClient() {}

    static boolean route(Context context, Intent original) {
        if (!GuardConfig.bool(ConfigKeys.AUTO_SMALL_WINDOW)) return false;

        String token = GuardConfig.string(ConfigKeys.ROOT_BRIDGE_TOKEN);
        if (token.isEmpty()) return false;

        try {
            String uri = original.toUri(Intent.URI_INTENT_SCHEME);
            if (uri.length() > 32000) return false;

            Intent request = new Intent(RootBridgeReceiver.ACTION);
            request.setClassName(
                    COMPANION_PACKAGE,
                    "com.yagay.alwaysforeground.RootBridgeReceiver");
            request.putExtra(RootBridgeReceiver.EXTRA_TOKEN, token);
            request.putExtra(RootBridgeReceiver.EXTRA_OP, RootBridgeReceiver.OP_WINDOW);
            request.putExtra(RootBridgeReceiver.EXTRA_PACKAGE, context.getPackageName());
            request.putExtra(RootBridgeReceiver.EXTRA_UID, context.getApplicationInfo().uid);
            request.putExtra(RootBridgeReceiver.EXTRA_INTENT_URI, uri);
            request.putExtra(
                    RootBridgeReceiver.EXTRA_USER_ID,
                    Math.max(0, context.getApplicationInfo().uid / 100000));
            request.putExtra(RootBridgeReceiver.EXTRA_FORM, GuardConfig.windowForm());
            request.putExtra(RootBridgeReceiver.EXTRA_WIDTH, GuardConfig.windowWidth());
            request.putExtra(RootBridgeReceiver.EXTRA_HEIGHT, GuardConfig.windowHeight());
            request.putExtra(
                    RootBridgeReceiver.EXTRA_FREEFORM,
                    GuardConfig.bool(ConfigKeys.AOSP_FREEFORM_FALLBACK));

            context.sendBroadcast(request);
            Log.i(TAG, "SMALL_WINDOW routed package=" + context.getPackageName()
                    + " form=" + GuardConfig.windowForm()
                    + " size=" + GuardConfig.windowWidth()
                    + "x" + GuardConfig.windowHeight());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "SMALL_WINDOW route failed package=" + context.getPackageName(), t);
            return false;
        }
    }
}
