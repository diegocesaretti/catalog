package com.bwa3d.snappenalty;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

public final class WristAimController implements SensorEventListener {
    public interface Listener {
        void onAimChanged(float normalizedX, float normalizedY, float motionIntensity);
    }

    private static final float HORIZONTAL_RANGE_RAD = (float) Math.toRadians(28.0);
    private static final float VERTICAL_RANGE_RAD = (float) Math.toRadians(22.0);

    private final Context context;
    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final Listener listener;
    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];

    private boolean running;
    private boolean calibrated;
    private boolean hasSample;
    private float latestYaw;
    private float latestPitch;
    private float centerYaw;
    private float centerPitch;
    private float filteredX;
    private float filteredY;
    private float previousYaw;
    private float previousPitch;
    private float motionIntensity;
    private long previousTimestampMs;
    private long firstSampleTimestampMs;

    public WristAimController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;

        SensorManager manager = null;
        Sensor selectedSensor = null;
        try {
            manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (manager != null) {
                selectedSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
                if (selectedSensor == null) {
                    selectedSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                }
            }
        } catch (Throwable ignored) {
            manager = null;
            selectedSensor = null;
        }
        sensorManager = manager;
        rotationSensor = selectedSensor;
    }

    public boolean isAvailable() {
        return sensorManager != null && rotationSensor != null;
    }

    public void start() {
        if (running || sensorManager == null || rotationSensor == null) {
            return;
        }
        try {
            running = sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
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
            // Sensor shutdown should not terminate the game.
        } finally {
            running = false;
        }
    }

    public void calibrate() {
        if (!hasSample) {
            calibrated = false;
            return;
        }
        centerYaw = latestYaw;
        centerPitch = latestPitch;
        filteredX = 0f;
        filteredY = 0f;
        calibrated = true;
        try {
            listener.onAimChanged(0f, 0f, motionIntensity);
        } catch (Throwable ignored) {
            // Ignore a stale view callback during an activity transition.
        }
    }

    public float getMotionIntensity() {
        return motionIntensity;
    }

    public boolean isCalibrated() {
        return calibrated;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        try {
            if (event == null || event.sensor == null) {
                return;
            }
            if (event.sensor.getType() != Sensor.TYPE_GAME_ROTATION_VECTOR
                    && event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
                return;
            }
            if (event.values == null || event.values.length < 3) {
                return;
            }

            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientation);

            latestYaw = orientation[0];
            latestPitch = orientation[1];
            long nowMs = SystemClock.elapsedRealtime();

            if (!hasSample) {
                hasSample = true;
                firstSampleTimestampMs = nowMs;
                previousTimestampMs = nowMs;
                previousYaw = latestYaw;
                previousPitch = latestPitch;
                centerYaw = latestYaw;
                centerPitch = latestPitch;
                filteredX = 0f;
                filteredY = 0f;
                return;
            }

            float dt = Math.max(0.008f, (nowMs - previousTimestampMs) / 1000f);
            float yawSpeed = Math.abs(shortestAngle(latestYaw - previousYaw)) / dt;
            float pitchSpeed = Math.abs(latestPitch - previousPitch) / dt;
            float rawMotion = yawSpeed + pitchSpeed;
            motionIntensity += 0.16f * (rawMotion - motionIntensity);

            previousTimestampMs = nowMs;
            previousYaw = latestYaw;
            previousPitch = latestPitch;

            if (!calibrated && nowMs - firstSampleTimestampMs > 450L) {
                calibrate();
            }
            if (!calibrated) {
                return;
            }

            float sensitivity = 0.55f + (GamePreferences.getAimSensitivity(context) - 1) * (1.45f / 9f);
            float deltaYaw = shortestAngle(latestYaw - centerYaw);
            float deltaPitch = latestPitch - centerPitch;

            float targetX = clamp(deltaYaw / HORIZONTAL_RANGE_RAD * sensitivity, -1f, 1f);
            float targetY = clamp(deltaPitch / VERTICAL_RANGE_RAD * sensitivity, -1f, 1f);
            if (!GamePreferences.isInvertVertical(context)) {
                targetY = -targetY;
            }

            float smoothing = 0.19f;
            filteredX += smoothing * (targetX - filteredX);
            filteredY += smoothing * (targetY - filteredY);
            listener.onAimChanged(filteredX, filteredY, motionIntensity);
        } catch (Throwable ignored) {
            // A malformed or unsupported sensor sample must not crash the activity.
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Relative orientation is sufficient for aiming.
    }

    private static float shortestAngle(float angle) {
        while (angle > Math.PI) {
            angle -= (float) (Math.PI * 2.0);
        }
        while (angle < -Math.PI) {
            angle += (float) (Math.PI * 2.0);
        }
        return angle;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
