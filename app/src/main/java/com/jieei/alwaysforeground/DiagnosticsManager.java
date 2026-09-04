package com.jieei.alwaysforeground;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DiagnosticsManager {
    private static final String PREFS = "diagnostics";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_START_MS = "start_ms";
    private static final String KEY_TARGET = "target";
    private static final String DEFAULT_TARGET = "com.phoenix.read";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    interface Callback {
        void onComplete(ExportResult result);
    }

    static final class ExportResult {
        final boolean success;
        final String message;

        ExportResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private DiagnosticsManager() {}

    static boolean isActive(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false);
    }

    static String getTarget(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TARGET, DEFAULT_TARGET);
    }

    static long getStartMs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_START_MS, 0L);
    }

    static void start(Context context, String targetPackage) {
        String target = targetPackage == null ? "" : targetPackage.trim();
        if (target.isEmpty()) target = DEFAULT_TARGET;
        long startMs = System.currentTimeMillis();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_START_MS, startMs)
                .putString(KEY_TARGET, target)
                .apply();
        AlwaysForegroundApp.setDiagnosticsState(true, target);
    }

    static void stopAndExport(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            ExportResult result;
            String target = getTarget(app);
            try {
                result = export(app);
            } catch (Throwable t) {
                result = new ExportResult(false, "导出失败: " + t);
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ACTIVE, false).apply();
            AlwaysForegroundApp.setDiagnosticsState(false, target);
            callback.onComplete(result);
        });
    }

    private static ExportResult export(Context context) throws Exception {
        long startMs = getStartMs(context);
        if (startMs <= 0L) startMs = System.currentTimeMillis() - 10 * 60_000L;
        long endMs = System.currentTimeMillis();
        String target = getTarget(context);
        boolean root = hasRoot();

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(endMs));
        File work = new File(context.getCacheDir(), "diag-" + stamp);
        deleteRecursively(work);
        if (!work.mkdirs() && !work.isDirectory()) {
            throw new IOException("cannot create diagnostics work directory");
        }

        String since = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(startMs));
        String rawLogcat = runShell(root,
                "logcat -d -v threadtime -T '" + since + "' 2>&1");
        String eventLog = runShell(root,
                "logcat -b events -d -v threadtime -T '" + since + "' 2>&1");

        String moduleLog = filter(rawLogcat,
                "AlwaysForeground", "libxposed", "LSPosed", "Xposed");
        String filteredLogcat = filter(rawLogcat,
                target,
                "AlwaysForeground",
                "ActivityTaskManager",
                "ActivityManager",
                "WindowManager",
                "AndroidRuntime",
                "ProcessLifecycleOwner",
                "FragmentManager",
                "SurfaceView",
                "TextureView",
                "MediaPlayer",
                "AudioTrack",
                "TTVideoEngine",
                "ExoPlayer");
        String systemEvents = filter(eventLog,
                target,
                "wm_pause_activity",
                "wm_resume_activity",
                "wm_stop_activity",
                "wm_destroy_activity",
                "wm_on_paused_called",
                "wm_on_stop_called",
                "am_proc_start",
                "am_proc_died",
                "am_kill",
                "am_uid_stopped",
                "am_uid_active",
                "am_uid_idle");

        writeText(new File(work, "module.log"), moduleLog);
        writeText(new File(work, "logcat.txt"), filteredLogcat);
        writeText(new File(work, "system-events.txt"), systemEvents);
        writeText(new File(work, "environment.txt"),
                buildEnvironment(context, target, root, startMs, endMs));

        boolean lsposedCopied = root && copyLsposedLogs(work);
        String hookCandidates = extractHookCandidates(rawLogcat);
        if (hookCandidates.isEmpty()) {
            hookCandidates = "No playback endpoint events were captured in this diagnostic session.\n"
                    + "Make sure the diagnostic session is active, the target package is correct, "
                    + "and reproduce the exact moment playback stops before exporting.\n";
        }
        writeText(new File(work, "hook-candidates.txt"), hookCandidates);
        writeText(new File(work, "summary.txt"),
                buildSummary(target, root, lsposedCopied, startMs, endMs));

        File zipFile = new File(context.getCacheDir(),
                "AlwaysForegroundX-diagnostic-" + stamp + ".zip");
        if (zipFile.exists()) zipFile.delete();
        zipDirectory(work, zipFile);
        String displayPath = saveToDownloads(context, zipFile);
        deleteRecursively(work);
        zipFile.delete();
        return new ExportResult(true, "已导出到 " + displayPath);
    }

    private static String extractHookCandidates(String rawLogcat) {
        if (rawLogcat == null || rawLogcat.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String line : rawLogcat.split("\\r?\\n")) {
            if (line.contains("HOOK_CANDIDATE")
                    || line.contains("HOOK_SUGGEST")
                    || line.contains("HOOK_STACK")
                    || line.contains("HOOK_TRACE_FAILED")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static boolean hasRoot() {
        try {
            Process process = new ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true).start();
            String output = readAll(process.getInputStream());
            int code = process.waitFor();
            return code == 0 && output.contains("uid=0");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String runShell(boolean root, String command) {
        try {
            Process process = root
                    ? new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                    : new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            String output = readAll(process.getInputStream());
            process.waitFor();
            return output;
        } catch (Throwable t) {
            return "COMMAND FAILED: " + command + "\n" + t + "\n";
        }
    }

    private static boolean copyLsposedLogs(File work) {
        File out = new File(work, "lsposed");
        out.mkdirs();
        String outPath = shellQuote(out.getAbsolutePath());
        String command = "for d in /data/adb/lspd/log /data/adb/lsposed/log "
                + "/data/adb/modules/zygisk_lsposed/log /data/adb/modules/LSPosed/log; do "
                + "if [ -d \"$d\" ]; then n=$(echo \"$d\" | tr '/' '_'); "
                + "mkdir -p " + outPath + "/$n; cp -a \"$d\"/. "
                + outPath + "/$n/ 2>/dev/null; fi; done; "
                + "chmod -R a+rX " + outPath + " 2>/dev/null; "
                + "find " + outPath + " -type f 2>/dev/null | head -1";
        return !runShell(true, command).trim().isEmpty();
    }

    private static String buildEnvironment(Context context, String target, boolean root,
                                           long startMs, long endMs) {
        StringBuilder s = new StringBuilder();
        s.append("diagnosticStart=").append(formatFull(startMs)).append('\n');
        s.append("diagnosticEnd=").append(formatFull(endMs)).append('\n');
        s.append("device=").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        s.append("product=").append(Build.PRODUCT).append('\n');
        s.append("android=").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        s.append("fingerprint=").append(Build.FINGERPRINT).append('\n');
        s.append("rootAvailable=").append(root).append('\n');
        s.append("modulePackage=").append(context.getPackageName()).append('\n');
        appendPackageInfo(context, context.getPackageName(), "module", s);
        s.append("libxposedApi=102.0.0\n");
        s.append("automaticHookPointLocator=true\n");
        s.append("hookCandidatesSource=current-session-logcat-only\n");
        s.append("targetPackage=").append(target).append('\n');
        appendPackageInfo(context, target, "target", s);
        s.append("mode=").append(AlwaysForegroundApp.getConfiguredMode()).append('\n');
        s.append("xposedServiceConnected=")
                .append(AlwaysForegroundApp.isServiceConnected()).append('\n');
        return s.toString();
    }

    private static void appendPackageInfo(Context context, String packageName, String prefix,
                                          StringBuilder s) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            s.append(prefix).append("VersionName=").append(info.versionName).append('\n');
            long code = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            s.append(prefix).append("VersionCode=").append(code).append('\n');
        } catch (PackageManager.NameNotFoundException e) {
            s.append(prefix).append("PackageInfo=not installed\n");
        }
    }

    private static String buildSummary(String target, boolean root, boolean lsposedCopied,
                                       long startMs, long endMs) {
        return "AlwaysForegroundX diagnostic session\n"
                + "Target: " + target + "\n"
                + "Start: " + formatFull(startMs) + "\n"
                + "End: " + formatFull(endMs) + "\n"
                + "DurationMs: " + (endMs - startMs) + "\n"
                + "Root: " + root + "\n"
                + "LSPosed logs copied: " + lsposedCopied + "\n"
                + "Automatic hook-point locator: enabled\n"
                + "Hook candidates: current diagnostic session only\n\n"
                + "Files:\n"
                + "- hook-candidates.txt: current-session playback endpoint events only\n"
                + "- module.log: AlwaysForeground/libxposed/LSPosed related logcat\n"
                + "- logcat.txt: target + lifecycle/window/runtime/player related logcat\n"
                + "- system-events.txt: ActivityManager/WindowManager event buffer entries\n"
                + "- environment.txt: device/module/target environment\n"
                + "- lsposed/: raw readable LSPosed logs (may contain historical sessions)\n";
    }

    private static String filter(String source, String... terms) {
        StringBuilder out = new StringBuilder();
        if (source == null || source.isEmpty()) return "";
        for (String line : source.split("\\r?\\n")) {
            for (String term : terms) {
                if (term != null && !term.isEmpty() && line.contains(term)) {
                    out.append(line).append('\n');
                    break;
                }
            }
        }
        return out.toString();
    }

    private static void writeText(File file, String value) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(file, StandardCharsets.UTF_8))) {
            writer.write(value == null ? "" : value);
        }
    }

    private static void zipDirectory(File sourceDir, File output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            zipChildren(sourceDir, sourceDir, zip);
        }
    }

    private static void zipChildren(File root, File current, ZipOutputStream zip)
            throws IOException {
        File[] files = current.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[32 * 1024];
        for (File file : files) {
            String name = root.toPath().relativize(file.toPath()).toString()
                    .replace(File.separatorChar, '/');
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children == null || children.length == 0) {
                    zip.putNextEntry(new ZipEntry(name + "/"));
                    zip.closeEntry();
                } else {
                    zipChildren(root, file, zip);
                }
            } else {
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream in = new FileInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) >= 0) zip.write(buffer, 0, read);
                }
                zip.closeEntry();
            }
        }
    }

    private static String saveToDownloads(Context context, File zipFile) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, zipFile.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/AlwaysForegroundX");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore insert failed");

        try {
            try (OutputStream out = resolver.openOutputStream(uri);
                 InputStream in = new FileInputStream(zipFile)) {
                if (out == null) throw new IOException("MediaStore output stream unavailable");
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        } catch (Throwable t) {
            resolver.delete(uri, null, null);
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException(t);
        }
        return "Download/AlwaysForegroundX/" + zipFile.getName();
    }

    private static String readAll(InputStream input) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String formatFull(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
                .format(new Date(time));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }
}
