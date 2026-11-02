package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * The welcome setup ("Welcome to mahitTV"): on a new TV after the startup (before "Who's Watching?"), and from Settings →
 * System → Run Setup Again. Four steps in one Liquid Glass card over the Frosted Horizon (never a flat black): Welcome;
 * the look (Light, Dark or Automatic, and Liquid or Frosted glass, both shown at once as they're picked); the Top Shelf;
 * the profiles and the weather (°F or °C), then "You're All Set". ← → pick in a row, ↑ ↓ move between rows, OK goes on,
 * Back goes back. HomeView adds it like LockView, sends it keys while it shows and applies each choice (Listener).
 * Everything is measured and every string made when a step is built: a frame only moves springs.
 */
final class WelcomeView extends View implements Choreographer.FrameCallback {
    interface Listener {
        /** The theme or the glass changed: the home screen (and this view's Horizon) follow at once. */
        void onLook();
        /** "Go to Home Screen": `fromBoot` when it ran before the lock (a new TV). */
        void onDone(boolean fromBoot);
    }

    private static final int INK = 0xFF1C1C1E, WHITE = 0xFFFFFFFF, BLUE = 0xFF007AFF;
    private static final int STEPS = 4;
    /** Layout, in 1080p px. */
    private static final float CARD_L = 300, CARD_T = 120, CARD_R = 1620, CARD_B = 960, CARD_RADIUS = 48;
    private static final float OPTION_W = 232, OPTION_H = 168, OPTION_GAP = 28, SMALL_W = 150, SMALL_H = 84;
    private static final float ITEM_W = 1080, ITEM_H = 84, ITEM_GAP = 10, BUTTON_H = 76, BUTTON_BOTTOM = 56;
    private static final int OPTIONS = 0, LIST = 1, BUTTON = 2;

    /** A row of a step: options side by side, a list, or the button that goes on. */
    private static final class Row {
        int type;
        String head;
        String[] values, labels, descs;
        int[][] swatches;   // an option's picture: its gradient's colours, top to bottom (or left to right: `across`)
        boolean across;
        String value;
        java.util.function.Consumer<String> set;
        final List<RectF> rects = new ArrayList<>();
        final List<LinearGradient> shaders = new ArrayList<>();
        float headY;
    }

    private final Scene scene;
    private final Listener listener;
    private final Spring appear = new Spring(Spring.SHEET_OPEN, 0), slide = new Spring(Spring.TITLE_SETTLE, 0);
    private boolean posted, leaving, fromBoot, night;
    private long lastNanos;
    private float w, h, k = 1;
    private int step, row, col;
    private Bitmap horizon;
    private final RectF screen = new RectF(), card = new RectF(), tmp = new RectF(), button = new RectF();
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG), veil = new Paint(), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG), swatch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint markLight, markBold, titleP, lineP, headP, optionP, itemP, descP, buttonP, nameP, cityP;

    // The step shown, made in build(): its title and line, its rows, the profiles (last step).
    private String title = "", line = "", buttonText = "", city = "";
    private boolean hero;
    private final List<Row> rows = new ArrayList<>();
    private final List<Bitmap> faces = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private float titleY;

    WelcomeView(Activity a, Scene scene, Listener listener) {
        super(a);
        this.scene = scene;
        this.listener = listener;
        setFocusable(false);
        setVisibility(GONE);
        rim.setStyle(Paint.Style.STROKE);
    }

    boolean showing() {
        return getVisibility() == VISIBLE && !leaving;
    }

    /** From the first step; `fromBoot`: before the lock (a new TV), so "Who's Watching?" comes after it. */
    void show(boolean fromBoot) {
        this.fromBoot = fromBoot;
        leaving = false;
        step = 0;
        theme();
        build();
        appear.tune(Spring.SHEET_OPEN);
        appear.snap(0);
        appear.target = 1;
        slide.snap(0);
        setContentDescription(Ui.tr("Welcome to mahitTV"));
        setVisibility(VISIBLE);
        bringToFront();
        wake();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (rows.isEmpty()) return code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN;   // not laid out yet
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) sideways(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) vertical(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down) ok();
                return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                if (!down && !e.isCanceled()) go(step - 1);
                return true;
            default:
                return false;   // the volume and the rest go on to the system
        }
    }

    private void sideways(int dir) {
        Row r = rows.get(row);
        if (r.type != OPTIONS) {
            Click.boundary();
            return;
        }
        int next = Math.max(0, Math.min(r.values.length - 1, col + dir));
        if (next == col) {
            Click.boundary();
            return;
        }
        col = next;
        pick(r, col);
        click();
    }

    private void vertical(int dir) {
        Row r = rows.get(row);
        if (r.type == LIST && col + dir >= 0 && col + dir < r.values.length) {
            col += dir;
        } else if (row + dir >= 0 && row + dir < rows.size()) {
            row += dir;
            Row to = rows.get(row);
            col = to.type == LIST ? (dir > 0 ? 0 : to.values.length - 1) : current(to);
        } else {
            Click.boundary();
            return;
        }
        click();
        invalidate();
    }

    private void ok() {
        Row r = rows.get(row);
        if (r.type == BUTTON) {
            Click c = Click.current();
            if (c != null) c.open();
            go(step + 1);
        } else if (r.type == LIST) {
            pick(r, col);
            click();
        } else if (row + 1 < rows.size()) {
            row++;
            col = current(rows.get(row));
            click();
            invalidate();
        }
    }

    private static void click() {
        Click c = Click.current();
        if (c != null) c.move();
    }

    /** Picks option `i` of `r`: applied at once (the theme and the glass show on the whole home screen). */
    private void pick(Row r, int i) {
        if (r.values[i].equals(r.value)) return;
        r.value = r.values[i];
        r.set.accept(r.value);
        invalidate();
    }

    private static int current(Row r) {
        if (r.values == null) return 0;
        for (int i = 0; i < r.values.length; i++) if (r.values[i].equals(r.value)) return i;
        return 0;
    }

    private void go(int to) {
        if (to < 0) {
            Click.boundary();
            return;
        }
        if (to >= STEPS) {
            Prefs.setSetupDone(true);
            leaving = true;
            appear.tune(Spring.SHEET_CLOSE);
            appear.target = 0;
            wake();
            listener.onDone(fromBoot);
            return;
        }
        step = to;
        build();
        if (!Spring.instant) {
            slide.snap(1);
            slide.target = 0;
        }
        wake();
    }

    // MARK: - The steps (never while drawing)

    private void build() {
        rows.clear();
        faces.clear();
        names.clear();
        hero = false;
        if (w == 0) return;
        switch (step) {
            case 0:
                hero = true;
                title = Ui.tr("Welcome to mahitTV");
                line = Ui.tr("A home screen for your TV, made like Apple TV.");
                buttonText = Ui.tr("Get Started");
                break;
            case 1: {
                title = Ui.tr("Choose Your Look");
                line = Ui.tr("You can change these anytime in Settings.");
                Row theme = options(Ui.tr("Theme"), Prefs.theme(), v -> {
                    Prefs.setTheme(v);
                    listener.onLook();
                    theme();
                }, new String[] {"light", "dark", "auto"}, new String[] {Ui.tr("Light"), Ui.tr("Dark"), Ui.tr("Automatic")},
                        new int[][] {{0xFF8EC5F0, 0xFFDCEBF7, 0xFFF7E4CF}, {0xFF0B1220, 0xFF1C2638, 0xFF3B3F6B}, {0xFFA9D2F2, 0xFF1C2638}});
                theme.across = false;
                Row glass = options(Ui.tr("Glass"), Prefs.glass(), v -> {
                    Prefs.setGlass(v);
                    Scene.frosted = "frosted".equals(v);
                    listener.onLook();
                }, new String[] {"liquid", "frosted"}, new String[] {Ui.tr("Liquid Glass"), Ui.tr("Frosted Glass")},
                        new int[][] {{0xFF7DD3FC, 0xFFA78BFA, 0xFFFEF3C7}, {0xFFE6EAF0, 0xFFC9D1DC}});
                rows.add(theme);
                rows.add(glass);
                buttonText = Ui.tr("Continue");
                break;
            }
            case 2: {
                title = Ui.tr("Top Shelf");
                line = Ui.tr("What fills the top of the TV tab, above the Dock.");
                Row list = new Row();
                list.type = LIST;
                list.value = Prefs.topShelf();
                list.set = Prefs::setTopShelf;
                list.values = new String[] {"app", "picks", "all", "off"};
                list.labels = new String[list.values.length];
                for (int i = 0; i < list.values.length; i++) list.labels[i] = SettingsActivity.SettingsView.shelfModeName(list.values[i]);
                list.descs = new String[] {Ui.tr("The Frosted Horizon until you focus a streaming app, then what you were watching in it."),
                        Ui.tr("Picks and news from your apps, always on top."), Ui.tr("What you were watching, from any app."),
                        Ui.tr("Only your wallpaper.")};
                rows.add(list);
                buttonText = Ui.tr("Continue");
                break;
            }
            default: {
                title = Ui.tr("You're All Set");
                line = Ui.tr("Your profiles and your weather are ready.");
                for (ProfileManager.Profile p : ProfileManager.all(getContext())) {
                    faces.add(ProfileManager.avatar(getContext(), p, Math.round(104 * k)));
                    names.add(p.name);
                }
                city = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
                Row unit = options(Ui.tr("Temperature"), "C".equals(Prefs.temperature()) ? "C" : "F", v -> {
                    Prefs.setTemperature(v);
                    HomeData.get(getContext()).settingsChanged();
                }, new String[] {"F", "C"}, new String[] {"°F", "°C"}, null);
                rows.add(unit);
                buttonText = Ui.tr("Go to Home Screen");
                break;
            }
        }
        Row go = new Row();
        go.type = BUTTON;
        rows.add(go);
        row = 0;
        col = current(rows.get(0));
        layout();
        invalidate();
    }

    private static Row options(String head, String value, java.util.function.Consumer<String> set, String[] values, String[] labels, int[][] swatches) {
        Row r = new Row();
        r.type = OPTIONS;
        r.head = head.toUpperCase(Ui.locale());   // drawn as tvOS's group headers
        r.value = value;
        r.set = set;
        r.values = values;
        r.labels = labels;
        r.swatches = swatches;
        return r;
    }

    /** Where each row sits for this step (and its options' pictures). */
    private void layout() {
        float cx = card.centerX();
        titleY = (hero ? 510 : 270) * k;
        float y = hero ? 600 * k : 360 * k;
        if (step == 3) y = 620 * k;   // under the profiles and the city
        for (Row r : rows) {
            r.rects.clear();
            r.shaders.clear();
            if (r.type == BUTTON) continue;
            if (r.type == LIST) {
                y += 20 * k;
                for (int i = 0; i < r.values.length; i++) {
                    r.rects.add(new RectF(cx - ITEM_W / 2 * k, y, cx + ITEM_W / 2 * k, y + ITEM_H * k));
                    y += (ITEM_H + ITEM_GAP) * k;
                }
                continue;
            }
            r.headY = y + 20 * k;
            y += 34 * k;
            boolean small = r.swatches == null;
            float ow = (small ? SMALL_W : OPTION_W) * k, oh = (small ? SMALL_H : OPTION_H) * k, gap = OPTION_GAP * k;
            float total = r.values.length * ow + (r.values.length - 1) * gap, x = cx - total / 2;
            for (int i = 0; i < r.values.length; i++) {
                RectF o = new RectF(x, y, x + ow, y + oh);
                r.rects.add(o);
                if (!small) {
                    int[] colors = r.swatches[i];
                    boolean split = r.values[i].equals("auto");   // Automatic: half day, half night
                    float sl = o.left + 20 * k, st = o.top + 18 * k, sr = o.right - 20 * k, sb = st + 96 * k;
                    r.shaders.add(split
                            ? new LinearGradient(sl, 0, sr, 0, new int[] {colors[0], colors[0], colors[1], colors[1]}, new float[] {0, 0.5f, 0.5f, 1}, Shader.TileMode.CLAMP)
                            : new LinearGradient(0, st, 0, sb, colors, null, Shader.TileMode.CLAMP));
                }
                x += ow + gap;
            }
            y += oh + 20 * k;
        }
        float bw = Math.max(300 * k, buttonP.measureText(buttonText) + 104 * k);
        button.set(cx - bw / 2, card.bottom - (BUTTON_BOTTOM + BUTTON_H) * k, cx + bw / 2, card.bottom - BUTTON_BOTTOM * k);
    }

    /** The Horizon and the ink for the theme now (it changes live on the look step). */
    private void theme() {
        night = Wallpaper.dark(Prefs.wallpaper(), HomeData.get(getContext()).isNight(System.currentTimeMillis()));
        if (w == 0 || titleP == null) return;
        horizon = Horizon.get((int) w, (int) h, night);
        veil.setColor(night ? 0x24000000 : 0x1AFFFFFF);
        int ink = night ? WHITE : INK;
        for (Paint p : new Paint[] {markLight, markBold, titleP, optionP, itemP, buttonP, nameP, cityP}) p.setColor(ink);
        lineP.setColor(Ui.alpha(ink, 0.62f));
        headP.setColor(Ui.alpha(ink, 0.55f));
        descP.setColor(Ui.alpha(ink, 0.62f));
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        card.set(CARD_L * k, CARD_T * k, CARD_R * k, CARD_B * k);
        markLight = Ui.text(Ui.light, 120 * k, WHITE);
        markBold = Ui.text(Ui.displayBold, 120 * k, WHITE);
        markLight.setLetterSpacing(-0.03f);
        markBold.setLetterSpacing(-0.03f);
        titleP = Ui.text(Ui.displaySemibold, 52 * k, WHITE);
        titleP.setLetterSpacing(-0.02f);
        titleP.setTextAlign(Paint.Align.CENTER);
        lineP = Ui.text(Ui.regular, 26 * k, WHITE);
        lineP.setTextAlign(Paint.Align.CENTER);
        headP = Ui.text(Ui.semibold, 19 * k, WHITE);
        headP.setLetterSpacing(0.06f);
        headP.setTextAlign(Paint.Align.CENTER);
        optionP = Ui.text(Ui.semibold, 24 * k, WHITE);
        optionP.setTextAlign(Paint.Align.CENTER);
        itemP = Ui.text(Ui.semibold, 26 * k, WHITE);
        descP = Ui.text(Ui.regular, 19 * k, WHITE);
        buttonP = Ui.text(Ui.semibold, 28 * k, WHITE);
        buttonP.setTextAlign(Paint.Align.CENTER);
        nameP = Ui.text(Ui.semibold, 22 * k, WHITE);
        nameP.setTextAlign(Paint.Align.CENTER);
        cityP = Ui.text(Ui.semibold, 26 * k, WHITE);
        theme();
        if (getVisibility() == VISIBLE) build();
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
        boolean moving = appear.step(dt) | slide.step(dt);
        setAlpha(Ui.clamp(appear.value));
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

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (w == 0 || titleP == null) return;
        Horizon.draw(c, horizon, screen, image, 1);
        c.drawRect(screen, veil);
        float z = 1.03f - 0.03f * Ui.clamp(appear.value);
        c.save();
        c.scale(z, z, card.centerX(), card.centerY());
        Ui.plate(c, card, CARD_RADIUS * k, night, 1, fill, rim, k);
        // The step's dots.
        float cx = card.centerX();
        for (int i = 0; i < STEPS; i++) {
            fill.setColor(Ui.alpha(night ? WHITE : INK, i == step ? 0.85f : 0.22f));
            c.drawCircle(cx + (i - (STEPS - 1) / 2f) * 24 * k, card.top + 45 * k, 5 * k, fill);
        }
        // The content slides in from the right on a new step.
        float s = Ui.clamp(slide.value);
        int save = s > 0.004f ? c.saveLayerAlpha(card.left, card.top, card.right, card.bottom, Math.round(255 * (1 - s))) : -1;
        c.translate(48 * k * s, 0);
        if (hero) {
            float mw = markLight.measureText("mahit") + markBold.measureText("TV"), mx = cx - mw / 2;
            c.drawText("mahit", mx, 430 * k, markLight);
            c.drawText("TV", mx + markLight.measureText("mahit"), 430 * k, markBold);
            titleP.setTextSize(44 * k);
        } else {
            titleP.setTextSize(52 * k);
        }
        c.drawText(title, cx, titleY, titleP);
        c.drawText(line, cx, titleY + 46 * k, lineP);
        if (step == 3) drawPeople(c, cx);
        for (int ri = 0; ri < rows.size(); ri++) {
            Row r = rows.get(ri);
            if (r.type == OPTIONS) drawOptions(c, r, ri == row);
            else if (r.type == LIST) drawList(c, r, ri == row);
        }
        drawButton(c, rows.get(row).type == BUTTON);
        if (save >= 0) c.restoreToCount(save);
        c.restore();
    }

    private void drawPeople(Canvas c, float cx) {
        int n = faces.size();
        float pitch = 150 * k, d = 104 * k, x0 = cx - (n - 1) * pitch / 2, top = 360 * k;
        for (int i = 0; i < n; i++) {
            float x = x0 + i * pitch;
            Bitmap b = faces.get(i);
            if (b != null) c.drawBitmap(b, x - d / 2, top, image);
            c.drawText(names.get(i), x, top + d + 34 * k, nameP);
        }
        float cw = Symbols.width(getContext(), "sunny", 30 * k) + 10 * k + cityP.measureText(city);
        Symbols.draw(getContext(), c, "sunny", cx - cw / 2 + 15 * k, 568 * k - Ui.halfCap(cityP), 30 * k, cityP.getColor());
        c.drawText(city, cx - cw / 2 + 40 * k, 568 * k, cityP);
    }

    private void drawOptions(Canvas c, Row r, boolean rowFocused) {
        if (r.head != null) c.drawText(r.head, card.centerX(), r.headY, headP);
        for (int i = 0; i < r.rects.size(); i++) {
            RectF o = r.rects.get(i);
            boolean focused = rowFocused && i == col, picked = r.values[i].equals(r.value);
            float rr = (r.swatches == null ? 22 : 26) * k, grow = focused ? 1.06f : 1;
            c.save();
            c.scale(grow, grow, o.centerX(), o.centerY());
            fill.setShader(null);
            fill.setColor(focused ? WHITE : Ui.alpha(WHITE, night ? 0.1f : 0.4f));
            c.drawRoundRect(o, rr, rr, fill);
            if (picked) {
                rim.setColor(BLUE);
                rim.setStrokeWidth(3 * k);
                tmp.set(o.left + 1.5f * k, o.top + 1.5f * k, o.right - 1.5f * k, o.bottom - 1.5f * k);
                c.drawRoundRect(tmp, rr, rr, rim);
            }
            if (i < r.shaders.size()) {
                swatch.setShader(r.shaders.get(i));
                tmp.set(o.left + 20 * k, o.top + 18 * k, o.right - 20 * k, o.top + 114 * k);
                c.drawRoundRect(tmp, 16 * k, 16 * k, swatch);
                swatch.setShader(null);
            }
            int ink = focused ? INK : (night ? WHITE : INK);
            optionP.setColor(ink);
            optionP.setTextSize((r.swatches == null ? 30 : 24) * k);
            float ty = r.swatches == null ? o.centerY() + Ui.halfCap(optionP) : o.bottom - 18 * k;
            c.drawText(r.labels[i], o.centerX(), ty, optionP);
            c.restore();
        }
    }

    private void drawList(Canvas c, Row r, boolean rowFocused) {
        for (int i = 0; i < r.rects.size(); i++) {
            RectF o = r.rects.get(i);
            boolean focused = rowFocused && i == col;
            float grow = focused ? 1.02f : 1;
            c.save();
            c.scale(grow, grow, o.centerX(), o.centerY());
            fill.setShader(null);
            fill.setColor(focused ? WHITE : Ui.alpha(WHITE, night ? 0.1f : 0.4f));
            c.drawRoundRect(o, 22 * k, 22 * k, fill);
            int ink = focused ? INK : (night ? WHITE : INK);
            itemP.setColor(ink);
            descP.setColor(Ui.alpha(ink, 0.62f));
            c.drawText(r.labels[i], o.left + 28 * k, o.top + 38 * k, itemP);
            c.drawText(r.descs[i], o.left + 28 * k, o.top + 66 * k, descP);
            if (r.values[i].equals(r.value)) Symbols.draw(getContext(), c, "check", o.right - 40 * k, o.centerY(), 28 * k, BLUE);
            c.restore();
        }
    }

    private void drawButton(Canvas c, boolean focused) {
        float grow = focused ? 1.06f : 1, rr = button.height() / 2;
        c.save();
        c.scale(grow, grow, button.centerX(), button.centerY());
        fill.setShader(null);
        fill.setColor(focused ? WHITE : Ui.alpha(WHITE, night ? 0.12f : 0.5f));
        c.drawRoundRect(button, rr, rr, fill);
        buttonP.setColor(focused ? INK : (night ? WHITE : INK));
        c.drawText(buttonText, button.centerX(), button.centerY() + Ui.halfCap(buttonP), buttonP);
        c.restore();
    }
}
