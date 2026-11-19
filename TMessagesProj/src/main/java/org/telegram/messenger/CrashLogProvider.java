package org.telegram.messenger;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

/**
 * SALFETGRAM diagnostics.
 *
 * Content providers are created before Application.onCreate(), so this is the
 * earliest Java code that can run in our process. It installs the crash
 * handler and opens the boot log, which makes it possible to see how far
 * startup got even if the app dies later.
 */
public class CrashLogProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        try {
            CrashLogWriter.install(getContext());
            CrashLogWriter.log(getContext(),
                    "provider onCreate: pid=" + android.os.Process.myPid()
                            + " model=" + Build.MANUFACTURER + " " + Build.MODEL
                            + " sdk=" + Build.VERSION.SDK_INT
                            + " pkg=" + getContext().getPackageName());
        } catch (Throwable ignore) {
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}