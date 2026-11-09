package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Music Glow's light (DESIGN-MUSIC-GLOW.md): the album art's two colours sliding around the rim of the picture, a thin
 * line right at the edge and a soft glow fading inward; each beat flashes it and widens it a little, loudness makes it
 * brighter and faster. Music in: it swells in. Out: it breathes out, its flow slowing as it fades.
 *
 * Made to cost next to nothing on a 2 GB TV (the first version, one shader over the whole picture on the key thread,
 * ran at 13 fps and made every key wait):
 * - Its own thread: the four windows belong to a HandlerThread, so the key service's thread never waits for a frame.
 * - Four thin windows, one per edge, as deep as the glow reaches: the picture's middle isn't composited at all.
 * - A tiny picture per edge: its glow (the colours along it, faded across it) is painted once per colour change at a
 *   quarter of the size, and a frame only slides and stretches it (a repeating BitmapShader, filtered): the cheapest
 *   draw a GPU has, and a soft light loses nothing at that size. A colour change cross-fades the old picture out.
 *   (Per-pixel gradients still took 17 ms of the GPU per edge on a TCL.) The same on Android 8 to 14.
 * - 25 frames a second, and none once it's dark (then the windows go). Measured on a 2.45 GB TCL with Spotify:
 *   median frame 5 ms, GPU 1 ms, 6 % late (it was 77 ms, 17 ms per edge and 72 %).
 * GlowRing calls it through post(); everything here runs on the light's thread.
 */
final class GlowRingView {
    /** How the light behaves now, read once a frame (GlowRing writes it from the audio thread). */
    interface Source {
        int beats();

        /** 0–1: louder than the song's usual (0.5 is its usual); -1: no audio data (a steady, breathing glow). */
        float energy();
    }

    interface Listener {
        /** It breathed out completely: the windows are gone. */
        void onDark();
    }

    // Glow Style: the line's width, the glow's width (px at 1080p) and the strength.
    private static final float[][] STYLES = {{2.5f, 30, 0.42f}, {3.5f, 46, 0.58f}, {4.5f, 70, 0.74f}};
    private static final long FRAME_MS = 40;   // 25 fps: plenty for a soft, slow light, and a quarter less work than 33
    private static final int TOP = 0, RIGHT = 1, BOTTOM = 2, LEFT = 3;

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Source source;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("glow-light");
    private final Handler handler;
    private final Edge[] edges = new Edge[4];
    private boolean shown, wanted, screenOn = true, posted;
    private int style = 1, lastBeats;
    private float w, h, k = 1, clock, phase, flow, bloom, bloomTarget;
    private long lastNanos, lastDraw;
    private final Spring amount = new Spring(0.9f, 1f, 0), energy = new Spring(Spring.SNAPPY_FOCUS, 0.5f);
    private final Spring blend = new Spring(1.4f, 1f, 1);
    private final float[] accent = {1, 1, 1}, deep = {0.5f, 0.6f, 0.8f}, accentTo = {1, 1, 1}, deepTo = {0.5f, 0.6f, 0.8f};
    private final float[] accentFrom = new float[3], deepFrom = new float[3];
    private final Paint white = new Paint();

