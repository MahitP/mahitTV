package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

/**
 * App Lock's passcode sheet (DESIGN-PASSCODE.md): a Liquid Glass card over the home screen, not a page. The app's card
 * with a lock on it, "Enter Passcode", "{app} is locked.", the passcode's six lines (PasscodePad), how to type it, and
 * a blue "Forgot Passcode?" that OK opens. Forgot: the App Passcode is reset with the profile's passcode (then a new
 * one, twice); the profile's own is let through with the manager's. Right: the dots light up, the card swells and fades
 * as the app opens behind it. Wrong: the head-shake and the knock. Back erases, then closes.
 * Measured on show and on a size change; frames only move springs (no allocation in doFrame or onDraw).
 */
final class AppLockView extends View implements Choreographer.FrameCallback {
    interface Listener {
        /** The code was right: open `it` (AppLock.pass is done). */
        void onUnlocked(HomeApps.Item it);

        /** The sheet is gone (opened or not). */
        void onGone();
    }

    private static final int VERIFY = 0, RECOVER = 1, NEW = 2, CONFIRM = 3;
    private static final float CARD_W = 700, RADIUS = 46, TILE_W = 176, TILE_H = 99;
    private static final int INK = 0xFF1C1C1E, WHITE = 0xFFFFFFFF, GREEN = 0xFF30D158;
    private static final long OPEN_DELAY_MS = 240;

    private final Activity activity;
    private final Listener listener;
    private final PasscodePad pad = new PasscodePad();
    private final Spring appear = new Spring(Spring.SHEET_OPEN, 0), linkFocus = new Spring(Spring.SNAPPY_FOCUS, 0);
    private final Spring linkPress = new Spring(Spring.MICRO_PRESS, 0);
    private HomeApps.Item item;
    private ProfileManager.Profile recoverer;
    private int mode;
    private boolean night = true, posted, leaving, opened, onLink;
    private long lastNanos;
    private String first = "", title = "", subtitle = "", message = "", hint = "", link = "";
    private boolean warn, waitShown;

