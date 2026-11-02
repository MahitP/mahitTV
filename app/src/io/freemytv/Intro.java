package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

/**
 * mahitTV's startup, "Anamorphic Horizon & Optical Liquid Glass Ignition", 3.10 s at 60 fps, in four acts:
 * <ol>
 * <li>Act I, lens ignition (0.00–0.75 s): from a calm cinema slate (#070B12), an anamorphic flare ribbon ignites on the
 * horizon line (0.46·H), 2 → 14 px, a pearl core in a warm-to-cyan bloom across 85% of the width; from 0.20 s the seam
 * opens like an aperture (spring 0.55 / 0.84) onto the Frosted Horizon (day, or its night), which breathes under slow
 * atmospheric drift waves (Android 13+: Shaders.HORIZON_RIPPLE; below, a drifting bitmap).</li>
 * <li>Act II, Liquid Glass materialization (0.45–1.85 s): each glyph of "mahitTV" condenses on its own spring
 * (0.48 / 0.86), 22 ms apart: 1.14× → 1, 14 px → 0 and out of a 28 px defocus. Three layers: the refractive frosted body,
 * a 0 18 44 slate shadow with the inner top bevel, the 1.5 px specular rim (Shaders.GLASS_WORD; layered Canvas below).</li>
 * <li>Act III, prismatic caustic sweep (1.15–2.35 s): a −28° volumetric beam crosses the screen (−0.3·W → 1.3·W, slowest
 * over the word, at its centre at 1.45 s), splitting the rim 1.8 px into cyan, white and amber; at 1.45 s a gossamer
 * glass ring spreads from the word over the sky (80 → 980 px, 0.28 → 0).</li>
 * <li>Act IV, zero-cut morph (2.35–3.10 s): "Who's Watching?" appears underneath on the same Horizon bitmap at 1:1 (the
 * drift, the beam and the ring are back to 0 by then, so the sky never changes) and its cards spring in 32 ms apart,
 * while the wordmark exhales (1 → 0.95, 0 → 14 px of blur, out) over 360 ms.</li>
 * </ol>
 * The sound (Click.chime: sub swell, Cmaj9 glass bloom, the shimmer that follows the beam) starts with the first frame
 * and carries its own timing. OK or Back jumps to the handover. Reduce motion: the Horizon and the still glass
 * wordmark for 0.7 s, then the handover at once. Everything is made in onSizeChanged, theme() and start(); a frame only
 * moves numbers (no allocation in doFrame or onDraw). The glass bitmaps are built on a background thread; until they're
 * ready the Canvas layers stand in.
 */
final class Intro extends View implements Choreographer.FrameCallback {
    // MARK: - Every parameter of the startup (seconds; px at 1080p, scaled by k = w / 1920)

    /** Act IV: the handover to the lock, the wordmark's exhale after it, the end, and the reduced (no motion) hold. */
    static final float INTRO_HANDOVER = 2.35f, INTRO_WORD_OUT = 0.36f, INTRO_END = 3.10f, INTRO_REDUCED_HOLD = 0.7f;
    static final float INTRO_WORD_TO_OUT = 0.95f, INTRO_EXHALE_BLUR = 14f;
    /** The intro keeps drawing its own Horizon this long past the handover (the lock's is identical under it). */
    static final float INTRO_SKY_OVERLAP = 0.10f;
    /** The cinema slate the panel wakes on. */
    static final int INTRO_MIDNIGHT = 0xFF070B12;
    /** The lock's faint frost over the Horizon (LockView.VEIL_DAY / VEIL_NIGHT): drawn here too, so the handover is exact. */
    static final int INTRO_VEIL_DAY = 0x1AFFFFFF, INTRO_VEIL_NIGHT = 0x24000000;

    /** Act I: the flare ribbon (its line, span, thickness, ignition and fade) and its bloom. */
    static final float INTRO_SEAM_Y = 0.46f, INTRO_RIBBON_SPAN = 0.85f, INTRO_RIBBON_THIN = 2f, INTRO_RIBBON_THICK = 14f;
    static final float INTRO_RIBBON_IGNITE = 0.20f, INTRO_RIBBON_OUT_FROM = 0.30f, INTRO_RIBBON_OUT_TO = 0.75f;
    static final float INTRO_BLOOM_FROM = 22f, INTRO_BLOOM_TO = 160f, INTRO_BLOOM_PEAK = 0.80f;
    static final int INTRO_RIBBON_CORE = 0xFFFFF7ED, INTRO_BLOOM_WARM = 0xFFF5EBD6, INTRO_BLOOM_COOL = 0xFF7DD3FC;
    /** The aperture: opens from here on its spring; its feathered edge is 8 px + 10% of H × open. */
    static final float INTRO_APERTURE_AT = 0.20f, INTRO_FEATHER = 8f, INTRO_FEATHER_GROW = 0.10f;
    static final float[] INTRO_APERTURE = {0.55f, 0.84f};
    /** The atmospheric drift: displacement (px), spatial frequency (per px), light; in with the aperture, out by the handover. */
    static final float INTRO_DRIFT_PX = 6f, INTRO_DRIFT_FREQ = 0.0034f, INTRO_DRIFT_LIGHT = 0.035f;
    static final float INTRO_DRIFT_IN_TO = 0.75f, INTRO_DRIFT_OUT_FROM = 1.85f;

