package io.freemytv;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import java.io.File;

/** Muted, non-interactive Aerial playback.  The activity owns its lifecycle. */
final class WallpaperVideoView extends TextureView implements TextureView.SurfaceTextureListener {
    private MediaPlayer player;
    private Surface surface;
    private File source;
    private Runnable failed;
    private long generation;

    WallpaperVideoView(Context context) {
        super(context);
        setSurfaceTextureListener(this);
        setFocusable(false); setFocusableInTouchMode(false); setKeepScreenOn(false);
        setVisibility(INVISIBLE);
    }

    void show(File next, Runnable onFailed) {
        generation++;
        source = next; failed = onFailed;
        if (next == null || !next.isFile()) { fail(generation); return; }
        setVisibility(VISIBLE);
        if (isAvailable()) start(generation, next);
    }

    void hide() {
        generation++; source = null; failed = null;
        if (player != null) { try { player.reset(); } catch (IllegalStateException ignored) { } }
        setVisibility(INVISIBLE);
    }

    void release() {
        generation++; source = null; failed = null;
        if (player != null) { player.reset(); player.release(); player = null; }
        if (surface != null) { surface.release(); surface = null; }
        setVisibility(INVISIBLE);
    }

    private void start(final long token, final File requested) {
        if (requested == null || surface == null || token != generation || requested != source) return;
        try {
            if (player != null) { player.reset(); player.release(); }
            player = new MediaPlayer();
            player.setSurface(surface); player.setVolume(0f, 0f); player.setLooping(true);
            player.setOnVideoSizeChangedListener((p, width, height) -> crop(width, height));
            player.setOnPreparedListener(p -> {
                if (token != generation || requested != source || getVisibility() != VISIBLE) return;
                crop(p.getVideoWidth(), p.getVideoHeight()); p.start();
            });
            player.setOnErrorListener((p, what, extra) -> { fail(token); return true; });
            player.setDataSource(source.getAbsolutePath()); player.prepareAsync();
        } catch (Exception e) { fail(token); }
    }

    private void crop(int videoWidth, int videoHeight) {
        int width = getWidth(), height = getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return;
        float scale = Math.max((float) width / videoWidth, (float) height / videoHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate((width - videoWidth * scale) / 2f, (height - videoHeight * scale) / 2f);
        setTransform(matrix);
    }
    private void fail(long token) {
        if (token != generation) return;
        Runnable r = failed;
        hide();
        if (r != null) r.run();
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) { surface = new Surface(texture); start(generation, source); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { if (player != null) crop(player.getVideoWidth(), player.getVideoHeight()); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { hide(); if (surface != null) { surface.release(); surface = null; } return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
}
