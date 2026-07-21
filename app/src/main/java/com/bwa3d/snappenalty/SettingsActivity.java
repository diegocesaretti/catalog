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
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(24));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = makeText("SETTINGS", 18f, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        content.addView(title, matchWrap(dp(10)));

        addSectionTitle(content, "MAESTRO WRIST AIM");
        addSeekRow(
                content,
                "Aim sensitivity",
                GamePreferences.getAimSensitivity(this),
                20,
                value -> {
                    GamePreferences.setAimSensitivity(this, value);
                    return value + " / 20";
                }
        );
        TextView aimHelp = makeText(
                "Higher values need only a very soft wrist tilt.",
                10f,
                0xFF9CA3AF,
                false
        );
        aimHelp.setGravity(Gravity.CENTER);
        content.addView(aimHelp, matchWrap(dp(5)));

        Switch invertSwitch = makeSwitch(
                "Invert vertical",
                GamePreferences.isInvertVertical(this)
        );
        invertSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                GamePreferences.setInvertVertical(this, checked));
        content.addView(invertSwitch, matchWrap(dp(7)));

        addSectionTitle(content, "SNAP SHOOTING");
        microphoneSwitch = makeSwitch(
                "Enable microphone snap",
                GamePreferences.isMicEnabled(this)
        );
        microphoneSwitch.setOnCheckedChangeListener(this::onMicrophoneToggled);
        content.addView(microphoneSwitch, matchWrap(dp(5)));

        addSeekRow(
                content,
                "Snap sensitivity",
                GamePreferences.getSnapSensitivity(this),
                20,
                value -> {
                    GamePreferences.setSnapSensitivity(this, value);
                    if (snapDetector != null) {
                        snapDetector.setSensitivity(value);
                    }
                    return value + " / 20";
                }
        );

        TextView snapHelp = makeText(
                "Try 14–18 when a soft snap is not detected.",
                10f,
                0xFF9CA3AF,
                false
        );
        snapHelp.setGravity(Gravity.CENTER);
        content.addView(snapHelp, matchWrap(dp(5)));

        Switch tapSwitch = makeSwitch(
                "Tap screen to shoot",
                GamePreferences.isTapEnabled(this)
        );
        tapSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                GamePreferences.setTapEnabled(this, checked));
        content.addView(tapSwitch, matchWrap(dp(6)));

        testMicButton = new Button(this);
        testMicButton.setText("TEST MICROPHONE");
        testMicButton.setAllCaps(true);
        testMicButton.setOnClickListener(view -> onTestMicrophone());
        content.addView(testMicButton, matchWrap(dp(6)));

        microphoneMeter = new ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
        );
        microphoneMeter.setMax(100);
        microphoneMeter.setProgress(0);
        content.addView(microphoneMeter, matchWrap(dp(4)));

        microphoneStatus = makeText("Microphone idle", 11f, 0xFFB3B9C5, false);
        microphoneStatus.setGravity(Gravity.CENTER);
        content.addView(microphoneStatus, matchWrap(dp(8)));

        addSectionTitle(content, "AUDIO");
        Switch musicSwitch = makeSwitch(
                "Background music",
                GamePreferences.isMusicEnabled(this)
        );
        musicSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                GamePreferences.setMusicEnabled(this, checked));
        content.addView(musicSwitch, matchWrap(dp(7)));

        addSectionTitle(content, "GOALKEEPER");
        addSeekRow(
                content,
                "Difficulty",
                GamePreferences.getDifficulty(this),
                3,
                value -> {
                    GamePreferences.setDifficulty(this, value);
                    return difficultyName(value);
                }
        );

        Button done = new Button(this);
        done.setText("DONE");
        done.setAllCaps(true);
        done.setOnClickListener(view -> finish());
        content.addView(done, matchWrap(0));

        refreshMicUi();
        return scrollView;
    }

    private void addSeekRow(LinearLayout parent, String labelText, int currentValue,
                            int maximum, ValueFormatter formatter) {
        TextView label = makeText(labelText, 13f, Color.WHITE, false);
        label.setGravity(Gravity.CENTER);
        parent.addView(label, matchWrap(dp(1)));

        TextView valueView = makeText(
                formatter.onValue(currentValue),
                12f,
                0xFFFFD166,
                false
        );
        valueView.setGravity(Gravity.CENTER);
        parent.addView(valueView, matchWrap(0));

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(maximum - 1);
        seekBar.setProgress(Math.max(0, Math.min(maximum - 1, currentValue - 1)));
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = progress + 1;
                valueView.setText(formatter.onValue(value));
            }
        });
        parent.addView(seekBar, matchWrap(dp(6)));
    }

    private void addSectionTitle(LinearLayout parent, String text) {
        TextView title = makeText(text, 11f, 0xFF6BE675, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(8), 0, dp(3));
        parent.addView(title, matchWrap(0));
    }

    private Switch makeSwitch(String label, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(Color.WHITE);
        toggle.setTextSize(13f);
        toggle.setChecked(checked);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
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

    private void onMicrophoneToggled(CompoundButton buttonView, boolean checked) {
        GamePreferences.setMicEnabled(this, checked);
        if (!checked) {
            stopMicrophoneTest();
        }
        refreshMicUi();
    }

    private void onTestMicrophone() {
        stopMicrophoneTest();
        if (!GamePreferences.isMicEnabled(this)) {
            setMicStatus("Enable microphone snap first", 0xFFFF8A80);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO
            );
            return;
        }

        snapDetector = new SnapDetector(
                this,
                GamePreferences.getSnapSensitivity(this),
                this
        );
        boolean started = snapDetector.start();
        if (started) {
            setMicStatus("Snap near the watch", 0xFFB3B9C5);
        } else {
            setMicStatus("Could not start microphone", 0xFFFF8A80);
        }
    }

    private void refreshMicUi() {
        boolean enabled = GamePreferences.isMicEnabled(this);
        if (testMicButton != null) {
            testMicButton.setEnabled(enabled);
            testMicButton.setAlpha(enabled ? 1f : 0.45f);
        }
        if (!enabled) {
            setMicStatus("Microphone disabled", 0xFFB3B9C5);
        }
    }

    private void stopMicrophoneTest() {
        if (snapDetector != null) {
            try {
                snapDetector.stop();
            } catch (Throwable ignored) {
            }
            snapDetector = null;
        }
        if (microphoneMeter != null) {
            microphoneMeter.setProgress(0);
        }
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            onTestMicrophone();
        } else {
            setMicStatus("Microphone permission denied", 0xFFFF8A80);
        }
    }

    private void setMicStatus(String text, int color) {
        if (microphoneStatus != null) {
            microphoneStatus.setText(text);
            microphoneStatus.setTextColor(color);
        }
    }

    private void showSimpleFallback(Throwable throwable) {
        TextView textView = new TextView(this);
        textView.setBackgroundColor(Color.BLACK);
        textView.setTextColor(Color.WHITE);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(dp(12), dp(12), dp(12), dp(12));
        textView.setText("Settings failed to open.\n"
                + throwable.getClass().getSimpleName());
        setContentView(textView);
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = bottomMargin;
        return params;
    }

    private String difficultyName(int value) {
        if (value <= 1) {
            return "EASY";
        }
        if (value >= 3) {
            return "HARD";
        }
        return "NORMAL";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface ValueFormatter {
        String onValue(int value);
    }

    private abstract static class SimpleSeekListener
            implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
