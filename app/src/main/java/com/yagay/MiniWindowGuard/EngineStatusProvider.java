package com.yagay.MiniWindowGuard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

public final class EngineStatusProvider extends ContentProvider {
    static final String AUTHORITY = "com.yagay.MiniWindowGuard.engine_status";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    static final String METHOD_MARK = "markEngine";

    private static final String PREFS = "engine_status";
    static final String KEY_VERSION = "version";
    static final String KEY_STARTED_AT = "started_at";
    static final String KEY_PID = "pid";
    static final String KEY_HOOKS = "hooks";

    static final class Status {
        final long versionCode;
        final long startedAt;
        final int pid;
        final int hookCount;

        Status(long versionCode, long startedAt, int pid, int hookCount) {
            this.versionCode = versionCode;
            this.startedAt = startedAt;
            this.pid = pid;
            this.hookCount = hookCount;
        }

        boolean isFromCurrentBoot() {
            if (startedAt <= 0) return false;
            long bootEpoch = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            return startedAt >= bootEpoch - 10_000L;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) return null;

        int caller = Binder.getCallingUid();
        int ownUid = context.getApplicationInfo().uid;
        if (caller != Process.SYSTEM_UID && caller != ownUid) {
            return null;
        }

        if (METHOD_MARK.equals(method)) {
            long version = extras == null ? -1L : extras.getLong(KEY_VERSION, -1L);
            int pid = extras == null ? -1 : extras.getInt(KEY_PID, -1);
            int hooks = extras == null ? -1 : extras.getInt(KEY_HOOKS, -1);
            long now = System.currentTimeMillis();

            prefs(context).edit()
                    .putLong(KEY_VERSION, version)
                    .putLong(KEY_STARTED_AT, now)
                    .putInt(KEY_PID, pid)
                    .putInt(KEY_HOOKS, hooks)
                    .commit();

            Bundle result = new Bundle();
            result.putBoolean("ok", true);
            result.putLong(KEY_STARTED_AT, now);
            return result;
        }

        return super.call(method, arg, extras);
    }

    static Status read(Context context) {
        if (context == null) return new Status(-1L, 0L, -1, -1);
        SharedPreferences p = prefs(context);
        return new Status(
                p.getLong(KEY_VERSION, -1L),
                p.getLong(KEY_STARTED_AT, 0L),
                p.getInt(KEY_PID, -1),
                p.getInt(KEY_HOOKS, -1));
    }

    private static SharedPreferences prefs(Context context) {
        Context dp = context.createDeviceProtectedStorageContext();
        return dp.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) { return 0; }
}
