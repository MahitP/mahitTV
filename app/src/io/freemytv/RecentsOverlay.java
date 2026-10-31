package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recent apps (TVOS-LAUNCHER-MERGE-SPEC.md S3): a carousel of the current profile's last apps, from mahitTV's own
 * launch history (WatchLog), over the Frosted Horizon. The focused card grows 1.18× (SNAPPY_FOCUS) with its name; ← →
 * move (a bump at the ends); OK opens it; Back closes. Nothing is closed or force-stopped. Guest has no history, so an
 * empty state. HomeView adds it like LockView and sends it keys while it shows.
 * The cards are made off the UI thread when it opens; a frame only moves springs.
 * The switcher idea is adapted from tvOS-Launcher (Apache-2.0).
 */
final class RecentsOverlay extends View implements Choreographer.FrameCallback {
    interface Listener {
        /** OK on a card: open this app (HomeView calls HomeApps.open, so the launch is logged as usual). */
        void open(HomeApps.Item item);
    }

    private static final int MAX = 12, INK = 0xFF1C1C1E, WHITE = 0xFFFFFFFF;
    private static final int VEIL_DAY = 0x1AFFFFFF, VEIL_NIGHT = 0x24000000;
    /** Cards, in 1080p px: 5:3 like the Dock's, centred on the focused one. */
    private static final float CARD_W = 400, CARD_H = 240, GAP = 64, GROW = 0.18f, ROW_Y = 560, TITLE_Y = 250;

