package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.util.Random;

/**
 * mahitTV's golden intro (MAHITTV-PROFILES-SPEC.md §2): the wordmark in brushed gold on obsidian, one specular sweep,
 * gold sparks rising with depth blur and a warm chord; then it dissolves into "Who's Watching?". OK or Back skips.
 * Reduce motion (animations off): a still wordmark and a quick fade. Android 8–11: no sparks.
 * Everything is made in onSizeChanged; a frame only moves numbers (no allocation in doFrame or onDraw).
 */
final class Intro extends View implements Choreographer.FrameCallback {
    // MARK: - Every parameter of the intro

    /** Whole intro, and the reduced one (seconds). */
    static final float INTRO_DURATION = 2.4f, INTRO_DURATION_REDUCED = 1.1f;
    /** The wordmark fades in over this long, settling from INTRO_SCALE_FROM. */
    static final float INTRO_FADE_IN = 0.4f, INTRO_SCALE_FROM = 0.94f;
    /** The specular band sweeps across the wordmark between these times, this wide (share of the wordmark's width). */
    static final float INTRO_SWEEP_FROM = 0.5f, INTRO_SWEEP_TO = 1.4f, INTRO_SWEEP_WIDTH = 0.28f;
    /** The chord starts here. */
    static final float INTRO_CHIME_AT = 0.15f;
    /** The dissolve into the lock starts here (the lock comes in under it) and lasts to the end. */
    static final float INTRO_END_AT = 1.9f, INTRO_END_AT_REDUCED = 0.75f;
    /** Wordmark: text, size (1080p px) and how far above the centre it sits. */
    static final String INTRO_WORDMARK = "mahitTV";
    static final float INTRO_WORDMARK_SIZE = 150, INTRO_WORDMARK_LIFT = 10, INTRO_WORDMARK_TRACKING = -0.02f;
    /** Brushed gold, top to bottom, and the glow behind the letters. */
    static final int[] INTRO_GOLD = {0xFFD4AF37, 0xFFF5D061, 0xFFFFF3B0, 0xFFD4AF37};
    static final float[] INTRO_GOLD_STOPS = {0f, 0.38f, 0.55f, 1f};
    static final int INTRO_GLOW = 0x66D4AF37;
    static final float INTRO_GLOW_RADIUS = 36;
    /** Obsidian: the centre and the edges. */
    static final int INTRO_BG_CENTER = 0xFF15130F, INTRO_BG_EDGE = 0xFF030303;
    /** Sparks: how many, their rise (px/s at depth 1), size range (px), sway, and the depth under which they're bokeh. */
    static final int INTRO_SPARKS = 48;
    static final float INTRO_RISE = 120, INTRO_SPARK_MIN = 2.5f, INTRO_SPARK_MAX = 6f, INTRO_SWAY = 18;
    static final float INTRO_BOKEH_DEPTH = 0.45f, INTRO_BOKEH_SCALE = 5f;
    static final int INTRO_SPARK_COLOR = 0xFFF5D061;

    interface Listener {
        /** The dissolve starts: the lock should come in under the intro now. */
        void onIntroEnding();

        /** The intro is gone. */
        void onIntroDone();
    }

    private final Listener listener;
    private boolean running, endingSent, reduced, sparks;
    private long startNanos;
    private float t, endAt, duration, alpha = 1;
    private float w, h, k = 1;

    private final Paint bg = new Paint(), gold = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bokehPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix sweepMatrix = new Matrix();
    private final RectF rect = new RectF();
    private float wordW, wordX, wordY, wordCap;
    private Bitmap bokeh;
    private Typeface face;

    private final float[] sx = new float[INTRO_SPARKS], sy = new float[INTRO_SPARKS], sz = new float[INTRO_SPARKS];
    private final float[] sSize = new float[INTRO_SPARKS], sPhase = new float[INTRO_SPARKS];

    Intro(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        sendEnding = listener::onIntroEnding;
        sendDone = listener::onIntroDone;
        Random r = new Random(7);
        for (int i = 0; i < INTRO_SPARKS; i++) {
            sx[i] = r.nextFloat();
            sy[i] = r.nextFloat();
            sz[i] = 0.15f + 0.85f * r.nextFloat();
            sSize[i] = INTRO_SPARK_MIN + (INTRO_SPARK_MAX - INTRO_SPARK_MIN) * r.nextFloat();
            sPhase[i] = (float) (r.nextFloat() * Math.PI * 2);
        }
        face = Ui.displaySemibold;
        try {
            Typeface bold = new Typeface.Builder(c.getAssets(), "fonts/InterVariable.ttf")
                    .setFontVariationSettings("'opsz' 32, 'wght' 700").build();
            if (bold != null) face = bold;
        } catch (RuntimeException ignored) {
        }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
    }

    boolean showing() {
        return getVisibility() == VISIBLE;
    }

