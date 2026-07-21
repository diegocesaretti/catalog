package com.bwa3d.snappenalty;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SnapDetector {
    public interface Listener {
        void onSnapDetected();
        void onAudioLevel(float level01);
        void onDetectorError(String message);
    }

    private static final int FRAME_SAMPLES = 512;
    private static final int[] SAMPLE_RATES = {44100, 22050, 16000};
    private static final int[] AUDIO_SOURCES = {
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
    };
    private static final long COOLDOWN_MS = 240L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread worker;
    private AudioRecord audioRecord;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private volatile int sensitivity;

    public SnapDetector(Context context, int sensitivity, Listener listener) {
        this.context = context.getApplicationContext();
        this.sensitivity = clamp(sensitivity, 1, 20);
        this.listener = listener;
    }

    public void setSensitivity(int sensitivity) {
        this.sensitivity = clamp(sensitivity, 1, 20);
    }

    public boolean start() {
        if (running.get()) return true;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            postError("Microphone permission is not granted");
            return false;
        }

        audioRecord = openBestRecorder();
        if (audioRecord == null) {
            postError("Could not open the watch microphone");
            return false;
        }
        enableAudioEffects(audioRecord.getAudioSessionId());

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
            }
        }
        Thread localWorker = worker;
        if (localWorker != null && localWorker != Thread.currentThread()) {
            try {
                localWorker.join(500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        releaseAudioResources();
        worker = null;
    }

    private AudioRecord openBestRecorder() {
        for (int source : AUDIO_SOURCES) {
            for (int rate : SAMPLE_RATES) {
                int minimum = AudioRecord.getMinBufferSize(
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) continue;
                int bufferBytes = Math.max(minimum, FRAME_SAMPLES * 8);
                AudioRecord candidate = null;
                try {
                    candidate = new AudioRecord(
                            source,
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferBytes);
                    if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                        return candidate;
                    }
                } catch (RuntimeException ignored) {
                }
                if (candidate != null) {
                    try {
                        candidate.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    private void enableAudioEffects(int audioSessionId) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
        } catch (Throwable ignored) {
            echoCanceler = null;
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId);
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }
        } catch (Throwable ignored) {
            noiseSuppressor = null;
        }
    }

    private void captureLoop() {
        short[] frame = new short[FRAME_SAMPLES];
        float noiseRms = 160f;
        float previousRms = 160f;
        float previousPeak = 700f;
        float levelSmoothed = 0f;
        long lastSnapMs = 0L;
        long startedMs = SystemClock.elapsedRealtime();

        try {
            audioRecord.startRecording();
            while (running.get()) {
                int count = audioRecord.read(frame, 0, frame.length, AudioRecord.READ_BLOCKING);
                if (count <= 32) continue;

                Features features = calculateFeatures(frame, count);
                float normalizedLevel = clamp(features.peak / 9000f, 0f, 1f);
                levelSmoothed += 0.24f * (normalizedLevel - levelSmoothed);
                float uiLevel = levelSmoothed;
                mainHandler.post(() -> listener.onAudioLevel(uiLevel));

                float sensitivity01 = (sensitivity - 1) / 19f;
                float absolutePeakFloor = lerp(3200f, 420f, sensitivity01);
                float relativePeakFloor = lerp(7.2f, 1.85f, sensitivity01);
                float crestFloor = lerp(4.3f, 1.45f, sensitivity01);
                float derivativeFloor = lerp(1.02f, 0.22f, sensitivity01);
                float zeroCrossFloor = lerp(0.065f, 0.010f, sensitivity01);
                float transientFloor = lerp(2.8f, 1.16f, sensitivity01);
                float peakJumpFloor = lerp(2.5f, 1.18f, sensitivity01);

                boolean strongPeak = features.peak
                        > Math.max(absolutePeakFloor, noiseRms * relativePeakFloor);
                boolean impulsive = features.crestFactor > crestFloor
                        && features.derivativeRatio > derivativeFloor;
                boolean brightEnough = features.zeroCrossingRate > zeroCrossFloor
                        || features.derivativeRatio > derivativeFloor * 1.45f;
                boolean transientEnough = features.rms > previousRms * transientFloor
                        || features.peak > previousPeak * peakJumpFloor;

                long nowMs = SystemClock.elapsedRealtime();
                boolean warmedUp = nowMs - startedMs > 350L;
                boolean isSnap = warmedUp
                        && strongPeak
                        && impulsive
                        && brightEnough
                        && transientEnough
                        && nowMs - lastSnapMs > COOLDOWN_MS;

                if (isSnap) {
                    lastSnapMs = nowMs;
                    mainHandler.post(listener::onSnapDetected);
                }

                if (!isSnap && features.rms < Math.max(900f, noiseRms * 2.7f)) {
                    float adaptation = features.rms < noiseRms ? 0.055f : 0.018f;
                    noiseRms += adaptation * (features.rms - noiseRms);
                    noiseRms = clamp(noiseRms, 45f, 5000f);
                }
                previousRms += 0.28f * (features.rms - previousRms);
                previousPeak += 0.24f * (features.peak - previousPeak);
            }
        } catch (SecurityException exception) {
            postError("Microphone permission was revoked");
        } catch (IllegalStateException exception) {
            if (running.get()) postError("Microphone capture stopped unexpectedly");
        } catch (Throwable exception) {
            if (running.get()) postError("Microphone capture failed");
        } finally {
            running.set(false);
            mainHandler.post(() -> listener.onAudioLevel(0f));
        }
    }

    private void releaseAudioResources() {
        if (echoCanceler != null) {
            try {
                echoCanceler.release();
            } catch (Throwable ignored) {
            }
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try {
                noiseSuppressor.release();
            } catch (Throwable ignored) {
            }
            noiseSuppressor = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Throwable ignored) {
            }
            audioRecord = null;
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
                if ((value >= 0 && previous < 0) || (value < 0 && previous >= 0)) {
                    zeroCrossings++;
                }
            }
            previous = samples[i];
        }
        float rms = (float) Math.sqrt(sumSquares / count);
        float meanDerivative = (float) (derivativeSum / Math.max(1, count - 1));
        return new Features(
                peak,
                rms,
                peak / Math.max(1f, rms),
                meanDerivative / Math.max(1f, rms),
                zeroCrossings / (float) Math.max(1, count - 1));
    }

    private void postError(String message) {
        mainHandler.post(() -> listener.onDetectorError(message));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
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

        Features(float peak, float rms, float crestFactor,
                 float derivativeRatio, float zeroCrossingRate) {
            this.peak = peak;
            this.rms = rms;
            this.crestFactor = crestFactor;
            this.derivativeRatio = derivativeRatio;
            this.zeroCrossingRate = zeroCrossingRate;
        }
    }
}
