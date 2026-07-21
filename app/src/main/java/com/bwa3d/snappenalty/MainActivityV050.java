package com.bwa3d.snappenalty;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;

public final class MainActivityV050 extends Activity implements GameView.Host {
    private static final int REQUEST_AUDIO = 51;

    private GameView gameView;
    private MaestroAimControllerV050 aimController;
    private SnapDetectorV050 snapDetector;
    private BackgroundMusicControllerV050 musicController;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            gameView = new GameView(this, this);
            setContentView(gameView);
            aimController = new MaestroAimControllerV050(this, gameView::setAim);
            musicController = new BackgroundMusicControllerV050(this);
            if (!aimController.isAvailable()) {
                gameView.showTemporaryMessage("NO WRIST SENSOR • TAP TO SHOOT", 2600L);
            } else {
                gameView.showTemporaryMessage("MAESTRO AIM READY", 1200L);
            }
        } catch (Throwable error) {
            android.widget.TextView fallback = new android.widget.TextView(this);
            fallback.setTextColor(android.graphics.Color.WHITE);
            fallback.setBackgroundColor(android.graphics.Color.BLACK);
            fallback.setGravity(android.view.Gravity.CENTER);
            fallback.setText("SNAP PENALTY 0.5\n\nSTARTUP ERROR\n" + error.getClass().getSimpleName());
            setContentView(fallback);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gameView == null) return;
        gameView.refreshPreferences();
        if (aimController != null) aimController.start();
        if (musicController != null) {
            musicController.refreshPreference();
            musicController.onResume();
        }
        startMicrophoneSafely();
    }

    @Override
    protected void onPause() {
        stopMicrophone();
        if (aimController != null) aimController.stop();
        if (musicController != null) musicController.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopMicrophone();
        if (musicController != null) musicController.stop();
        super.onDestroy();
    }

    @Override
    public void openSettings() {
        try {
            startActivity(new Intent(this, SettingsActivityV050.class));
        } catch (Throwable error) {
            gameView.showTemporaryMessage("SETTINGS FAILED TO OPEN", 1800L);
        }
    }

    @Override
    public void recenterAim() {
        if (aimController != null) aimController.calibrate();
    }

    @Override
    public void resetAimAfterPenalty() {
        if (aimController != null) {
            gameView.postDelayed(aimController::calibrate, 120L);
        }
    }

    private void startMicrophoneSafely() {
        stopMicrophone();
        if (!GamePreferences.isMicEnabled(this)) {
            gameView.setMicrophoneListening(false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            gameView.setMicrophoneListening(false);
            gameView.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
                }
            }, 650L);
            return;
        }
        try {
            snapDetector = new SnapDetectorV050(
                    this,
                    GamePreferences.getSnapSensitivity(this),
                    new SnapDetectorV050.Listener() {
                        @Override public void onSnapDetected() {
                            if (gameView != null) gameView.onSnapDetected();
                        }
                        @Override public void onAudioLevel(float level01) {
                            if (gameView != null) gameView.setAudioLevel(level01);
                        }
                        @Override public void onDetectorError(String message) {
                            if (gameView != null) {
                                gameView.setMicrophoneListening(false);
                                gameView.showTemporaryMessage("MIC ERROR • TAP WORKS", 2000L);
                            }
                        }
                    });
            boolean started = snapDetector.start();
            gameView.setMicrophoneListening(started);
            if (!started) gameView.showTemporaryMessage("MIC UNAVAILABLE • TAP WORKS", 2000L);
        } catch (Throwable error) {
            snapDetector = null;
            gameView.setMicrophoneListening(false);
            gameView.showTemporaryMessage("MIC START FAILED • TAP WORKS", 2000L);
        }
    }

    private void stopMicrophone() {
        SnapDetectorV050 local = snapDetector;
        snapDetector = null;
        if (local != null) {
            try { local.stop(); } catch (Throwable ignored) { }
        }
        if (gameView != null) {
            gameView.setAudioLevel(0f);
            gameView.setMicrophoneListening(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQUEST_AUDIO || gameView == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            gameView.postDelayed(this::startMicrophoneSafely, 200L);
        } else {
            GamePreferences.setMicEnabled(this, false);
            gameView.showTemporaryMessage("MIC DENIED • TAP WORKS", 2200L);
        }
    }
}
