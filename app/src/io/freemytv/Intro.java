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
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

/**
 * mahitTV's startup, "Liquid Glass & Prismatic Horizon" (TVOS-LAUNCHER-MERGE-SPEC.md Part 2 §1), 2.8 s:
 * <ol>
 * <li>Ignition (0.08–0.65 s): from studio midnight, an anamorphic light seam at 0.44·H opens, on SPATIAL_SCROLL, onto
 * the Frosted Horizon (day, or its twilight after sunset).</li>
 * <li>The wordmark (from 0.40 s): "mahitTV" in refractive glass (Shaders.GLASS_WORD on Android 13+, layered Canvas
 * below), settling from 1.10× and out of a 24 px defocus on TITLE_SETTLE; a −28° caustic band crosses it
 * (0.95–1.75 s) splitting its rim into cyan, white and pearl; a glass ripple spreads over the sky (1.10–2.00 s).</li>
 * <li>The handover (2.10 s): "Who's Watching?" appears underneath on the same Horizon bitmap at 1:1, so the sky never
 * changes; from then on this view draws only the wordmark, shrinking to 0.94× and fading over 280 ms, then it's gone
 * (2.38 s) while the lock's cards spring in.</li>
 * </ol>
 * The chord (Click.chime, Cmaj9) starts with the first frame and carries its own timing. OK or Back jumps to the
 * handover. Reduce motion: the Horizon and the still glass wordmark for 0.7 s, then the handover at once.
 * Everything is made in onSizeChanged, theme() and start(); a frame only moves numbers (no allocation in doFrame or
 * onDraw). The glass bitmaps are built on a background thread; until they're ready the Canvas layers stand in.
 */
final class Intro extends View implements Choreographer.FrameCallback {
    // MARK: - Every parameter of the startup (seconds; px at 1080p, scaled by k = w / 1920)

    /** The handover to the lock, the wordmark's fade-out after it, and the reduced (no motion) handover. */
    static final float INTRO_HANDOVER = 2.10f, INTRO_WORD_OUT = 0.28f, INTRO_REDUCED_HOLD = 0.7f;
    /** The intro keeps drawing its own Horizon this long past the handover (the lock's is identical under it). */
    static final float INTRO_SKY_OVERLAP = 0.10f;
    /** Studio midnight, before the horizon ignites. */
    static final int INTRO_MIDNIGHT = 0xFF090D16;
    /** The lock's faint frost over the Horizon (LockView.VEIL_DAY / VEIL_NIGHT): drawn here too, so the handover is exact. */
    static final int INTRO_VEIL_DAY = 0x1AFFFFFF, INTRO_VEIL_NIGHT = 0x24000000;
    /** The seam: its height (share of H), core and halo colours, peak alpha, rise and fall (s), thickness (px). */
    static final float INTRO_SEAM_Y = 0.44f, INTRO_SEAM_PEAK = 0.90f;
    static final int INTRO_SEAM_CORE = 0xFFFFFFFF, INTRO_SEAM_HALO = 0xFFCFE8FF;
    static final float INTRO_SEAM_IN_FROM = 0.08f, INTRO_SEAM_IN_TO = 0.18f, INTRO_SEAM_OUT_FROM = 0.30f, INTRO_SEAM_OUT_TO = 0.65f;
    static final float INTRO_SEAM_THIN = 1.5f, INTRO_SEAM_THICK = 38f;
    /** The aperture onto the Horizon: opens from here on SPATIAL_SCROLL; its feathered edge is 8 px + 10% of H × open. */
    static final float INTRO_APERTURE_AT = 0.08f, INTRO_FEATHER = 8f, INTRO_FEATHER_GROW = 0.10f;
    /** Wordmark: text (a name, not translated), size, tracking; it arrives from here, from 1.10×, out of a defocus. */
    static final String INTRO_WORDMARK = "mahitTV";
    static final float INTRO_WORDMARK_SIZE = 116f, INTRO_WORDMARK_TRACKING = -0.03f;
    static final float INTRO_WORD_AT = 0.40f, INTRO_WORD_FROM = 1.10f, INTRO_WORD_TO_OUT = 0.94f, INTRO_DEFOCUS = 12f;
    /** The glass: inner optical tint (dark slate by day, frost at night) and its strength; refraction and R/B split. */
    static final int INTRO_BODY_DAY = 0xFF4A5D70, INTRO_BODY_NIGHT = 0xFFFFFFFF;
    static final float INTRO_BODY_DAY_A = 0.38f, INTRO_BODY_NIGHT_A = 0.22f, INTRO_REFRACTION = 10f, INTRO_FRINGE = 1.5f;
    /** The specular rim's width (px). */
    static final float INTRO_RIM = 1.5f;
    /** The caustic sweep: when, its angle (°), width (share of the word's width) and the prism's three tints. */
    static final float INTRO_SWEEP_FROM = 0.95f, INTRO_SWEEP_TO = 1.75f, INTRO_SWEEP_ANGLE = -28f, INTRO_SWEEP_WIDTH = 0.30f;
    static final int INTRO_PRISM_A = 0xFF7DD3FC, INTRO_PRISM_B = 0xFFFFFFFF, INTRO_PRISM_C = 0xFFFEF3C7;
    static final float INTRO_PRISM_PX = 1.5f, INTRO_SWEEP_LIGHT = 0.55f;
    /** The ripple: when, how far (share of W), ring width and bend (px), peak light. */
    static final float INTRO_RIPPLE_FROM = 1.10f, INTRO_RIPPLE_TO = 2.00f, INTRO_RIPPLE_REACH = 0.9f;
    static final float INTRO_RIPPLE_WIDTH = 90f, INTRO_RIPPLE_AMP = 6f, INTRO_RIPPLE_LIGHT = 0.16f;

