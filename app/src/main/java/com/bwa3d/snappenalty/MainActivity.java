package com.bwa3d.snappenalty;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

public final class MainActivity extends Activity implements GameView.Host {
    private static final int REQUEST_RECORD_AUDIO = 41;

    private GameView gameView;
    private WristAimController wristAimController;
    private SnapDetector snapDetector;
    private BackgroundMusic backgroundMusic;
    private boolean bootFailed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashReporter.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            gameView = new GameView(this, this);
            setContentView(gameView);

            wristAimController = new WristAimController(this, gameView::setAim);
            backgroundMusic = new BackgroundMusic(this);

            if (!wristAimController.isAvailable()) {
                gameView.showTemporaryMessage("NO ROTATION SENSOR • TAP TO SHOOT", 3000L);
            }

            String previousCrash = CrashReporter.consumeLastCrash(this);
            if (previousCrash != null && !previousCrash.isEmpty()) {
                gameView.showTemporaryMessage("RECOVERED FROM: " + previousCrash, 5000L);
            } else {
                gameView.showTemporaryMessage("MAESTRO AIM • READY", 1800L);
            }
        } catch (Throwable error) {
            showBootError(error);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bootFailed || gameView == null) {
            return;
        }

        try {
            gameView.refreshPreferences();
            if (wristAimController != null) {
                wristAimController.start();
            }
            if (backgroundMusic != null) {
                backgroundMusic.onResume();
            }

            // Permission is still requested only from Settings, keeping boot reliable.
            if (GamePreferences.isMicEnabled(this)) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    gameView.postDelayed(this::startMicrophoneSafely, 650L);
                } else {
                    gameView.setMicrophoneListening(false);
                    gameView.showTemporaryMessage("MIC OFF • ENABLE IT IN SETTINGS", 2500L);
                }
            } else {
                gameView.setMicrophoneListening(false);
                if (backgroundMusic != null) {
                    backgroundMusic.setMicrophoneActive(false);
                }
            }
        } catch (Throwable error) {
            gameView.showTemporaryMessage("INPUT START ERROR • TAP STILL WORKS", 3000L);
        }
    }

    @Override
    protected void onPause() {
        stopMicrophone();
        if (wristAimController != null) {
            wristAimController.stop();
        }
        if (backgroundMusic != null) {
            backgroundMusic.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopMicrophone();
        if (wristAimController != null) {
            wristAimController.stop();
        }
        if (backgroundMusic != null) {
            backgroundMusic.release();
            backgroundMusic = null;
        }
        super.onDestroy();
    }

    @Override
    public void openSettings() {
        try {
            startActivity(new Intent(this, SettingsActivity.class));
        } catch (Throwable error) {
            if (gameView != null) {
                gameView.showTemporaryMessage("SETTINGS COULD NOT OPEN", 2000L);
            }
        }
    }

    @Override
    public void recenterAim() {
        if (wristAimController != null) {
            wristAimController.calibrate();
        }
    }

    private void startMicrophoneSafely() {
        if (isFinishing() || isDestroyed() || gameView == null) {
            return;
        }
        stopMicrophone();
        if (!GamePreferences.isMicEnabled(this)) {
            gameView.setMicrophoneListening(false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            gameView.setMicrophoneListening(false);
            return;
        }

        try {
            snapDetector = new SnapDetector(
                    this,
                    GamePreferences.getSnapSensitivity(this),
                    new SnapDetector.Listener() {
                        @Override
                        public void onSnapDetected() {
                            if (gameView != null) {
                                gameView.onSnapDetected();
                            }
                        }

                        @Override
                        public void onAudioLevel(float level01) {
                            if (gameView != null) {
                                gameView.setAudioLevel(level01);
                            }
                        }

                        @Override
                        public void onDetectorError(String message) {
                            if (gameView != null) {
                                gameView.setMicrophoneListening(false);
                                gameView.showTemporaryMessage(
                                        "MIC ERROR • TAP STILL WORKS",
                                        2500L
                                );
                            }
                            if (backgroundMusic != null) {
                                backgroundMusic.setMicrophoneActive(false);
                            }
                        }
                    }
            );
            boolean started = snapDetector.start();
            gameView.setMicrophoneListening(started);
            if (backgroundMusic != null) {
                backgroundMusic.setMicrophoneActive(started);
            }
            if (!started) {
                gameView.showTemporaryMessage("MIC UNAVAILABLE • TAP STILL WORKS", 2500L);
            }
        } catch (Throwable error) {
            snapDetector = null;
            gameView.setMicrophoneListening(false);
            if (backgroundMusic != null) {
                backgroundMusic.setMicrophoneActive(false);
            }
            gameView.showTemporaryMessage("MIC START FAILED • TAP STILL WORKS", 2500L);
        }
    }

    private void stopMicrophone() {
        SnapDetector detector = snapDetector;
        snapDetector = null;
        if (detector != null) {
            try {
                detector.stop();
            } catch (Throwable ignored) {
            }
        }
        if (backgroundMusic != null) {
            backgroundMusic.setMicrophoneActive(false);
        }
        if (gameView != null) {
            gameView.setAudioLevel(0f);
            gameView.setMicrophoneListening(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO || gameView == null) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            gameView.postDelayed(this::startMicrophoneSafely, 250L);
        } else {
            gameView.showTemporaryMessage("MIC DENIED • TAP STILL WORKS", 2200L);
        }
    }

    private void showBootError(Throwable error) {
        bootFailed = true;
        CrashReporter.saveCrash(this, error);

        TextView errorView = new TextView(this);
        errorView.setBackgroundColor(0xFF080B10);
        errorView.setTextColor(Color.WHITE);
        errorView.setGravity(Gravity.CENTER);
        errorView.setTextSize(14f);
        errorView.setPadding(24, 24, 24, 24);
        errorView.setText("SNAP PENALTY SAFE MODE\n\nThe game could not initialize.\n\n"
                + CrashReporter.summary(error)
                + "\n\nTake a photo of this screen.");
        setContentView(errorView);
    }
}
