package io.freemytv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.View;

/**
 * The volume and the brightness, as an iPhone shows its volume: a Liquid Glass pill against the left edge, where an
 * iPhone's volume buttons are (centred at y CENTER_Y), filled white from the bottom to the level (SNAPPY_FOCUS), the
 * speaker (muted, or 1 to 3 arcs for 1–33, 34–66, 67–100), the sun or AirPods at its foot. It comes out of the edge as
 * a 52 × 250 (1080p px) pill, narrows to a 12 px line IDLE_MS after the last change (the glyph fading), and leaves
 * HIDE_MS after it; each change widens it again (LIQUID_PILL). A press past 0 or 100 stretches it 3% that way
 * (BOUNDARY_BUMP). HomeView shows it for the volume keys and the channel keys (the brightness) while the home screen
 * is in front, and for Control Center's brightness. The view only bounds the pill: it draws in screen coordinates, like
 * HomeView's layers, so the glass reads the background where it really is.
 */
final class VolumeHud extends View implements Choreographer.FrameCallback {
    /** The pill and its fill, in 1080p px: wide while it changes, a line once it rests. */
    private static final float X = 22, W = 52, THIN = 12, H = 250, SLIDE = 40, STRETCH = 0.03f;
    /** The view around it, and where its centre is (HomeView places it with these). */
    static final float VIEW_W = 120, VIEW_H = 330, CENTER_Y = 340;
    private static final long IDLE_MS = 1100, HIDE_MS = 2400;
    /** The push a press past an end gives the stretch (BOUNDARY_BUMP peaks near 1 with it). */
    private static final float PUSH = 60;
    private static final int INK = 0xFF1C1C1E, WHITE = 0xFFFFFFFF, TINT = 0xFF1C1C22;

    private final Scene scene;
    private final Spring appear = new Spring(Spring.SHEET_OPEN, 0), level = new Spring(Spring.SNAPPY_FOCUS, 0);
    private final Spring arcs = new Spring(Spring.SNAPPY_FOCUS, 0), bump = new Spring(Spring.BOUNDARY_BUMP, 0);
    private final Spring wide = new Spring(Spring.LIQUID_PILL, 1);   // 1: the pill; 0: the line it rests as
    private int bumpDir = 1;   // 1: past 100, it stretches upwards; −1: past 0, downwards
    private boolean bright;    // the brightness (Control Center's slider): the sun at its foot instead of the speaker
    private boolean airpods;   // AirPods or Beats are the output: their buds instead of the speaker

