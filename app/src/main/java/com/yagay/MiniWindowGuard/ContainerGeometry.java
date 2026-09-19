package com.yagay.MiniWindowGuard;

import android.content.res.Resources;
import android.graphics.Rect;
import android.util.DisplayMetrics;

final class ContainerGeometry {
    private ContainerGeometry() {}

    static Rect visibleBounds(Resources resources, int widthPercent, int heightPercent) {
        DisplayMetrics dm = resources.getDisplayMetrics();
        int screenW = Math.max(1, dm.widthPixels);
        int screenH = Math.max(1, dm.heightPixels);
        float density = Math.max(1f, dm.density);

        int width = Math.round(screenW * widthPercent / 100f);
        int height = Math.round(screenH * heightPercent / 100f);

        width = Math.min(screenW, Math.max(Math.round(320 * density), width));
        height = Math.min(screenH, Math.max(Math.round(420 * density), height));

        int margin = Math.round(18 * density);
        int headerReserve = Math.round(96 * density);

        int left = Math.max(0, screenW - width - margin);
        int top = Math.min(
                Math.max(margin, headerReserve),
                Math.max(0, screenH - height - margin));

        return new Rect(left, top, left + width, top + height);
    }

    static Rect offscreenBounds(Resources resources, Rect visible) {
        DisplayMetrics dm = resources.getDisplayMetrics();
        int gap = Math.max(64, Math.round(32 * Math.max(1f, dm.density)));
        int left = dm.widthPixels + gap;
        return new Rect(
                left,
                visible.top,
                left + visible.width(),
                visible.top + visible.height());
    }

    static int dp(Resources resources, float value) {
        return Math.round(value * resources.getDisplayMetrics().density);
    }
}
