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
 * pad (PasscodePad: six presses of the arrows, or an older PIN's 0–9 row). Wrong: the pad shakes and clears; right: it pulses gold and the
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

        /** `covers`: it's opaque and in place, so nothing of the home screen behind it shows. */
        void onLockCovers(boolean covers);
    }

    private static final int CARDS = 0, PIN = 1, LEAVING = 2;
    private static final float CARD = 240, CARD_GAP = 96, CARD_Y = 560, FOCUS_GROW = 0.18f;
    /**
     * The entrance (TVOS-LAUNCHER-MERGE-SPEC.md §1.5): the header rises 24 px (TITLE_SETTLE); card i starts 30 ms after
     * card i − 1, rising 48 px from 0.92× and fading in (CARD_ENTER); the focused card grows (SNAPPY_FOCUS) 0.20 s in.
     */
    private static final float HEADER_RISE = 24, CARD_RISE = 48, CARD_FROM = 0.92f, CARD_STAGGER = 0.032f, FOCUS_AT = 0.20f;
    private static final float PIN_AVATAR_Y = 300, PIN_AVATAR_SCALE = 0.72f;
    private static final float HUD_W = 1060, HUD_TOP = 520, HUD_H = 300;
    private static final int GOLD = 0xFFF5D061, INK = 0xFF1C1C1E, WHITE = 0xFFFFFFFF;
    /** A faint frost over the Horizon: lighter by day, a little deeper at night. */
    private static final int VEIL_DAY = 0x1AFFFFFF, VEIL_NIGHT = 0x24000000;
    private static final int[] GOLD_RING = {0xFFFFF3B0, 0xFFF5D061, 0xFFD4AF37, 0xFFF5D061};

    private final Activity activity;
    private final Scene scene;
    private final Listener listener;
    private boolean covering;
    private List<ProfileManager.Profile> profiles = new ArrayList<>();
    private Bitmap[] avatars = new Bitmap[0];
    private String[] names = new String[0];
    private float[] nameW = new float[0], cardX = new float[0];
    private boolean[] pins = new boolean[0];
    private Spring[] focus = new Spring[0];
    /** The passcode: six arrows, or an older PIN's 0–9 row (PasscodePad). */
    private final PasscodePad pad = new PasscodePad();
    private String padHint = "";
    private final Spring nudgeX = new Spring(Spring.BOUNDARY_BUMP, 0).px(), nudgeY = new Spring(Spring.BOUNDARY_BUMP, 0).px();
    private final Spring enter = new Spring(Spring.SPATIAL_SCROLL, 1), pin = new Spring(Spring.SPATIAL_SCROLL, 0);
    private final Spring leave = new Spring(Spring.SPATIAL_SCROLL, 0), gold = new Spring(Spring.ROOM_FADE, 0);
    private final Spring keyPress = new Spring(Spring.MICRO_PRESS, 0);
    private final Spring headerIn = new Spring(Spring.TITLE_SETTLE, 1);
    private Spring[] cardIn = new Spring[0];
    private float[] cardAt = new float[0];
    private float clock, focusAt = -1;

    private int mode = CARDS, index, selected = -1;
    private boolean busy, posted, switching, zoomOut, night = true;
    private Bitmap horizon;
    private long lastNanos;
    private String header = "", pinHeader = "", waitText;

    private float w, h, k = 1, size, avatarSize;
    private final Path squircle = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldRing = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint();
    private final Paint frostPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Paint headerPaint, namePaint, waitPaint;
    private Bitmap shadow;
    private final RectF screen = new RectF(), hud = new RectF(), tmp = new RectF(), pillAt = new RectF();

    LockView(Activity a, Scene scene, Listener listener) {
        super(a);
        activity = a;
        this.scene = scene;
        this.listener = listener;
        ring.setStyle(Paint.Style.STROKE);
        goldRing.setStyle(Paint.Style.STROKE);
        setFocusable(false);
        setVisibility(GONE);
    }

    boolean showing() {
        return getVisibility() == VISIBLE;
    }

    /**
     * Shows the gate on the current profile's card. From the intro (`fromIntro`) the Frosted Horizon is already on
     * screen: the backdrop is opaque and 1:1 from the first frame (the same Horizon bitmap as the intro's, so nothing
     * changes) and only the header and cards come in. Otherwise the whole gate arrives from 1.04×, fading in over the
     * home screen. With `on` (Control Center's profiles row) it opens straight on that profile's PIN pad, and Back
     * there cancels.
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
        mode = CARDS;
        selected = -1;
        waitText = null;
        switching = on != null;
        index = Math.max(0, on != null ? profiles.indexOf(on) : ProfileManager.currentIndex(getContext()));
        // The entrance: the header and the cards spring in, staggered; the focused card grows once they're up.
        clock = 0;
        headerIn.snap(on != null ? 1 : 0);
        headerIn.target = 1;
        for (int i = 0; i < cardIn.length; i++) {
            cardIn[i].snap(on != null ? 1 : 0);
            cardAt[i] = i * CARD_STAGGER;
        }
        for (int i = 0; i < focus.length; i++) focus[i].snap(on != null && i == index ? 1 : 0);
        focusAt = on != null ? -1 : FOCUS_AT;
        pin.snap(0);
        leave.snap(0);
        gold.snap(0);
        nudgeX.snap(0);
        nudgeY.snap(0);
        enter.snap(fromIntro ? 1 : 0.6f);   // from the intro: already in place; after a while away: a quick arrival
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
        updateCover();
        listener.onLockGone();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;
        if (mode == LEAVING) return true;
        if (mode == PIN) return padKey(e);
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
                    choose();
                } else if (!down) {
                    keyPress.target = 0;
                }
                wake();
                return true;
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_BACK:
                return true;   // the gate: Back does nothing on the cards
            default:
                if (digit >= 0) return true;
                return !e.isSystem();   // the gate: only the system's own keys (volume, power, media) pass
        }
    }

    /** The passcode pad's keys (PasscodePad): six arrows, or a PIN's row; Back erases, then goes back to the cards. */
    private boolean padKey(KeyEvent e) {
        switch (pad.key(e)) {
            case PasscodePad.COMPLETE:
                postDelayed(checkSoon, Spring.instant ? 0 : 140);   // the last dot lands first
                break;
            case PasscodePad.BACK:
                if (!busy) {
                    if (switching) cancel(); else backToCards();
                }
                break;
            case PasscodePad.UP:
            case PasscodePad.DOWN:
                bump(nudgeY, e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                break;
            case PasscodePad.IGNORED:
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                        || e.getKeyCode() == KeyEvent.KEYCODE_ENTER || isArrow(e.getKeyCode()) || digit(e.getKeyCode()) >= 0) break;
                return !e.isSystem();   // the gate: only the system's own keys (volume, power, media) pass
            default:
                break;
        }
        wake();
        return true;
    }

    private static boolean isArrow(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private final Runnable checkSoon = this::check;

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
            focusAt = -1;
            for (int i = 0; i < focus.length; i++) focus[i].target = i == index ? 1 : 0;
            announceForAccessibility(names[index]);
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
        pad.start(ProfileManager.remotePin(getContext(), p), ProfileManager.pinLength(getContext(), p));
        padHint = pad.remote() ? Ui.tr("Use ↑ ↓ ← → on your remote") : "";
        gold.snap(0);
        pin.target = 1;
        if (switching) pin.snap(1);   // from Control Center: straight onto the pad
        waitTick.run();
        announceForAccessibility(pinHeader);
        wake();
    }

    private void backToCards() {
        mode = CARDS;
        removeCallbacks(checkSoon);
        index = selected;   // the card glides back to its place, still focused
        waitText = null;
        removeCallbacks(waitTick);
        pin.target = 0;
        wake();
    }

    private void check() {
        if (mode != PIN || selected < 0) return;
        ProfileManager.Profile p = profiles.get(selected);
        busy = true;
        if (ProfileManager.checkPin(getContext(), p, pad.code())) {
            gold.target = 1;
            pad.success();
            postDelayed(flyIn, Spring.instant ? 0 : 280);
        } else {
            pad.fail();
            postDelayed(clearDigits, Spring.instant ? 0 : 420);
        }
        wake();
    }

    private final Runnable nextShake = () -> { };   // the pad shakes itself now (PasscodePad.fail)

    private final Runnable clearDigits = () -> {
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
        clock += dt;
        boolean moving = enter.step(dt) | pin.step(dt) | leave.step(dt) | gold.step(dt)
                | nudgeX.step(dt) | nudgeY.step(dt) | keyPress.step(dt) | headerIn.step(dt);
        for (int i = 0; i < cardIn.length; i++) {
            if (cardIn[i].target == 0 && clock >= cardAt[i]) cardIn[i].target = 1;
            moving |= cardIn[i].step(dt);
            if (cardIn[i].target == 0) moving = true;   // waiting for its turn
        }
        if (focusAt >= 0) {
            if (clock >= focusAt) {
                if (mode == CARDS && index < focus.length) focus[index].target = 1;
                focusAt = -1;
            }
            moving = true;
        }
        for (Spring s : focus) moving |= s.step(dt);
        moving |= pad.step(dt, mode == PIN);
        applyEnter();
        updateCover();
        invalidate();
        if (mode == LEAVING && leave.settled() && leave.target == 1) {
            setVisibility(GONE);
            setAlpha(1);
            mode = CARDS;
            updateCover();
            listener.onLockGone();
            return;
        }
        if (moving) {
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** The arrival (1.04 → 1, fading in) and, for Guest, the fade as the card zooms in. */
    private void updateCover() {
        boolean now = getVisibility() == VISIBLE && mode != LEAVING && enter.target == 1 && enter.settled();
        if (now == covering) return;
        covering = now;
        listener.onLockCovers(now);
    }

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
        waitPaint = Ui.text(Ui.medium, 26 * k, 0xD9FFFFFF);
        waitPaint.setTextAlign(Paint.Align.CENTER);
        theme();
        int s = Math.max(8, Math.round(360 * k));
        shadow = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, 0x99000000, 0x00000000, Shader.TileMode.CLAMP));
        new Canvas(shadow).drawCircle(s / 2f, s / 2f, s / 2f, p);
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
        avatarSize = Math.round(size);   // the photo is the card
        if (focus.length != n) {
            focus = new Spring[n];
            cardIn = new Spring[n];
            cardAt = new float[n];
            for (int i = 0; i < n; i++) {
                focus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
                cardIn[i] = new Spring(Spring.CARD_ENTER, 1);
            }
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
            avatars[i] = ProfileManager.avatar(getContext(), p, Math.round(avatarSize * (1 + FOCUS_GROW)));   // sharp when focused
            names[i] = TextUtils.ellipsize(p.name, tp, size + gap - 40 * k, TextUtils.TruncateAt.END).toString();
            nameW[i] = namePaint.measureText(names[i]);
            pins[i] = ProfileManager.needsPin(getContext(), p);
        }
        Corners.continuous(squircle, size, size, size * ProfileManager.CORNER);
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
            float hi = Ui.clamp(headerIn.value);
            headerPaint.setAlpha(Math.round(255 * (1 - pv) * stay * hi));
            c.drawText(header, w / 2, 330 * k - 40 * k * pv + HEADER_RISE * k * (1 - headerIn.value), headerPaint);
        }
        if (pv > 0.001f) {
            headerPaint.setAlpha(Math.round(255 * pv * stay));
            c.drawText(pinHeader, w / 2, 150 * k + 30 * k * (1 - pv), headerPaint);
        }
        headerPaint.setAlpha(255);

        float bumpX = Spring.rubber(nudgeX.value) * k, bumpY = Spring.rubber(nudgeY.value) * k;
        for (int i = 0; i < profiles.size(); i++) {
            if (i == selected && glides(pv)) continue;
            float in = cardIn[i].value, f = Ui.clamp(focus[i].value), alpha = (1 - pv) * stay * Ui.clamp(in);
            if (alpha <= 0.004f) continue;
            float scale = (1 + FOCUS_GROW * focus[i].value - 0.04f * keyPress.value * f) * (CARD_FROM + (1 - CARD_FROM) * in);
            boolean on = i == index && mode == CARDS;
            drawCard(c, i, cardX[i] + (on ? bumpX : 0), CARD_Y * k + (on ? bumpY : 0) + CARD_RISE * k * (1 - in), scale, f, alpha, true);
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
        if (avatars[i] != null) {
            image.setAlpha(Math.round(255 * alpha));
            tmp.set(0, 0, s, s);
            c.drawBitmap(avatars[i], null, tmp, image);
            // A hairline of light at rest, so a dark photo keeps its edge on the night sky.
            ring.setStrokeWidth(1.5f * k / scale);
            ring.setAlpha(Math.round(255 * alpha * 0.3f * (1 - f)));
            c.drawPath(squircle, ring);
        } else {
            fill.setAlpha(Math.round(255 * alpha));
            c.drawPath(squircle, fill);
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

    /**
     * The Liquid Glass passcode pad: the six lines that become dots (or an older PIN's four, over its 0–9 row), how to
     * type it, and "Try again in…" under it while it waits. Gold when right.
     */
    private void drawPad(Canvas c, float alpha) {
        boolean remote = pad.remote();
        float padH = remote ? 210 : HUD_H, padW = remote ? 720 : HUD_W;
        float top = HUD_TOP * k + 40 * k * (1 - Ui.clamp(pin.value));
        float swell = 1 + 0.004f * pad.breath.value;
        hud.set(w / 2 - padW * k / 2 * swell, top, w / 2 + padW * k / 2 * swell, top + padH * k * swell);
        Ui.plate(c, hud, 56 * k, night, alpha, fill, ring, k);
        int text = night ? WHITE : INK;
        float dy = hud.top + (remote ? 86 : 88) * k;
        pad.draw(c, w / 2, dy, k, text, GOLD, night, alpha);
        if (remote && !padHint.isEmpty()) {
            waitPaint.setAlpha(Math.round(255 * alpha * 0.7f));
            c.drawText(padHint, w / 2, hud.bottom - 54 * k, waitPaint);
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