    void setAirPods(boolean on) {
        if (airpods == on) return;
        airpods = on;
        invalidate();
    }
    private boolean posted, leaving;
    private long lastNanos;
    private float k = 1;
    private final RectF capsule = new RectF(), fill = new RectF();
    private final RectF[] arcOvals = {new RectF(), new RectF(), new RectF()};
    private final Path speaker = new Path();
    private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG), glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable hideSoon = this::hide;
    private final Runnable narrow = () -> {
        wide.tune(Spring.SHEET_CLOSE);
        wide.target = 0;
        wake();
    };

    VolumeHud(Activity a, Scene scene) {
        super(a);
        this.scene = scene;
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // HomeView announces the level
        setVisibility(GONE);
        // The speaker, centred on (0, 0) in 1080p px: a box and its cone, and the arcs around the cone's mouth.
        speaker.moveTo(-15, -5);
        speaker.lineTo(-9, -5);
        speaker.lineTo(-2, -11);
        speaker.lineTo(-2, 11);
        speaker.lineTo(-9, 5);
        speaker.lineTo(-15, 5);
        speaker.close();
        for (int i = 0; i < 3; i++) {
            float r = 6 + 5 * i;
            arcOvals[i].set(-1 - r, -r, -1 + r, r);
        }
        glyph.setStyle(Paint.Style.FILL_AND_STROKE);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        glyph.setStrokeWidth(2);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(2.6f);
        fillP.setColor(WHITE);
    }

    /** A new level (0–100), muted or not; `push` 1 or −1 when the key pressed past 100 or 0. */
    void show(int percent, boolean muted, int push) {
        bright = false;
        present(muted ? 0 : percent, push);
    }

    /** The brightness (0–100), the same capsule with the sun; `push` as show's. */
    void showBrightness(int percent, int push) {
        bright = true;
        present(percent, push);
    }

    private void present(int percent, int push) {
        int p = Math.max(0, Math.min(100, percent));
        level.target = p / 100f;
        arcs.target = p == 0 ? 0 : p <= 33 ? 1 : p <= 66 ? 2 : 3;
        if (push != 0) {
            bumpDir = push;
            bump.impulse(PUSH);
        }
        if (getVisibility() != VISIBLE) {
            appear.snap(0);
            level.snap(level.target);
            arcs.snap(arcs.target);
            bump.snap(0);
            wide.snap(1);
            setVisibility(VISIBLE);
        }
        leaving = false;
        appear.tune(Spring.SHEET_OPEN);
        appear.target = 1;
        wide.tune(Spring.LIQUID_PILL);   // each change: the pill again, springing out of the line
        wide.target = 1;
        removeCallbacks(narrow);
        removeCallbacks(hideSoon);
        postDelayed(narrow, IDLE_MS);
        postDelayed(hideSoon, HIDE_MS);
        wake();
    }

    /** Leaves (SHEET_CLOSE). */
    void hide() {
        removeCallbacks(hideSoon);
        removeCallbacks(narrow);
        if (getVisibility() != VISIBLE) return;
        leaving = true;
        appear.tune(Spring.SHEET_CLOSE);
        appear.target = 0;
        wake();
    }

    /** Gone at once (the home screen went away). */
    void dismiss() {
        removeCallbacks(hideSoon);
        removeCallbacks(narrow);
        leaving = false;
        appear.snap(0);
        setVisibility(GONE);
    }

    // MARK: - Frames

    private void wake() {
        if (posted || getVisibility() != VISIBLE) return;
        posted = true;
        lastNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        posted = false;
        if (getVisibility() != VISIBLE) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(1 / 30f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        boolean moving = appear.step(dt) | level.step(dt) | arcs.step(dt) | bump.step(dt) | wide.step(dt);
        invalidate();
        if (leaving && (appear.settled() || appear.value <= 0.004f)) {
            leaving = false;
            setVisibility(GONE);
            return;
        }
        if (moving) {
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    // MARK: - Layout and drawing

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        k = width / VIEW_W;
    }

    @Override protected void onDraw(Canvas c) {
        float v = Ui.clamp(appear.value);
        if (v <= 0.004f) return;
        c.translate(-getLeft(), -getTop());
        // Out of the edge: it slides in from the left as it fades in; narrowing, it keeps its left side against the edge.
        float ww = Math.max(0, wide.value), wv = Ui.clamp(ww), w = (THIN + (W - THIN) * ww) * k;
        float x = (X - SLIDE * (1 - v)) * k, cy = getTop() + getHeight() / 2f, full = H * k;
        // Stretched away from the end that was pushed: past 100 it grows upwards, past 0 downwards.
        float tall = full * (1 + STRETCH * bump.value);
        float top = bumpDir < 0 ? cy - full / 2 : cy + full / 2 - tall;
        capsule.set(x, top, x + w, top + tall);
        scene.glass(c, capsule, w / 2, TINT, 0.5f, WHITE, 0.06f, 0.04f, 1, v);
        float lv = Ui.clamp(level.value), in = (2 + 3 * wv) * k;
        float fillTop = capsule.bottom - in - (capsule.height() - 2 * in) * lv;
        if (lv > 0.002f) {
            fill.set(capsule.left + in, fillTop, capsule.right - in, capsule.bottom - in);
            float r = Math.min(fill.width() / 2, fill.height() / 2);
            fillP.setAlpha(Math.round(255 * v));
            c.drawRoundRect(fill, r, r, fillP);
        }
        // The glyph at its foot: white on the glass, ink where the fill has reached it; gone as it narrows.
        float ga = v * Ui.clamp((wv - 0.4f) / 0.6f);
        if (ga < 0.01f) return;
        float gx = capsule.centerX(), gy = capsule.bottom - 28 * k;
        c.save();
        c.clipRect(capsule.left, capsule.top, capsule.right, fillTop);
        drawMarks(c, gx, gy, WHITE, ga);
        c.restore();
        if (lv > 0.002f) {
            c.save();
            c.clipRect(capsule.left, fillTop, capsule.right, capsule.bottom);
            drawMarks(c, gx, gy, INK, ga);
            c.restore();
        }
    }

    private void drawMarks(Canvas c, float gx, float gy, int color, float v) {
        int a = Math.round(255 * v);
        if (bright || airpods) {
            Symbols.draw(getContext(), c, bright ? "sunny" : "airpods", gx, gy, 26 * k, Ui.alpha(color, v));
            return;
        }
        c.save();
        c.translate(gx + 2 * k, gy);
        c.scale(0.85f * k, 0.85f * k);
        glyph.setColor(color);
        glyph.setAlpha(a);
        c.drawPath(speaker, glyph);
        arc.setColor(color);
        float n = arcs.value;
        for (int i = 0; i < 3; i++) {
            float on = Ui.clamp(n - i);
            if (on <= 0.01f) continue;
            arc.setAlpha(Math.round(a * on));
            c.drawArc(arcOvals[i], -45, 90, false, arc);
        }
        float slash = Ui.clamp(1 - n);   // muted (or 0): the speaker struck through
        if (slash > 0.01f) {
            arc.setAlpha(Math.round(a * slash));
            c.drawLine(-18, -12, 12, 12, arc);
        }
        c.restore();
    }
}
