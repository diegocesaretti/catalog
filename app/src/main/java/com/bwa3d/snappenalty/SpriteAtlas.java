package com.bwa3d.snappenalty;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Base64InputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Atlas generated from the user-supplied PNG, which already has real alpha transparency. */
public final class SpriteAtlas {
    private static SpriteAtlas instance;

    private final Bitmap atlas;
    private final Map<String, Rect> rects = new HashMap<>();

    private SpriteAtlas(Context context) {
        atlas = loadBase64Png(context, new String[]{
                "goalkeeper_atlas_v5_00.b64",
                "goalkeeper_atlas_v5_01.b64",
                "goalkeeper_atlas_v5_02.b64"
        });
        atlas.setHasAlpha(true);

        rects.put("idle", new Rect(8, 8, 81, 150));
        rects.put("crouch", new Rect(89, 8, 176, 138));
        rects.put("low_right", new Rect(184, 8, 348, 88));
        rects.put("high_right", new Rect(356, 8, 484, 134));
        rects.put("low_left", new Rect(492, 8, 670, 128));
        rects.put("high_left", new Rect(678, 8, 830, 134));
        rects.put("arms_up", new Rect(838, 8, 916, 146));
        rects.put("catch_front", new Rect(924, 8, 990, 143));
        rects.put("kneel", new Rect(8, 158, 106, 265));
        rects.put("point", new Rect(114, 158, 253, 274));
        rects.put("celebrate", new Rect(261, 158, 405, 284));
        rects.put("ball1", new Rect(413, 158, 452, 201));
        rects.put("ball2", new Rect(460, 158, 524, 200));
        rects.put("ball3", new Rect(532, 158, 592, 200));
        rects.put("ball4", new Rect(600, 158, 661, 192));
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

    private static Bitmap loadBase64Png(Context context, String[] assetNames) {
        java.util.List<InputStream> streams = new java.util.ArrayList<>();
        try {
            for (String assetName : assetNames) {
                streams.add(context.getAssets().open(assetName));
            }
            try (java.io.SequenceInputStream encoded = new java.io.SequenceInputStream(
                    java.util.Collections.enumeration(streams));
                 Base64InputStream decoded = new Base64InputStream(
                         encoded,
                         android.util.Base64.DEFAULT);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = decoded.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                }
                byte[] png = bytes.toByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length, options);
                if (bitmap == null) {
                    throw new IllegalStateException("Could not decode goalkeeper atlas");
                }
                return bitmap;
            }
        } catch (IOException exception) {
            for (InputStream stream : streams) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
            throw new IllegalStateException("Could not load goalkeeper atlas", exception);
        }
    }
}
