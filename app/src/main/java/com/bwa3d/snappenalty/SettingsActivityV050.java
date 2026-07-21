package com.bwa3d.snappenalty;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public final class SettingsActivityV050 extends Activity implements SnapDetectorV050.Listener {
    private static final int REQUEST_AUDIO = 52;
    private SnapDetectorV050 detector;
    private ProgressBar meter;
    private TextView micStatus;
    private Switch micSwitch;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            setContentView(buildUi());
        } catch (Throwable error) {
            TextView fallback = text("SETTINGS ERROR\n" + error.getClass().getSimpleName(), 14f, Color.WHITE);
            fallback.setGravity(Gravity.CENTER);
            fallback.setBackgroundColor(Color.BLACK);
            setContentView(fallback);
        }
    }

    @Override
    protected void onPause() {
        stopTest();
        super.onPause();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF080B10);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(30), dp(18), dp(30), dp(26));
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(box);

        TextView title = text("SNAP PENALTY", 18f, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(title, params(dp(12)));

        section(box, "WRIST AIM");
        seek(box, "Aim sensitivity", GamePreferences.getAimSensitivity(this), 10, value -> {
            GamePreferences.setAimSensitivity(this, value);
            return value + " / 10";
        });
        Switch invert = toggle("Invert vertical", GamePreferences.isInvertVertical(this));
        invert.setOnCheckedChangeListener((b, checked) -> GamePreferences.setInvertVertical(this, checked));
        box.addView(invert, params(dp(8)));

        section(box, "SNAP SHOOTING");
        micSwitch = toggle("Enable microphone", GamePreferences.isMicEnabled(this));
        micSwitch.setOnCheckedChangeListener(this::micChanged);
        box.addView(micSwitch, params(dp(5)));
        seek(box, "Snap sensitivity", GamePreferences.getSnapSensitivity(this), 20, value -> {
            GamePreferences.setSnapSensitivity(this, value);
            if (detector != null) detector.setSensitivity(value);
            return value + " / 20";
        });

        Switch tap = toggle("Tap screen to shoot", GamePreferences.isTapEnabled(this));
        tap.setOnCheckedChangeListener((b, checked) -> GamePreferences.setTapEnabled(this, checked));
        box.addView(tap, params(dp(6)));

        Button test = new Button(this);
        test.setText("TEST SNAP");
        test.setOnClickListener(v -> startTestOrPermission());
        box.addView(test, params(dp(6)));

        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(100);
        box.addView(meter, params(dp(3)));
        micStatus = text("Snap near the watch", 11f, 0xFFB3B9C5);
        micStatus.setGravity(Gravity.CENTER);
        box.addView(micStatus, params(dp(9)));

        section(box, "AUDIO");
        Switch music = toggle("Background music", GamePreferences.isMusicEnabled(this));
        music.setOnCheckedChangeListener((b, checked) -> GamePreferences.setMusicEnabled(this, checked));
        box.addView(music, params(dp(8)));

        section(box, "GOALKEEPER");
        seek(box, "Difficulty", GamePreferences.getDifficulty(this), 3, value -> {
            GamePreferences.setDifficulty(this, value);
            return value == 1 ? "EASY" : value == 3 ? "HARD" : "NORMAL";
        });

        Button done = new Button(this);
        done.setText("DONE");
        done.setOnClickListener(v -> finish());
        box.addView(done, params(0));
        return scroll;
    }

    private void micChanged(CompoundButton button, boolean enabled) {
        GamePreferences.setMicEnabled(this, enabled);
        if (!enabled) {
            stopTest();
            if (micStatus != null) micStatus.setText("Microphone disabled");
        }
    }

    private void startTestOrPermission() {
        if (!GamePreferences.isMicEnabled(this)) {
            micStatus.setText("Enable microphone first");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        stopTest();
        detector = new SnapDetectorV050(this, GamePreferences.getSnapSensitivity(this), this);
        if (detector.start()) {
            micStatus.setText("Snap near the watch");
            micStatus.setTextColor(0xFFB3B9C5);
        }
    }

    private void stopTest() {
        if (detector != null) detector.stop();
        detector = null;
        if (meter != null) meter.setProgress(0);
    }

    @Override public void onSnapDetected() {
        micStatus.setText("SNAP DETECTED");
        micStatus.setTextColor(0xFF5CFF75);
    }
    @Override public void onAudioLevel(float level) {
        if (meter != null) meter.setProgress(Math.round(level * 100f));
    }
    @Override public void onDetectorError(String message) {
        micStatus.setText(message);
        micStatus.setTextColor(0xFFFF6B6B);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQUEST_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startTestOrPermission();
        } else if (code == REQUEST_AUDIO) {
            GamePreferences.setMicEnabled(this, false);
            micSwitch.setChecked(false);
            micStatus.setText("Microphone permission denied");
        }
    }

    private void section(LinearLayout box, String value) {
        TextView view = text(value, 11f, 0xFF5CFF75);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(view, params(dp(5)));
    }

    private void seek(LinearLayout box, String label, int current, int maximum, Formatter formatter) {
        TextView labelView = text(label, 13f, Color.WHITE);
        labelView.setGravity(Gravity.CENTER);
        box.addView(labelView, params(0));
        TextView valueView = text(formatter.format(current), 12f, 0xFFFFC857);
        valueView.setGravity(Gravity.CENTER);
        box.addView(valueView, params(0));
        SeekBar bar = new SeekBar(this);
        bar.setMax(maximum - 1);
        bar.setProgress(current - 1);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valueView.setText(formatter.format(progress + 1));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        box.addView(bar, params(dp(7)));
    }

    private Switch toggle(String label, boolean checked) {
        Switch value = new Switch(this);
        value.setText(label);
        value.setTextColor(Color.WHITE);
        value.setTextSize(13f);
        value.setChecked(checked);
        return value;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams params(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = bottom;
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface Formatter { String format(int value); }
}
