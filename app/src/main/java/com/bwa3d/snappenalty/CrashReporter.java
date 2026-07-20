package com.bwa3d.snappenalty;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class CrashReporter {
    private static final String FILE = "snap_penalty_crash_report";
    private static final String KEY_SUMMARY = "summary";
    private static volatile boolean installed;

    private CrashReporter() {
    }

    public static synchronized void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        Context appContext = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            saveCrash(appContext, throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    public static void saveCrash(Context context, Throwable throwable) {
        try {
            StringWriter writer = new StringWriter();
            throwable.printStackTrace(new PrintWriter(writer));
            String report = summary(throwable) + "\n" + writer;
            if (report.length() > 8_000) {
                report = report.substring(0, 8_000);
            }
            preferences(context).edit().putString(KEY_SUMMARY, report).commit();
        } catch (Throwable ignored) {
            // A crash reporter must never cause another crash.
        }
    }

    public static String consumeLastCrash(Context context) {
        try {
            SharedPreferences preferences = preferences(context);
            String report = preferences.getString(KEY_SUMMARY, null);
            preferences.edit().remove(KEY_SUMMARY).apply();
            if (report == null) {
                return null;
            }
            int newline = report.indexOf('\n');
            String firstLine = newline >= 0 ? report.substring(0, newline) : report;
            return truncate(firstLine, 48);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String summary(Throwable throwable) {
        if (throwable == null) {
            return "Unknown startup error";
        }
        String name = throwable.getClass().getSimpleName();
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return name;
        }
        return name + ": " + truncate(message.replace('\n', ' '), 110);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String truncate(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        return value.substring(0, Math.max(0, maximum - 1)) + "…";
    }
}