    /** Act II: the wordmark (a name, not translated), size, tracking; the glyphs' start, stagger and spring. */
    static final String INTRO_WORDMARK = "mahitTV";
    static final float INTRO_WORDMARK_SIZE = 124f, INTRO_WORDMARK_TRACKING = -0.03f;
    static final float INTRO_WORD_AT = 0.45f, INTRO_GLYPH_STAGGER = 0.022f;
    static final float[] INTRO_GLYPH = {0.48f, 0.86f};
    /** Each glyph condenses from this scale, this far below, out of this defocus (px). */
    static final float INTRO_GLYPH_FROM = 1.14f, INTRO_GLYPH_RISE = 14f, INTRO_DEFOCUS = 28f;
    /** Layer 1: the frosted body (white, by day and at night), the refraction and its R/B split. */
    static final float INTRO_BODY_DAY_A = 0.24f, INTRO_BODY_NIGHT_A = 0.16f, INTRO_REFRACTION = 10f, INTRO_FRINGE = 1.5f;
    /** Layer 2: the elevation shadow (0 18 px 44 px, slate 0.22) and the inner top bevel's light. */
    static final int INTRO_SHADOW = 0x380F172A;
    static final float INTRO_SHADOW_Y = 18f, INTRO_SHADOW_BLUR = 44f, INTRO_BEVEL = 0.82f;
    /** The inner optical shadow: how much the glass's thickness darkens it toward its edges (by day, at night). */
    static final float INTRO_DEPTH_DAY = 0.30f, INTRO_DEPTH_NIGHT = 0.22f;
    /** Layer 3: the specular rim's width (px). */
    static final float INTRO_RIM = 1.5f;

    /** Act III: the caustic beam (when, angle, its width over the word and over the sky, its light on the sky). */
    static final float INTRO_SWEEP_FROM = 1.15f, INTRO_SWEEP_TO = 1.75f, INTRO_SWEEP_ANGLE = -28f;
    static final float INTRO_SWEEP_WIDTH = 300f, INTRO_BEAM_WIDTH = 0.09f, INTRO_BEAM_LIGHT = 0.07f, INTRO_SWEEP_LIGHT = 0.42f;
    /** The prism: cyan leading, diamond white, amber trailing, this far apart (px). */
    static final int INTRO_PRISM_A = 0xFF38BDF8, INTRO_PRISM_B = 0xFFFFFFFF, INTRO_PRISM_C = 0xFFFDE68A;
    static final float INTRO_PRISM_PX = 1.8f;
    /** The glass resonance ring: when, its radius (px), ring width and bend (px), peak light. */
    static final float INTRO_RIPPLE_FROM = 1.45f, INTRO_RIPPLE_TO = 2.35f, INTRO_RIPPLE_R0 = 80f, INTRO_RIPPLE_R1 = 980f;
    static final float INTRO_RIPPLE_WIDTH = 36f, INTRO_RIPPLE_AMP = 5f, INTRO_RIPPLE_LIGHT = 0.28f;

    private static final int GLYPHS = 7;

    interface Listener {
        /** The handover: "Who's Watching?" should appear now, under this view (its Horizon at 1:1). */
        void onIntroEnding();

        /** The intro is gone. */
        void onIntroDone();
    }

    private final Listener listener;
    private boolean running, endingSent, reduced, opened, night = true;
    private long startNanos, lastNanos;
    private float t, handoverAt, doneAt;
    private float w, h, k = 1;
    private final Spring aperture = new Spring(INTRO_APERTURE, 0);
    private final Spring[] glyph = new Spring[GLYPHS];

