package com.yagay.MiniWindowGuard;

final class PlaybackControlContract {
    static final String ACTION_TOGGLE =
            "com.yagay.MiniWindowGuard.action.TOGGLE_PLAYBACK";
    static final String ACTION_PLAY =
            "com.yagay.MiniWindowGuard.action.PLAY_PLAYBACK";
    static final String ACTION_PAUSE =
            "com.yagay.MiniWindowGuard.action.PAUSE_PLAYBACK";
    static final String ACTION_PREVIOUS =
            "com.yagay.MiniWindowGuard.action.PREVIOUS_PLAYBACK";
    static final String ACTION_NEXT =
            "com.yagay.MiniWindowGuard.action.NEXT_PLAYBACK";
    static final String ACTION_DISMISS =
            "com.yagay.MiniWindowGuard.action.DISMISS_PLAYBACK_NOTIFICATION";

    static final String EXTRA_PACKAGE_NAME =
            "package_name";

    static final String PERMISSION_CONTROL_PLAYBACK =
            "com.yagay.MiniWindowGuard.permission.CONTROL_PLAYBACK";

    private PlaybackControlContract() {}
}
