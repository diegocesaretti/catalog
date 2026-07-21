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
            showSimpleFallback(throwable);
        }
    }

    @Override
    protected void onPause() {
        stopMicrophoneTest();
        super.onPause();
    }

    private ScrollView buildContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFF0A0D12);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = dp(20);
        content.setPadding(side, dp(18), side, dp(20));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = makeText("SETTINGS", 18f, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        content.addView(title, matchWrap(dp(10)));

        addSectionTitle(content, "WRIST AIM");
        addSeekRow(content, "Aim sensitivity", GamePreferences.getAimSensitivity(this), 10,
                value -> {
                    GamePreferences.setAimSensitivity(this, value);
                    return value + " / 10";
                });

        Switch invertSwitch = makeSwitch("Invert vertical", GamePreferences.isInvertVertical(this));
        invertSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                GamePreferences.setInvertVertical(this, isChecked));
        content.addView(invertSwitch, matchWrap(dp(8)));

        addSectionTitle(content, "SHOOTING");
        microphoneSwitch = makeSwitch("Enable snap shooting", GamePreferences.isMicEnabled(this));
        microphoneSwitch.setOnCheckedChangeListener(this::onMicrophoneToggled);
        content.addView(microphoneSwitch, matchWrap(dp(6)));

        addSeekRow(content, "Snap sensitivity", GamePreferences.getSnapSensitivity(this), 10,
                value -> {
                    GamePreferences.setSnapSensitivity(this, value);
                    if (snapDetector != null) {
                        snapDetector.setSensitivity(value);
                    }
                    return value + " / 10";
                });

        Switch tapSwitch = makeSwitch("Tap screen to shoot", GamePreferences.isTapEnabled(this));
        tapSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                GamePreferences.setTapEnabled(this, isChecked));
        content.addView(tapSwitch, matchWrap(dp(6)));

        testMicButton = new Button(this);
        testMicButton.setText("TEST MICROPHONE");
        testMicButton.setAllCaps(true);
        testMicButton.setOnClickListener(v -> onTestMicrophone());
        content.addView(testMicButton, matchWrap(dp(8)));

        microphoneMeter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        microphoneMeter.setMax(100);
        content.addView(microphoneMeter, matchWrap(dp(4)));

        microphoneStatus = makeText("Microphone idle", 12f, 0xFFB3B9C5, false);
        microphoneStatus.setGravity(Gravity.CENTER);
        content.addView(microphoneStatus, matchWrap(dp(10)));

        addSectionTitle(content, "GOALKEEPER");
        addSeekRow(content, "Difficulty", GamePreferences.getDifficulty(this), 3,
                value -> {
                    GamePreferences.setDifficulty(this, value);
                    return difficultyName(value);
                });

        Button done = new Button(this);
        done.setText("DONE");
        done.setAllCaps(true);
        done.setOnClickListener(v -> finish());
        content.addView(done, matchWrap(0));

        refreshMicUi();
        return scrollView;
    }

    private void addSeekRow(LinearLayout parent, String labelText, int value, int max, ValueFormatter formatter) {
        TextView label = makeText(labelText, 13f, Color.WHITE, false);
        parent.addView(label, matchWrap(dp(2)));

        TextView valueView = makeText(formatter.onValue(value), 12f, 0xFFFFD166, false);
        valueView.setGravity(Gravity.CENTER_HORIZONTAL);
        parent.addView(valueView, matchWrap(dp(1)));

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max - 1);
        seekBar.setProgress(Math.max(0, value - 1));
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int newValue = progress + 1;
                valueView.setText(formatter.onValue(newValue));
            }
        });
        parent.addView(seekBar, matchWrap(dp(8)));
    }

    private void addSectionTitle(LinearLayout parent, String text) {
        TextView title = makeText(text, 11f, 0xFF6BE675, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, dp(4));
        parent.addView(title, matchWrap(0));
    }

    private Switch makeSwitch(String label, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(Color.WHITE);
        toggle.setTextSize(13f);
        toggle.setChecked(checked);
        return toggle;
    }

    private TextView makeText(String text, float sizeSp, int color, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(sizeSp);
        textView.setTextColor(color);
        if (bold) {
            textView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return textView;
    }

    private void onMicrophoneToggled(CompoundButton buttonView, boolean isChecked) {
        GamePreferences.setMicEnabled(this, isChecked);
        if (!isChecked) {
            stopMicrophoneTest();
        }
        refreshMicUi();
    }

    private void onTestMicrophone() {
        stopMicrophoneTest();
        if (!GamePreferences.isMicEnabled(this)) {
            microphoneStatus.setText("Enable snap shooting first");
            microphoneStatus.setTextColor(0xFFFF8A80);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        snapDetector = new SnapDetector(this, GamePreferences.getSnapSensitivity(this), this);
        boolean started = snapDetector.start();
        if (started) {
            microphoneStatus.setText("Snap near the watch");
            microphoneStatus.setTextColor(0xFFB3B9C5);
        } else {
            microphoneStatus.setText("Could not start microphone");
            microphoneStatus.setTextColor(0xFFFF8A80);
        }
    }

    private void refreshMicUi() {
        boolean enabled = GamePreferences.isMicEnabled(this);
        if (testMicButton != null) {
            testMicButton.setEnabled(enabled);
            testMicButton.setAlpha(enabled ? 1f : 0.45f);
        }
        if (microphoneStatus != null && !enabled) {
            microphoneStatus.setText("Microphone disabled");
            microphoneStatus.setTextColor(0xFFB3B9C5);
        }
    }

    private void stopMicrophoneTest() {
        if (snapDetector != null) {
            snapDetector.stop();
            snapDetector = null;
        }
        if (microphoneMeter != null) {
            microphoneMeter.setProgress(0);
        }
    }

    @Override
    public void onSnapDetected() {
        if (microphoneStatus != null) {
            microphoneStatus.setText("SNAP DETECTED");
            microphoneStatus.setTextColor(0xFF6BE675);
        }
    }

    @Override
    public void onAudioLevel(float level01) {
        if (microphoneMeter != null) {
            microphoneMeter.setProgress(Math.round(level01 * 100f));
        }
    }

    @Override
    public void onDetectorError(String message) {
        if (microphoneStatus != null) {
            microphoneStatus.setText(message);
            microphoneStatus.setTextColor(0xFFFF8A80);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onTestMicrophone();
            } else if (microphoneStatus != null) {
                microphoneStatus.setText("Microphone permission denied");
                microphoneStatus.setTextColor(0xFFFF8A80);
            }
        }
    }

    private void showSimpleFallback(Throwable throwable) {
        TextView textView = new TextView(this);
        textView.setBackgroundColor(Color.BLACK);
        textView.setTextColor(Color.WHITE);
        textView.setPadding(dp(12), dp(12), dp(12), dp(12));
        textView.setText("Settings failed to open.\n" + throwable.getClass().getSimpleName());
        setContentView(textView);
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private String difficultyName(int value) {
        if (value <= 1) return "EASY";
        if (value >= 3) return "HARD";
        return "NORMAL";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface ValueFormatter {
        String onValue(int value);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
