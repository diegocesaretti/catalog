package com.bwa3d.snappenalty;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import java.util.HashMap;
import java.util.Map;

public final class SpriteAtlas {
    private static SpriteAtlas instance;

    private final Bitmap atlas;
    private final Map<String, Rect> rects = new HashMap<>();

    private SpriteAtlas(Context context) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        atlas = BitmapFactory.decodeResource(
                context.getResources(), R.drawable.sprite_atlas_v050, options);
        if (atlas == null) {
            throw new IllegalStateException("Could not decode transparent sprite atlas");
        }
        atlas.setHasAlpha(true);
        atlas.setPremultiplied(true);

        rects.put("idle", new Rect(4, 4, 43, 80));
        rects.put("crouch", new Rect(47, 4, 96, 78));
        rects.put("low_right", new Rect(100, 4, 197, 51));
        rects.put("high_right", new Rect(201, 4, 277, 78));
        rects.put("low_left", new Rect(281, 4, 375, 51));
        rects.put("high_left", new Rect(379, 4, 453, 78));
        rects.put("arms_up", new Rect(457, 4, 499, 80));
        rects.put("catch_front", new Rect(503, 4, 539, 78));
        rects.put("celebrate", new Rect(543, 4, 597, 78));
        rects.put("ball", new Rect(601, 4, 624, 29));
    }

    public static synchronized SpriteAtlas get(Context context) {
        if (instance == null) {
            instance = new SpriteAtlas(context.getApplicationContext());
        }
        return instance;
    }

    public Bitmap getAtlas() {
        return atlas;
    }

    public Rect getRect(String name) {
        Rect rect = rects.get(name);
        return rect != null ? rect : rects.get("idle");
    }
}
