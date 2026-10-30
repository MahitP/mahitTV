package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * "Who's Watching?" (MAHITTV-PROFILES-SPEC.md §3): the gate over the home screen. The Frosted Horizon, a card per
 * profile (a squircle with the avatar), the focused one grown 1.18× with a specular ring (gold for the manager) and its
 * name and lock glyph lit. Guest goes straight in; a profile with a PIN glides to the centre over a Liquid Glass PIN
 * pad (four dots, a 0–9 row, the remote's number keys). Wrong: the dots shake and clear; right: they pulse gold and the
 * avatar flies into the status pill. Back does nothing on the cards (it's the gate); on the PIN pad it goes back to them.
 * By day its text is dark over the bright Horizon, at night white over its twilight (as web/lock.css).
 * Everything is measured in show()/onSizeChanged; frames only move springs (no allocation in doFrame or onDraw).
 */
final class LockView extends View implements Choreographer.FrameCallback {
    interface Listener {
        /** `p` is in (ProfileManager.unlock is done): the home behind re-renders for it now. */
        void onUnlock(ProfileManager.Profile p);

        /** Where the status pill's avatar is on screen (the flight's landing), in `out`. */
        void pillTarget(RectF out);

        /** The lock is gone. */
        void onLockGone();
    }

    private static final int CARDS = 0, PIN = 1, LEAVING = 2;
    private static final int KEYS = 11, DELETE = 10;
    private static final float CARD = 240, CARD_GAP = 96, CARD_Y = 560, FOCUS_GROW = 0.18f;
    private static final float PIN_AVATAR_Y = 300, PIN_AVATAR_SCALE = 0.72f;
    private static final float HUD_W = 1060, HUD_TOP = 520, HUD_H = 300, KEY = 72, KEY_GAP = 14;
    private static final int GOLD = 0xFFF5D061, INK = 0xFF1C1C1E, WHITE = 0xFFFFFFFF;
    /** A faint frost over the Horizon: lighter by day, a little deeper at night. */
    private static final int VEIL_DAY = 0x1AFFFFFF, VEIL_NIGHT = 0x24000000;
    private static final int[] GOLD_RING = {0xFFFFF3B0, 0xFFF5D061, 0xFFD4AF37, 0xFFF5D061};

    private final Activity activity;
    private final Scene scene;
    private final Listener listener;
    private List<ProfileManager.Profile> profiles = new ArrayList<>();
    private Bitmap[] avatars = new Bitmap[0];
    private String[] names = new String[0];
    private float[] nameW = new float[0], cardX = new float[0];
    private boolean[] pins = new boolean[0];
    private Spring[] focus = new Spring[0];
    private final Spring[] keyFocus = new Spring[KEYS], dotPop = new Spring[4];
    private final Spring nudgeX = new Spring(Spring.BOUNDARY_BUMP, 0).px(), nudgeY = new Spring(Spring.BOUNDARY_BUMP, 0).px();
    private final Spring shake = new Spring(Spring.BOUNDARY_BUMP, 0).px();
    private final Spring enter = new Spring(Spring.SPATIAL_SCROLL, 1), pin = new Spring(Spring.SPATIAL_SCROLL, 0);
    private final Spring leave = new Spring(Spring.SPATIAL_SCROLL, 0), gold = new Spring(Spring.ROOM_FADE, 0);
    private final Spring keyPress = new Spring(Spring.MICRO_PRESS, 0);

    private int mode = CARDS, index, keyIndex, selected = -1, entered, shakeStep;
    private final char[] digits = new char[4];
    private boolean busy, posted, switching, zoomOut, night = true;
    private Bitmap horizon;
    private long lastNanos;
    private String header = "", pinHeader = "", waitText;
    private final String[] keyLabels = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ""};

    private float w, h, k = 1, size, avatarSize;
    private final Path squircle = new Path(), deleteGlyph = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldRing = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint(), dot = new Paint(Paint.ANTI_ALIAS_FLAG), keyFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frostPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Paint headerPaint, namePaint, keyText, waitPaint;
    private Bitmap shadow;
    private final RectF screen = new RectF(), hud = new RectF(), tmp = new RectF(), pillAt = new RectF();

    LockView(Activity a, Scene scene, Listener listener) {
        super(a);
        activity = a;
        this.scene = scene;
        this.listener = listener;
        for (int i = 0; i < KEYS; i++) keyFocus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
        for (int i = 0; i < 4; i++) dotPop[i] = new Spring(Spring.MICRO_PRESS, 1);
        ring.setStyle(Paint.Style.STROKE);
        goldRing.setStyle(Paint.Style.STROKE);
        setFocusable(false);
        setVisibility(GONE);
    }

    boolean showing() {
        return getVisibility() == VISIBLE;
    }

    /**
     * Shows the gate: on the current profile's card, coming in from 1.04× (`fromIntro`: under the intro's dissolve).
     * With `on` (Control Center's profiles row) it opens straight on that profile's PIN pad, and Back there cancels.
     */
    void show(boolean fromIntro, ProfileManager.Profile on) {
        boolean dark = Wallpaper.dark(Prefs.wallpaper(), HomeData.get(getContext()).isNight(System.currentTimeMillis()));
        if (dark != night || horizon == null) {
            night = dark;
            theme();
        }
        profiles = ProfileManager.all(getContext());
        measureProfiles();
        removeCallbacks(waitTick);
        removeCallbacks(clearDigits);
        removeCallbacks(nextShake);
        removeCallbacks(flyIn);
        busy = zoomOut = false;
        entered = 0;
        mode = CARDS;
        selected = -1;
        waitText = null;
        switching = on != null;
        index = Math.max(0, on != null ? profiles.indexOf(on) : ProfileManager.currentIndex(getContext()));
        for (int i = 0; i < focus.length; i++) focus[i].snap(i == index ? 1 : 0);
        pin.snap(0);
        leave.snap(0);
        gold.snap(0);
        shake.snap(0);
        nudgeX.snap(0);
        nudgeY.snap(0);
        enter.snap(fromIntro ? 0 : 0.6f);   // after a while away: a quicker, smaller arrival
        enter.target = 1;
        setVisibility(VISIBLE);
        bringToFront();
        applyEnter();
        if (on != null) choose();
        wake();
    }

    /** Gone without unlocking (Back on a switch from Control Center). */
    private void cancel() {
        removeCallbacks(waitTick);
        mode = CARDS;
        selected = -1;
        setVisibility(GONE);
        listener.onLockGone();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;
        if (mode == LEAVING) return true;
        int digit = digit(code);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) bump(nudgeY, code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (first) {
                    keyPress.target = 1;
                    if (mode == CARDS) choose();
                    else if (keyIndex == DELETE) erase();
                    else type((char) ('0' + keyIndex));
                } else if (!down) {
                    keyPress.target = 0;
                }
                wake();
                return true;
            case KeyEvent.KEYCODE_DEL:
                if (first && mode == PIN) erase();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down && !e.isCanceled() && mode == PIN && !busy) {
                    if (switching) cancel(); else backToCards();
                }
                return true;
            default:
                if (digit >= 0) {
                    if (first && mode == PIN) {
                        keyIndex = digit;
                        type((char) ('0' + digit));
                    }
                    return true;
                }
                return !e.isSystem();   // the gate: only the system's own keys (volume, power, media) pass
        }
    }

    private static int digit(int code) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return code - KeyEvent.KEYCODE_0;
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) return code - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    private void move(int dx) {
        if (mode == CARDS) {
            int next = index + dx;
            if (next < 0 || next >= profiles.size()) {
                bump(nudgeX, dx);
                return;
            }
            index = next;
            for (int i = 0; i < focus.length; i++) focus[i].target = i == index ? 1 : 0;
            announceForAccessibility(names[index]);
        } else {
            int next = keyIndex + dx;
            if (next < 0 || next >= KEYS) {
                bump(nudgeX, dx);
                return;
            }
            keyIndex = next;
        }
        Click c = Click.current();
        if (c != null) c.move();
        wake();
    }

    /** A dead end: the focused thing is pushed that way and springs back, with the knock. */
    private void bump(Spring s, int dir) {
        s.impulse(dir * Spring.BUMP_VELOCITY);
        Click.boundary();
        wake();
    }

    /** OK on a card: Guest (or no PIN) goes in; the others glide to the centre over the PIN pad. */
    private void choose() {
        if (index >= profiles.size()) return;
        ProfileManager.Profile p = profiles.get(index);
        Click c = Click.current();
        if (c != null) c.open();
        selected = index;
        if (!ProfileManager.needsPin(getContext(), p)) {
            zoomOut = true;
            leaveNow();
            return;
        }
        mode = PIN;
        entered = 0;
        keyIndex = 1;
        for (int i = 0; i < KEYS; i++) keyFocus[i].snap(0);
        for (Spring s : dotPop) s.snap(1);
        gold.snap(0);
        pin.target = 1;
        if (switching) pin.snap(1);   // from Control Center: straight onto the pad
        waitTick.run();
        announceForAccessibility(pinHeader);
        wake();
    }

    private void backToCards() {
        mode = CARDS;
        entered = 0;
        index = selected;   // the card glides back to its place, still focused
        waitText = null;
        removeCallbacks(waitTick);
        pin.target = 0;
        wake();
    }

    private void type(char d) {
        if (busy || entered >= 4 || selected < 0) return;
        if (ProfileManager.waitLeft(profiles.get(selected)) > 0) {
            bump(shake, 1);
            return;
        }
        digits[entered] = d;
        dotPop[entered].snap(1.45f);
        dotPop[entered].target = 1;
        entered++;
        Click c = Click.current();
        if (c != null) c.move();
        if (entered == 4) check();
        wake();
    }

    private void erase() {
        if (busy || entered == 0) return;
        entered--;
        dotPop[entered].snap(0.7f);
        dotPop[entered].target = 1;
        wake();
    }

    private void check() {
        ProfileManager.Profile p = profiles.get(selected);
        busy = true;
        if (ProfileManager.checkPin(getContext(), p, new String(digits))) {
            gold.target = 1;
            for (Spring s : dotPop) {
                s.snap(1.35f);
                s.target = 1;
            }
            postDelayed(flyIn, Spring.instant ? 0 : 280);
        } else {
            Click c = Click.current();
            if (c != null) c.error();
            shakeStep = 0;
            nextShake.run();
            postDelayed(clearDigits, Spring.instant ? 0 : 420);
        }
    }

    /** The head-shake: right, left, right, each an impulse on the BOUNDARY_BUMP spring (about ±18 px). */
    private static final float[] SHAKE = {1f, -1.7f, 1.1f};
    private final Runnable nextShake = new Runnable() {
        @Override public void run() {
            if (shakeStep >= SHAKE.length) return;
            shake.impulse(SHAKE[shakeStep++] * Spring.BUMP_VELOCITY);
            wake();
            postDelayed(this, 95);
        }
    };

    private final Runnable clearDigits = () -> {
        entered = 0;
        busy = false;
        this.waitTick.run();
        wake();
    };

    private final Runnable flyIn = this::leaveNow;

    /** "Try again in {n} s" while the profile waits after too many wrong PINs, every second. */
    private final Runnable waitTick = new Runnable() {
        @Override public void run() {
            removeCallbacks(this);
            long left = mode == PIN && selected >= 0 ? ProfileManager.waitLeft(profiles.get(selected)) : 0;
            waitText = left > 0 ? Ui.trf("Try again in {n} s", "n", (left + 999) / 1000) : null;
            if (left > 0) postDelayed(this, 1000);
            invalidate();
        }
    };

    /** In: unlock, the home re-renders behind, and the avatar flies to the pill (or the card zooms in, for Guest). */
    private void leaveNow() {
        ProfileManager.Profile p = profiles.get(selected);
        ProfileManager.unlock(getContext(), p);
        listener.onUnlock(p);
        listener.pillTarget(pillAt);
        mode = LEAVING;
        removeCallbacks(waitTick);
        waitText = null;
        leave.snap(0);
        leave.target = 1;
        wake();
    }

    // MARK: - Frames

    private void wake() {
        if (posted || !showing()) return;
        posted = true;
        lastNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        posted = false;
        if (!showing()) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(1 / 30f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        boolean moving = enter.step(dt) | pin.step(dt) | leave.step(dt) | gold.step(dt) | shake.step(dt)
                | nudgeX.step(dt) | nudgeY.step(dt) | keyPress.step(dt);
        for (Spring s : focus) moving |= s.step(dt);
        for (int i = 0; i < KEYS; i++) {
            keyFocus[i].target = mode == PIN && i == keyIndex ? 1 : 0;
            moving |= keyFocus[i].step(dt);
        }
        for (Spring s : dotPop) moving |= s.step(dt);
        applyEnter();
        invalidate();
        if (mode == LEAVING && leave.settled() && leave.target == 1) {
            setVisibility(GONE);
            setAlpha(1);
            mode = CARDS;
            listener.onLockGone();
            return;
        }
        if (moving) {
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** The arrival (1.04 → 1, fading in) and, for Guest, the fade as the card zooms in. */
    private void applyEnter() {
        float e = Ui.clamp(enter.value), z = 1.04f - 0.04f * enter.value;
        setScaleX(z);
        setScaleY(z);
        setAlpha(zoomOut && mode == LEAVING ? e * (1 - Ui.clamp(leave.value)) : e);
    }

    // MARK: - Layout (never while drawing)

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        w = width;
        h = height;
        k = w / 1920f;
        setPivotX(w / 2);
        setPivotY(h / 2);
        screen.set(0, 0, w, h);
        headerPaint = Ui.text(Ui.displaySemibold, 57 * k, 0xFFFFFFFF);
        headerPaint.setTextAlign(Paint.Align.CENTER);
        headerPaint.setShadowLayer(24 * k, 0, 2 * k, 0x66000000);
        namePaint = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
        namePaint.setTextAlign(Paint.Align.CENTER);
        namePaint.setShadowLayer(16 * k, 0, 1 * k, 0x80000000);
        keyText = Ui.numbers(Ui.semibold, 32 * k, 0xFFFFFFFF);
        keyText.setTextAlign(Paint.Align.CENTER);
        waitPaint = Ui.text(Ui.medium, 26 * k, 0xD9FFFFFF);
        waitPaint.setTextAlign(Paint.Align.CENTER);
        theme();
        int s = Math.max(8, Math.round(360 * k));
        shadow = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, 0x99000000, 0x00000000, Shader.TileMode.CLAMP));
        new Canvas(shadow).drawCircle(s / 2f, s / 2f, s / 2f, p);
        float g = 15 * k;   // the delete key's glyph: a tag pointing left with an ×
        deleteGlyph.rewind();
        deleteGlyph.moveTo(-g * 1.3f, 0);
        deleteGlyph.lineTo(-g * 0.55f, -g * 0.8f);
        deleteGlyph.lineTo(g * 1.2f, -g * 0.8f);
        deleteGlyph.lineTo(g * 1.2f, g * 0.8f);
        deleteGlyph.lineTo(-g * 0.55f, g * 0.8f);
        deleteGlyph.close();
        Symbols.warm(getContext(), "lock", 22 * k);
        measureProfiles();
    }

    /** The Horizon for this size and theme, and the text in its colours: dark by day, white at night. */
    private void theme() {
        if (w == 0 || headerPaint == null) return;
        horizon = Horizon.get((int) w, (int) h, night);
        int text = night ? WHITE : INK;
        headerPaint.setColor(text);
        headerPaint.setShadowLayer(24 * k, 0, 2 * k, night ? 0x66000000 : 0x40FFFFFF);
        namePaint.setColor(text);
        namePaint.setShadowLayer(16 * k, 0, 1 * k, night ? 0x80000000 : 0x4DFFFFFF);
        keyText.setColor(text);
        waitPaint.setColor(Ui.alpha(text, 0.85f));
        scrim.setColor(night ? VEIL_NIGHT : VEIL_DAY);
    }

    /** Cards, avatars, names and the ring's gradient for the current list (show() and a size change). */
    private void measureProfiles() {
        if (namePaint == null) return;
        int n = profiles.size();
        size = CARD * k;
        float gap = CARD_GAP * k, total = n * size + Math.max(0, n - 1) * gap;
        if (total > 1760 * k && n > 0) {   // more than six: smaller cards, same proportions
            float f = 1760 * k / total;
            size *= f;
            gap *= f;
            total = 1760 * k;
        }
        avatarSize = Math.round(size * 0.84f);
        if (focus.length != n) {
            focus = new Spring[n];
            for (int i = 0; i < n; i++) focus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
        }
        avatars = new Bitmap[n];
        names = new String[n];
        nameW = new float[n];
        cardX = new float[n];
        pins = new boolean[n];
        TextPaint tp = new TextPaint(namePaint);
        for (int i = 0; i < n; i++) {
            ProfileManager.Profile p = profiles.get(i);
            cardX[i] = (w - total) / 2 + i * (size + gap) + size / 2;
            avatars[i] = ProfileManager.avatar(getContext(), p, Math.round(avatarSize));
            names[i] = TextUtils.ellipsize(p.name, tp, size + gap - 40 * k, TextUtils.TruncateAt.END).toString();
            nameW[i] = namePaint.measureText(names[i]);
            pins[i] = ProfileManager.needsPin(getContext(), p);
        }
        Corners.continuous(squircle, size, size, size * 0.225f);
        fill.setShader(new LinearGradient(0, 0, 0, size, 0x4DFFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP));
        goldRing.setShader(new LinearGradient(0, 0, size, size, GOLD_RING, null, Shader.TileMode.CLAMP));
        ring.setShader(new LinearGradient(0, 0, 0, size, 0xFFFFFFFF, 0x99FFFFFF, Shader.TileMode.CLAMP));
        header = Ui.tr("Who's Watching?");
        pinHeader = Ui.tr("Enter Passcode");
        setContentDescription(header);
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (namePaint == null) return;
        boolean flying = mode == LEAVING && !zoomOut;
        float fly = flying ? Ui.clamp(leave.value) : 0, stay = 1 - fly;
        float pv = Ui.clamp(pin.value);
        drawBackdrop(c, stay);

        // Header: "Who's Watching?" crossfades to "Enter Passcode" on the PIN pad.
        if (pv < 0.999f) {
            headerPaint.setAlpha(Math.round(255 * (1 - pv) * stay));
            c.drawText(header, w / 2, 330 * k - 40 * k * pv, headerPaint);
        }
        if (pv > 0.001f) {
            headerPaint.setAlpha(Math.round(255 * pv * stay));
            c.drawText(pinHeader, w / 2, 150 * k + 30 * k * (1 - pv), headerPaint);
        }
        headerPaint.setAlpha(255);

        float bumpX = Spring.rubber(nudgeX.value) * k, bumpY = Spring.rubber(nudgeY.value) * k;
        for (int i = 0; i < profiles.size(); i++) {
            if (i == selected && glides(pv)) continue;
            float f = Ui.clamp(focus[i].value), alpha = (1 - pv) * stay;
            if (alpha <= 0.004f) continue;
            float scale = 1 + FOCUS_GROW * focus[i].value - 0.04f * keyPress.value * f;
            boolean on = i == index && mode == CARDS;
            drawCard(c, i, cardX[i] + (on ? bumpX : 0), CARD_Y * k + (on ? bumpY : 0), scale, f, alpha, true);
        }

        if (glides(pv)) {
            // The chosen card glides from its place to the top centre (and on into the pill when it's in).
            float fs = 1 + FOCUS_GROW * focus[selected].value;
            float x = cardX[selected] + (w / 2 - cardX[selected]) * pin.value;
            float y = CARD_Y * k + (PIN_AVATAR_Y * k - CARD_Y * k) * pin.value;
            float scale = fs + (PIN_AVATAR_SCALE - fs) * pin.value;
            if (zoomOut && mode == LEAVING) scale += 0.7f * leave.value;
            if (flying) {
                drawFlight(c, x, y, scale, fly);
            } else {
                drawCard(c, selected, x, y, scale, mode == PIN ? 1 : Ui.clamp(focus[selected].value), 1, mode != PIN);
                if (mode == PIN) {
                    namePaint.setAlpha(Math.round(255 * pv));
                    c.drawText(names[selected], x, y + size * scale / 2 + 52 * k, namePaint);
                    namePaint.setAlpha(255);
                }
            }
        }
        if (pv > 0.001f) drawPad(c, pv * stay);
    }

    /** The chosen card is drawn on its own: on the pad, on its way there or back, and going in. */
    private boolean glides(float pv) {
        return selected >= 0 && selected < profiles.size() && (mode != CARDS || pv > 0.001f);
    }

    /** The Frosted Horizon (day or twilight) and a faint frost over it. */
    private void drawBackdrop(Canvas c, float alpha) {
        if (alpha <= 0.004f) return;
        Horizon.draw(c, horizon, screen, frostPaint, alpha);
        scrim.setColor(night ? VEIL_NIGHT : VEIL_DAY);
        scrim.setAlpha(Math.round((scrim.getColor() >>> 24) * alpha));
        c.drawRect(screen, scrim);
    }

    /** A profile's card at (cx, cy): squircle glass, the avatar, and with focus `f` its shadow, ring, name and lock. */
    private void drawCard(Canvas c, int i, float cx, float cy, float scale, float f, float alpha, boolean label) {
        float s = size;
        if (f > 0.01f && shadow != null) {
            float r = s * scale * 0.62f;
            tmp.set(cx - r, cy - r + 26 * k, cx + r, cy + r + 26 * k);
            image.setAlpha(Math.round(255 * f * alpha * 0.8f));
            c.drawBitmap(shadow, null, tmp, image);
        }
        c.save();
        c.translate(cx - s / 2, cy - s / 2);
        c.scale(scale, scale, s / 2, s / 2);
        fill.setAlpha(Math.round(255 * alpha));
        c.drawPath(squircle, fill);
        if (avatars[i] != null) {
            image.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(avatars[i], (s - avatars[i].getWidth()) / 2f, (s - avatars[i].getHeight()) / 2f, image);
        }
        if (f > 0.01f) {
            Paint p = profiles.get(i).manager() ? goldRing : ring;
            p.setStrokeWidth(5 * k / scale);
            p.setAlpha(Math.round(255 * f * alpha));
            c.drawPath(squircle, p);
        }
        c.restore();
        image.setAlpha(255);
        if (!label) return;
        float y = cy + s * scale / 2 + 52 * k;
        namePaint.setAlpha(Math.round(255 * alpha * (0.6f + 0.4f * f)));
        c.drawText(names[i], cx, y, namePaint);
        namePaint.setAlpha(255);
        if (pins[i] && f > 0.01f) {
            int a = Math.round(255 * f * alpha * 0.85f);
            Symbols.draw(getContext(), c, "lock", cx + nameW[i] / 2 + 22 * k, y - Ui.cap(namePaint) / 2, 22 * k, (a << 24) | ((night ? WHITE : INK) & 0xFFFFFF));
        }
    }

    /** The avatar on its way into the pill: the card melts away, the round avatar shrinks onto the pill's. */
    private void drawFlight(Canvas c, float x, float y, float scale, float t) {
        float tx = pillAt.isEmpty() ? x : pillAt.centerX(), ty = pillAt.isEmpty() ? y : pillAt.centerY();
        float cx = x + (tx - x) * leave.value, cy = y + (ty - y) * leave.value;
        float from = avatarSize * scale, to = pillAt.isEmpty() ? from : pillAt.width();
        float d = from + (to - from) * leave.value, z = d / from;
        if (t < 0.999f) {
            c.save();
            c.translate(cx - size / 2, cy - size / 2);
            c.scale(scale * z, scale * z, size / 2, size / 2);
            fill.setAlpha(Math.round(255 * (1 - t)));
            c.drawPath(squircle, fill);
            c.restore();
        }
        Bitmap a = avatars[selected];
        if (a != null) {
            tmp.set(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2);
            image.setAlpha(255);
            c.drawBitmap(a, null, tmp, image);
        }
    }

    /** The Liquid Glass PIN pad: four dots and the 0–9 row with delete; "Try again in…" under it while it waits. */
    private void drawPad(Canvas c, float alpha) {
        float top = HUD_TOP * k + 40 * k * (1 - Ui.clamp(pin.value));
        hud.set(w / 2 - HUD_W * k / 2, top, w / 2 + HUD_W * k / 2, top + HUD_H * k);
        scene.glass(c, hud, 56 * k, night ? 0xFF1C1C22 : WHITE, night ? 0.42f : 0.5f, WHITE, 0.06f, 0.04f, 1, alpha);

        // Dots (they shake together on a wrong PIN; each pops as its digit comes; gold when right).
        float dy = hud.top + 88 * k, gap = 64 * k, x0 = w / 2 - gap * 1.5f + shake.value * k;
        int text = night ? WHITE : INK, ink = blend(text, GOLD, Ui.clamp(gold.value));
        for (int i = 0; i < 4; i++) {
            float r = 13 * k * dotPop[i].value, x = x0 + i * gap;
            if (i < entered || busy && gold.target == 1) {
                dot.setStyle(Paint.Style.FILL);
                dot.setColor(ink);
            } else {
                dot.setStyle(Paint.Style.STROKE);
                dot.setStrokeWidth(3 * k);
                dot.setColor(night ? 0xB3FFFFFF : 0x991C1C1E);
            }
            dot.setAlpha(Math.round((dot.getColor() >>> 24) * alpha));
            c.drawCircle(x, dy, r, dot);
        }

        // The row: 0–9 and delete; the focused key grows (SNAPPY_FOCUS) and turns white with dark ink, as tvOS's.
        float key = KEY * k, step = key + KEY_GAP * k, kx0 = w / 2 - step * (KEYS - 1) / 2, ky = hud.top + 206 * k;
        float half = Ui.cap(keyText) / 2;
        float bump = Spring.rubber(nudgeX.value) * k;
        for (int i = 0; i < KEYS; i++) {
            float f = Ui.clamp(keyFocus[i].value), x = kx0 + i * step + (i == keyIndex ? bump : 0);
            float r = key / 2 * (1 + FOCUS_GROW * keyFocus[i].value - 0.06f * keyPress.value * f);
            // tvOS's focused key: white with dark ink at night, dark with white ink over the day Horizon.
            keyFill.setColor(blend(night ? 0x26FFFFFF : 0x73FFFFFF, night ? WHITE : INK, f));
            keyFill.setAlpha(Math.round((keyFill.getColor() >>> 24) * alpha));
            c.drawCircle(x, ky, r, keyFill);
            int keyInk = blend(text, night ? INK : WHITE, f);
            if (i == DELETE) {
                keyFill.setColor(keyInk);
                keyFill.setAlpha(Math.round(255 * alpha));
                c.save();
                c.translate(x, ky);
                c.drawPath(deleteGlyph, keyFill);
                c.restore();
            } else {
                keyText.setColor(keyInk);
                keyText.setAlpha(Math.round(255 * alpha));
                c.drawText(keyLabels[i], x, ky + half, keyText);
            }
        }
        if (waitText != null) {
            waitPaint.setAlpha(Math.round(255 * alpha));
            c.drawText(waitText, w / 2, hud.bottom + 60 * k, waitPaint);
        }
    }

    /** a → b by t, channel by channel (alpha too). */
    private static int blend(int a, int b, float t) {
        int aa = a >>> 24, ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
                | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }
}
