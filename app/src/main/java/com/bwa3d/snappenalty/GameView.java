package com.bwa3d.snappenalty;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;
import java.util.Random;

public final class GameView extends View {
    public interface Host {
        void openSettings();

        void recenterAim();
    }

    private enum State {
        READY,
        SHOOTING,
        RESULT,
        GAME_OVER
    }

    private static final int TOTAL_SHOTS = 5;
    private static final long SHOT_DURATION_MS = 880L;
    private static final long RESULT_DURATION_MS = 1_150L;

    private final Host host;
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF goalRect = new RectF();
    private final RectF aimRect = new RectF();
    private final RectF settingsHitRect = new RectF();
    private final RectF recenterHitRect = new RectF();

    private State state = State.READY;
    private float aimX;
    private float aimY;
    private float motionIntensity;
    private float audioLevel;
    private boolean microphoneListening;
    private String statusMessage = "MOVE WRIST • SNAP TO SHOOT";
    private long statusMessageUntilMs;

    private int shotsTaken;
    private int goals;
    private long stateStartedMs;
    private float shotTargetX;
    private float shotTargetY;
    private float keeperTargetX;
    private float keeperTargetY;
    private boolean currentShotSaved;
    private float touchDownX;
    private float touchDownY;

    public GameView(Context context, Host host) {
        super(context);
        this.host = host;
        setFocusable(true);
        setKeepScreenOn(true);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
    }

    public void setAim(float normalizedX, float normalizedY, float motionIntensity) {
        this.aimX = clamp(normalizedX, -1f, 1f);
        this.aimY = clamp(normalizedY, -1f, 1f);
        this.motionIntensity = motionIntensity;
        if (state == State.READY) {
            postInvalidateOnAnimation();
        }
    }

    public void setMicrophoneListening(boolean listening) {
        microphoneListening = listening;
        postInvalidateOnAnimation();
    }

    public void setAudioLevel(float level01) {
        audioLevel = clamp(level01, 0f, 1f);
        postInvalidateOnAnimation();
    }

    public void showTemporaryMessage(String message, long durationMs) {
        statusMessage = message;
        statusMessageUntilMs = SystemClock.elapsedRealtime() + durationMs;
        postInvalidateOnAnimation();
    }

    public void onSnapDetected() {
        if (state != State.READY) {
            return;
        }
        // A snap is allowed during mild movement, but a strong wrist swing is likely handling noise.
        if (motionIntensity > 5.8f) {
            showTemporaryMessage("HOLD THE WATCH STEADY", 900L);
            performHapticFeedback(HapticFeedbackConstants.REJECT);
            return;
        }
        shoot();
    }

    public void refreshPreferences() {
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        goalRect.set(width * 0.07f, height * 0.335f, width * 0.93f, height * 0.71f);
        aimRect.set(width * 0.145f, height * 0.39f, width * 0.855f, height * 0.655f);

        float controlSize = Math.min(width, height) * 0.20f;
        recenterHitRect.set(width * 0.08f, height * 0.08f, width * 0.08f + controlSize, height * 0.08f + controlSize);
        settingsHitRect.set(width * 0.92f - controlSize, height * 0.08f, width * 0.92f, height * 0.08f + controlSize);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long nowMs = SystemClock.elapsedRealtime();
        updateState(nowMs);
        drawBackground(canvas);
        drawVignette(canvas);
        drawGoalkeeper(canvas, nowMs);
        drawBall(canvas, nowMs);

        if (state == State.READY) {
            drawAimDot(canvas);
        }

        drawTopHud(canvas);
        drawControls(canvas);
        drawStatus(canvas, nowMs);

        if (state == State.SHOOTING || state == State.RESULT) {
            postInvalidateOnAnimation();
        }
    }

