package com.bwa3d.snappenalty;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public final class SettingsActivity extends Activity implements SnapDetector.Listener {
    private static final int REQUEST_RECORD_AUDIO = 42;

    private SnapDetector snapDetector;
    private ProgressBar microphoneMeter;
    private TextView microphoneStatus;
    private Switch microphoneSwitch;
    private Button testMicButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(buildContent());
        } catch (Throwable throwable) {
            showFallback(throwable);
        }
    }

    @Override
    protected void onPause() {
        stopMicrophoneTest();
        super.onPause();
    }

    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF0A0D12);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = text("SETTINGS", 18f, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        content.addView(title, params(dp(10)));

        section(content, "WRIST AIM");
        seek(content, "Aim sensitivity", GamePreferences.getAimSensitivity(this), 10,
                value -> {
                    GamePreferences.setAimSensitivity(this, value);
                    return value + " / 10";
                });
        Switch invert = toggle("Invert vertical", GamePreferences.isInvertVertical(this));
        invert.setOnCheckedChangeListener((button, checked) ->
                GamePreferences.setInvertVertical(this, checked));
        content.addView(invert, params(dp(8)));

        section(content, "SHOOTING");
        microphoneSwitch = toggle("Enable snap shooting", GamePreferences.isMicEnabled(this));
        microphoneSwitch.setOnCheckedChangeListener((button, checked) -> {
            GamePreferences.setMicEnabled(this, checked);
            if (!checked) stopMicrophoneTest();
            refreshMicUi();
        });
        content.addView(microphoneSwitch, params(dp(6)));

        seek(content, "Snap sensitivity", GamePreferences.getSnapSensitivity(this), 20,
                value -> {
                    GamePreferences.setSnapSensitivity(this, value);
                    if (snapDetector != null) snapDetector.setSensitivity(value);
                    return value + " / 20";
                });

        Switch tap = toggle("Tap screen to shoot", GamePreferences.isTapEnabled(this));
        tap.setOnCheckedChangeListener((button, checked) ->
                GamePreferences.setTapEnabled(this, checked));
        content.addView(tap, params(dp(6)));

        testMicButton = new Button(this);
        testMicButton.setText("TEST MICROPHONE");
        testMicButton.setOnClickListener(view -> testMicrophone());
        content.addView(testMicButton, params(dp(8)));

        microphoneMeter = new ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        microphoneMeter.setMax(100);
        content.addView(microphoneMeter, params(dp(4)));

        microphoneStatus = text("Microphone idle", 12f, 0xFFB3B9C5, false);
        microphoneStatus.setGravity(Gravity.CENTER);
        content.addView(microphoneStatus, params(dp(10)));

        section(content, "AUDIO");
        Switch music = toggle("Background music", GamePreferences.isMusicEnabled(this));
        music.setOnCheckedChangeListener((button, checked) ->
                GamePreferences.setMusicEnabled(this, checked));
        content.addView(music, params(dp(8)));

        section(content, "GOALKEEPER");
        seek(content, "Difficulty", GamePreferences.getDifficulty(this), 3,
                value -> {
                    GamePreferences.setDifficulty(this, value);
                    if (value == 1) return "EASY";
                    if (value == 3) return "HARD";
                    return "NORMAL";
                });

        Button done = new Button(this);
        done.setText("DONE");
        done.setOnClickListener(view -> finish());
        content.addView(done, params(0));

        refreshMicUi();
        return scroll;
    }

    private void testMicrophone() {
        stopMicrophoneTest();
        if (!GamePreferences.isMicEnabled(this)) {
            setMicStatus("Enable snap shooting first", 0xFFFF8A80);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        snapDetector = new SnapDetector(
                this, GamePreferences.getSnapSensitivity(this), this);
        if (snapDetector.start()) {
            setMicStatus("Snap near the watch", 0xFFB3B9C5);
        } else {
            setMicStatus("Could not start microphone", 0xFFFF8A80);
        }
    }

    private void stopMicrophoneTest() {
        if (snapDetector != null) {
            snapDetector.stop();
            snapDetector = null;
        }
        if (microphoneMeter != null) microphoneMeter.setProgress(0);
    }

    private void refreshMicUi() {
        boolean enabled = GamePreferences.isMicEnabled(this);
        if (testMicButton != null) {
            testMicButton.setEnabled(enabled);
            testMicButton.setAlpha(enabled ? 1f : 0.45f);
        }
        if (!enabled) setMicStatus("Microphone disabled", 0xFFB3B9C5);
    }

    @Override
    public void onSnapDetected() {
        setMicStatus("SNAP DETECTED", 0xFF6BE675);
    }

    @Override
    public void onAudioLevel(float level01) {
        if (microphoneMeter != null) {
            microphoneMeter.setProgress(Math.round(level01 * 100f));
        }
    }

    @Override
    public void onDetectorError(String message) {
        setMicStatus(message, 0xFFFF8A80);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) return;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            testMicrophone();
        } else {
            setMicStatus("Microphone permission denied", 0xFFFF8A80);
        }
    }

    private void seek(LinearLayout parent, String label, int initial,
                      int maximum, Formatter formatter) {
        parent.addView(text(label, 13f, Color.WHITE, false), params(dp(2)));
        TextView value = text(formatter.format(initial), 12f, 0xFFFFD166, false);
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        parent.addView(value, params(dp(1)));

        SeekBar bar = new SeekBar(this);
        bar.setMax(maximum - 1);
        bar.setProgress(Math.max(0, initial - 1));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(formatter.format(progress + 1));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        parent.addView(bar, params(dp(8)));
    }

    private void section(LinearLayout parent, String label) {
        TextView view = text(label, 11f, 0xFF6BE675, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(8), 0, dp(4));
        parent.addView(view, params(0));
    }

    private Switch toggle(String label, boolean checked) {
        Switch view = new Switch(this);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13f);
        view.setChecked(checked);
        return view;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private void setMicStatus(String value, int color) {
        if (microphoneStatus == null) return;
        microphoneStatus.setText(value);
        microphoneStatus.setTextColor(color);
    }

    private void showFallback(Throwable throwable) {
        TextView view = text("Settings failed to open.\n"
                + throwable.getClass().getSimpleName(), 13f, Color.WHITE, false);
        view.setBackgroundColor(Color.BLACK);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        setContentView(view);
    }

    private LinearLayout.LayoutParams params(int bottomMargin) {
        LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        value.bottomMargin = bottomMargin;
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface Formatter {
        String format(int value);
    }
}