    GlowRingView(AccessibilityService s, Source source, Listener listener) {
        service = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        this.source = source;
        this.listener = listener;
        white.setColor(0xFFFFFFFF);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Runs `r` on the light's thread. */
    void post(Runnable r) {
        handler.post(r);
    }

    void quit() {
        handler.post(() -> {
            removeWindows();
            thread.quitSafely();
        });
    }

    // MARK: - On the light's thread (GlowRing posts these)

    /** Glow Style: 0 Subtle, 1 Balanced, 2 Vivid. The windows' depth follows. */
    void setStyle(int s) {
        int st = Math.max(0, Math.min(2, s));
        if (st == style) return;
        style = st;
        if (shown) {
            removeWindows();
            addWindows();
            wake();
        }
    }

    void setColors(float[] a, float[] d) {
        System.arraycopy(accent, 0, accentFrom, 0, 3);
        System.arraycopy(deep, 0, deepFrom, 0, 3);
        System.arraycopy(a, 0, accentTo, 0, 3);
        System.arraycopy(d, 0, deepTo, 0, 3);
        if (amount.value < 0.01f) {
            blend.snap(1);
        } else {
            blend.snap(0);
            blend.target = 1;
        }
        mixColors();
        wake();
    }

    /** Music playing (true) or not: it swells in, or breathes out. */
    void setOn(boolean on) {
        wanted = on;
        if (on && !shown) addWindows();
        amount.tune(on ? 0.9f : 1.6f, 1f);
        amount.target = on ? 1 : 0;
        if (on) lastBeats = source.beats();   // no flash for beats from before
        wake();
    }

    void setScreen(boolean on) {
        screenOn = on;
        if (on) wake();
    }

    // MARK: - Windows

    private void addWindows() {
        try {
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(m);
            w = m.widthPixels;
            h = m.heightPixels;
            k = w / 1920f;
            int band = Math.round(reach() * 1.35f);   // room for the beat's widening
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            for (int side = 0; side < 4; side++) {
                boolean across = side == TOP || side == BOTTOM;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(across ? (int) w : band, across ? band : (int) h,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
                lp.gravity = side == TOP ? Gravity.TOP | Gravity.LEFT : side == BOTTOM ? Gravity.BOTTOM | Gravity.LEFT
                        : side == LEFT ? Gravity.TOP | Gravity.LEFT : Gravity.TOP | Gravity.RIGHT;
                lp.setTitle("mahitTV Music Glow");
                Edge e = new Edge(service, side, band);
                wm.addView(e, lp);
                edges[side] = e;
            }
            shown = true;
        } catch (RuntimeException e) {
            AppLog.w("music glow: window: " + e.getMessage());
            removeWindows();
        }
    }

    private void removeWindows() {
        for (int i = 0; i < 4; i++) {
            if (edges[i] == null) continue;
            try {
                wm.removeView(edges[i]);
            } catch (RuntimeException ignored) {
            }
            edges[i] = null;
        }
        shown = false;
    }

    /** How far in the glow reaches (px), where it has faded out. */
    private float reach() {
        return STYLES[style][1] * 3.2f * k;
    }

    // MARK: - Frames

    private final Choreographer.FrameCallback frame = this::doFrame;

    private void wake() {
        if (posted || !screenOn || !shown) return;
        posted = true;
        lastNanos = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private void doFrame(long nanos) {
        posted = false;
        if (!screenOn || !shown) return;
        float dt = lastNanos == 0 ? 1 / 30f : Math.min(0.1f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        clock += dt;
        amount.step(dt);
        blend.step(dt);
        mixColors();
        int beats = source.beats();
        float e = source.energy();
        if (beats != lastBeats && wanted) {
            lastBeats = beats;
            bloomTarget = 1;
        }
        if (e < 0) {   // no audio data: a steady glow that breathes every 4.5 s (Lumn's idle glow)
            bloomTarget = 0;
            energy.target = 0.5f + 0.1f * (float) Math.sin(clock * 2 * Math.PI / 4.5);
        } else {
            energy.target = e;
        }
        // The beat: a fast rise (about 35 ms) to its target, which falls away over about 200 ms.
        bloom += (bloomTarget - bloom) * Math.min(1, dt / 0.035f);
        bloomTarget *= (float) Math.exp(-dt / 0.2f);
        energy.step(dt);
        // The colours slide faster with the music, and slow down as it fades out.
        float speed = (0.03f + 0.05f * Ui.clamp(energy.value) + 0.12f * bloom) * Ui.clamp(amount.value);
        flow += (speed - flow) * Math.min(1, dt / 0.6f);
        phase = (phase + flow * dt) % 1f;
        long now = SystemClock.uptimeMillis();
        if (now - lastDraw >= FRAME_MS - 4) {
            lastDraw = now;
            for (Edge ed : edges) if (ed != null) ed.invalidate();
        }
        if (!wanted && amount.value <= 0.002f && amount.settled()) {
            amount.snap(0);
            bloom = bloomTarget = 0;
            removeWindows();
            listener.onDark();
            return;
        }
        posted = true;
        Choreographer.getInstance().postFrameCallbackDelayed(frame, FRAME_MS / 2);
    }

    private void mixColors() {
        float t = Ui.clamp(blend.value);
        for (int i = 0; i < 3; i++) {
            accent[i] = accentFrom[i] + (accentTo[i] - accentFrom[i]) * t;
            deep[i] = deepFrom[i] + (deepTo[i] - deepFrom[i]) * t;
        }
    }

    private static int rgb(float[] c) {
        return 0xFF000000 | Math.round(Ui.clamp(c[0]) * 255) << 16 | Math.round(Ui.clamp(c[1]) * 255) << 8
                | Math.round(Ui.clamp(c[2]) * 255);
    }

    /** The tiles are painted at this fraction of the screen's size, and drawn stretched back. */
    private static final int TILE = 4;

    /**
     * One edge: its glow is a small repeating tile (the deep → accent → deep colours along the rim, faded across from
     * the edge in), slid along the rim by the phase so the colours flow on round the corners, stretched across by the
     * beat; and the bright line at the edge. A colour change paints a new tile and cross-fades the old one out.
     */
    private final class Edge extends View {
        private final int side;
        private final float band;
        private final boolean vertical;
        private final Paint glow = new Paint(Paint.FILTER_BITMAP_FLAG), old = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint line = new Paint(Paint.DITHER_FLAG);
        private final Matrix m = new Matrix(), along = new Matrix();
        private BitmapShader tile, oldTile;
        private LinearGradient colors;
        private int madeAccent, madeDeep;
        private float oldFade;   // 1 → 0 as the old tile goes

        Edge(Context c, int side, int band) {
            super(c);
            this.side = side;
            this.band = band;
            vertical = side == RIGHT || side == LEFT;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        /** A new tile for these colours (about once a song; a cross-fade paints at most a few a second). */
        private void make(int a, int d) {
            if (tile != null && a == madeAccent && d == madeDeep) return;
            boolean fresh = tile == null;
            madeAccent = a;
            madeDeep = d;
            float period = w + h;   // exactly two deep–accent–deep cycles around the rim: no seam where it closes
            int len = Math.max(8, Math.round(period / TILE)), deepPx = Math.max(4, Math.round(band / TILE));
            Bitmap b = Bitmap.createBitmap(vertical ? deepPx : len, vertical ? len : deepPx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            LinearGradient col = vertical ? new LinearGradient(0, 0, 0, len, new int[] {d, a, d}, null, Shader.TileMode.REPEAT)
                    : new LinearGradient(0, 0, len, 0, new int[] {d, a, d}, null, Shader.TileMode.REPEAT);
            // The fade across, from the edge (full) in to nothing: close to an exponential.
            int[] alphas = {0xFF000000, 0x8C000000, 0x40000000, 0x17000000, 0x06000000, 0x00000000};
            float[] stops = {0f, 0.12f, 0.3f, 0.52f, 0.76f, 1f};
            LinearGradient fade = vertical ? new LinearGradient(0, 0, deepPx, 0, alphas, stops, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, deepPx, alphas, stops, Shader.TileMode.CLAMP);
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(new ComposeShader(col, fade, PorterDuff.Mode.DST_IN));
            c.drawRect(0, 0, b.getWidth(), b.getHeight(), p);
            oldTile = fresh ? null : tile;
            oldFade = fresh ? 0 : 1;
            tile = new BitmapShader(b, vertical ? Shader.TileMode.CLAMP : Shader.TileMode.REPEAT,
                    vertical ? Shader.TileMode.REPEAT : Shader.TileMode.CLAMP);
            glow.setShader(tile);
            old.setShader(oldTile);
            colors = vertical ? new LinearGradient(0, 0, 0, period, new int[] {d, a, d}, null, Shader.TileMode.REPEAT)
                    : new LinearGradient(0, 0, period, 0, new int[] {d, a, d}, null, Shader.TileMode.REPEAT);
            line.setShader(colors);
        }

        @Override protected void onDraw(Canvas c) {
            float a = Ui.clamp(amount.value);
            if (a <= 0.002f) return;
            // During a song's cross-fade the colours change every frame: a new tile at most every 5th of a second.
            int ac = rgb(accent), dc = rgb(deep);
            if (tile == null || blend.value >= 0.999f || SystemClock.uptimeMillis() - madeAt > 200) {
                if (ac != madeAccent || dc != madeDeep) madeAt = SystemClock.uptimeMillis();
                make(ac, dc);
            }
            float[] st = STYLES[style];
            float cw = getWidth(), ch = getHeight();
            // Along the rim: where this edge starts on it (clockwise from the top left), less the flow.
            float shift = phase * (w + h) * 2;
            float start = side == TOP ? 0 : side == RIGHT ? w : side == BOTTOM ? w + h : 2 * w + h;
            // The rim's own coordinate s runs clockwise from the top left; each edge maps it onto its axis (forward
            // for the top and right, backward for the bottom and left) offset by `pos`, so the corners meet.
            float pos = -(start + shift);
            // Across: from the screen's edge inward, a little deeper on the beat and when it's loud.
            float deeper = (reach() / (band / 1.35f)) * (1 + 0.22f * bloom + 0.1f * Ui.clamp(energy.value));
            float sAlong = TILE, sAcross = TILE * deeper;
            m.reset();
            switch (side) {
                case TOP: m.setScale(sAlong, sAcross); m.postTranslate(pos, 0); break;
                case BOTTOM: m.setScale(-sAlong, -sAcross); m.postTranslate(w - pos, ch); break;
                case RIGHT: m.setScale(-sAcross, sAlong); m.postTranslate(cw, pos); break;
                default: m.setScale(sAcross, -sAlong); m.postTranslate(0, h - pos); break;
            }
            tile.setLocalMatrix(m);
            float e = Ui.clamp(energy.value);
            float strength = a * st[2] * (0.62f + 0.28f * e + 0.32f * bloom);
            oldFade = Math.max(0, oldFade - 0.12f);
            glow.setAlpha(Math.round(255 * Math.min(1, strength)));
            c.drawRect(0, 0, cw, ch, glow);
            if (oldTile != null && oldFade > 0) {
                oldTile.setLocalMatrix(m);
                old.setAlpha(Math.round(255 * Math.min(1, strength) * oldFade));
                c.drawRect(0, 0, cw, ch, old);
                if (oldFade <= 0) oldTile = null;
            }
            // The bright line right at the edge, whiter on the beat.
            along.reset();
            switch (side) {
                case TOP: along.setTranslate(pos, 0); break;
                case RIGHT: along.setTranslate(0, pos); break;
                case BOTTOM: along.setScale(-1, 1); along.postTranslate(w - pos, 0); break;
                default: along.setScale(1, -1); along.postTranslate(0, h - pos); break;
            }
            colors.setLocalMatrix(along);
            float lw = st[0] * k * (1 + 0.6f * bloom);
            line.setAlpha(Math.round(255 * Math.min(1, strength * 1.15f)));
            float l, t, r, b;
            switch (side) {
                case TOP: l = 0; t = 0; r = cw; b = lw; break;
                case BOTTOM: l = 0; t = ch - lw; r = cw; b = ch; break;
                case LEFT: l = 0; t = 0; r = lw; b = ch; break;
                default: l = cw - lw; t = 0; r = cw; b = ch; break;
            }
            c.drawRect(l, t, r, b, line);
            if (bloom > 0.02f) {
                white.setAlpha(Math.round(255 * Math.min(1, 0.35f * bloom * a)));
                c.drawRect(l, t, r, b, white);
            }
        }

        private long madeAt;
    }
}
