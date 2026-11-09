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
import android.text.InputType;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.net.URL;
import java.io.InputStream;

/**
 * mahitTV Settings (the app's icon, "All Apps", or the gear on the home screen), as tvOS draws its Settings: two panes
 * over the Frosted Horizon. On the left the section's emblem (a glass squircle with its symbol), its title and one line
 * on what it holds, then the seven sections of MAHITTV-PROFILES-SPEC.md §6 as capsules; on the right the section's
 * rows in grouped inset containers, with switches for what turns on and off and a list with a check for what picks one
 * of a few. ← → move between the panes, Back goes back a level. "Home Screen" (System & Original TV) opens the
 * mahitTV / Original choice (with a check on the current one); the switch runs in the main process (HomeMode) and this
 * screen only asks for it and follows the state file. Original, and both resets, ask for the manager's PIN first.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting
    private boolean previewOnly;   // opened only for the preview (Control Center): closing it closes Settings
    private boolean backToHome;   // opened from the app's icon in Original mode (onBackPressed)

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
        noteIcon(getIntent());
    }

    private void noteIcon(Intent intent) {
        org.json.JSONObject s = HomeMode.state(this);
        boolean free = HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted");
        backToHome = fromIcon(intent) && !free && !HomeMode.phone(this);
    }

    private static boolean fromIcon(Intent intent) {
        return intent != null && (intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) || intent.hasCategory(Intent.CATEGORY_LAUNCHER));
    }

    /**
     * In Original mode the icon opens only these settings and Home goes to the stock screen, so Back is the way into
     * mahitTV's home screen. Not for a visit that began in mahitTV mode: a switch to Original there asks for the stock one.
     */
    @Override public void onBackPressed() {
        if (backToHome) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }
        super.onBackPressed();
    }

    /**
     * Fire TV: Amazon keeps the Home button, so the app's icon opens the home screen (FireHomeActivity) directly;
     * the gear on the home screen opens these settings (no launcher category).
     */
    private boolean openFireHome(Intent intent) {
        if (!HomeMode.AMAZON || !fromIcon(intent) || intent.getBooleanExtra("screensaver", false)) return false;
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
            previewOnly = true;   // Control Center's Screensaver card: closing it goes back home, not to Settings
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openFireHome(intent)) return;
        setIntent(intent);
        noteIcon(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        if (view != null) view.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (view != null) view.destroy();
        super.onDestroy();
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

    /** Settings Style changed: Settings opens again in it (the sidebar over the home screen, or full screen). */
    void restyle() {
        Intent next = new Intent(this, "sidebar".equals(Prefs.settingsStyle()) ? SettingsSidebar.class : SettingsActivity.class);
        startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
        overridePendingTransition(0, 0);
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
        if (previewOnly) {
            previewOnly = false;
            finish();
            return;
        }
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, SECTION = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5,
                POWER = 6, PIN_PAD = 7, WALLPAPER_PICKER = 8, CLEAN = 9, TMDB_KEY = 10, PICK_FROM = 11;
        // tvOS Settings' two panes (px at 1920 × 1080). Left, 96–664: the emblem (a 160 px glass squircle), the title
        // and the description, then the sections as 64 px capsules. Right, 744–1824: the title, then the rows (64 tall)
        // in grouped inset containers with an uppercase header above and a footer under; a long section scrolls.
        // The Floating Sidebar (Appearance & Startup → Settings Style, SettingsSidebar): one column in a glass panel at
        // x 1080–1840, y 124–1040, over the home screen, which stays behind it; the sections (MAIN), or a section's rows.
        // The panes' geometry is set for the style in the constructor.
        private float LEFT_X = 96, LEFT_R = 664, LEFT_CX = 380, EMBLEM = 160, EMBLEM_TOP = 112, PILL_TOP = 486;
        private float LIST_X = 744, LIST_R = 1824, TITLE_BASE = 150, CONTENT_TOP = 196, CONTENT_BOTTOM = 1048;
        private static final float PILL_H = 64, PILL_PITCH = 72,
                ROW_H = 64, HEADER_H = 46, FOOTER_GAP = 12, GROUP_GAP = 30, INSET = 28, RADIUS = 16;
        private static final float SIDE_L = 1080, SIDE_T = 124, SIDE_R = 1840, SIDE_B = 1040, SIDE_RADIUS = 40;
        private final boolean sidebar;
        private final RectF sidePanel = new RectF();
        private final Paint sideFill = new Paint(Paint.ANTI_ALIAS_FLAG), sideRim = new Paint(Paint.ANTI_ALIAS_FLAG), sideVeil = new Paint();
        /** Rows on screen at once in the pushed lists (apps, cities, languages); a longer list scrolls. */
        private static final int PICK_VISIBLE = 9;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E, GREEN = 0xFF34C759;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        /** A slate tint over the Frosted Horizon, so the white text stays clear on the bright day sky (web: .sheet). */
        private final Paint tint = new Paint();
        /** The Horizon is its twilight (night): the system dialogs follow it too (light by day). */
        private boolean dark;
        private final Handler handler = new Handler(Looper.getMainLooper());
        // Each row's focus: its white platter (or capsule) lifting in. As many as the longest section has rows.
        private final Spring[] pop = new Spring[40];
        /** Each switch's knob (0 off, 1 on), on SNAPPY_FOCUS; snapped when another list comes in. */
        private final Spring[] knobs = new Spring[40];
        /** The open section's scroll (px at 1080p): the focused row, and its group's header, stay in view. */
        private final Spring scroll = new Spring(Spring.SNAPPY_FOCUS, 0).px();
        // The ink and the glass for the Horizon behind: dark ink on white glass by day, white ink on dark glass at night.
        private int primary, secondary, tertiary, groupColor, rimColor, separatorColor, currentPill, warnColor, trackOff;
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor (also the top buttons' one, with editingTop): what it can hold (selected ones first, in
        // order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private boolean editingTop;
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0, PICK_LANGUAGE = -1, PICK_SLEEP = -2, PICK_PLACE = -3, PICK_ENUM = -4;
        /** PICK_ENUM's row: the setting (or the profile's PIN) its list picks for. */
        private int enumRow;
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
        private final WallpaperPicker wallpaperPicker;
        private final AerialCache aerialPreviewCache;
        private String[] wallpaperCategoryLabels = new String[0], wallpaperOptionLabels = new String[0];
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
        private long requestedAt;   // the switch asked for: its stamp, to tell whether its job ever started
        private boolean allowingBoot;   // TCL's background permission on its way, before the switch's request
        private boolean zoneWrong;      // the TV's time zone can't be the city's (TimeCheck)
        private String zoneName = "";
        private boolean stalled;    // it didn't: a TV maker's background blocker (stalledText says what to do)
        private final boolean phone;   // installed on a phone: the settings say where it goes (HomeMode.phone)
        private final List<String[]> services = new ArrayList<>();   // {key, name} of the streaming services on this TV
        private boolean someServicesOff;
        private static final float QR_SIDE = 300;
        private static final int K_NEW = 0, K_TYPE = 1, K_REMOVE = 2;
        private KeyPage keyPage;
        private boolean keyWatchmode;   // the screen is the Watchmode key's, else TMDB's
        private Bitmap keyQr;
        private boolean keyChecking, keyStatusError;
        private int[] keyRowIds = new int[0];
        private String keyStatus, keyPurpose;
        private StaticLayout keySteps;
        private String keyHelp;   // the purpose and the steps, for TalkBack
        private final Paint qrPaint = new Paint();
        private final RectF keyQrRect = new RectF();
        private int w, h;
        private float k = 1;
        private Paint title, paneTitle, version, pillLabel, rowLabel, rowValue, header, metricLabel, metricValue, warn, spinner,
                white, shadow, groupFill, groupRim, separator, track, knob, knobShadow, emblemFill, emblemSheen, ring, digit,
                previewPaint;
        private TextPaint footerPaint, describePaint, warnText, notice, measure;
        private final android.graphics.Path boxPath = new android.graphics.Path(), emblemPath = new android.graphics.Path();
        private final RectF pillRect = new RectF(), switchRect = new RectF();
        private String versionText = "mahitTV";

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                sleepLeft = SleepTimer.minutesLeft(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                java.util.TimeZone zone = java.util.TimeZone.getDefault();
                zoneName = zone.getID().replace('_', ' ');
                zoneWrong = TimeCheck.zoneLooksWrong(zone.getOffset(System.currentTimeMillis()) / 60_000, Prefs.longitude());
                // The TV's own accessibility settings, shown in Appearance (read here, not on every frame).
                android.content.ContentResolver cr = getContext().getContentResolver();
                reduceMotion = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
                highContrast = Settings.Secure.getInt(cr, "high_text_contrast_enabled", 0) == 1;
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) {
                    pendingAt = 0;
                    requestedAt = 0;
                    stalled = false;
                } else if (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt >= 10_000) {
                    // Asked 10 s ago and its job never ran: say why, rather than quietly showing Original again (#364).
                    pendingAt = 0;
                    if (requestedAt != 0 && !ModeJob.started(getContext(), requestedAt)) {
                        stalled = true;
                        AppLog.w("the switch's job didn't start" + (RemoteKeys.TCL ? " (TCL's AUTO_START not allowed?)" : ""));
                        announceForAccessibility(stalledText());
                    }
                    requestedAt = 0;
                }
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
            phone = HomeMode.phone(a);
            sidebar = a instanceof SettingsSidebar;
            if (sidebar) {
                LEFT_X = 1128;
                LEFT_R = 1792;
                LEFT_CX = 1460;
                EMBLEM = 120;
                EMBLEM_TOP = 164;
                PILL_TOP = 470;
                LIST_X = 1128;
                LIST_R = 1792;
                TITLE_BASE = 214;
                CONTENT_TOP = 256;
                CONTENT_BOTTOM = 1016;
                PAD_KEY = 58;
                PAD_GAP = 7;
            }
            PAD_X = LIST_X + (LIST_R - LIST_X - 10 * PAD_KEY - 9 * PAD_GAP) / 2;
            aerialPreviewCache = new AerialCache(new File(a.getCacheDir(), "aerial-previews"));
            wallpaperPicker = new WallpaperPicker((aerial, callback) -> aerialPreviewCache.fetch(aerial.preview, file -> {
                if (!callback.current()) return;
                if (file == null) { handler.post(() -> { if (callback.current()) callback.error(); }); return; }
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
                if (!callback.current()) { releasePreview(bitmap); return; }
                handler.post(() -> {
                    if (!callback.current()) releasePreview(bitmap);
                    else if (bitmap == null) callback.error(); else callback.ready(bitmap);
                    invalidate();
                });
            }), id -> { Prefs.setWallpaper(id); HomeData.get(getContext()).settingsChanged(); }, SettingsView::releasePreview);
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            for (int i = 0; i < knobs.length; i++) knobs[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
            versionText = versionName(a);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            dark = Wallpaper.dark(Prefs.wallpaper(), HomeData.get(a).isNight(System.currentTimeMillis()));
            scene.setHorizon(dark);
            // Apple's label colors (primary, secondary .6, tertiary .3) and grouped glass at .55.
            primary = dark ? 0xF2FFFFFF : 0xFF1D1D1F;
            secondary = dark ? 0x99EBEBF5 : 0x993C3C43;
            tertiary = dark ? 0x4DEBEBF5 : 0x4D3C3C43;
            groupColor = dark ? 0x8C1E1E23 : 0x8CFFFFFF;
            rimColor = dark ? 0x1FFFFFFF : 0xB3FFFFFF;
            separatorColor = dark ? 0x1FFFFFFF : 0x2E3C3C43;
            currentPill = dark ? 0x2EFFFFFF : 0x80FFFFFF;
            warnColor = dark ? AMBER : 0xFFB25000;
            trackOff = dark ? 0x52787880 : 0x29787880;
            // A light veil over the day sky; the twilight a touch deeper.
            tint.setColor(dark ? 0x2616212D : 0x1FFFFFFF);
            sideVeil.setColor(dark ? 0x52000000 : 0x38162130);
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
                    if (pinRemote) return status + ". " + Ui.tr("Use ↑ ↓ ← → on your remote");
                    return Ui.trf("Digit {n}", "n", row) + ", " + Ui.trf("{i} of 4 entered", "i", pinDigits.length())
                            + ". " + status;
                }
                case SECTION:
                case REMOTE_KEYS:
                case POWER: {
                    String[] labels = listLabels(), values = listValues();
                    int at = pickScroll + row;
                    return at < labels.length ? labels[at] + ", " + values[at] : "";
                }
                case TMDB_KEY:
                    if (row >= keyRowIds.length) return "";
                    return row == 0 && keyHelp != null ? keyLabel(keyRowIds[0]) + ". " + keyHelp : keyLabel(keyRowIds[row]);
                case PICK_FROM: {
                    int at = pickScroll + row;
                    if (at >= services.size()) return "";
                    return services.get(at)[1] + ", " + onOff(serviceOn(services.get(at)[0])) + ", "
                            + Ui.trf("{i} of {n}", "i", at + 1, "n", services.size());
                }
                case CLEAN: {
                    int at = pickScroll + row;
                    if (at >= cleanRowIds.length) return "";
                    String value = cleanValue(cleanRowIds[at]);
                    return value.isEmpty() ? cleanLabel(cleanRowIds[at]) : cleanLabel(cleanRowIds[at]) + ", " + value;
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
            if (screen == MAIN) {
                float top = PILL_TOP + row * PILL_PITCH;
                focusBox.set(LEFT_X * k, top * k, LEFT_R * k, (top + PILL_H) * k);
            } else if (screen == PIN_PAD && pinRemote) {
                float mid = (LIST_X + LIST_R) / 2f;
                focusBox.set((mid - 160) * k, (PAD_DOTS_Y - 30) * k, (mid + 160) * k, (PAD_DOTS_Y + 30) * k);
            } else if (screen == PIN_PAD) {
                float x = PAD_X + row * (PAD_KEY + PAD_GAP);
                focusBox.set(x * k, PAD_KEYS_TOP * k, (x + PAD_KEY) * k, (PAD_KEYS_TOP + PAD_KEY) * k);
            } else {
                float top = CONTENT_TOP + (screen != SECTION ? row * ROW_H : row < rowY.length ? rowY[row] - scroll.value : 0);
                focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + ROW_H) * k);
            }
            return focusBox;
        }

        /** The Sleep Timer screen, focused on its first row. */
        void openSleepTimer() {
            section = S_SYSTEM;   // its row's section: the left pane shows it, Back goes there
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
            if (keyPage != null) {   // the phone's page answers only while its code shows
                stopKeyPage();
                setKeyStatus(Ui.tr("This code has expired."), false);
                keyRowsChanged();
            }
        }

        void destroy() {
            stopKeyPage();
            wallpaperPicker.close();
            aerialPreviewCache.close();
        }

        private static void releasePreview(Object image) {
            if (image instanceof Bitmap) {
                Bitmap bitmap = (Bitmap) image;
                if (!bitmap.isRecycled()) bitmap.recycle();
            }
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy() || !shake.settled() || !scroll.settled();
            for (Spring s : pop) moving |= !s.settled();
            for (Spring s : dots) moving |= !s.settled();
            for (Spring s : knobs) moving |= !s.settled();
            // Only a new frame: the rows' text stays as it was made (invalidate() below makes it again).
            if (moving || SystemClock.uptimeMillis() - lastSky >= 100) super.invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        /** Something changed (a key, the state, an answer): the rows' text is made again before the next frame. */
        @Override public void invalidate() {
            cellsDirty = true;
            super.invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            cleanMeasure = null;   // the row text's size changed
            scene.setSize(w, h);
            Context c = getContext();
            for (int s = -1; s < SECTIONS; s++) {
                Symbols.warm(c, emblemSymbol(s), 84 * k);
                Symbols.warm(c, emblemSymbol(s), 30 * k);
            }
            Symbols.warm(c, "chevron_right", 22 * k);
            Symbols.warm(c, "chevron_down", 22 * k);
            Symbols.warm(c, "check", 26 * k);
            title = Ui.text(Ui.displaySemibold, 44 * k, primary);
            title.setTextAlign(Paint.Align.CENTER);
            title.setLetterSpacing(-0.02f);
            describePaint = new TextPaint(Ui.text(Ui.regular, 24 * k, secondary));
            version = Ui.text(Ui.regular, 22 * k, tertiary);
            version.setTextAlign(Paint.Align.CENTER);
            pillLabel = Ui.text(Ui.medium, 28 * k, primary);
            pillLabel.setLetterSpacing(-0.01f);
            paneTitle = Ui.text(Ui.displayBold, 46 * k, primary);
            paneTitle.setLetterSpacing(-0.02f);
            rowLabel = Ui.text(Ui.medium, 28 * k, primary);
            rowLabel.setLetterSpacing(-0.01f);
            rowValue = Ui.text(Ui.regular, 26 * k, secondary);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowValue.setLetterSpacing(-0.01f);
            header = Ui.text(Ui.semibold, 20 * k, secondary);
            header.setLetterSpacing(0.06f);
            footerPaint = new TextPaint(Ui.text(Ui.regular, 22 * k, secondary));
            warnText = new TextPaint(Ui.text(Ui.regular, 22 * k, warnColor));
            metricLabel = Ui.text(Ui.regular, 25 * k, primary);
            metricValue = Ui.numbers(Ui.regular, 25 * k, primary);
            metricValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 24 * k, warnColor);
            notice = new TextPaint(Ui.text(Ui.regular, 26 * k, primary));
            measure = new TextPaint(rowLabel);
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFFFFFFF);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(24 * k, BlurMaskFilter.Blur.NORMAL));
            groupFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            groupFill.setColor(groupColor);
            groupRim = new Paint(Paint.ANTI_ALIAS_FLAG);
            groupRim.setStyle(Paint.Style.STROKE);
            groupRim.setStrokeWidth(Math.max(1, k));
            groupRim.setColor(rimColor);
            separator = new Paint();
            separator.setColor(separatorColor);
            track = new Paint(Paint.ANTI_ALIAS_FLAG);
            knob = new Paint(Paint.ANTI_ALIAS_FLAG);
            knob.setColor(0xFFFFFFFF);
            knobShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            knobShadow.setColor(0x2E000000);
            emblemFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            emblemFill.setColor(dark ? 0x592C2C33 : 0xB3FFFFFF);
            emblemSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            emblemSheen.setShader(new android.graphics.LinearGradient(0, 0, 0, EMBLEM * k, dark ? 0x40FFFFFF : 0x99FFFFFF,
                    0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            Corners.continuous(emblemPath, EMBLEM * k, EMBLEM * k, 36 * k);
            ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3 * k);
            digit = Ui.numbers(Ui.medium, 34 * k, primary);
            digit.setTextAlign(Paint.Align.CENTER);
            previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            java.util.Arrays.fill(footers, null);   // made again at this size
            descLayout = null;
            snapScroll = true;
            invalidate();
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
                case SECTION: return rows.length;   // all of them: the section scrolls by pixels, not rows
                case PIN_PAD: return pinRemote ? 1 : 10;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case WALLPAPER_PICKER: return wallpaperPicker.draw().optionsPane ? wallpaperPicker.draw().options.length : wallpaperPicker.draw().categories.length;
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                case POWER: return 1;   // powerRows()' length, without making it
                case CLEAN: return Math.min(SETTINGS_VISIBLE, cleanRowIds.length);
                case TMDB_KEY: return keyRowIds.length;
                case PICK_FROM: return Math.min(PICK_VISIBLE, services.size());
                default: return Math.min(SETTINGS_VISIBLE, keyRowCount());
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            int n = rowCount();
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < n ? 1 : 0;
                pop[i].step(dt);
            }
            for (Spring s : knobs) s.step(dt);
            scroll.step(dt);
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            if (sidebar) {
                // The home screen shows through the window, a little dimmed; the panel is frosted (a flat glass: a
                // translucent window can't read what's under it).
                c.drawRect(0, 0, w, h, sideVeil);
                sidePanel.set(SIDE_L * k, SIDE_T * k, SIDE_R * k, SIDE_B * k);
                sideFill.setColor(dark ? 0xF21E2330 : 0xF2F6F7FA);
                c.drawRoundRect(sidePanel, SIDE_RADIUS * k, SIDE_RADIUS * k, sideFill);
                sideRim.setStyle(Paint.Style.STROKE);
                sideRim.setStrokeWidth(Math.max(1, k));
                sideRim.setColor(dark ? 0x24FFFFFF : 0x8CFFFFFF);
                c.drawRoundRect(sidePanel, SIDE_RADIUS * k, SIDE_RADIUS * k, sideRim);
            } else {
                scene.drawBackground(c);
                c.drawRect(0, 0, w, h, tint);
            }
            if (screen == CLEAN) syncClean();
            if (cellsDirty) refreshCells();
            if (!sidebar || screen == MAIN) drawLeftPane(c);
            if (sidebar && screen == MAIN) return;   // the sidebar: the sections alone, a section's rows on their own
            if (screen == MAIN) drawOverview(c, seconds);
            else if (screen == WALLPAPER_PICKER) drawWallpaperPicker(c);
            else if (screen == PIN_PAD) drawPinPad(c, dt);
            else if (screen == SECTION) drawSection(c, seconds);
            else drawPushed(c, seconds);
        }

        // MARK: - The left pane: the emblem, the title and the description, then the sections

        /** Section `s`'s symbol; -1 (the root) the gear. */
        private static String emblemSymbol(int s) {
            switch (s) {
                case S_PROFILES: return "lock";
                case S_APPEARANCE: return "sparkles";
                case S_LOCATION: return "partly_cloudy_day";
                case S_SMART_HOME: return "home";
                case S_WELLY: return "mic";
                case S_SYSTEM: return "tv_settings";
                case S_RESET: return "sync";
                default: return "settings";
            }
        }

        /** What a section holds, in one line under its title; -1 (the root) the whole app. */
        private static String sectionDescription(int s) {
            switch (s) {
                case S_PROFILES: return Ui.tr("Who's watching, their PINs and their Dock.");
                case S_APPEARANCE: return Ui.tr("Theme, wallpaper, screensaver and the startup.");
                case S_LOCATION: return Ui.tr("Your city, weather and the Top Shelf's picks.");
                case S_SMART_HOME: return Ui.tr("The Kasa lights the Home tab turns on and off.");
                case S_WELLY: return Ui.tr("Welly's brain and what it remembers.");
                case S_SYSTEM: return Ui.tr("Home screen, remote buttons and the TV itself.");
                case S_RESET: return Ui.tr("Start over, or bring back the TV's own home screen.");
                default: return Ui.tr("Your TV's home screen and screensaver");
            }
        }

        private StaticLayout descLayout;
        private String descShown;

        private void drawLeftPane(Canvas c) {
            Context ctx = getContext();
            if (screen == WALLPAPER_PICKER) {
                drawWallpaperPreview(c);
            } else {
                int s = screen == MAIN ? -1 : section;   // the root shows the gear, a section its own symbol
                c.save();
                c.translate((LEFT_CX - EMBLEM / 2) * k, EMBLEM_TOP * k + 10 * k);
                shadow.setColor(0x33000000);
                c.drawPath(emblemPath, shadow);
                c.translate(0, -10 * k);
                c.drawPath(emblemPath, emblemFill);
                c.drawPath(emblemPath, emblemSheen);
                c.drawPath(emblemPath, groupRim);
                c.restore();
                Symbols.draw(ctx, c, emblemSymbol(s), LEFT_CX * k, (EMBLEM_TOP + EMBLEM / 2) * k, 84 * k, primary);
                c.drawText(s < 0 ? Ui.tr("mahitTV Settings") : sectionName(s), LEFT_CX * k, 346 * k, title);
                String text = sectionDescription(s);
                int width = Math.round(540 * k);
                if (descLayout == null || !text.equals(descShown) || descLayout.getWidth() != width) {
                    descShown = text;
                    descLayout = StaticLayout.Builder.obtain(text, 0, text.length(), describePaint, width)
                            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setMaxLines(2)
                            .setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
                }
                c.save();
                c.translate(LEFT_CX * k - width / 2f, 394 * k - descLayout.getLineBaseline(0));
                descLayout.draw(c);
                c.restore();
            }
            // The sections: the focused one a white capsule lifted 1.04×; the open one (focus on the right) a faint one.
            for (int i = 0; i < SECTIONS; i++) {
                float top = (PILL_TOP + i * PILL_PITCH) * k, r = PILL_H / 2 * k;
                pillRect.set(LEFT_X * k, top, LEFT_R * k, top + PILL_H * k);
                float f = screen == MAIN ? Ui.clamp(pop[i].value) : 0;
                if (screen != MAIN && i == section) {
                    white.setColor(currentPill);
                    c.drawRoundRect(pillRect, r, r, white);
                }
                int save = c.save();
                if (f > 0.01f) {
                    float sc = 1 + 0.04f * f;
                    c.scale(sc, sc, pillRect.centerX(), pillRect.centerY());
                    pillRect.offset(0, 10 * k * f);
                    shadow.setColor(Ui.alpha(0xFF000000, 0.25f * f));
                    c.drawRoundRect(pillRect, r, r, shadow);
                    pillRect.offset(0, -10 * k * f);
                    white.setColor(Ui.alpha(0xFFFFFFFF, f));
                    c.drawRoundRect(pillRect, r, r, white);
                }
                int ink = blend(primary, INK, f);
                Symbols.draw(ctx, c, emblemSymbol(i), pillRect.left + 44 * k, pillRect.centerY(), 30 * k, ink);
                pillLabel.setColor(ink);
                c.drawText(sectionName(i), pillRect.left + 78 * k, pillRect.centerY() + Ui.halfCap(pillLabel), pillLabel);
                c.restoreToCount(save);
            }
            c.drawText(versionText, LEFT_CX * k, 1036 * k, version);
        }

        // MARK: - The root's right pane: which home screen, the comparison, the picks and the notices

        private String ovHome = "", ovRecs = "", ovCredit = "", ovFooter;
        private final String[] ovMetric = new String[6], ovNotice = new String[2];
        private int ovNotices;
        private static final String[] METRICS = {"homeMs", "memAvailableMB", "homePssMB"};

        /** The root's text, made when the state changes (not per frame). */
        private void refreshOverview() {
            boolean working = busy();
            String busyText = busyText();
            ovHome = fitValue(homeValue(), 620 * k);
            String error = phone ? HomeMode.phoneText() : stalled ? stalledText() : state.optString("error");
            if (working && busyText.equals(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."))) {
                ovFooter = Ui.tr("On the TV: tick “Always allow” and press OK.");
            } else {
                ovFooter = !working && !error.isEmpty() ? error : null;   // the whole message: some say what to do (#14)
            }
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            for (int i = 0; i < METRICS.length; i++) {
                ovMetric[2 * i] = metric(a, METRICS[i]);
                ovMetric[2 * i + 1] = metric(b, METRICS[i]);
            }
            String line;
            String recsError = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("No TMDB key: see the README (config.properties).");
            else if (recsError.equals(Recs.NO_KNOWN_APPS)) line = Ui.tr("None of the TV's streaming apps can give picks.");
            else if (recsError.equals(Recs.NO_APPS)) line = Ui.trf("TMDB has none of the TV's apps for tmdb_region={region}.", "region", Recs.REGION);
            else if (!recsError.isEmpty()) line = Ui.trf("Error: {error}.", "error", recsError);
            else if (count > 0) line = Ui.trf("On · {n} titles from the TV's apps, 3 new ones every hour.", "n", count);
            else line = Ui.tr("Fetching titles…");
            ovRecs = TextUtils.ellipsize(line, notice, (LIST_R - LIST_X - 2 * INSET) * k, TextUtils.TruncateAt.END).toString();
            ovCredit = Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB.")
                    + (Recs.watchmodeKey().isEmpty() ? "" : " " + Ui.tr("Links to the titles by Watchmode."));
            JSONArray notices = state.optJSONArray("notices");
            ovNotices = 0;
            for (int i = 0; notices != null && i < notices.length() && ovNotices < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String text = ago(o.optLong("at")) + " · " + o.optString("text");
                ovNotice[ovNotices++] = TextUtils.ellipsize(text, notice, (LIST_R - LIST_X - 2 * INSET) * k,
                        TextUtils.TruncateAt.END).toString();
            }
        }

        private void drawOverview(Canvas c, float seconds) {
            float x = (LIST_X + INSET) * k, right = (LIST_R - INSET) * k, row = ROW_H * k;
            c.drawText(versionText, LIST_X * k, TITLE_BASE * k, paneTitle);
            c.save();
            c.clipRect(0, (CONTENT_TOP - 24) * k, w, h);
            float y = CONTENT_TOP * k;
            // Which home screen; while it switches, its current step with the spinning arc.
            drawContainer(c, y, y + row);
            rowLabel.setColor(primary);
            float base = y + row / 2 + Ui.halfCap(rowLabel);
            c.drawText(Ui.tr("Home Screen"), x, base, rowLabel);
            rowValue.setColor(secondary);
            c.drawText(ovHome, right, base, rowValue);
            if (busy()) {
                spinner.setColor(primary);
                Ui.spinner(c, right - rowValue.measureText(ovHome) - 26 * k, y + row / 2, 12 * k, seconds * 1.3f, spinner);
            }
            y += row;
            if (ovFooter != null) y = drawFooter(c, SLOT_STATUS, ovFooter, y, warnText);
            y += GROUP_GAP * k;

            // Original and mahitTV, measured the last time each was on.
            y = drawHeader(c, Ui.tr("COMPARISON"), y);
            float pitch = 48 * k, colA = right - 230 * k;
            drawContainer(c, y, y + 4 * pitch);
            metricValue.setColor(secondary);
            float b = y + pitch / 2 + Ui.halfCap(metricValue);
            c.drawText(Ui.tr("Original"), colA, b, metricValue);
            c.drawText("mahitTV", right, b, metricValue);
            metricValue.setColor(primary);
            metricLabel.setColor(primary);
            for (int i = 0; i < METRICS.length; i++) {
                float top = y + (i + 1) * pitch;
                drawSeparator(c, LIST_X * k, LIST_R * k, top);
                float bb = top + pitch / 2 + Ui.halfCap(metricLabel);
                c.drawText(i == 0 ? Ui.tr("Home button") : i == 1 ? Ui.tr("Free memory") : Ui.tr("Home screen memory"), x, bb, metricLabel);
                c.drawText(ovMetric[2 * i], colA, bb, metricValue);
                c.drawText(ovMetric[2 * i + 1], right, bb, metricValue);
            }
            y += 4 * pitch + GROUP_GAP * k;

            // The Top Shelf's picks, with the credit TMDB asks for.
            y = drawHeader(c, Ui.tr("RECOMMENDATIONS"), y);
            drawContainer(c, y, y + row);
            notice.setColor(primary);
            c.drawText(ovRecs, x, y + row / 2 + Ui.halfCap(notice), notice);
            y = drawFooter(c, SLOT_CREDIT, ovCredit, y + row, footerPaint) + GROUP_GAP * k;

            // How the last switches went.
            y = drawHeader(c, Ui.tr("NOTICES"), y);
            int shown = Math.max(1, ovNotices);
            drawContainer(c, y, y + shown * row);
            for (int i = 0; i < shown; i++) {
                if (i > 0) drawSeparator(c, LIST_X * k, LIST_R * k, y + i * row);
                c.drawText(ovNotices == 0 ? Ui.tr("None yet.") : ovNotice[i], x, y + i * row + row / 2 + Ui.halfCap(notice), notice);
            }
            c.restore();
        }

        // MARK: - Rows: grouped inset containers, 64 tall, the focused one a white platter (tvOS)

        /** What a row shows at its right: a value, a chevron after it (it opens something), a switch, or a check. */
        private static final int KIND_VALUE = 0, KIND_PUSH = 1, KIND_SWITCH = 2, KIND_CHECK = 3;
        // The rows' text and look, made when something changes (invalidate), never per frame.
        private String[] cellText = new String[0], cellValue = new String[0];
        private int[] cellKind = new int[0];
        private boolean[] cellOn = new boolean[0], cellDim = new boolean[0], cellWarn = new boolean[0], cellSpin = new boolean[0];
        private boolean cellsDirty = true;
        private String pushedTitle = "", pushedNote = "", footerA, footerB;
        private int knobKey = -1, rowsVersion;
        // The open section's groups: their kind (G_…), first and last row, and where each part sits (px at 1080p from
        // the top of the content), with the footer's layout.
        private static final int MAX_GROUPS = 8;
        private int groupCount;
        private final int[] groupKind = new int[MAX_GROUPS], gFirst = new int[MAX_GROUPS], gLast = new int[MAX_GROUPS];
        private final float[] gTop = new float[MAX_GROUPS], gBoxTop = new float[MAX_GROUPS], gBoxBottom = new float[MAX_GROUPS],
                gFooterTop = new float[MAX_GROUPS], gEnd = new float[MAX_GROUPS];
        private final StaticLayout[] gFooter = new StaticLayout[MAX_GROUPS];
        private int[] rowGroup = new int[0];
        private float[] rowY = new float[0];
        private float contentH;
        private boolean snapScroll = true;
        // Footers' layouts, kept per slot (a group's, or one of the screens' own) and made again when their text changes.
        private static final int SLOT_A = MAX_GROUPS, SLOT_B = MAX_GROUPS + 1, SLOT_STATUS = MAX_GROUPS + 2,
                SLOT_CREDIT = MAX_GROUPS + 3;
        private final String[] footerTexts = new String[MAX_GROUPS + 4];
        private final StaticLayout[] footers = new StaticLayout[MAX_GROUPS + 4];
        private final java.util.HashMap<String, String> uppers = new java.util.HashMap<>();
        private int uppersGeneration = -1;

        /** `text` in capitals, as the group headers show it (made once per language). */
        private String upper(String text) {
            if (uppersGeneration != Ui.generation) {
                uppers.clear();
                uppersGeneration = Ui.generation;
            }
            String u = uppers.get(text);
            if (u == null) {
                u = text.toUpperCase(Ui.locale());
                uppers.put(text, u);
            }
            return u;
        }

        /** A group's uppercase header with its top at `y` (px); returns where the group's container starts. */
        private float drawHeader(Canvas c, String text, float y) {
            c.drawText(upper(text), (LIST_X + INSET) * k, y + 30 * k, header);
            return y + HEADER_H * k;
        }

        /** A grouped container: continuous corners, the theme's glass and a 1 px rim. */
        private void drawBox(Canvas c, float left, float right, float top, float bottom) {
            Corners.continuous(boxPath, right - left, bottom - top, RADIUS * k);
            c.save();
            c.translate(left, top);
            c.drawPath(boxPath, groupFill);
            c.drawPath(boxPath, groupRim);
            c.restore();
        }

        private void drawContainer(Canvas c, float top, float bottom) {
            drawBox(c, LIST_X * k, LIST_R * k, top, bottom);
        }

        /** The 1 px line between two rows, inset 24 px from both sides. */
        private void drawSeparator(Canvas c, float left, float right, float y) {
            float hair = Math.max(1, k);
            c.drawRect(left + 24 * k, y - hair / 2, right - 24 * k, y + hair / 2, separator);
        }

        /** The focused row's white platter, 1.02× with its shadow (`f`: how focused). */
        private void drawPlatter(Canvas c, float left, float right, float top, float f) {
            float s = 1 + 0.02f * f, cx = (left + right) / 2, cy = top + ROW_H * k / 2;
            float hw = (right - left) * s / 2, hh = ROW_H * k * s / 2, r = RADIUS * k * s;
            tmp.set(cx - hw, cy - hh + 10 * k * f, cx + hw, cy + hh + 10 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.25f * f));
            c.drawRoundRect(tmp, r, r, shadow);
            tmp.offset(0, -10 * k * f);
            white.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawRoundRect(tmp, r, r, white);
        }

        /** A footer under a group, wrapped (its layout is kept in `slot`); returns the y under it. */
        private float drawFooter(Canvas c, int slot, String text, float y, TextPaint paint) {
            StaticLayout l = footer(slot, text, paint);
            float top = y + FOOTER_GAP * k;
            c.save();
            c.translate((LIST_X + INSET) * k, top);
            l.draw(c);
            c.restore();
            return top + l.getHeight();
        }

        private StaticLayout footer(int slot, String text, TextPaint paint) {
            int width = Math.round((LIST_R - LIST_X - 2 * INSET) * k);
            StaticLayout l = footers[slot];
            if (l == null || !text.equals(footerTexts[slot]) || l.getWidth() != width) {
                footerTexts[slot] = text;
                l = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width).setMaxLines(5)
                        .setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).setLineSpacing(4 * k, 1).build();
                footers[slot] = l;
            }
            return l;
        }

        private float focusOf(int i) {
            return i < pop.length ? Ui.clamp(pop[i].value) : 0;
        }

        private Spring knob(int i) {
            return knobs[Math.min(i, knobs.length - 1)];
        }

        /** The switches' knobs head for their values; a list that just came in starts with them there. */
        private void aimKnobs(int n, int key) {
            boolean fresh = key != knobKey;
            knobKey = key;
            for (int i = 0; i < n; i++) {
                if (cellKind[i] != KIND_SWITCH) continue;
                float on = cellOn[i] ? 1 : 0;
                if (fresh) knob(i).snap(on); else knob(i).target = on;
            }
        }

        /** One row's content between `left` and `right` with its top at `top` (px); `f`: how focused. */
        private void drawCell(Canvas c, int i, float left, float right, float top, float f, float seconds) {
            Context ctx = getContext();
            float cy = top + ROW_H * k / 2;
            int save = c.save();
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                c.scale(s, s, (left + right) / 2, cy);
            }
            boolean dim = cellDim[i];
            rowLabel.setColor(dim ? blend(Ui.alpha(primary, 0.45f), 0x731C1C1E, f) : blend(primary, INK, f));
            float base = cy + Ui.halfCap(rowLabel), x = left + INSET * k, r = right - INSET * k;
            if (screen == SECTION && rows[i] == R_PROFILE && avatar != null) {   // who's here: their avatar first
                c.drawBitmap(avatar, x, cy - avatar.getHeight() / 2f, null);
                x += avatar.getWidth() + 18 * k;
            }
            c.drawText(cellText[i], x, base, rowLabel);
            switch (cellKind[i]) {
                case KIND_SWITCH:
                    r = drawSwitch(c, i, r, cy, f) - 20 * k;
                    break;
                case KIND_PUSH: {
                    float cw = Symbols.width(ctx, "chevron_right", 22 * k);
                    Symbols.draw(ctx, c, "chevron_right", r - cw / 2, cy, 22 * k, blend(tertiary, 0x4D3C3C43, f));
                    r -= cw + 14 * k;
                    break;
                }
                case KIND_CHECK: {   // the check's place stays empty on the others, so their values line up
                    float cw = Symbols.width(ctx, "check", 26 * k);
                    if (cellOn[i]) Symbols.draw(ctx, c, "check", r - cw / 2, cy, 26 * k, blend(primary, INK, f));
                    r -= cw + 16 * k;
                    break;
                }
                default:
                    break;
            }
            if (!cellValue[i].isEmpty()) {
                rowValue.setColor(cellWarn[i] ? blend(warnColor, 0xFFB25000, f)
                        : dim ? blend(tertiary, 0x4D3C3C43, f) : blend(secondary, 0x993C3C43, f));
                c.drawText(cellValue[i], r, base, rowValue);
                if (cellSpin[i]) {
                    spinner.setColor(blend(primary, INK, f));
                    Ui.spinner(c, r - rowValue.measureText(cellValue[i]) - 26 * k, cy, 12 * k, seconds * 1.3f, spinner);
                }
            }
            c.restoreToCount(save);
        }

        /** A tvOS switch (64 × 38, green when on) ending at `right`; returns its left edge. */
        private float drawSwitch(Canvas c, int i, float right, float cy, float f) {
            float v = Ui.clamp(knob(i).value), tw = 64 * k, th = 38 * k, left = right - tw;
            switchRect.set(left, cy - th / 2, right, cy + th / 2);
            track.setColor(blend(blend(trackOff, 0x29787880, f), GREEN, v));
            c.drawRoundRect(switchRect, th / 2, th / 2, track);
            float r = 17 * k, x = left + 2 * k + r + v * (tw - 4 * k - 2 * r);
            c.drawCircle(x, cy + 1.5f * k, r, knobShadow);
            c.drawCircle(x, cy, r, knob);
            return left;
        }

        // MARK: - A section: its groups, scrolled so the focused row shows

        /** Where each group and row sits, and the footers' layouts: after the rows or a footer's text changed. */
        private void layoutSection() {
            if (footerPaint == null) return;
            if (rowY.length != rows.length) rowY = new float[rows.length];
            float y = 0;
            int r = 0;
            for (int g = 0; g < groupCount; g++) {
                if (g > 0) y += GROUP_GAP;
                gTop[g] = y;
                if (groupHeader(groupKind[g]) != null) y += HEADER_H;
                gBoxTop[g] = y;
                gFirst[g] = r;
                while (r < rows.length && rowGroup[r] == g) {
                    rowY[r++] = y;
                    y += ROW_H;
                }
                gLast[g] = r - 1;
                gBoxBottom[g] = y;
                String text = groupFooter(groupKind[g]);
                gFooter[g] = text == null ? null : footer(g, text, footerPaint);
                if (gFooter[g] != null) {
                    y += FOOTER_GAP;
                    gFooterTop[g] = y;
                    y += gFooter[g].getHeight() / k;
                }
                gEnd[g] = y;
            }
            contentH = y;
        }

        /** Scrolls only as far as needed to show the focused row (its group's header with the first, its footer with the last). */
        private void scrollToFocus(boolean snap) {
            if (focus < 0 || focus >= rowY.length || rowGroup.length != rowY.length) return;
            float view = CONTENT_BOTTOM - CONTENT_TOP;
            int g = rowGroup[focus];
            float top = focus == gFirst[g] ? gTop[g] : rowY[focus];
            float bottom = focus == gLast[g] ? gEnd[g] : rowY[focus] + ROW_H;
            float t = scroll.target;
            if (bottom - t > view) t = bottom - view;
            if (top < t) t = top;
            t = Math.max(0, Math.min(t, Math.max(0, contentH - view)));
            if (snap) scroll.snap(t); else scroll.target = t;
        }

        private void drawSection(Canvas c, float seconds) {
            c.drawText(pushedTitle, LIST_X * k, TITLE_BASE * k, paneTitle);
            scrollToFocus(snapScroll);
            snapScroll = false;
            int n = Math.min(rows.length, cellText.length);
            aimKnobs(n, SECTION | section << 4 | rowsVersion << 8);
            float origin = (CONTENT_TOP - scroll.value) * k, viewTop = (CONTENT_TOP - 30) * k, left = LIST_X * k, right = LIST_R * k;
            c.save();
            c.clipRect(0, viewTop, w, h);   // the rows pass under the title
            for (int g = 0; g < groupCount; g++) {
                float top = origin + gTop[g] * k;
                if (top > h || origin + gEnd[g] * k < viewTop) continue;
                String header = groupHeader(groupKind[g]);
                if (header != null) drawHeader(c, header, top);
                drawContainer(c, origin + gBoxTop[g] * k, origin + gBoxBottom[g] * k);
                for (int r = gFirst[g]; r < gLast[g]; r++) drawSeparator(c, left, right, origin + (rowY[r] + ROW_H) * k);
                if (gFooter[g] != null) {
                    c.save();
                    c.translate((LIST_X + INSET) * k, origin + gFooterTop[g] * k);
                    gFooter[g].draw(c);
                    c.restore();
                }
            }
            for (int i = 0; i < n; i++) {
                float f = focusOf(i);
                if (f > 0.01f) drawPlatter(c, left, right, origin + rowY[i] * k, f);
            }
            for (int i = 0; i < n; i++) {
                float top = origin + rowY[i] * k;
                if (top > h || top + ROW_H * k < viewTop) continue;
                drawCell(c, i, left, right, top, focusOf(i), seconds);
            }
            c.restore();
        }

        // MARK: - The lists a row opens: one group, nine rows at a time

        private void drawPushed(Canvas c, float seconds) {
            c.drawText(pushedTitle, LIST_X * k, TITLE_BASE * k, paneTitle);
            float top = CONTENT_TOP * k, left = LIST_X * k, right = LIST_R * k, row = ROW_H * k;
            if (screen == CLEAN && cleanPlanAt < 0) {   // the first plan isn't here yet
                drawContainer(c, top, top + row);
                rowLabel.setColor(primary);
                c.drawText(pushedNote, left + INSET * k, top + row / 2 + Ui.halfCap(rowLabel), rowLabel);
                spinner.setColor(primary);
                Ui.spinner(c, right - (INSET + 12) * k, top + row / 2, 12 * k, seconds * 1.3f, spinner);
                return;
            }
            if (screen == CLEAN && cleanRowIds.length == 0) {
                drawFooter(c, SLOT_A, pushedNote, top - FOOTER_GAP * k, footerPaint);
                return;
            }
            int n = Math.min(rowCount(), cellText.length);
            aimKnobs(n, screen | (pickFor & 0xFF) << 4 | pickScroll << 12 | rowsVersion << 20);
            float bottom = top + n * row;
            if (n > 0) {
                drawContainer(c, top, bottom);
                for (int i = 1; i < n; i++) drawSeparator(c, left, right, top + i * row);
                for (int i = 0; i < n; i++) {
                    float f = focusOf(i);
                    if (f > 0.01f) drawPlatter(c, left, right, top + i * row, f);
                }
                for (int i = 0; i < n; i++) drawCell(c, i, left, right, top + i * row, focusOf(i), seconds);
            }
            float y = bottom;
            if (scrolling() && pickScroll + n < listSize()) {   // more below: the list goes on
                Symbols.draw(getContext(), c, "chevron_down", (left + right) / 2, y + 22 * k, 22 * k, tertiary);
                y += 30 * k;
            }
            if (footerA != null) y = drawFooter(c, SLOT_A, footerA, y, screen == TMDB_KEY && keyStatusError ? warnText : footerPaint);
            if (footerB != null) y = drawFooter(c, SLOT_B, footerB, y, footerPaint);
            if (screen == TMDB_KEY) drawKeyCode(c, y + 26 * k);
        }

        /** The code with the steps beside it, and the address under it for a phone without a camera. */
        private void drawKeyCode(Canvas c, float top) {
            float x = (LIST_X + INSET) * k, below = top + QR_SIDE * k;
            Bitmap qr = keyQr;
            if (qr != null) {
                int n = qr.getWidth();
                float side = Math.max(1, (int) (QR_SIDE * k / n)) * n;   // whole pixels per module
                keyQrRect.set(x, top, x + side, top + side);
                c.drawBitmap(qr, null, keyQrRect, qrPaint);
            }
            if (keySteps != null) {
                c.save();
                c.translate(x + (QR_SIDE + 40) * k, top);
                keySteps.draw(c);
                c.restore();
                below = Math.max(below, top + keySteps.getHeight());
            }
            KeyPage page = keyPage;
            if (page != null) {
                c.drawText(Ui.tr("No camera? On a phone on the same network, open:"), x, below + 44 * k, footerPaint);
                c.drawText(page.url, x, below + 80 * k, footerPaint);
            }
        }

        /** The value cut to `width` px (it keeps the start). */
        private String fitValue(String value, float width) {
            if (rowValue.measureText(value) <= width) return value;
            measure.set(rowValue);
            return TextUtils.ellipsize(value, measure, Math.max(0, width), TextUtils.TruncateAt.END).toString();
        }

        /** The labels and values cut to fit their row, around what sits at its right. */
        private void fitCells(int n) {
            float width = (LIST_R - LIST_X - 2 * INSET) * k;
            for (int i = 0; i < n; i++) {
                float accessory = cellKind[i] == KIND_SWITCH ? 84 * k : cellKind[i] == KIND_VALUE ? 0 : 42 * k;
                float lead = screen == SECTION && rows[i] == R_PROFILE && avatar != null ? avatar.getWidth() + 18 * k : 0;
                float labelMax = width * 0.62f - lead;
                if (rowLabel.measureText(cellText[i]) > labelMax) {
                    measure.set(rowLabel);
                    cellText[i] = TextUtils.ellipsize(cellText[i], measure, labelMax, TextUtils.TruncateAt.END).toString();
                }
                float valueMax = width - accessory - lead - rowLabel.measureText(cellText[i]) - 32 * k - (cellSpin[i] ? 44 * k : 0);
                if (!cellValue[i].isEmpty()) cellValue[i] = fitValue(cellValue[i], valueMax);
            }
        }

        /** Makes the current screen's rows (text, kind, state) and its footers: after a change, never per frame. */
        private void refreshCells() {
            cellsDirty = false;
            if (rowLabel == null) return;   // before the first size
            if (screen == MAIN) {
                refreshOverview();
                return;
            }
            if (screen == PIN_PAD) {
                pinTitleText = pinTitle();
                pinReasonText = pinReason();
                return;
            }
            if (screen == WALLPAPER_PICKER) return;
            int n = screen == SECTION ? rows.length : rowCount();
            if (cellText.length != n) {
                cellText = new String[n];
                cellValue = new String[n];
                cellKind = new int[n];
                cellOn = new boolean[n];
                cellDim = new boolean[n];
                cellWarn = new boolean[n];
                cellSpin = new boolean[n];
            }
            for (int i = 0; i < n; i++) {
                cellText[i] = cellValue[i] = "";
                cellKind[i] = KIND_VALUE;
                cellOn[i] = cellDim[i] = cellWarn[i] = cellSpin[i] = false;
            }
            footerA = footerB = null;
            Context c = getContext();
            switch (screen) {
                case SECTION:
                    for (int i = 0; i < n; i++) {
                        int id = rows[i];
                        cellText[i] = rowName(id);
                        if (unavailable(i) != null) {
                            cellDim[i] = true;
                            cellValue[i] = Ui.tr("Unavailable");
                            continue;
                        }
                        if (isSwitch(id)) {
                            cellKind[i] = KIND_SWITCH;
                            cellOn[i] = switchOn(id);
                        } else {
                            cellValue[i] = rowState(id);
                            cellKind[i] = opens(id) || (id == R_TMDB && may("tmdb")) || (id == R_WATCHMODE && may("watchmode"))
                                    ? KIND_PUSH : KIND_VALUE;
                        }
                        cellWarn[i] = armed == id;
                        cellSpin[i] = id == R_HOME && busy();
                    }
                    pushedTitle = sectionName(section);
                    layoutSection();
                    break;
                case HOME_CHOICE: {
                    int current = freeActive() ? 0 : 1;
                    cellText[0] = "mahitTV";
                    cellValue[0] = Ui.tr("No ads, with the new screensaver");
                    cellText[1] = Ui.tr("Original");
                    cellValue[1] = Ui.tr("The TV's factory home screen");
                    for (int i = 0; i < 2; i++) {
                        cellKind[i] = KIND_CHECK;
                        cellOn[i] = i == current;
                    }
                    pushedTitle = Ui.tr("Home Screen");
                    footerA = Ui.tr("Switching takes a few seconds; the screen blinks while it measures.");
                    footerB = Ui.tr("Original asks for the manager's PIN.");
                    break;
                }
                case REMOTE_KEYS: {
                    String[] labels = keyLabels(), values = keyValues();
                    for (int i = 0; i < n; i++) {
                        int at = pickScroll + i;
                        cellText[i] = labels[at];
                        if (unavailable(i) != null) {
                            cellDim[i] = true;
                            cellValue[i] = Ui.tr("Unavailable");
                        } else {
                            cellValue[i] = values[at];
                            cellKind[i] = KIND_PUSH;
                        }
                    }
                    pushedTitle = Ui.tr("Remote Buttons");
                    footerA = remoteNote();
                    // No key service on this TV (RemoteKeys.FILTER_SAFE): inside other apps the buttons do nothing.
                    if (!RemoteKeys.FILTER_SAFE) footerB = Ui.tr("On this TV they work on mahitTV's home screen only.");
                    else if (RemoteKeys.doublePress()) footerB = Ui.tr("Pressed twice or held: a second or a third app.");
                    break;
                }
                case POWER: {
                    int[] ids = powerRows();
                    String[] labels = powerLabels(), values = powerValues();
                    for (int i = 0; i < n && i < ids.length; i++) {
                        cellText[i] = labels[i];
                        if (unavailable(i) != null) {
                            cellDim[i] = true;
                            cellValue[i] = Ui.tr("Unavailable");
                        } else if (ids[i] == P_SHOW) {
                            cellKind[i] = KIND_SWITCH;
                            cellOn[i] = Prefs.sleepShown();
                        } else {
                            cellValue[i] = values[i];
                            cellKind[i] = KIND_PUSH;
                        }
                    }
                    pushedTitle = Ui.tr("Sleep Timer");
                    break;
                }
                case TMDB_KEY:
                    for (int i = 0; i < n; i++) {
                        cellText[i] = keyLabel(keyRowIds[i]);
                        cellKind[i] = KIND_PUSH;
                    }
                    pushedTitle = keyWatchmode ? Ui.tr("Watchmode key") : Ui.tr("TMDB key");
                    footerA = keyStatus;
                    footerB = keyPurpose;
                    break;
                case PICK_FROM:
                    for (int i = 0; i < n; i++) {
                        String[] service = services.get(pickScroll + i);
                        cellText[i] = service[1];
                        cellKind[i] = KIND_SWITCH;
                        cellOn[i] = serviceOn(service[0]);
                    }
                    if (lockList) {   // the same switches list, for App Lock's apps
                        pushedTitle = Ui.tr("Locked Apps");
                        footerA = Ui.tr("Choose apps that ask for a passcode.");
                        break;
                    }
                    pushedTitle = Ui.tr("Picks from");
                    footerA = services.isEmpty() ? Ui.tr("No streaming apps on this TV.")
                            : Ui.tr("Titles from a service that is off are left out of the picks.");
                    break;
                case PICKER:
                    for (int i = 0; i < n; i++) {
                        int at = pickScroll + i;
                        cellText[i] = pickLabels.get(at);
                        if (unavailable(i) != null) {
                            cellDim[i] = true;
                            cellValue[i] = Ui.tr("Unavailable");
                        } else {
                            cellKind[i] = KIND_CHECK;
                            cellOn[i] = at == pickCurrent;
                        }
                    }
                    pushedTitle = pickFor == PICK_CITY ? Ui.tr("City") : pickFor == PICK_LANGUAGE ? Ui.tr("Language")
                            : pickFor == PICK_SLEEP ? Ui.tr("Sleep Timer") : pickFor == PICK_PLACE ? Ui.tr("Position")
                            : pickFor == PICK_ENUM ? rowName(enumRow) : keyLabels()[pickFor - 1];
                    if (pickFor == PICK_ENUM) {
                        footerA = enumRow == R_SAVER_STYLE ? groupFooter(G_SAVER)
                                : enumRow >= R_OTHER && enumRow < R_COIN ? groupFooter(G_PINS) : null;
                    }
                    break;
                case SHELF_EDIT: {
                    for (int i = 0; i < n; i++) {
                        String[] app = shelfRows.get(pickScroll + i);
                        int place = shelfOrder.indexOf(app[0]);
                        cellText[i] = app[1];
                        if (unavailable(i) != null) {
                            cellDim[i] = true;
                            cellValue[i] = Ui.tr("Unavailable");
                        } else {
                            cellKind[i] = KIND_CHECK;
                            cellOn[i] = place >= 0;
                            cellValue[i] = place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off");
                        }
                    }
                    pushedTitle = editingTop ? Ui.tr("Top Buttons") : Ui.tr("Dock");
                    // How to move one is the only way to know it can move: this footer stays.
                    int max = HomeApps.maxShelf(c);
                    footerA = editingTop ? Ui.tr("OK adds or removes a button. ← → change the order.")
                            : max == Integer.MAX_VALUE ? Ui.tr("OK adds or removes an app. ← → change the order.")
                            : Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", max);
                    if (!editingTop && HomeApps.lowRam(c)) footerB = Ui.tr("This TV has little memory, so the Dock holds fewer apps.");
                    break;
                }
                case CLEAN: {
                    pushedTitle = Ui.tr("Clean Up This TV");
                    if (cleanPlanAt < 0) {
                        pushedNote = fitValue(busyText().isEmpty() ? Ui.tr("Reading this TV's apps…") : busyText(), 900 * k);
                        break;
                    }
                    if (cleanRowIds.length == 0) {
                        pushedNote = !cleanError.isEmpty() ? cleanError : Ui.tr("Nothing on this TV is on its list: it's already clean.");
                        break;
                    }
                    cleanCache();
                    boolean waiting = busy();
                    for (int i = 0; i < n; i++) {
                        int id = cleanRowIds[pickScroll + i];
                        boolean safe = id < 0 || cleanItem(id).optInt("w") == Debloat.SAFE;
                        cellText[i] = cleanLabels[pickScroll + i];
                        cellValue[i] = cleanValues[pickScroll + i];
                        cellKind[i] = KIND_CHECK;
                        cellOn[i] = id >= 0 && cleanOff.contains(cleanItem(id).optString("p"));
                        cellDim[i] = !safe || waiting;
                        cellWarn[i] = armed == ARM_CLEAN && id == CLEAN_APPLY;
                    }
                    // What the focused row means (why one stays on), and whose list it is.
                    int pos = pickScroll + focus;
                    footerA = busy() ? (busyText().isEmpty() ? Ui.tr("Starting…") : busyText())
                            : pos < cleanRowIds.length ? cleanHint(cleanRowIds[pos]) : null;
                    footerB = cleanFrom.isEmpty() ? null : cleanFrom;
                    break;
                }
                default:
                    break;
            }
            fitCells(n);
        }

        /** Remote Buttons' footer: which of the remote's buttons stay the TV's. */
        private static String remoteNote() {
            return RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                    : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                    : RemoteKeys.SONY ? Ui.tr("Sony default: the button opens what it always did.")
                    : RemoteKeys.FORMOVIE || RemoteKeys.JVC_SKYWORTH ? Ui.tr("The other buttons: the TV itself controls them.")
                    : RemoteKeys.XIAOMI ? Ui.tr("Xiaomi default: the button opens what it always did.")
                    : RemoteKeys.HISENSE ? Ui.tr("Netflix: the TV itself controls that button.")
                    : RemoteKeys.GOOGLE_STREAMER ? Ui.tr("The other buttons: the TV itself controls them.")
                    : RemoteKeys.supported() ? Ui.tr("Disney and Globoplay: the TV itself controls those buttons.")
                    : Ui.tr("TCL remote buttons: on this TV they work as always.");
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

        /**
         * Every key, with the home screen's sounds as tvOS has them: the tick when the focus (or the screen) moves, the
         * select click when OK opens a row (a switch plays its own on or off), the tick on Back.
         */
        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int code = e.getKeyCode();
            long before = where();
            boolean okRow = !down && okDown && screen == SECTION && focus < rows.length && !isSwitch(rows[focus]);
            boolean handled = keyOn(e);
            Click k = Click.current();
            if (k != null && handled) {
                boolean arrow = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                        || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
                boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
                if (down && arrow && where() != before) k.move();
                else if (!down && ok && (okRow || (screen != SECTION && where() != before))) k.open();
                else if (!down && code == KeyEvent.KEYCODE_BACK && where() != before) k.move();
            }
            return handled;
        }

        /** Where the focus is (screen, row, scroll), to tell whether a key moved it. */
        private long where() {
            return ((long) screen << 40) | ((long) (focus & 0xFFFFF) << 20) | (pickScroll & 0xFFFFF);
        }

        private boolean keyOn(KeyEvent e) {
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
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    boolean right = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT;
                    if (down && screen == WALLPAPER_PICKER) {
                        wallpaperPicker.optionsPane(right);
                        cacheWallpaperLabels();
                        focus = wallpaperPicker.draw().optionsPane ? wallpaperPicker.draw().option : wallpaperPicker.draw().category;
                        invalidate();
                        spoken.focusMoved();
                        return true;
                    }
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(right ? 1 : -1);   // the Dock's order: its footer says so
                    } else if (down && screen == MAIN && right) {
                        openSection(focus, -1);   // → into the section's rows, as OK
                        for (Spring s : pop) s.snap(0);
                    } else if (down && screen != MAIN && !right) {
                        toSidebar();
                    }
                    whyRows = null;   // a value or the rows' order changed: what each row needs may have too
                    invalidate();
                    spoken.focusMoved();
                    return true;
                }
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
                                wallpaperPicker.back();
                                openSection(S_APPEARANCE, R_WALLPAPER);
                            } else if (screen == PICKER && pickFor == PICK_ENUM) {
                                openSection(section, enumRow);
                            } else if (screen == PICKER && pickFor <= PICK_SLEEP) {
                                focus = powerFocus(pickFor == PICK_SLEEP ? P_SLEEP : P_WHERE);
                                pickScroll = 0;   // the picker's scroll isn't this short list's
                                screen = POWER;
                            } else if (screen == PICKER && pickFor == PICK_CITY) {
                                openSection(S_LOCATION, R_CITY);
                            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                                openSection(S_SYSTEM, R_LANGUAGE);
                            } else if (screen == PICKER) {
                                focusKeyRow(pickFor - 1);
                            } else if (screen == HOME_CHOICE) {
                                openSection(S_SYSTEM, R_HOME);
                            } else if (screen == SHELF_EDIT) {
                                openSection(editingTop ? S_SYSTEM : shelfSection, editingTop ? R_TOP_BUTTONS : R_DOCK);
                            } else if (screen == REMOTE_KEYS) {
                                openSection(S_SYSTEM, R_REMOTE);
                            } else if (screen == CLEAN) {
                                openSection(S_SYSTEM, R_CLEAN);
                            } else if (screen == TMDB_KEY) {
                                stopKeyPage();
                                openSection(S_LOCATION, keyWatchmode ? R_WATCHMODE : R_TMDB);
                            } else if (screen == PICK_FROM && lockList) {
                                lockList = false;
                                openSection(S_PROFILES, R_LOCKED_APPS);
                            } else if (screen == PICK_FROM) {
                                openSection(S_LOCATION, R_PICKS_FROM);
                            } else if (screen == POWER) {
                                openSection(S_SYSTEM, R_SLEEP);
                            } else {
                                focus = screen == SECTION ? section : 0;
                                pickScroll = 0;
                                screen = MAIN;
                                for (Spring s : pop) s.snap(0);
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

        /** ← from the rows: the focus goes to the section's capsule on the left (→ or OK comes back in). */
        private void toSidebar() {
            if (screen == WALLPAPER_PICKER) wallpaperPicker.close();
            if (screen == TMDB_KEY) stopKeyPage();   // as Back: the phone's page answers only while its code shows
            armed = 0;
            pickScroll = 0;
            focus = section;
            screen = MAIN;
            for (Spring s : pop) s.snap(0);
        }

        // MARK: - Sections (MAHITTV-PROFILES-SPEC.md §6)

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
                R_PICKS_FROM = 31, R_CARTOONS = 32, R_TRAKT = 33, R_PHOTO_SOURCE = 34, R_CLOCK = 77, R_POWER_ON = 78,
                R_CHECK = 40, R_GEMINI = 50, R_OLLAMA = 51, R_FORGET = 52,
                R_HOME = 60, R_SCREENSAVER = 61, R_TOP_BUTTONS = 62, R_REMOTE = 63, R_SLEEP = 64, R_TV_SETTINGS = 65,
                R_LANGUAGE = 66, R_SYSTEM_APPS = 67, R_APP_COUNT = 68, R_SAVER_STYLE = 69, R_CLEAN = 72,
                R_SAVER_SCENE = 73, R_SAVER_CLOCK = 74, R_PHOTOS_REMOVE = 75,
                R_GLASS = 17, R_SETTINGS_STYLE = 18, R_TEXT_SIZE = 19, R_SHELF_MODE = 30, R_SETUP = 76, R_INTRO_PREVIEW = 79,
                R_RESET = 70, R_RESTORE = 71,
                // The TV's own Settings, as its menu lists them (TCL Google TV): each opens that page there.
                R_TV_CHANNELS = 80, R_TV_DISPLAY = 81, R_TV_NETWORK = 82, R_TV_ACCOUNTS = 83, R_TV_PRIVACY = 84, R_TV_APPS = 85,
                R_TV_SYSTEM = 86, R_TV_ACCESSIBILITY = 87, R_TV_REMOTES = 88, R_DATE_TIME = 89,
                // App Lock (AppLock): the locked apps, what unlocks them, and the App Passcode.
                R_LOCKED_APPS = 90, R_LOCK_WITH = 91, R_APP_CODE = 92,
                // Music Glow (GlowRing): on, and its style.
                R_GLOW = 93, R_GLOW_STYLE = 94,
                R_OTHER = 100, R_COIN = 200, R_LIGHT = 300;
        /** The four Kasa lights (house.json fixtures). */
        private static final String[] LIGHTS = {"parents.bulb", "mine.lamp", "mine.bulb", "living.bulb"};
        /** How often the Top Shelf moves on (HomeView.ROTATE_MS): there's no setting for it yet, so the row only reads. */
        private static final int TOP_SHELF_SECONDS = 8;
        /** Remote Buttons' and Clean Up's rows on screen at once (with their footers, at 1080p); a longer list scrolls. */
        private static final int SETTINGS_VISIBLE = 9;

        private int section;
        private int[] rows = new int[0];
        private int shelfSection = S_PROFILES;   // where the Dock editor was opened from, for Back
        private int armed;                       // a row waiting for its second OK
        private int appCount = -1;               // apps found (HomeApps.launchable), counted off the UI thread
        // Google TV Settings' values, read off the UI thread when System opens: the Wi-Fi network, and the Bluetooth
        // headphones playing (else Bluetooth's state).
        private String tvNetwork = "", tvRemotes = "";
        private int checking;                    // lights that haven't answered "Check again" yet
        private boolean reduceMotion, highContrast;   // the TV's own accessibility settings, read with the state
        private Bitmap avatar;                   // the current profile's, for its row

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

        // A section's groups (tvOS: grouped inset containers): each one's header above and footer under it.
        private static final int G_YOU = 0, G_PINS = 1, G_LOOK = 2, G_SAVER = 3, G_STARTUP = 4, G_WEATHER = 5, G_WIDGETS = 6,
                G_MEDIA = 7, G_LIGHTS = 8, G_BRAIN = 9, G_MEMORY = 10, G_HOME = 11, G_REMOTE = 12, G_TV = 13, G_RESET = 14,
                G_RESTORE = 15, G_TV_MENU = 16, G_APP_LOCK = 17, G_GLOW = 18;
        private final List<Integer> buildRows = new ArrayList<>(), buildGroups = new ArrayList<>();

        private void group(int kind) {
            if (groupCount < MAX_GROUPS) groupKind[groupCount++] = kind;
        }

        private void add(int... ids) {
            for (int id : ids) {
                buildRows.add(id);
                buildGroups.add(groupCount - 1);
            }
        }

        /** Section `s`'s rows and groups (which rows show changes with the profile, the TV and the screensaver). */
        private void buildSection(int s) {
            Context c = getContext();
            buildRows.clear();
            buildGroups.clear();
            groupCount = 0;
            switch (s) {
                case S_PROFILES: {
                    ProfileManager.Profile me = ProfileManager.current(c);
                    group(G_YOU);
                    add(R_PROFILE);
                    if (me.guest()) add(R_GUEST);
                    add(R_SWITCH);
                    if (!me.guest()) add(R_CHANGE_PIN);
                    add(R_DOCK);
                    if (!me.guest()) {
                        group(G_APP_LOCK);
                        add(R_LOCKED_APPS, R_LOCK_WITH);
                        if (!AppLock.usesProfile(c)) add(R_APP_CODE);
                    }
                    if (me.manager()) {
                        List<ProfileManager.Profile> all = ProfileManager.all(c);
                        boolean grouped = false;
                        for (int i = 0; i < all.size(); i++) {
                            ProfileManager.Profile p = all.get(i);
                            if (p.id.equals(me.id) || p.guest()) continue;
                            if (!grouped) group(G_PINS);
                            grouped = true;
                            add(R_OTHER + i);
                        }
                    }
                    break;
                }
                case S_APPEARANCE:
                    group(G_LOOK);
                    add(R_THEME, R_TEXT_SIZE);
                    if (may("clock")) add(R_CLOCK);
                    add(R_WALLPAPER, R_GLASS, R_SETTINGS_STYLE, R_PHOTOS);
                    if (!Photos.files(c).isEmpty()) add(R_PHOTOS_REMOVE);
                    group(G_GLOW);
                    add(R_GLOW);
                    if (Prefs.glowOn()) add(R_GLOW_STYLE);
                    group(G_SAVER);
                    add(R_SAVER_STYLE);
                    if ("nature".equals(Prefs.saverStyle())) add(R_SAVER_SCENE, R_SAVER_CLOCK);
                    add(R_SCREENSAVER);
                    group(G_STARTUP);
                    add(R_INTRO, R_INTRO_PREVIEW, R_SOUNDS, R_MOTION, R_TRANSPARENCY);
                    break;
                case S_LOCATION:
                    group(G_WEATHER);
                    add(R_CITY, R_TEMPERATURE, R_DATE_TIME, R_WEATHER, R_FORECAST);
                    group(G_WIDGETS);
                    add(R_PLAYING, R_QUOTES);
                    for (int i = 0; i < (dollar() ? 4 : 3); i++) add(R_COIN + i);
                    group(G_MEDIA);
                    add(R_SHELF_MODE, R_SAVER, R_TMDB, R_WATCHMODE, R_TOP_SHELF);
                    if (may("picksFrom")) {
                        add(R_PICKS_FROM, R_CARTOONS);
                        countServicesOff();
                    }
                    if (Trakt.available() && may("trakt")) add(R_TRAKT);
                    if (ImmichPhotos.configured(c) && may("photoSource")) add(R_PHOTO_SOURCE);
                    break;
                case S_SMART_HOME:
                    group(G_LIGHTS);
                    for (int i = 0; i < LIGHTS.length; i++) add(R_LIGHT + i);
                    add(R_CHECK);
                    break;
                case S_WELLY:
                    group(G_BRAIN);
                    add(R_GEMINI, R_OLLAMA);
                    group(G_MEMORY);
                    add(R_FORGET);
                    break;
                case S_SYSTEM:
                    group(G_TV_MENU);
                    add(R_TV_CHANNELS, R_TV_DISPLAY, R_TV_NETWORK, R_TV_ACCOUNTS, R_TV_PRIVACY, R_TV_APPS, R_TV_SYSTEM,
                            R_TV_ACCESSIBILITY, R_TV_REMOTES);
                    group(G_HOME);
                    add(R_HOME, R_DOCK, R_TOP_BUTTONS, R_SETUP);
                    if (may("powerOn")) add(R_POWER_ON);
                    group(G_REMOTE);
                    add(R_REMOTE, R_SLEEP, R_CLEAN);
                    group(G_TV);
                    add(R_LANGUAGE, R_SYSTEM_APPS, R_APP_COUNT, R_TV_SETTINGS);
                    break;
                default:
                    group(G_RESET);
                    add(R_RESET);
                    group(G_RESTORE);
                    add(R_RESTORE);
            }
            rows = new int[buildRows.size()];
            rowGroup = new int[rows.length];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = buildRows.get(i);
                rowGroup[i] = buildGroups.get(i);
            }
        }

        /** A group's header (shown in capitals), or null for none. */
        private static String groupHeader(int g) {
            switch (g) {
                case G_PINS: return Ui.tr("Family PINs");
                case G_LOOK: return Ui.tr("Look");
                case G_SAVER: return Ui.tr("Screensaver");
                case G_STARTUP: return Ui.tr("Startup & Accessibility");
                case G_WEATHER: return Ui.tr("Weather");
                case G_WIDGETS: return Ui.tr("Widgets");
                case G_MEDIA: return Ui.tr("Media");
                case G_LIGHTS: return Ui.tr("Lights");
                case G_BRAIN: return Ui.tr("Brain");
                case G_MEMORY: return Ui.tr("Memory");
                case G_HOME: return Ui.tr("Home Screen");
                case G_REMOTE: return Ui.tr("Remote & Power");
                case G_TV: return Ui.tr("TV");
                case G_TV_MENU: return Ui.tr("Google TV Settings");
                case G_APP_LOCK: return Ui.tr("App Lock");
                case G_GLOW: return Ui.tr("Music Glow");
                default: return null;
            }
        }

        /** What a group's rows mean, under it; null for none. Made when the rows change, not per frame. */
        private String groupFooter(int g) {
            Context c = getContext();
            switch (g) {
                case G_YOU: return ProfileManager.current(c).guest()
                        ? Ui.tr("Guest's Dock, theme and Welly chats are forgotten when the TV locks.") : null;
                case G_PINS: return Ui.tr("Changing a PIN asks for the manager's PIN.");
                case G_GLOW: return Ui.tr("A light around the picture in the album art's colours, pulsing with the beat while a music app plays.");
                case G_APP_LOCK: return Ui.tr("Locked apps ask for a passcode when opened from mahitTV. Forgot the App Passcode? Your profile's passcode resets it.");
                case G_LOOK: return Photos.files(c).isEmpty() ? Ui.tr("Send them from a computer: see “Your own photos” in the README.") : null;
                case G_SAVER: return Ui.tr("Photos, a depth-layered Nature Clock, Aurora, Apple Aerials or a dim clock on black.");
                case G_STARTUP: return Ui.tr("The Liquid Glass mahitTV startup when the TV turns on.");
                case G_WIDGETS: return Prefs.quotes() ? null : Ui.tr("Turn on the Quotes widget to pick its coins.");
                case G_MEDIA: return Ui.tr("Keys live only in config.properties on the computer, never on screen.");
                case G_LIGHTS: {
                    String read = Ui.tr("Read only: the Home tab turns the lights on and off.");
                    for (int i = 0; i < LIGHTS.length; i++) {   // one says "Not set up": where it's set
                        if (light(R_LIGHT + i) == null) return read + " " + Ui.tr("Not in kasa= in config.properties on the computer.");
                    }
                    return read;
                }
                case G_BRAIN: return Ui.tr("Set in config.properties: gemini_key, welly_model and ollama_url.");
                case G_MEMORY: return armed == R_FORGET ? Ui.tr("OK again clears it. Any other key keeps it.")
                        : Ui.tr("Clears Welly's chats, what it learned and this profile's watch history.");
                case G_REMOTE: return Ui.tr("Turns off the promo and tracking apps CleanYourTV lists for this TV. Nothing is uninstalled, and Original turns them back on. Asks for the manager's PIN.");
                case G_TV: return Ui.tr("Also lists system apps that only have a phone-style entry.");
                case G_RESET: return armed == R_RESET ? Ui.tr("OK again resets it. Any other key keeps it.")
                        : Ui.tr("Clears every profile's Dock, theme, PIN changes, Welly memory and history. Language, city and remote buttons stay. Asks for the manager's PIN.");
                case G_RESTORE: return armed == R_RESTORE ? Ui.tr("OK again restores it. Any other key keeps mahitTV.")
                        : Ui.tr("Turns mahitTV mode off and brings back the TV's own home screen; mahitTV stays installed. Asks for the manager's PIN.");
                default: return null;
            }
        }

        /** Opens section `s`, focused on row `id` (its first row when it has none). */
        private void openSection(int s, int id) {
            if (screen == WALLPAPER_PICKER) wallpaperPicker.close();
            section = s;
            buildSection(s);
            rowsVersion++;
            screen = SECTION;
            armed = 0;
            whyRows = null;
            pickScroll = 0;   // a section scrolls by pixels (scroll), its focus is the row itself
            int at = 0;
            for (int i = 0; i < rows.length; i++) if (rows[i] == id) at = i;
            focus = at;
            layoutSection();
            snapScroll = true;
            if (s == S_PROFILES) loadAvatar();
            if (s == S_SMART_HOME) checkLights();
            if (s == S_SYSTEM) {
                countApps();
                readTvState();
            }
        }

        private void readTvState() {
            Context c = getContext().getApplicationContext();
            new Thread(() -> {
                String[] st = HomeApps.connectivity(c);   // {wifi, bluetooth, audio, dnd}
                handler.post(() -> {
                    tvNetwork = st[0] == null ? "" : st[0];
                    tvRemotes = st[1] == null ? "" : st[1];
                    invalidate();
                });
            }).start();
        }

        /**
         * One of Google TV Settings' rows: its page in the TV's own Settings (the public Android actions Google TV's
         * Settings answers); Channels & Inputs opens the TV's live channels app, which holds the tuner and the sources.
         * Where the TV has no such page, its Settings' first page.
         */
        private void openTvPage(int id) {
            Context c = getContext();
            List<Intent> tries = new ArrayList<>();
            switch (id) {
                case R_TV_CHANNELS:
                    for (String tv : HomeApps.LIVE_TV_APPS) {
                        Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(tv);
                        if (i != null) tries.add(i);
                    }
                    break;
                case R_TV_DISPLAY: tries.add(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); tries.add(new Intent(Settings.ACTION_SOUND_SETTINGS)); break;
                case R_TV_NETWORK: tries.add(new Intent(Settings.ACTION_WIFI_SETTINGS)); tries.add(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); break;
                case R_TV_ACCOUNTS: tries.add(new Intent(Settings.ACTION_SYNC_SETTINGS)); tries.add(new Intent(Settings.ACTION_ADD_ACCOUNT)); break;
                case R_TV_PRIVACY: tries.add(new Intent(Settings.ACTION_PRIVACY_SETTINGS)); break;
                case R_TV_APPS: tries.add(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)); tries.add(new Intent(Settings.ACTION_APPLICATION_SETTINGS)); break;
                case R_TV_SYSTEM: tries.add(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)); break;
                case R_TV_ACCESSIBILITY: tries.add(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); break;
                default:   // Remotes & Accessories
                    Intent bt = HomeApps.bluetoothPage(c);
                    if (bt != null) tries.add(bt);
                    break;
            }
            tries.add(new Intent(Settings.ACTION_SETTINGS));
            for (Intent i : tries) {
                if (c.getPackageManager().resolveActivity(i, 0) == null) continue;
                try {
                    activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (RuntimeException e) {
                    AppLog.w("TV settings page: " + e.getMessage());
                }
            }
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
            return wallpaperOptionName(id);
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
                case R_LOCKED_APPS: return Ui.tr("Locked Apps");
                case R_GLOW: return Ui.tr("Music Glow");
                case R_GLOW_STYLE: return Ui.tr("Glow Style");
                case R_LOCK_WITH: return Ui.tr("Unlock With");
                case R_APP_CODE: return AppLock.hasAppCode(c) ? Ui.tr("Change App Passcode") : Ui.tr("Set App Passcode");
                case R_THEME: return Ui.tr("Theme");
                case R_TEXT_SIZE: return Ui.tr("Text Size");
                case R_WALLPAPER: return Ui.tr("Wallpaper");
                case R_GLASS: return Ui.tr("Glass");
                case R_SETTINGS_STYLE: return Ui.tr("Settings Style");
                case R_SHELF_MODE: return Ui.tr("Top Shelf");
                case R_SETUP: return Ui.tr("Run Setup Again");
                case R_INTRO_PREVIEW: return Ui.tr("Preview Startup Animation");
                case R_PHOTOS: return Ui.tr("My Photos");
                case R_INTRO: return Ui.tr("Startup animation & chime");
                case R_SOUNDS: return Ui.tr("Navigation sounds");
                case R_MOTION: return Ui.tr("Reduce motion");
                case R_TRANSPARENCY: return Ui.tr("Reduce transparency");
                case R_CITY: return Ui.tr("City");
                case R_DATE_TIME: return Ui.tr("Date & Time");
                case R_TEMPERATURE: return Ui.tr("Temperature");
                case R_WEATHER: return Ui.tr("Weather widget");
                case R_FORECAST: return Ui.tr("Forecast");
                case R_PLAYING: return Ui.tr("Now Playing widget");
                case R_QUOTES: return Ui.tr("Quotes widget");
                case R_SAVER: return Ui.tr("Watch from the screensaver");
                case R_TMDB: return Ui.tr("TMDB key");
                case R_WATCHMODE: return Ui.tr("Watchmode key");
                case R_PICKS_FROM: return Ui.tr("Picks from");
                case R_CARTOONS: return Ui.tr("Cartoons and anime");
                case R_TRAKT: return "Trakt";
                case R_PHOTO_SOURCE: return Ui.tr("My Photos");
                case R_CLOCK: return Ui.tr("Clock");
                case R_POWER_ON: return Ui.tr("At power-on");
                case R_TOP_SHELF: return Ui.tr("Top Shelf auto-advance");
                case R_CHECK: return Ui.tr("Check again");
                case R_GEMINI: return "Gemini";
                case R_OLLAMA: return Ui.tr("Ollama fallback");
                case R_FORGET: return Ui.trf("Clear Welly's memory for {name}", "name", ProfileManager.current(c).name);
                case R_HOME: return Ui.tr("Home Screen");
                case R_SCREENSAVER: return Ui.tr("Preview");
                case R_TOP_BUTTONS: return Ui.tr("Top Buttons");
                case R_REMOTE: return Ui.tr("Remote Buttons");
                case R_SLEEP: return Ui.tr("Sleep Timer");
                case R_TV_SETTINGS: return Ui.tr("TV Settings");
                case R_TV_CHANNELS: return Ui.tr("Channels & Inputs");
                case R_TV_DISPLAY: return Ui.tr("Display & Sound");
                case R_TV_NETWORK: return Ui.tr("Network & Internet");
                case R_TV_ACCOUNTS: return Ui.tr("Accounts & Profiles");
                case R_TV_PRIVACY: return Ui.tr("Privacy");
                case R_TV_APPS: return Ui.tr("Apps");
                case R_TV_SYSTEM: return Ui.tr("tv|System");
                case R_TV_ACCESSIBILITY: return Ui.tr("Accessibility");
                case R_TV_REMOTES: return Ui.tr("Remotes & Accessories");
                case R_LANGUAGE: return Ui.tr("Language");
                case R_SYSTEM_APPS: return Ui.tr("Show system apps");
                case R_APP_COUNT: return Ui.tr("Apps found");
                case R_SAVER_STYLE: return Ui.tr("Screensaver");
                case R_SAVER_SCENE: return Ui.tr("Screensaver Scene");
                case R_SAVER_CLOCK: return Ui.tr("Clock Style");
                case R_PHOTOS_REMOVE: return Ui.tr("Remove My Photos");
                case R_CLEAN: return Ui.tr("Clean Up This TV");
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
                if (!d.problem.isEmpty()) return Ui.trf("Problem: {problem}", "problem", d.problem);
                String now = !d.reachable ? Ui.tr("Unreachable") : d.known ? onOff(d.on) : Ui.tr("Checking…");
                return d.host + "  ·  " + now;
            }
            if (id >= R_COIN) return onOff(Prefs.quotes() && Prefs.coin(id - R_COIN));
            if (id >= R_OTHER) {
                return ProfileManager.needsPin(c, other(id)) ? Ui.tr("Set") : Ui.tr("No PIN");
            }
            if (armed == id) return Ui.tr("Press OK again");
            switch (id) {
                case R_PROFILE: {
                    ProfileManager.Profile me = ProfileManager.current(c);
                    return me.manager() ? Ui.tr("Manager") : me.guest() ? Ui.tr("Guest") : Ui.tr("Family");
                }
                case R_CHANGE_PIN: return ProfileManager.needsPin(c, ProfileManager.current(c)) ? "" : Ui.tr("No PIN");
                case R_LOCKED_APPS: {
                    int n = AppLock.lockedApps(c).size();
                    return n == 0 ? Ui.tr("Off") : Ui.plural("plural|one:{n} app|other:{n} apps", n);
                }
                case R_THEME: {
                    String theme = Prefs.theme();
                    return theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark") : Ui.tr("Automatic");
                }
                case R_TEXT_SIZE: return textSizeName(Prefs.textSize());
                case R_GLASS: return glassName(Prefs.glass());
                case R_SETTINGS_STYLE: return styleName(Prefs.settingsStyle());
                case R_SHELF_MODE: return shelfModeName(Prefs.topShelf());
                case R_LOCK_WITH: return AppLock.usesProfile(c) ? Ui.tr("Profile Passcode") : Ui.tr("App Passcode");
                case R_GLOW_STYLE: return glowName(Prefs.glowStyle());
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
                case R_DATE_TIME: return zoneWrong ? Ui.tr("Check the time zone") : zoneName;
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
                case R_WATCHMODE: return Recs.watchmodeKey().isEmpty() ? Ui.tr("Not set") : Ui.tr("Set");
                case R_PICKS_FROM: return someServicesOff ? Ui.tr("services|Some") : Ui.tr("services|All");
                case R_TRAKT: return traktValue();
                case R_PHOTO_SOURCE: {
                    String source = Prefs.photoSource();
                    return source.equals("album") ? Prefs.photoAlbumName()
                            : source.equals("immich") ? Ui.tr("Immich: all photos") : Ui.tr("photos|Default");
                }
                case R_CLOCK: {
                    String clock = Prefs.clockFormat();
                    return clock.equals("24") ? Ui.tr("24 hours") : clock.equals("12") ? Ui.tr("12 hours") : Ui.tr("Automatic");
                }
                case R_POWER_ON: {
                    String power = Prefs.powerOn();
                    return power.equals("home") ? Ui.tr("power|Home screen") : power.equals("live") ? Ui.tr("Live TV") : Ui.tr("TV's choice");
                }
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
                case R_SCREENSAVER: case R_INTRO_PREVIEW: return "";
                case R_LANGUAGE: {
                    String lang = Prefs.language();
                    return lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang);
                }
                case R_SYSTEM_APPS: return onOff(Prefs.systemApps());
                case R_APP_COUNT: return appCount < 0 ? Ui.tr("Counting…") : String.valueOf(appCount);
                case R_TV_NETWORK: return tvNetwork;
                case R_TV_REMOTES: return tvRemotes;
                case R_SAVER_STYLE: return saverName(Prefs.saverStyle());
                case R_SAVER_SCENE: return sceneName(Prefs.saverScene());
                case R_SAVER_CLOCK: return clockName(Prefs.saverClock());
                case R_PHOTOS_REMOVE: return Ui.plural("plural|one:{n} photo|other:{n} photos", Photos.files(c).size());
                case R_CLEAN: {
                    int off = HomeMode.stringList(state, "debloated").size();
                    return off == 0 ? "" : Ui.plural("plural|one:{n} app off|other:{n} apps off", off);
                }
                case R_RESTORE: return originalNow() ? Ui.tr("In use") : "";
                default: return "";
            }
        }

        /** Rows that open something (a screen, a list, a dialog, the PIN pad): they show a chevron. */
        private static boolean opens(int id) {
            if (isEnum(id)) return true;
            switch (id) {
                case R_CHANGE_PIN: case R_DOCK: case R_HOME: case R_TOP_BUTTONS: case R_REMOTE: case R_SLEEP: case R_WALLPAPER:
                case R_LOCKED_APPS: case R_APP_CODE:
                case R_TV_SETTINGS: case R_LANGUAGE: case R_MOTION: case R_TRANSPARENCY: case R_RESET: case R_RESTORE:
                case R_CLEAN: case R_CITY: case R_DATE_TIME: case R_SCREENSAVER: case R_SETUP: case R_PICKS_FROM: case R_TRAKT: case R_INTRO_PREVIEW:
                case R_TV_CHANNELS: case R_TV_DISPLAY: case R_TV_NETWORK: case R_TV_ACCOUNTS: case R_TV_PRIVACY: case R_TV_APPS:
                case R_TV_SYSTEM: case R_TV_ACCESSIBILITY: case R_TV_REMOTES:
                    return true;
                default:
                    return id >= R_OTHER && id < R_COIN;
            }
        }

        /** Rows that turn something on or off: a tvOS switch, and OK flips it. */
        private boolean isSwitch(int id) {
            if (id >= R_COIN && id < R_LIGHT) return true;
            switch (id) {
                case R_PHOTOS: return !Photos.files(getContext()).isEmpty();   // with none, it only says so
                case R_CARTOONS: return true;
                case R_INTRO: case R_SOUNDS: case R_WEATHER: case R_PLAYING: case R_QUOTES: case R_SAVER: case R_SYSTEM_APPS:
                case R_GLOW:
                    return true;
                default:
                    return false;
            }
        }

        private boolean switchOn(int id) {
            if (id >= R_COIN && id < R_LIGHT) return Prefs.quotes() && Prefs.coin(id - R_COIN);
            switch (id) {
                case R_PHOTOS: return Prefs.photos();
                case R_CARTOONS: return !Prefs.hideAnimation();
                case R_INTRO: return Prefs.intro();
                case R_SOUNDS: return Prefs.sounds();
                case R_WEATHER: return Prefs.weather();
                case R_PLAYING: return Prefs.nowPlaying();
                case R_QUOTES: return Prefs.quotes();
                case R_SAVER: return Prefs.saverWatch();
                case R_SYSTEM_APPS: return Prefs.systemApps();
                case R_GLOW: return Prefs.glowOn();
                default: return false;
            }
        }

        /** Rows whose value is one of a few: OK opens their list, with a check on the one in use. */
        private static boolean isEnum(int id) {
            switch (id) {
                case R_THEME: case R_TEMPERATURE: case R_FORECAST: case R_SAVER_STYLE: case R_SAVER_SCENE: case R_SAVER_CLOCK:
                case R_GLASS: case R_SETTINGS_STYLE: case R_SHELF_MODE: case R_TEXT_SIZE: case R_CLOCK: case R_POWER_ON: case R_PHOTO_SOURCE:
                case R_LOCK_WITH: case R_GLOW_STYLE:
                    return true;
                default:
                    return false;
            }
        }

        private static final String[] GLASSES = {"liquid", "frosted"}, STYLES = {"full", "sidebar"},
                SHELF_MODES = {"app", "picks", "all", "off"};

        /** Music Glow's styles: a fine line, the default, a broad band that washes the room. */
        static String glowName(int style) {
            return style == 0 ? Ui.tr("Subtle") : style == 2 ? Ui.tr("Vivid") : Ui.tr("Balanced");
        }

        private static String glassName(String v) {
            return "frosted".equals(v) ? Ui.tr("Frosted Glass") : Ui.tr("Liquid Glass");
        }

        private static String styleName(String v) {
            return "sidebar".equals(v) ? Ui.tr("Floating Sidebar") : Ui.tr("Full Screen (tvOS)");
        }

        /** The Top Shelf's modes (Prefs.topShelf), as the list and the welcome setup name them. */
        static String shelfModeName(String v) {
            switch (v) {
                case "picks": return Ui.tr("Recommendations & News");
                case "all": return Ui.tr("All Continue Watching");
                case "off": return Ui.tr("Off (Wallpaper Only)");
                default: return Ui.tr("App-Specific Continue Watching");
            }
        }

        private static final String[] SAVER_STYLES = {"shuffle", "photos", "nature", "aurora", "aerials", "landscapes", "black"},
                SAVER_SCENES = {"rotate", "night", "golden", "water", "fog", "dawn"}, SAVER_CLOCKS = {"tall", "rounded"};

        /** Text Size's choices, as the list names them. */
        private static String textSizeName(String v) {
            return v.equals("small") ? Ui.tr("size|Small") : v.equals("large") ? Ui.tr("size|Large") : Ui.tr("size|Default");
        }

        /** The screensaver's looks (Prefs.saverStyle), as the list names them. */
        private static String saverName(String v) {
            switch (v) {
                case "shuffle": return Ui.tr("saver|Shuffle");
                case "nature": return Ui.tr("Nature Clock");
                case "aurora": return Ui.tr("Aurora");
                case "aerials": return Ui.tr("Apple Aerials");
                case "landscapes": return Ui.tr("Landscapes");
                case "black": return Ui.tr("saver|Black");
                default: return Ui.tr("saver|Photos");
            }
        }

        /** The Nature Clock's scenes (Prefs.saverScene, DreamView.Nature.SCENES). */
        private static String sceneName(String v) {
            switch (v) {
                case "night": return Ui.tr("saver|Night");
                case "golden": return Ui.tr("Golden Hour");
                case "water": return Ui.tr("Waterline");
                case "fog": return Ui.tr("Fog");
                case "dawn": return Ui.tr("Rainforest Dawn");
                default: return Ui.tr("Change every minute");
            }
        }

        /** The Nature Clock's faces (Prefs.saverClock): Antonio, or Varela Round. */
        private static String clockName(String v) {
            return "rounded".equals(v) ? Ui.tr("Rounded") : Ui.tr("Tall Glass");
        }

        /** An enum row's choices as {value, name}: settings, or what to do with another profile's PIN. */
        private List<String[]> enumChoices(int id) {
            List<String[]> l = new ArrayList<>();
            switch (id) {
                case R_CLOCK:
                    l.add(new String[] {"auto", Ui.tr("Automatic")});
                    l.add(new String[] {"24", Ui.tr("24 hours")});
                    l.add(new String[] {"12", Ui.tr("12 hours")});
                    break;
                case R_POWER_ON:
                    l.add(new String[] {"tv", Ui.tr("TV's choice")});
                    l.add(new String[] {"home", Ui.tr("power|Home screen")});
                    l.add(new String[] {"live", Ui.tr("Live TV")});
                    break;
                case R_PHOTO_SOURCE:
                    l.add(new String[] {"default", Ui.tr("photos|Default")});
                    l.add(new String[] {"immich", Ui.tr("Immich: all photos")});
                    for (ImmichPhotos.Album a : ImmichPhotos.albums) l.add(new String[] {"album:" + a.id, a.name});
                    break;
                case R_TEXT_SIZE:
                    for (String v : new String[] {"small", "default", "large"}) l.add(new String[] {v, textSizeName(v)});
                    break;
                case R_THEME:
                    l.add(new String[] {"auto", Ui.tr("Automatic")});
                    l.add(new String[] {"light", Ui.tr("Light")});
                    l.add(new String[] {"dark", Ui.tr("Dark")});
                    break;
                case R_TEMPERATURE:
                    l.add(new String[] {"auto", Ui.tr("unit|Automatic")});
                    l.add(new String[] {"C", "°C"});
                    l.add(new String[] {"F", "°F"});
                    break;
                case R_FORECAST:
                    l.add(new String[] {"hours", Ui.tr("Next hours")});
                    l.add(new String[] {"days", Ui.tr("Next days")});
                    break;
                case R_SAVER_STYLE:
                    for (String v : SAVER_STYLES) l.add(new String[] {v, saverName(v)});
                    break;
                case R_SAVER_SCENE:
                    for (String v : SAVER_SCENES) l.add(new String[] {v, sceneName(v)});
                    break;
                case R_SAVER_CLOCK:
                    for (String v : SAVER_CLOCKS) l.add(new String[] {v, clockName(v)});
                    break;
                case R_GLASS:
                    for (String v : GLASSES) l.add(new String[] {v, glassName(v)});
                    break;
                case R_SETTINGS_STYLE:
                    for (String v : STYLES) l.add(new String[] {v, styleName(v)});
                    break;
                case R_SHELF_MODE:
                    for (String v : SHELF_MODES) l.add(new String[] {v, shelfModeName(v)});
                    break;
                case R_GLOW_STYLE:
                    for (int i = 0; i < 3; i++) l.add(new String[] {Integer.toString(i), glowName(i)});
                    break;
                case R_LOCK_WITH:
                    l.add(new String[] {AppLock.WITH_PROFILE, Ui.tr("Profile Passcode")});
                    l.add(new String[] {AppLock.WITH_APP, Ui.tr("App Passcode")});
                    break;
                default:   // another profile's PIN
                    l.add(new String[] {"set", Ui.tr("Set PIN")});
                    l.add(new String[] {"remove", Ui.tr("Remove PIN")});
            }
            return l;
        }

        private String enumNow(int id) {
            switch (id) {
                case R_LOCK_WITH: return AppLock.with(getContext());
                case R_GLOW_STYLE: return Integer.toString(Prefs.glowStyle());
                case R_CLOCK: return Prefs.clockFormat();
                case R_POWER_ON: return Prefs.powerOn();
                case R_PHOTO_SOURCE: return "album".equals(Prefs.photoSource()) ? "album:" + Prefs.photoAlbum() : Prefs.photoSource();
                case R_THEME: return Prefs.theme();
                case R_TEXT_SIZE: return Prefs.textSize();
                case R_TEMPERATURE: return Prefs.temperature();
                case R_FORECAST: return Prefs.forecast();
                case R_SAVER_STYLE: return Prefs.saverStyle();
                case R_SAVER_SCENE: return Prefs.saverScene();
                case R_SAVER_CLOCK: return Prefs.saverClock();
                case R_GLASS: return Prefs.glass();
                case R_SETTINGS_STYLE: return Prefs.settingsStyle();
                case R_SHELF_MODE: return Prefs.topShelf();
                default: return "";
            }
        }

        /** OK on an enum row: its list (PICK_ENUM), focused on the value in use. */
        private void openEnum(int id) {
            enumRow = id;
            openChoices(PICK_ENUM, enumChoices(id), enumNow(id));
        }

        /** OK in an enum row's list: the value is set and it's back to its row (a profile's PIN goes on to the pad). */
        private void pickEnum() {
            Context c = getContext();
            String v = choices.get(pickScroll + focus)[0];
            int id = enumRow;
            if (id >= R_OTHER && id < R_COIN) {
                askPin(PIN_MANAGER, v.equals("remove") ? THEN_REMOVE : THEN_SET, other(id), id);
                return;
            }
            if (id == R_LOCK_WITH) {
                pendingWith = v;
                if (v.equals(AppLock.with(c))) {
                    openSection(section, id);
                } else if (lockNeedsCode()) {
                    askPin(PIN_LOCK, THEN_LOCK_WITH, ProfileManager.current(c), id);
                } else {
                    applyWith();
                }
                return;
            }
            switch (id) {
                case R_CLOCK: Prefs.setClockFormat(v); break;
                case R_POWER_ON: Prefs.setPowerOn(v); break;   // read at the next full start (HomeMode.bootCheck, HomeActivity)
                case R_PHOTO_SOURCE: {
                    boolean album = v.startsWith("album:");
                    String source = album ? "album" : v, albumId = album ? v.substring(6) : "";
                    if (!source.equals(Prefs.photoSource()) || !albumId.equals(Prefs.photoAlbum())) {
                        Prefs.setPhotoSource(source, albumId, album ? choices.get(pickScroll + focus)[1] : "");
                        ImmichPhotos.sourceChanged(c);
                    }
                    // Also when it's the same choice: My Photos turned off earlier (before the key came) is on again
                    Prefs.setPhotos(!"default".equals(source));
                    break;
                }
                case R_TEXT_SIZE:
                    Prefs.setTextSize(v);
                    onSizeChanged(w, h, w, h);   // this screen's text at the new size at once; the home screen on return
                    break;
                case R_THEME:
                    Prefs.setTheme(v);
                    SleepOverlay.update(c);   // the pill's glass follows
                    HomeData.get(c).settingsChanged();
                    break;
                case R_TEMPERATURE:
                    Prefs.setTemperature(v);
                    HomeData.get(c).settingsChanged();
                    break;
                case R_FORECAST: Prefs.setForecast(v); break;   // both are in memory: nothing to fetch
                case R_SAVER_STYLE: Prefs.setSaverStyle(v); break;   // Scene and Clock Style come and go with it
                case R_SAVER_SCENE: Prefs.setSaverScene(v); break;
                case R_SAVER_CLOCK: Prefs.setSaverClock(v); break;
                case R_GLASS:
                    Prefs.setGlass(v);
                    Scene.frosted = "frosted".equals(v);
                    break;
                case R_SETTINGS_STYLE:
                    Prefs.setSettingsStyle(v);
                    activity.restyle();   // Settings opens again in its new style, on this row
                    return;
                case R_SHELF_MODE: Prefs.setTopShelf(v); break;
                case R_GLOW_STYLE: Prefs.setGlow(Prefs.glowOn(), Integer.parseInt(v)); break;
                default: break;
            }
            openSection(section, id);
        }

        /**
         * OK on a section's row. Returns true when the screen changed (the focus pops start again), false when the row
         * only changed its value or said something.
         */
        private boolean activateRow(int id) {
            Context c = getContext();
            if (id >= R_LIGHT) return false;   // read only
            if (id >= R_OTHER && id < R_COIN) {
                // With a PIN: a list (Set PIN, Remove PIN); without one, straight to setting it. Both ask the manager.
                ProfileManager.Profile p = other(id);
                if (ProfileManager.needsPin(c, p)) openEnum(id);
                else askPin(PIN_MANAGER, THEN_SET, p, id);
                return true;
            }
            if (id == R_PHOTO_SOURCE && ImmichPhotos.albums.isEmpty()) {
                // The albums come a moment later: the list opens again with them, if it's still showing.
                ImmichPhotos.loadAlbums(c, () -> {
                    if (screen == PICKER && pickFor == PICK_ENUM && enumRow == R_PHOTO_SOURCE) openEnum(R_PHOTO_SOURCE);
                    cellsDirty = true;
                    invalidate();
                });
            }
            if (isEnum(id)) {
                openEnum(id);
                return true;
            }
            switch (id) {
                case R_TMDB: case R_WATCHMODE:
                    if (!may(id == R_TMDB ? "tmdb" : "watchmode")) return false;
                    openKeyScreen(id == R_WATCHMODE);
                    return true;
                case R_PICKS_FROM:
                    lockList = false;
                    services.clear();
                    services.addAll(Recs.installedServices(c));
                    screen = PICK_FROM;
                    pickScroll = 0;
                    focus = 0;
                    return true;
                case R_TRAKT:
                    traktOk();
                    return false;
                case R_SWITCH:
                    ProfileManager.lock(c);
                    activity.finish();   // the home screen shows "Who's Watching?"
                    return false;
                case R_CHANGE_PIN:
                    askPin(PIN_OLD, 0, ProfileManager.current(c), id);
                    return true;
                case R_LOCKED_APPS:
                    if (lockNeedsCode()) askPin(PIN_LOCK, THEN_LOCKED_APPS, ProfileManager.current(c), id);
                    else openLockedApps();
                    return true;
                case R_APP_CODE:
                    if (lockNeedsCode()) askPin(PIN_LOCK, THEN_APP_CODE, ProfileManager.current(c), id);
                    else askPin(PIN_APP_NEW, 0, ProfileManager.current(c), id);
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
                case R_PHOTOS_REMOVE:
                    askRemovePhotos();
                    return false;
                case R_MOTION:
                case R_TRANSPARENCY:
                    openAccessibility();
                    return false;
                case R_CITY:
                    askCity();
                    return false;
                case R_DATE_TIME:
                    openDateTime();
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
                case R_INTRO_PREVIEW:
                    // The whole startup and its sound, then "Who's Watching?", as the home screen comes back.
                    HomeView.replayIntro = true;
                    activity.finish();
                    return false;
                case R_SETUP:
                    // The welcome setup: the home screen shows it as it comes back (Prefs.setupDone false).
                    Prefs.setSetupDone(false);
                    activity.finish();
                    return false;
                case R_CLEAN:
                    if (busy()) return false;
                    askPin(PIN_MANAGER, THEN_CLEAN, ProfileManager.manager(c), id);
                    return true;
                case R_REMOTE:
                    HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                    loadApps();
                    screen = REMOTE_KEYS;
                    focus = 0;
                    pickScroll = 0;
                    return true;
                case R_SLEEP:
                    pickScroll = 0;
                    screen = POWER;
                    focus = 0;
                    return true;
                case R_TV_SETTINGS:
                    // As Control Center's TV Settings: where nothing answers the standard request, Android TV's own settings.
                    HomeApps.open(activity, HomeApps.tvSettings(), null);
                    return false;
                case R_TV_CHANNELS: case R_TV_DISPLAY: case R_TV_NETWORK: case R_TV_ACCOUNTS: case R_TV_PRIVACY: case R_TV_APPS:
                case R_TV_SYSTEM: case R_TV_ACCESSIBILITY: case R_TV_REMOTES:
                    openTvPage(id);
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
                case R_PROFILE: case R_GUEST: case R_TOP_SHELF: case R_GEMINI:
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

        /** The TV's date and time page: only there can the time zone be changed. */
        private void openDateTime() {
            try {
                activity.startActivity(new Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (android.content.ActivityNotFoundException e) {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }

        /** mahitTV mode: on TCL, first what lets the switch's job start, asking for USB debugging as the switch itself would. */
        private void requestFree() {
            stalled = false;
            final Context app = getContext().getApplicationContext();
            if (!RemoteKeys.TCL) {
                requestedAt = HomeMode.request(app, HomeMode.FREE);
                pendingAt = SystemClock.uptimeMillis();
                return;
            }
            if (allowingBoot) return;
            allowingBoot = true;
            new Thread(() -> {
                HomeMode.allowAutoStart(app, () -> handler.post(() -> say(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."))));
                handler.post(() -> {
                    allowingBoot = false;
                    requestedAt = HomeMode.request(app, HomeMode.FREE);
                    pendingAt = SystemClock.uptimeMillis();
                });
            }, "auto-start").start();
        }

        private void say(String text) {
            if (whyToast != null) whyToast.cancel();
            whyToast = Toast.makeText(getContext(), text, Toast.LENGTH_LONG);
            whyToast.show();
        }

        /** Asks the main process for the original home screen (as Home Screen → Original always did), then shows it. */
        private void requestOriginal() {
            if (!originalNow() && !busy()) {
                requestedAt = HomeMode.request(getContext(), HomeMode.ORIGINAL);
                stalled = false;
                pendingAt = SystemClock.uptimeMillis();
            }
            openSection(S_SYSTEM, R_HOME);
        }

        // MARK: - Clean Up This TV (TVOS-LAUNCHER-MERGE-SPEC.md S1)

        /** Clean Up's rows: these two, then the plan's packages by index. */
        private static final int CLEAN_APPLY = -1, CLEAN_UNDO = -2, ARM_CLEAN = 1000;
        private final Set<String> cleanOff = new HashSet<>();   // the safe packages ticked to go off
        private long cleanAskedAt, cleanPlanAt = -1;
        private int[] cleanRowIds = new int[0];
        private JSONArray cleanItems = new JSONArray();
        private String cleanError = "", cleanModel = "", cleanFrom = "";
        private int cleanKind;
        private TextPaint cleanMeasure;   // made once, for long package names
        // What the rows show, made again only when the plan, a tick, the armed row or the language changes (not per frame).
        private String[] cleanLabels = new String[0], cleanValues = new String[0];
        private int cleanTicks, cleanUndo, cleanCacheTicks = -1, cleanCacheArmed = -1, cleanCacheGen = -1;
        private long cleanCachePlan = -2;

        /** After the manager's PIN: the screen, and this TV read again (the plan comes back through the state). */
        private void openClean() {
            screen = CLEAN;
            focus = 0;
            pickScroll = 0;
            armed = 0;
            cleanPlanAt = -1;
            cleanRowIds = new int[0];
            if (busy()) {   // another job is running (a new one would stop it): the last plan, if there is one
                cleanAskedAt = 0;
                return;
            }
            cleanAskedAt = System.currentTimeMillis();
            HomeMode.request(getContext(), HomeMode.CLEAN_SCAN);
            pendingAt = SystemClock.uptimeMillis();
        }

        /** Reads the plan from the state when a newer one arrives (with the poll, not per frame). */
        private void syncClean() {
            JSONObject clean = state.optJSONObject("clean");
            if (clean == null) {
                // Opened while another job ran and there's no plan yet: read the TV now that it's done.
                if (cleanAskedAt == 0 && !busy()) {
                    cleanAskedAt = System.currentTimeMillis();
                    HomeMode.request(getContext(), HomeMode.CLEAN_SCAN);
                    pendingAt = SystemClock.uptimeMillis();
                }
                return;
            }
            long at = clean.optLong("at");
            boolean fresh = at >= cleanAskedAt - 1000;
            if (!fresh || at == cleanPlanAt) return;
            cleanPlanAt = at;
            cleanError = clean.optString("error");
            cleanModel = clean.optString("title");
            cleanKind = clean.optInt("kind");
            String from = cleanKind == Debloat.EXACT ? Ui.trf("CleanYourTV's list for {model}.", "model", cleanModel)
                    : cleanKind == Debloat.SIMILAR ? Ui.trf("CleanYourTV's list for a similar TV: {model}.", "model", cleanModel)
                    : Ui.trf("CleanYourTV's list for the TV with the most apps in common: {model}.", "model", cleanModel);
            cleanFrom = cleanModel.isEmpty() ? "" : from;
            cleanItems = clean.optJSONArray("items");
            if (cleanItems == null) cleanItems = new JSONArray();
            cleanOff.clear();
            for (int i = 0; i < cleanItems.length(); i++) {
                JSONObject o = cleanItems.optJSONObject(i);
                if (o != null && o.optInt("w") == Debloat.SAFE) cleanOff.add(o.optString("p"));
            }
            cleanUndo = HomeMode.stringList(state, "debloated").size();
            List<Integer> ids = new ArrayList<>();
            if (!cleanOff.isEmpty()) ids.add(CLEAN_APPLY);
            if (cleanUndo > 0) ids.add(CLEAN_UNDO);
            for (int i = 0; i < cleanItems.length(); i++) ids.add(i);
            cleanRowIds = new int[ids.size()];
            for (int i = 0; i < cleanRowIds.length; i++) cleanRowIds[i] = ids.get(i);
            // The new plan can be shorter (apps just turned off drop out): keep the scroll and the focus inside it.
            int visible = rowCount();
            pickScroll = Math.max(0, Math.min(pickScroll, cleanRowIds.length - visible));
            focus = Math.min(focus, Math.max(0, visible - 1));
            whyRows = null;
            cellsDirty = true;
        }

        private JSONObject cleanItem(int id) {
            JSONObject o = id >= 0 ? cleanItems.optJSONObject(id) : null;
            return o == null ? new JSONObject() : o;
        }

        private String cleanLabel(int id) {
            if (id == CLEAN_APPLY) return Ui.plural("plural|one:Turn Off {n} App|other:Turn Off {n} Apps", cleanOff.size());
            if (id == CLEAN_UNDO) return Ui.plural("plural|one:Turn {n} App Back On|other:Turn {n} Apps Back On", cleanUndo);
            return cleanItem(id).optString("p");
        }

        private String cleanValue(int id) {
            if (id == CLEAN_APPLY) return armed == ARM_CLEAN ? Ui.tr("Press OK again") : "";
            if (id == CLEAN_UNDO) return "";
            JSONObject o = cleanItem(id);
            switch (o.optInt("w")) {
                case Debloat.SAFE: return cleanOff.contains(o.optString("p")) ? Ui.tr("clean|Turn off") : Ui.tr("clean|Keep");
                case Debloat.SYSTEM: return Ui.tr("Part of the system");
                case Debloat.TV_NEEDS: return Ui.tr("The TV needs it");
                case Debloat.WIFI: return Ui.tr("Saved the Wi-Fi");
                case Debloat.HOME: return Ui.tr("A home screen");
                default: return Ui.tr("Not checked");
            }
        }

        private String cleanHint(int id) {
            if (id == CLEAN_APPLY) {
                return armed == ARM_CLEAN ? Ui.tr("OK again turns them off. Any other key keeps them.")
                        : Ui.tr("Nothing is uninstalled: they're only turned off, and Original turns them back on.");
            }
            if (id == CLEAN_UNDO) return Ui.tr("Turns back on only what Clean Up turned off.");
            switch (cleanItem(id).optInt("w")) {
                case Debloat.SAFE: return Ui.tr("OK chooses whether this one goes off.");
                case Debloat.SYSTEM: return Ui.tr("Stays on: Android or the TV needs it to start, or it's a factory menu.");
                case Debloat.TV_NEEDS: return Ui.tr("Stays on: the TV's inputs or remote use it.");
                case Debloat.WIFI: return Ui.tr("Stays on: turning it off would erase a saved Wi-Fi network.");
                case Debloat.HOME: return Ui.tr("Stays on: only Home Screen switches home screens and screensavers.");
                default: return Ui.tr("Stays on: I couldn't check what the TV's inputs need. Try again later.");
            }
        }

        /** OK on Clean Up: an app's tick, or (twice) turning the ticked ones off, or turning Clean Up's back on. */
        private void activateClean() {
            if (busy() || cleanRowIds.length == 0) return;
            int id = cleanRowIds[pickScroll + focus];
            if (id == CLEAN_APPLY) {
                if (armed != ARM_CLEAN) {
                    armed = ARM_CLEAN;
                    return;
                }
                armed = 0;
                List<String> chosen = new ArrayList<>();
                for (int i = 0; i < cleanItems.length(); i++) {   // in the profile's order
                    String pkg = cleanItem(i).optString("p");
                    if (cleanOff.contains(pkg)) chosen.add(pkg);
                }
                if (chosen.isEmpty()) return;
                HomeMode.requestClean(getContext(), chosen);
            } else if (id == CLEAN_UNDO) {
                HomeMode.request(getContext(), HomeMode.UNCLEAN);
            } else {
                JSONObject o = cleanItem(id);
                if (o.optInt("w") != Debloat.SAFE) {
                    say(cleanHint(id));
                    return;
                }
                String pkg = o.optString("p");
                if (!cleanOff.remove(pkg)) cleanOff.add(pkg);
                cleanTicks++;
                return;
            }
            cleanAskedAt = System.currentTimeMillis();   // the job reads the TV again when it's done
            pendingAt = SystemClock.uptimeMillis();
        }

        /** The rows' labels (long package names cut in the middle) and values, for the current plan, ticks and language. */
        private void cleanCache() {
            if (cleanCachePlan == cleanPlanAt && cleanCacheTicks == cleanTicks && cleanCacheArmed == armed
                    && cleanCacheGen == Ui.generation && cleanLabels.length == cleanRowIds.length) return;
            cleanCachePlan = cleanPlanAt;
            cleanCacheTicks = cleanTicks;
            cleanCacheArmed = armed;
            cleanCacheGen = Ui.generation;
            if (cleanMeasure == null) cleanMeasure = new TextPaint(rowLabel);
            cleanLabels = new String[cleanRowIds.length];
            cleanValues = new String[cleanRowIds.length];
            for (int i = 0; i < cleanRowIds.length; i++) {
                String label = cleanLabel(cleanRowIds[i]);
                if (rowLabel.measureText(label) > 540 * k) {
                    label = TextUtils.ellipsize(label, cleanMeasure, 540 * k, TextUtils.TruncateAt.MIDDLE).toString();
                }
                cleanLabels[i] = label;
                cleanValues[i] = cleanValue(cleanRowIds[i]);
            }
        }

        // MARK: - PIN pad (MAHITTV-PROFILES-SPEC.md §1.4, §3)

        private static final int PIN_OLD = 0, PIN_NEW = 1, PIN_CONFIRM = 2, PIN_MANAGER = 3,
                PIN_LOCK = 4, PIN_APP_NEW = 5, PIN_APP_CONFIRM = 6;   // App Lock's code, and a new App Passcode
        /** What the manager's PIN was asked for. */
        private static final int THEN_SET = 1, THEN_REMOVE = 2, THEN_RESET = 3, THEN_ORIGINAL = 4, THEN_RESTORE = 5, THEN_CLEAN = 6,
                THEN_LOCKED_APPS = 7, THEN_LOCK_WITH = 8, THEN_APP_CODE = 9;
        // The pad's keys: 72 apart on the full screen, smaller in the sidebar so the ten fit its column (constructor).
        private float PAD_KEY = 72, PAD_GAP = 11, PAD_X;
        private static final float PAD_KEYS_TOP = 560, PAD_DOTS_Y = 420, PAD_DOT_R = 13, PAD_DOT_GAP = 58;
        private static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};

        private int pinStep, pinThen, pinFromScreen, pinFromRow;
        private String pinTitleText = "", pinReasonText = "";   // made with the rows, not per frame
        private ProfileManager.Profile pinFor;
        private String pinFirst = "", pinMessage = "";
        private final StringBuilder pinDigits = new StringBuilder();
        /** A remote passcode's entry (six arrows: PasscodePad); an older four-digit PIN keeps the digits row below. */
        private final PasscodePad pinPad = new PasscodePad();
        private boolean pinRemote;
        private int pinLen = 4;
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
            if (step == PIN_LOCK && !AppLock.hasCode(c)) {
                lockOk();
                return;
            }
            startPinStep(step);
        }

        private void startPinStep(int step) {
            if (screen != PIN_PAD) focus = 1;
            pinStep = step;
            Context c = getContext();
            // New passcodes are remote ones; one being checked is typed the way it was made.
            switch (step) {
                case PIN_OLD: pinRemote = ProfileManager.remotePin(c, pinFor); pinLen = ProfileManager.pinLength(c, pinFor); break;
                case PIN_MANAGER: {
                    ProfileManager.Profile m = ProfileManager.manager(c);
                    pinRemote = ProfileManager.remotePin(c, m);
                    pinLen = ProfileManager.pinLength(c, m);
                    break;
                }
                case PIN_LOCK: pinRemote = AppLock.remoteCode(c); pinLen = AppLock.codeLength(c); break;
                default: pinRemote = true; pinLen = PasscodePad.REMOTE_LENGTH;
            }
            if (!pinRemote) pinLen = 4;
            pinPad.start(pinRemote, pinLen);
            if (pinRemote) focus = 0;   // one place: the pad (its arrows type)
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
                case PIN_LOCK: return Ui.tr("Enter Passcode");
                case PIN_APP_NEW: return Ui.tr("Enter a New App Passcode");
                case PIN_APP_CONFIRM: return Ui.tr("Enter the New Passcode Again");
                default: return Ui.trf("Enter {name}'s PIN", "name", ProfileManager.manager(c).name);
            }
        }

        private String pinReason() {
            if (pinStep == PIN_LOCK || pinStep == PIN_APP_NEW || pinStep == PIN_APP_CONFIRM) return Ui.tr("App Lock");
            if (pinStep != PIN_MANAGER) return pinFor.name;
            switch (pinThen) {
                case THEN_SET: return Ui.trf("To set {name}'s PIN", "name", pinFor.name);
                case THEN_REMOVE: return Ui.trf("To remove {name}'s PIN", "name", pinFor.name);
                case THEN_RESET: return Ui.tr("To reset all mahitTV data");
                case THEN_CLEAN: return Ui.tr("To clean up this TV");
                default: return Ui.tr("To restore the TV's original home screen");
            }
        }

        /** The pad's own keys: digits (the remote's number keys too), ← →, Back (deletes, then leaves). OK goes on to activate. */
        private boolean pinKey(KeyEvent e, boolean down) {
            int code = e.getKeyCode();
            if (pinRemote) {   // six arrows: nothing on screen shows which (PasscodePad); Back erases, then leaves
                switch (pinPad.key(e)) {
                    case PasscodePad.COMPLETE:
                        pinMessage = "";
                        handler.postDelayed(this::submitPin, 160);   // the last dot shows first
                        break;
                    case PasscodePad.BACK:
                        leavePin();
                        spoken.focusMoved();
                        break;
                    case PasscodePad.TYPED:
                        pinMessage = "";
                        break;
                    default:
                        break;
                }
                invalidate();
                return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_DPAD_UP
                        || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT
                        || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER
                        || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                        || code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9;
            }
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
            if (pinRemote) return;
            boolean checks = pinStep == PIN_OLD || pinStep == PIN_MANAGER || pinStep == PIN_LOCK;
            if (checks && (pinStep == PIN_LOCK ? AppLock.waitLeft(getContext()) : ProfileManager.waitLeft(pinChecked())) > 0) {
                if (pinStep == PIN_LOCK) wrongLock(); else wrongPin(pinChecked());
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
            String d = pinRemote ? pinPad.code() : pinDigits.toString();
            if (screen != PIN_PAD || d.length() != pinLen) return;
            Context c = getContext();
            switch (pinStep) {
                case PIN_LOCK:
                    if (AppLock.check(c, d)) lockOk(); else wrongLock();
                    break;
                case PIN_APP_NEW:
                    pinFirst = d;
                    startPinStep(PIN_APP_CONFIRM);
                    break;
                case PIN_APP_CONFIRM:
                    if (d.equals(pinFirst)) {
                        AppLock.setAppCode(c, d);
                        if (AppLock.WITH_APP.equals(pendingWith)) AppLock.setWith(c, AppLock.WITH_APP);
                        pendingWith = "";
                        AppLock.profileChanged(c);
                        lockVerified = true;
                        say(Ui.tr("App Passcode changed."));
                        leavePin();
                    } else {
                        startPinStep(PIN_APP_NEW);
                        fail(Ui.tr("The passcodes didn't match. Try again."));
                    }
                    break;
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
                default:   // PIN_CONFIRM
                    if (d.equals(pinFirst)) {
                        ProfileManager.setPin(c, pinFor, d);
                        AppLock.profileChanged(c);   // the remote's buttons know whether there's a code to ask
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
                    say(Ui.trf("{name} has no PIN now.", "name", pinFor.name));
                    leavePin();
                    return;
                case THEN_RESET:
                    ProfileManager.resetAll(c);
                    activity.finish();   // the home screen, locked: "Who's Watching?" with the default profiles
                    return;
                case THEN_CLEAN:
                    pinDigits.setLength(0);
                    openClean();
                    for (Spring s : pop) s.snap(0);
                    return;
                default:   // THEN_ORIGINAL, THEN_RESTORE
                    pinDigits.setLength(0);
                    requestOriginal();
                    for (Spring s : pop) s.snap(0);
            }
        }

        private void wrongLock() {
            long wait = AppLock.waitLeft(getContext());
            fail(wait > 0 ? Ui.trf("Too many tries. Wait {n} s.", "n", (wait + 999) / 1000) : Ui.tr("Wrong passcode. Try again."));
        }

        private void wrongPin(ProfileManager.Profile p) {
            long wait = ProfileManager.waitLeft(p);
            fail(wait > 0 ? Ui.trf("Too many tries. Wait {n} s.", "n", (wait + 999) / 1000) : Ui.tr("Wrong PIN. Try again."));
        }

        /** Wrong: the head-shake (±18 px), the knock, the dots clear and the message says why. */
        private void fail(String message) {
            pinMessage = message;
            pinDigits.setLength(0);
            if (pinRemote) pinPad.fail();
            for (Spring s : dots) s.snap(1);
            shake.impulse(Spring.BUMP_VELOCITY);
            Click.boundary();
            announceForAccessibility(message);
        }

        private void drawPinPad(Canvas c, float dt) {
            shake.step(dt);
            for (Spring s : dots) s.step(dt);
            if (pinRemote) {
                drawRemotePad(c, dt);
                return;
            }
            float dx = shake.value * k, x0 = LIST_X * k, mid = (LIST_X + LIST_R) / 2f * k;
            c.drawText(pinTitleText, x0 + dx, TITLE_BASE * k, paneTitle);
            rowLabel.setColor(secondary);
            c.drawText(pinReasonText, x0 + dx, (TITLE_BASE + 54) * k, rowLabel);
            white.setColor(primary);
            ring.setColor(Ui.alpha(primary, 0.55f));
            for (int i = 0; i < 4; i++) {
                float cx = mid + (i - 1.5f) * PAD_DOT_GAP * k + dx, cy = PAD_DOTS_Y * k, r = PAD_DOT_R * k * dots[i].value;
                c.drawCircle(cx, cy, r, i < pinDigits.length() ? white : ring);
            }
            if (!pinMessage.isEmpty()) c.drawText(pinMessage, mid - warn.measureText(pinMessage) / 2 + dx, (PAD_DOTS_Y + 74) * k, warn);
            // The keys: grouped glass squircles; the focused one a white key lifted 1.12×.
            for (int i = 0; i < 10; i++) {
                float f = Ui.clamp(pop[i].value), s = 1 + 0.12f * f;
                float cx = (PAD_X + i * (PAD_KEY + PAD_GAP) + PAD_KEY / 2) * k + dx, cy = (PAD_KEYS_TOP + PAD_KEY / 2) * k;
                float half = PAD_KEY / 2 * k * s, r = 20 * k * s;
                tmp.set(cx - half, cy - half, cx + half, cy + half);
                if (f > 0.01f) {
                    tmp.offset(0, 8 * k * f);
                    shadow.setColor(Ui.alpha(0xFF000000, 0.3f * f));
                    c.drawRoundRect(tmp, r, r, shadow);
                    tmp.offset(0, -8 * k * f);
                }
                c.drawRoundRect(tmp, r, r, groupFill);
                c.drawRoundRect(tmp, r, r, groupRim);
                if (f > 0.01f) {
                    white.setColor(Ui.alpha(0xFFFFFFFF, f));
                    c.drawRoundRect(tmp, r, r, white);
                }
                digit.setColor(blend(primary, INK, f));
                c.drawText(DIGITS[i], cx, cy + Ui.halfCap(digit), digit);
            }
        }

        /** A remote passcode: the six lines that become dots, how to type it, or what went wrong. */
        private void drawRemotePad(Canvas c, float dt) {
            if (pinPad.step(dt, true)) invalidate();
            float dx = pinPad.shakeX(k), x0 = LIST_X * k, mid = (LIST_X + LIST_R) / 2f * k;
            c.drawText(pinTitleText, x0 + dx, TITLE_BASE * k, paneTitle);
            rowLabel.setColor(secondary);
            c.drawText(pinReasonText, x0 + dx, (TITLE_BASE + 54) * k, rowLabel);
            pinPad.draw(c, mid, PAD_DOTS_Y * k, k, primary, GREEN, dark, 1);
            if (!pinMessage.isEmpty()) {
                c.drawText(pinMessage, mid - warn.measureText(pinMessage) / 2 + dx, (PAD_DOTS_Y + 84) * k, warn);
            } else {
                String hint = Ui.tr("Use ↑ ↓ ← → on your remote");
                rowLabel.setColor(tertiary);
                c.drawText(hint, mid - rowLabel.measureText(hint) / 2, (PAD_DOTS_Y + 84) * k, rowLabel);
            }
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
                    return id == R_HOME ? Capabilities.NEED_SWITCH : id == R_POWER_ON ? Capabilities.NEED_LIVE_TV
                            : id == R_REMOTE ? Capabilities.NEED_REMOTE_BUTTONS : id == R_SLEEP || id == R_CLEAN ? Capabilities.NEED_ADB
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
            return new int[] {P_SLEEP};   // the time left shows in the dynamic dot only, so no Show or Position rows
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

        /**
         * Remote Buttons' rows: each button, and (where the remote allows it, RemoteKeys.doublePress) the same button
         * pressed twice and held. Row r is button r / 3 (r with neither); r % 3 is 0 (press), 1 (twice) or 2 (held).
         */
        private int keyRowCount() {
            return Prefs.KEYS.length * keyRowsPerButton();
        }

        private static int keyRowsPerButton() {
            return RemoteKeys.doublePress() ? 3 : 1;
        }

        private int keyOfRow(int row) {
            return row / keyRowsPerButton();
        }

        private boolean twiceRow(int row) {
            return RemoteKeys.doublePress() && row % 3 == 1;
        }

        private boolean heldRow(int row) {
            return RemoteKeys.doublePress() && row % 3 == 2;
        }

        /** A second or held app: "Off" (none) is a choice there. */
        private boolean optionalRow(int row) {
            return twiceRow(row) || heldRow(row);
        }

        private String keyRowApp(int row) {
            int key = Prefs.KEYS[keyOfRow(row)];
            return heldRow(row) ? Prefs.keyLongApp(key) : twiceRow(row) ? Prefs.keyDoubleApp(key) : Prefs.keyApp(key);
        }

        private void setKeyRowApp(int row, String pkg) {
            int key = Prefs.KEYS[keyOfRow(row)];
            if (heldRow(row)) Prefs.setKeyLongApp(key, pkg);
            else if (twiceRow(row)) Prefs.setKeyDoubleApp(key, pkg);
            else Prefs.setKeyApp(key, pkg);
            keyVersion++;
        }

        /** Back on Remote Buttons at row `row`, scrolled so it shows. */
        private void focusKeyRow(int row) {
            screen = REMOTE_KEYS;
            keyVersion++;   // read the choices again on the way in
            pickScroll = Math.max(0, Math.min(row - SETTINGS_VISIBLE + 1, keyRowCount() - SETTINGS_VISIBLE));
            pickScroll = Math.max(0, Math.min(pickScroll, row));
            focus = row - pickScroll;
        }

        // Remote Buttons' labels and values, made again only when a choice, the app list or the language changes:
        // reading a choice checks remote-keys.txt, so not per row per frame.
        private String[] keyLabelCache, keyValueCache;
        private int keyVersion, keyCacheVersion = -1, keyCacheGen = -1;

        private void keyCache() {
            if (keyLabelCache != null && keyCacheVersion == keyVersion && keyCacheGen == Ui.generation) return;
            keyCacheVersion = keyVersion;
            keyCacheGen = Ui.generation;
            keyLabelCache = makeKeyLabels();
            keyValueCache = makeKeyValues();
        }

        private String[] keyLabels() {
            keyCache();
            return keyLabelCache;
        }

        private String[] keyValues() {
            keyCache();
            return keyValueCache;
        }

        private String[] makeKeyLabels() {
            String[] buttons = buttonLabels();
            if (!RemoteKeys.doublePress()) return buttons;
            String[] out = new String[buttons.length * 3];
            for (int i = 0; i < buttons.length; i++) {
                out[3 * i] = buttons[i];
                out[3 * i + 1] = Ui.trf("{button}, pressed twice", "button", buttons[i]);
                out[3 * i + 2] = Ui.trf("{button}, held", "button", buttons[i]);
            }
            return out;
        }

        private String[] buttonLabels() {
            if (RemoteKeys.GOOGLE_STREAMER) return new String[] {Ui.tr("YouTube button"), Ui.tr("Netflix button"),
                    Ui.tr("Custom button") + " \u2605"};
            if (RemoteKeys.JVC_SKYWORTH) return new String[] {Ui.tr("Inputs"), Ui.tr("Settings button")};
            if (RemoteKeys.HISENSE) return new String[] {Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Disney+ button"), Ui.tr("All Apps button"), Ui.tr("Settings button"), Ui.tr("Profile button")};
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Tabii button")};
            if (RemoteKeys.XIAOMI_PATCHWALL_ONLY) return new String[] {Ui.tr("PatchWall button")};
            if (RemoteKeys.XIAOMI) return new String[] {Ui.tr("Mi button"), Ui.tr("YouTube button"), Ui.tr("PatchWall button"),
                    Ui.tr("Okko button"), Ui.tr("VK button"), Ui.tr("Rutube button"), Ui.tr("KION button"),
                    Ui.tr("Wink button"), Ui.tr("IVI button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("BRAVIA CORE button"),
                    Ui.tr("Netflix button"), Ui.tr("Disney+ button"),
                    Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Crunchyroll button"), Ui.tr("Google Play button"), Ui.tr("SonyLIV button")};
            return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                    Ui.tr("Prime Video button"), Ui.tr("TCL Channel button"), Ui.tr("Settings button")};
        }

        private String[] makeKeyValues() {
            String[] v = new String[keyRowCount()];
            for (int i = 0; i < v.length; i++) {
                String pkg = keyRowApp(i);
                int at = appPkgs.indexOf(pkg);
                v[i] = optionalRow(i) && pkg.isEmpty() ? Ui.tr("Off") : at >= 0 ? appLabels.get(at) : Ui.tr("Not installed");
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
            // An empty choice leaves the key to the system's receiver.
            appLabels.add(RemoteKeys.GOOGLE_STREAMER ? Ui.tr("Google default")
                    : RemoteKeys.SONY ? Ui.tr("Sony default") : RemoteKeys.XIAOMI ? Ui.tr("Xiaomi default") : Ui.tr("Nothing"));
            if (RemoteKeys.JVC_SKYWORTH) {
                appPkgs.add(Prefs.TV_SHORTCUTS);
                appLabels.add(Ui.tr("Inputs"));
                appPkgs.add(Prefs.QUICK_SETTINGS);
                appLabels.add(Ui.tr("Quick Settings"));
            }
            if (RemoteKeys.TCL) {
                appPkgs.add(Prefs.QUICK_PANEL);
                appLabels.add(Ui.tr("Quick Settings"));
                // The Settings button's default, listed even where TV Settings has no launcher entry.
                boolean listed = false;
                for (String[] f : found) listed |= f[0].equals("com.android.tv.settings");
                if (!listed) {
                    appPkgs.add("com.android.tv.settings");
                    appLabels.add(Ui.tr("TV Settings"));
                }
            }
            // On Xiaomi the Mi button is the remote's Home button: it can open this home screen.
            if (RemoteKeys.XIAOMI) {
                appPkgs.add(HomeMode.PKG);
                appLabels.add("mahitTV");
            }
            if (RemoteKeys.HISENSE || RemoteKeys.GOOGLE_STREAMER) {
                appPkgs.add(Prefs.ALL_APPS);
                appLabels.add(Ui.tr("All Apps"));
            }
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
            keyVersion++;
        }

        /** OK on a switch (or a Sleep Timer row): the other value, or the next one. */
        private void adjust(int dir) {
            // Refused or without mahitTV mode, Now Playing can still be turned off (and the refusal is then forgotten).
            boolean turnOff = screen == SECTION && rows[focus] == R_PLAYING && Prefs.nowPlaying();
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
            if (screen == PICK_FROM) {
                toggleService();
                return;
            }
            if (screen != SECTION) return;
            int id = rows[focus];
            if (id >= R_LIGHT) return;   // read only
            if (isSwitch(id)) {   // its sound as it flips: rising on, falling off
                Click k = Click.current();
                if (k != null) k.toggle(!switchOn(id));
            }
            if (id >= R_COIN) {
                if (!Prefs.quotes()) return;
                Prefs.setCoin(id - R_COIN, !Prefs.coin(id - R_COIN));
                HomeData.get(getContext()).settingsChanged();
                return;
            }
            switch (id) {
                case R_PHOTOS:
                    if (Photos.files(getContext()).isEmpty()) return;
                    Prefs.setPhotos(!Prefs.photos());
                    return;
                case R_INTRO: Prefs.setIntro(!Prefs.intro()); return;
                case R_CARTOONS: Prefs.setHideAnimation(!Prefs.hideAnimation()); return;
                case R_SOUNDS: Prefs.setSounds(!Prefs.sounds()); break;
                case R_SAVER: Prefs.setSaverWatch(!Prefs.saverWatch()); break;
                case R_WEATHER: Prefs.setWeather(!Prefs.weather()); break;
                case R_PLAYING:   // the listener follows the widget: allowed while it's on, taken back when it goes off
                    Prefs.setNowPlaying(!Prefs.nowPlaying());
                    HomeMode.nowPlayingAccess(getContext());
                    break;
                case R_QUOTES:
                    Prefs.setQuotes(!Prefs.quotes());
                    layoutSection();   // the coins' footer comes or goes
                    break;
                case R_SYSTEM_APPS:
                    Prefs.setSystemApps(!Prefs.systemApps());
                    appCount = -1;
                    countApps();
                    return;
                case R_GLOW:
                    Prefs.setGlow(!Prefs.glowOn(), Prefs.glowStyle());
                    openSection(section, R_GLOW);   // Glow Style comes and goes with it
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
            AlertDialog dialog = new AlertDialog.Builder(activity, dialogTheme())
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
            lightDim(dialog);
        }

        /** The system dialog matching the Horizon: light by day, dark at night. */
        private int dialogTheme() {
            return dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        }

        /** The system dims the whole screen about 60% behind a dialog: only a light dim, so the Horizon stays bright. */
        private static void lightDim(AlertDialog dialog) {
            if (dialog.getWindow() != null) dialog.getWindow().setDimAmount(0.15f);
        }

        // MARK: - My Photos (sent from a computer: tools/photos.py)

        /** OK on My Photos: asks, then deletes them all. */
        private void askRemovePhotos() {
            int n = Photos.files(getContext()).size();
            if (n == 0) return;
            AlertDialog dialog = new AlertDialog.Builder(activity, dialogTheme())
                    .setTitle(Ui.plural("plural|one:Remove {n} photo?|other:Remove {n} photos?", n))
                    .setPositiveButton(Ui.tr("Remove"), (d, which) -> {
                        Photos.removeAll(getContext());
                        if (screen == SECTION && section == S_APPEARANCE) openSection(S_APPEARANCE, R_PHOTOS);
                        invalidate();
                        spoken.focusMoved();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
            lightDim(dialog);
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
            return screen == PICKER || screen == SHELF_EDIT || screen == CLEAN || screen == REMOTE_KEYS || screen == PICK_FROM;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : screen == CLEAN ? cleanRowIds.length
                    : screen == REMOTE_KEYS ? keyRowCount() : screen == PICK_FROM ? services.size() : pickLabels.size();
        }

        // MARK: - Keys, picks and sign-ins from upstream

        /** Only the manager changes a key, a sign-in or a choice for the whole TV (RowAccess). */
        private boolean may(String row) {
            ProfileManager.Profile me = ProfileManager.current(getContext());
            return RowAccess.mayOpen(row, me.manager(), me.guest());
        }

        private static String keyLabel(int id) {
            return id == K_NEW ? Ui.tr("New code") : id == K_TYPE ? Ui.tr("Type it with the remote") : Ui.tr("Remove the key");
        }

        /** A new page and its code (a new port and token); the old one stops. */
        private void startKeyPage() {
            stopKeyPage();
            InetAddress lan = KeyPage.lanAddress(getContext());
            if (lan == null) {
                setKeyStatus(Ui.tr("The TV isn't on a network: connect it, or type the key."), true);
            } else {
                try {
                    keyPage = new KeyPage(lan, keyWatchmode, keyListener);
                    keyQr = qrBitmap(Qr.encode(keyPage.url));
                    // While the code waits (5 minutes at most), the TV's screensaver doesn't cover it.
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    setKeyStatus(Ui.tr("Waiting for your phone. The code works for 5 minutes."), false);
                } catch (IOException | RuntimeException e) {
                    stopKeyPage();
                    setKeyStatus(Ui.trf("Error: {error}.", "error", e.getMessage()), true);
                }
            }
            keyRowsChanged();
        }

        void stopKeyPage() {
            if (keyPage != null) keyPage.close();
            keyPage = null;
            keyQr = null;
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        /** The QR code's modules as a bitmap, one pixel each, with the light margin readers need (4 modules). */
        private static Bitmap qrBitmap(boolean[][] modules) {
            int quiet = 4, n = modules.length + 2 * quiet;
            int[] pixels = new int[n * n];
            java.util.Arrays.fill(pixels, 0xFFFFFFFF);
            for (int y = 0; y < modules.length; y++) {
                for (int x = 0; x < modules.length; x++) if (modules[y][x]) pixels[(y + quiet) * n + x + quiet] = 0xFF000000;
            }
            return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888);
        }

        /** Off the UI thread: checks the key with TMDB and saves it if it works. Null when it's saved, else why not. */
        private String saveKey(String pasted) {
            String key = Recs.cleanKey(pasted);
            String why = key.isEmpty() ? Ui.tr("That doesn't look like a TMDB key.") : Recs.checkKey(key);
            if (why == null) Prefs.setTmdbKey(key);
            keyChecked(why, false, Ui.tr("Saved. The picks arrive in a moment."));
            return why;
        }

        /** As saveKey, for the Watchmode key. */
        private String saveWatchmodeKey(String pasted) {
            String key = Recs.cleanKey(pasted);
            String why = key.isEmpty() ? Ui.tr("That doesn't look like a Watchmode key.") : Recs.checkWatchmodeKey(key);
            if (why == null) Prefs.setWatchmodeKey(key);
            keyChecked(why, true, Ui.tr("Saved. The picks' pages arrive in a moment."));
            return why;
        }

        private void keyChecked(String why, boolean watchmode, String saved) {
            handler.post(() -> {
                keyChecking = false;
                if (why == null) Recs.get(getContext()).refreshIfStale();
                if (watchmode == keyWatchmode) {   // not if the other key's screen opened meanwhile
                    if (why == null) {
                        stopKeyPage();   // typed: the phone's page has nothing left to do
                        setKeyStatus(saved, false);
                    } else {
                        setKeyStatus(why, true);
                    }
                }
                keyRowsChanged();
            });
        }

        /** The rows for now: a new code once the last one is used up, typing, and removing a key given on the TV. */
        private void keyRowsChanged() {
            int was = focus < keyRowIds.length ? keyRowIds[focus] : K_TYPE;
            int[] ids = new int[3];
            int n = 0;
            if (keyPage == null && !keyChecking) ids[n++] = K_NEW;
            ids[n++] = K_TYPE;
            if (!(keyWatchmode ? Prefs.watchmodeKey() : Prefs.tmdbKey()).isEmpty()) ids[n++] = K_REMOVE;
            boolean added = n > 0 && ids[0] == K_NEW && (keyRowIds.length == 0 || keyRowIds[0] != K_NEW);
            keyRowIds = java.util.Arrays.copyOf(ids, n);
            cellsDirty = true;
            if (screen != TMDB_KEY) return;
            focus = 0;   // a new code just became possible: there first
            if (!added) for (int i = 0; i < n; i++) if (ids[i] == was) focus = i;
            invalidate();
            spoken.focusMoved();   // the rows changed under TalkBack: it reads the focused one again
        }

        private void activateKey() {
            if (focus >= keyRowIds.length) return;
            switch (keyRowIds[focus]) {
                case K_NEW: startKeyPage(); break;
                case K_TYPE: askKey(); break;
                default: askRemoveKey();
            }
        }

        /** The fallback: the key typed with the remote (the on-screen keyboard), then checked as a phone's would be. */
        private void askKey() {
            if (keyChecking) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(keyWatchmode ? Ui.tr("Watchmode key") : Ui.tr("API Read Access Token or API Key"));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            AlertDialog dialog = new AlertDialog.Builder(activity, dialogTheme())
                    .setTitle(keyWatchmode ? Ui.tr("Watchmode key") : Ui.tr("TMDB key"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Save"), (d, which) -> typedKey(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                typedKey(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void typedKey(String text) {
            if (text.trim().isEmpty() || keyChecking) return;
            keyChecking = true;
            boolean watchmode = keyWatchmode;
            setKeyStatus(watchmode ? Ui.tr("Checking the key with Watchmode…") : Ui.tr("Checking the key with TMDB…"), false);
            keyRowsChanged();
            new Thread(() -> {
                if (watchmode) saveWatchmodeKey(text);
                else saveKey(text);
            }, "Key").start();
        }

        /** Asks, then forgets the key given on the TV (one built in from config.properties is used again). */
        private void askRemoveKey() {
            AlertDialog dialog = new AlertDialog.Builder(activity, dialogTheme())
                    .setTitle(keyWatchmode ? Ui.tr("Remove the Watchmode key?") : Ui.tr("Remove the TMDB key?"))
                    .setPositiveButton(Ui.tr("Remove"), (d, which) -> {
                        if (keyWatchmode) Prefs.setWatchmodeKey("");
                        else Prefs.setTmdbKey("");
                        keyStatus = null;   // the row says it
                        keyRowsChanged();
                        spoken.focusMoved();
                    })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();   // one stray OK keeps it
        }

        /** "Some" only when one of the services the TV has is off (one it doesn't have, a typo in hide_services, or one found by name that starts off, doesn't count). */
        private void countServicesOff() {
            someServicesOff = false;
            for (String[] service : Recs.installedServices(getContext())) someServicesOff |= service[0].indexOf('.') < 0 && Prefs.serviceOff(service[0]);
        }

        private void toggleService() {
            int at = pickScroll + focus;
            if (at >= services.size()) return;
            String key = services.get(at)[0];
            Click k = Click.current();
            if (k != null) k.toggle(!serviceOn(key));
            if (lockList) AppLock.setLocked(getContext(), key, !AppLock.locked(getContext(), key));
            else Prefs.setServiceOff(key, !Prefs.serviceOff(key));
            cellsDirty = true;
        }

        /** The switch of `key` in the PICK_FROM list: a service in the picks, or (App Lock's list) an app locked. */
        private boolean serviceOn(String key) {
            return lockList ? AppLock.locked(getContext(), key) : !Prefs.serviceOff(key);
        }

        // MARK: - App Lock (AppLock, DESIGN-PASSCODE.md)

        /** PICK_FROM shows App Lock's apps (else the picks' services). */
        private boolean lockList;
        /** The lock's code was given in this visit to Settings: its rows open without it again. */
        private boolean lockVerified;
        private String pendingWith = "";

        /** Changing App Lock asks for its code first, once, while apps are locked with one. */
        private boolean lockNeedsCode() {
            Context c = getContext();
            return !lockVerified && !AppLock.lockedApps(c).isEmpty() && AppLock.hasCode(c);
        }

        /** Every app on the TV (but mahitTV), A to Z, each with its lock switch. */
        private void openLockedApps() {
            Context c = getContext();
            services.clear();
            for (String[] app : HomeApps.launchable(c)) {
                if (!app[0].equals(HomeMode.PKG)) services.add(new String[] {app[0], app[1]});
            }
            java.util.Collections.sort(services, (a, b) -> a[1].compareToIgnoreCase(b[1]));
            lockList = true;
            screen = PICK_FROM;
            pickScroll = 0;
            focus = 0;
            cellsDirty = true;
        }

        /** Unlock With is now `pendingWith`: a profile without a passcode makes one first, an App Passcode is set first. */
        private void applyWith() {
            Context c = getContext();
            ProfileManager.Profile me = ProfileManager.current(c);
            if (AppLock.WITH_APP.equals(pendingWith) && !AppLock.hasAppCode(c)) {
                askPin(PIN_APP_NEW, 0, me, R_LOCK_WITH);   // set with it once it's made
                return;
            }
            AppLock.setWith(c, pendingWith);
            AppLock.profileChanged(c);
            if (AppLock.WITH_PROFILE.equals(pendingWith) && !ProfileManager.needsPin(c, me)) {
                askPin(PIN_OLD, 0, me, R_LOCK_WITH);   // no passcode yet: make one (PIN_OLD is skipped)
                return;
            }
            openSection(section, R_LOCK_WITH);
        }

        /** The lock's code was right: what it was asked for. */
        private void lockOk() {
            lockVerified = true;
            switch (pinThen) {
                case THEN_LOCKED_APPS:
                    openLockedApps();
                    for (Spring s : pop) s.snap(0);
                    return;
                case THEN_LOCK_WITH:
                    applyWith();
                    return;
                default:   // THEN_APP_CODE
                    startPinStep(PIN_APP_NEW);
            }
        }

        private String traktValue() {
            Context c = getContext();
            String code = Trakt.code();
            if (code != null) return Ui.trf("{url}, code {code}", "url", Trakt.url(), "code", code);
            if (Trakt.busy()) return Ui.tr("Connecting…");
            if (Trakt.signedIn(c)) return Trakt.user(c).isEmpty() ? Ui.tr("Signed in") : Trakt.user(c);
            return Trakt.message().isEmpty() ? Ui.tr("Sign in") : Trakt.message();
        }

        /** OK on the Trakt row: shows a code to sign in with, or asks before signing out. */
        private void traktOk() {
            Context c = getContext();
            if (!Trakt.signedIn(c)) {
                // TalkBack reads the row again at each step: the code as it appears, then signed in (or why not).
                Trakt.signIn(c, () -> handler.post(() -> {
                    invalidate();
                    spoken.focusMoved();
                }));
                return;
            }
            String user = Trakt.user(c).isEmpty() ? "Trakt" : Trakt.user(c);
            new AlertDialog.Builder(activity, dialogTheme())
                    .setTitle("Trakt")
                    .setMessage(Ui.trf("Sign out of {name}? The picks go back to TMDB's alone.", "name", user))
                    .setPositiveButton(Ui.tr("Sign out"), (d, which) -> { Trakt.signOut(c); invalidate(); })
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .show();
        }

        /**
         * Why a switch never started, and what to do. TCL's TclAppBoot blocks the job until its permission is allowed
         * from a computer, and an uninstall takes that back (#364, #375): the README's command, for both of its names.
         */
        private static String stalledText() {
            return RemoteKeys.TCL
                    ? Ui.trf("TCL blocks mahitTV from running in the background, so nothing changed. From a computer, "
                            + "run this, then try again: {command}", "command",
                            "adb shell \"appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow\"")
                    : Ui.tr("The TV didn't let mahitTV run in the background, so nothing changed. Try again in a minute.");
        }

        private final KeyPage.Listener keyListener = new KeyPage.Listener() {
            @Override public void checking() {
                handler.post(() -> {
                    keyChecking = true;
                    setKeyStatus(keyWatchmode ? Ui.tr("Checking the key with Watchmode…") : Ui.tr("Checking the key with TMDB…"), false);
                    keyRowsChanged();
                });
            }

            @Override public String accept(String key, boolean watchmode) {
                return watchmode ? saveWatchmodeKey(key) : saveKey(key);
            }

            @Override public void closed(KeyPage page, boolean expired) {
                handler.post(() -> {
                    if (page != keyPage) return;   // an older page, or the key came another way
                    stopKeyPage();
                    if (expired) setKeyStatus(Ui.tr("This code has expired."), false);
                    keyRowsChanged();
                });
            }
        };

        private void openKeyScreen(boolean watchmode) {
            keyWatchmode = watchmode;
            screen = TMDB_KEY;
            pickScroll = 0;
            focus = 0;
            keyStatus = null;
            keyRowIds = new int[0];
            // The steps, in the TV's language, beside the code; the phone's page says the same.
            String steps = Ui.tr("Free, and it takes two minutes:") + (watchmode
                    ? "\n1. " + Ui.tr("Create an account at api.watchmode.com.")
                    + "\n2. " + Ui.tr("Copy the API key it gives you.")
                    + "\n3. " + Ui.tr("Scan this code with your phone and paste the key there.")
                    : "\n1. " + Ui.tr("Create an account at themoviedb.org.")
                    + "\n2. " + Ui.tr("Open Settings → API and request a developer key.")
                    + "\n3. " + Ui.tr("Scan this code with your phone and paste the API Read Access Token (or the API Key) there."));
            int width = Math.round((LIST_R - LIST_X - 2 * INSET - QR_SIDE - 40) * k);
            keySteps = StaticLayout.Builder.obtain(steps, 0, steps.length(), footerPaint, width).setLineSpacing(6 * k, 1).build();
            String purpose = watchmode ? Ui.tr("The key makes a pick open the title's page in its app, where the app allows it.")
                    : Ui.tr("The key brings the picks: what's trending on your streaming apps, with their photos on the screensaver.");
            keyPurpose = purpose;
            keyHelp = purpose + " " + steps.replace('\n', ' ');
            startKeyPage();
        }

        private void setKeyStatus(String text, boolean error) {
            keyStatus = text;
            keyStatusError = error;
            cellsDirty = true;
            if (screen == TMDB_KEY && running) announceForAccessibility(text);   // not over another app
            invalidate();
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
                if (optionalRow(which - 1)) pickLabels.set(0, Ui.tr("Off"));   // none: the first app opens at once
                pickCurrent = appPkgs.indexOf(keyRowApp(which - 1));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        private void openWallpaperPicker() {
            wallpaperPicker.open(Prefs.wallpaper());
            cacheWallpaperLabels();
            screen = WALLPAPER_PICKER;
            focus = wallpaperPicker.draw().category;
        }

        private void moveWallpaper(int direction) {
            wallpaperPicker.move(direction);
            cacheWallpaperLabels();
            focus = wallpaperPicker.draw().optionsPane ? wallpaperPicker.draw().option : wallpaperPicker.draw().category;
            invalidate();
            spoken.focusMoved();
        }

        private void cacheWallpaperLabels() {
            WallpaperPicker.DrawData data = wallpaperPicker.draw();
            wallpaperCategoryLabels = new String[data.categories.length];
            for (int i = 0; i < data.categories.length; i++) wallpaperCategoryLabels[i] = wallpaperCategoryName(data.categories[i]);
            wallpaperOptionLabels = new String[data.options.length];
            for (int i = 0; i < data.options.length; i++) wallpaperOptionLabels[i] = wallpaperOptionName(data.options[i]);
        }

        private static String wallpaperCategoryName(String category) {
            if ("horizon".equals(category)) return Ui.tr("Apple Frosted Horizon");
            if ("aerial".equals(category)) return Ui.tr("Apple 4K Aerials");
            if ("studio".equals(category)) return Ui.tr("Apple Studio Solids");
            return Ui.tr("Live Aerial Screensaver");
        }

        private static String wallpaperOptionName(String id) {
            if ("horizon:auto".equals(id)) return Ui.tr("Auto Sunset");
            if ("horizon:light".equals(id)) return Ui.tr("Light");
            if ("horizon:dark".equals(id)) return Ui.tr("Dark");
            if ("aerial:yosemite".equals(id)) return Ui.tr("Yosemite");
            if ("aerial:iceland".equals(id)) return Ui.tr("Iceland");
            if ("aerial:north-america-aurora".equals(id)) return Ui.tr("North America Aurora");
            if ("aerial:palau-coral".equals(id)) return Ui.tr("Palau Coral");
            if ("aerial:new-york".equals(id)) return Ui.tr("New York");
            if ("aerial:sonoma-coast".equals(id)) return Ui.tr("Sonoma Coast");
            if ("studio:black".equals(id)) return Ui.tr("Studio Black");
            if ("studio:space-gray".equals(id)) return Ui.tr("Space Gray");
            if ("studio:midnight-blue".equals(id)) return Ui.tr("Midnight Blue");
            if ("studio:deep-forest".equals(id)) return Ui.tr("Deep Forest Green");
            if ("studio:teal".equals(id)) return Ui.tr("Teal");
            if ("studio:warm-stone".equals(id)) return Ui.tr("Warm Stone");
            if ("studio:silver-mist".equals(id)) return Ui.tr("Silver Mist");
            if ("live:aerial".equals(id)) return Ui.tr("Live Aerial Screensaver");
            Wallpaper.Aerial aerial = Wallpaper.find(id);
            if (aerial != null) return aerial.title;
            if (!id.startsWith("studio:")) return Ui.tr("Automatic");
            return id.substring("studio:".length()).replace('-', ' ');
        }

        private static String wallpaperStatus(String status) {
            if ("Downloading…".equals(status)) return Ui.tr("Downloading…");
            if ("Aerial preview ready".equals(status)) return Ui.tr("Aerial preview ready");
            if ("Live Aerial preview ready".equals(status)) return Ui.tr("Live Aerial preview ready");
            if ("Unavailable — showing Frosted Horizon".equals(status)) return Ui.tr("Unavailable — showing Frosted Horizon");
            return status;
        }

        /** The wallpaper's preview where the emblem was, 568 × 320, with how it's loading under it. */
        private void drawWallpaperPreview(Canvas c) {
            WallpaperPicker.DrawData data = wallpaperPicker.draw();
            tmp.set(LEFT_X * k, EMBLEM_TOP * k, LEFT_R * k, (EMBLEM_TOP + 320) * k);
            previewPaint.setColor(data.color | 0xFF000000);
            c.save();
            Corners.continuous(boxPath, tmp.width(), tmp.height(), 22 * k);
            c.translate(tmp.left, tmp.top);
            c.clipPath(boxPath);
            c.drawPaint(previewPaint);
            if (data.image instanceof Bitmap) {
                tmp.offsetTo(0, 0);
                c.drawBitmap((Bitmap) data.image, null, tmp, previewPaint);
            }
            c.restore();
            c.save();
            c.translate(LEFT_X * k, EMBLEM_TOP * k);
            c.drawPath(boxPath, groupRim);
            c.restore();
            c.drawText(wallpaperStatus(data.status), LEFT_CX * k, (EMBLEM_TOP + 320 + 40) * k, version);
        }

        /** Two columns of rows: the kinds on the left, that kind's wallpapers on the right (a check on the one in use). */
        private void drawWallpaperPicker(Canvas c) {
            WallpaperPicker.DrawData data = wallpaperPicker.draw();
            Context ctx = getContext();
            c.drawText(Ui.tr("Wallpaper"), LIST_X * k, TITLE_BASE * k, paneTitle);
            float top = CONTENT_TOP * k, row = ROW_H * k, split = 1268 * k, gap = 16 * k;
            for (int column = 0; column < 2; column++) {
                boolean options = column == 1;
                String[] labels = options ? wallpaperOptionLabels : wallpaperCategoryLabels;
                int n = Math.min(options ? 7 : 8, labels.length), at = options ? data.option : data.category;
                boolean active = data.optionsPane == options;
                float left = options ? split + gap : LIST_X * k, right = options ? LIST_R * k : split;
                if (n == 0) continue;
                drawBox(c, left, right, top, top + n * row);
                for (int i = 1; i < n; i++) drawSeparator(c, left, right, top + i * row);
                for (int i = 0; i < n; i++) {
                    float y = top + i * row, cy = y + row / 2;
                    boolean focused = active && i == at;
                    if (focused) {
                        drawPlatter(c, left, right, y, 1);
                    } else if (!options && i == at) {   // the kind whose wallpapers show on the right
                        white.setColor(currentPill);
                        tmp.set(left, y, right, y + row);
                        c.drawRoundRect(tmp, RADIUS * k, RADIUS * k, white);
                    }
                    rowLabel.setColor(focused ? INK : primary);
                    c.drawText(labels[i], left + INSET * k, cy + Ui.halfCap(rowLabel), rowLabel);
                    if (options && i < data.options.length && data.options[i].equals(data.selected)) {
                        float cw = Symbols.width(ctx, "check", 26 * k);
                        Symbols.draw(ctx, c, "check", right - INSET * k - cw / 2, cy, 26 * k, focused ? INK : primary);
                    }
                }
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
                        requestFree();
                    }
                    openSection(S_SYSTEM, R_HOME);
                }
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                openSection(S_LOCATION, R_CITY);
            } else if (screen == WALLPAPER_PICKER) {
                WallpaperPicker.Action action = wallpaperPicker.ok();
                if (action == WallpaperPicker.Action.OPEN_OPTIONS) {
                    cacheWallpaperLabels();
                    focus = wallpaperPicker.draw().option;
                }
            } else if (screen == PICKER && pickFor == PICK_ENUM) {
                pickEnum();
            } else if (screen == PICKER && pickFor <= PICK_SLEEP) {
                pickPower();
            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[pickScroll + focus]);
                SleepOverlay.update(c);   // the pill speaks it too
                openSection(S_SYSTEM, R_LANGUAGE);
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                setKeyRowApp(pickFor - 1, appPkgs.get(at));
                focusKeyRow(pickFor - 1);
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == CLEAN) {
                activateClean();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == TMDB_KEY) {
                activateKey();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == PICK_FROM) {
                toggleService();
                invalidate();
                spoken.focusMoved();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(pickScroll + focus + 1);
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