    private final Paint horizonPaint = new Paint(Paint.FILTER_BITMAP_FLAG), corePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bloomPaint = new Paint(Paint.FILTER_BITMAP_FLAG), topFeather = new Paint(), bottomFeather = new Paint();
    private final Paint ringPaint = new Paint(Paint.FILTER_BITMAP_FLAG), softPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint(Paint.FILTER_BITMAP_FLAG), beamPaint = new Paint();
    private final Paint word = new Paint(Paint.ANTI_ALIAS_FLAG), frost = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skyText = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bright = new Paint(Paint.ANTI_ALIAS_FLAG), prismA = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint prismB = new Paint(Paint.ANTI_ALIAS_FLAG), prismC = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint(), addLayer = new Paint();
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG), livePaint = new Paint(), veil = new Paint();
    private final Matrix topM = new Matrix(), bottomM = new Matrix(), bandM = new Matrix(), beamM = new Matrix(), skyM = new Matrix();
    private final RectF screen = new RectF(), rect = new RectF(), wordBounds = new RectF(), glassRect = new RectF();
    private final float[] sweepDir = {(float) Math.cos(Math.toRadians(INTRO_SWEEP_ANGLE)), (float) Math.sin(Math.toRadians(INTRO_SWEEP_ANGLE))};
    private final char[] chars = INTRO_WORDMARK.toCharArray();
    /** Per glyph: its pen x, its pivot x (screen), and the cell edge on its left (screen). */
    private final float[] penX = new float[GLYPHS], midX = new float[GLYPHS], edgeX = new float[GLYPHS];
    /** Per frame, per glyph: scale, rise (px), sharp alpha, defocus alpha; and the shader's two uniform arrays. */
    private final float[] gScale = new float[GLYPHS], gRise = new float[GLYPHS], gSharp = new float[GLYPHS], gSoft = new float[GLYPHS];
    private final float[] glyphU = new float[GLYPHS * 4], glyphBU = new float[GLYPHS * 4];
    private float wordX, wordY, wordW, wordCap, pivotX, pivotY, glassX, glassY;
    private int softPad, shadePad;
    private Bitmap horizon, core, bloom, ring, soft, shade;
    private Typeface face;
    /** Android 13+: the glass wordmark and the living Horizon (null below, or if the GPU refuses them). */
    private RuntimeShader glassWord, live;
    private GlassText.Built glass;
    private int buildGeneration;

    Intro(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        sendEnding = listener::onIntroEnding;
        sendDone = listener::onIntroDone;
        for (int i = 0; i < GLYPHS; i++) glyph[i] = new Spring(INTRO_GLYPH, 0);
        face = Ui.displaySemibold;
        try {
            Typeface bold = new Typeface.Builder(c.getAssets(), "fonts/InterVariable.ttf")
                    .setFontVariationSettings("'opsz' 32, 'wght' 700").build();
            if (bold != null) face = bold;
        } catch (RuntimeException ignored) {
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                glassWord = new RuntimeShader(Shaders.GLASS_WORD);
                live = new RuntimeShader(Shaders.HORIZON_RIPPLE);
                glassPaint.setShader(glassWord);
                livePaint.setShader(live);
            } catch (RuntimeException | LinkageError e) {
                AppLog.w("startup glass unavailable, drawing it in layers: " + e.getMessage());
                glassWord = null;
                live = null;
            }
        }
        corePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        bloomPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        beamPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        ringPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        addLayer.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        band.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        // Soft Gaussian-like bands across x ∈ [−0.5, 0.5], placed per frame by a matrix (no new shader per frame).
        band.setShader(new LinearGradient(-0.5f, 0, 0.5f, 0, new int[] {0x00FFFFFF, 0x59FFFFFF, 0xFFFFFFFF, 0x59FFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.3f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        beamPaint.setShader(new LinearGradient(-0.5f, 0, 0.5f, 0, new int[] {0x00FFFFFF, 0x2EFFFFFF, 0xFFFFFFFF, 0x2EFFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.3f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        topFeather.setShader(new LinearGradient(0, 0, 0, 1, INTRO_MIDNIGHT, INTRO_MIDNIGHT & 0x00FFFFFF, Shader.TileMode.CLAMP));
        bottomFeather.setShader(new LinearGradient(0, 0, 0, 1, INTRO_MIDNIGHT, INTRO_MIDNIGHT & 0x00FFFFFF, Shader.TileMode.CLAMP));
        for (Paint p : new Paint[] {rim, prismA, prismB, prismC}) p.setStyle(Paint.Style.STROKE);
        prismA.setColor(INTRO_PRISM_A);
        prismB.setColor(INTRO_PRISM_B);
        prismC.setColor(INTRO_PRISM_C);
        softPaint.setColor(0xFFFFFFFF);
        shadePaint.setColor(INTRO_SHADOW);
        core = makeCore();
        bloom = makeBloom();
        ring = makeRing();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
    }

    boolean showing() {
        return getVisibility() == VISIBLE;
    }

    /** Plays the startup from the start (power-on, or Settings → Preview Startup Animation). */
    void start() {
        boolean dark = Wallpaper.dark(Prefs.wallpaper(), HomeData.get(getContext()).isNight(System.currentTimeMillis()));
        if (dark != night || horizon == null) {
            night = dark;
            theme();
        }
        reduced = Spring.instant;
        handoverAt = reduced ? INTRO_REDUCED_HOLD : INTRO_HANDOVER;
        doneAt = handoverAt + (reduced ? 0 : INTRO_WORD_OUT);
        aperture.snap(reduced ? 1 : 0);
        for (Spring g : glyph) g.snap(reduced ? 1 : 0);
        opened = reduced;
        endingSent = false;
        startNanos = 0;
        lastNanos = 0;
        t = 0;
        setAlpha(1);
        setVisibility(VISIBLE);
        bringToFront();
        if (!running) {
            running = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
        removeCallbacks(chime);
        post(chime);
        invalidate();
    }

    private final Runnable chime = () -> {
        Click c = Click.current();
        if (c != null && showing()) c.chime();
    };

    private final Runnable sendEnding, sendDone;

    /** OK or Back: straight to the handover. */
    void skip() {
        if (!showing() || t >= handoverAt) return;
        startNanos -= Math.round((handoverAt - t) * 1e9);
        aperture.snap(1);
        opened = true;
        for (Spring g : glyph) g.snap(1);
    }

    /** Gone at once (the screen went off mid-intro, the view is going away). */
    void stop() {
        removeCallbacks(chime);
        if (running) Choreographer.getInstance().removeFrameCallback(this);
        running = false;
        setVisibility(GONE);
    }

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BACK)) {
            skip();
        }
        return true;   // nothing gets past the intro
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (startNanos == 0) startNanos = frameTimeNanos;
        float dt = lastNanos == 0 ? 1 / 60f : (frameTimeNanos - lastNanos) / 1e9f;
        lastNanos = frameTimeNanos;
        t = (frameTimeNanos - startNanos) / 1e9f;
        if (t >= INTRO_APERTURE_AT) aperture.target = 1;
        aperture.step(dt);
        if (aperture.value >= 0.999f) opened = true;
        for (int i = 0; i < GLYPHS; i++) {
            if (t >= INTRO_WORD_AT + i * INTRO_GLYPH_STAGGER) glyph[i].target = 1;
            glyph[i].step(dt);
        }
        if (t >= handoverAt && !endingSent) {
            endingSent = true;
            post(sendEnding);   // the lock's set-up allocates: off the frame
        }
        if (t >= doneAt && t >= handoverAt + INTRO_SKY_OVERLAP) {
            running = false;
            setVisibility(GONE);
            post(sendDone);
            return;
        }
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    // MARK: - Layout and the things a frame draws with (never while drawing)

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        for (Paint p : new Paint[] {word, frost, skyText, rim, bright, prismA, prismB, prismC}) {
            p.setTypeface(face);
            p.setTextSize(INTRO_WORDMARK_SIZE * k);
            p.setLetterSpacing(INTRO_WORDMARK_TRACKING);
            p.setTextAlign(Paint.Align.LEFT);
        }
        rim.setStrokeWidth(INTRO_RIM * k);
        for (Paint p : new Paint[] {prismA, prismB, prismC}) p.setStrokeWidth(INTRO_PRISM_PX * k);
        wordW = word.measureText(INTRO_WORDMARK);
        wordCap = Ui.cap(word);
        wordX = (w - wordW) / 2;
        wordY = h / 2 + wordCap / 2;
        pivotX = w / 2;
        pivotY = h / 2;
        // The glyphs: a single glyph drawn at the run's pen position lands where it does in the whole word.
        for (int i = 0; i < GLYPHS; i++) penX[i] = wordX + word.measureText(INTRO_WORDMARK, 0, i);
        for (int i = 0; i < GLYPHS; i++) {
            float right = i + 1 < GLYPHS ? penX[i + 1] : wordX + wordW;
            midX[i] = (penX[i] + right) / 2;
            edgeX[i] = penX[i];
        }
        wordBounds.set(wordX - 40 * k, wordY - wordCap - 60 * k, wordX + wordW + 40 * k, wordY + 60 * k);
        // The rim's light: the inner top bevel (0.82) fading to a faint lower edge.
        rim.setShader(new LinearGradient(0, wordY - wordCap, 0, wordY, Ui.alpha(0xFFFFFFFF, INTRO_BEVEL), 0x40FFFFFF, Shader.TileMode.CLAMP));
        theme();
        buildGlass();
    }

    /** The Horizon, the glass's tint and the shaders' fixed uniforms, for this size and the time of day. */
    private void theme() {
        if (w == 0) return;
        horizon = Horizon.get((int) w, (int) h, night);
        float bodyA = night ? INTRO_BODY_NIGHT_A : INTRO_BODY_DAY_A;
        veil.setColor(night ? INTRO_VEIL_NIGHT : INTRO_VEIL_DAY);
        frost.setColor(Ui.alpha(0xFFFFFFFF, bodyA));
        bright.setColor(Ui.alpha(0xFFFFFFFF, INTRO_SWEEP_LIGHT));
        softPaint.setColor(Ui.alpha(0xFFFFFFFF, night ? 0.55f : 0.70f));
        // The sky inside the letters (layered glass): the Horizon 1.04× around the word's centre, a refraction look.
        BitmapShader sky = new BitmapShader(horizon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        skyM.setScale(w / horizon.getWidth(), h / horizon.getHeight());
        skyM.postScale(1.04f, 1.04f, pivotX, pivotY);
        sky.setLocalMatrix(skyM);
        skyText.setShader(sky);
        if (glassWord != null) {
            BitmapShader screenSky = Frost.filter(new BitmapShader(horizon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                    BitmapShader.FILTER_MODE_LINEAR);
            Matrix m = new Matrix();
            m.setScale(w / horizon.getWidth(), h / horizon.getHeight());
            screenSky.setLocalMatrix(m);
            glassWord.setInputShader("sky", screenSky);
            live.setInputShader("sky", screenSky);
            glassWord.setFloatUniform("refraction", INTRO_REFRACTION * k);
            glassWord.setFloatUniform("fringe", INTRO_FRINGE * k);
            glassWord.setFloatUniform("body", 1, 1, 1, bodyA);
            glassWord.setFloatUniform("bevel", INTRO_BEVEL);
            glassWord.setFloatUniform("depth", night ? INTRO_DEPTH_NIGHT : INTRO_DEPTH_DAY);
            glassWord.setFloatUniform("sweepDir", sweepDir[0], sweepDir[1]);
            glassWord.setFloatUniform("sweepWidth", INTRO_SWEEP_WIDTH * k);
            glassWord.setFloatUniform("prismA", 0x38 / 255f, 0xBD / 255f, 0xF8 / 255f);
            glassWord.setFloatUniform("prismB", 1, 1, 1);
            glassWord.setFloatUniform("prismC", 0xFD / 255f, 0xE6 / 255f, 0x8A / 255f);
            glassWord.setFloatUniform("prismPx", INTRO_PRISM_PX * k);
            glassWord.setFloatUniform("pivot", pivotX, pivotY);
            live.setFloatUniform("freq", INTRO_DRIFT_FREQ / k);
            live.setFloatUniform("center", pivotX, pivotY);
            live.setFloatUniform("width", INTRO_RIPPLE_WIDTH * k);
            live.setFloatUniform("beamDir", sweepDir[0], sweepDir[1]);
            live.setFloatUniform("beamWidth", INTRO_BEAM_WIDTH * w);
        }
    }

    /** The ribbon's core: a crisp pearl streak (#FFF7ED), brightest in the middle, made once. */
    private static Bitmap makeCore() {
        int bw = 512, bh = 16;
        int[] px = new int[bw * bh];
        for (int y = 0; y < bh; y++) {
            float v = ((y + 0.5f) / bh * 2 - 1) / 0.42f;
            for (int x = 0; x < bw; x++) {
                float u = Math.abs((x + 0.5f) / bw - 0.5f) * 2;   // 0 in the middle, 1 at the ends
                float a = (float) Math.exp(-v * v) * (0.35f + 0.65f * (float) Math.exp(-u * 2.2f)) * (1 - smooth(0.7f, 1f, u));
                px[y * bw + x] = (Math.round(255 * Math.min(1, a)) << 24) | (INTRO_RIBBON_CORE & 0xFFFFFF);
            }
        }
        return Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888);
    }

    /** The ribbon's bloom: soft and wide, warm cream-gold (#F5EBD6) in the middle, cyan (#7DD3FC) toward the ends. */
    private static Bitmap makeBloom() {
        int bw = 512, bh = 64;
        int[] px = new int[bw * bh];
        int wr = (INTRO_BLOOM_WARM >> 16) & 255, wg = (INTRO_BLOOM_WARM >> 8) & 255, wb = INTRO_BLOOM_WARM & 255;
        int cr = (INTRO_BLOOM_COOL >> 16) & 255, cg = (INTRO_BLOOM_COOL >> 8) & 255, cb = INTRO_BLOOM_COOL & 255;
        for (int y = 0; y < bh; y++) {
            float v = ((y + 0.5f) / bh * 2 - 1) / 0.45f;
            for (int x = 0; x < bw; x++) {
                float u = Math.abs((x + 0.5f) / bw - 0.5f) * 2;
                float a = 0.6f * (float) Math.exp(-v * v) * (float) Math.exp(-u * 1.3f) * (1 - smooth(0.6f, 1f, u));
                float f = smooth(0.05f, 0.8f, u);
                int r = Math.round(wr + (cr - wr) * f), g = Math.round(wg + (cg - wg) * f), b = Math.round(wb + (cb - wb) * f);
                px[y * bw + x] = (Math.round(255 * Math.min(1, a)) << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888);
    }

    /** A soft light ring at 92% of the radius (the ring without shaders), made once. */
    private static Bitmap makeRing() {
        int s = 256;
        int[] px = new int[s * s];
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float dx = (x + 0.5f) / s * 2 - 1, dy = (y + 0.5f) / s * 2 - 1;
                float d = ((float) Math.sqrt(dx * dx + dy * dy) - 0.92f) / 0.035f;
                int a = Math.round(255 * (float) Math.exp(-d * d));
                px[y * s + x] = (a << 24) | 0xFFFFFF;
            }
        }
        return Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888);
    }

    /** The glass bitmaps (GlassText), the defocused copy and the shadow, off the UI thread; swapped in when ready. */
    private void buildGlass() {
        final int gen = ++buildGeneration;
        final Paint p = new Paint(word);
        final float scale = k;
        new Thread(() -> {
            GlassText.Built b = GlassText.build(INTRO_WORDMARK, p, scale);
            // Padded first: the blurs reach well past the glass bitmaps' 40 px margin.
            int sp = Math.round(INTRO_DEFOCUS * 2.2f * scale), hp = Math.round((INTRO_SHADOW_BLUR + 16) * scale);
            Bitmap blurred = blurPadded(b.cover, sp, Math.round(INTRO_DEFOCUS * scale));
            Bitmap shadow = blurPadded(b.cover, hp, Math.round(INTRO_SHADOW_BLUR / 2 * scale));
            post(() -> {
                if (gen != buildGeneration) return;
                glass = b;
                soft = blurred;
                shade = shadow;
                softPad = sp;
                shadePad = hp;
                glassX = wordX + b.ink.left - b.pad;
                glassY = wordY + b.ink.top - b.pad;
                float bw = b.cover.getWidth(), bh = b.cover.getHeight();
                // Room for a glyph at 1.14×, 14 px low.
                glassRect.set(glassX - 0.10f * bw, glassY - 0.25f * bh, glassX + 1.10f * bw, glassY + 1.25f * bh + INTRO_GLYPH_RISE * k);
                for (int i = 0; i < GLYPHS; i++) {
                    glyphU[i * 4] = i == 0 ? -1e4f : edgeX[i] - glassX;
                    glyphU[i * 4 + 1] = i == GLYPHS - 1 ? 1e4f : edgeX[i + 1] - glassX;
                    glyphU[i * 4 + 2] = midX[i];
                }
                if (glassWord != null) {
                    glassWord.setInputShader("slope", nearest(b.slope));
                    glassWord.setInputShader("cover", nearest(b.cover));
                    glassWord.setFloatUniform("origin", glassX, glassY);
                }
                invalidate();
            });
        }, "intro-glass").start();
    }

    private static Bitmap blurPadded(Bitmap cover, int pad, int r) {
        Bitmap big = Bitmap.createBitmap(cover.getWidth() + 2 * pad, cover.getHeight() + 2 * pad, Bitmap.Config.ARGB_8888);
        new Canvas(big).drawBitmap(cover, pad, pad, null);
        Bitmap out = GlassText.defocused(big, Math.max(1, r));
        big.recycle();
        return out;
    }

    private static BitmapShader nearest(Bitmap b) {
        return Frost.filter(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), BitmapShader.FILTER_MODE_NEAREST);
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (horizon == null) return;
        if (t < handoverAt + INTRO_SKY_OVERLAP) drawSky(c);
        drawWord(c);
    }

    /** Where the beam's centre is (screen x): slowest as it crosses the word, at its centre at 1.45 s. */
    private float beamX() {
        float u = Ui.clamp((t - INTRO_SWEEP_FROM) / (INTRO_SWEEP_TO - INTRO_SWEEP_FROM)) * 2 - 1;
        return w * (0.5f + 0.8f * (0.35f * u + 0.65f * u * u * u));
    }

    /** The beam's strength over the sky (in and out at the ends of its run). */
    private float beamLight() {
        if (reduced) return 0;
        return smooth(INTRO_SWEEP_FROM, INTRO_SWEEP_FROM + 0.12f, t) * (1 - smooth(INTRO_SWEEP_TO - 0.12f, INTRO_SWEEP_TO, t));
    }

    /** Slate, the ribbon and the aperture onto the living Horizon; then the Horizon (drift, beam, ring) and the veil. */
    private void drawSky(Canvas c) {
        float r = Ui.clamp(aperture.value);
        float drift = reduced ? 0 : smooth(INTRO_APERTURE_AT, INTRO_DRIFT_IN_TO, t) * (1 - smooth(INTRO_DRIFT_OUT_FROM, INTRO_HANDOVER, t));
        float rp = reduced ? -1 : (t - INTRO_RIPPLE_FROM) / (INTRO_RIPPLE_TO - INTRO_RIPPLE_FROM);
        boolean rippling = rp > 0 && rp < 1;
        float beam = beamLight();
        boolean alive = drift > 0.001f || rippling || beam > 0.001f;
        if (opened) {
            drawHorizon(c, alive, drift, rippling, rp, beam);
        } else {
            float y0 = INTRO_SEAM_Y * h, feather = (INTRO_FEATHER + INTRO_FEATHER_GROW * 1080 * r) * k;
            float a = k + (Math.max(y0, h - y0) + feather - k) * r;
            c.drawColor(INTRO_MIDNIGHT);
            if (r > 0.0005f) {
                c.save();
                c.clipRect(0, y0 - a, w, y0 + a);
                drawHorizon(c, alive, drift, false, 0, 0);
                topM.setScale(1, feather);
                topM.postTranslate(0, y0 - a);
                topFeather.getShader().setLocalMatrix(topM);
                c.drawRect(0, y0 - a, w, y0 - a + feather, topFeather);
                bottomM.setScale(1, -feather);
                bottomM.postTranslate(0, y0 + a);
                bottomFeather.getShader().setLocalMatrix(bottomM);
                c.drawRect(0, y0 + a - feather, w, y0 + a, bottomFeather);
                c.restore();
            }
        }
        // The anamorphic ribbon: it ignites (0 → 0.20 s, 2 → 14 px, its span stretching out), then fades as the sky opens.
        float ignite = reduced ? 0 : smooth(0, INTRO_RIBBON_IGNITE, t);
        float glow = ignite * (1 - smooth(INTRO_RIBBON_OUT_FROM, INTRO_RIBBON_OUT_TO, t));
        if (glow > 0.004f) {
            float y0 = INTRO_SEAM_Y * h, span = INTRO_RIBBON_SPAN * w * (0.35f + 0.65f * ignite);
            float bloomHalf = (INTRO_BLOOM_FROM + (INTRO_BLOOM_TO - INTRO_BLOOM_FROM) * r) * k;
            rect.set(pivotX - span * 0.62f, y0 - bloomHalf, pivotX + span * 0.62f, y0 + bloomHalf);
            bloomPaint.setAlpha(Math.round(255 * INTRO_BLOOM_PEAK * glow));
            c.drawBitmap(bloom, null, rect, bloomPaint);
            float half = (INTRO_RIBBON_THIN + (INTRO_RIBBON_THICK - INTRO_RIBBON_THIN) * ignite) / 2 * k / 0.42f;
            rect.set(pivotX - span / 2, y0 - half, pivotX + span / 2, y0 + half);
            corePaint.setAlpha(Math.round(255 * glow));
            c.drawBitmap(core, null, rect, corePaint);
        }
    }

    /** The Frosted Horizon, alive (the shader, or a drifting bitmap with the beam and ring on top) or still, then the veil. */
    private void drawHorizon(Canvas c, boolean alive, float drift, boolean rippling, float rp, float beam) {
        float e = 1 - (1 - rp) * (1 - rp) * (1 - rp);
        float radius = (INTRO_RIPPLE_R0 + (INTRO_RIPPLE_R1 - INTRO_RIPPLE_R0) * e) * k;
        if (alive && live != null) {
            live.setFloatUniform("time", t);
            live.setFloatUniform("drift", INTRO_DRIFT_PX * k * drift);
            live.setFloatUniform("driftLight", INTRO_DRIFT_LIGHT * drift);
            live.setFloatUniform("radius", radius);
            live.setFloatUniform("amp", rippling ? INTRO_RIPPLE_AMP * k * (1 - rp) : 0);
            live.setFloatUniform("ringLight", rippling ? INTRO_RIPPLE_LIGHT * (1 - rp) : 0);
            live.setFloatUniform("beamAt", beamX(), pivotY);
            live.setFloatUniform("beamLight", INTRO_BEAM_LIGHT * beam);
            c.drawRect(screen, livePaint);
            c.drawRect(screen, veil);
            return;
        }
        if (drift > 0.001f) {
            // A slow breath of the whole sky: a few px of drift and a hair of scale, back to 1:1 by the handover.
            float d = INTRO_DRIFT_PX * k * drift, dx = d * (float) Math.sin(t * 1.1f), dy = 0.5f * d * (float) Math.cos(t * 0.8f);
            rect.set(-d + dx, -d + dy, w + d + dx, h + d + dy);
            Horizon.draw(c, horizon, rect, horizonPaint, 1);
        } else {
            Horizon.draw(c, horizon, screen, horizonPaint, 1);
        }
        c.drawRect(screen, veil);
        if (beam > 0.001f) {
            beamM.setScale(INTRO_BEAM_WIDTH * w * 2.4f, 1);
            beamM.postRotate(INTRO_SWEEP_ANGLE);
            beamM.postTranslate(beamX(), pivotY);
            beamPaint.getShader().setLocalMatrix(beamM);
            beamPaint.setAlpha(Math.round(255 * INTRO_BEAM_LIGHT * beam));
            c.drawRect(screen, beamPaint);
        }
        if (rippling) {
            float R = radius / 0.92f;
            rect.set(pivotX - R, pivotY - R, pivotX + R, pivotY + R);
            ringPaint.setAlpha(Math.round(255 * INTRO_RIPPLE_LIGHT * (1 - rp)));
            c.drawBitmap(ring, null, rect, ringPaint);
        }
    }

    /** The glass wordmark: seven glyphs condensing, the caustic sweep, then (after the handover) the exhale. */
    private void drawWord(Canvas c) {
        float zoom = 1, alpha = 1, haze = 0;
        if (t >= handoverAt) {
            // Ease-out cubic (as web/intro.js): most of the word is gone before the lock's cards reach it.
            float p = reduced ? 1 : Ui.clamp((t - handoverAt) / INTRO_WORD_OUT), e = 1 - (1 - p) * (1 - p) * (1 - p);
            zoom = 1 + (INTRO_WORD_TO_OUT - 1) * e;
            alpha = 1 - e;
            haze = e * INTRO_EXHALE_BLUR / INTRO_DEFOCUS;   // 0 → 14 px: half the way to the defocused copy
        }
        if (alpha <= 0.004f) return;
        float meanScale = 0, meanRise = 0, meanSharp = 0, any = 0;
        for (int i = 0; i < GLYPHS; i++) {
            float g = reduced ? 1 : glyph[i].value, in = Ui.clamp(g);
            gScale[i] = INTRO_GLYPH_FROM + (1 - INTRO_GLYPH_FROM) * g;
            gRise[i] = INTRO_GLYPH_RISE * k * (1 - g);
            gSharp[i] = alpha * in * in * (1 - haze);
            gSoft[i] = alpha * Ui.clamp(g * 1.6f) * Math.max(1 - in, haze);
            meanScale += gScale[i] / GLYPHS;
            meanRise += gRise[i] / GLYPHS;
            meanSharp += gSharp[i] / GLYPHS;
            any = Math.max(any, Math.max(gSharp[i], gSoft[i]));
        }
        if (any <= 0.004f) return;
        float sweepX = beamX();
        boolean sweeping = !reduced && t > INTRO_SWEEP_FROM && t < INTRO_SWEEP_TO;
        // Layer 2: the elevation shadow, under everything, with the glyphs' mean transform.
        if (shade != null && meanSharp > 0.004f) {
            c.save();
            c.scale(zoom, zoom, pivotX, pivotY);
            c.translate(0, meanRise + INTRO_SHADOW_Y * k);
            c.scale(meanScale, meanScale, pivotX, pivotY);
            shadePaint.setAlpha(Math.round((INTRO_SHADOW >>> 24) * meanSharp));
            c.drawBitmap(shade, glassX - shadePad, glassY - shadePad, shadePaint);
            c.restore();
        }
        // The optical defocus: each glyph's part of the blurred copy, on that glyph's transform.
        if (soft != null) {
            for (int i = 0; i < GLYPHS; i++) {
                if (gSoft[i] <= 0.004f) continue;
                c.save();
                c.scale(zoom, zoom, pivotX, pivotY);
                c.translate(0, gRise[i]);
                c.scale(gScale[i], gScale[i], midX[i], pivotY);
                c.clipRect(i == 0 ? -w : edgeX[i], 0, i == GLYPHS - 1 ? 2 * w : edgeX[i + 1], h);
                softPaint.setAlpha(Math.round(255 * (night ? 0.55f : 0.70f) * gSoft[i]));
                c.drawBitmap(soft, glassX - softPad, glassY - softPad, softPaint);
                c.restore();
            }
        }
        if (meanSharp <= 0.002f) return;
        if (glassWord != null && glass != null) {
            for (int i = 0; i < GLYPHS; i++) {
                glyphU[i * 4 + 3] = gScale[i];
                glyphBU[i * 4] = gRise[i];
                glyphBU[i * 4 + 1] = gSharp[i];
            }
            glassWord.setFloatUniform("glyph", glyphU);
            glassWord.setFloatUniform("glyphB", glyphBU);
            glassWord.setFloatUniform("zoom", zoom);
            glassWord.setFloatUniform("alpha", 1);
            glassWord.setFloatUniform("sweepAt", sweeping ? sweepX : -1e5f, pivotY);
            c.drawRect(pivotX + (glassRect.left - pivotX) * zoom, pivotY + (glassRect.top - pivotY) * zoom,
                    pivotX + (glassRect.right - pivotX) * zoom, pivotY + (glassRect.bottom - pivotY) * zoom, glassPaint);
            return;
        }
        // Layered glass (Android 8–12, or until the glass bitmaps are ready): per glyph, the sky, the frost, the rim.
        for (int i = 0; i < GLYPHS; i++) {
            int a = Math.round(255 * gSharp[i]);
            if (a <= 1) continue;
            c.save();
            c.scale(zoom, zoom, pivotX, pivotY);
            c.translate(0, gRise[i]);
            c.scale(gScale[i], gScale[i], midX[i], pivotY);
            skyText.setAlpha(a);
            c.drawText(chars, i, 1, penX[i], wordY, skyText);
            frost.setAlpha(Math.round((night ? INTRO_BODY_NIGHT_A : INTRO_BODY_DAY_A) * a));
            c.drawText(chars, i, 1, penX[i], wordY, frost);
            rim.setAlpha(a);
            c.drawText(chars, i, 1, penX[i], wordY, rim);
            c.restore();
        }
        if (sweeping) {
            // The beam on the glass, in screen coordinates (as the shader's): a layer added onto the word.
            c.save();
            c.scale(zoom, zoom, pivotX, pivotY);
            int save = c.saveLayer(wordBounds, addLayer);
            bright.setAlpha(Math.round(255 * INTRO_SWEEP_LIGHT * meanSharp));
            c.drawText(INTRO_WORDMARK, wordX, wordY, bright);
            float ox = sweepDir[0] * INTRO_PRISM_PX * k, oy = sweepDir[1] * INTRO_PRISM_PX * k;
            int pa = Math.round(235 * meanSharp);
            prismA.setAlpha(pa);
            prismB.setAlpha(pa);
            prismC.setAlpha(pa);
            c.drawText(INTRO_WORDMARK, wordX + ox, wordY + oy, prismA);   // cyan leads
            c.drawText(INTRO_WORDMARK, wordX, wordY, prismB);
            c.drawText(INTRO_WORDMARK, wordX - ox, wordY - oy, prismC);   // amber trails
            bandM.setScale(INTRO_SWEEP_WIDTH * k, 1);
            bandM.postRotate(INTRO_SWEEP_ANGLE);
            bandM.postTranslate(sweepX, pivotY);
            band.getShader().setLocalMatrix(bandM);
            c.drawRect(wordBounds, band);
            c.restoreToCount(save);
            c.restore();
        }
    }

    private static float smooth(float a, float b, float x) {
        float t = Ui.clamp((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }
}
