package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.audiofx.Visualizer;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Music Glow (DESIGN-MUSIC-GLOW.md): while a music app plays, a ring of light in its album art's colours around the
 * picture, over every app (the key service's own overlay window), pulsing with the beat. It lives in `:keys` with the
 * service: it watches the media sessions (Now Playing's access), takes two colours from the art (Palette), and while
 * the music plays and the screen is on, listens to the TV's output mix for the beat (Beat: Visualizer FFT magnitudes,
 * never the microphone). GlowRingView draws it; the window exists only while it glows.
 */
final class GlowRing implements MediaSessionManager.OnActiveSessionsChangedListener, GlowRingView.Source, GlowRingView.Listener {
    static final String ACTION_SETTINGS = "io.freemytv.GLOW";

    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaController> controllers = new ArrayList<>();
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
        @Override public void onSessionDestroyed() { update(); }
    };
    /** The light: its own thread and windows, so the key service's thread never waits for it. */
    private final GlowRingView light;
    private boolean watching, screenOn = true, playing;
    private Bitmap artFrom;
    private String artPkg;
    private final Beat beat = new Beat();
    private HandlerThread worker;
    private Handler work;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                screenOn = false;
                light.post(() -> light.setScreen(false));
                beat.stop();
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                screenOn = true;
                light.post(() -> light.setScreen(true));
                update();
            } else {   // Settings changed it
                final int style = Prefs.glowStyle();
                light.post(() -> light.setStyle(style));
                update();
            }
        }
    };

    GlowRing(AccessibilityService s) {
        service = s;
        light = new GlowRingView(s, this, this);
    }

    void start() {
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(ACTION_SETTINGS);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) service.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            else service.registerReceiver(receiver, f);
        } catch (RuntimeException e) {
            AppLog.w("music glow: " + e.getMessage());
        }
        worker = new HandlerThread("glow");
        worker.start();
        work = new Handler(worker.getLooper());
        AppLog.w("music glow: " + (Prefs.glowOn() ? "on" : "off"));
        watch();
    }

    void stop() {
        try {
            service.unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
        }
        unwatch();
        beat.stop();
        light.quit();
        if (worker != null) worker.quitSafely();
        worker = null;
    }

    // MARK: - What's playing

    private void watch() {
        if (watching) return;
        MediaSessionManager m = service.getSystemService(MediaSessionManager.class);
        try {
            m.addOnActiveSessionsChangedListener(this, NowPlaying.component(service), main);
            watching = true;
            onActiveSessionsChanged(m.getActiveSessions(NowPlaying.component(service)));
        } catch (SecurityException e) {
            // Now Playing's access isn't given: no glow (it comes with the dynamic dot, Home Settings).
            AppLog.w("music glow: no access to what's playing");
        }
    }

    private void unwatch() {
        if (!watching) return;
        watching = false;
        try {
            service.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this);
        } catch (RuntimeException ignored) {
        }
        onActiveSessionsChanged(null);
    }

    @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
        for (MediaController mc : controllers) mc.unregisterCallback(callback);
        controllers.clear();
        if (sessions != null) {
            for (MediaController mc : sessions) {
                mc.registerCallback(callback, main);
                controllers.add(mc);
            }
        }
        update();
    }

    /** A music app's session that plays (most recent first), or null. */
    private MediaController musicPlaying() {
        for (MediaController mc : controllers) {
            PlaybackState s = mc.getPlaybackState();
            int st = s == null ? PlaybackState.STATE_NONE : s.getState();
            if (st != PlaybackState.STATE_PLAYING) continue;
            if (isMusic(mc)) return mc;
        }
        return null;
    }

    /**
     * A music app, or another app (never a video app, IPTV or YouTube) playing a song with both an artist and an
     * album: a light around a film or live TV would fight the picture.
     */
    static boolean isMusic(MediaController mc) {
        String pkg = mc.getPackageName();
        if (pkg == null || pkg.equals(HomeMode.PKG) || Wallpaper.videoApp(pkg) || Wallpaper.theaterApp(pkg)
                || Wallpaper.youTubeApp(pkg)) return false;
        if (Wallpaper.musicApp(pkg)) return true;
        MediaMetadata m = mc.getMetadata();
        return m != null && !TextUtils.isEmpty(m.getString(MediaMetadata.METADATA_KEY_ARTIST))
                && !TextUtils.isEmpty(m.getString(MediaMetadata.METADATA_KEY_ALBUM));
    }

    private void update() {
        MediaController mc = Prefs.glowOn() && screenOn ? musicPlaying() : null;
        boolean now = mc != null;
        if (now) colorsFrom(mc);
        if (now && !playing) {
            final int style = Prefs.glowStyle();
            light.post(() -> {
                light.setStyle(style);
                light.setOn(true);
            });
            beat.start();
        } else if (!now && playing) {
            light.post(() -> light.setOn(false));   // it breathes out; the listening stops once it's dark (onDark)
        }
        playing = now;
    }

    // MARK: - The colours

    /** The art's two colours, worked out off the main thread once per new picture. */
    private void colorsFrom(MediaController mc) {
        MediaMetadata m = mc.getMetadata();
        Bitmap art = null;
        if (m != null) {
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        String pkg = mc.getPackageName();
        if (art == artFrom && pkg.equals(artPkg)) return;
        artFrom = art;
        artPkg = pkg;
        final Bitmap src = art;
        final Context c = service;
        if (work == null) return;
        work.post(() -> {
            Bitmap b = src;
            try {
                if (b == null || b.isRecycled()) b = iconOf(c, pkg);
                final float[][] p = Palette.of(b);
                light.post(() -> light.setColors(p[0], p[1]));
            } catch (RuntimeException e) {
                AppLog.w("music glow: colours: " + e.getMessage());
            }
        });
    }

    private static Bitmap iconOf(Context c, String pkg) {
        try {
            android.graphics.drawable.Drawable d = c.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(b);
            d.setBounds(0, 0, 64, 64);
            d.draw(canvas);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** It breathed out: no window, no listening, until music plays again. */
    @Override public void onDark() {
        main.post(() -> {
            if (!playing) beat.stop();
        });
    }

    // MARK: - The beat (GlowRingView.Source)

    @Override public int beats() {
        return beat.count;
    }

    @Override public float energy() {
        return beat.energy;
    }

    /**
     * The TV's output mix as FFT magnitudes (Visualizer, session 0), about 20 times a second on its own thread, as
     * Lumn's AudioBeatDetector and MusicSyncClock do with their tap: a beat is the bass (40–150 Hz) over 1.35 × its
     * 1.6 s average and rising, at least 260 ms after the last; the energy is the loudness (40 Hz–6 kHz) with a fast
     * attack and a slow release, against a slowly falling peak. No data for a while (a protected or tunnelled stream):
     * energy −1, the steady glow.
     */
    static final class Beat {
        private static final int HISTORY = 32;
        volatile int count;
        volatile float energy = -1;
        private Visualizer visualizer;
        private HandlerThread thread;
        private final float[] bass = new float[HISTORY];
        private int at, filled;
        private float lastBass, env, peak = 1, baseline;
        private long lastBeat, lastSound;
        // Every 15 s while it listens, one line: beats heard, the mean energy and the mean full-band level.
        private long reportAt;
        private int reportBeats, frames;
        private float sumEnergy, sumFull;

        void start() {
            if (thread != null) return;
            thread = new HandlerThread("glow-beat");
            thread.start();
            new Handler(thread.getLooper()).post(this::open);
        }

        private void open() {
            try {
                Visualizer v = new Visualizer(0);
                v.setEnabled(false);
                int[] range = Visualizer.getCaptureSizeRange();
                v.setCaptureSize(Math.min(1024, range[1]));
                v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);   // the same beat at any volume
                int rate = Math.min(Visualizer.getMaxCaptureRate(), 25_000);
                v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override public void onWaveFormDataCapture(Visualizer vis, byte[] wave, int samplingRate) {}

                    @Override public void onFftDataCapture(Visualizer vis, byte[] fft, int samplingRate) {
                        analyse(fft, samplingRate);
                    }
                }, rate, false, true);
                v.setEnabled(true);
                visualizer = v;
                lastSound = SystemClock.uptimeMillis();
                AppLog.w("music glow: listening (" + v.getCaptureSize() + " at " + rate / 1000 + " Hz)");
            } catch (RuntimeException e) {   // no permission, or a TV whose output can't be read: the steady glow
                AppLog.w("music glow: can't hear the music: " + e.getMessage());
                energy = -1;
            }
        }

        void stop() {
            HandlerThread t = thread;
            if (t == null) return;
            thread = null;
            new Handler(t.getLooper()).post(() -> {
                if (visualizer != null) {
                    try {
                        visualizer.setEnabled(false);
                        visualizer.release();
                    } catch (RuntimeException ignored) {
                    }
                    visualizer = null;
                }
                Arrays.fill(bass, 0);
                at = filled = 0;
                env = baseline = 0;
                peak = 1;
                energy = -1;
                t.quitSafely();
            });
        }

        private void analyse(byte[] fft, int samplingRateMilli) {
            int n = fft.length / 2;   // bins: [0] DC, [1] Nyquist, then (re, im) pairs
            float hzPerBin = samplingRateMilli / 1000f / fft.length;
            if (n < 4 || hzPerBin <= 0) return;
            float b = 0, full = 0;
            for (int k = 1; k < n; k++) {
                float hz = k * hzPerBin;
                if (hz > 6000) break;
                if (hz < 40) continue;
                float re = fft[2 * k], im = fft[2 * k + 1];
                float mag = (float) Math.sqrt(re * re + im * im);
                full += mag;
                if (hz <= 150) b += mag;
            }
            long now = SystemClock.uptimeMillis();
            // Loudness: fast attack, slow release, over a peak that falls slowly (quiet songs still glow).
            env = full > env ? full : env * 0.86f + full * 0.14f;
            peak = Math.max(env, peak * 0.996f);
            // Against the song's own baseline (its last few seconds, as Lumn's MusicSyncClock): louder than usual
            // lights up, quieter settles, whatever the song's overall level.
            baseline = baseline == 0 ? env : baseline + (env - baseline) * 0.02f;
            if (full > 6) lastSound = now;
            if (now - lastSound > 1500) {   // silence or no data: the steady glow
                energy = -1;
            } else {
                float over = (env - baseline) / Math.max(peak - baseline, peak * 0.08f);
                energy = Math.max(0, Math.min(1, 0.5f + 0.5f * over));
            }
            // The beat: the bass jumping over its own recent average.
            float avg = 0;
            for (int i = 0; i < filled; i++) avg += bass[i];
            avg = filled == 0 ? b : avg / filled;
            if (filled >= 8 && b > avg * 1.35f + 2 && b > lastBass && now - lastBeat > 260 && energy >= 0) {
                lastBeat = now;
                count++;
            }
            frames++;
            sumEnergy += Math.max(0, energy);
            sumFull += full;
            if (now - reportAt > 15_000) {
                if (reportAt != 0 && frames > 0) {
                    AppLog.w(String.format(java.util.Locale.ROOT, "music glow: %d beats in 15 s, energy %.2f, level %.0f%s",
                            count - reportBeats, sumEnergy / frames, sumFull / frames, energy < 0 ? " (no audio: steady)" : ""));
                }
                reportAt = now;
                reportBeats = count;
                frames = 0;
                sumEnergy = sumFull = 0;
            }
            lastBass = b;
            bass[at] = b;
            at = (at + 1) % HISTORY;
            filled = Math.min(HISTORY, filled + 1);
        }
    }

    // MARK: - The colours of a picture

    /**
     * Two colours from album art, as Lumn's accent and deep: the most vivid hue (weighted by saturation and
     * brightness), and a second one at least 50° away (else a darker neighbour). Both lifted to glow; grey art gets
     * a soft silver and blue.
     */
    static final class Palette {
        private static final int BINS = 24;

        static float[][] of(Bitmap art) {
            if (art == null) return grey();
            Bitmap small = Bitmap.createScaledBitmap(art, 32, 32, true);
            float[] weight = new float[BINS], r = new float[BINS], g = new float[BINS], bl = new float[BINS];
            float[] hsv = new float[3];
            float total = 0;
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    int c = small.getPixel(x, y);
                    Color.colorToHSV(c, hsv);
                    if (hsv[2] < 0.15f) continue;
                    float wgt = hsv[1] * hsv[1] * (float) Math.pow(hsv[2], 1.5);
                    int bin = Math.min(BINS - 1, (int) (hsv[0] / 360f * BINS));
                    weight[bin] += wgt;
                    r[bin] += Color.red(c) * wgt;
                    g[bin] += Color.green(c) * wgt;
                    bl[bin] += Color.blue(c) * wgt;
                    total += wgt;
                }
            }
            if (small != art) small.recycle();
            if (total < 8) return grey();   // black and white art
            // Smooth the hue circle a little, then the strongest hue and a second one far enough from it.
            float[] smooth = new float[BINS];
            for (int i = 0; i < BINS; i++) {
                smooth[i] = weight[i] + 0.5f * (weight[(i + 1) % BINS] + weight[(i + BINS - 1) % BINS]);
            }
            int first = 0;
            for (int i = 1; i < BINS; i++) if (smooth[i] > smooth[first]) first = i;
            int second = -1;
            for (int i = 0; i < BINS; i++) {
                int dist = Math.abs(i - first);
                dist = Math.min(dist, BINS - dist);
                if (dist * 360f / BINS < 50) continue;
                if (smooth[i] < smooth[first] * 0.22f) continue;
                if (second < 0 || smooth[i] > smooth[second]) second = i;
            }
            float[] accent = lift(mean(first, weight, r, g, bl), 0.62f, 0.95f);
            float[] deep;
            if (second >= 0) {
                deep = lift(mean(second, weight, r, g, bl), 0.70f, 0.78f);
            } else {   // one hue: a deeper neighbour of it
                float[] h = toHsv(accent);
                h[0] = (h[0] + 330) % 360;
                h[1] = Math.min(1, h[1] + 0.12f);
                h[2] = 0.70f;
                deep = fromHsv(h);
            }
            return new float[][] {accent, deep};
        }

        private static float[] mean(int bin, float[] w, float[] r, float[] g, float[] b) {
            float s = Math.max(w[bin], 1e-4f);
            return new float[] {r[bin] / s / 255f, g[bin] / s / 255f, b[bin] / s / 255f};
        }

        /** At least this saturated, and this bright: a light, not a print. */
        private static float[] lift(float[] rgb, float minSat, float value) {
            float[] h = toHsv(rgb);
            h[1] = Math.max(h[1], minSat);
            h[2] = Math.max(h[2], value);
            return fromHsv(h);
        }

        private static float[] toHsv(float[] rgb) {
            float[] h = new float[3];
            Color.RGBToHSV(Math.round(rgb[0] * 255), Math.round(rgb[1] * 255), Math.round(rgb[2] * 255), h);
            return h;
        }

        private static float[] fromHsv(float[] h) {
            int c = Color.HSVToColor(h);
            return new float[] {Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f};
        }

        private static float[][] grey() {
            return new float[][] {{0.94f, 0.95f, 1f}, {0.52f, 0.62f, 0.86f}};
        }
    }
}
