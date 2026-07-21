package com.bwa3d.snappenalty;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

public final class BackgroundMusicPlayer {
    private static final float NORMAL_VOLUME = 0.22f;
    private static final float MICROPHONE_VOLUME = 0.11f;

    private final Context context;
    private final int[] tracks = {
            R.raw.music_tubthumping,
            R.raw.music_song2
    };

    private MediaPlayer player;
    private int trackIndex;
    private boolean playRequested;
    private boolean microphoneActive;

    public BackgroundMusicPlayer(Context context) {
        this.context = context.getApplicationContext();
    }

    public void startOrResume() {
        playRequested = true;
        if (!GamePreferences.isMusicEnabled(context)) {
            releasePlayer();
            return;
        }
        if (player == null) {
            createAndStartCurrentTrack();
        } else {
            applyVolume();
            try {
                if (!player.isPlaying()) {
                    player.start();
                }
            } catch (IllegalStateException ignored) {
                releasePlayer();
                createAndStartCurrentTrack();
            }
        }
    }

    public void pause() {
        playRequested = false;
        if (player == null) return;
        try {
            if (player.isPlaying()) {
                player.pause();
            }
        } catch (IllegalStateException ignored) {
            releasePlayer();
        }
    }

    public void setMicrophoneActive(boolean active) {
        microphoneActive = active;
        applyVolume();
    }

    public void release() {
        playRequested = false;
        releasePlayer();
    }

    private void createAndStartCurrentTrack() {
        releasePlayer();
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            MediaPlayer local = MediaPlayer.create(context, tracks[trackIndex], attributes, 0);
            if (local == null) return;
            local.setLooping(false);
            local.setOnCompletionListener(completed -> {
                releasePlayer();
                trackIndex = (trackIndex + 1) % tracks.length;
                if (playRequested && GamePreferences.isMusicEnabled(context)) {
                    createAndStartCurrentTrack();
                }
            });
            local.setOnErrorListener((failed, what, extra) -> {
                releasePlayer();
                trackIndex = (trackIndex + 1) % tracks.length;
                return true;
            });
            player = local;
            applyVolume();
            if (playRequested) {
                player.start();
            }
        } catch (Throwable ignored) {
            releasePlayer();
        }
    }

    private void applyVolume() {
        if (player == null) return;
        float volume = microphoneActive ? MICROPHONE_VOLUME : NORMAL_VOLUME;
        try {
            player.setVolume(volume, volume);
        } catch (IllegalStateException ignored) {
        }
    }

    private void releasePlayer() {
        MediaPlayer local = player;
        player = null;
        if (local == null) return;
        try {
            local.setOnCompletionListener(null);
            local.setOnErrorListener(null);
            local.release();
        } catch (Throwable ignored) {
        }
    }
}
