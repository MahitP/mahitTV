package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.net.URL;
import java.io.InputStream;

/**
 * mahitTV Settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's settings:
 * the big symbol and title on the left, the list on the right with the focused row in white. The main list is the
 * seven sections of MAHITTV-PROFILES-SPEC.md §6; each opens its rows. "Home Screen" (System & Original TV) opens the
 * mahitTV / Original choice (with a check on the current one); the switch runs in the main process (HomeMode) and this
 * screen only asks for it and follows the state file. Original, and both resets, ask for the manager's PIN first.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    /** Opens straight on the Sleep Timer (the home screen's Control Center). */
    static final String EXTRA_SLEEP = "sleep";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (openFireHome(getIntent())) return;
        Watchdog.install(this);
        Ui.init(this);
        SleepTimer.listen(this);   // :home may start here, from the launcher icon
        SleepTimer.check(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    /**
     * Fire TV: Amazon keeps the Home button, so the app's icon opens the home screen (FireHomeActivity) directly;
     * the gear on the home screen opens these settings (no launcher category).
     */
    private boolean openFireHome(Intent intent) {
        if (!HomeMode.AMAZON || intent == null || !(intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                || intent.hasCategory(Intent.CATEGORY_LAUNCHER)) || intent.getBooleanExtra("screensaver", false)) {
            return false;
        }
        startActivity(new Intent(this, FireHomeActivity.class));
        finish();
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (view == null) return;
        view.resume();
        if (getIntent().getBooleanExtra(EXTRA_SLEEP, false)) {
            getIntent().removeExtra(EXTRA_SLEEP);
            view.openSleepTimer();
        }
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openFireHome(intent)) return;
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        if (view != null) view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system); with Watch from the
            // screensaver on, OK opens the title first.
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean ok = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                        if (ok && Prefs.saverWatch()) preview.watch();
                        closePreview();
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, SECTION = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5,
                POWER = 6, PIN_PAD = 7, WALLPAPER_PICKER = 8;
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 6;
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_GAP = 8, CHOICE_H = 104;
        // The main menu: eight rows, then the comparison, the picks (with the Watchmode credit) and two notices, all on
        // one 1080 px screen: the rows share the 462 px that seven took.
        private static final float MAIN_TOP = 140, ROW_H = 53.75f, MAIN_GAP = 4;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor (also the top buttons' one, with editingTop): what it can hold (selected ones first, in
        // order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private boolean editingTop;
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0, PICK_LANGUAGE = -1, PICK_SLEEP = -2, PICK_PLACE = -3;
        /** Sleep Timer's pickers (PICK_SLEEP and below): their choices as {value, name}, and the value now. */
        private final List<String[]> choices = new ArrayList<>();
        private String choiceNow = "";
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        // Dedicated two-pane wallpaper picker; unlike the generic PICKER it never shares a scroll or selection state.
        private int wallpaperCategory, wallpaperOption;
        private boolean wallpaperOptionsPane;
        private final String[] wallpaperCategories = Wallpaper.categories();
        private String[] wallpaperOptions = new String[0];
        private Bitmap wallpaperPreviewBitmap;
        private int wallpaperPreviewState;
        private final Paint wallpaperPreviewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private Toast whyToast;   // why the focused option is unavailable
        private String[] whyRows;  // each row's reason, worked out once per screen (null: again on the next frame)
        private int whyScreen = -1, whyScroll = -1;
        private int sleepLeft;     // the sleep timer's minutes left, read with the state, not on every frame
        private int whyState = -1;
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private Paint heading, hint, glass, ring, digit;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                sleepLeft = SleepTimer.minutesLeft(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                // The TV's own accessibility settings, shown in Appearance (read here, not on every frame).
                android.content.ContentResolver cr = getContext().getContentResolver();
                reduceMotion = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
                highContrast = Settings.Secure.getInt(cr, "high_text_contrast_enabled", 0) == 1;
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // What can change the reasons away from a key: a switch (or the watchdog's revert) and the overlay's and the
                // listener's checks.
                int whyNow = (freeActive() ? 1 : 0) | (Capabilities.refused(getContext(), Capabilities.OVERLAY) ? 2 : 0)
                        | (Capabilities.refused(getContext(), Capabilities.LISTENER) ? 4 : 0);
                if (whyNow != whyState) whyRows = null;
                whyState = whyNow;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription(Ui.tr("mahitTV Settings"));
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen, and why where it's unavailable. */
        private String rowText(int row) {
            String why = unavailable(row);
            return why == null ? rowValueText(row) : rowValueText(row) + ", " + Ui.tr("Unavailable") + ". " + why;
        }

        private String rowValueText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row >= SECTIONS) return "";
                    String value = mainValue(row);
                    return value.isEmpty() ? sectionName(row) : sectionName(row) + ", " + value;
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "mahitTV, " + Ui.tr("no ads, with the new screensaver")
                            : "Original, " + Ui.tr("the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". In use") : "") + ", " + Ui.trf("{i} of {n}", "i", row + 1, "n", 2);
                }
                case PIN_PAD: {
                    String status = pinMessage.isEmpty() ? pinTitle() : pinMessage;
                    return Ui.trf("Digit {n}", "n", row) + ", " + Ui.trf("{i} of 4 entered", "i", pinDigits.length())
                            + ". " + status;
                }
                case SECTION:
                case REMOTE_KEYS:
                case POWER: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = listLabels(), values = listValues();
                    int at = keys ? row : pickScroll + row;
                    return at < labels.length ? labels[at] + ", " + values[at] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", selected") : "")
                            + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", pickLabels.size());
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    if (editingTop) {
                        return app[1] + ", " + (place >= 0 ? Ui.trf("in the top buttons, place {n}", "n", place + 1)
                                : Ui.tr("not in the top buttons"));
                    }
                    return app[1] + ", " + (place >= 0 ? Ui.trf("in the Dock, place {n}", "n", place + 1)
                            : Ui.tr("not in the Dock"));
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = MAIN_TOP + row * (ROW_H + MAIN_GAP);
                height = ROW_H;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else if (screen == PIN_PAD) {
                float x = PAD_X + row * (PAD_KEY + PAD_GAP);
                focusBox.set(x * k, PAD_KEYS_TOP * k, (x + PAD_KEY) * k, (PAD_KEYS_TOP + PAD_KEY) * k);
                return focusBox;
            } else {
                top = LIST_TOP + 20 + row * (LIST_ROW_H + LIST_ROW_GAP);
                height = LIST_ROW_H;
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        /** The Sleep Timer screen, focused on its first row. */
        void openSleepTimer() {
            screen = POWER;
            focus = powerFocus(P_SLEEP);
            invalidate();
            spoken.focusMoved();
        }

        void resume() {
            if (running) return;
            running = true;
            whyRows = null;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
            Photos.importInbox(getContext(), this::invalidate);   // photos a computer just sent
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy() || !shake.settled();
            for (Spring s : pop) moving |= !s.settled();
            for (Spring s : dots) moving |= !s.settled();
            if (moving || SystemClock.uptimeMillis() - lastSky >= 100) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            Symbols.warm(getContext(), "sparkles", 150 * k);
            Symbols.warm(getContext(), "chevron_right", 22 * k);
            Symbols.warm(getContext(), "check", 26 * k);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            glass = new Paint(Paint.ANTI_ALIAS_FLAG);
            glass.setColor(0x26FFFFFF);
            ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3 * k);
            digit = Ui.numbers(Ui.medium, 34 * k, 0xF2FFFFFF);
            digit.setTextAlign(Paint.Align.CENTER);
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return SECTIONS;
                case HOME_CHOICE: return 2;
                case SECTION: return Math.min(SETTINGS_VISIBLE, rows.length);
                case PIN_PAD: return 10;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case WALLPAPER_PICKER: return wallpaperOptionsPane ? wallpaperOptions.length : 4;
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                case POWER: return powerRows().length;
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            scene.drawBackground(c);

            // On the left: big symbol, title, description.
            float cx = 500 * k;
            Symbols.draw(getContext(), c, "sparkles", cx, 400 * k, 150 * k, 0xEBFFFFFF);
            c.drawText(Ui.tr("mahitTV Settings"), cx, 612 * k, title);
            c.drawText(Ui.tr("Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            c.drawText(versionName(getContext()), cx, 704 * k, version);

            if (screen == MAIN) drawMain(c, seconds); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == WALLPAPER_PICKER) drawWallpaperPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c);
            else if (screen == PIN_PAD) drawPinPad(c, dt);
            else drawList(c);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = busyText();
            for (int i = 0; i < SECTIONS; i++) {
                float y = (MAIN_TOP + i * (ROW_H + MAIN_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                boolean off = unavailable(i) != null;
                int ink = off ? blend(0x73FFFFFF, 0x731C1C1E, f) : blend(0xF2FFFFFF, INK, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(sectionName(i), rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                // Every section opens: the chevron, with what's worth knowing before it (who, which mode).
                float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                right -= cw + 16 * k;
                if (off) {
                    rowValue.setColor(blend(0x59FFFFFF, 0x591C1C1E, f));
                    c.drawText(Ui.tr("Unavailable"), right, base, rowValue);
                } else if (i == S_SYSTEM && working) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = busyText.isEmpty() ? Ui.tr("Starting…") : busyText;
                    rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
                    String shown = TextUtils.ellipsize(text, new TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(0xFFFFFFFF, INK, f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else {
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    c.drawText(mainValue(i), right, base, rowValue);
                }
            }

            float y = (MAIN_TOP + SECTIONS * (ROW_H + MAIN_GAP) + 20) * k;
            String error = state.optString("error");
            if (working && busyText.equals(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."))) {
                c.drawText(Ui.tr("On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty()) {
                // The whole message, on up to five lines: some say what to do (the Wi-Fi one, #14).
                int width = Math.round(760 * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 30 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
                y += errorLayout.getLineBaseline(errorLayout.getLineCount() - 1) - errorLayout.getLineBaseline(0) + 46 * k;
            }
            y = drawComparison(c, y);
            y = drawRecs(c, y + 26 * k);
            drawNotices(c, y + 40 * k);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("No TMDB key: see the README (config.properties).");
            else if (error.equals(Recs.NO_KNOWN_APPS)) line = Ui.tr("None of the TV's streaming apps can give picks.");
            else if (error.equals(Recs.NO_APPS)) line = Ui.trf("TMDB has none of the TV's apps for tmdb_region={region}.", "region", Recs.REGION);
            else if (!error.isEmpty()) line = Ui.trf("Error: {error}.", "error", error);
            else if (count > 0) line = Ui.trf("On · {n} titles from the TV's apps, 3 new ones every hour.", "n", count);
            else line = Ui.tr("Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            if (!Config.WATCHMODE_KEY.trim().isEmpty()) {
                c.drawText(Ui.tr("Links to the titles by Watchmode."), x, y + 108 * k, version);
            }
            version.setTextAlign(Paint.Align.CENTER);
            return y + (Config.WATCHMODE_KEY.trim().isEmpty() ? 78 : 108) * k;
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARISON"), x, y, header);
            cellValue.setColor(0x73FFFFFF);
            c.drawText(Ui.tr("Original"), colA, y, cellValue);
            c.drawText("mahitTV", colB, y, cellValue);
            cellValue.setColor(0xD9FFFFFF);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Home button"), "homeMs"}, {Ui.tr("Free memory"), "memAvailableMB"},
                    {Ui.tr("Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (46 + 40 * rows.length) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("None yet."), x, y + 42 * k, notice);
        }

        /** Home screen choice: mahitTV or the original (behind the manager's PIN), with a check on the one in use. */
        private void drawChoice(Canvas c) {
            c.drawText(Ui.tr("Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"mahitTV", Ui.tr("No ads, with the new screensaver")},
                    {Ui.tr("Original"), Ui.tr("The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                rowSub.setColor(blend(0x8CFFFFFF, 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            float hy = (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k;
            c.drawText(Ui.tr("Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k, hy, hint);
            c.drawText(Ui.tr("Original asks for the manager's PIN."), LIST_X * k + 30 * k, hy + 38 * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return Ui.trf("{n} ms", "n", v);
            if (key.equals("memAvailableMB") && v >= 1024) return Ui.trf("{n} GB", "n", String.format(Ui.locale(), "%.2f", v / 1024.0));
            return Ui.trf("{n} MB", "n", v);
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("now");
            if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
            if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
            return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
        }

        private static String versionName(Context c) {
            try {
                return "mahitTV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "mahitTV";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (screen == PIN_PAD && pinKey(e, down)) return true;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && screen == WALLPAPER_PICKER) { moveWallpaper(-1); return true; }
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    if (down) armed = 0;   // a second OK must be on the same row, right after the first
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && screen == WALLPAPER_PICKER) { moveWallpaper(1); return true; }
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    if (down) armed = 0;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == WALLPAPER_PICKER) { wallpaperOptionsPane = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT; invalidate(); return true; }
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    } else if (down && (screen == SECTION || screen == REMOTE_KEYS || screen == POWER)) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    }
                    whyRows = null;   // a value or the rows' order changed: what each row needs may have too
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) {
                            activate();
                            whyRows = null;   // the same after OK
                        }
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            armed = 0;
                            if (screen == WALLPAPER_PICKER) {
                                openSection(S_APPEARANCE, R_WALLPAPER);
                            } else if (screen == PICKER && pickFor <= PICK_SLEEP) {
                                focus = powerFocus(pickFor == PICK_SLEEP ? P_SLEEP : P_WHERE);
                                pickScroll = 0;   // the picker's scroll isn't this short list's
                                screen = POWER;
                            } else if (screen == PICKER && pickFor == PICK_CITY) {
                                openSection(S_LOCATION, R_CITY);
                            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                                openSection(S_SYSTEM, R_LANGUAGE);
                            } else if (screen == PICKER) {
                                focus = pickFor - 1;
                                screen = REMOTE_KEYS;
                            } else if (screen == HOME_CHOICE) {
                                openSection(S_SYSTEM, R_HOME);
                            } else if (screen == SHELF_EDIT) {
                                openSection(editingTop ? S_SYSTEM : shelfSection, editingTop ? R_TOP_BUTTONS : R_DOCK);
                            } else if (screen == REMOTE_KEYS) {
                                openSection(S_SYSTEM, R_REMOTE);
                            } else if (screen == POWER) {
                                openSection(S_SYSTEM, R_SLEEP);
                            } else {
                                focus = screen == SECTION ? section : 0;
                                pickScroll = 0;
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Sections (MAHITTV-PROFILES-SPEC.md §6)

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /** The main list, in this order. */
        private static final int S_PROFILES = 0, S_APPEARANCE = 1, S_LOCATION = 2, S_SMART_HOME = 3, S_WELLY = 4,
                S_SYSTEM = 5, S_RESET = 6, SECTIONS = 7;
        /**
         * A section's rows, by what they do (which rows show changes with the profile and the TV). R_OTHER + i is the
         * manager's row for profile i, R_COIN + i a coin of the Quotes widget, R_LIGHT + i one of LIGHTS.
         */
        private static final int R_PROFILE = 1, R_SWITCH = 2, R_CHANGE_PIN = 3, R_DOCK = 4, R_GUEST = 5,
                R_THEME = 10, R_WALLPAPER = 11, R_PHOTOS = 12, R_INTRO = 13, R_SOUNDS = 14, R_MOTION = 15,
                R_TRANSPARENCY = 16,
                R_CITY = 20, R_TEMPERATURE = 21, R_WEATHER = 22, R_FORECAST = 23, R_PLAYING = 24, R_QUOTES = 25,
                R_SAVER = 26, R_TMDB = 27, R_WATCHMODE = 28, R_TOP_SHELF = 29,
                R_CHECK = 40, R_GEMINI = 50, R_OLLAMA = 51, R_FORGET = 52,
                R_HOME = 60, R_SCREENSAVER = 61, R_TOP_BUTTONS = 62, R_REMOTE = 63, R_SLEEP = 64, R_TV_SETTINGS = 65,
                R_LANGUAGE = 66, R_SYSTEM_APPS = 67, R_APP_COUNT = 68,
                R_RESET = 70, R_RESTORE = 71,
                R_OTHER = 100, R_COIN = 200, R_LIGHT = 300;
        /** The four Kasa lights (house.json fixtures). */
        private static final String[] LIGHTS = {"parents.bulb", "mine.lamp", "mine.bulb", "living.bulb"};
        /** How often the Top Shelf moves on (HomeView.ROTATE_MS): there's no setting for it yet, so the row only reads. */
        private static final int TOP_SHELF_SECONDS = 8;
        /** Section rows on screen at once (what fits above the hints at 1080p); a longer list scrolls. */
        private static final int SETTINGS_VISIBLE = 10;

        private int section;
        private int[] rows = new int[0];
        private int shelfSection = S_PROFILES;   // where the Dock editor was opened from, for Back
        private int armed;                       // a row waiting for its second OK
        private int appCount = -1;               // apps found (HomeApps.launchable), counted off the UI thread
        private int checking;                    // lights that haven't answered "Check again" yet
        private boolean reduceMotion, highContrast;   // the TV's own accessibility settings, read with the state
        private Bitmap avatar;                   // the current profile's, for its row
        private final Set<String> removePin = new HashSet<>();   // others' rows where ← → picked "Remove PIN"
        private StaticLayout hintLayout;
        private String hintShown;

        private boolean dollar() {
            return "BRL".equals(Place.currency());
        }

        private String sectionName(int s) {
            switch (s) {
                case S_PROFILES: return Ui.tr("Profiles & PINs");
                case S_APPEARANCE: return Ui.tr("Appearance & Startup");
                case S_LOCATION: return Ui.tr("Location & Media");
                case S_SMART_HOME: return Ui.tr("Smart Home");
                case S_WELLY: return "Welly";
                case S_SYSTEM: return Ui.tr("System & Original TV");
                default: return Ui.tr("Reset & Restore");
            }
        }

        /** What a section's row on the main list says before it opens: who's here, which home screen. */
        private String mainValue(int s) {
            if (s == S_PROFILES) return ProfileManager.current(getContext()).name;
            if (s == S_SYSTEM) return homeValue();
            return "";
        }

        private String homeValue() {
            if (busy()) {
                String text = busyText();
                return text.isEmpty() ? Ui.tr("Starting…") : text;
            }
            return freeActive() ? "mahitTV" : Ui.tr("Original");
        }

        private boolean originalNow() {
            return HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
        }

        private int[] sectionRows(int s) {
            Context c = getContext();
            List<Integer> l = new ArrayList<>();
            switch (s) {
                case S_PROFILES: {
                    ProfileManager.Profile me = ProfileManager.current(c);
                    l.add(R_PROFILE);
                    if (me.guest()) l.add(R_GUEST);
                    l.add(R_SWITCH);
                    if (!me.guest()) l.add(R_CHANGE_PIN);
                    l.add(R_DOCK);
                    if (me.manager()) {
                        List<ProfileManager.Profile> all = ProfileManager.all(c);
                        for (int i = 0; i < all.size(); i++) {
                            ProfileManager.Profile p = all.get(i);
                            if (!p.id.equals(me.id) && !p.guest()) l.add(R_OTHER + i);
                        }
                    }
                    break;
                }
                case S_APPEARANCE:
                    Collections.addAll(l, R_THEME, R_WALLPAPER, R_PHOTOS, R_INTRO, R_SOUNDS, R_MOTION, R_TRANSPARENCY);
                    break;
                case S_LOCATION:
                    Collections.addAll(l, R_CITY, R_TEMPERATURE, R_WEATHER, R_FORECAST, R_PLAYING, R_QUOTES);
                    for (int i = 0; i < (dollar() ? 4 : 3); i++) l.add(R_COIN + i);
                    Collections.addAll(l, R_SAVER, R_TMDB, R_WATCHMODE, R_TOP_SHELF);
                    break;
                case S_SMART_HOME:
                    for (int i = 0; i < LIGHTS.length; i++) l.add(R_LIGHT + i);
                    l.add(R_CHECK);
                    break;
                case S_WELLY:
                    Collections.addAll(l, R_GEMINI, R_OLLAMA, R_FORGET);
                    break;
                case S_SYSTEM:
                    Collections.addAll(l, R_HOME, R_SCREENSAVER, R_DOCK, R_TOP_BUTTONS, R_REMOTE, R_SLEEP, R_LANGUAGE,
                            R_SYSTEM_APPS, R_APP_COUNT, R_TV_SETTINGS);
                    break;
                default:
                    Collections.addAll(l, R_RESET, R_RESTORE);
            }
            int[] out = new int[l.size()];
            for (int i = 0; i < out.length; i++) out[i] = l.get(i);
            return out;
        }

        /** Opens section `s`, focused on row `id` (its first row when it has none). */
        private void openSection(int s, int id) {
            section = s;
            rows = sectionRows(s);
            screen = SECTION;
            armed = 0;
            whyRows = null;
            int at = 0;
            for (int i = 0; i < rows.length; i++) if (rows[i] == id) at = i;
            pickScroll = Math.max(0, at - Math.min(SETTINGS_VISIBLE, rows.length) + 1);
            focus = at - pickScroll;
            if (s == S_PROFILES) loadAvatar();
            if (s == S_SMART_HOME) checkLights();
            if (s == S_SYSTEM) countApps();
        }

        private void loadAvatar() {
            Context c = getContext();
            ProfileManager.Profile me = ProfileManager.current(c);
            int size = Math.round(44 * k);
            new Thread(() -> {
                Bitmap b = ProfileManager.avatar(c, me, size);
                handler.post(() -> {
                    avatar = b;
                    invalidate();
                });
            }).start();
        }

        private void countApps() {
            Context c = getContext();
            new Thread(() -> {
                int n = HomeApps.launchable(c).size();
                handler.post(() -> {
                    appCount = n;
                    invalidate();
                });
            }).start();
        }

        /** Reads every light's state again; Settings never switches one. */
        private void checkLights() {
            SmartHome.Bridge bridge = SmartHome.get(getContext());
            checking = bridge.devices().size();
            if (checking == 0) return;
            bridge.refresh((d, ok) -> {   // once per light, on the main thread
                if (checking > 0) checking--;
                invalidate();
            });
        }

        private SmartHome.Device light(int id) {
            return SmartHome.get(getContext()).device(LIGHTS[id - R_LIGHT]);
        }

        private static String lightName(int i) {
            switch (i) {
                case 0: return Ui.tr("Parents' Room Tube Light");
                case 1: return Ui.tr("My Room Lamp");
                case 2: return Ui.tr("My Room Tube Light");
                default: return Ui.tr("Living Room Lightbulb");
            }
        }

        /** A stable wallpaper ID as the transitional Appearance row presents it. */
        private static String wallpaperName(String id) {
            if ("horizon:auto".equals(id)) return Ui.tr("Automatic");
            if ("horizon:light".equals(id)) return Ui.tr("Light");
            if ("horizon:dark".equals(id)) return Ui.tr("Dark");
            if ("live:aerial".equals(id)) return "Live Aerial Screensaver";
            if (id.startsWith("studio:")) return id.substring("studio:".length()).replace('-', ' ');
            Wallpaper.Aerial aerial = Wallpaper.find(id);
            return aerial == null ? Ui.tr("Automatic") : aerial.title;
        }

        private ProfileManager.Profile other(int id) {
            return ProfileManager.all(getContext()).get(id - R_OTHER);
        }

        private String rowName(int id) {
            Context c = getContext();
            if (id >= R_LIGHT) return lightName(id - R_LIGHT);
            if (id >= R_COIN) {
                int i = id - R_COIN;
                return i == 0 ? "Bitcoin" : i == 1 ? "Ethereum" : i == 2 ? "Solana" : Ui.tr("Dollar");
            }
            if (id >= R_OTHER) return other(id).name;
            switch (id) {
                case R_PROFILE: return ProfileManager.current(c).name;
                case R_GUEST: return Ui.tr("Guest Mode: nothing is saved");
                case R_SWITCH: return Ui.tr("Switch Profile");
                case R_CHANGE_PIN: return Ui.tr("Change PIN");
                case R_DOCK: return Ui.tr("Dock");
                case R_THEME: return Ui.tr("Theme");
                case R_WALLPAPER: return Ui.tr("Wallpaper");
                case R_PHOTOS: return Ui.tr("My Photos");
                case R_INTRO: return Ui.tr("Startup animation & chime");
                case R_SOUNDS: return Ui.tr("Navigation sounds");
                case R_MOTION: return Ui.tr("Reduce motion");
                case R_TRANSPARENCY: return Ui.tr("Reduce transparency");
                case R_CITY: return Ui.tr("City");
                case R_TEMPERATURE: return Ui.tr("Temperature");
                case R_WEATHER: return Ui.tr("Weather widget");
                case R_FORECAST: return Ui.tr("Forecast");
                case R_PLAYING: return Ui.tr("Now Playing widget");
                case R_QUOTES: return Ui.tr("Quotes widget");
                case R_SAVER: return Ui.tr("Watch from the screensaver");
                case R_TMDB: return Ui.tr("TMDB key");
                case R_WATCHMODE: return Ui.tr("Watchmode key");
                case R_TOP_SHELF: return Ui.tr("Top Shelf auto-advance");
                case R_CHECK: return Ui.tr("Check again");
                case R_GEMINI: return "Gemini";
                case R_OLLAMA: return Ui.tr("Ollama fallback");
                case R_FORGET: return Ui.trf("Clear Welly's memory for {name}", "name", ProfileManager.current(c).name);
                case R_HOME: return Ui.tr("Home Screen");
                case R_SCREENSAVER: return Ui.tr("Screensaver");
                case R_TOP_BUTTONS: return Ui.tr("Top Buttons");
                case R_REMOTE: return Ui.tr("Remote Buttons");
                case R_SLEEP: return Ui.tr("Sleep Timer");
                case R_TV_SETTINGS: return Ui.tr("TV Settings");
                case R_LANGUAGE: return Ui.tr("Language");
                case R_SYSTEM_APPS: return Ui.tr("Show system apps");
                case R_APP_COUNT: return Ui.tr("Apps found");
                case R_RESET: return Ui.tr("Reset mahitTV Data");
                case R_RESTORE: return Ui.tr("Restore Original TV");
                default: return "";
            }
        }

        private String rowState(int id) {
            Context c = getContext();
            if (id >= R_LIGHT) {
                SmartHome.Device d = light(id);
                if (d == null) return Ui.tr("Not set up");
                String now = !d.reachable ? Ui.tr("Unreachable") : d.known ? onOff(d.on) : Ui.tr("Checking…");
                return d.host + "  ·  " + now;
            }
            if (id >= R_COIN) return onOff(Prefs.quotes() && Prefs.coin(id - R_COIN));
            if (id >= R_OTHER) {
                ProfileManager.Profile p = other(id);
                return removePin.contains(p.id) && ProfileManager.needsPin(c, p) ? Ui.tr("Remove PIN") : Ui.tr("Set PIN");
            }
            if (armed == id) return Ui.tr("Press OK again");
            switch (id) {
                case R_PROFILE: {
                    ProfileManager.Profile me = ProfileManager.current(c);
                    return me.manager() ? Ui.tr("Manager") : me.guest() ? Ui.tr("Guest") : Ui.tr("Family");
                }
                case R_CHANGE_PIN: return ProfileManager.needsPin(c, ProfileManager.current(c)) ? "" : Ui.tr("No PIN");
                case R_THEME: {
                    String theme = Prefs.theme();
                    return theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark") : Ui.tr("Automatic");
                }
                case R_WALLPAPER: {
                    return wallpaperName(Prefs.wallpaper());
                }
                case R_PHOTOS: {
                    int photos = Photos.files(c).size();
                    return photos == 0 ? Ui.tr("photos|None")
                            : Prefs.photos() ? Ui.plural("plural|one:{n} photo|other:{n} photos", photos) : Ui.tr("Off");
                }
                case R_INTRO: return onOff(Prefs.intro());
                case R_SOUNDS: return onOff(Prefs.sounds());
                case R_MOTION: return onOff(reduceMotion);
                case R_TRANSPARENCY: return onOff(highContrast);
                case R_CITY: return !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
                case R_TEMPERATURE: {
                    String unit = Prefs.temperature();
                    return unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("unit|Automatic");
                }
                case R_WEATHER: return onOff(Prefs.weather());
                case R_FORECAST: return Prefs.forecast().equals("days") ? Ui.tr("Next days") : Ui.tr("Next hours");
                case R_PLAYING: return onOff(Prefs.nowPlaying());
                case R_QUOTES: return onOff(Prefs.quotes());
                case R_SAVER: return onOff(Prefs.saverWatch());
                // Only whether there is one: a key is never shown.
                case R_TMDB: return hasKey ? Ui.tr("Set") : Ui.tr("Not set");
                case R_WATCHMODE: return Config.WATCHMODE_KEY.trim().isEmpty() ? Ui.tr("Not set") : Ui.tr("Set");
                case R_TOP_SHELF: return Ui.trf("Every {n} s", "n", TOP_SHELF_SECONDS);
                case R_CHECK: return checking > 0 ? Ui.tr("Checking…") : "";
                case R_GEMINI: return Config.GEMINI_KEY.trim().isEmpty() ? Ui.tr("Key not set")
                        : Ui.trf("Key set  ·  {model}", "model", Config.WELLY_MODEL);
                case R_OLLAMA: {
                    String url = Config.OLLAMA_URL.trim();
                    if (url.isEmpty()) return Ui.tr("Not set");
                    String host = android.net.Uri.parse(url).getHost();
                    return host == null || host.isEmpty() ? Ui.tr("Set") : host;
                }
                case R_HOME: return homeValue();
                case R_SCREENSAVER: return Ui.tr("Preview");
                case R_LANGUAGE: {
                    String lang = Prefs.language();
                    return lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang);
                }
                case R_SYSTEM_APPS: return onOff(Prefs.systemApps());
                case R_APP_COUNT: return appCount < 0 ? Ui.tr("Counting…") : String.valueOf(appCount);
                case R_RESTORE: return originalNow() ? Ui.tr("In use") : "";
                default: return "";
            }
        }

        /** Rows that open something (a screen, a list, the PIN pad): they show a chevron. */
        private static boolean opens(int id) {
            switch (id) {
                case R_CHANGE_PIN: case R_DOCK: case R_HOME: case R_TOP_BUTTONS: case R_REMOTE: case R_SLEEP: case R_WALLPAPER:
                case R_TV_SETTINGS: case R_LANGUAGE: case R_MOTION: case R_TRANSPARENCY: case R_RESET: case R_RESTORE:
                    return true;
                default:
                    return id >= R_OTHER && id < R_COIN;
            }
        }

        /** What the focused row does, under the list. */
        private String sectionHint(int id) {
            Context c = getContext();
            if (id >= R_LIGHT) {
                SmartHome.Device d = light(id);
                if (d == null) return Ui.tr("Not in kasa= in config.properties on the computer.");
                return d.problem.isEmpty() ? Ui.tr("Read only: the Home tab turns the lights on and off.")
                        : Ui.trf("Problem: {problem}", "problem", d.problem);
            }
            if (id >= R_COIN) return Ui.tr("OK or the arrows change the value.");
            if (id >= R_OTHER) {
                return ProfileManager.needsPin(c, other(id)) ? Ui.tr("← → choose Set PIN or Remove PIN. OK asks for your PIN first.")
                        : Ui.tr("No PIN yet. OK asks for your PIN, then a new one.");
            }
            switch (id) {
                case R_PROFILE: return Ui.tr("The profile using the TV now.");
                case R_GUEST: return Ui.tr("Guest's Dock, theme and Welly chats are forgotten when the TV locks.");
                case R_SWITCH: return Ui.tr("OK locks the TV and goes back to “Who's Watching?”.");
                case R_CHANGE_PIN: return Ui.tr("OK asks for your PIN, then the new one twice.");
                case R_DOCK: return Ui.tr("This profile's apps on the home screen, in order.");
                case R_WALLPAPER: return Ui.tr("Automatic shows the picks, then the aerials. ← → pick one aerial.");
                case R_PHOTOS: return Photos.files(c).isEmpty() ? Ui.tr("Send them from a computer: see “Your own photos” in the README.")
                        : Ui.tr("OK removes them; the arrows turn them on or off.");
                case R_INTRO: return Ui.tr("The golden mahitTV intro when the TV turns on.");
                case R_MOTION:
                case R_TRANSPARENCY: return Ui.tr("Shown as the TV has it. OK opens the TV's Accessibility settings.");
                case R_TMDB:
                case R_WATCHMODE: return Ui.tr("Keys live only in config.properties on the computer, never on screen.");
                case R_TOP_SHELF: return Ui.tr("Fixed for now: there's no setting for it yet.");
                case R_CHECK: return Ui.tr("OK reads every light again. Nothing is switched.");
                case R_GEMINI:
                case R_OLLAMA: return Ui.tr("Set in config.properties: gemini_key, welly_model and ollama_url.");
                case R_FORGET: return armed == R_FORGET ? Ui.tr("OK again clears it. Any other key keeps it.")
                        : Ui.tr("Clears Welly's chats, what it learned and this profile's watch history.");
                case R_HOME: return Ui.tr("OK opens the choice: mahitTV or the TV's original home screen.");
                case R_SYSTEM_APPS: return Ui.tr("Also lists system apps that only have a phone-style entry.");
                case R_APP_COUNT: return Ui.tr("Every app the TV can open from All Apps.");
                case R_RESET: return armed == R_RESET ? Ui.tr("OK again resets it. Any other key keeps it.")
                        : Ui.tr("Clears every profile's Dock, theme, PIN changes, Welly memory and history. Language, city and remote buttons stay. Asks for the manager's PIN.");
                case R_RESTORE: return armed == R_RESTORE ? Ui.tr("OK again restores it. Any other key keeps mahitTV.")
                        : Ui.tr("Turns mahitTV mode off and brings back the TV's own home screen; mahitTV stays installed. Asks for the manager's PIN.");
                case R_REMOTE:
                case R_SLEEP:
                case R_TOP_BUTTONS:
                case R_TV_SETTINGS:
                case R_LANGUAGE:
                case R_SCREENSAVER:
                case R_CITY: return Ui.tr("OK opens it.");
                default: return Ui.tr("OK or the arrows change the value.");
            }
        }

        /**
         * OK on a section's row. Returns true when the screen changed (the focus pops start again), false when the row
         * only changed its value or said something.
         */
        private boolean activateRow(int id) {
            Context c = getContext();
            if (id >= R_LIGHT) return false;   // read only
            if (id >= R_OTHER && id < R_COIN) {
                ProfileManager.Profile p = other(id);
                askPin(PIN_MANAGER, removePin.contains(p.id) && ProfileManager.needsPin(c, p) ? THEN_REMOVE : THEN_SET, p, id);
                return true;
            }
            switch (id) {
                case R_SWITCH:
                    ProfileManager.lock(c);
                    activity.finish();   // the home screen shows "Who's Watching?"
                    return false;
                case R_CHANGE_PIN:
                    askPin(PIN_OLD, 0, ProfileManager.current(c), id);
                    return true;
                case R_DOCK:
                    shelfSection = section;
                    openShelfEditor(false);
                    return true;
                case R_WALLPAPER:
                    openWallpaperPicker();
                    return true;
                case R_TOP_BUTTONS:
                    openShelfEditor(true);
                    return true;
                case R_PHOTOS:
                    askRemovePhotos();
                    return false;
                case R_MOTION:
                case R_TRANSPARENCY:
                    openAccessibility();
                    return false;
                case R_CITY:
                    askCity();
                    return false;
                case R_CHECK:
                    checkLights();
                    return false;
                case R_FORGET:
                    if (armed != R_FORGET) {
                        armed = R_FORGET;
                        return false;
                    }
                    armed = 0;
                    ProfileManager.Profile me = ProfileManager.current(c);
                    WellyMemory.clear(c, me);
                    WatchLog.clear(c);
                    say(Ui.trf("Welly's memory for {name} is cleared.", "name", me.name));
                    return false;
                case R_HOME:
                    if (busy()) return false;
                    screen = HOME_CHOICE;
                    focus = freeActive() ? 0 : 1;
                    return true;
                case R_SCREENSAVER:
                    pause();
                    activity.showPreview();
                    return false;
                case R_REMOTE:
                    HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                    loadApps();
                    screen = REMOTE_KEYS;
                    focus = 0;
                    return true;
                case R_SLEEP:
                    pickScroll = 0;
                    screen = POWER;
                    focus = 0;
                    return true;
                case R_TV_SETTINGS:
                    activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return false;
                case R_LANGUAGE:
                    openPicker(PICK_LANGUAGE);
                    return true;
                case R_RESET:
                case R_RESTORE: {
                    if (id == R_RESTORE && originalNow()) {
                        say(Ui.tr("The TV's original home screen is already on."));
                        return false;
                    }
                    if (id == R_RESTORE && busy()) return false;
                    // Without a manager PIN to ask, a second OK stands in for it.
                    if (!ProfileManager.needsPin(c, ProfileManager.manager(c)) && armed != id) {
                        armed = id;
                        return false;
                    }
                    armed = 0;
                    askPin(PIN_MANAGER, id == R_RESET ? THEN_RESET : THEN_RESTORE, ProfileManager.manager(c), id);
                    return true;
                }
                case R_PROFILE: case R_GUEST: case R_TMDB: case R_WATCHMODE: case R_TOP_SHELF: case R_GEMINI:
                case R_OLLAMA: case R_APP_COUNT:
                    return false;   // they only read
                default:
                    adjust(1);
                    return false;
            }
        }

        /** The TV's accessibility settings (reduce motion and the like are the TV's, not changed from here). */
        private void openAccessibility() {
            try {
                activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (android.content.ActivityNotFoundException e) {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }

        private void say(String text) {
            if (whyToast != null) whyToast.cancel();
            whyToast = Toast.makeText(getContext(), text, Toast.LENGTH_LONG);
            whyToast.show();
        }

        /** Asks the main process for the original home screen (as Home Screen → Original always did), then shows it. */
        private void requestOriginal() {
            if (!originalNow() && !busy()) {
                HomeMode.request(getContext(), HomeMode.ORIGINAL);
                pendingAt = SystemClock.uptimeMillis();
            }
            openSection(S_SYSTEM, R_HOME);
        }

        // MARK: - PIN pad (MAHITTV-PROFILES-SPEC.md §1.4, §3)

        private static final int PIN_OLD = 0, PIN_NEW = 1, PIN_CONFIRM = 2, PIN_MANAGER = 3;
        /** What the manager's PIN was asked for. */
        private static final int THEN_SET = 1, THEN_REMOVE = 2, THEN_RESET = 3, THEN_ORIGINAL = 4, THEN_RESTORE = 5;
        private static final float PAD_KEY = 72, PAD_GAP = 11, PAD_X = LIST_X + (LIST_R - LIST_X - 10 * PAD_KEY - 9 * PAD_GAP) / 2,
                PAD_KEYS_TOP = 560, PAD_DOTS_Y = 420, PAD_DOT_R = 13, PAD_DOT_GAP = 58;
        private static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};

        private int pinStep, pinThen, pinFromScreen, pinFromRow;
        private ProfileManager.Profile pinFor;
        private String pinFirst = "", pinMessage = "";
        private final StringBuilder pinDigits = new StringBuilder();
        /** The pad's head-shake on a wrong PIN, and its nudge at a dead end (px at 1080p). */
        private final Spring shake = new Spring(Spring.BOUNDARY_BUMP, 0).px();
        /** Each dot pops as its digit lands. */
        private final Spring[] dots = {new Spring(Spring.MICRO_PRESS, 1), new Spring(Spring.MICRO_PRESS, 1),
                new Spring(Spring.MICRO_PRESS, 1), new Spring(Spring.MICRO_PRESS, 1)};

        /**
         * Opens the pad at `step` for `who` (whose PIN changes; for PIN_MANAGER, the profile it's about), from row `fromRow`
         * of this screen. A PIN that isn't there isn't asked: its step is skipped.
         */
        private void askPin(int step, int then, ProfileManager.Profile who, int fromRow) {
            Context c = getContext();
            pinFromScreen = screen;
            pinFromRow = fromRow;
            pinThen = then;
            pinFor = who;
            if (step == PIN_MANAGER && !ProfileManager.needsPin(c, ProfileManager.manager(c))) {
                managerOk();
                return;
            }
            if (step == PIN_OLD && !ProfileManager.needsPin(c, who)) step = PIN_NEW;
            startPinStep(step);
        }

        private void startPinStep(int step) {
            if (screen != PIN_PAD) focus = 1;
            pinStep = step;
            pinDigits.setLength(0);
            pinMessage = "";
            for (Spring s : dots) s.snap(1);
            screen = PIN_PAD;
        }

        /** Back from the pad: to the row it was opened from. */
        private void leavePin() {
            pinDigits.setLength(0);
            pinMessage = "";
            if (pinFromScreen == HOME_CHOICE) {
                screen = HOME_CHOICE;
                focus = 1;
            } else {
                openSection(section, pinFromRow);
            }
            for (Spring s : pop) s.snap(0);
        }

        private String pinTitle() {
            Context c = getContext();
            switch (pinStep) {
                case PIN_OLD: return Ui.tr("Enter your current PIN");
                case PIN_NEW: return pinFor.id.equals(ProfileManager.current(c).id) ? Ui.tr("Enter a new PIN")
                        : Ui.trf("Enter a new PIN for {name}", "name", pinFor.name);
                case PIN_CONFIRM: return Ui.tr("Enter the new PIN again");
                default: return Ui.trf("Enter {name}'s PIN", "name", ProfileManager.manager(c).name);
            }
        }

        private String pinReason() {
            if (pinStep != PIN_MANAGER) return pinFor.name;
            switch (pinThen) {
                case THEN_SET: return Ui.trf("To set {name}'s PIN", "name", pinFor.name);
                case THEN_REMOVE: return Ui.trf("To remove {name}'s PIN", "name", pinFor.name);
                case THEN_RESET: return Ui.tr("To reset all mahitTV data");
                default: return Ui.tr("To restore the TV's original home screen");
            }
        }

        /** The pad's own keys: digits (the remote's number keys too), ← →, Back (deletes, then leaves). OK goes on to activate. */
        private boolean pinKey(KeyEvent e, boolean down) {
            int code = e.getKeyCode();
            int n = code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9 ? code - KeyEvent.KEYCODE_0
                    : code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9 ? code - KeyEvent.KEYCODE_NUMPAD_0 : -1;
            if (n >= 0) {
                if (down && e.getRepeatCount() == 0) {
                    focus = n;
                    typeDigit(n);
                    invalidate();
                }
                return true;
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    if (!down) return true;
                    int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    if (focus + dir < 0 || focus + dir > 9) {
                        shake.impulse(dir * Spring.BUMP_VELOCITY * 0.4f);   // a dead end: the row leans and comes back
                        Click.boundary();
                    } else {
                        focus += dir;
                        spoken.focusMoved();
                    }
                    invalidate();
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;   // one row
                case KeyEvent.KEYCODE_DEL:
                    if (down && pinDigits.length() > 0) pinDigits.setLength(pinDigits.length() - 1);
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                        return true;
                    }
                    if (backDown && !e.isCanceled()) {
                        if (pinDigits.length() > 0) pinDigits.setLength(pinDigits.length() - 1);
                        else leavePin();
                    }
                    backDown = false;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        private ProfileManager.Profile pinChecked() {
            return pinStep == PIN_MANAGER ? ProfileManager.manager(getContext()) : pinFor;
        }

        private void typeDigit(int n) {
            boolean checks = pinStep == PIN_OLD || pinStep == PIN_MANAGER;
            if (checks && ProfileManager.waitLeft(pinChecked()) > 0) {
                wrongPin(pinChecked());
                return;
            }
            if (pinDigits.length() >= 4) return;
            pinDigits.append(DIGITS[n]);
            Spring dot = dots[pinDigits.length() - 1];
            dot.snap(1.35f);
            dot.target = 1;
            pinMessage = "";
            if (pinDigits.length() == 4) handler.postDelayed(this::submitPin, 160);   // the fourth dot shows first
        }

        private void submitPin() {
            if (screen != PIN_PAD || pinDigits.length() != 4) return;
            Context c = getContext();
            String d = pinDigits.toString();
            switch (pinStep) {
                case PIN_OLD:
                    if (ProfileManager.checkPin(c, pinFor, d)) startPinStep(PIN_NEW); else wrongPin(pinFor);
                    break;
                case PIN_MANAGER: {
                    ProfileManager.Profile manager = ProfileManager.manager(c);
                    if (ProfileManager.checkPin(c, manager, d)) managerOk(); else wrongPin(manager);
                    break;
                }
                case PIN_NEW:
                    pinFirst = d;
                    startPinStep(PIN_CONFIRM);
                    break;
                default:
                    if (d.equals(pinFirst)) {
                        ProfileManager.setPin(c, pinFor, d);
                        say(pinFor.id.equals(ProfileManager.current(c).id) ? Ui.tr("Your PIN is changed.")
                                : Ui.trf("{name}'s PIN is set.", "name", pinFor.name));
                        leavePin();
                    } else {
                        startPinStep(PIN_NEW);
                        fail(Ui.tr("The PINs didn't match. Enter the new one again."));
                    }
            }
            invalidate();
            spoken.focusMoved();
        }

        /** The manager's PIN was right (or there is none): what it was asked for. */
        private void managerOk() {
            Context c = getContext();
            switch (pinThen) {
                case THEN_SET:
                    startPinStep(PIN_NEW);
                    return;
                case THEN_REMOVE:
                    ProfileManager.setPin(c, pinFor, "");
                    removePin.remove(pinFor.id);
                    say(Ui.trf("{name} has no PIN now.", "name", pinFor.name));
                    leavePin();
                    return;
                case THEN_RESET:
                    ProfileManager.resetAll(c);
                    activity.finish();   // the home screen, locked: "Who's Watching?" with the default profiles
                    return;
                default:   // THEN_ORIGINAL, THEN_RESTORE
                    pinDigits.setLength(0);
                    requestOriginal();
                    for (Spring s : pop) s.snap(0);
            }
        }

        private void wrongPin(ProfileManager.Profile p) {
            long wait = ProfileManager.waitLeft(p);
            fail(wait > 0 ? Ui.trf("Too many tries. Wait {n} s.", "n", (wait + 999) / 1000) : Ui.tr("Wrong PIN. Try again."));
        }

        /** Wrong: the head-shake (±18 px), the knock, the dots clear and the message says why. */
        private void fail(String message) {
            pinMessage = message;
            pinDigits.setLength(0);
            for (Spring s : dots) s.snap(1);
            shake.impulse(Spring.BUMP_VELOCITY);
            Click.boundary();
            announceForAccessibility(message);
        }

        private void drawPinPad(Canvas c, float dt) {
            shake.step(dt);
            for (Spring s : dots) s.step(dt);
            float dx = shake.value * k, x0 = LIST_X * k + 30 * k, mid = (LIST_X + LIST_R) / 2f * k;
            c.drawText(pinTitle(), x0 + dx, (LIST_TOP - 24) * k, heading);
            rowSub.setColor(0x99FFFFFF);
            c.drawText(pinReason(), x0 + dx, (LIST_TOP + 26) * k, rowSub);
            white.setAlpha(255);
            for (int i = 0; i < 4; i++) {
                float cx = mid + (i - 1.5f) * PAD_DOT_GAP * k + dx, cy = PAD_DOTS_Y * k, r = PAD_DOT_R * k * dots[i].value;
                if (i < pinDigits.length()) {
                    c.drawCircle(cx, cy, r, white);
                } else {
                    ring.setColor(0x8CFFFFFF);
                    c.drawCircle(cx, cy, r, ring);
                }
            }
            if (!pinMessage.isEmpty()) c.drawText(pinMessage, mid - warn.measureText(pinMessage) / 2 + dx, (PAD_DOTS_Y + 74) * k, warn);
            for (int i = 0; i < 10; i++) {
                float f = Ui.clamp(pop[i].value), s = 1 + 0.12f * f;
                float cx = (PAD_X + i * (PAD_KEY + PAD_GAP) + PAD_KEY / 2) * k + dx, cy = (PAD_KEYS_TOP + PAD_KEY / 2) * k;
                float half = PAD_KEY / 2 * k * s, r = 20 * k * s;
                tmp.set(cx - half, cy - half, cx + half, cy + half);
                if (f > 0.01f) {
                    tmp.offset(0, 8 * k * f);
                    shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                    c.drawRoundRect(tmp, r, r, shadow);
                    tmp.offset(0, -8 * k * f);
                }
                c.drawRoundRect(tmp, r, r, glass);
                if (f > 0.01f) {
                    white.setAlpha(Math.round(255 * f));
                    c.drawRoundRect(tmp, r, r, white);
                }
                digit.setColor(blend(0xF2FFFFFF, INK, f));
                c.drawText(DIGITS[i], cx, cy + Ui.halfCap(digit), digit);
            }
            c.drawText(Ui.tr("Type with the number keys, or pick a digit and press OK. Back deletes."), x0,
                    (PAD_KEYS_TOP + PAD_KEY + 70) * k, hint);
        }

        /** "auto", then English and every language with a catalogue. */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        /**
         * What the focused screen's row `i` needs from the TV (Capabilities.NEED_…), or null. A new option that needs
         * something adds its line here; drawing, keys and TalkBack then treat it as unavailable where the TV lacks it.
         */
        private String need(int i) {
            switch (screen) {
                case SECTION: {
                    int id = rows[pickScroll + i];
                    return id == R_REMOTE ? Capabilities.NEED_REMOTE_BUTTONS : id == R_SLEEP ? Capabilities.NEED_ADB
                            : id == R_PLAYING ? Capabilities.NEED_LISTENER : null;
                }
                case POWER: {
                    int id = powerRows()[i];
                    return id == P_WHERE ? Capabilities.NEED_OVERLAY : null;
                }
                case SHELF_EDIT: {   // only to add it: one already among the buttons can still move or go
                    String key = shelfRows.get(pickScroll + i)[0];
                    return editingTop && HomeApps.TOP_LIVE.equals(key) && !shelfOrder.contains(key) ? Capabilities.NEED_LIVE_TV : null;
                }
                default:
                    return null;
            }
        }

        /** Why row `i` can't be used here, for the person to read; null where it works. */
        private String unavailable(int i) {
            int n = rowCount();
            if (i < 0 || i >= n) return null;
            int where = screen == SECTION ? screen * 16 + section : screen;
            if (whyRows == null || whyRows.length != n || whyScreen != where || whyScroll != pickScroll) {
                whyRows = new String[n];
                whyScreen = where;
                whyScroll = pickScroll;
                for (int r = 0; r < n; r++) {
                    String need = need(r);
                    int id = screen == SECTION ? rows[pickScroll + r] : 0;
                    whyRows[r] = id >= R_COIN && id < R_LIGHT && !Prefs.quotes()
                            ? Ui.tr("Turn on the Quotes widget to pick its coins.")
                            : need == null ? null : Capabilities.missing(getContext(), need);
                }
            }
            return whyRows[i];
        }

        /** OK on an unavailable row: a toast says why; false if it isn't unavailable. */
        private boolean tellWhy() {
            String why = unavailable(focus);
            if (whyToast != null) whyToast.cancel();
            whyToast = why == null ? null : Toast.makeText(getContext(), why, Toast.LENGTH_LONG);
            if (whyToast != null) whyToast.show();
            return why != null;
        }

        /**
         * Sleep Timer's rows, by what they set: the timer, whether it shows on screen and (where it shows over other
         * apps) where. Values are picked from lists (OK), as on the TV's own settings; ← → step them.
         */
        private static final int P_SLEEP = 0, P_SHOW = 1, P_WHERE = 2;

        private int[] powerRows() {
            return Prefs.sleepShown() ? new int[] {P_SLEEP, P_SHOW, P_WHERE} : new int[] {P_SLEEP, P_SHOW};
        }

        private int powerRow() {
            int[] rows = powerRows();
            return rows[Math.min(focus, rows.length - 1)];
        }

        private int powerFocus(int id) {
            int[] rows = powerRows();
            for (int i = 0; i < rows.length; i++) if (rows[i] == id) return i;
            return 0;
        }

        private String[] powerLabels() {
            int[] rows = powerRows();
            String[] out = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                switch (rows[i]) {
                    case P_SLEEP: out[i] = Ui.tr("Sleep Timer"); break;
                    case P_SHOW: out[i] = Ui.tr("Show Time Left"); break;
                    default: out[i] = Ui.tr("Position");
                }
            }
            return out;
        }

        private String[] powerValues() {
            int[] rows = powerRows();
            String[] out = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                switch (rows[i]) {
                    case P_SLEEP: out[i] = sleepLeft == 0 ? Ui.tr("Off") : Ui.trf("{time} left", "time", SleepTimer.duration(sleepLeft)); break;
                    case P_SHOW: out[i] = onOff(Prefs.sleepShown()); break;
                    default: out[i] = SleepOverlay.placeName(Prefs.sleepPlace());
                }
            }
            return out;
        }

        /** OK on a Sleep Timer row: its list of choices, or (on a switch) the other value. */
        private void activatePower() {
            Context c = getContext();
            List<String[]> list = new ArrayList<>();
            switch (powerRow()) {
                case P_SLEEP:
                    list.add(new String[] {"0", Ui.tr("Off")});
                    for (int m : new int[] {5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240}) {
                        list.add(new String[] {String.valueOf(m), SleepTimer.duration(m)});
                    }
                    int left = SleepTimer.minutesLeft(c);
                    openChoices(PICK_SLEEP, list, String.valueOf(left));
                    return;
                case P_WHERE:
                    for (int i = 0; i < SleepOverlay.PLACES; i++) list.add(new String[] {String.valueOf(i), SleepOverlay.placeName(i)});
                    openChoices(PICK_PLACE, list, String.valueOf(Prefs.sleepPlace()));
                    return;
                default:
                    adjust(1);
            }
        }

        private void openChoices(int which, List<String[]> list, String now) {
            choices.clear();
            choices.addAll(list);
            choiceNow = now;
            openPicker(which);
        }

        /** OK in a Sleep Timer picker: applied, and back to its row. */
        private void pickPower() {
            Context c = getContext();
            String[] choice = choices.get(pickScroll + focus);
            int row = pickFor == PICK_SLEEP ? P_SLEEP : P_WHERE;
            if (pickFor == PICK_SLEEP) {
                SleepTimer.set(c, Integer.parseInt(choice[0]));
                sleepLeft = SleepTimer.minutesLeft(c);
            } else {
                Prefs.setSleepPlace(Integer.parseInt(choice[0]));
                SleepOverlay.update(c);
            }
            pickScroll = 0;
            screen = POWER;
            focus = powerFocus(row);
        }

        private String[] listLabels() {
            if (screen == REMOTE_KEYS) return keyLabels();
            if (screen == POWER) return powerLabels();
            String[] out = new String[rows.length];
            for (int i = 0; i < out.length; i++) out[i] = rowName(rows[i]);
            return out;
        }

        private String[] listValues() {
            if (screen == REMOTE_KEYS) return keyValues();
            if (screen == POWER) return powerValues();
            String[] out = new String[rows.length];
            for (int i = 0; i < out.length; i++) out[i] = rowState(rows[i]);
            return out;
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("On") : Ui.tr("Off");
        }

        private String[] keyLabels() {
            if (RemoteKeys.HISENSE) return new String[] {Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Disney+ button"), Ui.tr("All Apps button"), Ui.tr("Settings button"), Ui.tr("Profile button")};
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Tabii button")};
            if (RemoteKeys.XIAOMI) return new String[] {Ui.tr("Mi button"), Ui.tr("YouTube button"), Ui.tr("PatchWall button"),
                    Ui.tr("Okko button"), Ui.tr("VK button"), Ui.tr("Rutube button"), Ui.tr("KION button"),
                    Ui.tr("Wink button"), Ui.tr("IVI button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("BRAVIA CORE button"),
                    Ui.tr("Netflix button"), Ui.tr("Disney+ button"),
                    Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Crunchyroll button"), Ui.tr("Google Play button")};
            return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                    Ui.tr("Prime Video button"), Ui.tr("TCL Channel button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony and Xiaomi, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Sony default") : RemoteKeys.XIAOMI ? Ui.tr("Xiaomi default") : Ui.tr("Nothing"));
            // On Xiaomi the Mi button is the remote's Home button: it can open this home screen.
            if (RemoteKeys.XIAOMI) {
                appPkgs.add(HomeMode.PKG);
                appLabels.add("mahitTV");
            }
            if (RemoteKeys.HISENSE) {
                appPkgs.add(Prefs.ALL_APPS);
                appLabels.add(Ui.tr("All Apps"));
            }
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            // Refused or without mahitTV mode, Now Playing can still be turned off (and the refusal is then forgotten).
            boolean turnOff = screen == SECTION && rows[pickScroll + focus] == R_PLAYING && Prefs.nowPlaying();
            if (unavailable(focus) != null && !turnOff) return;
            if (screen == POWER) {
                int row = powerRow();
                if (row == P_SLEEP) {
                    SleepTimer.step(getContext(), dir);
                    sleepLeft = SleepTimer.minutesLeft(getContext());
                } else if (row == P_SHOW) {
                    Prefs.setSleepShown(!Prefs.sleepShown());
                    SleepOverlay.update(getContext());
                } else if (row == P_WHERE) {
                    Prefs.setSleepPlace((Prefs.sleepPlace() + dir + SleepOverlay.PLACES) % SleepOverlay.PLACES);
                    SleepOverlay.update(getContext());
                }
                focus = powerFocus(row);   // rows come and go with the switches above them
                return;
            }
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            if (screen != SECTION) return;
            int id = rows[pickScroll + focus];
            if (id >= R_LIGHT) return;   // read only
            if (id >= R_COIN) {
                if (!Prefs.quotes()) return;
                Prefs.setCoin(id - R_COIN, !Prefs.coin(id - R_COIN));
                HomeData.get(getContext()).settingsChanged();
                return;
            }
            if (id >= R_OTHER) {   // ← → pick what OK does to that profile's PIN
                ProfileManager.Profile p = other(id);
                if (ProfileManager.needsPin(getContext(), p) && !removePin.remove(p.id)) removePin.add(p.id);
                return;
            }
            switch (id) {
                case R_TEMPERATURE: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case R_THEME: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    SleepOverlay.update(getContext());   // the pill's glass follows
                    break;
                }
                case R_WALLPAPER: return;
                case R_PHOTOS:
                    if (Photos.files(getContext()).isEmpty()) return;
                    Prefs.setPhotos(!Prefs.photos());
                    return;
                case R_INTRO: Prefs.setIntro(!Prefs.intro()); return;
                case R_SOUNDS: Prefs.setSounds(!Prefs.sounds()); break;
                case R_SAVER: Prefs.setSaverWatch(!Prefs.saverWatch()); break;
                case R_WEATHER: Prefs.setWeather(!Prefs.weather()); break;
                case R_FORECAST:   // both are in memory: nothing to fetch
                    Prefs.setForecast(Prefs.forecast().equals("days") ? "hours" : "days");
                    return;
                case R_PLAYING:   // the listener follows the widget: allowed while it's on, taken back when it goes off
                    Prefs.setNowPlaying(!Prefs.nowPlaying());
                    HomeMode.nowPlayingAccess(getContext());
                    break;
                case R_QUOTES: Prefs.setQuotes(!Prefs.quotes()); break;
                case R_SYSTEM_APPS:
                    Prefs.setSystemApps(!Prefs.systemApps());
                    appCount = -1;
                    countApps();
                    return;
                default:
                    return;   // OK opens it, or it only reads
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void askCity() {
            if (Ui.tr("Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        // MARK: - My Photos (sent from a computer: tools/photos.py)

        /** OK on My Photos: asks, then deletes them all. */
        private void askRemovePhotos() {
            int n = Photos.files(getContext()).size();
            if (n == 0) return;
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.plural("plural|one:Remove {n} photo?|other:Remove {n} photos?", n))
                    .setPositiveButton(Ui.tr("Remove"), (d, which) -> {
                        Photos.removeAll(getContext());
                        invalidate();
                        spoken.focusMoved();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();   // one stray OK keeps them
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.trf("Couldn't search now: {error}", "error", e.getMessage());
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == SECTION && section == S_LOCATION) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT || screen == SECTION;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : screen == SECTION ? rows.length : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor(boolean top) {
            editingTop = top;
            shelfOrder.clear();
            if (top) {
                shelfOrder.addAll(HomeApps.topButtonKeys());
                rebuildShelfRows(HomeApps.topCandidates());
            } else {
                // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the
                // limit) without a row to take it off.
                PackageManager pm = getContext().getPackageManager();
                for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
                rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            }
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            if (editingTop) Prefs.setTopButtons(shelfOrder); else Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (editingTop || shelfOrder.size() < HomeApps.maxShelf(getContext())) shelfOrder.add(pkg);   // three buttons: no limit to reach
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            c.drawText(editingTop ? Ui.tr("Top Buttons") : Ui.tr("Dock"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                boolean off = unavailable(i) != null;
                rowLabel.setColor(off ? blend(0x73FFFFFF, 0x731C1C1E, f)
                        : blend(place >= 0 ? 0xF2FFFFFF : 0x80FFFFFF, place >= 0 ? INK : 0x801C1C1E, f));
                rowValue.setColor(off ? blend(0x59FFFFFF, 0x591C1C1E, f) : blend(0xB3FFFFFF, 0xB31C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (place >= 0 && !off) {
                    // On the shelf: a check, as in the pickers, after its place.
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", right - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                    right -= cw + 16 * k;
                }
                c.drawText(off ? Ui.tr("Unavailable") : place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off"), right, base, rowValue);
            }
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            if (!editingTop && HomeApps.lowRam(getContext())) {
                c.drawText(Ui.tr("This TV has little memory, so the Dock holds fewer apps."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
            c.drawText(editingTop ? Ui.tr("OK adds or removes a button. ← → change the order.")
                    : HomeApps.maxShelf(getContext()) == Integer.MAX_VALUE ? Ui.tr("OK adds or removes an app. ← → change the order.")
                    : Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", HomeApps.maxShelf(getContext())),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else if (which == PICK_LANGUAGE) {
                String[] choices = languageChoices();
                for (String code : choices) {
                    pickLabels.add(code.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(code));
                }
                pickCurrent = java.util.Arrays.asList(choices).indexOf(Prefs.language());
            } else if (which <= PICK_SLEEP) {
                pickCurrent = -1;
                for (int i = 0; i < choices.size(); i++) {
                    pickLabels.add(choices.get(i)[1]);
                    if (choices.get(i)[0].equals(choiceNow)) pickCurrent = i;
                }
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            String title = pickFor == PICK_CITY ? Ui.tr("City")
                    : pickFor == PICK_LANGUAGE ? Ui.tr("Language")
                    : pickFor == PICK_SLEEP ? Ui.tr("Sleep Timer") : pickFor == PICK_PLACE ? Ui.tr("Position")
                    : keyLabels()[pickFor - 1];
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                boolean off = unavailable(i) != null;
                rowLabel.setColor(off ? blend(0x73FFFFFF, 0x731C1C1E, f) : blend(0xF2FFFFFF, INK, f));
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (off) {
                    rowValue.setColor(blend(0x59FFFFFF, 0x591C1C1E, f));
                    c.drawText(Ui.tr("Unavailable"), rowRect.right - 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowValue);
                } else if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void openWallpaperPicker() {
            String category = Wallpaper.categoryOf(Prefs.wallpaper());
            wallpaperCategory = 0;
            for (int i = 0; i < wallpaperCategories.length; i++) if (wallpaperCategories[i].equals(category)) wallpaperCategory = i;
            wallpaperOptions = Wallpaper.options(wallpaperCategories[wallpaperCategory]);
            wallpaperOption = 0;
            for (int i = 0; i < wallpaperOptions.length; i++) if (wallpaperOptions[i].equals(Prefs.wallpaper())) wallpaperOption = i;
            wallpaperOptionsPane = false;
            screen = WALLPAPER_PICKER;
            focus = wallpaperCategory;
            refreshWallpaperPreview();
        }

        private void moveWallpaper(int direction) {
            if (wallpaperOptionsPane) {
                wallpaperOption = Math.max(0, Math.min(wallpaperOptions.length - 1, wallpaperOption + direction));
                focus = wallpaperOption;
            } else {
                wallpaperCategory = Math.max(0, Math.min(3, wallpaperCategory + direction));
                wallpaperOptions = Wallpaper.options(wallpaperCategories[wallpaperCategory]);
                wallpaperOption = 0;
                focus = wallpaperCategory;
            }
            invalidate();
            spoken.focusMoved();
            refreshWallpaperPreview();
        }

        private void refreshWallpaperPreview() {
            final String id = wallpaperOptions[wallpaperOption];
            wallpaperPreviewBitmap = null;
            Wallpaper.Aerial aerial = Wallpaper.find(id);
            if (aerial == null) { wallpaperPreviewState = "live:aerial".equals(id) ? Wallpaper.PickerState.PREVIEW_LIVE_FALLBACK : Wallpaper.PickerState.PREVIEW_HORIZON; return; }
            wallpaperPreviewState = Wallpaper.PickerState.PREVIEW_LOADING;
            final String url = aerial.preview;
            new Thread(() -> {
                Bitmap bitmap = null;
                try { InputStream stream = new URL(url).openStream(); bitmap = BitmapFactory.decodeStream(stream); stream.close(); } catch (Exception ignored) {}
                final Bitmap ready = bitmap;
                handler.post(() -> {
                    if (screen != WALLPAPER_PICKER || !id.equals(wallpaperOptions[wallpaperOption])) return;
                    wallpaperPreviewBitmap = ready;
                    wallpaperPreviewState = ready == null ? Wallpaper.PickerState.PREVIEW_ERROR : Wallpaper.PickerState.PREVIEW_READY;
                    invalidate();
                });
            }, "wallpaper-preview").start();
        }

        private static String wallpaperCategoryName(String category) {
            if ("horizon".equals(category)) return "Apple Frosted Horizon";
            if ("aerial".equals(category)) return "Apple 4K Aerials";
            if ("studio".equals(category)) return "Apple Studio Solids";
            return "Live Aerial Screensaver";
        }

        private String wallpaperOptionName(String id) {
            if ("horizon:auto".equals(id)) return Ui.tr("Automatic");
            if ("horizon:light".equals(id)) return Ui.tr("Light");
            if ("horizon:dark".equals(id)) return Ui.tr("Dark");
            Wallpaper.Aerial aerial = Wallpaper.find(id);
            if (aerial != null) return aerial.title;
            if ("live:aerial".equals(id)) return "Live Aerial Screensaver";
            return id.substring("studio:".length()).replace('-', ' ');
        }

        private void drawWallpaperPicker(Canvas c) {
            c.drawText(Ui.tr("Wallpaper"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            float left = LIST_X * k, middle = 1335 * k, right = LIST_R * k, top = (LIST_TOP + 20) * k, pitch = 74 * k;
            for (int i = 0; i < wallpaperCategories.length; i++) {
                rowRect.set(left, top + i * pitch, middle - 16 * k, top + i * pitch + 66 * k);
                boolean selected = !wallpaperOptionsPane && i == wallpaperCategory;
                c.drawRoundRect(rowRect, 18 * k, 18 * k, selected ? white : glass);
                rowLabel.setColor(selected ? INK : 0xF2FFFFFF);
                c.drawText(wallpaperCategoryName(wallpaperCategories[i]), rowRect.left + 22 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
            }
            for (int i = 0; i < wallpaperOptions.length && i < 7; i++) {
                rowRect.set(middle, top + i * pitch, right, top + i * pitch + 66 * k);
                boolean selected = wallpaperOptionsPane && i == wallpaperOption;
                c.drawRoundRect(rowRect, 18 * k, 18 * k, selected ? white : glass);
                rowLabel.setColor(selected ? INK : 0xF2FFFFFF);
                c.drawText(wallpaperOptionName(wallpaperOptions[i]), rowRect.left + 22 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
            }
            tmp.set(590 * k, 585 * k, 1230 * k, 945 * k);
            String chosen = wallpaperOptions[wallpaperOption];
            wallpaperPreviewPaint.setColor(0xffb8c7d6);
            if (chosen.startsWith("studio:")) wallpaperPreviewPaint.setColor(Wallpaper.solidColor(chosen));
            c.drawRoundRect(tmp, 22 * k, 22 * k, wallpaperPreviewPaint);
            if (wallpaperPreviewBitmap != null) c.drawBitmap(wallpaperPreviewBitmap, null, tmp, wallpaperPreviewPaint);
            if (wallpaperPreviewState == Wallpaper.PickerState.PREVIEW_LOADING) c.drawText("Loading Apple preview…", tmp.left + 22 * k, tmp.bottom - 22 * k, hint);
            else if (wallpaperPreviewState == Wallpaper.PickerState.PREVIEW_ERROR) c.drawText("Apple preview unavailable · Frosted Horizon", tmp.left + 22 * k, tmp.bottom - 22 * k, hint);
            else if (wallpaperPreviewState == Wallpaper.PickerState.PREVIEW_LIVE_FALLBACK) c.drawText("Live aerial available on the Home screen", tmp.left + 22 * k, tmp.bottom - 22 * k, hint);
            c.drawText("OK applies immediately · Back returns to Appearance", LIST_X * k + 30 * k, 1000 * k, hint);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS, sec = screen == SECTION;
            String[] labels = listLabels();
            String[] values = listValues();
            c.drawText(keys ? Ui.tr("Remote Buttons") : screen == POWER ? Ui.tr("Sleep Timer") : sectionName(section),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            float pitch = LIST_ROW_H + LIST_ROW_GAP;
            int shown = keys ? labels.length : rowCount(), from = keys ? 0 : pickScroll;
            for (int i = 0; i < shown; i++) {
                int id = sec ? rows[from + i] : 0;
                float y = (LIST_TOP + 20 + i * pitch) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                boolean locked = unavailable(i) != null;
                if (locked) values[from + i] = Ui.tr("Unavailable");
                rowValue.setColor(blend(locked ? 0x59FFFFFF : 0xB3FFFFFF, locked ? 0x591C1C1E : 0xB31C1C1E, f));
                if (locked) rowLabel.setColor(blend(0x73FFFFFF, 0x731C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel), left = rowRect.left + 30 * k;
                if (id == R_PROFILE && avatar != null) {   // who's here: their avatar before their name
                    c.drawBitmap(avatar, left, rowRect.centerY() - avatar.getHeight() / 2f, null);
                    left += avatar.getWidth() + 18 * k;
                }
                c.drawText(labels[from + i], left, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (sec && !locked && opens(id)) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                    right -= cw + 16 * k;
                }
                if (id == R_FORGET && armed == R_FORGET || (id == R_RESET || id == R_RESTORE) && armed == id) {
                    rowValue.setColor(blend(AMBER, 0xFFB25000, f));
                }
                c.drawText(values[from + i], right, base, rowValue);
            }
            float hy = (LIST_TOP + 20 + shown * pitch + 36) * k;
            // A list longer than the screen says where we are in it, as the app picker does. In a section, what the
            // focused row does (wrapped: some explain a reset); on the Sleep Timer screen, the timer and Position open a
            // list on OK.
            String where = shown < labels.length ? (from + focus + 1) + " / " + labels.length + "  ·  " : "";
            String tip = where + (sec ? sectionHint(rows[from + focus])
                    : screen == POWER && powerRow() != P_SHOW && unavailable(focus) == null ? Ui.tr("OK opens the list. ← → change the value.")
                    : Ui.tr("OK or the arrows change the value."));
            int width = Math.round(764 * k);
            if (hintLayout == null || !tip.equals(hintShown) || hintLayout.getWidth() != width) {
                hintShown = tip;
                hintLayout = StaticLayout.Builder.obtain(tip, 0, tip.length(), new TextPaint(hint), width)
                        .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            }
            c.save();
            c.translate(LIST_X * k + 30 * k, hy - hintLayout.getLineBaseline(0));
            hintLayout.draw(c);
            c.restore();
            if (keys) {
                c.drawText(RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                        : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                        : RemoteKeys.SONY ? Ui.tr("Sony default: the button opens what it always did.")
                        : RemoteKeys.FORMOVIE ? Ui.tr("The other buttons: the TV itself controls them.")
                        : RemoteKeys.XIAOMI ? Ui.tr("Xiaomi default: the button opens what it always did.")
                        : RemoteKeys.HISENSE ? Ui.tr("Netflix: the TV itself controls that button.")
                        : RemoteKeys.supported() ? Ui.tr("Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("TCL remote buttons: on this TV they work as always."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
        }

        private void activate() {
            Context c = getContext();
            if (tellWhy()) return;
            if (screen == PIN_PAD) {
                typeDigit(focus);
                invalidate();
                spoken.focusMoved();
                return;
            }
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : originalNow();
                if (!already && !busy() && !wantFree) {
                    askPin(PIN_MANAGER, THEN_ORIGINAL, ProfileManager.manager(c), R_HOME);   // Original: the manager's call
                } else {
                    if (!already && !busy()) {
                        HomeMode.request(c, HomeMode.FREE);
                        pendingAt = SystemClock.uptimeMillis();
                    }
                    openSection(S_SYSTEM, R_HOME);
                }
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                openSection(S_LOCATION, R_CITY);
            } else if (screen == WALLPAPER_PICKER) {
                Prefs.setWallpaper(wallpaperOptions[wallpaperOption]);
                HomeData.get(c).settingsChanged();
            } else if (screen == PICKER && pickFor <= PICK_SLEEP) {
                pickPower();
            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[pickScroll + focus]);
                SleepOverlay.update(c);   // the pill speaks it too
                openSection(S_SYSTEM, R_LANGUAGE);
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == POWER) {
                activatePower();
            } else if (screen == SECTION) {
                if (!activateRow(rows[pickScroll + focus])) {
                    invalidate();
                    spoken.focusMoved();   // the value may have changed: TalkBack reads it again
                    return;
                }
            } else {
                openSection(focus, -1);
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
