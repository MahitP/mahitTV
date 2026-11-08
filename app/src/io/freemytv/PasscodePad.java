package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * mahitTV's passcode entry (DESIGN-PASSCODE.md), shared by "Who's Watching?" (LockView), Settings and App Lock
 * (AppLockView). A remote passcode is six presses of the ring around OK (↑ ↓ ← →), as Fire TV and Xbox let you type
 * theirs: nothing on screen moves with the press, so it can't be read over a shoulder, and no row of digits to walk.
 * Six short lines stand for it; each press turns the next one into a dot (the line draws in and swells, on a spring)
 * and the whole pad breathes once, the same for every direction. Older four-digit PINs keep the 0–9 row until they're
 * changed (the remote's number keys work on both).
 * The host draws the glass around it, routes keys to key(), steps it with its frames and says fail() or success().
 * No allocation while drawing.
 */
final class PasscodePad {
    static final int REMOTE_LENGTH = 6, DIGIT_LENGTH = 4;
    /** What a key did (key()): the host redraws, checks a full code, leaves, or acts on OK. */
    static final int IGNORED = 0, TYPED = 1, COMPLETE = 2, ERASED = 3, BACK = 4, OK = 5, MOVED = 6, UP = 7, DOWN = 8;
    private static final int KEYS = 11, DELETE = 10;
    /** The symbols of a remote passcode, by key. */
    static final char UP_KEY = 'U', DOWN_KEY = 'D', LEFT_KEY = 'L', RIGHT_KEY = 'R';
    /** The head-shake: right, left, right, impulses 95 ms apart on BOUNDARY_BUMP (about ±18 px), as LockView's was. */
    private static final float[] SHAKE = {1f, -1.7f, 1.1f};
    private static final long SHAKE_MS = 95, CLEAR_MS = 420;

    private boolean remote = true;
    private int length = REMOTE_LENGTH, keyIndex = 1, shakeStep = SHAKE.length;
    private final StringBuilder code = new StringBuilder();
    private boolean busy;
    private long nextShakeAt, clearAt;
    /** Each slot: 0 a line, 1 a dot; and its pop as it lands. */
    private final Spring[] morph = new Spring[REMOTE_LENGTH], pop = new Spring[REMOTE_LENGTH];
    private final Spring shake = new Spring(Spring.BOUNDARY_BUMP, 0).px(), nudge = new Spring(Spring.BOUNDARY_BUMP, 0).px();
    /** Right: the dots light up in the accent; the breath: one soft swell of the whole pad per press. */
    final Spring flash = new Spring(Spring.ROOM_FADE, 0), breath = new Spring(Spring.MICRO_PRESS, 0);
    private final Spring[] keyFocus = new Spring[KEYS];
    private final Spring keyPress = new Spring(Spring.MICRO_PRESS, 0);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path deleteGlyph = new Path();
    private Paint keyText;
    private float glyphK = -1;

    PasscodePad() {
        for (int i = 0; i < REMOTE_LENGTH; i++) {
            morph[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
            pop[i] = new Spring(Spring.MICRO_PRESS, 1);
        }
        for (int i = 0; i < KEYS; i++) keyFocus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
    }

    /** A new entry: a remote passcode (six presses of the arrows) or a four-digit PIN with its 0–9 row. */
    void start(boolean remote, int length) {
        this.remote = remote;
        this.length = Math.max(1, Math.min(REMOTE_LENGTH, length));
        code.setLength(0);
        busy = false;
        clearAt = 0;
        shakeStep = SHAKE.length;
        keyIndex = 1;
        for (int i = 0; i < REMOTE_LENGTH; i++) {
            morph[i].snap(0);
            pop[i].snap(1);
        }
        for (Spring s : keyFocus) s.snap(0);
        shake.snap(0);
        nudge.snap(0);
        flash.snap(0);
        breath.snap(0);
    }

    boolean remote() {
        return remote;
    }

    String code() {
        return code.toString();
    }

    int entered() {
        return code.length();
    }

    /** Wrong: the head-shake and the knock; the dots go back to lines a moment later. */
    void fail() {
        busy = true;
        shakeStep = 0;
        nextShakeAt = 0;
        clearAt = SystemClock.uptimeMillis() + (Spring.instant ? 0 : CLEAR_MS);
        Click c = Click.current();
        if (c != null) c.error();
    }

    /** Right: the dots pop and light up; it stays as it is until the host takes it away. */
    void success() {
        busy = true;
        flash.target = 1;
        for (int i = 0; i < length; i++) {
            pop[i].snap(1.3f);
            pop[i].target = 1;
        }
    }

    /** Back to empty lines, at once (a new step of a flow, or a wait). */
    void clear() {
        code.setLength(0);
        busy = false;
        clearAt = 0;
        for (int i = 0; i < REMOTE_LENGTH; i++) morph[i].target = 0;
    }

    /** One key. Arrows type (remote) or move along the row (digits); Back erases, then leaves; OK is the host's. */
    int key(KeyEvent e) {
        int keyCode = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;
        int digit = keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9 ? keyCode - KeyEvent.KEYCODE_0
                : keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9 ? keyCode - KeyEvent.KEYCODE_NUMPAD_0 : -1;
        if (digit >= 0) {
            if (!first || remote) return IGNORED;
            keyIndex = digit;
            return type((char) ('0' + digit));
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!first) return IGNORED;   // held: still one press
                if (remote) return type(symbol(keyCode));
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return UP;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return DOWN;
                return move(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down) {
                    keyPress.target = 0;
                    return IGNORED;
                }
                if (!first) return IGNORED;
                if (remote) return OK;
                keyPress.target = 1;
                return keyIndex == DELETE ? erase() : type((char) ('0' + keyIndex));
            case KeyEvent.KEYCODE_DEL:
                return first ? erase() : IGNORED;
            case KeyEvent.KEYCODE_BACK:
                if (down || e.isCanceled()) return IGNORED;
                return code.length() > 0 && !busy ? erase() : BACK;
            default:
                return IGNORED;
        }
    }

    /** The symbol an arrow types. */
    static char symbol(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return UP_KEY;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN_KEY;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT_KEY;
            default: return RIGHT_KEY;
        }
    }

    private int type(char ch) {
        if (busy || code.length() >= length) return IGNORED;
        int i = code.length();
        code.append(ch);
        morph[i].target = 1;
        pop[i].snap(1.45f);
        pop[i].target = 1;
        breath.impulse(9);   // the same swell whatever was pressed
        Click c = Click.current();
        if (c != null) c.move();
        return code.length() == length ? COMPLETE : TYPED;
    }

    private int erase() {
        if (busy || code.length() == 0) return IGNORED;
        int i = code.length() - 1;
        code.setLength(i);
        morph[i].target = 0;
        pop[i].snap(0.8f);
        pop[i].target = 1;
        return ERASED;
    }

    private int move(int dx) {
        int next = keyIndex + dx;
        if (next < 0 || next >= KEYS) {
            nudge.impulse(dx * Spring.BUMP_VELOCITY);
            Click.boundary();
            return MOVED;
        }
        keyIndex = next;
        Click c = Click.current();
        if (c != null) c.move();
        return MOVED;
    }

    /** One frame: true while anything still moves (or a shake or a clear is due). */
    boolean step(float dt, boolean focused) {
        long now = SystemClock.uptimeMillis();
        boolean moving = false;
        if (shakeStep < SHAKE.length && now >= nextShakeAt) {
            shake.impulse(SHAKE[shakeStep++] * Spring.BUMP_VELOCITY);
            nextShakeAt = now + SHAKE_MS;
        }
        if (shakeStep < SHAKE.length) moving = true;
        if (clearAt != 0) {
            if (now >= clearAt) clear();
            else moving = true;
        }
        moving |= shake.step(dt) | nudge.step(dt) | flash.step(dt) | breath.step(dt) | keyPress.step(dt);
        for (int i = 0; i < REMOTE_LENGTH; i++) moving |= morph[i].step(dt) | pop[i].step(dt);
        for (int i = 0; i < KEYS; i++) {
            keyFocus[i].target = !remote && focused && i == keyIndex ? 1 : 0;
            moving |= keyFocus[i].step(dt);
        }
        return moving;
    }

    /** How far the shake moves the pad now (px on screen), for text the host draws with it. */
    float shakeX(float k) {
        return shake.value * k;
    }

    /** The pad's height under its slots' centre: the digit row's, or none. */
    static float rowDrop(boolean remote, float k) {
        return remote ? 0 : 118 * k;
    }

    /**
     * The slots centred at (cx, cy), and for a PIN the 0–9 row under them. `ink` is the text colour over the glass,
     * `accent` the colour it lights up in when right, `night` the glass's (the keys' fill follows it).
     */
    void draw(Canvas c, float cx, float cy, float k, int ink, int accent, boolean night, float alpha) {
        if (alpha <= 0.004f) return;
        float dx = shake.value * k;
        int lit = blend(ink, accent, Ui.clamp(flash.value));
        float gap = (remote ? 46 : 60) * k, x0 = cx - gap * (length - 1) / 2f + dx;
        for (int i = 0; i < length; i++) {
            // A line (30 × 4) that draws itself into a dot (17), from its middle; filled as it lands.
            float t = Ui.clamp(morph[i].value), p = pop[i].value;
            float lw = (30 + (17 - 30) * t) * k * p, lh = (4 + (17 - 4) * t) * k * p;
            float x = x0 + i * gap, y = cy + (remote ? 9 * k * (1 - t) : 0);   // the line sits a little low, on a baseline
            r.set(x - lw / 2, y - lh / 2, x + lw / 2, y + lh / 2);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(blend(Ui.alpha(ink, 0.42f), lit, t));
            fill.setAlpha(Math.round((fill.getColor() >>> 24) * alpha));
            c.drawRoundRect(r, lh / 2, lh / 2, fill);
        }
        if (!remote) drawRow(c, cx, cy + rowDrop(false, k), k, ink, night, alpha);
    }

    /** The 0–9 row with delete: the focused key grows and turns white with dark ink (tvOS's focus). */
    private void drawRow(Canvas c, float cx, float ky, float k, int ink, boolean night, float alpha) {
        if (glyphK != k) {
            glyphK = k;
            keyText = Ui.numbers(Ui.semibold, 30 * k, 0xFFFFFFFF);
            keyText.setTextAlign(Paint.Align.CENTER);
            float g = 14 * k;   // a tag pointing left with an ×
            deleteGlyph.rewind();
            deleteGlyph.moveTo(-g * 1.3f, 0);
            deleteGlyph.lineTo(-g * 0.55f, -g * 0.8f);
            deleteGlyph.lineTo(g * 1.2f, -g * 0.8f);
            deleteGlyph.lineTo(g * 1.2f, g * 0.8f);
            deleteGlyph.lineTo(-g * 0.55f, g * 0.8f);
            deleteGlyph.close();
        }
        float key = 64 * k, step = key + 12 * k, kx0 = cx - step * (KEYS - 1) / 2, half = Ui.cap(keyText) / 2;
        float bump = Spring.rubber(nudge.value) * k;
        int focusInk = night ? 0xFF1C1C1E : 0xFFFFFFFF;
        for (int i = 0; i < KEYS; i++) {
            float f = Ui.clamp(keyFocus[i].value), x = kx0 + i * step + (i == keyIndex ? bump : 0);
            float rad = key / 2 * (1 + 0.18f * keyFocus[i].value - 0.06f * keyPress.value * f);
            fill.setColor(blend(night ? 0x26FFFFFF : 0x40FFFFFF, night ? 0xFFFFFFFF : 0xFF1C1C1E, f));
            fill.setAlpha(Math.round((fill.getColor() >>> 24) * alpha));
            c.drawCircle(x, ky, rad, fill);
            int keyInk = blend(ink, focusInk, f);
            if (i == DELETE) {
                fill.setColor(keyInk);
                fill.setAlpha(Math.round(255 * alpha));
                c.save();
                c.translate(x, ky);
                c.drawPath(deleteGlyph, fill);
                c.restore();
            } else {
                keyText.setColor(keyInk);
                keyText.setAlpha(Math.round(255 * alpha));
                c.drawText(DIGITS[i], x, ky + half, keyText);
            }
        }
    }

    private static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};

    /** a → b by t, channel by channel (alpha too). */
    static int blend(int a, int b, float t) {
        int aa = a >>> 24, ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
                | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }
}
