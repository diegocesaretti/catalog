package com.bwa3d.snappenalty;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.Base64InputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Alternates compact 20-second loops from the two supplied 8-bit tracks. */
public final class BackgroundMusic {
    private static final String[][] TRACK_ASSETS = {
            {
                    "music_tub_v5_00.b64",
                    "music_tub_v5_01.b64",
                    "music_tub_v5_02.b64"
            },
            {
                    "music_song2_v5_00.b64",
                    "music_song2_v5_01.b64",
                    "music_song2_v5_02.b64"
            }
    };
    private static final String[] CACHE_NAMES = {
            "tubthumping_8bit_loop_v5.m4a",
            "song2_8bit_loop_v5.m4a"
    };

    private final Context context;
    private MediaPlayer player;
    private int trackIndex;
    private boolean foreground;
    private boolean microphoneActive;

    public BackgroundMusic(Context context) {
        this.context = context.getApplicationContext();
    }

    public void onResume() {
        foreground = true;
        refresh();
    }

    public void onPause() {
        foreground = false;
        pausePlayer();
    }

    public void setMicrophoneActive(boolean active) {
        microphoneActive = active;
        applyVolume();
    }

    public void refresh() {
        if (!foreground || !GamePreferences.isMusicEnabled(context)) {
            pausePlayer();
            return;
        }
        if (player == null) {
            createCurrentTrack();
        }
        if (player != null) {
            try {
                if (!player.isPlaying()) {
                    player.start();
                }
            } catch (IllegalStateException ignored) {
                advanceTrack();
            }
        }
    }

    public void release() {
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            try {
                old.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void pausePlayer() {
        if (player == null) {
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
            }
        } catch (IllegalStateException ignored) {
        }
    }

    private void createCurrentTrack() {
        release();
        try {
            File track = ensureDecodedTrack(trackIndex);
            MediaPlayer created = new MediaPlayer();
            created.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            created.setDataSource(track.getAbsolutePath());
            created.setLooping(false);
            created.setOnCompletionListener(ignored -> advanceTrack());
            created.setOnErrorListener((ignored, what, extra) -> {
                advanceTrack();
                return true;
            });
            created.prepare();
            player = created;
            applyVolume();
        } catch (Throwable ignored) {
            player = null;
        }
    }

    private void advanceTrack() {
        release();
        trackIndex = (trackIndex + 1) % TRACK_ASSETS.length;
        if (foreground && GamePreferences.isMusicEnabled(context)) {
            createCurrentTrack();
            if (player != null) {
                try {
                    player.start();
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    private File ensureDecodedTrack(int index) throws IOException {
        File file = new File(context.getCacheDir(), CACHE_NAMES[index]);
        if (file.isFile() && file.length() > 10_000L) {
            return file;
        }

        List<InputStream> chunks = new ArrayList<>();
        try {
            for (String asset : TRACK_ASSETS[index]) {
                chunks.add(context.getAssets().open(asset));
            }
            try (SequenceInputStream encoded = new SequenceInputStream(
                    Collections.enumeration(chunks));
                 Base64InputStream decoded = new Base64InputStream(
                         encoded,
                         android.util.Base64.DEFAULT);
                 FileOutputStream output = new FileOutputStream(file, false)) {
                byte[] buffer = new byte[16_384];
                int read;
                while ((read = decoded.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            }
        } catch (IOException exception) {
            for (InputStream stream : chunks) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
            file.delete();
            throw exception;
        }
        return file;
    }

    private void applyVolume() {
        if (player == null) {
            return;
        }
        float volume = microphoneActive ? 0.18f : 0.32f;
        try {
            player.setVolume(volume, volume);
        } catch (IllegalStateException ignored) {
        }
    }
}
