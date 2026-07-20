package com.bwa3d.snappenalty;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SnapDetector snapDetector;
    private ProgressBar microphoneMeter;
    private TextView microphoneStatus;
    private Switch microphoneSwitch;
    private SeekBar snapSensitivityBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hideSystemUi();
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (GamePreferences.isMicEnabled(this)) {
            startMicrophoneTestOrRequestPermission();
        }
    }

    @Override
    protected void onPause() {
        stopMicrophoneTest();
        super.onPause();
    }

    private View buildContent() {
        int sidePadding = dp(34);
        int verticalPadding = dp(18);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFF080B10);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(sidePadding, verticalPadding, sidePadding, dp(30));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("SNAP PENALTY");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19f);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(title, matchWrap(dp(8)));

        TextView subtitle = new TextView(this);
        subtitle.setText("SETTINGS");
        subtitle.setTextColor(0xFF9CA3AF);
        subtitle.setTextSize(11f);
        subtitle.setGravity(Gravity.CENTER);
        content.addView(subtitle, matchWrap(dp(14)));

        addSectionTitle(content, "WRIST AIM");
        TextView aimValue = addSeekSetting(
                content,
                "Sensitivity",
                GamePreferences.getAimSensitivity(this),
                10,
                value -> {
                    GamePreferences.setAimSensitivity(this, value);
                    return value + " / 10";
                }
        );
        aimValue.setText(GamePreferences.getAimSensitivity(this) + " / 10");

        Switch invertSwitch = addSwitch(content, "Invert vertical", GamePreferences.isInvertVertical(this));
        invertSwitch.setOnCheckedChangeListener((button, checked) ->
                GamePreferences.setInvertVertical(this, checked));

        addSectionTitle(content, "SNAP SHOOTING");
        microphoneSwitch = addSwitch(content, "Use microphone", GamePreferences.isMicEnabled(this));
        microphoneSwitch.setOnCheckedChangeListener(this::onMicrophoneToggle);

        TextView snapValue = new TextView(this);
        snapValue.setTextColor(0xFFFFC857);
        snapValue.setTextSize(12f);
        snapValue.setGravity(Gravity.CENTER);
        content.addView(snapValue, matchWrap(dp(0)));

        TextView snapLabel = makeLabel("Snap sensitivity");
        content.addView(snapLabel, matchWrap(dp(2)));

        snapSensitivityBar = new SeekBar(this);
        snapSensitivityBar.setMax(9);
        snapSensitivityBar.setProgress(GamePreferences.getSnapSensitivity(this) - 1);
        snapSensitivityBar.setContentDescription("Snap sensitivity");
        content.addView(snapSensitivityBar, matchWrap(dp(4)));
        snapValue.setText(GamePreferences.getSnapSensitivity(this) + " / 10");
        snapSensitivityBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 1;
                GamePreferences.setSnapSensitivity(SettingsActivity.this, value);
                snapValue.setText(value + " / 10");
                if (snapDetector != null) {
                    snapDetector.setSensitivity(value);
                }
            }
        });

        microphoneMeter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        microphoneMeter.setMax(100);
        microphoneMeter.setProgress(0);
        microphoneMeter.setContentDescription("Microphone level");
        content.addView(microphoneMeter, matchWrap(dp(3)));

        microphoneStatus = new TextView(this);
        microphoneStatus.setText("Make a finger snap near the watch");
        microphoneStatus.setTextColor(0xFF9CA3AF);
        microphoneStatus.setTextSize(11f);
        microphoneStatus.setGravity(Gravity.CENTER);
        microphoneStatus.setPadding(0, dp(3), 0, dp(8));
        content.addView(microphoneStatus, matchWrap(dp(3)));

        Switch tapSwitch = addSwitch(content, "Tap screen to shoot", GamePreferences.isTapEnabled(this));
        tapSwitch.setOnCheckedChangeListener((button, checked) ->
                GamePreferences.setTapEnabled(this, checked));

        addSectionTitle(content, "GOALKEEPER");
        TextView difficultyValue = addSeekSetting(
                content,
                "Difficulty",
                GamePreferences.getDifficulty(this),
                3,
                value -> {
                    GamePreferences.setDifficulty(this, value);
                    return difficultyName(value);
                }
        );
        difficultyValue.setText(difficultyName(GamePreferences.getDifficulty(this)));

        Button done = new Button(this);
        done.setText("DONE");
        done.setTextSize(13f);
        done.setAllCaps(true);
        done.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        doneParams.topMargin = dp(16);
        content.addView(done, doneParams);

        return scrollView;
    }

    private TextView addSeekSetting(
            LinearLayout parent,
            String label,
            int currentValue,
            int maximum,
            ValueFormatter formatter
    ) {
        TextView labelView = makeLabel(label);
        parent.addView(labelView, matchWrap(dp(1)));

        TextView valueView = new TextView(this);
        valueView.setTextColor(0xFFFFC857);
        valueView.setTextSize(12f);
        valueView.setGravity(Gravity.CENTER);
        parent.addView(valueView, matchWrap(dp(0)));

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(maximum - 1);
        seekBar.setProgress(currentValue - 1);
        seekBar.setContentDescription(label);
        parent.addView(seekBar, matchWrap(dp(6)));
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = progress + 1;
                valueView.setText(formatter.onValue(value));
            }
        });
        return valueView;
    }

    private Switch addSwitch(LinearLayout parent, String label, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(Color.WHITE);
        toggle.setTextSize(13f);
        toggle.setChecked(checked);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setPadding(0, dp(4), 0, dp(4));
        parent.addView(toggle, matchWrap(dp(2)));
        return toggle;
    }

    private void addSectionTitle(LinearLayout parent, String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(0xFF5CFF75);
        title.setTextSize(11f);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(10), 0, dp(4));
        parent.addView(title, matchWrap(dp(0)));
    }

    private TextView makeLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13f);
        label.setGravity(Gravity.CENTER);
        return label;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = bottomMargin;
        return params;
    }

    private void onMicrophoneToggle(CompoundButton button, boolean checked) {
        GamePreferences.setMicEnabled(this, checked);
        if (checked) {
            startMicrophoneTestOrRequestPermission();
        } else {
            stopMicrophoneTest();
            microphoneStatus.setText("Microphone disabled");
            microphoneStatus.setTextColor(0xFF9CA3AF);
        }
    }

    private void startMicrophoneTestOrRequestPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        startMicrophoneTest();
    }

    private void startMicrophoneTest() {
        stopMicrophoneTest();
        snapDetector = new SnapDetector(this, GamePreferences.getSnapSensitivity(this), this);
        boolean started = snapDetector.start();
        if (started) {
            microphoneStatus.setText("Make a finger snap near the watch");
            microphoneStatus.setTextColor(0xFF9CA3AF);
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
        microphoneStatus.setText("SNAP DETECTED");
        microphoneStatus.setTextColor(0xFF5CFF75);
        microphoneStatus.removeCallbacks(resetSnapMessage);
        microphoneStatus.postDelayed(resetSnapMessage, 650L);
    }

    private final Runnable resetSnapMessage = () -> {
        if (microphoneStatus != null && GamePreferences.isMicEnabled(this)) {
            microphoneStatus.setText("Make a finger snap near the watch");
            microphoneStatus.setTextColor(0xFF9CA3AF);
        }
    };

    @Override
    public void onAudioLevel(float level01) {
        microphoneMeter.setProgress(Math.round(level01 * 100f));
    }

    @Override
    public void onDetectorError(String message) {
        microphoneStatus.setText(message);
        microphoneStatus.setTextColor(0xFFFF6B6B);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMicrophoneTest();
        } else {
            GamePreferences.setMicEnabled(this, false);
            microphoneSwitch.setChecked(false);
            microphoneStatus.setText("Microphone permission denied");
            microphoneStatus.setTextColor(0xFFFF6B6B);
        }
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

    private void hideSystemUi() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface ValueFormatter {
        String onValue(int value);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