    /** Plays the intro from the start. */
    void start() {
        reduced = Spring.instant;
        sparks = !reduced && !Ui.LEGACY;
        duration = reduced ? INTRO_DURATION_REDUCED : INTRO_DURATION;
        endAt = reduced ? INTRO_END_AT_REDUCED : INTRO_END_AT;
        endingSent = false;
        startNanos = 0;
        t = 0;
        alpha = 1;
        setAlpha(1);
        setVisibility(VISIBLE);
        bringToFront();
        if (!running) {
            running = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
        postDelayed(chime, Math.round(INTRO_CHIME_AT * 1000));
        invalidate();
    }

    private final Runnable chime = () -> {
        Click c = Click.current();
        if (c != null && showing()) c.chime();
    };

    private final Runnable sendEnding, sendDone;

    /** OK or Back: straight to the dissolve. */
    void skip() {
        if (!showing() || t >= endAt) return;
        startNanos -= Math.round((endAt - t) * 1e9);
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
        t = (frameTimeNanos - startNanos) / 1e9f;
        if (t >= endAt && !endingSent) {
            endingSent = true;
            post(sendEnding);   // the lock's set-up allocates: off the frame
        }
        alpha = t <= endAt ? 1 : 1 - Ui.clamp((t - endAt) / (duration - endAt));
        setAlpha(alpha);
        if (t >= duration) {
            running = false;
            setVisibility(GONE);
            post(sendDone);
            return;
        }
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        w = width;
        h = height;
        k = w / 1920f;
        bg.setShader(new RadialGradient(w / 2, h / 2, Math.max(w, h) * 0.62f, INTRO_BG_CENTER, INTRO_BG_EDGE, Shader.TileMode.CLAMP));
        gold.setTypeface(face);
        gold.setTextSize(INTRO_WORDMARK_SIZE * k);
        gold.setLetterSpacing(INTRO_WORDMARK_TRACKING);
        gold.setTextAlign(Paint.Align.LEFT);
        wordW = gold.measureText(INTRO_WORDMARK);
        wordCap = Ui.cap(gold);
        wordX = (w - wordW) / 2;
        wordY = h / 2 + wordCap / 2 - INTRO_WORDMARK_LIFT * k;
        gold.setShader(new LinearGradient(0, wordY - wordCap, 0, wordY + wordCap * 0.08f, INTRO_GOLD, INTRO_GOLD_STOPS, Shader.TileMode.CLAMP));
        // The glow: the letters in plain gold with a soft shadow, under the gradient letters that cover them.
        glow.set(gold);
        glow.setShader(null);
        glow.setColor(INTRO_GOLD[0]);
        glow.setShadowLayer(INTRO_GLOW_RADIUS * k, 0, 0, INTRO_GLOW);
        sweep.set(gold);
        float band = wordW * INTRO_SWEEP_WIDTH;
        // A band at the origin, moved along by its local matrix (no new shader per frame).
        sweep.setShader(new LinearGradient(-band / 2, 0, band / 2, band * 0.35f,
                new int[] {0x00FFFFFF, 0xD9FFFFFF, 0x00FFFFFF}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        int b = Math.max(8, Math.round(48 * k));
        bokeh = Bitmap.createBitmap(b, b, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(b / 2f, b / 2f, b / 2f, new int[] {INTRO_SPARK_COLOR, Ui.alpha(INTRO_SPARK_COLOR, 0.35f), 0x00000000},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(bokeh).drawCircle(b / 2f, b / 2f, b / 2f, p);
        dot.setColor(INTRO_SPARK_COLOR);
    }

    @Override protected void onDraw(Canvas c) {
        c.drawRect(0, 0, w, h, bg);
        if (sparks) drawSparks(c);
        float in = reduced ? 1 : Ui.clamp(t / INTRO_FADE_IN);
        float ease = in * in * (3 - 2 * in);
        float z = reduced ? 1 : INTRO_SCALE_FROM + (1 - INTRO_SCALE_FROM) * ease;
        int a = Math.round(255 * ease);
        c.save();
        c.scale(z, z, w / 2, wordY - wordCap / 2);
        glow.setAlpha(a);
        c.drawText(INTRO_WORDMARK, wordX, wordY, glow);
        gold.setAlpha(a);
        c.drawText(INTRO_WORDMARK, wordX, wordY, gold);
        if (!reduced && t > INTRO_SWEEP_FROM && t < INTRO_SWEEP_TO) {
            float s = (t - INTRO_SWEEP_FROM) / (INTRO_SWEEP_TO - INTRO_SWEEP_FROM);
            s = s * s * (3 - 2 * s);
            float band = wordW * INTRO_SWEEP_WIDTH;
            sweepMatrix.setTranslate(wordX - band + s * (wordW + 2 * band), wordY - wordCap / 2);
            sweep.getShader().setLocalMatrix(sweepMatrix);
            c.drawText(INTRO_WORDMARK, wordX, wordY, sweep);
        }
        c.restore();
    }

    /** Sparks rising, swaying a little; the far ones (low depth) are big, soft bokeh, the near ones sharp points. */
    private void drawSparks(Canvas c) {
        float fadeIn = Ui.clamp(t / 0.6f);
        for (int i = 0; i < INTRO_SPARKS; i++) {
            float depth = sz[i];
            float rise = INTRO_RISE * k * (0.35f + depth);
            float y = sy[i] * (h + 80 * k) - rise * t;
            float span = h + 80 * k;
            y = ((y % span) + span) % span - 40 * k;
            float x = sx[i] * w + (float) Math.sin(sPhase[i] + t * (0.8f + depth)) * INTRO_SWAY * k * depth;
            // Brighter toward the middle of the screen (where the wordmark is), dimmer at the top.
            float a = fadeIn * (0.25f + 0.75f * depth) * Ui.clamp(y / (h * 0.35f));
            if (a <= 0.01f) continue;
            if (depth < INTRO_BOKEH_DEPTH) {
                float r = sSize[i] * k * INTRO_BOKEH_SCALE * (1 - depth);
                rect.set(x - r, y - r, x + r, y + r);
                bokehPaint.setAlpha(Math.round(255 * a * 0.55f));
                c.drawBitmap(bokeh, null, rect, bokehPaint);
            } else {
                float twinkle = 0.75f + 0.25f * (float) Math.sin(sPhase[i] * 3 + t * 6);
                dot.setAlpha(Math.round(255 * a * twinkle));
                c.drawCircle(x, y, sSize[i] * k * depth * 0.6f, dot);
            }
        }
    }
}
