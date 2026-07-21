package com.bwa3d.snappenalty;

import android.content.Context;
import android.content.SharedPreferences;

public final class GamePreferences {
    private static final String FILE = "snap_penalty_safe_preferences_v2";
    private static final String KEY_AIM_SENSITIVITY = "aim_sensitivity";
    private static final String KEY_SNAP_SENSITIVITY = "snap_sensitivity";
    private static final String KEY_MIC_ENABLED = "mic_enabled";
    private static final String KEY_TAP_ENABLED = "tap_enabled";
    private static final String KEY_INVERT_VERTICAL = "invert_vertical";
    private static final String KEY_DIFFICULTY = "difficulty";
    private static final String KEY_MUSIC_ENABLED = "music_enabled";

    private GamePreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int getAimSensitivity(Context context) {
        return clamp(prefs(context).getInt(KEY_AIM_SENSITIVITY, 13), 1, 20);
    }

    public static void setAimSensitivity(Context context, int value) {
        prefs(context).edit().putInt(KEY_AIM_SENSITIVITY, clamp(value, 1, 20)).apply();
    }

    public static int getSnapSensitivity(Context context) {
        return clamp(prefs(context).getInt(KEY_SNAP_SENSITIVITY, 12), 1, 20);
    }

    public static void setSnapSensitivity(Context context, int value) {
        prefs(context).edit().putInt(KEY_SNAP_SENSITIVITY, clamp(value, 1, 20)).apply();
    }

    public static boolean isMicEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MIC_ENABLED, false);
    }

    public static void setMicEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MIC_ENABLED, enabled).apply();
    }

    public static boolean isTapEnabled(Context context) {
        return prefs(context).getBoolean(KEY_TAP_ENABLED, true);
    }

    public static void setTapEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_TAP_ENABLED, enabled).apply();
    }

    public static boolean isInvertVertical(Context context) {
        return prefs(context).getBoolean(KEY_INVERT_VERTICAL, false);
    }

    public static void setInvertVertical(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_INVERT_VERTICAL, enabled).apply();
    }

    public static int getDifficulty(Context context) {
        return clamp(prefs(context).getInt(KEY_DIFFICULTY, 2), 1, 3);
    }

    public static void setDifficulty(Context context, int value) {
        prefs(context).edit().putInt(KEY_DIFFICULTY, clamp(value, 1, 3)).apply();
    }

    public static boolean isMusicEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MUSIC_ENABLED, true);
    }

    public static void setMusicEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MUSIC_ENABLED, enabled).apply();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
