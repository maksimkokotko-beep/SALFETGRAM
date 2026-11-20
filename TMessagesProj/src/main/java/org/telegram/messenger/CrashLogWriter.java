package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * SALFETGRAM diagnostics.
 *
 * Writes a boot log and crash traces into Download/SalfetGram, keeps the last
 * lines on screen (diag overlay) and mirrors everything to a remote channel,
 * so the log can be read even when the public folder is not writable.
 */
public final class CrashLogWriter {

    private static final String TAG = "SalfetGram";
    private static final String PUBLIC_DIR = "Download/SalfetGram";
    private static final String PUSH_URL = "https://ntfy.sh/salfetgram-diag-7f3k9q";
    private static final int PUSH_LIMIT = 400;

    private static final StringBuilder BUF = new StringBuilder();
    private static final List<String> LINES = new ArrayList<>();
    private static Context appCtx;
    private static String stamp;
    private static Uri bootUri;
    private static boolean installed;
    private static int pushed;

    private static volatile TextView ui;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CrashLogWriter() {
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String startStamp() {
        if (stamp == null) {
            stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        }
        return stamp;
    }

    /** on-screen overlay: last lines are always visible without any file or network */
    public static void setUi(TextView v) {
        ui = v;
        renderUi();
    }

    private static void renderUi() {
        final TextView v = ui;
        if (v == null) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        int from = Math.max(0, LINES.size() - 6);
        for (int i = from; i < LINES.size(); i++) {
            sb.append(LINES.get(i)).append('\n');
        }
        MAIN.post(() -> {
            try {
                v.setText(sb.toString());
            } catch (Throwable ignore) {
            }
        });
    }

    private static void push(final String what) {
        if (pushed >= PUSH_LIMIT) {
            return;
        }
        pushed++;
        final String body = what.length() > 3500 ? what.substring(0, 3500) : what;
        Thread t = new Thread(() -> {
            HttpURLConnection c = null;
            try {
                byte[] out = body.getBytes("UTF-8");
                c = (HttpURLConnection) new URL(PUSH_URL).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(out.length);
                OutputStream os = c.getOutputStream();
                os.write(out);
                os.flush();
                os.close();
                c.getResponseCode();
            } catch (Throwable ignore) {
            } finally {
                if (c != null) {
                    try {
                        c.disconnect();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }, "sg-diag");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable ignore) {
        }
    }

    private static Uri createPublic(Context ctx, String name) {
        if (Build.VERSION.SDK_INT < 29) {
            return null;
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_DIR);
            return ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        } catch (Throwable t) {
            Log.w(TAG, "createPublic failed: " + t);
            return null;
        }
    }

    private static void flush(Context ctx) {
        final String text = BUF.toString();
        // 1) public Download/SalfetGram/boot-*.txt
        try {
            if (bootUri == null) {
                bootUri = createPublic(ctx, "boot-" + startStamp() + ".txt");
            }
            if (bootUri != null) {
                OutputStream os = ctx.getContentResolver().openOutputStream(bootUri);
                if (os != null) {
                    os.write(text.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                }
            }
        } catch (Throwable ignore) {
        }
        // 2) app private folder (bonus, readable via adb)
        try {
            File dir = new File(ctx.getExternalFilesDir(null), "logs");
            dir.mkdirs();
            FileOutputStream fos = new FileOutputStream(new File(dir, "boot-" + startStamp() + ".txt"), false);
            fos.write(text.getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignore) {
        }
    }

    public static synchronized void log(String line) {
        Context ctx = appCtx;
        String rec = now() + "  " + line;
        BUF.append(rec).append('\n');
        LINES.add(rec);
        if (LINES.size() > 40) {
            LINES.remove(0);
        }
        Log.d(TAG, line);
        renderUi();
        push(rec);
        if (ctx != null) {
            flush(ctx);
        }
    }

    public static synchronized void log(Context ctx, String line) {
        if (ctx != null) {
            appCtx = ctx;
        }
        log(line);
    }

    private static String traceOf(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    public static synchronized void report(Throwable e, String where) {
        Context ctx = appCtx;
        String text = "=== SALFETGRAM CRASH ===\n"
                + "when: " + where + "\n"
                + "time: " + startStamp() + "\n"
                + "device: " + Build.MANUFACTURER + " " + Build.MODEL + " / SDK " + Build.VERSION.SDK_INT + "\n"
                + "package: " + (ctx != null ? ctx.getPackageName() : "?") + "\n"
                + "thread: " + Thread.currentThread().getName() + "\n\n"
                + traceOf(e);

        BUF.append("\n").append(text).append('\n');
        push(text);

        if (ctx != null) {
            // dedicated crash file, newest one wins: also readable on Android < 29
            try {
                String name = "crash-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
                Uri uri = createPublic(ctx, name);
                if (uri != null) {
                    OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(text.getBytes("UTF-8"));
                        os.flush();
                        os.close();
                    }
                }
            } catch (Throwable ignore) {
            }
            try {
                File dir = new File(ctx.getExternalFilesDir(null), "logs");
                dir.mkdirs();
                FileOutputStream fos = new FileOutputStream(new File(dir, "crash-last.txt"), false);
                fos.write(text.getBytes("UTF-8"));
                fos.flush();
                fos.close();
            } catch (Throwable ignore) {
            }
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text));
                }
            } catch (Throwable ignore) {
            }
            flush(ctx);
        }
        Log.e(TAG, text);
    }

    public static synchronized void install(Context ctx) {
        if (ctx != null) {
            Context app = ctx.getApplicationContext();
            appCtx = app != null ? app : ctx;
        }
        if (installed) {
            return;
        }
        installed = true;
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    report(e, "uncaught in thread " + t.getName());
                } catch (Throwable ignore) {
                }
                if (old != null && old != Thread.getDefaultUncaughtExceptionHandler()) {
                    old.uncaughtException(t, e);
                }
            }
        });
        log("handler installed, pid=" + android.os.Process.myPid()
                + " device=" + Build.MANUFACTURER + " " + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT
                + " pkg=" + (appCtx != null ? appCtx.getPackageName() : "?")
                + " appId=" + BuildVars.APP_ID);
    }
}