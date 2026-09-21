package com.yagay.MiniWindowGuard;

import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

/**
 * Applies Android edge-to-edge safe insets without replacing a view's
 * existing content padding.
 *
 * Android 15+ enforces edge-to-edge for modern target SDKs, so content must
 * consume status/navigation bar and display-cutout insets explicitly.
 */
final class SystemBarInsets {
    private SystemBarInsets() {}

    static void apply(View view) {
        if (view == null) {
            return;
        }

        final int baseLeft =
                view.getPaddingLeft();
        final int baseTop =
                view.getPaddingTop();
        final int baseRight =
                view.getPaddingRight();
        final int baseBottom =
                view.getPaddingBottom();

        view.setOnApplyWindowInsetsListener(
                (v, windowInsets) -> {
                    Insets safeInsets =
                            windowInsets.getInsets(
                                    WindowInsets.Type.systemBars()
                                            | WindowInsets.Type.displayCutout());

                    v.setPadding(
                            baseLeft
                                    + safeInsets.left,
                            baseTop
                                    + safeInsets.top,
                            baseRight
                                    + safeInsets.right,
                            baseBottom
                                    + safeInsets.bottom);

                    return windowInsets;
                });

        view.requestApplyInsets();
    }
}
