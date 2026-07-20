package com.bwa3d.snappenalty;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

public final class MainActivity extends Activity implements GameView.Host {
    private static final int REQUEST_RECORD_AUDIO = 41;

    private GameView gameView;
    private WristAimController wristAimController;
    private SnapDetector snapDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        gameView = new GameView(this, this);
        setContentView(gameView);

        wristAimController = new WristAimController(this, gameView::setAim);
        if (!wristAimController.isAvailable()) {
            gameView.showTemporaryMessage("NO ROTATION SENSOR • USE TAP", 2_500L);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        gameView.refreshPreferences();
        wristAimController.start();
        startMicrophoneIfEnabled();
    }

    @Override
    protected void onPause() {
        stopMicrophone();
        wristAimController.stop();
        super.onPause();
    }

    @Override
    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    public void recenterAim() {
        wristAimController.calibrate();
    }

    private void startMicrophoneIfEnabled() {
        stopMicrophone();
        if (!GamePreferences.isMicEnabled(this)) {
            gameView.setMicrophoneListening(false);
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            gameView.setMicrophoneListening(false);
            gameView.showTemporaryMessage("ALLOW MIC FOR SNAP SHOOTING", 1_800L);
            return;
        }

        snapDetector = new SnapDetector(
                this,
                GamePreferences.getSnapSensitivity(this),
                new SnapDetector.Listener() {
                    @Override
                    public void onSnapDetected() {
                        gameView.onSnapDetected();
                    }

                    @Override
                    public void onAudioLevel(float level01) {
                        gameView.setAudioLevel(level01);
                    }

                    @Override
                    public void onDetectorError(String message) {
                        gameView.setMicrophoneListening(false);
                        gameView.showTemporaryMessage(message.toUpperCase(), 2_000L);
                    }
                }
        );
        boolean started = snapDetector.start();
        gameView.setMicrophoneListening(started);
    }

    private void stopMicrophone() {
        if (snapDetector != null) {
            snapDetector.stop();
            snapDetector = null;
        }
        if (gameView != null) {
            gameView.setAudioLevel(0f);
            gameView.setMicrophoneListening(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMicrophoneIfEnabled();
        } else {
            gameView.showTemporaryMessage("MIC DENIED • TAP STILL WORKS", 2_200L);
        }
    }

    private void hideSystemUi() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        }
    }
}
