package io.freemytv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.View;

/**
 * The volume, as tvOS shows it: a Liquid Glass capsule at the left edge, 72×320 (1080p px) at x 56, centred vertically,
 * filled white from the bottom to the level (SNAPPY_FOCUS), with a speaker at its foot (muted, or 1 to 3 arcs for 1–33,
 * 34–66, 67–100) and the level above it, in ink where the fill has reached them. It slides in from 40 px to the left as
 * it fades in (SHEET_OPEN) and leaves HIDE_MS after the last change (SHEET_CLOSE); a press past 0 or 100 stretches it
 * 3% that way (BOUNDARY_BUMP). HomeView shows it for the volume keys while the home screen is in front; other apps keep
 * the system's own panel; Control Center's brightness shows it too, with the sun (showBrightness). The view only bounds the capsule: it draws in screen coordinates, like HomeView's layers, so
 * the glass reads the background where it really is.
 */
final class VolumeHud extends View implements Choreographer.FrameCallback {
    /** The capsule and its fill, in 1080p px. */
    private static final float X = 56, W = 72, H = 320, INSET = 6, FILL_R = 30, SLIDE = 40, STRETCH = 0.03f;
    /** The view around it: room for the slide, the stretch and the glass's edge (HomeView measures it with these). */
    static final float VIEW_W = 200, VIEW_H = 420;
    private static final long HIDE_MS = 2200;
    /** The push a press past an end gives the stretch (BOUNDARY_BUMP peaks near 1 with it). */
    private static final float PUSH = 60;
    private static final int INK = 0xFF1C1C1E, WHITE = 0xFFFFFFFF, TINT = 0xFF1C1C22;

    private final Scene scene;
    private final Spring appear = new Spring(Spring.SHEET_OPEN, 0), level = new Spring(Spring.SNAPPY_FOCUS, 0);
    private final Spring arcs = new Spring(Spring.SNAPPY_FOCUS, 0), bump = new Spring(Spring.BOUNDARY_BUMP, 0);
    private int bumpDir = 1;   // 1: past 100, it stretches upwards; −1: past 0, downwards
    private boolean bright;    // the brightness (Control Center's slider): the sun at its foot instead of the speaker
    private boolean airpods;   // AirPods or Beats are the output: their buds instead of the speaker

    void setAirPods(boolean on) {
        if (airpods == on) return;
        airpods = on;
        invalidate();
    }
    private String number = "0";
    private boolean posted, leaving;
    private long lastNanos;
    private float k = 1;
    private final RectF capsule = new RectF(), fill = new RectF();
    private final RectF[] arcOvals = {new RectF(), new RectF(), new RectF()};
    private final Path speaker = new Path();
    private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG), glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint numberP;
    private final Runnable hideSoon = this::hide;

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
        number = Integer.toString(p);
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
            setVisibility(VISIBLE);
        }
        leaving = false;
        appear.tune(Spring.SHEET_OPEN);
        appear.target = 1;
        removeCallbacks(hideSoon);
        postDelayed(hideSoon, HIDE_MS);
        wake();
    }

    /** Leaves (SHEET_CLOSE). */
    void hide() {
        removeCallbacks(hideSoon);
        if (getVisibility() != VISIBLE) return;
        leaving = true;
        appear.tune(Spring.SHEET_CLOSE);
        appear.target = 0;
        wake();
    }

    /** Gone at once (the home screen went away). */
    void dismiss() {
        removeCallbacks(hideSoon);
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
        boolean moving = appear.step(dt) | level.step(dt) | arcs.step(dt) | bump.step(dt);
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
        numberP = Ui.numbers(Ui.semibold, 21 * k, WHITE);
        numberP.setTextAlign(Paint.Align.CENTER);
    }

    @Override protected void onDraw(Canvas c) {
        if (numberP == null) return;
        float v = Ui.clamp(appear.value);
        if (v <= 0.004f) return;
        c.translate(-getLeft(), -getTop());
        float x = (X - SLIDE * (1 - v)) * k, cy = getTop() + getHeight() / 2f, full = H * k;
        // Stretched away from the end that was pushed: past 100 it grows upwards, past 0 downwards.
        float tall = full * (1 + STRETCH * bump.value);
        float top = bumpDir < 0 ? cy - full / 2 : cy + full / 2 - tall;
        capsule.set(x, top, x + W * k, top + tall);
        scene.glass(c, capsule, W * k / 2, TINT, 0.5f, WHITE, 0.06f, 0.04f, 1, v);
        float lv = Ui.clamp(level.value), in = INSET * k;
        float fillTop = capsule.bottom - in - (capsule.height() - 2 * in) * lv;
        if (lv > 0.002f) {
            fill.set(capsule.left + in, fillTop, capsule.right - in, capsule.bottom - in);
            float r = Math.min(FILL_R * k, fill.height() / 2);
            fillP.setAlpha(Math.round(255 * v));
            c.drawRoundRect(fill, r, r, fillP);
        }
        // The speaker and the number: white on the glass, ink where the fill has reached them.
        float gx = capsule.centerX(), gy = capsule.bottom - 40 * k;
        c.save();
        c.clipRect(capsule.left, capsule.top, capsule.right, fillTop);
        drawMarks(c, gx, gy, WHITE, v);
        c.restore();
        if (lv > 0.002f) {
            c.save();
            c.clipRect(capsule.left, fillTop, capsule.right, capsule.bottom);
            drawMarks(c, gx, gy, INK, v);
            c.restore();
        }
    }

    private void drawMarks(Canvas c, float gx, float gy, int color, float v) {
        int a = Math.round(255 * v);
        numberP.setColor(color);
        numberP.setAlpha(a);
        c.drawText(number, gx, gy - 34 * k, numberP);
        if (bright || airpods) {
            Symbols.draw(getContext(), c, bright ? "sunny" : "airpods", gx, gy, 30 * k, Ui.alpha(color, v));
            return;
        }
        c.save();
        c.translate(gx, gy);
        c.scale(k, k);
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
