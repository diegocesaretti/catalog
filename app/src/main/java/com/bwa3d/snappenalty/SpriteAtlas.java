package com.bwa3d.snappenalty;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class SpriteAtlas {
    private static SpriteAtlas instance;

    private final Bitmap atlas;
    private final Map<String, Rect> rects = new HashMap<>();

    private SpriteAtlas(Context context) {
        atlas = loadBitmapFromBase64Asset(context, "sprite_atlas_compact.b64");
        rects.put("idle", new Rect(4, 4, 43, 79));
        rects.put("crouch", new Rect(47, 4, 94, 74));
        rects.put("low_right", new Rect(98, 4, 187, 47));
        rects.put("high_right", new Rect(191, 4, 261, 70));
        rects.put("arms_up", new Rect(265, 4, 306, 79));
        rects.put("catch_front", new Rect(310, 4, 345, 77));
        rects.put("celebrate", new Rect(349, 4, 417, 72));
        rects.put("ball", new Rect(421, 4, 442, 27));
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
        if (rect == null) {
            return rects.get("idle");
        }
        return rect;
    }

    private static Bitmap loadBitmapFromBase64Asset(Context context, String assetName) {
        try (InputStream inputStream = context.getAssets().open(assetName)) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, count);
            }
            String base64 = new String(outputStream.toByteArray(), StandardCharsets.US_ASCII)
                    .replace("\n", "")
                    .replace("\r", "");
            byte[] pngBytes = Base64.decode(base64, Base64.DEFAULT);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.length, options);
            if (bitmap == null) {
                throw new IllegalStateException("Could not decode sprite atlas");
            }
            bitmap.setHasAlpha(true);
            bitmap.setPremultiplied(true);
            return bitmap;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load sprite atlas", exception);
        }
    }
}
