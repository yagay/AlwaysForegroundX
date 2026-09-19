package com.yagay.MiniWindowGuard;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DiagnosticsManager {
    private static final String TAG = "MiniWindowGuard";
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
                    .withZone(ZoneId.systemDefault());

    static final class ExportResult {
        final Uri uri;
        final String fileName;
        final String error;

        ExportResult(Uri uri, String fileName, String error) {
            this.uri = uri;
            this.fileName = fileName;
            this.error = error;
        }

        boolean ok() {
            return uri != null;
        }
    }

    private DiagnosticsManager() {}

    static void startSession() {
        String started = Long.toString(System.currentTimeMillis());
        GuardApp.putString(ConfigKeys.DIAGNOSTICS_STARTED_AT, started);
        GuardApp.putBoolean(ConfigKeys.DIAGNOSTICS_ACTIVE, true);

        Log.i(TAG, "DIAG_SESSION START"
                + " epochMs=" + started
                + " targets=" + GuardApp.getTargetPackages());
    }

    static void stopSession() {
        Log.i(TAG, "DIAG_SESSION STOP"
                + " epochMs=" + System.currentTimeMillis()
                + " targets=" + GuardApp.getTargetPackages());
        GuardApp.putBoolean(ConfigKeys.DIAGNOSTICS_ACTIVE, false);
    }

    static ExportResult export(Context context) {
        String stamp = FILE_TIME.format(Instant.now());
        String fileName = "MiniWindowGuard-diagnostic-" + stamp + ".zip";
        File workDir = new File(context.getCacheDir(), "diag-" + stamp);

        try {
            deleteRecursive(workDir);
            if (!workDir.mkdirs() && !workDir.isDirectory()) {
                return new ExportResult(null, fileName, "cannot-create-work-dir");
            }

            Set<String> targets = GuardApp.getTargetPackages();

            writeText(new File(workDir, "00-summary.txt"),
                    buildSummary(context, targets));

            File crash = CrashStore.getFile(context);
            if (crash != null) {
                copyFile(crash, new File(workDir, "00-last-crash.txt"));
            }

            writeText(new File(workDir, "01-root-status.txt"),
                    RootManager.capture("id", 16_384));

            writeCommand(workDir, "10-build-properties.txt",
                    "getprop", 512_000);
            writeCommand(workDir, "11-activity-activities.txt",
                    "dumpsys activity activities", 2_000_000);
            writeCommand(workDir, "12-activity-processes.txt",
                    "dumpsys activity processes", 2_000_000);
            writeCommand(workDir, "13-activity-oom.txt",
                    "dumpsys activity oom", 1_000_000);
            writeCommand(workDir, "14-activity-recents.txt",
                    "dumpsys activity recents", 1_500_000);
            writeCommand(workDir, "15-window.txt",
                    "dumpsys window", 2_500_000);
            writeCommand(workDir, "16-window-policy.txt",
                    "dumpsys window policy", 1_000_000);
            writeCommand(workDir, "17-deviceidle.txt",
                    "dumpsys deviceidle", 1_000_000);
            writeCommand(workDir, "18-netpolicy.txt",
                    "dumpsys netpolicy", 1_500_000);
            writeCommand(workDir, "19-power.txt",
                    "dumpsys power", 1_000_000);
            writeCommand(workDir, "20-audio.txt",
                    "dumpsys audio", 1_500_000);
            writeCommand(workDir, "21-media-session.txt",
                    "dumpsys media_session", 1_500_000);
            writeCommand(workDir, "22-notification.txt",
                    "dumpsys notification --noredact", 1_500_000);

            String fullLog = RootManager.capture(
                    "logcat -b all -d -v threadtime -t 30000",
                    8_000_000);
            writeText(new File(workDir, "30-logcat-full-tail.txt"), fullLog);
            writeText(new File(workDir, "31-logcat-filtered.txt"),
                    filterLogcat(fullLog, targets));

            File targetsDir = new File(workDir, "targets");
            //noinspection ResultOfMethodCallIgnored
            targetsDir.mkdirs();

            int index = 0;
            for (String pkg : targets) {
                index++;
                String prefix = String.format(Locale.US, "%02d-%s", index, safe(pkg));
                writeText(new File(targetsDir, prefix + "-package.txt"),
                        RootManager.capture(
                                "dumpsys package " + RootManager.quote(pkg),
                                1_200_000));
                writeText(new File(targetsDir, prefix + "-appops.txt"),
                        RootManager.capture(
                                "cmd appops get " + RootManager.quote(pkg),
                                512_000));
                writeText(new File(targetsDir, prefix + "-standby.txt"),
                        RootManager.capture(
                                "am get-standby-bucket " + RootManager.quote(pkg),
                                64_000));
                writeText(new File(targetsDir, prefix + "-meminfo.txt"),
                        RootManager.capture(
                                "dumpsys meminfo " + RootManager.quote(pkg),
                                512_000));
            }

            File zip = new File(context.getCacheDir(), fileName);
            if (zip.exists()) //noinspection ResultOfMethodCallIgnored
                zip.delete();
            zipDirectory(workDir, zip);

            Uri uri = publishToDownloads(context, zip, fileName);
            if (uri == null) {
                return new ExportResult(null, fileName, "MediaStore insert failed");
            }

            Log.i(TAG, "DIAG_EXPORT success"
                    + " file=" + fileName
                    + " targets=" + targets.size()
                    + " uri=" + uri);

            deleteRecursive(workDir);
            //noinspection ResultOfMethodCallIgnored
            zip.delete();

            return new ExportResult(uri, fileName, null);
        } catch (Throwable t) {
            Log.e(TAG, "DIAG_EXPORT failed", t);
            return new ExportResult(
                    null,
                    fileName,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            deleteRecursive(workDir);
        }
    }

    private static String buildSummary(Context context, Set<String> targets) {
        StringBuilder out = new StringBuilder();
        out.append("MiniWindow Guard diagnostic\n");
        out.append("generated=").append(Instant.now()).append('\n');
        out.append("package=").append(context.getPackageName()).append('\n');

        try {
            var info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            out.append("versionName=").append(info.versionName).append('\n');
            out.append("versionCode=").append(info.getLongVersionCode()).append('\n');
        } catch (Throwable ignored) {
        }

        out.append("device=").append(Build.MANUFACTURER)
                .append(' ').append(Build.MODEL).append('\n');
        out.append("android=").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        out.append("fingerprint=").append(Build.FINGERPRINT).append('\n');
        out.append("lsposedConnected=")
                .append(GuardApp.isXposedServiceConnected()).append('\n');
        out.append("framework=").append(GuardApp.getFrameworkName()).append('\n');
        out.append("expectedEngineVersionCode=")
                .append(GuardApp.getExpectedVersionCode()).append('\n');
        out.append("loadedEngineVersionCode=")
                .append(GuardApp.getLoadedEngineVersionCode()).append('\n');
        out.append("systemEngineCurrent=")
                .append(GuardApp.isSystemEngineCurrent()).append('\n');
        out.append("engineStartedAt=")
                .append(GuardApp.getEngineStartedAt()).append('\n');
        out.append("diagnosticsActive=")
                .append(GuardApp.getBoolean(ConfigKeys.DIAGNOSTICS_ACTIVE)).append('\n');
        out.append("diagnosticsStartedAt=")
                .append(GuardApp.getString(ConfigKeys.DIAGNOSTICS_STARTED_AT)).append('\n');
        out.append("targets=").append(targets).append("\n\n");

        out.append("[settings]\n");
        for (String key : booleanKeys()) {
            out.append(key).append('=')
                    .append(GuardApp.getBoolean(key)).append('\n');
        }
        out.append(ConfigKeys.CONTAINER_DEFAULT_STATE).append('=')
                .append(GuardApp.getInt(ConfigKeys.CONTAINER_DEFAULT_STATE)).append('\n');
        out.append(ConfigKeys.CONTAINER_WIDTH).append('=')
                .append(GuardApp.getInt(ConfigKeys.CONTAINER_WIDTH)).append('\n');
        out.append(ConfigKeys.CONTAINER_HEIGHT).append('=')
                .append(GuardApp.getInt(ConfigKeys.CONTAINER_HEIGHT)).append('\n');
        out.append(ConfigKeys.CONTAINER_COMMAND_PACKAGE).append('=')
                .append(GuardApp.getString(ConfigKeys.CONTAINER_COMMAND_PACKAGE)).append('\n');
        out.append(ConfigKeys.CONTAINER_COMMAND_STATE).append('=')
                .append(GuardApp.getInt(ConfigKeys.CONTAINER_COMMAND_STATE)).append('\n');
        out.append(ConfigKeys.CONTAINER_COMMAND_SEQ).append('=')
                .append(GuardApp.getInt(ConfigKeys.CONTAINER_COMMAND_SEQ)).append('\n');

        out.append("\n[files]\n");
        out.append("30-logcat-full-tail.txt: last 30000 lines from all logcat buffers\n");
        out.append("31-logcat-filtered.txt: module/TaskSurface/ActivityTaskManager/target-focused view\n");
        out.append("11-22: system state snapshots\n");
        out.append("targets/: package/appops/standby/meminfo per protected app\n");

        return out.toString();
    }

    private static List<String> booleanKeys() {
        ArrayList<String> keys = new ArrayList<>();
        keys.add(ConfigKeys.MASTER_ENABLED);
        keys.add(ConfigKeys.ROOT_KEEP_ALIVE);
        keys.add(ConfigKeys.ROOT_DOZE_WHITELIST);
        keys.add(ConfigKeys.ROOT_STANDBY_ACTIVE);
        keys.add(ConfigKeys.ROOT_BACKGROUND_APPOPS);
        keys.add(ConfigKeys.ROOT_NETWORK_WHITELIST);
        keys.add(ConfigKeys.ROOT_WAKELOCK);
        keys.add(ConfigKeys.SYSTEM_IMPORTANCE_TOP);
        keys.add(ConfigKeys.SYSTEM_HAS_RESUMED);
        keys.add(ConfigKeys.SYSTEM_KEEP_CONTAINER_RESUMED);
        keys.add(ConfigKeys.SYSTEM_KEEP_CONTAINER_VISIBLE);
        keys.add(ConfigKeys.AUTO_CONTAINER);
        keys.add(ConfigKeys.CONTAINER_ALWAYS_ON_TOP);
        return keys;
    }

    private static String filterLogcat(String full, Set<String> targets) {
        StringBuilder filtered = new StringBuilder();
        String[] keywords = {
                "MiniWindowGuard",
                "ActivityTaskManager",
                "ActivityManager",
                "WindowManager",
                "MiniWindowGuard",
                "CONTAINER",
                "TaskSurface",
                "SurfaceControl",
                "OplusHans",
                "AudioService",
                "MediaSession",
                "deviceidle",
                "netpolicy",
                "AppOps"
        };

        for (String line : full.split("\n")) {
            boolean keep = false;

            for (String keyword : keywords) {
                if (line.contains(keyword)) {
                    keep = true;
                    break;
                }
            }

            if (!keep) {
                for (String pkg : targets) {
                    if (line.contains(pkg)) {
                        keep = true;
                        break;
                    }
                }
            }

            if (keep) filtered.append(line).append('\n');
        }

        return filtered.toString();
    }

    private static void writeCommand(
            File dir,
            String fileName,
            String command,
            int maxChars
    ) throws Exception {
        String output = RootManager.capture(command, maxChars);
        writeText(new File(dir, fileName),
                "$ " + command + "\n\n" + output);
    }

    private static void writeText(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyFile(File source, File target) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static void zipDirectory(File sourceDir, File target) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(target)))) {
            addToZip(sourceDir, sourceDir, zip);
        }
    }

    private static void addToZip(File base, File file, ZipOutputStream zip) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) addToZip(base, child, zip);
            }
            return;
        }

        String name = base.toPath().relativize(file.toPath())
                .toString().replace(File.separatorChar, '/');
        zip.putNextEntry(new ZipEntry(name));

        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
    }

    private static Uri publishToDownloads(
            Context context,
            File source,
            String fileName
    ) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        values.put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/MiniWindowGuard");
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values);
        if (uri == null) return null;

        try (OutputStream out = context.getContentResolver().openOutputStream(uri);
             BufferedInputStream in = new BufferedInputStream(new FileInputStream(source))) {
            if (out == null) throw new IllegalStateException("openOutputStream returned null");
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        context.getContentResolver().update(uri, done, null, null);
        return uri;
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
