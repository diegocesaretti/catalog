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

    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME_SAMPLES = 512;
    private static final long COOLDOWN_MS = 360L;

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
        if (running.get()) {
            return true;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            postError("Microphone permission is not granted");
            return false;
        }

        int minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minimum <= 0) {
            postError("This watch did not expose a usable microphone buffer");
            return false;
        }

        int bufferBytes = Math.max(minimum, FRAME_SAMPLES * 4);
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
            );
        } catch (RuntimeException exception) {
            postError("Could not open the watch microphone");
            return false;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            postError("The watch microphone could not be initialized");
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
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
                // The capture loop may already have stopped the recorder.
            }
        }
        Thread localWorker = worker;
        if (localWorker != null) {
            try {
                localWorker.join(350L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        worker = null;
    }

    private void captureLoop() {
        short[] frame = new short[FRAME_SAMPLES];
        float noiseRms = 180f;
        long lastSnapMs = 0L;
        float smoothedLevel = 0f;

        try {
            audioRecord.startRecording();
            while (running.get()) {
                int count = audioRecord.read(frame, 0, frame.length, AudioRecord.READ_BLOCKING);
                if (count <= 8) {
                    continue;
                }

                Features features = calculateFeatures(frame, count);
                float normalizedLevel = clamp(features.peak / 10_000f, 0f, 1f);
                smoothedLevel += 0.22f * (normalizedLevel - smoothedLevel);
                final float levelForUi = smoothedLevel;
                mainHandler.post(() -> listener.onAudioLevel(levelForUi));

                int localSensitivity = sensitivity;
                float relativeMultiplier = 8.2f - (localSensitivity - 1) * (5.6f / 9f);
                float absoluteMinimum = 2_900f - (localSensitivity - 1) * (1_850f / 9f);
                float peakThreshold = Math.max(absoluteMinimum, noiseRms * relativeMultiplier);
                float crestMinimum = 2.45f - (localSensitivity - 1) * (0.55f / 9f);
                float derivativeMinimum = 1.02f - (localSensitivity - 1) * (0.22f / 9f);
                float zeroCrossingMinimum = 0.055f;

                long nowMs = android.os.SystemClock.elapsedRealtime();
                boolean sharpImpulse = features.peak >= peakThreshold
                        && features.crestFactor >= crestMinimum
                        && features.derivativeRatio >= derivativeMinimum
                        && features.zeroCrossingRate >= zeroCrossingMinimum;

                if (sharpImpulse && nowMs - lastSnapMs >= COOLDOWN_MS) {
                    lastSnapMs = nowMs;
                    mainHandler.post(listener::onSnapDetected);
                } else if (!sharpImpulse && features.rms < noiseRms * 2.2f) {
                    noiseRms += 0.025f * (features.rms - noiseRms);
                    noiseRms = clamp(noiseRms, 70f, 4_000f);
                }
            }
        } catch (SecurityException exception) {
            postError("Microphone permission was revoked");
        } catch (IllegalStateException exception) {
            if (running.get()) {
                postError("Microphone capture stopped unexpectedly");
            }
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
            int absolute = Math.abs(value);
            peak = Math.max(peak, absolute);
            sumSquares += (double) value * value;

            if (i > 0) {
                derivativeSum += Math.abs(value - previous);
                if ((value >= 0 && previous < 0) || (value < 0 && previous >= 0)) {
                    zeroCrossings++;
                }
            }
            previous = samples[i];
        }

        float rms = (float) Math.sqrt(sumSquares / count);
        float meanDerivative = (float) (derivativeSum / Math.max(1, count - 1));
        float crestFactor = peak / Math.max(1f, rms);
        float derivativeRatio = meanDerivative / Math.max(1f, rms);
        float zeroCrossingRate = zeroCrossings / (float) Math.max(1, count - 1);
        return new Features(peak, rms, crestFactor, derivativeRatio, zeroCrossingRate);
    }

    private void postError(String message) {
        mainHandler.post(() -> listener.onDetectorError(message));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

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
