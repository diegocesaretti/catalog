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

    // Deliberately small ranges: soft wrist movements should cross most of the goal.
    private static final float HORIZONTAL_RANGE_RAD = (float) Math.toRadians(9.0);
    private static final float VERTICAL_RANGE_RAD = (float) Math.toRadians(15.0);

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
    private float latestRoll;
    private float centerYaw;
    private float centerPitch;
    private float centerRoll;
    private float filteredX;
    private float filteredY;
    private float previousYaw;
    private float previousPitch;
    private float previousRoll;
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
        centerRoll = latestRoll;
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
            SensorManager.getOrientation(rotationMatrix, orientation);
            latestYaw = orientation[0];
            latestPitch = orientation[1];
            latestRoll = orientation[2];
            long nowMs = SystemClock.elapsedRealtime();

            if (!hasSample) {
                hasSample = true;
                firstSampleTimestampMs = nowMs;
                previousTimestampMs = nowMs;
                previousYaw = latestYaw;
                previousPitch = latestPitch;
                previousRoll = latestRoll;
                centerYaw = latestYaw;
                centerPitch = latestPitch;
                centerRoll = latestRoll;
                return;
            }

            float dt = Math.max(0.008f, (nowMs - previousTimestampMs) / 1000f);
            float yawSpeed = Math.abs(shortestAngle(latestYaw - previousYaw)) / dt;
            float pitchSpeed = Math.abs(latestPitch - previousPitch) / dt;
            float rollSpeed = Math.abs(shortestAngle(latestRoll - previousRoll)) / dt;
            motionIntensity += 0.14f * ((yawSpeed + pitchSpeed + rollSpeed) - motionIntensity);
            previousTimestampMs = nowMs;
            previousYaw = latestYaw;
            previousPitch = latestPitch;
            previousRoll = latestRoll;

            if (!calibrated && nowMs - firstSampleTimestampMs > 250L) {
                calibrate();
            }
            if (!calibrated) {
                return;
            }

            int setting = GamePreferences.getAimSensitivity(context);
            float horizontalGain = 1.35f + (setting - 1) * (2.65f / 9f);
            float verticalGain = 0.85f + (setting - 1) * (1.25f / 9f);
            float deltaYaw = shortestAngle(latestYaw - centerYaw);
            float deltaPitch = latestPitch - centerPitch;
            float deltaRoll = shortestAngle(latestRoll - centerRoll);

            // Roll is the dominant left/right movement on a worn watch; yaw is blended in
            // so horizontal aim still responds if the watch is held at a different angle.
            float horizontalMotion = deltaRoll * 0.88f + deltaYaw * 0.42f;
            float targetX = clamp(horizontalMotion / HORIZONTAL_RANGE_RAD * horizontalGain, -1f, 1f);
            float targetY = clamp(deltaPitch / VERTICAL_RANGE_RAD * verticalGain, -1f, 1f);
            if (!GamePreferences.isInvertVertical(context)) {
                targetY = -targetY;
            }

            // Faster response, with just enough smoothing to avoid jitter.
            filteredX += 0.34f * (targetX - filteredX);
            filteredY += 0.24f * (targetY - filteredY);
            listener.onAimChanged(filteredX, filteredY, motionIntensity);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
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
