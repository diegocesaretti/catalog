package com.bwa3d.snappenalty;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/**
 * Relative wrist tracker based on the same approach used by Maestro:
 * game-rotation-vector orientation, a calibrated relative matrix, and gyroscope
 * motion for responsiveness/stability diagnostics.
 */
public final class MaestroAimControllerV050 implements SensorEventListener {
    public interface Listener {
        void onAimChanged(float normalizedX, float normalizedY, float motionIntensity);
    }

    private static final float HORIZONTAL_RANGE_RAD = (float) Math.toRadians(11.0);
    private static final float VERTICAL_RANGE_RAD = (float) Math.toRadians(18.0);

    private final Context context;
    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final Sensor gyroSensor;
    private final Listener listener;

    private final float[] currentMatrix = new float[9];
    private final float[] centerMatrix = new float[9];
    private final float[] centerTranspose = new float[9];
    private final float[] relativeMatrix = new float[9];
    private final float[] relativeOrientation = new float[3];

    private boolean running;
    private boolean hasRotation;
    private boolean calibrated;
    private long firstSampleMs;
    private long lastGyroNs;
    private float filteredX;
    private float filteredY;
    private float gyroMotion;
    private float gyroHorizontalPrediction;
    private float gyroVerticalPrediction;

    public MaestroAimControllerV050(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        sensorManager = manager;
        Sensor rotation = null;
        Sensor gyro = null;
        if (manager != null) {
            rotation = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (rotation == null) {
                rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            }
            gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        rotationSensor = rotation;
        gyroSensor = gyro;
    }

    public boolean isAvailable() {
        return sensorManager != null && rotationSensor != null;
    }

    public void start() {
        if (running || !isAvailable()) {
            return;
        }
        running = sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        if (gyroSensor != null) {
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        running = false;
        lastGyroNs = 0L;
    }

    public void calibrate() {
        if (!hasRotation) {
            calibrated = false;
            return;
        }
        System.arraycopy(currentMatrix, 0, centerMatrix, 0, 9);
        transpose3x3(centerMatrix, centerTranspose);
        filteredX = 0f;
        filteredY = 0f;
        gyroHorizontalPrediction = 0f;
        gyroVerticalPrediction = 0f;
        calibrated = true;
        listener.onAimChanged(0f, 0f, -1f);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.values == null) {
            return;
        }
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_GYROSCOPE) {
            updateGyroscope(event);
            return;
        }
        if (type != Sensor.TYPE_GAME_ROTATION_VECTOR && type != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }
        if (event.values.length < 3) {
            return;
        }
        try {
            SensorManager.getRotationMatrixFromVector(currentMatrix, event.values);
            long nowMs = SystemClock.elapsedRealtime();
            if (!hasRotation) {
                hasRotation = true;
                firstSampleMs = nowMs;
                System.arraycopy(currentMatrix, 0, centerMatrix, 0, 9);
                transpose3x3(centerMatrix, centerTranspose);
                return;
            }
            if (!calibrated && nowMs - firstSampleMs >= 320L) {
                calibrate();
            }
            if (!calibrated) {
                return;
            }

            multiply3x3(centerTranspose, currentMatrix, relativeMatrix);
            SensorManager.getOrientation(relativeMatrix, relativeOrientation);
            float relativeYaw = shortestAngle(relativeOrientation[0]);
            float relativePitch = shortestAngle(relativeOrientation[1]);
            float relativeRoll = shortestAngle(relativeOrientation[2]);

            // On a watch, a soft left/right wrist movement can appear mostly as roll,
            // mostly as yaw, or a mixture depending on arm posture. Blending both makes
            // horizontal aiming reliable without forcing one specific pose.
            float horizontalAngle = relativeRoll * 0.78f + relativeYaw * 0.58f;
            float verticalAngle = relativePitch;

            int setting = GamePreferences.getAimSensitivity(context);
            float sensitivity = 0.62f + (setting - 1) * (1.98f / 9f);
            float targetX = clamp(horizontalAngle / HORIZONTAL_RANGE_RAD * sensitivity
                    + gyroHorizontalPrediction, -1f, 1f);
            float targetY = clamp(verticalAngle / VERTICAL_RANGE_RAD * sensitivity
                    + gyroVerticalPrediction, -1f, 1f);
            if (!GamePreferences.isInvertVertical(context)) {
                targetY = -targetY;
            }

            // Faster response than the old filter, while retaining enough damping for
            // a small round display. Soft movements should visibly move the reticle.
            float smoothing = 0.43f;
            filteredX += smoothing * (targetX - filteredX);
            filteredY += smoothing * (targetY - filteredY);
            if (Math.abs(filteredX) < 0.006f) filteredX = 0f;
            if (Math.abs(filteredY) < 0.006f) filteredY = 0f;

            gyroHorizontalPrediction *= 0.72f;
            gyroVerticalPrediction *= 0.72f;
            listener.onAimChanged(filteredX, filteredY, gyroMotion);
        } catch (Throwable ignored) {
            // Unsupported samples must never close the game.
        }
    }

    private void updateGyroscope(SensorEvent event) {
        if (event.values.length < 3) {
            return;
        }
        long timestamp = event.timestamp;
        if (lastGyroNs != 0L) {
            float dt = clamp((timestamp - lastGyroNs) / 1_000_000_000f, 0.001f, 0.05f);
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
            gyroMotion += 0.22f * (magnitude - gyroMotion);

            // Small predictive component reduces perceived latency. Both likely watch
            // axes are included because band orientation differs between wrists.
            gyroHorizontalPrediction += (z * 0.68f + y * 0.28f) * dt * 0.55f;
            gyroVerticalPrediction += x * dt * 0.28f;
            gyroHorizontalPrediction = clamp(gyroHorizontalPrediction, -0.18f, 0.18f);
            gyroVerticalPrediction = clamp(gyroVerticalPrediction, -0.12f, 0.12f);
        }
        lastGyroNs = timestamp;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static void transpose3x3(float[] in, float[] out) {
        out[0] = in[0]; out[1] = in[3]; out[2] = in[6];
        out[3] = in[1]; out[4] = in[4]; out[5] = in[7];
        out[6] = in[2]; out[7] = in[5]; out[8] = in[8];
    }

    private static void multiply3x3(float[] a, float[] b, float[] out) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                out[row * 3 + col] =
                        a[row * 3] * b[col]
                                + a[row * 3 + 1] * b[3 + col]
                                + a[row * 3 + 2] * b[6 + col];
            }
        }
    }

    private static float shortestAngle(float value) {
        while (value > Math.PI) value -= (float) (Math.PI * 2.0);
        while (value < -Math.PI) value += (float) (Math.PI * 2.0);
        return value;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
