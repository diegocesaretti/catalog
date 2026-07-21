package com.bwa3d.snappenalty;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
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

    private static final int SAMPLE_RATE = 22050;
    private static final int FRAME_SAMPLES = 512;
    private static final long COOLDOWN_MS = 330L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread worker;
    private AudioRecord audioRecord;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl gainControl;
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
        if (running.get()) {
            return true;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            postError("Microphone permission is not granted");
            return false;
        }

        int minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minimum <= 0) {
            postError("Microphone buffer unavailable");
            return false;
        }
        int bufferBytes = Math.max(minimum, FRAME_SAMPLES * 8);

        audioRecord = createRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferBytes);
        if (audioRecord == null) {
            audioRecord = createRecord(MediaRecorder.AudioSource.MIC, bufferBytes);
        }
        if (audioRecord == null) {
            postError("Could not open the watch microphone");
            return false;
        }

        attachAudioEffects(audioRecord.getAudioSessionId());
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
        if (localWorker != null) {
            try {
                localWorker.join(500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        releaseEffects();
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        worker = null;
    }

    private AudioRecord createRecord(int source, int bufferBytes) {
        try {
            AudioRecord record = new AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
            );
            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                return record;
            }
            record.release();
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private void attachAudioEffects(int audioSessionId) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId);
                if (echoCanceler != null) {
                    echoCanceler.setEnabled(true);
                }
            }
        } catch (Throwable ignored) {
            echoCanceler = null;
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId);
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                }
            }
        } catch (Throwable ignored) {
            noiseSuppressor = null;
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(audioSessionId);
                if (gainControl != null) {
                    // AGC smears short impulses on some watches, so keep it disabled.
                    gainControl.setEnabled(false);
                }
            }
        } catch (Throwable ignored) {
            gainControl = null;
        }
    }

    private void releaseEffects() {
        try {
            if (echoCanceler != null) {
                echoCanceler.release();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (noiseSuppressor != null) {
                noiseSuppressor.release();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (gainControl != null) {
                gainControl.release();
            }
        } catch (Throwable ignored) {
        }
        echoCanceler = null;
        noiseSuppressor = null;
        gainControl = null;
    }

    private void captureLoop() {
        short[] frame = new short[FRAME_SAMPLES];
        float noiseRms = 110f;
        float previousRms = noiseRms;
        float smoothedLevel = 0f;
        long lastSnapMs = 0L;

        try {
            audioRecord.startRecording();
            while (running.get()) {
                int count = audioRecord.read(frame, 0, frame.length, AudioRecord.READ_BLOCKING);
                if (count <= 32) {
                    continue;
                }

                Features features = calculateFeatures(frame, count);
                float displayLevel = clamp(features.peak / 7000f, 0f, 1f);
                smoothedLevel += 0.24f * (displayLevel - smoothedLevel);
                float finalLevel = smoothedLevel;
                mainHandler.post(() -> listener.onAudioLevel(finalLevel));

                int localSensitivity = sensitivity;
                float amount = (localSensitivity - 1f) / 19f;
                float peakFloor = lerp(2600f, 320f, amount);
                float relativePeak = lerp(7.2f, 1.8f, amount);
                float derivativeFloor = lerp(1.10f, 0.28f, amount);
                float crestFloor = lerp(3.0f, 1.18f, amount);
                float zeroCrossFloor = lerp(0.075f, 0.012f, amount);
                float transientRatio = lerp(4.5f, 1.35f, amount);

                float localBaseline = Math.max(55f, Math.min(noiseRms, previousRms * 1.7f));
                boolean strongPeak = features.peak
                        > Math.max(peakFloor, localBaseline * relativePeak);
                boolean transientEnergy = features.rms
                        > Math.max(70f, localBaseline * transientRatio);
                boolean impulsive = features.derivativeRatio > derivativeFloor
                        && features.crestFactor > crestFloor;
                boolean brightEnough = features.zeroCrossingRate > zeroCrossFloor;
                boolean verySharp = features.peak > peakFloor * 1.35f
                        && features.derivativeRatio > derivativeFloor * 0.72f
                        && features.crestFactor > crestFloor * 0.86f;

                long nowMs = SystemClock.elapsedRealtime();
                boolean detected = strongPeak
                        && transientEnergy
                        && brightEnough
                        && (impulsive || (localSensitivity >= 14 && verySharp))
                        && nowMs - lastSnapMs >= COOLDOWN_MS;

                if (detected) {
                    lastSnapMs = nowMs;
                    mainHandler.post(listener::onSnapDetected);
                    // Do not teach the adaptive floor using the snap itself.
                } else {
                    float updateRate = features.rms < noiseRms * 2.6f ? 0.035f : 0.008f;
                    noiseRms += updateRate * (features.rms - noiseRms);
                    noiseRms = clamp(noiseRms, 45f, 5000f);
                }
                previousRms = features.rms;
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

        for (int index = 0; index < count; index++) {
            int value = samples[index];
            int absolute = Math.abs(value);
            peak = Math.max(peak, absolute);
            sumSquares += (double) value * value;
            if (index > 0) {
                derivativeSum += Math.abs(value - previous);
                if ((value >= 0 && previous < 0) || (value < 0 && previous >= 0)) {
                    zeroCrossings++;
                }
            }
            previous = samples[index];
        }

        float rms = (float) Math.sqrt(sumSquares / count);
        float derivative = (float) (derivativeSum / Math.max(1, count - 1));
        float crest = peak / Math.max(1f, rms);
        float derivativeRatio = derivative / Math.max(1f, rms);
        float zeroCrossingRate = zeroCrossings / (float) Math.max(1, count - 1);
        return new Features(peak, rms, crest, derivativeRatio, zeroCrossingRate);
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

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static final class Features {
        final float peak;
        final float rms;
        final float crestFactor;
        final float derivativeRatio;
        final float zeroCrossingRate;

        Features(float peak, float rms, float crestFactor, float derivativeRatio,
                 float zeroCrossingRate) {
            this.peak = peak;
            this.rms = rms;
            this.crestFactor = crestFactor;
            this.derivativeRatio = derivativeRatio;
            this.zeroCrossingRate = zeroCrossingRate;
        }
    }
}
