package com.bwa3d.snappenalty;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/**
 * Relative pointing controller derived from the orientation approach used by Maestro Wear.
 * Instead of subtracting Euler angles, it compares the watch screen-normal vector against
 * the calibrated right/up/forward basis. That avoids wraparound and gimbal issues and makes
 * soft wrist tilts usable in any initial arm orientation.
 */
public final class WristAimController implements SensorEventListener {
    public interface Listener {
        void onAimChanged(float normalizedX, float normalizedY, float motionIntensity);
    }

    private final Context context;
    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final Listener listener;
    private final float[] rotationMatrix = new float[9];

    private final float[] currentForward = new float[3];
    private final float[] previousForward = new float[3];
    private final float[] centerForward = new float[3];
    private final float[] centerRight = new float[3];
    private final float[] centerUp = new float[3];

    private boolean running;
    private boolean hasSample;
    private boolean calibrated;
    private float filteredX;
    private float filteredY;
    private float motionIntensity;
    private long firstSampleMs;
    private long previousSampleMs;

    public WristAimController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;

        SensorManager manager = null;
        Sensor selected = null;
        try {
            manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (manager != null) {
                selected = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
                if (selected == null) {
                    selected = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                }
            }
        } catch (Throwable ignored) {
            manager = null;
            selected = null;
        }
        sensorManager = manager;
        rotationSensor = selected;
    }

    public boolean isAvailable() {
        return sensorManager != null && rotationSensor != null;
    }

    public void start() {
        if (running || !isAvailable()) {
            return;
        }
        try {
            running = sensorManager.registerListener(
                    this,
                    rotationSensor,
                    SensorManager.SENSOR_DELAY_GAME
            );
        } catch (Throwable ignored) {
            running = false;
        }
    }

    public void stop() {
        if (!running || sensorManager == null) {
            return;
        }
        try {
            sensorManager.unregisterListener(this);
        } catch (Throwable ignored) {
        } finally {
            running = false;
        }
    }

    public void calibrate() {
        if (!hasSample) {
            calibrated = false;
            return;
        }
        // Columns of Android's rotation matrix are the device X, Y and Z axes in world space.
        copyColumn(rotationMatrix, 2, centerForward);
        copyColumn(rotationMatrix, 0, centerRight);
        copyColumn(rotationMatrix, 1, centerUp);
        normalize(centerForward);
        normalize(centerRight);
        normalize(centerUp);
        filteredX = 0f;
        filteredY = 0f;
        calibrated = true;
        try {
            listener.onAimChanged(0f, 0f, motionIntensity);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        try {
            if (event == null || event.sensor == null || event.values == null || event.values.length < 3) {
                return;
            }
            int type = event.sensor.getType();
            if (type != Sensor.TYPE_GAME_ROTATION_VECTOR && type != Sensor.TYPE_ROTATION_VECTOR) {
                return;
            }

            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            copyColumn(rotationMatrix, 2, currentForward);
            normalize(currentForward);
            long now = SystemClock.elapsedRealtime();

            if (!hasSample) {
                hasSample = true;
                firstSampleMs = now;
                previousSampleMs = now;
                copy(currentForward, previousForward);
                return;
            }

            float dt = Math.max(0.008f, Math.min(0.25f, (now - previousSampleMs) / 1000f));
            float angularStep = safeAcos(clamp(dot(currentForward, previousForward), -1f, 1f));
            float angularSpeed = angularStep / dt;
            motionIntensity += 0.16f * (angularSpeed - motionIntensity);
            previousSampleMs = now;
            copy(currentForward, previousForward);

            if (!calibrated && now - firstSampleMs >= 420L) {
                calibrate();
            }
            if (!calibrated) {
                return;
            }

            float forwardProjection = dot(currentForward, centerForward);
            float horizontalAngle = (float) Math.atan2(
                    dot(currentForward, centerRight),
                    forwardProjection
            );
            float verticalAngle = (float) Math.atan2(
                    dot(currentForward, centerUp),
                    forwardProjection
            );

            int setting = GamePreferences.getAimSensitivity(context);
            float fraction = (setting - 1f) / 19f;
            float horizontalRange = (float) Math.toRadians(24f - 19.5f * fraction);
            float verticalRange = (float) Math.toRadians(28f - 18f * fraction);

            float targetX = shape(horizontalAngle / horizontalRange, 0.80f);
            float targetY = shape(verticalAngle / verticalRange, 0.92f);
            if (!GamePreferences.isInvertVertical(context)) {
                targetY = -targetY;
            }
            targetX = clamp(targetX, -1f, 1f);
            targetY = clamp(targetY, -1f, 1f);

            // Horizontal response is deliberately quicker because that was the weak axis on-watch.
            filteredX += 0.46f * (targetX - filteredX);
            filteredY += 0.31f * (targetY - filteredY);
            listener.onAimChanged(filteredX, filteredY, motionIntensity);
        } catch (Throwable ignored) {
            // A malformed sample must never close the game.
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static void copyColumn(float[] matrix, int column, float[] output) {
        output[0] = matrix[column];
        output[1] = matrix[3 + column];
        output[2] = matrix[6 + column];
    }

    private static void copy(float[] source, float[] destination) {
        destination[0] = source[0];
        destination[1] = source[1];
        destination[2] = source[2];
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static void normalize(float[] vector) {
        float length = (float) Math.sqrt(dot(vector, vector));
        if (length > 0.00001f) {
            vector[0] /= length;
            vector[1] /= length;
            vector[2] /= length;
        }
    }

    private static float shape(float value, float exponent) {
        float absolute = Math.abs(value);
        if (absolute < 0.006f) {
            return 0f;
        }
        return Math.copySign((float) Math.pow(absolute, exponent), value);
    }

    private static float safeAcos(float value) {
        return (float) Math.acos(clamp(value, -1f, 1f));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