    private void drawBackground(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();

        // Sky and distant stadium.
        paint.setShader(new LinearGradient(
                0f,
                0f,
                0f,
                height * 0.58f,
                0xFF2378C8,
                0xFFBDE8FF,
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(0f, 0f, width, height * 0.58f, paint);
        paint.setShader(null);

        paint.setColor(0xFF263A43);
        Path stands = new Path();
        stands.moveTo(0f, height * 0.38f);
        stands.lineTo(width * 0.18f, height * 0.32f);
        stands.lineTo(width * 0.36f, height * 0.37f);
        stands.lineTo(width * 0.53f, height * 0.31f);
        stands.lineTo(width * 0.72f, height * 0.36f);
        stands.lineTo(width, height * 0.30f);
        stands.lineTo(width, height * 0.59f);
        stands.lineTo(0f, height * 0.59f);
        stands.close();
        canvas.drawPath(stands, paint);

        // Pitch with perspective bands.
        paint.setShader(new LinearGradient(
                0f,
                height * 0.53f,
                0f,
                height,
                0xFF44B94B,
                0xFF126D2B,
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(0f, height * 0.53f, width, height, paint);
        paint.setShader(null);
        for (int i = 0; i < 7; i++) {
            float top = height * 0.55f + i * height * 0.072f;
            paint.setColor((i & 1) == 0 ? 0x224CFF62 : 0x22123519);
            canvas.drawRect(0f, top, width, top + height * 0.036f, paint);
        }

        // Penalty spot and field line.
        strokePaint.setColor(0x99FFFFFF);
        strokePaint.setStrokeWidth(Math.max(1.5f, width * 0.006f));
        canvas.drawLine(0f, height * 0.765f, width, height * 0.765f, strokePaint);
        paint.setColor(0xCCFFFFFF);
        canvas.drawCircle(width * 0.5f, height * 0.805f, width * 0.009f, paint);

        // Goal shadow.
        paint.setColor(0x44000000);
        canvas.drawOval(new RectF(
                goalRect.left - width * 0.02f,
                goalRect.bottom - height * 0.015f,
                goalRect.right + width * 0.02f,
                goalRect.bottom + height * 0.055f
        ), paint);

        // Net backing.
        Path net = new Path();
        net.moveTo(goalRect.left, goalRect.top);
        net.lineTo(goalRect.right, goalRect.top);
        net.lineTo(goalRect.right - width * 0.035f, goalRect.bottom);
        net.lineTo(goalRect.left + width * 0.035f, goalRect.bottom);
        net.close();
        paint.setColor(0x22FFFFFF);
        canvas.drawPath(net, paint);

        strokePaint.setColor(0x99FFFFFF);
        strokePaint.setStrokeWidth(Math.max(1f, width * 0.003f));
        int columns = 11;
        int rows = 6;
        for (int i = 0; i <= columns; i++) {
            float fraction = i / (float) columns;
            float topX = lerp(goalRect.left, goalRect.right, fraction);
            float bottomX = lerp(goalRect.left + width * 0.035f, goalRect.right - width * 0.035f, fraction);
            canvas.drawLine(topX, goalRect.top, bottomX, goalRect.bottom, strokePaint);
        }
        for (int i = 0; i <= rows; i++) {
            float fraction = i / (float) rows;
            float y = lerp(goalRect.top, goalRect.bottom, fraction);
            float inset = width * 0.035f * fraction;
            canvas.drawLine(goalRect.left + inset, y, goalRect.right - inset, y, strokePaint);
        }

        // Bright front goal frame.
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStrokeWidth(Math.max(6f, width * 0.025f));
        canvas.drawLine(goalRect.left, goalRect.bottom, goalRect.left, goalRect.top, strokePaint);
        canvas.drawLine(goalRect.left, goalRect.top, goalRect.right, goalRect.top, strokePaint);
        canvas.drawLine(goalRect.right, goalRect.top, goalRect.right, goalRect.bottom, strokePaint);
        strokePaint.setColor(0xAAE8F4FF);
        strokePaint.setStrokeWidth(Math.max(1.5f, width * 0.006f));
        canvas.drawLine(goalRect.left + width * 0.012f, goalRect.top + height * 0.006f,
                goalRect.right - width * 0.012f, goalRect.top + height * 0.006f, strokePaint);

        drawFloodlight(canvas, width * 0.13f, height * 0.18f, -9f);
        drawFloodlight(canvas, width * 0.87f, height * 0.18f, 9f);
    }

    private void drawFloodlight(Canvas canvas, float centerX, float centerY, float rotation) {
        float width = getWidth();
        float height = getHeight();
        canvas.save();
        canvas.rotate(rotation, centerX, centerY);
        strokePaint.setColor(0xFF263A43);
        strokePaint.setStrokeWidth(Math.max(3f, width * 0.012f));
        canvas.drawLine(centerX, centerY + height * 0.055f, centerX, height * 0.39f, strokePaint);
        paint.setColor(0xFFEFF9FF);
        RectF panel = new RectF(centerX - width * 0.075f, centerY - height * 0.055f,
                centerX + width * 0.075f, centerY + height * 0.055f);
        canvas.drawRoundRect(panel, width * 0.008f, width * 0.008f, paint);
        strokePaint.setColor(0xFF334155);
        strokePaint.setStrokeWidth(Math.max(1f, width * 0.005f));
        canvas.drawRoundRect(panel, width * 0.008f, width * 0.008f, strokePaint);
        for (int i = 1; i < 4; i++) {
            float x = lerp(panel.left, panel.right, i / 4f);
            canvas.drawLine(x, panel.top, x, panel.bottom, strokePaint);
        }
        for (int i = 1; i < 3; i++) {
            float y = lerp(panel.top, panel.bottom, i / 3f);
            canvas.drawLine(panel.left, y, panel.right, y, strokePaint);
        }
        canvas.restore();
    }

    private void drawVignette(Canvas canvas) {
        float h = getHeight();
        paint.setShader(new LinearGradient(
                0f,
                0f,
                0f,
                h,
                new int[]{0xB8000000, 0x12000000, 0x08000000, 0xA8000000},
                new float[]{0f, 0.24f, 0.72f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
    }

    private void drawAimDot(Canvas canvas) {
        float x = map(aimX, -1f, 1f, aimRect.left, aimRect.right);
        float y = map(aimY, -1f, 1f, aimRect.top, aimRect.bottom);
        float radius = Math.min(getWidth(), getHeight()) * 0.025f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55FF0000);
        canvas.drawCircle(x, y, radius * 1.85f, paint);
        paint.setColor(0xFFFF2D21);
        canvas.drawCircle(x, y, radius, paint);

        strokePaint.setColor(Color.WHITE);
        strokePaint.setStrokeWidth(Math.max(1.5f, getWidth() * 0.004f));
        canvas.drawCircle(x, y, radius, strokePaint);
        canvas.drawLine(x - radius * 1.65f, y, x - radius * 0.65f, y, strokePaint);
        canvas.drawLine(x + radius * 0.65f, y, x + radius * 1.65f, y, strokePaint);
        canvas.drawLine(x, y - radius * 1.65f, x, y - radius * 0.65f, strokePaint);
        canvas.drawLine(x, y + radius * 0.65f, x, y + radius * 1.65f, strokePaint);
    }

    private void drawGoalkeeper(Canvas canvas, long nowMs) {
        float progress = 0f;
        if (state == State.SHOOTING || state == State.RESULT) {
            progress = clamp((nowMs - stateStartedMs) / 620f, 0f, 1f);
            progress = easeOutCubic(progress);
        }

        float startX = goalRect.centerX();
        float startY = goalRect.top + goalRect.height() * 0.67f;
        float destinationX = map(keeperTargetX, 0f, 1f, aimRect.left, aimRect.right);
        float destinationY = map(keeperTargetY, 0f, 1f, aimRect.top, aimRect.bottom);
        float x = lerp(startX, destinationX, progress);
        float y = lerp(startY, destinationY, progress) - (float) Math.sin(progress * Math.PI) * getHeight() * 0.018f;

        float direction = Math.signum(destinationX - startX);
        float rotation = direction * progress * 48f;
        float scale = Math.min(getWidth(), getHeight()) / 360f;

        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(rotation);

        float bodyWidth = 24f * scale;
        float bodyHeight = 41f * scale;
        float headRadius = 7.2f * scale;
        float armSpread = lerp(20f, 37f, progress) * scale;
        float armY = -bodyHeight * 0.62f;

        // Shadow.
        paint.setColor(0x44000000);
        canvas.drawOval(new RectF(-28f * scale, 16f * scale, 28f * scale, 27f * scale), paint);

        // Legs.
        strokePaint.setStrokeWidth(7f * scale);
        strokePaint.setColor(0xFF1F2937);
        canvas.drawLine(-bodyWidth * 0.20f, 5f * scale, -15f * scale, 24f * scale, strokePaint);
        canvas.drawLine(bodyWidth * 0.20f, 5f * scale, 15f * scale, 24f * scale, strokePaint);

        // Arms and gloves.
        strokePaint.setStrokeWidth(6.5f * scale);
        strokePaint.setColor(0xFFFF8A00);
        canvas.drawLine(-bodyWidth * 0.38f, armY, -armSpread, armY - 4f * scale, strokePaint);
        canvas.drawLine(bodyWidth * 0.38f, armY, armSpread, armY - 4f * scale, strokePaint);
        paint.setColor(0xFFEAF6FF);
        canvas.drawCircle(-armSpread, armY - 4f * scale, 5.5f * scale, paint);
        canvas.drawCircle(armSpread, armY - 4f * scale, 5.5f * scale, paint);

        // Torso.
        paint.setColor(0xFFFF8A00);
        canvas.drawRoundRect(
                new RectF(-bodyWidth / 2f, -bodyHeight, bodyWidth / 2f, 7f * scale),
                7f * scale,
                7f * scale,
                paint
        );
        paint.setColor(0xFF111827);
        canvas.drawRect(-bodyWidth / 2f, -7f * scale, bodyWidth / 2f, 8f * scale, paint);

        // Head.
        paint.setColor(0xFFF2C49A);
        canvas.drawCircle(0f, -bodyHeight - headRadius * 0.9f, headRadius, paint);
        paint.setColor(0xFF402A20);
        canvas.drawArc(
                new RectF(-headRadius, -bodyHeight - headRadius * 1.9f, headRadius, -bodyHeight + headRadius * 0.1f),
                190f,
                160f,
                true,
                paint
        );

        canvas.restore();
    }

    private void drawBall(Canvas canvas, long nowMs) {
        float startX = getWidth() * 0.5f;
        float startY = getHeight() * 0.825f;
        float x = startX;
        float y = startY;
        float progress = 0f;

        if (state == State.SHOOTING || state == State.RESULT) {
            progress = clamp((nowMs - stateStartedMs) / (float) SHOT_DURATION_MS, 0f, 1f);
            float targetX = map(shotTargetX, 0f, 1f, aimRect.left, aimRect.right);
            float targetY = map(shotTargetY, 0f, 1f, aimRect.top, aimRect.bottom);
            float controlX = lerp(startX, targetX, 0.52f);
            float controlY = Math.min(startY, targetY) - getHeight() * 0.115f;
            float oneMinus = 1f - progress;
            x = oneMinus * oneMinus * startX
                    + 2f * oneMinus * progress * controlX
                    + progress * progress * targetX;
            y = oneMinus * oneMinus * startY
                    + 2f * oneMinus * progress * controlY
                    + progress * progress * targetY;
        }

        float startRadius = Math.min(getWidth(), getHeight()) * 0.067f;
        float endRadius = Math.min(getWidth(), getHeight()) * 0.027f;
        float radius = lerp(startRadius, endRadius, progress);
        drawSoccerBall(canvas, x, y, radius, progress * 520f);
    }

    private void drawSoccerBall(Canvas canvas, float x, float y, float radius, float rotationDegrees) {
        paint.setColor(0x55000000);
        canvas.drawOval(new RectF(
                x - radius * 0.82f,
                y + radius * 0.72f,
                x + radius * 0.82f,
                y + radius * 1.08f
        ), paint);

        canvas.save();
        canvas.rotate(rotationDegrees, x, y);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, radius, paint);
        strokePaint.setColor(0xFF222222);
        strokePaint.setStrokeWidth(Math.max(1f, radius * 0.055f));
        canvas.drawCircle(x, y, radius, strokePaint);

        Path pentagon = polygonPath(x, y, radius * 0.34f, 5, -90f);
        paint.setColor(0xFF111111);
        canvas.drawPath(pentagon, paint);

        for (int i = 0; i < 5; i++) {
            double angle = Math.toRadians(-90f + i * 72f);
            float patchX = x + (float) Math.cos(angle) * radius * 0.69f;
            float patchY = y + (float) Math.sin(angle) * radius * 0.69f;
            Path patch = polygonPath(patchX, patchY, radius * 0.20f, 5, -90f + i * 72f);
            canvas.drawPath(patch, paint);
            canvas.drawLine(
                    x + (float) Math.cos(angle) * radius * 0.34f,
                    y + (float) Math.sin(angle) * radius * 0.34f,
                    patchX,
                    patchY,
                    strokePaint
            );
        }
        canvas.restore();
    }

    private void drawTopHud(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        float textSize = Math.min(width, height) * 0.045f;
        textPaint.setTextSize(textSize);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setShadowLayer(4f, 0f, 1f, 0xCC000000);

        int displayShot = Math.min(TOTAL_SHOTS, shotsTaken + (state == State.READY ? 1 : 0));
        String hud = String.format(Locale.US, "%d/%d     GOALS %d", displayShot, TOTAL_SHOTS, goals);
        canvas.drawText(hud, width / 2f, height * 0.105f, textPaint);
        textPaint.clearShadowLayer();

        // Live microphone meter under the HUD.
        float meterWidth = width * 0.23f;
        float meterHeight = Math.max(3f, height * 0.009f);
        float left = width / 2f - meterWidth / 2f;
        float top = height * 0.125f;
        paint.setColor(0x66000000);
        canvas.drawRoundRect(new RectF(left, top, left + meterWidth, top + meterHeight), meterHeight, meterHeight, paint);
        paint.setColor(microphoneListening ? 0xFF5CFF75 : 0xFF777777);
        canvas.drawRoundRect(
                new RectF(left, top, left + meterWidth * audioLevel, top + meterHeight),
                meterHeight,
                meterHeight,
                paint
        );
    }

    private void drawControls(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float iconRadius = size * 0.045f;

        // Recenter/crosshair button.
        float recenterX = recenterHitRect.centerX();
        float recenterY = recenterHitRect.centerY();
        paint.setColor(0x77000000);
        canvas.drawCircle(recenterX, recenterY, iconRadius * 1.35f, paint);
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStrokeWidth(size * 0.006f);
        canvas.drawCircle(recenterX, recenterY, iconRadius * 0.62f, strokePaint);
        canvas.drawLine(recenterX - iconRadius, recenterY, recenterX - iconRadius * 0.35f, recenterY, strokePaint);
        canvas.drawLine(recenterX + iconRadius * 0.35f, recenterY, recenterX + iconRadius, recenterY, strokePaint);
        canvas.drawLine(recenterX, recenterY - iconRadius, recenterX, recenterY - iconRadius * 0.35f, strokePaint);
        canvas.drawLine(recenterX, recenterY + iconRadius * 0.35f, recenterX, recenterY + iconRadius, strokePaint);

        // Settings/gear button.
        float settingsX = settingsHitRect.centerX();
        float settingsY = settingsHitRect.centerY();
        paint.setColor(0x77000000);
        canvas.drawCircle(settingsX, settingsY, iconRadius * 1.35f, paint);
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStrokeWidth(size * 0.006f);
        canvas.drawCircle(settingsX, settingsY, iconRadius * 0.72f, strokePaint);
        canvas.drawCircle(settingsX, settingsY, iconRadius * 0.24f, strokePaint);
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45f);
            float innerX = settingsX + (float) Math.cos(angle) * iconRadius * 0.78f;
            float innerY = settingsY + (float) Math.sin(angle) * iconRadius * 0.78f;
            float outerX = settingsX + (float) Math.cos(angle) * iconRadius * 1.08f;
            float outerY = settingsY + (float) Math.sin(angle) * iconRadius * 1.08f;
            canvas.drawLine(innerX, innerY, outerX, outerY, strokePaint);
        }
    }

    private void drawStatus(Canvas canvas, long nowMs) {
        float width = getWidth();
        float height = getHeight();
        String message;

        if (state == State.GAME_OVER) {
            message = goals + "/" + TOTAL_SHOTS + " GOALS  •  TAP TO RESTART";
        } else if (state == State.RESULT) {
            message = currentShotSaved ? "SAVED!" : "GOAL!";
        } else if (statusMessageUntilMs > nowMs) {
            message = statusMessage;
        } else if (state == State.READY) {
            if (!microphoneListening && !GamePreferences.isTapEnabled(getContext())) {
                message = "ENABLE MIC OR TAP IN SETTINGS";
            } else if (!microphoneListening) {
                message = "TAP TO SHOOT";
            } else {
                message = "MOVE WRIST • SNAP TO SHOOT";
            }
        } else {
            message = "";
        }

        if (message.isEmpty()) {
            return;
        }

        float textSize = Math.min(width, height) * (state == State.RESULT ? 0.072f : 0.038f);
        textPaint.setTextSize(textSize);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(state == State.RESULT && !currentShotSaved ? 0xFF8CFF7A : Color.WHITE);
        textPaint.setShadowLayer(5f, 0f, 2f, 0xDD000000);
        canvas.drawText(message, width / 2f, height * 0.94f, textPaint);
        textPaint.clearShadowLayer();
    }

    private void shoot() {
        if (state != State.READY) {
            return;
        }

        shotTargetX = clamp((aimX + 1f) / 2f, 0f, 1f);
        shotTargetY = clamp((aimY + 1f) / 2f, 0f, 1f);

        int zoneX = random.nextInt(3);
        int zoneY = random.nextInt(2);
        keeperTargetX = new float[]{0.17f, 0.50f, 0.83f}[zoneX];
        keeperTargetY = zoneY == 0 ? 0.22f : 0.76f;

        int difficulty = GamePreferences.getDifficulty(getContext());
        float reach = difficulty == 1 ? 0.205f : difficulty == 2 ? 0.265f : 0.325f;
        float dx = shotTargetX - keeperTargetX;
        float dy = (shotTargetY - keeperTargetY) * 0.82f;
        currentShotSaved = Math.sqrt(dx * dx + dy * dy) <= reach;

        shotsTaken++;
        state = State.SHOOTING;
        stateStartedMs = SystemClock.elapsedRealtime();
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        postInvalidateOnAnimation();
    }

    private void updateState(long nowMs) {
        if (state == State.SHOOTING && nowMs - stateStartedMs >= SHOT_DURATION_MS) {
            if (!currentShotSaved) {
                goals++;
            }
            state = State.RESULT;
            stateStartedMs = nowMs;
            performHapticFeedback(currentShotSaved ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.CONFIRM);
        } else if (state == State.RESULT && nowMs - stateStartedMs >= RESULT_DURATION_MS) {
            if (shotsTaken >= TOTAL_SHOTS) {
                state = State.GAME_OVER;
            } else {
                state = State.READY;
            }
            stateStartedMs = nowMs;
            postInvalidateOnAnimation();
        }
    }

    private void restartGame() {
        shotsTaken = 0;
        goals = 0;
        state = State.READY;
        statusMessageUntilMs = 0L;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float x = event.getX();
                float y = event.getY();
                float moved = (float) Math.hypot(x - touchDownX, y - touchDownY);
                if (moved > Math.min(getWidth(), getHeight()) * 0.08f) {
                    return true;
                }
                if (recenterHitRect.contains(x, y)) {
                    host.recenterAim();
                    showTemporaryMessage("AIM RECENTERED", 800L);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    return true;
                }
                if (settingsHitRect.contains(x, y)) {
                    host.openSettings();
                    return true;
                }
                if (state == State.GAME_OVER) {
                    restartGame();
                    return true;
                }
                if (state == State.READY && GamePreferences.isTapEnabled(getContext())) {
                    shoot();
                }
                return true;
            default:
                return true;
        }
    }

    private static Path polygonPath(float centerX, float centerY, float radius, int sides, float rotationDegrees) {
        Path path = new Path();
        for (int i = 0; i < sides; i++) {
            double angle = Math.toRadians(rotationDegrees + (360f / sides) * i);
            float x = centerX + (float) Math.cos(angle) * radius;
            float y = centerY + (float) Math.sin(angle) * radius;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
        return path;
    }

    private static float map(float value, float fromMin, float fromMax, float toMin, float toMax) {
        float fraction = (value - fromMin) / (fromMax - fromMin);
        return toMin + fraction * (toMax - toMin);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static float easeOutCubic(float value) {
        float inverted = 1f - value;
        return 1f - inverted * inverted * inverted;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