    interface Listener {
        /** The handover: "Who's Watching?" should appear now, under this view (its Horizon at 1:1). */
        void onIntroEnding();

        /** The intro is gone. */
        void onIntroDone();
    }

    private final Listener listener;
    private boolean running, endingSent, reduced, night = true;
    private long startNanos, lastNanos;
    private float t, handoverAt, doneAt;
    private float w, h, k = 1;
    private final Spring aperture = new Spring(Spring.SPATIAL_SCROLL, 0), wordIn = new Spring(Spring.TITLE_SETTLE, 0);

    private final Paint horizonPaint = new Paint(Paint.FILTER_BITMAP_FLAG), seamPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint topFeather = new Paint(), bottomFeather = new Paint();
    private final Paint ringPaint = new Paint(Paint.FILTER_BITMAP_FLAG), softPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint word = new Paint(Paint.ANTI_ALIAS_FLAG), inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG), skyText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG), bright = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint prismA = new Paint(Paint.ANTI_ALIAS_FLAG), prismB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint prismC = new Paint(Paint.ANTI_ALIAS_FLAG), band = new Paint(), addLayer = new Paint();
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG), ripplePaint = new Paint();
    private final Matrix topM = new Matrix(), bottomM = new Matrix(), bandM = new Matrix(), skyM = new Matrix();
    private final RectF screen = new RectF(), rect = new RectF(), wordBounds = new RectF();
    private final float[] sweepDir = {(float) Math.cos(Math.toRadians(INTRO_SWEEP_ANGLE)), (float) Math.sin(Math.toRadians(INTRO_SWEEP_ANGLE))};
    private float wordX, wordY, wordW, wordCap, pivotX, pivotY, glassX, glassY, projMin, projMax, innerA;
    private final Paint veil = new Paint();
    private Bitmap horizon, seam, ring, soft;
    private Typeface face;
    /** Android 13+: the glass wordmark and the ripple (null below, or if the GPU refuses them). */
    private RuntimeShader glassWord, ripple;
    private GlassText.Built glass;
    private int buildGeneration;

    Intro(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        sendEnding = listener::onIntroEnding;
        sendDone = listener::onIntroDone;
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
                ripple = new RuntimeShader(Shaders.HORIZON_RIPPLE);
                glassPaint.setShader(glassWord);
                ripplePaint.setShader(ripple);
            } catch (RuntimeException | LinkageError e) {
                AppLog.w("startup glass unavailable, drawing it in layers: " + e.getMessage());
                glassWord = null;
                ripple = null;
            }
        }
        seamPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        ringPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        addLayer.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        band.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        // A soft Gaussian-like band across x ∈ [−0.5, 0.5], placed per frame by bandM (no new shader per frame).
        band.setShader(new LinearGradient(-0.5f, 0, 0.5f, 0, new int[] {0x00FFFFFF, 0x59FFFFFF, 0xFFFFFFFF, 0x59FFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.3f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        topFeather.setShader(new LinearGradient(0, 0, 0, 1, INTRO_MIDNIGHT, INTRO_MIDNIGHT & 0x00FFFFFF, Shader.TileMode.CLAMP));
        bottomFeather.setShader(new LinearGradient(0, 0, 0, 1, INTRO_MIDNIGHT, INTRO_MIDNIGHT & 0x00FFFFFF, Shader.TileMode.CLAMP));
        for (Paint p : new Paint[] {rim, prismA, prismB, prismC}) p.setStyle(Paint.Style.STROKE);
        prismA.setColor(INTRO_PRISM_A);
        prismB.setColor(INTRO_PRISM_B);
        prismC.setColor(INTRO_PRISM_C);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
    }

    boolean showing() {
        return getVisibility() == VISIBLE;
    }

    /** Plays the startup from the start. */
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
        wordIn.snap(reduced ? 1 : 0);
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
        wordIn.snap(1);
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
        if (t >= INTRO_WORD_AT) wordIn.target = 1;
        aperture.step(dt);
        wordIn.step(dt);
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
        for (Paint p : new Paint[] {word, inner, frost, skyText, rim, bright, prismA, prismB, prismC}) {
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
        wordBounds.set(wordX - 20 * k, wordY - wordCap - 40 * k, wordX + wordW + 20 * k, wordY + 40 * k);
        rim.setShader(new LinearGradient(wordX, wordY - wordCap, wordX + wordW, wordY, 0xE6FFFFFF, 0x40FFFFFF, Shader.TileMode.CLAMP));
        seam = makeSeam();
        ring = makeRing();
        theme();
        buildGlass();
    }

    /** The Horizon, the glass's tint and the shaders' fixed uniforms, for this size and the time of day. */
    private void theme() {
        if (w == 0) return;
        horizon = Horizon.get((int) w, (int) h, night);
        int body = night ? INTRO_BODY_NIGHT : INTRO_BODY_DAY;
        float bodyA = night ? INTRO_BODY_NIGHT_A : INTRO_BODY_DAY_A;
        innerA = night ? 0.10f : 0.30f;
        inner.setColor(Ui.alpha(body, innerA));
        veil.setColor(night ? INTRO_VEIL_NIGHT : INTRO_VEIL_DAY);
        inner.setShadowLayer(6 * k, 0, 3 * k, Ui.alpha(body, night ? 0.10f : 0.30f));
        frost.setColor(0x38FFFFFF);   // white 0.22
        bright.setColor(Ui.alpha(0xFFFFFFFF, INTRO_SWEEP_LIGHT));
        softPaint.setColor(Ui.alpha(night ? 0xFFFFFFFF : INTRO_BODY_DAY, 0.55f));
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
            ripple.setInputShader("sky", screenSky);
            glassWord.setFloatUniform("refraction", INTRO_REFRACTION * k);
            glassWord.setFloatUniform("fringe", INTRO_FRINGE * k);
            glassWord.setFloatUniform("body", ((body >> 16) & 255) / 255f, ((body >> 8) & 255) / 255f, (body & 255) / 255f, bodyA);
            glassWord.setFloatUniform("sweepDir", sweepDir[0], sweepDir[1]);
            glassWord.setFloatUniform("prismA", 0x7D / 255f, 0xD3 / 255f, 0xFC / 255f);
            glassWord.setFloatUniform("prismB", 1, 1, 1);
            glassWord.setFloatUniform("prismC", 0xFE / 255f, 0xF3 / 255f, 0xC7 / 255f);
            glassWord.setFloatUniform("prismPx", INTRO_PRISM_PX * k);
            glassWord.setFloatUniform("pivot", pivotX, pivotY);
            ripple.setFloatUniform("center", pivotX, pivotY);
            ripple.setFloatUniform("width", INTRO_RIPPLE_WIDTH * k);
            ripple.setFloatUniform("amp", INTRO_RIPPLE_AMP * k);
        }
    }

    /** The anamorphic seam: a long horizontal streak, white core over a pale cyan halo, made once. */
    private static Bitmap makeSeam() {
        int bw = 512, bh = 64;
        int[] px = new int[bw * bh];
        for (int y = 0; y < bh; y++) {
            float v = (y + 0.5f) / bh * 2 - 1;   // −1 … 1 across the seam
            float core = (float) Math.exp(-(v / 0.12f) * (v / 0.12f)), halo = 0.55f * (float) Math.exp(-(v / 0.45f) * (v / 0.45f));
            for (int x = 0; x < bw; x++) {
                float across = Math.abs((x + 0.5f) / bw - 0.5f);
                float fall = (float) Math.exp(-across / 0.55f) * (1 - smooth(0.42f, 0.5f, across));
                float a = Math.min(1, (core + halo) * fall);
                if (a <= 0) continue;
                float c = core / Math.max(1e-4f, core + halo);   // share of the white core in this pixel
                int r = Math.round(255 * (c + (1 - c) * (0xCF / 255f)));
                int g = Math.round(255 * (c + (1 - c) * (0xE8 / 255f)));
                px[y * bw + x] = (Math.round(255 * a) << 24) | (r << 16) | (g << 8) | 0xFF;
            }
        }
        return Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888);
    }

    /** A soft light ring at 92% of the radius (the ripple without shaders), made once. */
    private static Bitmap makeRing() {
        int s = 256;
        int[] px = new int[s * s];
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float dx = (x + 0.5f) / s * 2 - 1, dy = (y + 0.5f) / s * 2 - 1;
                float d = ((float) Math.sqrt(dx * dx + dy * dy) - 0.92f) / 0.06f;
                int a = Math.round(255 * (float) Math.exp(-d * d));
                px[y * s + x] = (a << 24) | 0xFFFFFF;
            }
        }
        return Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888);
    }

    /** The glass bitmaps (GlassText) and the defocused copy, off the UI thread; swapped in when ready. */
    private void buildGlass() {
        final int gen = ++buildGeneration;
        final Paint p = new Paint(word);
        final float scale = k;
        new Thread(() -> {
            GlassText.Built b = GlassText.build(INTRO_WORDMARK, p, scale);
            Bitmap blurred = GlassText.defocused(b.cover, Math.round(INTRO_DEFOCUS * scale));
            post(() -> {
                if (gen != buildGeneration) return;
                glass = b;
                soft = blurred;
                glassX = wordX + b.ink.left - b.pad;
                glassY = wordY + b.ink.top - b.pad;
                // The band's travel: the word bitmap's extent along the sweep direction.
                float bw = b.cover.getWidth(), bh = b.cover.getHeight();
                projMin = Math.min(0, Math.min(bw * sweepDir[0], Math.min(bh * sweepDir[1], bw * sweepDir[0] + bh * sweepDir[1])));
                projMax = Math.max(0, Math.max(bw * sweepDir[0], Math.max(bh * sweepDir[1], bw * sweepDir[0] + bh * sweepDir[1])));
                if (glassWord != null) {
                    glassWord.setInputShader("slope", nearest(b.slope));
                    glassWord.setInputShader("cover", nearest(b.cover));
                    glassWord.setFloatUniform("origin", glassX, glassY);
                    glassWord.setFloatUniform("sweepWidth", INTRO_SWEEP_WIDTH * (projMax - projMin));
                }
                invalidate();
            });
        }, "intro-glass").start();
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

    /** Midnight, the seam and the aperture onto the Horizon; then the Horizon (with the ripple while it runs). */
    private void drawSky(Canvas c) {
        float r = Ui.clamp(aperture.value);
        float rp = reduced ? -1 : (t - INTRO_RIPPLE_FROM) / (INTRO_RIPPLE_TO - INTRO_RIPPLE_FROM);
        boolean rippling = rp > 0 && rp < 1;
        if (r >= 0.999f) {
            if (rippling && ripple != null) {
                float e = 1 - (1 - rp) * (1 - rp) * (1 - rp);
                ripple.setFloatUniform("radius", INTRO_RIPPLE_REACH * w * e);
                ripple.setFloatUniform("strength", 1 - rp);
                c.drawRect(screen, ripplePaint);
                c.drawRect(screen, veil);
            } else {
                Horizon.draw(c, horizon, screen, horizonPaint, 1);
                c.drawRect(screen, veil);
                if (rippling) {
                    float e = 1 - (1 - rp) * (1 - rp) * (1 - rp), R = INTRO_RIPPLE_REACH * w * e / 0.92f;
                    rect.set(pivotX - R, pivotY - R, pivotX + R, pivotY + R);
                    ringPaint.setAlpha(Math.round(255 * INTRO_RIPPLE_LIGHT * (1 - rp)));
                    c.drawBitmap(ring, null, rect, ringPaint);
                }
            }
        } else {
            float y0 = INTRO_SEAM_Y * h, feather = (INTRO_FEATHER + INTRO_FEATHER_GROW * 1080 * r) * k;
            float a = k + (Math.max(y0, h - y0) + feather - k) * r;
            c.drawColor(INTRO_MIDNIGHT);
            c.save();
            c.clipRect(0, y0 - a, w, y0 + a);
            Horizon.draw(c, horizon, screen, horizonPaint, 1);
            c.drawRect(screen, veil);
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
        float seamA = reduced ? 0 : smooth(INTRO_SEAM_IN_FROM, INTRO_SEAM_IN_TO, t) * (1 - smooth(INTRO_SEAM_OUT_FROM, INTRO_SEAM_OUT_TO, t));
        if (seamA > 0.004f) {
            float y0 = INTRO_SEAM_Y * h, half = (INTRO_SEAM_THIN + INTRO_SEAM_THICK * r) * k;
            rect.set(0, y0 - half, w, y0 + half);
            seamPaint.setAlpha(Math.round(255 * INTRO_SEAM_PEAK * seamA));
            c.drawBitmap(seam, null, rect, seamPaint);
        }
    }

    /** The glass wordmark: in from 1.10× out of the defocus, the caustic sweep, then (after the handover) out. */
    private void drawWord(Canvas c) {
        float wi = reduced ? 1 : wordIn.value;
        if (wi <= 0.002f) return;
        float zoom = INTRO_WORD_FROM + (1 - INTRO_WORD_FROM) * wi, alpha = Ui.clamp(wi * 1.4f), defocus = 1 - Ui.clamp(wi);
        if (t >= handoverAt) {
            // Ease-out cubic (as web/intro.js): most of the word is gone before the lock's cards reach it.
            float p = reduced ? 1 : Ui.clamp((t - handoverAt) / INTRO_WORD_OUT), e = 1 - (1 - p) * (1 - p) * (1 - p);
            zoom *= 1 + (INTRO_WORD_TO_OUT - 1) * e;
            alpha *= 1 - e;
        }
        if (alpha <= 0.004f) return;
        float s = reduced ? -1 : (t - INTRO_SWEEP_FROM) / (INTRO_SWEEP_TO - INTRO_SWEEP_FROM);
        boolean sweeping = s > 0 && s < 1 && glass != null;
        float sweepPos = 0;
        if (sweeping) {
            s = s * s * (3 - 2 * s);
            sweepPos = projMin + (-0.3f + 1.6f * s) * (projMax - projMin);
        }
        // The defocused copy (the word coming into focus).
        if (soft != null && defocus > 0.004f) {
            c.save();
            c.scale(zoom, zoom, pivotX, pivotY);
            softPaint.setAlpha(Math.round(255 * 0.55f * alpha * defocus));
            c.drawBitmap(soft, glassX, glassY, softPaint);
            c.restore();
        }
        float sharp = alpha * (1 - defocus);
        if (sharp <= 0.004f) return;
        if (glassWord != null && glass != null) {
            glassWord.setFloatUniform("zoom", zoom);
            glassWord.setFloatUniform("alpha", sharp);
            glassWord.setFloatUniform("sweepPos", sweeping ? sweepPos : -1e5f);
            float l = pivotX + (glassX - pivotX) * zoom, top = pivotY + (glassY - pivotY) * zoom;
            c.drawRect(l, top, l + glass.cover.getWidth() * zoom, top + glass.cover.getHeight() * zoom, glassPaint);
            return;
        }
        // Layered glass (Android 8–12, or until the glass bitmaps are ready).
        int a = Math.round(255 * sharp);
        c.save();
        c.scale(zoom, zoom, pivotX, pivotY);
        inner.setAlpha(Math.round(innerA * a));
        c.drawText(INTRO_WORDMARK, wordX, wordY, inner);
        skyText.setAlpha(a);
        c.drawText(INTRO_WORDMARK, wordX, wordY, skyText);
        frost.setAlpha(Math.round(0x38 / 255f * a));
        c.drawText(INTRO_WORDMARK, wordX, wordY, frost);
        rim.setAlpha(a);
        c.drawText(INTRO_WORDMARK, wordX, wordY, rim);
        if (sweeping) {
            // The band, in the word's own coordinates (as the shader's): a layer added onto the glass.
            int save = c.saveLayer(wordBounds, addLayer);
            bright.setAlpha(Math.round(255 * INTRO_SWEEP_LIGHT * sharp));
            c.drawText(INTRO_WORDMARK, wordX, wordY, bright);
            float ax = -sweepDir[1] * INTRO_PRISM_PX * k, ay = sweepDir[0] * INTRO_PRISM_PX * k;
            prismA.setAlpha(Math.round(230 * sharp));
            prismB.setAlpha(Math.round(230 * sharp));
            prismC.setAlpha(Math.round(230 * sharp));
            c.drawText(INTRO_WORDMARK, wordX + ax, wordY + ay, prismA);
            c.drawText(INTRO_WORDMARK, wordX, wordY, prismB);
            c.drawText(INTRO_WORDMARK, wordX - ax, wordY - ay, prismC);
            float bandW = INTRO_SWEEP_WIDTH * (projMax - projMin);
            float cx = glassX + sweepDir[0] * sweepPos, cy = glassY + sweepDir[1] * sweepPos;
            bandM.setScale(bandW, 1);
            bandM.postRotate(INTRO_SWEEP_ANGLE);
            bandM.postTranslate(cx, cy);
            band.getShader().setLocalMatrix(bandM);
            c.drawRect(wordBounds, band);
            c.restoreToCount(save);
        }
        c.restore();
    }

    private static float smooth(float a, float b, float x) {
        float t = Ui.clamp((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }
}
