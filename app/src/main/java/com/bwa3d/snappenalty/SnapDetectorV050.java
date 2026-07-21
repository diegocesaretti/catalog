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

public final class SnapDetectorV050 {
    public interface Listener {
        void onSnapDetected();
        void onAudioLevel(float level01);
        void onDetectorError(String message);
    }

    private static final int SAMPLE_RATE = 22_050;
    private static final int FRAME_SAMPLES = 512;
    private static final long COOLDOWN_MS = 330L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile int sensitivity;
    private AudioRecord audioRecord;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private Thread worker;

    public SnapDetectorV050(Context context, int sensitivity, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        setSensitivity(sensitivity);
    }

    public void setSensitivity(int sensitivity) {
        this.sensitivity = clamp(sensitivity, 1, 20);
    }

    public boolean start() {
        if (running.get()) return true;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            postError("Microphone permission missing");
            return false;
        }

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            postError("Microphone buffer unavailable");
            return false;
        }

        try {
            int bufferBytes = Math.max(minBuffer, FRAME_SAMPLES * 8);
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                audioRecord = null;
                postError("Microphone initialization failed");
                return false;
            }
            int session = audioRecord.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(session);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(session);
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }
            running.set(true);
            worker = new Thread(this::captureLoop, "SnapPenalty-SnapV050");
            worker.start();
            return true;
        } catch (Throwable error) {
            releaseAudio();
            postError("Could not open watch microphone");
            return false;
        }
    }

    public void stop() {
        running.set(false);
        AudioRecord record = audioRecord;
        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) { }
        }
        Thread local = worker;
        if (local != null) {
            try { local.join(450L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        releaseAudio();
        mainHandler.post(() -> listener.onAudioLevel(0f));
    }

    private void captureLoop() {
        short[] samples = new short[FRAME_SAMPLES];
        float noiseRms = 100f;
        float previousPeak = 120f;
        float previousRms = 100f;
        float smoothedLevel = 0f;
        long lastSnapMs = 0L;

        try {
            audioRecord.startRecording();
            while (running.get()) {
                int count = audioRecord.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (count < 32) continue;

                Features f = features(samples, count);
                smoothedLevel += 0.30f * (clamp(f.peak / 7_000f, 0f, 1f) - smoothedLevel);
                final float level = smoothedLevel;
                mainHandler.post(() -> listener.onAudioLevel(level));

                float t = (sensitivity - 1) / 19f;
                float absolutePeak = lerp(3_400f, 430f, t);
                float relativePeak = lerp(7.2f, 1.65f, t);
                float crestThreshold = lerp(3.0f, 1.42f, t);
                float derivativeThreshold = lerp(1.12f, 0.42f, t);
                float attackThreshold = lerp(3.4f, 1.22f, t);
                float brightThreshold = lerp(0.055f, 0.018f, t);

                float attackPeak = f.peak / Math.max(80f, previousPeak);
                float attackRms = f.rms / Math.max(60f, previousRms);
                boolean strong = f.peak >= Math.max(absolutePeak, noiseRms * relativePeak);
                boolean impulsive = f.crest >= crestThreshold
                        && f.derivativeRatio >= derivativeThreshold;
                boolean sudden = Math.max(attackPeak, attackRms) >= attackThreshold;
                boolean bright = f.zeroCrossRate >= brightThreshold;

                boolean highSensitivityFallback = sensitivity >= 16
                        && strong
                        && sudden
                        && f.derivativeRatio >= derivativeThreshold * 0.82f;
                boolean detected = (strong && impulsive && sudden && bright)
                        || highSensitivityFallback;

                long now = SystemClock.elapsedRealtime();
                if (detected && now - lastSnapMs >= COOLDOWN_MS) {
                    lastSnapMs = now;
                    mainHandler.post(listener::onSnapDetected);
                } else if (f.rms < noiseRms * 2.3f) {
                    noiseRms += 0.025f * (f.rms - noiseRms);
                    noiseRms = clamp(noiseRms, 45f, 4_500f);
                }

                previousPeak += 0.30f * (f.peak - previousPeak);
                previousRms += 0.24f * (f.rms - previousRms);
            }
        } catch (Throwable error) {
            if (running.get()) postError("Microphone capture stopped");
        } finally {
            running.set(false);
        }
    }

    private void releaseAudio() {
        if (echoCanceler != null) {
            try { echoCanceler.release(); } catch (Throwable ignored) { }
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try { noiseSuppressor.release(); } catch (Throwable ignored) { }
            noiseSuppressor = null;
        }
        if (audioRecord != null) {
            try { audioRecord.release(); } catch (Throwable ignored) { }
            audioRecord = null;
        }
    }

    private static Features features(short[] samples, int count) {
        double sumSquares = 0d;
        double derivativeSum = 0d;
        int peak = 0;
        int zeroCrossings = 0;
        int previous = samples[0];
        for (int i = 0; i < count; i++) {
            int value = samples[i];
            int absolute = Math.abs(value);
            if (absolute > peak) peak = absolute;
            sumSquares += (double) value * value;
            if (i > 0) {
                derivativeSum += Math.abs(value - previous);
                if ((value >= 0 && previous < 0) || (value < 0 && previous >= 0)) {
                    zeroCrossings++;
                }
            }
            previous = value;
        }
        float rms = (float) Math.sqrt(sumSquares / Math.max(1, count));
        float derivative = (float) (derivativeSum / Math.max(1, count - 1));
        return new Features(
                peak,
                rms,
                peak / Math.max(1f, rms),
                derivative / Math.max(1f, rms),
                zeroCrossings / (float) Math.max(1, count - 1));
    }

    private void postError(String message) {
        mainHandler.post(() -> listener.onDetectorError(message));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Features {
        final float peak;
        final float rms;
        final float crest;
        final float derivativeRatio;
        final float zeroCrossRate;

        Features(float peak, float rms, float crest, float derivativeRatio, float zeroCrossRate) {
            this.peak = peak;
            this.rms = rms;
            this.crest = crest;
            this.derivativeRatio = derivativeRatio;
            this.zeroCrossRate = zeroCrossRate;
        }
    }
}