    private final Listener listener;
    private final Scene scene;
    private final Spring appear = new Spring(Spring.SHEET_OPEN, 0);
    private final Spring scroll = new Spring(Spring.SPATIAL_SCROLL, 0).px(), nudge = new Spring(Spring.BOUNDARY_BUMP, 0).px();
    private Spring[] focus = new Spring[0];
    private List<HomeApps.Item> items = new ArrayList<>();
    private Bitmap[] cards = new Bitmap[0];
    private BitmapShader[] shaders = new BitmapShader[0];
    private String[] names = new String[0];
    private boolean night = true, posted, closing, loading;
    private int index, generation;
    private long lastNanos;
    private float w, h, k = 1;
    private Bitmap horizon;
    private String title = "", empty = "";
    private final RectF screen = new RectF(), tmp = new RectF();
    private final Path squircle = new Path();
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG), veil = new Paint();
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint titleP, nameP, emptyP;

    RecentsOverlay(Activity a, Scene scene, Listener listener) {
        super(a);
        this.listener = listener;
        this.scene = scene;
        setFocusable(false);
        setVisibility(GONE);
    }

    /** The current profile's recent apps, newest first, that are still installed (at most 12). Off the UI thread. */
    static List<HomeApps.Item> recents(Context c) {
        List<HomeApps.Item> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        Set<String> seen = new HashSet<>();
        for (WatchLog.Entry e : WatchLog.list(c)) {
            if (out.size() >= MAX) break;
            String pkg = e.pkg;
            if (pkg == null || pkg.isEmpty() || pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            if (HomeApps.launchIntent(pm, pkg) == null) continue;   // uninstalled or turned off since
            String label = e.app;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            out.add(HomeApps.app(pkg, label == null || label.isEmpty() ? pkg : label));
        }
        return out;
    }

    void show() {
        HomeData data = HomeData.get(getContext());
        night = Wallpaper.dark(Prefs.wallpaper(), data.isNight(System.currentTimeMillis()));
        closing = false;
        index = 0;
        items = new ArrayList<>();
        cards = new Bitmap[0];
        shaders = new BitmapShader[0];
        names = new String[0];
        focus = new Spring[0];
        scroll.snap(0);
        nudge.snap(0);
        appear.tune(Spring.SHEET_OPEN);
        appear.snap(0);
        appear.target = 1;
        theme();
        title = Ui.tr("Recent Apps");
        empty = Ui.tr("No recent apps yet.");
        setContentDescription(title);
        setVisibility(VISIBLE);
        bringToFront();
        apply();
        load();
        wake();
    }

    void hide() {
        closing = false;
        setVisibility(GONE);
    }

    /** Taking keys: true from show() until it leaves. */
    boolean showing() {
        return getVisibility() == VISIBLE && !closing;
    }

    /** ← → move, OK opens, Back closes; it takes every key while it shows. */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (!down) dismiss();
            return true;
        }
        if (!down || e.getRepeatCount() > 0 && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) move(1);
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT) move(-1);
        else if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) Click.boundary();
        else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) choose();
        return true;
    }

    private void move(int dir) {
        int next = index + dir;
        if (next < 0 || next >= items.size()) {
            nudge.impulse(dir * Spring.BUMP_VELOCITY);
            Click.boundary();
            wake();
            return;
        }
        index = next;
        scroll.target = index * (CARD_W + GAP) * k;
        Click c = Click.current();
        if (c != null) c.move();
        wake();
    }

    private void choose() {
        if (index >= items.size()) {
            dismiss();
            return;
        }
        Click c = Click.current();
        if (c != null) c.open();
        HomeApps.Item it = items.get(index);
        hide();
        listener.open(it);
    }

    private void dismiss() {
        if (closing || getVisibility() != VISIBLE) return;
        closing = true;
        appear.tune(Spring.SHEET_CLOSE);
        appear.target = 0;
        wake();
    }

    // MARK: - The cards, made in the background

    private void load() {
        if (w == 0) return;
        final int gen = ++generation;
        final Context c = getContext().getApplicationContext();
        final int cw = Math.round(CARD_W * k), ch = Math.round(CARD_H * k);
        loading = true;
        new Thread(() -> {
            List<HomeApps.Item> list = recents(c);
            Bitmap[] made = new Bitmap[list.size()];
            for (int i = 0; i < list.size(); i++) made[i] = cardFor(c, list.get(i), cw, ch);
            post(() -> {
                if (gen != generation) return;
                loading = false;
                items = list;
                cards = made;
                shaders = new BitmapShader[made.length];
                names = new String[made.length];
                focus = new Spring[made.length];
                TextPaint tp = new TextPaint(nameP);
                for (int i = 0; i < made.length; i++) {
                    shaders[i] = made[i] == null ? null : new BitmapShader(made[i], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    names[i] = TextUtils.ellipsize(list.get(i).label, tp, (CARD_W + GAP - 24) * k, TextUtils.TruncateAt.END).toString();
                    focus[i] = new Spring(Spring.SNAPPY_FOCUS, i == index ? 1 : 0);
                }
                invalidate();
                wake();
            });
        }, "recents").start();
    }

    /** A 5:3 card: the app's banner, else its composed logo on its colour, else its icon on graphite. */
    private static Bitmap cardFor(Context c, HomeApps.Item it, int cw, int ch) {
        try {
            HomeApps.compose(c, it, true);
            Bitmap b = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            if (it.tile != null) {
                fill(cv, it.tile, cw, ch, p);
                return b;
            }
            int top = it.style != null && it.style.length >= 2 ? it.style[0] : 0xFF3A3A3C;
            int bottom = it.style != null && it.style.length >= 2 ? it.style[1] : 0xFF1C1C1E;
            p.setShader(new LinearGradient(0, 0, 0, ch, top, bottom, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, cw, ch, p);
            p.setShader(null);
            Bitmap logo = it.logo;
            if (logo != null) {
                float s = Math.min(cw * 0.62f / logo.getWidth(), ch * 0.46f / logo.getHeight()) * Math.max(0.5f, it.logoScale);
                float lw = logo.getWidth() * s, lh = logo.getHeight() * s;
                cv.drawBitmap(logo, null, new RectF((cw - lw) / 2, (ch - lh) / 2, (cw + lw) / 2, (ch + lh) / 2), p);
                return b;
            }
            PackageManager pm = c.getPackageManager();
            Drawable d = pm.getApplicationBanner(it.pkg);
            if (d != null) {
                d.setBounds(0, 0, cw, ch);
                d.draw(cv);
                return b;
            }
            d = pm.getApplicationIcon(it.pkg);
            int s = Math.round(ch * 0.5f);
            d.setBounds((cw - s) / 2, (ch - s) / 2, (cw + s) / 2, (ch + s) / 2);
            d.draw(cv);
            return b;
        } catch (Throwable e) {
            AppLog.w("recents card: " + e.getMessage());
            return null;
        }
    }

    /** Draws `src` to cover the card (centre crop). */
    private static void fill(Canvas cv, Bitmap src, int cw, int ch, Paint p) {
        float s = Math.max(cw / (float) src.getWidth(), ch / (float) src.getHeight());
        float sw = src.getWidth() * s, sh = src.getHeight() * s;
        cv.drawBitmap(src, null, new RectF((cw - sw) / 2, (ch - sh) / 2, (cw + sw) / 2, (ch + sh) / 2), p);
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
        boolean moving = appear.step(dt) | scroll.step(dt) | nudge.step(dt);
        for (int i = 0; i < focus.length; i++) {
            focus[i].target = i == index ? 1 : 0;
            moving |= focus[i].step(dt);
        }
        apply();
        invalidate();
        if (closing && appear.target == 0 && (appear.settled() || appear.value <= 0.004f)) {
            closing = false;
            setVisibility(GONE);
            return;
        }
        if (moving) {
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private void apply() {
        float v = Ui.clamp(appear.value), z = 1.04f - 0.04f * v;
        setScaleX(z);
        setScaleY(z);
        setAlpha(v);
    }

    // MARK: - Layout (never while drawing)

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        w = width;
        h = height;
        k = w / 1920f;
        setPivotX(w / 2);
        setPivotY(h / 2);
        screen.set(0, 0, w, h);
        titleP = Ui.text(Ui.displaySemibold, 57 * k, WHITE);
        titleP.setTextAlign(Paint.Align.CENTER);
        nameP = Ui.text(Ui.semibold, 30 * k, WHITE);
        nameP.setTextAlign(Paint.Align.CENTER);
        emptyP = Ui.text(Ui.medium, 32 * k, WHITE);
        emptyP.setTextAlign(Paint.Align.CENTER);
        squircle.rewind();
        Corners.continuous(squircle, CARD_W * k, CARD_H * k, 34 * k);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(5 * k);
        ring.setColor(0xF2FFFFFF);
        shade.setColor(0x33000000);
        theme();
        if (getVisibility() == VISIBLE) load();
    }

    private void theme() {
        if (w == 0 || titleP == null) return;
        horizon = Horizon.get((int) w, (int) h, night);
        int ink = night ? WHITE : INK;
        titleP.setColor(ink);
        nameP.setColor(ink);
        emptyP.setColor(Ui.alpha(ink, 0.7f));
        veil.setColor(night ? VEIL_NIGHT : VEIL_DAY);
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (w == 0 || titleP == null) return;
        Horizon.draw(c, horizon, screen, image, 1);
        c.drawRect(screen, veil);
        c.drawText(title, w / 2, TITLE_Y * k, titleP);
        if (items.isEmpty()) {
            if (!loading) c.drawText(empty, w / 2, ROW_Y * k, emptyP);
            return;
        }
        float cw = CARD_W * k, ch = CARD_H * k, step = (CARD_W + GAP) * k;
        float x0 = w / 2 - scroll.value - Spring.rubber(nudge.value) * k;
        for (int i = 0; i < items.size(); i++) {
            float cx = x0 + i * step;
            if (cx < -cw || cx > w + cw) continue;
            float f = i < focus.length ? Ui.clamp(focus[i].value) : 0, z = 1 + GROW * (i < focus.length ? focus[i].value : 0);
            float cy = ROW_Y * k;
            // The glass under each card, a soft lift for the focused one.
            tmp.set(cx - cw / 2 * z - 10 * k, cy - ch / 2 * z - 10 * k, cx + cw / 2 * z + 10 * k, cy + ch / 2 * z + 10 * k);
            scene.glass(c, tmp, 44 * k * z, night ? 0xFF1C1C22 : WHITE, night ? 0.36f : 0.45f, WHITE, 0.05f + 0.1f * f, 0.04f, 1, 0.55f + 0.45f * f);
            c.save();
            c.translate(cx - cw / 2 * z, cy - ch / 2 * z);
            c.scale(z, z);
            if (shaders[i] != null) {
                card.setShader(shaders[i]);
                c.drawPath(squircle, card);
                card.setShader(null);
            } else {
                c.drawPath(squircle, shade);
            }
            if (f > 0.01f) {
                ring.setAlpha(Math.round(242 * f));
                c.drawPath(squircle, ring);
            }
            c.restore();
            nameP.setAlpha(Math.round(255 * (0.45f + 0.55f * f)));
            c.drawText(names[i], cx, cy + ch / 2 * z + 58 * k, nameP);
        }
        nameP.setAlpha(255);
    }
}
