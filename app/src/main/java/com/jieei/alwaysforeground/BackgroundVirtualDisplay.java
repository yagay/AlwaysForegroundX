package com.jieei.alwaysforeground;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

/**
 * Runs app-owned background Activity launches on a private virtual display so they can remain
 * resumed without stealing the user's primary display.
 *
 * The display is private and only the owning UID can place Activities on it. Frames are drained
 * and discarded; this class never exposes or stores rendered content.
 */
final class BackgroundVirtualDisplay {
    private static final String TAG = "AlwaysForeground";
    private static final int MAX_WIDTH = 720;

    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread drainThread;
    private int displayId = Display.INVALID_DISPLAY;

    synchronized int ensure(Context context) {
        if (virtualDisplay != null
                && virtualDisplay.getDisplay() != null
                && virtualDisplay.getDisplay().isValid()) {
            return displayId;
        }

        Context app = context.getApplicationContext();
        PackageManager pm = app.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) {
            return Display.INVALID_DISPLAY;
        }

        DisplayManager dm = app.getSystemService(DisplayManager.class);
        if (dm == null) return Display.INVALID_DISPLAY;

        DisplayMetrics metrics = app.getResources().getDisplayMetrics();
        int srcWidth = Math.max(1, metrics.widthPixels);
        int srcHeight = Math.max(1, metrics.heightPixels);
        float scale = Math.min(1.0f, MAX_WIDTH / (float) srcWidth);

        int width = Math.max(360, Math.round(srcWidth * scale));
        int height = Math.max(640, Math.round(srcHeight * scale));
        int density = Math.max(120, Math.round(metrics.densityDpi * scale));

        try {
            drainThread = new HandlerThread("AFX-VirtualDisplay");
            drainThread.start();
            Handler drainHandler = new Handler(drainThread.getLooper());

            imageReader = ImageReader.newInstance(
                    width,
                    height,
                    android.graphics.PixelFormat.RGBA_8888,
                    3);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                } catch (Throwable ignored) {
                } finally {
                    if (image != null) image.close();
                }
            }, drainHandler);

            virtualDisplay = dm.createVirtualDisplay(
                    "AlwaysForegroundX-" + app.getPackageName(),
                    width,
                    height,
                    density,
                    imageReader.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);

            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                release();
                return Display.INVALID_DISPLAY;
            }

            displayId = virtualDisplay.getDisplay().getDisplayId();
            Log.i(TAG, "GENERIC_VIRTUAL_DISPLAY created"
                    + " id=" + displayId
                    + " size=" + width + "x" + height
                    + " density=" + density
                    + " package=" + app.getPackageName());
            return displayId;
        } catch (Throwable t) {
            Log.w(TAG, "GENERIC_VIRTUAL_DISPLAY create failed package="
                    + app.getPackageName(), t);
            release();
            return Display.INVALID_DISPLAY;
        }
    }

    synchronized boolean owns(Activity activity) {
        if (activity == null || displayId == Display.INVALID_DISPLAY) return false;
        try {
            Display display = activity.getDisplay();
            return display != null && display.getDisplayId() == displayId;
        } catch (Throwable ignored) {
            return false;
        }
    }

    synchronized boolean owns(Context context) {
        return context instanceof Activity && owns((Activity) context);
    }

    synchronized ActivityOptions makeLaunchOptions(Context context) {
        int id = ensure(context);
        if (id == Display.INVALID_DISPLAY) return null;
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(id);
        return options;
    }

    synchronized int getDisplayId() {
        return displayId;
    }

    synchronized void release() {
        int oldId = displayId;
        displayId = Display.INVALID_DISPLAY;

        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Throwable ignored) {
        }
        virtualDisplay = null;

        try {
            if (imageReader != null) imageReader.close();
        } catch (Throwable ignored) {
        }
        imageReader = null;

        try {
            if (drainThread != null) drainThread.quitSafely();
        } catch (Throwable ignored) {
        }
        drainThread = null;

        if (oldId != Display.INVALID_DISPLAY) {
            Log.i(TAG, "GENERIC_VIRTUAL_DISPLAY released id=" + oldId);
        }
    }

    static Intent virtualizeIntent(Intent original) {
        Intent copy = new Intent(original);
        copy.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        return copy;
    }
}
