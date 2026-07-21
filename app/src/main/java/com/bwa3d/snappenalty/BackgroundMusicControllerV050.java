package com.bwa3d.snappenalty;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

public final class BackgroundMusicControllerV050 {
    private static final String[] TRACKS = {
            "music_tubthumping.mp3",
            "music_song2.mp3"
    };

    private final Context context;
    private MediaPlayer player;
    private int nextTrack;
    private boolean resumed;

    public BackgroundMusicControllerV050(Context context) {
        this.context = context.getApplicationContext();
    }

    public void onResume() {
        resumed = true;
        if (!GamePreferences.isMusicEnabled(context)) {
            stop();
            return;
        }
        if (player != null) {
            try {
                player.start();
                return;
            } catch (Throwable ignored) {
                releasePlayer();
            }
        }
        playNext();
    }

    public void onPause() {
        resumed = false;
        if (player != null) {
            try { player.pause(); } catch (Throwable ignored) { }
        }
    }

    public void refreshPreference() {
        if (!GamePreferences.isMusicEnabled(context)) {
            stop();
        } else if (resumed && player == null) {
            playNext();
        }
    }

    public void stop() {
        releasePlayer();
    }

    private void playNext() {
        if (!resumed || !GamePreferences.isMusicEnabled(context)) return;
        releasePlayer();
        String asset = TRACKS[nextTrack];
        nextTrack = (nextTrack + 1) % TRACKS.length;
        try (AssetFileDescriptor afd = context.getAssets().openFd(asset)) {
            MediaPlayer created = new MediaPlayer();
            created.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            created.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            created.setVolume(0.20f, 0.20f);
            created.setLooping(false);
            created.setOnCompletionListener(mp -> playNext());
            created.setOnErrorListener((mp, what, extra) -> {
                playNext();
                return true;
            });
            created.prepare();
            player = created;
            created.start();
        } catch (Throwable ignored) {
            releasePlayer();
        }
    }

    private void releasePlayer() {
        MediaPlayer local = player;
        player = null;
        if (local != null) {
            try { local.stop(); } catch (Throwable ignored) { }
            try { local.release(); } catch (Throwable ignored) { }
        }
    }
}
