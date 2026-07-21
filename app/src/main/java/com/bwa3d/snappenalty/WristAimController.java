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

    private static final long AUTO_CALIBRATION_DELAY_MS = 320L;
    private static final long GAME_SENSOR_STALE_NS = 300_000_000L;

    private final Context context;
    private final SensorManager sensorManager;
    private final Sensor gameRotationSensor;
    private final Sensor rotationSensor;
    private final Sensor gyroscopeSensor;
    private final Listener listener;

    private final float[] currentMatrix = new float[9];
    private final float[] gameMatrix = new float[9];
    private final float[] absoluteMatrix = new float[9];
    private final float[] centerMatrix = new float[9];
    private final float[] centerTranspose = new float[9];
    private final float[] relativeMatrix = new float[9];
    private final float[] relativeOrientation = new float[3];
    private final float[] gyroBias = new float[3];

    private boolean running;
    private boolean hasGameSample;
    private boolean hasAbsoluteSample;
    private boolean hasOrientationSample;
    private boolean calibrated;
    private long latestGameTimestampNs;
    private long firstOrientationTimestampMs;
    private float filteredX;
    private float filteredY;
    private float motionIntensity;

    public WristAimController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        SensorManager manager = null;
        Sensor game = null;
        Sensor absolute = null;
        Sensor gyro = null;
        try {
            manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (manager != null) {
                game = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
                absolute = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            }
        } catch (Throwable ignored) {
            manager = null;
        }
        sensorManager = manager;
        gameRotationSensor = game;
        rotationSensor = absolute;
        gyroscopeSensor = gyro;
    }

    public boolean isAvailable() {
        return sensorManager != null && (gameRotationSensor != null || rotationSensor != null);
    }

    public void start() {
        if (running || sensorManager == null || !isAvailable()) return;
        boolean registered = false;
        try {
            if (gameRotationSensor != null) {
                registered |= sensorManager.registerListener(
                        this, gameRotationSensor, SensorManager.SENSOR_DELAY_GAME);
            }
            if (rotationSensor != null) {
                registered |= sensorManager.registerListener(
                        this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
            }
            if (gyroscopeSensor != null) {
                sensorManager.registerListener(
                        this, gyroscopeSensor, SensorManager.SENSOR_DELAY_GAME);
            }
        } catch (Throwable ignored) {
            try {
                sensorManager.unregisterListener(this);
            } catch (Throwable ignoredAgain) {
            }
            registered = false;
        }
        running = registered;
    }

    public void stop() {
        if (sensorManager == null) return;
        try {
            sensorManager.unregisterListener(this);
        } catch (Throwable ignored) {
        }
        running = false;
    }

    public void calibrate() {
        if (!hasOrientationSample) {
            calibrated = false;
            return;
        }
        System.arraycopy(currentMatrix, 0, centerMatrix, 0, 9);
        transpose3x3(centerMatrix, centerTranspose);
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
        if (event == null || event.sensor == null || event.values == null) return;
        try {
            int type = event.sensor.getType();
            if (type == Sensor.TYPE_GYROSCOPE) {
                handleGyroscope(event.values);
                return;
            }
            if (type != Sensor.TYPE_GAME_ROTATION_VECTOR
                    && type != Sensor.TYPE_ROTATION_VECTOR) {
                return;
            }
            if (event.values.length < 3) return;

            if (type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(gameMatrix, event.values);
                hasGameSample = true;
                latestGameTimestampNs = event.timestamp;
            } else {
                SensorManager.getRotationMatrixFromVector(absoluteMatrix, event.values);
                hasAbsoluteSample = true;
            }

            boolean useGame = hasGameSample
                    && (type == Sensor.TYPE_GAME_ROTATION_VECTOR
                    || event.timestamp - latestGameTimestampNs < GAME_SENSOR_STALE_NS);
            if (useGame) {
                System.arraycopy(gameMatrix, 0, currentMatrix, 0, 9);
            } else if (hasAbsoluteSample) {
                System.arraycopy(absoluteMatrix, 0, currentMatrix, 0, 9);
            } else {
                return;
            }

            long nowMs = SystemClock.elapsedRealtime();
            if (!hasOrientationSample) {
                hasOrientationSample = true;
                firstOrientationTimestampMs = nowMs;
                System.arraycopy(currentMatrix, 0, centerMatrix, 0, 9);
                transpose3x3(centerMatrix, centerTranspose);
                return;
            }
            if (!calibrated && nowMs - firstOrientationTimestampMs >= AUTO_CALIBRATION_DELAY_MS) {
                calibrate();
            }
            if (!calibrated) return;

            multiply3x3(centerTranspose, currentMatrix, relativeMatrix);
            SensorManager.getOrientation(relativeMatrix, relativeOrientation);

            float yaw = wrapAngle(relativeOrientation[0]);
            float pitch = wrapAngle(relativeOrientation[1]);
            float roll = wrapAngle(relativeOrientation[2]);

            float horizontal;
            if (Math.abs(roll) >= Math.abs(yaw) * 0.72f) {
                horizontal = roll;
                if (sameDirection(roll, yaw)) horizontal += yaw * 0.18f;
            } else {
                horizontal = yaw;
                if (sameDirection(yaw, roll)) horizontal += roll * 0.18f;
            }

            int setting = GamePreferences.getAimSensitivity(context);
            float t = (setting - 1) / 9f;
            float horizontalRange = (float) Math.toRadians(28f - 21.5f * t);
            float verticalRange = (float) Math.toRadians(24f - 14f * t);
            float targetX = clamp(horizontal / horizontalRange, -1f, 1f);
            float targetY = clamp(pitch / verticalRange, -1f, 1f);
            if (!GamePreferences.isInvertVertical(context)) targetY = -targetY;

            float horizontalFollow = 0.24f + 0.18f * t;
            float verticalFollow = 0.20f + 0.14f * t;
            filteredX += horizontalFollow * (targetX - filteredX);
            filteredY += verticalFollow * (targetY - filteredY);

            listener.onAimChanged(filteredX, filteredY, motionIntensity);
        } catch (Throwable ignored) {
        }
    }

    private void handleGyroscope(float[] values) {
        if (values.length < 3) return;
        float x = values[0] - gyroBias[0];
        float y = values[1] - gyroBias[1];
        float z = values[2] - gyroBias[2];
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);

        if (magnitude < 0.12f) {
            gyroBias[0] += 0.003f * (values[0] - gyroBias[0]);
            gyroBias[1] += 0.003f * (values[1] - gyroBias[1]);
            gyroBias[2] += 0.003f * (values[2] - gyroBias[2]);
        }
        motionIntensity += 0.18f * (magnitude - motionIntensity);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static void transpose3x3(float[] input, float[] output) {
        output[0] = input[0];
        output[1] = input[3];
        output[2] = input[6];
        output[3] = input[1];
        output[4] = input[4];
        output[5] = input[7];
        output[6] = input[2];
        output[7] = input[5];
        output[8] = input[8];
    }

    private static void multiply3x3(float[] a, float[] b, float[] out) {
        for (int row = 0; row < 3; row++) {
            int r = row * 3;
            for (int col = 0; col < 3; col++) {
                out[r + col] = a[r] * b[col]
                        + a[r + 1] * b[3 + col]
                        + a[r + 2] * b[6 + col];
            }
        }
    }

    private static boolean sameDirection(float a, float b) {
        return a != 0f && b != 0f && Math.signum(a) == Math.signum(b);
    }

    private static float wrapAngle(float angle) {
        while (angle > Math.PI) angle -= (float) (Math.PI * 2.0);
        while (angle < -Math.PI) angle += (float) (Math.PI * 2.0);
        return angle;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
