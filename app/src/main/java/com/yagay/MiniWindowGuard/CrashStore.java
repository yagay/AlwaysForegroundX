package com.yagay.MiniWindowGuard;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

final class CrashStore {
    private static final String FILE_NAME = "last-crash.txt";

    private CrashStore() {}

    static void record(Context context, String source, Throwable throwable) {
        if (context == null || throwable == null) return;

        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            StringBuilder out = new StringBuilder();
            out.append("time=").append(Instant.now()).append('\n');
            out.append("source=").append(source).append('\n');
            out.append("thread=").append(Thread.currentThread().getName()).append('\n');
            out.append("exception=")
                    .append(throwable.getClass().getName())
                    .append(": ")
                    .append(String.valueOf(throwable.getMessage()))
                    .append("\n\n");

            for (StackTraceElement frame : throwable.getStackTrace()) {
                out.append("at ").append(frame).append('\n');
            }

            Throwable cause = throwable.getCause();
            int depth = 0;
            while (cause != null && cause != throwable && depth++ < 8) {
                out.append("\nCaused by: ")
                        .append(cause.getClass().getName())
                        .append(": ")
                        .append(String.valueOf(cause.getMessage()))
                        .append('\n');
                for (StackTraceElement frame : cause.getStackTrace()) {
                    out.append("at ").append(frame).append('\n');
                }
                cause = cause.getCause();
            }

            try (FileOutputStream stream = new FileOutputStream(file, false)) {
                stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    static File getFile(Context context) {
        if (context == null) return null;
        File file = new File(context.getFilesDir(), FILE_NAME);
        return file.isFile() ? file : null;
    }
}
