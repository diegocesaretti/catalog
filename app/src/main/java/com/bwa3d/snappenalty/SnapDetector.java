package com.bwa3d.snappenalty;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SnapDetector {
    public interface Listener {
        void onSnapDetected();
        void onAudioLevel(float level01);
        void onDetectorError(String message);
    }

    private static final int SAMPLE_RATE = 22050;
    private static final int FRAME_SAMPLES = 1024;
    private static final long COOLDOWN_MS = 300L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread worker;
    private AudioRecord audioRecord;
    private volatile int sensitivity;

    public SnapDetector(Context context, int sensitivity, Listener listener) {
        this.context = context.getApplicationContext();
        this.sensitivity = clamp(sensitivity, 1, 10);
        this.listener = listener;
    }

    public void setSensitivity(int sensitivity) {
        this.sensitivity = clamp(sensitivity, 1, 10);
    }

    public boolean start() {
        if (running.get()) return true;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            postError("Microphone permission is not granted");
            return false;
        }
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            postError("Microphone buffer unavailable");
            return false;
        }
        int bufferBytes = Math.max(minimum, FRAME_SAMPLES * 6);
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes);
        } catch (RuntimeException exception) {
            postError("Could not open the watch microphone");
            return false;
        }
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            postError("Microphone init failed");
            return false;
        }
        running.set(true);
        worker = new Thread(this::captureLoop, "SnapPenalty-Microphone");
        worker.start();
        return true;
    }

    public void stop() {
        running.set(false);
        AudioRecord record = audioRecord;
        if (record != null) {
            try { record.stop(); } catch (IllegalStateException ignored) {}
        }
        Thread localWorker = worker;
        if (localWorker != null) {
            try { localWorker.join(400L); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        worker = null;
    }

    private void captureLoop() {
        short[] frame = new short[FRAME_SAMPLES];
        float noiseRms = 120f;
        long lastSnapMs = 0L;
        float levelSmoothed = 0f;
        try {
            audioRecord.startRecording();
            while (running.get()) {
                int count = audioRecord.read(frame, 0, frame.length, AudioRecord.READ_BLOCKING);
                if (count <= 16) continue;
                Features features = calculateFeatures(frame, count);
                float normalizedLevel = clamp(features.peak / 7000f, 0f, 1f);
                levelSmoothed += 0.22f * (normalizedLevel - levelSmoothed);
                final float uiLevel = levelSmoothed;
                mainHandler.post(() -> listener.onAudioLevel(uiLevel));

                int localSensitivity = sensitivity;
                float peakFloor = 1200f - (localSensitivity - 1) * 80f;
                float relativePeak = 5.5f - (localSensitivity - 1) * 0.35f;
                float derivativeFloor = 0.82f - (localSensitivity - 1) * 0.03f;
                float crestFloor = 2.0f - (localSensitivity - 1) * 0.03f;
                boolean strongPeak = features.peak > Math.max(peakFloor, noiseRms * relativePeak);
                boolean impulsive = features.derivativeRatio > derivativeFloor
                        && features.crestFactor > crestFloor;
                boolean brightEnough = features.zeroCrossingRate > 0.035f;
                long nowMs = android.os.SystemClock.elapsedRealtime();
                boolean isSnap = strongPeak && impulsive && brightEnough
                        && nowMs - lastSnapMs > COOLDOWN_MS;

                if (isSnap) {
                    lastSnapMs = nowMs;
                    mainHandler.post(listener::onSnapDetected);
                } else if (features.rms < noiseRms * 2.5f) {
                    noiseRms += 0.03f * (features.rms - noiseRms);
                    noiseRms = clamp(noiseRms, 50f, 3500f);
                }
            }
        } catch (SecurityException exception) {
            postError("Microphone permission was revoked");
        } catch (IllegalStateException exception) {
            if (running.get()) postError("Microphone capture stopped unexpectedly");
        } finally {
            running.set(false);
            mainHandler.post(() -> listener.onAudioLevel(0f));
        }
    }

    private static Features calculateFeatures(short[] samples, int count) {
        double sumSquares = 0.0;
        double derivativeSum = 0.0;
        int peak = 0;
        int zeroCrossings = 0;
        short previous = samples[0];
        for (int i = 0; i < count; i++) {
            int value = samples[i];
            peak = Math.max(peak, Math.abs(value));
            sumSquares += (double) value * value;
            if (i > 0) {
                derivativeSum += Math.abs(value - previous);
                if ((value >= 0 && previous < 0) || (value < 0 && previous >= 0)) zeroCrossings++;
            }
            previous = samples[i];
        }
        float rms = (float) Math.sqrt(sumSquares / count);
        float meanDerivative = (float) (derivativeSum / Math.max(1, count - 1));
        return new Features(peak, rms,
                peak / Math.max(1f, rms),
                meanDerivative / Math.max(1f, rms),
                zeroCrossings / (float) Math.max(1, count - 1));
    }

    private void postError(String message) {
        mainHandler.post(() -> listener.onDetectorError(message));
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

    private static final class Features {
        final float peak;
        final float rms;
        final float crestFactor;
        final float derivativeRatio;
        final float zeroCrossingRate;

        Features(float peak, float rms, float crestFactor, float derivativeRatio, float zeroCrossingRate) {
            this.peak = peak;
            this.rms = rms;
            this.crestFactor = crestFactor;
            this.derivativeRatio = derivativeRatio;
            this.zeroCrossingRate = zeroCrossingRate;
        }
    }
}
