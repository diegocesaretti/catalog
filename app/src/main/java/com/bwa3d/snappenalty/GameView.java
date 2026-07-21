package com.bwa3d.snappenalty;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
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

    private enum State { READY, SHOOTING, RESULT, GAME_OVER }

    private static final int TOTAL_SHOTS = 5;
    private static final long SHOT_MS = 820L;
    private static final long RESULT_MS = 1050L;

    private final Host host;
    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spritePaint = new Paint();
    private final RectF goal = new RectF();
    private final RectF aimArea = new RectF();
    private final RectF settingsHit = new RectF();
    private final RectF recenterHit = new RectF();
    private final Rect sourceRect = new Rect();
    private final RectF destinationRect = new RectF();

    private SpriteAtlas sprites;
    private State state = State.READY;
    private float aimX;
    private float aimY;
    private float motionIntensity;
    private float audioLevel;
    private boolean microphoneListening;
    private int shots;
    private int goals;
    private long stateStart;
    private long messageUntil;
    private String temporaryMessage = "";
    private long shakeUntil;
    private float shotX;
    private float shotY;
    private float keeperX;
    private float keeperY;
    private boolean saved;
    private float downX;
    private float downY;

    public GameView(Context context, Host host) {
        super(context);
        this.host = host;
        setKeepScreenOn(true);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        spritePaint.setAntiAlias(false);
        spritePaint.setFilterBitmap(false);
        spritePaint.setDither(false);
        try {
            sprites = SpriteAtlas.get(context);
        } catch (Throwable ignored) {
            sprites = null;
        }
    }

    public void setAim(float x, float y, float motion) {
        aimX = clamp(x, -1f, 1f);
        aimY = clamp(y, -1f, 1f);
        motionIntensity = motion;
        if (state == State.READY) postInvalidateOnAnimation();
    }

    public void setMicrophoneListening(boolean listening) {
        microphoneListening = listening;
        invalidate();
    }

    public void setAudioLevel(float level) {
        audioLevel = clamp(level, 0f, 1f);
        invalidate();
    }

    public void refreshPreferences() {
        invalidate();
    }

    public void showTemporaryMessage(String message, long durationMs) {
        temporaryMessage = message;
        messageUntil = SystemClock.elapsedRealtime() + durationMs;
        invalidate();
    }

    public void onSnapDetected() {
        if (state != State.READY) return;
        if (motionIntensity > 7.0f) {
            showTemporaryMessage("HOLD WRIST STEADY", 700L);
            return;
        }
        shoot();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        goal.set(w * 0.075f, h * 0.33f, w * 0.925f, h * 0.70f);
        aimArea.set(w * 0.14f, h * 0.38f, w * 0.86f, h * 0.64f);
        float button = Math.min(w, h) * 0.19f;
        recenterHit.set(w * 0.06f, h * 0.055f, w * 0.06f + button, h * 0.055f + button);
        settingsHit.set(w * 0.94f - button, h * 0.055f, w * 0.94f, h * 0.055f + button);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.elapsedRealtime();
        advance(now);
        canvas.save();
        if (now < shakeUntil) {
            float remaining = (shakeUntil - now) / 380f;
            float amplitude = Math.min(getWidth(), getHeight()) * 0.022f * remaining;
            canvas.translate((random.nextFloat() * 2f - 1f) * amplitude,
                    (random.nextFloat() * 2f - 1f) * amplitude * 0.7f);
        }
        drawStadium(canvas);
        drawKeeper(canvas, now);
        drawBall(canvas, now);
        if (state == State.READY) drawAim(canvas);
        canvas.restore();
        drawHud(canvas, now);
        if (state == State.SHOOTING || state == State.RESULT || now < shakeUntil) {
            postInvalidateOnAnimation();
        }
    }

    private void drawStadium(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        paint.setShader(new LinearGradient(0, 0, 0, h * 0.56f,
                0xFF1680C9, 0xFFBDEBFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h * 0.56f, paint);
        paint.setShader(null);
        paint.setColor(0xFF26323A);
        canvas.drawRect(0, h * 0.29f, w, h * 0.56f, paint);
        paint.setShader(new LinearGradient(0, h * 0.52f, 0, h,
                0xFF43B94A, 0xFF106B29, Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * 0.52f, w, h, paint);
        paint.setShader(null);
        for (int i = 0; i < 7; i++) {
            paint.setColor((i & 1) == 0 ? 0x164CFF62 : 0x16123519);
            float top = h * 0.55f + i * h * 0.068f;
            canvas.drawRect(0, top, w, top + h * 0.034f, paint);
        }

        paint.setColor(0x22FFFFFF);
        canvas.drawRect(goal, paint);
        linePaint.setColor(0x99FFFFFF);
        linePaint.setStrokeWidth(Math.max(1f, w * 0.003f));
        for (int i = 1; i < 10; i++) {
            float x = goal.left + goal.width() * i / 10f;
            canvas.drawLine(x, goal.top, x, goal.bottom, linePaint);
        }
        for (int i = 1; i < 6; i++) {
            float y = goal.top + goal.height() * i / 6f;
            canvas.drawLine(goal.left, y, goal.right, y, linePaint);
        }
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(Math.max(6f, w * 0.024f));
        canvas.drawLine(goal.left, goal.bottom, goal.left, goal.top, linePaint);
        canvas.drawLine(goal.left, goal.top, goal.right, goal.top, linePaint);
        canvas.drawLine(goal.right, goal.top, goal.right, goal.bottom, linePaint);
        linePaint.setStrokeWidth(Math.max(1.5f, w * 0.005f));
        linePaint.setColor(0xAAFFFFFF);
        canvas.drawLine(0, h * 0.76f, w, h * 0.76f, linePaint);
    }

    private void drawAim(Canvas canvas) {
        float x = map(aimX, -1, 1, aimArea.left, aimArea.right);
        float y = map(aimY, -1, 1, aimArea.top, aimArea.bottom);
        float r = Math.min(getWidth(), getHeight()) * 0.025f;
        paint.setColor(0x55FF0000);
        canvas.drawCircle(x, y, r * 1.8f, paint);
        paint.setColor(0xFFFF2D21);
        canvas.drawCircle(x, y, r, paint);
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(Math.max(1.5f, getWidth() * 0.004f));
        canvas.drawCircle(x, y, r, linePaint);
    }

    private void drawKeeper(Canvas canvas, long now) {
        if (sprites == null) return;
        float p = 0f;
        if (state == State.SHOOTING || state == State.RESULT) {
            p = easeOut(clamp((now - stateStart) / 560f, 0f, 1f));
        }
        float startX = goal.centerX();
        float startBottom = goal.bottom + getHeight() * 0.018f;
        float targetX = map(keeperX, 0, 1, aimArea.left, aimArea.right);
        float targetBottom = keeperY < 0.5f
                ? goal.top + goal.height() * 0.74f
                : goal.bottom + getHeight() * 0.018f;
        float x = lerp(startX, targetX, p);
        float bottom = lerp(startBottom, targetBottom, p)
                - (float) Math.sin(p * Math.PI) * getHeight() * 0.008f;
        String sprite = keeperSprite(p);
        boolean mirror = false;
        if ("high_left".equals(sprite)) { sprite = "high_right"; mirror = true; }
        if ("low_left".equals(sprite)) { sprite = "low_right"; mirror = true; }
        float height = Math.min(getWidth(), getHeight()) * 0.19f;
        if (sprite.startsWith("low_")) height *= 0.82f;
        drawSprite(canvas, sprite, x, bottom, height, mirror);
    }

    private String keeperSprite(float p) {
        if (state == State.GAME_OVER && goals >= 3) return "celebrate";
        if (state == State.READY) return "crouch";
        if (saved && p > 0.88f) return "catch_front";
        boolean left = keeperX < 0.34f;
        boolean right = keeperX > 0.66f;
        boolean high = keeperY < 0.5f;
        if (!left && !right) return high ? "arms_up" : "idle";
        return high ? (left ? "high_left" : "high_right")
                : (left ? "low_left" : "low_right");
    }

    private void drawBall(Canvas canvas, long now) {
        if (sprites == null) return;
        float p = 0f;
        float x = getWidth() * 0.5f;
        float y = getHeight() * 0.825f;
        if (state == State.SHOOTING || state == State.RESULT) {
            p = clamp((now - stateStart) / (float) SHOT_MS, 0f, 1f);
            float tx = map(shotX, 0, 1, aimArea.left, aimArea.right);
            float ty = map(shotY, 0, 1, aimArea.top, aimArea.bottom);
            float cx = lerp(x, tx, 0.52f);
            float cy = Math.min(y, ty) - getHeight() * 0.11f;
            float q = 1f - p;
            x = q * q * x + 2f * q * p * cx + p * p * tx;
            y = q * q * y + 2f * q * p * cy + p * p * ty;
        }
        drawSprite(canvas, "ball", x, y,
                lerp(Math.min(getWidth(), getHeight()) * 0.105f,
                        Math.min(getWidth(), getHeight()) * 0.048f, p), false);
    }

    private void drawSprite(Canvas canvas, String name, float cx, float bottom, float height, boolean mirror) {
        Bitmap bitmap = sprites.getAtlas();
        Rect src = sprites.getRect(name);
        sourceRect.set(src);
        float width = height * src.width() / (float) src.height();
        destinationRect.set(cx - width / 2f, bottom - height, cx + width / 2f, bottom);
        if (mirror) {
            canvas.save();
            canvas.scale(-1, 1, cx, 0);
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, spritePaint);
            canvas.restore();
        } else {
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, spritePaint);
        }
    }

    private void drawHud(Canvas canvas, long now) {
        float w = getWidth();
        float h = getHeight();
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(Math.min(w, h) * 0.044f);
        textPaint.setShadowLayer(4, 0, 1, Color.BLACK);
        int shownShot = Math.min(TOTAL_SHOTS, shots + (state == State.READY ? 1 : 0));
        canvas.drawText(String.format(Locale.US, "%d/%d   GOALS %d", shownShot, TOTAL_SHOTS, goals),
                w / 2f, h * 0.105f, textPaint);
        textPaint.clearShadowLayer();

        float meterW = w * 0.22f;
        float meterH = Math.max(3f, h * 0.009f);
        float meterL = w / 2f - meterW / 2f;
        paint.setColor(0x66000000);
        canvas.drawRoundRect(new RectF(meterL, h * 0.125f, meterL + meterW, h * 0.125f + meterH), meterH, meterH, paint);
        paint.setColor(microphoneListening ? 0xFF5CFF75 : 0xFF777777);
        canvas.drawRoundRect(new RectF(meterL, h * 0.125f, meterL + meterW * audioLevel, h * 0.125f + meterH), meterH, meterH, paint);

        drawRecenterIcon(canvas, recenterHit.centerX(), recenterHit.centerY());
        drawSettingsIcon(canvas, settingsHit.centerX(), settingsHit.centerY());

        String message;
        if (state == State.GAME_OVER) message = goals + "/" + TOTAL_SHOTS + " GOALS • TAP TO RESTART";
        else if (state == State.RESULT) message = saved ? "SAVED!" : "GOAL!";
        else if (now < messageUntil) message = temporaryMessage;
        else if (microphoneListening) message = "MOVE WRIST • SNAP TO SHOOT";
        else message = "MOVE WRIST • TAP TO SHOOT";
        textPaint.setTextSize(Math.min(w, h) * (state == State.RESULT ? 0.07f : 0.036f));
        textPaint.setColor(state == State.RESULT && !saved ? 0xFF8CFF7A : Color.WHITE);
        textPaint.setShadowLayer(5, 0, 2, Color.BLACK);
        canvas.drawText(message, w / 2f, h * 0.94f, textPaint);
        textPaint.clearShadowLayer();
    }

    private void drawRecenterIcon(Canvas canvas, float x, float y) {
        float r = Math.min(getWidth(), getHeight()) * 0.045f;
        paint.setColor(0x77000000); canvas.drawCircle(x, y, r * 1.35f, paint);
        linePaint.setColor(Color.WHITE); linePaint.setStrokeWidth(r * 0.14f);
        canvas.drawCircle(x, y, r * 0.62f, linePaint);
        canvas.drawLine(x-r, y, x-r*.35f, y, linePaint); canvas.drawLine(x+r*.35f,y,x+r,y,linePaint);
        canvas.drawLine(x,y-r,x,y-r*.35f,linePaint); canvas.drawLine(x,y+r*.35f,x,y+r,linePaint);
    }

    private void drawSettingsIcon(Canvas canvas, float x, float y) {
        float r = Math.min(getWidth(), getHeight()) * 0.045f;
        paint.setColor(0x77000000); canvas.drawCircle(x, y, r * 1.35f, paint);
        linePaint.setColor(Color.WHITE); linePaint.setStrokeWidth(r * 0.14f);
        canvas.drawCircle(x, y, r * 0.72f, linePaint); canvas.drawCircle(x, y, r * 0.22f, linePaint);
    }

    private void shoot() {
        shotX = clamp((aimX + 1f) / 2f, 0, 1);
        shotY = clamp((aimY + 1f) / 2f, 0, 1);
        keeperX = new float[]{0.17f, 0.5f, 0.83f}[random.nextInt(3)];
        keeperY = random.nextBoolean() ? 0.22f : 0.76f;
        float reach = new float[]{0.20f, 0.26f, 0.32f}[GamePreferences.getDifficulty(getContext()) - 1];
        float dx = shotX - keeperX;
        float dy = (shotY - keeperY) * 0.82f;
        saved = Math.sqrt(dx * dx + dy * dy) <= reach;
        shots++;
        state = State.SHOOTING;
        stateStart = SystemClock.elapsedRealtime();
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        invalidate();
    }

    private void advance(long now) {
        if (state == State.SHOOTING && now - stateStart >= SHOT_MS) {
            if (!saved) { goals++; shakeUntil = now + 380L; }
            state = State.RESULT;
            stateStart = now;
            performHapticFeedback(saved ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.CONFIRM);
        } else if (state == State.RESULT && now - stateStart >= RESULT_MS) {
            aimX = 0f;
            aimY = 0f;
            host.recenterAim();
            state = shots >= TOTAL_SHOTS ? State.GAME_OVER : State.READY;
            stateStart = now;
        }
    }

    private void restart() {
        shots = 0; goals = 0; state = State.READY; aimX = 0; aimY = 0;
        host.recenterAim();
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = event.getX(), y = event.getY();
            if (Math.hypot(x-downX, y-downY) > Math.min(getWidth(), getHeight()) * .08f) return true;
            if (recenterHit.contains(x,y)) { aimX=0; aimY=0; host.recenterAim(); showTemporaryMessage("AIM RECENTERED",700); return true; }
            if (settingsHit.contains(x,y)) { host.openSettings(); return true; }
            if (state == State.GAME_OVER) { restart(); return true; }
            if (state == State.READY && GamePreferences.isTapEnabled(getContext())) shoot();
            return true;
        }
        return true;
    }

    private static float map(float v,float a,float b,float c,float d){ return c+(v-a)/(b-a)*(d-c); }
    private static float lerp(float a,float b,float t){ return a+(b-a)*t; }
    private static float easeOut(float v){ float q=1-v; return 1-q*q*q; }
    private static float clamp(float v,float a,float b){ return Math.max(a,Math.min(b,v)); }
}