    private float w, h, k = 1, cardH, slotsY, hintY, linkY, titleY, subY, tileY;
    private final RectF card = new RectF(), tileRect = new RectF(), linkRect = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint(), tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG), linkFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix tileMatrix = new Matrix();
    private Paint titlePaint, subPaint, hintPaint, linkPaint;

    AppLockView(Activity a, Listener listener) {
        super(a);
        activity = a;
        this.listener = listener;
        setFocusable(false);
        setVisibility(GONE);
    }

    boolean showing() {
        return getVisibility() == VISIBLE;
    }

    /** Asks for `it`'s code. */
    void show(HomeApps.Item it) {
        item = it;
        night = Wallpaper.dark(Prefs.wallpaper(), HomeData.get(getContext()).isNight(System.currentTimeMillis()));
        leaving = opened = false;
        removeCallbacks(openNow);
        removeCallbacks(waitTick);
        appear.tune(Spring.SHEET_OPEN);
        appear.snap(0);
        appear.target = 1;
        recoverer = AppLock.recoverer(getContext());
        setVisibility(VISIBLE);
        bringToFront();
        start(VERIFY);
        Click c = Click.current();
        if (c != null) c.open();
        announceForAccessibility(title + ". " + subtitle);
        wake();
    }

    /** A step of the flow: what it checks or sets, its words, and the pad for it. */
    private void start(int step) {
        android.content.Context c = getContext();
        mode = step;
        onLink = false;
        linkFocus.snap(0);
        message = "";
        warn = waitShown = false;
        String app = item.label != null ? item.label : "";
        switch (step) {
            case VERIFY:
                title = Ui.tr("Enter Passcode");
                subtitle = Ui.trf("{app} is locked.", "app", app);
                pad.start(AppLock.remoteCode(c), AppLock.codeLength(c));
                break;
            case RECOVER:
                title = Ui.trf("Enter {name}'s Passcode", "name", recoverer.name);
                subtitle = AppLock.usesProfile(c) ? Ui.trf("{app} is locked.", "app", app) : Ui.tr("To reset the App Passcode");
                pad.start(ProfileManager.remotePin(c, recoverer), ProfileManager.pinLength(c, recoverer));
                break;
            case NEW:
                title = Ui.tr("Enter a New App Passcode");
                subtitle = Ui.trf("{app} is locked.", "app", app);
                pad.start(true, PasscodePad.REMOTE_LENGTH);
                break;
            default:
                title = Ui.tr("Enter the New Passcode Again");
                subtitle = Ui.trf("{app} is locked.", "app", app);
                pad.start(true, PasscodePad.REMOTE_LENGTH);
        }
        hint = pad.remote() ? Ui.tr("Use ↑ ↓ ← → on your remote") : "";
        link = step == VERIFY && recoverer != null ? Ui.tr("Forgot Passcode?") : "";
        waitTick.run();
        measure();
        invalidate();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        if (leaving) return true;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;
        // On a PIN's row, ↓ goes to "Forgot Passcode?" and ↑ back; OK there opens it.
        if (onLink) {
            if (code == KeyEvent.KEYCODE_DPAD_UP && first) {
                onLink = false;
                Click c = Click.current();
                if (c != null) c.move();
            } else if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && first) {
                forgot();
            } else if (code == KeyEvent.KEYCODE_BACK && !down && !e.isCanceled()) {
                close();
            }
            wake();
            return isOurs(e);
        }
        int what = pad.key(e);
        switch (what) {
            case PasscodePad.COMPLETE:
                postDelayed(check, Spring.instant ? 0 : 140);   // the last dot lands first
                break;
            case PasscodePad.BACK:
                close();
                break;
            case PasscodePad.OK:
                if (!link.isEmpty()) forgot();
                break;
            case PasscodePad.DOWN:
                if (!link.isEmpty()) {
                    onLink = true;
                    Click c = Click.current();
                    if (c != null) c.move();
                } else {
                    Click.boundary();
                }
                break;
            case PasscodePad.TYPED:
                if (!message.isEmpty() && !warnWaiting()) message = "";
                break;
            default:
                break;
        }
        wake();
        return isOurs(e);
    }

    /** Everything is the sheet's but the system's own keys (volume, power, media). */
    private static boolean isOurs(KeyEvent e) {
        return !e.isSystem() || e.getKeyCode() == KeyEvent.KEYCODE_BACK;
    }

    private boolean warnWaiting() {
        return mode != NEW && mode != CONFIRM && waitLeft() > 0;
    }

    private long waitLeft() {
        return mode == RECOVER ? ProfileManager.waitLeft(recoverer) : AppLock.waitLeft(getContext());
    }

    private final Runnable check = this::checkNow;

    private void checkNow() {
        if (!showing() || leaving) return;
        android.content.Context c = getContext();
        String code = pad.code();
        switch (mode) {
            case VERIFY:
                if (AppLock.check(c, code)) right(); else wrong(Ui.tr("Wrong passcode. Try again."));
                break;
            case RECOVER:
                if (!ProfileManager.checkPin(c, recoverer, code)) {
                    wrong(Ui.tr("Wrong passcode. Try again."));
                } else if (AppLock.usesProfile(c)) {
                    right();   // the manager lets it through
                } else {
                    start(NEW);
                }
                break;
            case NEW:
                first = code;
                start(CONFIRM);
                break;
            default:
                if (code.equals(first)) {
                    AppLock.setAppCode(c, code);
                    AppLock.profileChanged(c);
                    right();
                } else {
                    start(NEW);
                    wrong(Ui.tr("The passcodes didn't match. Try again."));
                }
        }
        invalidate();
    }

    private void wrong(String why) {
        long wait = warnWaiting() ? waitLeft() : 0;
        message = wait > 0 ? Ui.trf("Try again in {n} s", "n", (wait + 999) / 1000) : why;
        warn = true;
        waitShown = wait > 0;
        pad.fail();
        announceForAccessibility(message);
        waitTick.run();
        wake();
    }

    /** Right: the dots light up, then the app opens as the card swells and fades into it. */
    private void right() {
        pad.success();
        AppLock.pass(item.pkg);
        postDelayed(openNow, Spring.instant ? 0 : OPEN_DELAY_MS);
        wake();
    }

    private final Runnable openNow = this::openApp;

    private void openApp() {
        if (!showing()) return;
        opened = true;
        leaving = true;
        appear.tune(Spring.SHEET_CLOSE);
        appear.target = 0;
        listener.onUnlocked(item);
        wake();
    }

    private void forgot() {
        if (recoverer == null) return;
        linkPress.snap(1);
        linkPress.target = 0;
        Click c = Click.current();
        if (c != null) c.open();
        start(RECOVER);
        announceForAccessibility(title + ". " + subtitle);
        wake();
    }

    /** Gone without opening. */
    void close() {
        if (!showing() || leaving) return;
        leaving = true;
        removeCallbacks(check);
        removeCallbacks(waitTick);
        appear.tune(Spring.SHEET_CLOSE);
        appear.target = 0;
        Click c = Click.current();
        if (c != null) c.move();
        wake();
    }

    /** "Try again in {n} s" while it waits after too many wrong codes, every second. */
    private final Runnable waitTick = new Runnable() {
        @Override public void run() {
            removeCallbacks(this);
            if (!showing()) return;
            long left = warnWaiting() ? waitLeft() : 0;
            if (left > 0) {
                message = Ui.trf("Try again in {n} s", "n", (left + 999) / 1000);
                warn = waitShown = true;
                postDelayed(this, 1000);
            } else if (waitShown) {
                message = "";
                waitShown = false;
            }
            invalidate();
        }
    };

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
        linkFocus.target = onLink ? 1 : 0;
        boolean moving = appear.step(dt) | pad.step(dt, !onLink) | linkFocus.step(dt) | linkPress.step(dt);
        invalidate();
        if (leaving && appear.settled() && appear.target == 0) {
            setVisibility(GONE);
            listener.onGone();
            return;
        }
        if (moving) {
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    // MARK: - Layout (never while drawing)

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        w = width;
        h = height;
        k = w / 1920f;
        titlePaint = Ui.text(Ui.displaySemibold, 40 * k, WHITE);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        subPaint = Ui.text(Ui.regular, 25 * k, WHITE);
        subPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint = Ui.text(Ui.regular, 22 * k, WHITE);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        linkPaint = Ui.text(Ui.medium, 26 * k, WHITE);
        linkPaint.setTextAlign(Paint.Align.CENTER);
        Symbols.warm(getContext(), "lock", 20 * k);
        Symbols.warm(getContext(), "lock", 40 * k);
        measure();
    }

    /** The card's height for this step (a PIN's row makes it taller), and where each line sits in it. */
    private void measure() {
        if (titlePaint == null) return;
        boolean remote = pad.remote();
        float y = 44 * k;
        tileY = y;
        y += TILE_H * k + 62 * k;
        titleY = y;
        y += 44 * k;
        subY = y;
        y += 74 * k;
        slotsY = y;
        y += PasscodePad.rowDrop(remote, k) + (remote ? 62 : 70) * k;
        hintY = y;
        y += link.isEmpty() ? 0 : 60 * k;
        linkY = y;
        cardH = y + 44 * k;
        card.set(w / 2 - CARD_W * k / 2, h / 2 - cardH / 2, w / 2 + CARD_W * k / 2, h / 2 + cardH / 2);
        float lw = linkPaint.measureText(link) + 44 * k;
        linkRect.set(w / 2 - lw / 2, card.top + linkY - 34 * k, w / 2 + lw / 2, card.top + linkY + 14 * k);
        tileRect.set(w / 2 - TILE_W * k / 2, card.top + tileY, w / 2 + TILE_W * k / 2, card.top + tileY + TILE_H * k);
        Bitmap b = item != null ? item.tile : null;
        if (b != null) {
            BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(tileRect.width() / b.getWidth(), tileRect.height() / b.getHeight());
            tileMatrix.setScale(scale, scale);
            tileMatrix.postTranslate(tileRect.centerX() - b.getWidth() * scale / 2, tileRect.centerY() - b.getHeight() * scale / 2);
            s.setLocalMatrix(tileMatrix);
            tilePaint.setShader(s);
        } else {
            tilePaint.setShader(null);
        }
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (titlePaint == null || item == null) return;
        float v = Ui.clamp(appear.value);
        scrim.setColor(Ui.alpha(0xFF000000, 0.5f * v));
        c.drawRect(0, 0, w, h, scrim);
        // In: from 0.92× and 30 px low. Out: opened, it swells into the app; closed, it sinks back.
        float scale = leaving && opened ? 1 + 0.06f * (1 - appear.value) : 0.92f + 0.08f * appear.value;
        scale *= 1 + 0.004f * pad.breath.value;
        float lift = leaving && opened ? 0 : 30 * k * (1 - appear.value);
        c.save();
        c.translate(0, lift);
        c.scale(scale, scale, w / 2, h / 2);
        int ink = night ? WHITE : INK;
        Ui.plate(c, card, RADIUS * k, night, v, fill, rim, k);

        // The app's card with a lock on its corner (else a lock in a glass circle).
        if (tilePaint.getShader() != null) {
            tilePaint.setAlpha(Math.round(255 * v));
            c.drawRoundRect(tileRect, 16 * k, 16 * k, tilePaint);
            float bx = tileRect.right - 6 * k, by = tileRect.bottom - 6 * k, br = 22 * k;
            badge.setColor(Ui.alpha(night ? 0xFF2C2C2E : 0xFFF2F2F7, v));
            c.drawCircle(bx, by, br, badge);
            Symbols.draw(getContext(), c, "lock", bx, by, 20 * k, Ui.alpha(ink, v));
        } else {
            badge.setColor(Ui.alpha(night ? 0x33FFFFFF : 0x22000000, v));
            c.drawCircle(w / 2, tileRect.centerY(), 46 * k, badge);
            Symbols.draw(getContext(), c, "lock", w / 2, tileRect.centerY(), 40 * k, Ui.alpha(ink, v));
        }

        float dx = pad.shakeX(k);
        titlePaint.setColor(Ui.alpha(ink, v));
        c.drawText(title, w / 2 + dx, card.top + titleY, titlePaint);
        subPaint.setColor(Ui.alpha(ink, 0.72f * v));
        c.drawText(subtitle, w / 2 + dx, card.top + subY, subPaint);
        pad.draw(c, w / 2, card.top + slotsY, k, ink, GREEN, night, v);

        // How to type it, or why it went wrong (in red), in the same place.
        boolean showMessage = !message.isEmpty();
        hintPaint.setColor(showMessage && warn ? Ui.alpha(night ? 0xFFFF6961 : 0xFFD70015, v) : Ui.alpha(ink, 0.55f * v));
        String line = showMessage ? message : hint;
        if (!line.isEmpty()) c.drawText(line, w / 2, card.top + hintY, hintPaint);

        // "Forgot Passcode?": system blue; on a PIN's row it takes the focus (↓) as a soft capsule.
        if (!link.isEmpty()) {
            float f = Ui.clamp(linkFocus.value);
            if (f > 0.01f) {
                linkFill.setColor(Ui.alpha(night ? 0xFFFFFFFF : 0xFF1C1C1E, 0.14f * f * v));
                c.drawRoundRect(linkRect, linkRect.height() / 2, linkRect.height() / 2, linkFill);
            }
            float press = 1 - 0.05f * linkPress.value;
            c.save();
            c.scale(press, press, linkRect.centerX(), linkRect.centerY());
            linkPaint.setColor(Ui.alpha(night ? 0xFF0A84FF : 0xFF007AFF, v));
            c.drawText(link, w / 2, card.top + linkY, linkPaint);
            c.restore();
        }
        c.restore();
    }
}
