package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, matched to Apple's own tvOS 26 photo (DESIGN-TVOS.md v3):
 *  - the background: the focused Dock app's Top Shelf (its pick from Recs, edge to edge, its title logo at the top
 *    left), or, when that app has none, a wallpaper (your photo, else a bundled aerial); ↑ on a pick goes full screen
 *    (the Dock slides away; details, synopsis and ▶ Play at the bottom left);
 *  - at the top right, a smoke-glass pill with the time and the current profile's avatar: OK opens Control Center;
 *  - the Dock: one Liquid Glass bar with 6 apps (5:3, each composed like a tvOS icon by HomeApps; the focused one
 *    grows 1.23× and sways, with one soft highlight), a chevron above it when a pick shows, and the app grid's first row peeking below;
 *  - ↓ on the Dock: the page glides up, the background frosts and dims, and the grid (6 columns) comes up;
 *  - Control Center: a floating glass panel on the right with the profiles, the inputs and shortcuts, and the weather;
 *  - from sunset to sunrise, a night look: smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps, 133 × 75 with ten
    // (HomeApps.shelfVisible; past that the shelf scrolls).
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the centre, as measured on Apple's photo (251 × 150 → 308 × 185,
    // DESIGN-TVOS.md §0), with no lift.
    private static final float FOCUS_GROW = 0.23f, TV_FOCUS_HALF = FOCUS_GROW / 2, TV_FOCUS_LIFT = 0;
    // The Dock as on Apple's photo (§1): 6 tiles of 251 × 150 (5:3; TV_TILE_H sets the shape), 49 apart, x 85–1835,
    // top 822; the glass 48 past them on the sides and 40 above and below (x 37–1883, y 782–1010), radius 64.
    private static final float TV_MARGIN = 85, TV_TILE_W = 251, TV_TILE_H = 150, TV_TILE_GAP = 49, TV_TILE_RADIUS = 24;
    private static final float TV_DOCK_PAD_X = 48, TV_DOCK_PAD = 40, TV_DOCK_TOP = 782, TV_DOCK_SHOWN = 6, TV_DOCK_RADIUS = 64;
    /** The thin ⌃ above the Dock (only when the focused app's pick shows): its centre's y. */
    private static final float TV_CHEVRON_Y = 718;
    // The Top Shelf: the title's logo fitted in 380 × 200 at the top left; full screen (↑): the details at the bottom
    // left, their bottom at y 1000, and the Dock sliding 300 down.
    private static final float TV_LOGO_X = 64, TV_LOGO_Y = 44, TV_LOGO_W = 380, TV_LOGO_H = 200, TV_FULL_BOTTOM = 1000;
    private static final float TV_TITLE_W = 700, TV_TEXT_W = 860, TV_BUTTON_H = 60, TV_FULL_DROP = 300;
    // The status pill (§1): x up to 1875, y 48–105, the time 24 from its left, the avatar 49 round inset 4 at its right.
    private static final float PILL_RIGHT = 1875, PILL_TOP = 48, PILL_H = 57, PILL_AVATAR = 49;
    /**
     * How far the page glides up when the app grid opens: the Dock to y = 60, the grid's first row (peeking at y 1052
     * at rest) to 330.
     */
    private static final float TV_GRID_LIFT = 722;
    // Control Center (§4): a floating glass panel on the right, its bento cards in 2 columns, the weather at the bottom.
    private static final float CC_LEFT = 1340, CC_RIGHT = 1860, CC_TOP = 60, CC_BOTTOM = 1020, CC_RADIUS = 44, CC_PAD = 28;
    private static final float CC_CARD_W = 224, CC_CARD_H = 120, CC_GAP = 16, CC_CARD_RADIUS = 26, CC_WEATHER_H = 210;
    private static final float CC_SLIDE = 80;
    private static final long ROTATE_MS = 8000;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // "All Apps" sheet.
    // The app grid, on the Dock's columns (GRID_TOP: its first row with the page risen by TV_GRID_LIFT).
    private static final int COLUMNS = 6, VISIBLE_ROWS = 3;
    private static final float GRID_W = TV_TILE_W, GRID_H = TV_TILE_H, GRID_GAP = TV_TILE_GAP, GRID_ROW = 250, GRID_TOP = 330,
            GRID_RADIUS = TV_TILE_RADIUS;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 44, KEY_BLUR = 16;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, TABBAR = 8;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 60, L_BUTTON_GAP = 16, L_BUTTON_CY = 90;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5;
    // The tab bar (WELLY-HOME-REMOTE-SPEC.md §2): TV · Home · Welly, a glass capsule at the top centre, on the pill's
    // line. The focus row is the three tabs (topIndex 0 to 2), the round buttons (3…) and the pill (Z_STATUS).
    private static final int TAB_TV = 0, TAB_HOME = 1, TAB_WELLY = 2;
    private static final float TAB_TOP = 48, TAB_H = 57, TAB_PAD = 30, TAB_INSET = 4, TAB_MIN = 112;
    /** The Home and Welly tabs' content has the focus (the house's lights; Welly's conversation). */
    private static final int Z_HOUSE = 10, Z_WELLY = 11;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final NowPlaying.Watch playing;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return (id - 1) / 1000 == GRID && canToggleShelf((id - 1) % 1000); }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, tabBar;
    private final HouseView house;
    private final WellyView welly;
    private int zone = Z_SHELF;
    // The tabs: tabAway = the TV tab's content hidden and the background frosted and dimmed (Home or Welly); the two
    // contents cross-fade (smooth); lens = where the selected tab's lighter lens is (0 to 2, snappy).
    private final Spring tabAway = new Spring(0.45f, 0.9f, 0), homeShown = new Spring(0.45f, 0.9f, 0);
    private final Spring wellyShown = new Spring(0.45f, 0.9f, 0), lens = new Spring(0.30f, 0.80f, 0);
    private final RectF tabBarRect = new RectF();
    private Paint tabText;
    private final float[] tabTextW = new float[3];
    private int tabLayoutGeneration = -1;
    private boolean houseFocused, wellyFocused;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The three tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[3 + HomeApps.MAX_TOP];
    private final RectF[] topRects = new RectF[3 + HomeApps.MAX_TOP];
    private final Spring[] cardFocus = new Spring[3];
    private final RectF[] cardRects = new RectF[3];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    // tvOS: the Top Shelf's text, the status pill and the app names under the focused tile.
    private Paint tvLabel, tvMeta, tvBadge, tvButton, tvPill, tvName, ccName;
    private TextPaint tvTitle, tvOverview;
    private final RectF pillRect = new RectF(), badgeRect = new RectF();
    private String pillTemp = "", pillSymbol;
    private float pillClockW, pillTempW, pillSymbolW;
    // The tile (or grid cell) that last took the focus: it sways once toward where the focus came from.
    private int swayTile = -1, swayCell = -1;
    /** Full screen's details enter one after another (metadata, synopsis, Play), from this time. */
    private long fullAt;
    /** The last ← → (−1, 0, 1): the focused tile's logo leans that way (a touch of tvOS's parallax). */
    private float lean;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    /** Each app's logo already sized for the tile at rest and focused, with its soft drop shadow (the emblem layer). */
    private static final class Emblem {
        Bitmap src, rest, focus;
    }
    private final Map<String, Emblem> emblems = new HashMap<>(), darkEmblems = new HashMap<>();
    private final Map<String, LinearGradient> brandShaders = new HashMap<>();   // the backdrops, for TILE_STYLE's brands
    // The tile's light layers in its own coordinates (centred), made once per tile size: the radial lift, the bottom
    // vignette, the gloss band, the rim's gradient.
    /** The focused tile's one specular highlight (DESIGN-TVOS.md §0b #3). */
    private Shader tileSheen;
    /** Apple's continuous-corner shapes (Corners), made once per size: the tiles, the bento cards, the weather card. */
    private final Shape tileShape = new Shape(), cardShape = new Shape(), weatherShape = new Shape();
    private float tileFxW;
    private final android.graphics.Matrix imageMatrix = new android.graphics.Matrix();
    // The focused tile sways toward where the focus came from, then settles (tvOS's tilt): angles from these springs.
    private final Spring swayX = new Spring(0.5f, 0.45f, 0), swayY = new Spring(0.5f, 0.45f, 0);
    private final android.graphics.Camera tiltCamera = new android.graphics.Camera();
    private final android.graphics.Matrix tiltMatrix = new android.graphics.Matrix();
    private int lastDx, lastDy;
    private float nameCap;
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        // DESIGN-TVOS.md §3: each brand's backdrop, top to bottom, and how its logo sits on it.
        TILE_STYLE.put("netflix", new int[] {0xFF141414, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF202020, 0xFF0B0B0B, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF1FA2FF, 0xFF0A62C9, 1});
        TILE_STYLE.put("disney", new int[] {0xFF1A3E9A, 0xFF0A1854, 1});
        TILE_STYLE.put("max", new int[] {0xFF5B2EEA, 0xFF1F1477, 1});
        TILE_STYLE.put("appletv", new int[] {0xFF2C2C2E, 0xFF000000, 1});
        TILE_STYLE.put("hulu", new int[] {0xFF1CE783, 0xFF0E9F57, 2});
        TILE_STYLE.put("paramount", new int[] {0xFF0064FF, 0xFF00287F, 1});
        TILE_STYLE.put("peacock", new int[] {0xFF1C1C1E, 0xFF000000, 0});
        TILE_STYLE.put("applemusic", new int[] {0xFFFA586A, 0xFFFA233B, 1});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    /** The first app the shelf shows: with more apps than fit, it scrolls with the focus. */
    private int shelfFirst;
    private final int shelfVisible;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background: all of this hour's picks, and those of the focused Dock app (the Top Shelf shows these)
    private List<Recs.Pick> picks = new ArrayList<>(), appPicks = new ArrayList<>();
    /** Full screen (↑ on a pick): 0 = the Dock, 1 = the details at the bottom and the Dock away. */
    private final Spring full = new Spring(0.45f, 0.9f, 0);   // smooth (apple-design-motion: push-like transitions)
    // The wallpaper when the focused app has no pick: a bundled aerial (one per hour), built like a pick's backdrop.
    private Backdrop wallpaper;
    private int wallpaperIndex = -1;
    private boolean wallpaperLoading;
    private String[] aerials;
    // The status pill: its focus, and the current profile's avatar at its size.
    private final Spring pillFocus = new Spring(0.26f, 0.8f, 0);
    private Bitmap pillAvatar;
    private int pillFrom = Z_SHELF;          // where ↓ from the pill goes back to
    private float pillTimeW;
    private android.graphics.Path chevron;
    private Shader fullScrim;   // full screen: the soft dark gradient under the details (made once per size)
    // Control Center's profiles row (its top row): avatars, names, focus.
    private List<Profiles.Profile> profiles = new ArrayList<>();
    private Bitmap[] profileAvatars = new Bitmap[0];
    private String[] profileNames = new String[0];
    private RectF[] profileRects = new RectF[0];
    private Spring[] profileFocus = new Spring[0];
    private boolean ccOnProfiles;
    private int profileIndex;
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many of Control Center's cards are inputs (Live TV and HDMI)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF(), panelRect = new RectF();
    private String[] drawerLabels = new String[0];
    private int drawerFrom = Z_SHELF;            // where the focus goes back when Control Center closes
    private Paint ccTime, ccDate, ccLabel, ccTemp;
    private final Spring drawer = new Spring(0.45f, 0.85f, 0);   // Control Center: presents (0.45, 0.85), dismisses (0.40, 0.88)
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.45f, 0.9f, 0);   // the grid's lift: smooth
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF(), gridFocusCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    /** "Off in 42 min" next to the round buttons while the sleep timer runs (Settings > Sleep Timer); "" otherwise. */
    private String sleepText = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp, dayLow;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), tmp2 = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();

    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private int textPrefs = -1;
    private boolean clock24;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] dayText = new String[5], dayMaxText = new String[5], dayMinText = new String[5];
    private boolean textDays;   // the weather card shows the next days (Home Settings → Forecast) and has them

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "", rating = "", app = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout, overviewLayout;
        float metaW, ratingW, appW;
        Recs.Pick pick;               // for its title logo (Recs.logo), which may arrive after the text
        String[] badges = new String[0];
        float[] badgeW = new float[0];
        int ratingBadge = -1;         // which badge is the ★ rating (drawn with a star)

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS || kind == TABBAR ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawTopShelf(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case TABBAR: drawTabBar(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    /** The Top Shelf pages by itself while the focus is elsewhere (and not on an app whose pick it shows). */
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (zone == Z_SHELF && !sheetOpen && !drawerOpen && appPicks.size() > 1) browse(1);
            if (picksDue()) updatePicks();   // a new hour: another aerial
            handler.postDelayed(this, ROTATE_MS);
        }
    };
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            playing.start();   // the listener allowed after the screen showed (a no-op once watching)
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            updateSleep();
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        playing = new NowPlaying.Watch(a, () -> {
            fitPlaying();
            heroDirty = true;
            wake();
            bounceBars();
        });
        click = new Click(a);
        shelfVisible = Math.min((int) TV_DOCK_SHOWN, HomeApps.shelfVisible(a));
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        tabBar = new Layer(a, TABBAR);
        house = new HouseView(a);
        welly = new WellyView(a, scene);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer, tabBar, house, welly}) {
            v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        }
        // The Home and Welly tabs sit on the background, under the TV tab's layers and the tab bar.
        for (View v : new View[] {sky, house, welly, heroLayer, status, tabBar, platter, tiles, drawerLayer, sheetLayer, toastLayer}) {
            // TalkBack reads the items (Spoken); Welly speaks for itself.
            if (v != welly) v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(v);
        }
        house.setVisibility(INVISIBLE);
        welly.setVisibility(INVISIBLE);
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = i < 3 ? new Spring(0.30f, 0.80f, 0) : new Spring(0.26f, 0.85f, 0);   // tabs: snappy
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        setLightButtons(HomeApps.topButtons());
        recs.setLight(false);   // the Top Shelf shows the whole photo
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        // Animations off in the TV's settings (animator duration scale 0): every spring jumps, nothing sways.
        Spring.instant = android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        checkNight(false);
        boolean h24 = Prefs.clock24();
        if (h24 != clock24) {   // 12/24 hours changed in the TV's settings: the clock and the drawer's times again
            clock24 = h24;
            clockMinute = -1;
            textPrefs = -1;
        }
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        Photos.importInbox(getContext(), ownReady);   // photos a computer just sent
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        handler.removeCallbacks(rotate);
        handler.postDelayed(rotate, ROTATE_MS);
        playing.start();
        checkNotices();
        if (!again) reload();
        updatePalette();
        updatePicks();
        updateSleep();      // a timer just set in Settings shows at once, not at the next tick
        if (allAppsOnResume) {
            allAppsOnResume = false;
            reset(false);
            openSheet();
        } else if (sheetOpen) {
            closeSheet();
        }
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
        welly.resume();
        if (tab == TAB_HOME) house.refresh();
    }

    void pause() {
        if (!running) return;
        running = false;
        welly.pause();
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(rotate);
        handler.removeCallbacks(reloadSoon);
        handler.removeCallbacks(bars);
        playing.stop();
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
            full.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
            full.snap(0);
        }
        updatePicks();
        selectTab(TAB_TV);
        if (!animated) {
            for (Spring sp : new Spring[] {tabAway, homeShown, wellyShown, lens}) sp.snap(sp.target);
        }
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        lightButtons.addAll(list);
        if (zone == Z_TOP && topIndex >= 3 + lightButtons.size()) {
            zone = Z_STATUS;   // the focused button went away: the pill, its neighbour
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("TV"), Ui.tr("Home"), "Welly"};   // Welly is a name
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        layoutPill();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, tabBar}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, height);
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(440 * kk));
        measure(tiles, width, Math.round(440 * kk));   // y 640–1080: the chevron, the Dock, the grown tile's shadow
        measure(drawerLayer, Math.round(640 * kk), height);
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        measure(tabBar, width, Math.round(160 * kk));
        measure(house, width, height);
        measure(welly, width, height);
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(640 * kk));
        place(tiles, 0, Math.round(640 * kk));
        place(drawerLayer, width - drawerLayer.getMeasuredWidth(), 0);
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        place(tabBar, 0, 0);
        house.layout(0, 0, width, height);
        welly.layout(0, 0, width, height);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        gridColumnRects = null;
        clockMinute = -1;
        updateClock();
        Symbols.warm(getContext(), "bell", 26 * k);
        Symbols.warm(getContext(), "bedtime", 21 * k);   // the sleep timer's "Off in", at lQuote's size
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        dayLow = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        dayLow.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        tvLabel = Ui.text(Ui.semibold, 22 * k, 0xB3FFFFFF);
        tvLabel.setLetterSpacing(0.12f);
        tvTitle = new TextPaint(Ui.text(Ui.displaySemibold, 76 * k, 0xFFFFFFFF));
        tvTitle.setLetterSpacing(-0.015f);
        tvMeta = Ui.text(Ui.medium, 29 * k, 0xCCFFFFFF);
        tvBadge = Ui.numbers(Ui.semibold, 21 * k, 0xD9FFFFFF);
        tvOverview = new TextPaint(Ui.text(Ui.regular, 26 * k, 0xB8FFFFFF));
        tvButton = Ui.text(Ui.semibold, 28 * k, INK);
        for (Paint p : new Paint[] {tvLabel, tvTitle, tvMeta, tvOverview}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        tvPill = Ui.numbers(Ui.semibold, 28 * k, 0xFFFFFFFF);
        tabText = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
        tabText.setTextAlign(Paint.Align.CENTER);
        tabLayoutGeneration = -1;
        ccName = Ui.text(Ui.semibold, 21 * k, L_INK);
        ccName.setTextAlign(Paint.Align.CENTER);
        fullScrim = null;
        tvName = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
        ccTime = Ui.numbers(Ui.light, 57 * k, L_INK);
        ccTime.setLetterSpacing(-0.01f);
        ccDate = Ui.text(Ui.medium, 25 * k, L_DIM);
        ccLabel = Ui.text(Ui.semibold, 21 * k, L_INK);
        ccTemp = Ui.numbers(Ui.light, 64 * k, L_INK);
        tvName.setTextAlign(Paint.Align.CENTER);
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(KEY_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        nameCap = Ui.cap(tvName);
        emblems.clear();
        darkEmblems.clear();
        brandShaders.clear();
        tileFxW = 0;
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
        fitPlaying();
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean swaying = swayX.step(dt) | swayY.step(dt);
        boolean tilesMoving = step(tileFocus, dt) | (swaying && !sheetOpen);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt) | pillFocus.step(dt);
        boolean fullMoving = full.step(dt);
        boolean heroMoving = heroFocus.step(dt) | fullMoving;
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt);
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt) | step(profileFocus, dt);
        boolean scrollMoving = scroll.step(dt);
        boolean gridMoving = step(gridFocus, dt) | scrollMoving | (swaying && sheetOpen);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);
        boolean tabMoving = tabAway.step(dt) | homeShown.step(dt) | wellyShown.step(dt);
        boolean lensMoving = lens.step(dt);

        if (drawerMoving || sheetMoving || scrollMoving || fullMoving || tabMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (topMoving || lensMoving || statusDirty || bgFading) tabBar.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || sheetMoving || bgFading || tabMoving) sky.invalidate();
        boolean moving = drawerMoving || sheetMoving || scrollMoving;
        if (bgFading || (drawerWasMoving && !moving)) platter.invalidate();
        drawerWasMoving = moving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            layoutPill();
            status.invalidate();
            tabBar.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving || scrollMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || tabMoving || lensMoving
                || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Control Center, the app grid and full screen: the layers' positions and opacity (without redrawing them). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value), fs = Ui.clamp(full.value);
        // The app grid (tvOS): the whole page glides up, the Top Shelf's logo and the pill fade, and the grid comes up
        // under the Dock (its first row peeks below it at rest); scrolling the grid takes the Dock along.
        float rise = (sheet.value * TV_GRID_LIFT + scroll.value * GRID_ROW) * k;
        float top = 1 - smoothstep(0, 0.45f, s);
        // Full screen: the Dock and the grid's peek slide down and fade.
        float drop = full.value * TV_FULL_DROP * k, away = 1 - smoothstep(0, 0.7f, fs);
        // Control Center: nothing moves; the page dims a little behind the panel (the sky darkens too, drawLightSky).
        float dim = 1 - 0.25f * dc;
        // The tabs: the TV tab's content fades out over Home and Welly, which cross-fade.
        float tv = 1 - Ui.clamp(tabAway.value);
        show(heroLayer, top * dim * tv, -rise);
        show(status, top * (1 - smoothstep(0, 0.5f, dc)), -sheet.value * TV_GRID_LIFT * k);
        // The tab bar leaves with the title logo when the grid or full screen owns the screen.
        show(tabBar, top * (1 - smoothstep(0, 0.5f, dc)) * (1 - smoothstep(0, 0.6f, fs)), -sheet.value * TV_GRID_LIFT * k);
        show(platter, dim * away * tv, -rise + drop);
        show(tiles, dim * away * tv, -rise + drop);
        show(house, dim * Ui.clamp(homeShown.value), 0);
        show(welly, dim * Ui.clamp(wellyShown.value), 0);
        float da = (1 - s) * smoothstep(0, 0.6f, dc);
        drawerLayer.setTranslationX((1 - d) * CC_SLIDE * k);
        drawerLayer.setTranslationY(0);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        float ga = dim * away * tv;
        sheetLayer.setVisibility(!others.isEmpty() && ga > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(ga);
        sheetLayer.setTranslationY((1 - sheet.value) * TV_GRID_LIFT * k + drop);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private void updateSleep() {
        boolean here = Prefs.sleepShown() && !android.provider.Settings.canDrawOverlays(getContext());   // else SleepOverlay
        int left = here ? SleepTimer.minutesLeft(getContext()) : 0;
        String text = left == 0 ? "" : Ui.trf("Off in {time}", "time", SleepTimer.duration(left));
        if (text.equals(sleepText)) return;
        sleepText = text;
        statusDirty = true;
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    /** The pick the Top Shelf shows: one of the focused Dock app's, or null (then a wallpaper shows). */
    private Recs.Pick currentPick() {
        return appPicks.isEmpty() ? null : appPicks.get(Math.min(pickIndex, appPicks.size() - 1));
    }

    private long picksHour = -1;

    /** A new hour: the aerial changes (and Recs' picks with it). */
    private boolean picksDue() {
        return System.currentTimeMillis() / 3600_000L != picksHour;
    }

    /** This hour's picks, the focused app's among them, and the background (when Recs notifies, every 15 s, …). */
    private void updatePicks() {
        if (labelPaint == null) return;
        picksHour = System.currentTimeMillis() / 3600_000L;
        List<Recs.Pick> list = recs.shelf();
        if (!samePicks(list, picks)) picks = list;
        for (Recs.Pick p : list) recs.backdrop(p);   // every pick's photo ready: moving along the Dock is instant
        followShelf(false);
        updateBackground();
        refreshHero(false);
        spoken.focusMoved();
    }

    /**
     * The focused Dock app's picks (tvOS shows the focused app's Top Shelf): when they change, the Top Shelf shows its
     * first one, or the wallpaper when it has none.
     */
    private void followShelf(boolean animate) {
        String pkg = shelf.isEmpty() || shelfIndex >= shelf.size() ? null : shelf.get(shelfIndex).pkg;
        List<Recs.Pick> mine = new ArrayList<>();
        for (Recs.Pick p : picks) if (pkg != null && pkg.equals(p.pkg)) mine.add(p);
        if (samePicks(mine, appPicks)) return;
        appPicks = mine;
        pickIndex = 0;
        heroSlide = 1;
        if (zone == Z_HERO && mine.isEmpty()) zone = Z_SHELF;
        refreshHero(animate);
        updateBackground();
        handler.removeCallbacks(rotate);
        handler.postDelayed(rotate, ROTATE_MS);
    }

    /**
     * The background (DESIGN-TVOS.md §5): the shown pick's photo; without one, your photo (Photos), else this hour's
     * aerial. One still loading keeps the current background until it's ready (Recs and the loader call back).
     */
    private void updateBackground() {
        Recs.Pick p = currentPick();
        Backdrop b;
        if (p != null) {
            b = recs.backdrop(p);
        } else if (Photos.on(getContext())) {
            b = Photos.backdrop(getContext(), ownReady);
        } else {
            b = aerial();
        }
        if (b != null) showBackdrop(b);
    }

    /** This hour's aerial (assets/aerials, one per hour in turn), or null while it's built in the background. */
    private Backdrop aerial() {
        if (aerials == null) {
            try {
                String[] list = getContext().getAssets().list("aerials");
                aerials = list == null ? new String[0] : list;
                java.util.Arrays.sort(aerials);
            } catch (java.io.IOException e) {
                aerials = new String[0];
            }
        }
        if (aerials.length == 0) return null;
        final int index = (int) (System.currentTimeMillis() / 3600_000L % aerials.length);
        if (index == wallpaperIndex) return wallpaper;
        if (!wallpaperLoading) {
            wallpaperLoading = true;
            final String name = aerials[index];
            final Context c = getContext().getApplicationContext();
            LOADER.execute(() -> {
                Backdrop built = null;
                try (java.io.InputStream in = c.getAssets().open("aerials/" + name)) {
                    Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
                    if (b != null) built = Backdrop.make(b, false);
                } catch (Exception | OutOfMemoryError e) {
                    AppLog.w("aerial " + name + ": " + e.getMessage());
                }
                final Backdrop ready = built;
                handler.post(() -> {
                    wallpaperLoading = false;
                    if (ready == null) return;
                    wallpaper = ready;
                    wallpaperIndex = index;
                    if (running) updateBackground();
                });
            });
        }
        return wallpaper;   // the previous hour's until the new one is ready
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.pick == null && hero == null) return;
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (next.pick == null || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        hero = next.pick == null ? null : next;
        heroDirty = tilesDirty = true;   // the tiles layer holds the chevron
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.pick = p;
        h.title = p.title == null ? "" : p.title;
        // "Sci-Fi · 2026 · 2h 4m · Netflix": the year after the genre (after "Series · Drama" for a show).
        String meta = p.meta == null ? "" : p.meta;
        if (p.year != null && !p.year.isEmpty()) {
            List<String> parts = new ArrayList<>(java.util.Arrays.asList(meta.isEmpty() ? new String[0] : meta.split(" · ")));
            int at = !parts.isEmpty() && parts.get(0).equals(Ui.tr("Series")) ? 2 : 1;
            parts.add(Math.min(at, parts.size()), p.year);
            meta = TextUtils.join(" · ", parts);
        }
        h.meta = meta;
        h.app = Recs.serviceName(p);
        h.button = p.pkg != null && !h.app.isEmpty();
        h.dots = appPicks.size();
        h.dot = pickIndex;
        h.metaW = tvMeta.measureText(h.meta);
        h.appW = tvButton.measureText(Ui.tr("Play"));
        // Badges: the age rating, the ★ score, then any extras (the preview's 4K, Dolby Vision…).
        List<String> badges = new ArrayList<>();
        if (p.cert != null && !p.cert.isEmpty()) badges.add(p.cert);
        if (p.rating > 0) {
            h.ratingBadge = badges.size();
            badges.add(String.format(Ui.locale(), "%.1f", p.rating));
        }
        if (p.badges != null) for (String extra : p.badges.split(",")) if (!extra.trim().isEmpty()) badges.add(extra.trim());
        h.badges = badges.toArray(new String[0]);
        h.badgeW = new float[h.badges.length];
        for (int i = 0; i < h.badges.length; i++) {
            h.badgeW[i] = 2 * 10 * k + tvBadge.measureText(h.badges[i]) + (i == h.ratingBadge ? 16 * k + 5 * k : 0);
        }
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), tvTitle, Math.max(1, Math.round(TV_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.95f)
                .setIncludePad(false)
                .build();
        String overview = p.overview == null ? "" : p.overview;
        h.overviewLayout = StaticLayout.Builder.obtain(overview, 0, overview.length(), tvOverview, Math.max(1, Math.round(TV_TEXT_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 1.08f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ in full screen (or the Top Shelf paging by itself): the focused app's next (or previous) pick. */
    private boolean browse(int dir) {
        if (appPicks.size() < 2) return false;
        showPick((pickIndex + dir + appPicks.size()) % appPicks.size(), dir);
        return true;
    }

    /** Brings pick `index` up on the Top Shelf (its text slides in from `dir`), and its photo behind. */
    private void showPick(int index, int dir) {
        pickIndex = index;
        heroSlide = dir;
        refreshHero(true);
        updateBackground();
        handler.removeCallbacks(rotate);
        handler.postDelayed(rotate, ROTATE_MS);
        spoken.focusMoved();
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.lightGlass == null) return b.glass;   // the Top Shelf's photo: the glass shows it as it is
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        if (!scene.shaded()) {   // Android 8 to 12: a gray image would be one more full-area pass for the flat look
            scene.clearFrost();
            scene.clearGlass();
            nightPair = false;
            return;
        }
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && glassOf(bgTo) != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.30f, 0.80f, 0);   // snappy: a focus is a quick state change
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
        followShelf(false);   // the focused app may be another one now
    }

    private void layoutShelf() {
        int n = Math.min(shelf.size(), shelfVisible);
        float padX = TV_DOCK_PAD_X * k, pad = TV_DOCK_PAD * k, gap = TV_TILE_GAP * k, tw = TV_TILE_W * k, th = TV_TILE_H * k;
        // The Dock: up to 6 tiles in tvOS's 5:3, centred (x 85–1835 with 6); with more apps, it scrolls.
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = TV_DOCK_TOP * k;
        platterRect.set(left - padX, top, left + width + padX, top + th + 2 * pad);
        snap(platterRect);
        shelfFirst = Math.max(0, Math.min(shelfFirst, shelf.size() - n));
        float x = left - shelfFirst * (tw + gap), y = top + pad;
        for (int i = 0; i < shelf.size(); i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            snap(tileRects[i]);
            x += tw + gap;
        }
        emblems.clear();
        darkEmblems.clear();
    }

    /** Scrolls the shelf, one app at a time, so the focused app is shown: the tiles slide there (unless `snap`). */
    private void revealShelf(boolean snap) {
        int n = Math.min(shelf.size(), shelfVisible);
        int first = Math.max(0, Math.min(Math.max(shelfFirst, shelfIndex - n + 1), Math.min(shelfIndex, shelf.size() - n)));
        if (first == shelfFirst || tileRects.length == 0) return;
        float dx = (shelfFirst - first) * (tileRects[0].width() + TV_TILE_GAP * k);
        for (int i = 0; i < tileRects.length; i++) {
            tileRects[i].offset(dx, 0);
            if (!snap && i < tileSlide.length) tileSlide[i].value -= dx;
        }
        shelfFirst = first;
        tilesDirty = true;
        if (snap) return;
        reorderAnim = true;   // the same slides as moving an app
        wake();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = TV_DOCK_RADIUS * k;   // concentric with the tiles (24 + 40)
        tmp.set(platterRect);
        tmp.offset(0, 16 * k);
        shadow.setColor(tone(0x24000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // Liquid Glass (DESIGN-TVOS.md §2): thin, so the photo's colors bleed through; white by day, smoked at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.24f, 0.18f), tone(0.03f, 0.05f),
                34 * k, 26 * k, tone(0.75f, 0.9f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;
    // What's playing, cut to fit the tab row and the clock's row: once per title, never while drawing.
    private String playingTab, playingClock;
    private float playingTabWidth, playingTabCap, playingClockCap;
    private final RectF barRect = new RectF();
    // The bars bounce for a few seconds when the screen shows or the title changes, then stand still: bouncing all
    // along redrew the top layer 10 times a second (~8% of a core on a Skyworth's Cortex-A55), the still screen nothing.
    private static final long BAR_FRAME_MS = 100, BARS_BOUNCE_MS = 3000;
    private long barsUntil;
    private final Runnable bars = new Runnable() {
        @Override public void run() {
            if (!running || playingTab == null) return;
            heroDirty = true;
            wake();
            if (SystemClock.uptimeMillis() < barsUntil) handler.postDelayed(this, BAR_FRAME_MS);
        }
    };

    private void bounceBars() {
        handler.removeCallbacks(bars);
        if (playingTab == null) return;
        barsUntil = SystemClock.uptimeMillis() + BARS_BOUNCE_MS;
        handler.post(bars);
    }

    private void fitPlaying() {
        String now = playing.text;
        if (now == null || lQuote == null) {
            playingTab = playingClock = null;
            return;
        }
        fitPaint.set(lQuote);
        playingTab = TextUtils.ellipsize(now, fitPaint, 640 * k, TextUtils.TruncateAt.END).toString();
        playingTabWidth = lQuote.measureText(playingTab);
        playingTabCap = Ui.cap(lQuote);
        playingClockCap = Ui.cap(lClockQuote);
        fitPaint.set(lClockQuote);
        playingClock = TextUtils.ellipsize(now, fitPaint, (CONTENT_R - MARGIN - 60) * k, TextUtils.TruncateAt.END).toString();
    }

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        // At the right margin, under the round buttons: the shelf's glass is narrower and centred with few apps.
        float right = CONTENT_R * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 3; i < 3 + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        // What an app is playing takes the quotes' place while it plays.
        String line = clock ? playingClock : playingTab;
        if (line != null) {
            float cap = clock ? playingClockCap : playingTabCap, gap = cap * 0.6f;
            float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + cap / 2;
            float x = clock ? MARGIN * k : right - playingTabWidth - gap - NowPlaying.barsWidth(cap);
            NowPlaying.drawBars(c, x, base, cap, text, SystemClock.uptimeMillis() < barsUntil, barRect);
            c.drawText(line, x + NowPlaying.barsWidth(cap) + gap, base, text);
            text.setColor(textColor);
            return;
        }
        // One read of the list, so n always matches the coins drawn below.
        int[] coins = HomeData.active();
        int n = coins.length;
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        int last = Math.min(shelf.size(), shelfFirst + shelfVisible);
        // Scrolling, tiles slide in and out through the glass's sides (only those that show any part are drawn). Only
        // then: on a shelf that fits, the clip would cut the end tiles' shadows in a straight line.
        boolean scrolls = shelf.size() > shelfVisible;
        if (scrolls) {
            c.save();
            c.clipRect(platterRect.left, 0, platterRect.right, h);
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float slid = i < tileSlide.length ? tileSlide[i].value : 0;
                if (tileRects[i].right + slid <= platterRect.left || tileRects[i].left + slid >= platterRect.right) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (scrolls) c.restore();
        // No names under the Dock's apps (as in Apple's photo). Above the Dock, a thin ⌃ while a pick shows: ↑ goes full
        // screen.
        if (hero != null) drawChevron(c, w / 2f, TV_CHEVRON_Y * k);
        // More apps than fit: a small arrow inside the glass's edge on each side that has more, where no tile reaches
        // even focused (inside the screen's safe area).
        float cy = platterRect.centerY(), size = 28 * k, inset = 14 * k;
        int ink = tone(0x99000000, 0xCCFFFFFF);
        if (last < shelf.size()) Symbols.draw(getContext(), c, "chevron_right", platterRect.right - inset, cy, size, ink);
        if (shelfFirst > 0) {
            c.save();
            c.scale(-1, 1, platterRect.left + inset, cy);
            Symbols.draw(getContext(), c, "chevron_right", platterRect.left + inset, cy, size, ink);
            c.restore();
        }
    }

    /** The thin ⌃ (30 × 12, white 75%), centred on (cx, cy): the path is made once, at unit size. */
    private void drawChevron(Canvas c, float cx, float cy) {
        if (chevron == null) {
            chevron = new android.graphics.Path();
            chevron.moveTo(-15, 6);
            chevron.lineTo(0, -6);
            chevron.lineTo(15, 6);
        }
        c.save();
        c.translate(cx, cy);
        c.scale(k, k);
        rim.setShader(null);
        rim.setColor(0xBFFFFFFF);
        rim.setStrokeWidth(3);
        rim.setStrokeCap(Paint.Cap.ROUND);
        rim.setStrokeJoin(Paint.Join.ROUND);
        rim.setShadowLayer(8, 0, 1, 0x66000000);
        c.drawPath(chevron, rim);
        rim.clearShadowLayer();
        rim.setStrokeCap(Paint.Cap.BUTT);
        rim.setStrokeJoin(Paint.Join.MITER);
        rim.setStrokeWidth(2.5f * k);
        c.restore();
    }

    private void drawShelfTile(Canvas c, int i) {
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        drawAppTile(c, shelf.get(i), tileRects[i], tileFocus[i].value, l);
    }

    /**
     * An app tile as tvOS shows one (DESIGN-TVOS.md §0b #2–#4), in Apple's continuous-corner shape and in the tile's
     * own coordinates: at rest only the back layer (the brand's backdrop, or the app's TV banner) and its emblem, flat;
     * the system adds the light. Focused (`f`): 1.23×, one soft lift shadow, a 3° sway toward where the focus came
     * from with the emblem leaning 4 px (parallax), and one specular highlight that moves with the sway. `l`: lifted
     * to be moved. Nothing is allocated here.
     */
    private void drawAppTile(Canvas c, HomeApps.Item it, RectF base, float f, float l) {
        float fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        float bw = base.width(), bh = base.height(), r0 = TV_TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY() - TV_FOCUS_LIFT * k * f;
        tileFx(bw, bh);
        android.graphics.Path shape = tileShape.of(bw, bh, r0);
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        float lift = Math.max(fc, Ui.clamp(l));
        if (lift > 0.01f) {
            // The one lift shadow, at the resting size and enlarged by the transform (the blur stays cached).
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tmp.offset(0, (10 + 18 * lift) * k);
            tmp.inset(6 * k, 4 * k);
            shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.30f, 0.45f) * lift));
            shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        }
        // The sway: a small 3D turn toward where the focus came from, settling back (only while focused).
        float ax = swayX.value * 3 * fc, ay = swayY.value * 3 * fc;
        if (Math.abs(ax) > 0.02f || Math.abs(ay) > 0.02f) {
            tiltCamera.save();
            tiltCamera.rotateY(-ax);
            tiltCamera.rotateX(ay);
            tiltCamera.getMatrix(tiltMatrix);
            tiltCamera.restore();
            c.concat(tiltMatrix);
        }
        c.translate(-bw / 2, -bh / 2);   // the shape's own origin (its top left)
        // Back layer: the brand's backdrop, or the one HomeApps composed (a gradient, or a picture), or dark.
        int[] style = TILE_STYLE.get(it.key);
        if (style == null) style = it.style;
        boolean emblem = it.logo != null;
        if (emblem && style != null) {
            // Keyed by the app alone (a brand's colours never change): no string built while drawing.
            LinearGradient g = brandShaders.get(it.key);
            if (g == null) {   // once per app and tile size (the map is cleared with the size)
                g = new LinearGradient(bw * 0.3f, 0, bw * 0.7f, bh, style[0], style[1], Shader.TileMode.CLAMP);
                brandShaders.put(it.key, g);
            }
            tileFill.setShader(g);
            tileFill.setAlpha(255);
            c.drawPath(shape, tileFill);
            tileFill.setShader(null);
        } else if (it.tile != null && it.tileShader != null) {
            // The banner, filled and centred (never stretched), through the shape.
            float iw = it.tile.getWidth(), ih = it.tile.getHeight(), sc = Math.max(bw / iw, bh / ih);
            imageMatrix.setTranslate(-(iw - bw / sc) / 2, -(ih - bh / sc) / 2);
            imageMatrix.postScale(sc, sc);
            it.tileShader.setLocalMatrix(imageMatrix);
            imageFill.setShader(it.tileShader);
            c.drawPath(shape, imageFill);
            imageFill.setShader(null);
            it.tileShader.setLocalMatrix(null);
        } else {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawPath(shape, tileFill);
        }
        c.translate(bw / 2, bh / 2);
        // Parallax: the emblem leans with the sway.
        float px = (0.4f * lean + 0.6f * swayX.value) * 4 * k * fc, py = swayY.value * 3 * k * fc;
        if (emblem) drawEmblem(c, it, style == null ? 0 : style[2], s, px, py);
        if (fc > 0.01f) {
            // The one specular highlight: soft, ≤ 14 %, sitting up and to the left and following the sway. The canvas
            // moves and the shape moves back: the shader keeps its matrix (a new local matrix every frame rebuilds the
            // native gradient on Android 8 to 12).
            float hx = -bw * (0.22f + 0.12f * swayX.value), hy = -bh * (0.3f + 0.1f * swayY.value);
            c.translate(hx - bw / 2, hy - bh / 2);
            shape.offset(-hx, -hy);
            tileFill.setShader(tileSheen);
            tileFill.setAlpha(Math.round(255 * fc));
            c.drawPath(shape, tileFill);
            tileFill.setShader(null);
            tileFill.setAlpha(255);
            shape.offset(hx, hy);
        }
        c.restore();
    }

    /** The focused tile's highlight for this size (centred on the canvas origin; drawn moved to where it sits). */
    private void tileFx(float bw, float bh) {
        if (tileFxW == bw && tileSheen != null) return;
        tileFxW = bw;
        tileSheen = new android.graphics.RadialGradient(bw / 2, bh / 2, bw * 0.62f, new int[] {0x24FFFFFF, 0x0CFFFFFF, 0x00FFFFFF},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
    }

    /** Apple's continuous-corner path for one size, at the origin, rebuilt only when the size changes. */
    private static final class Shape {
        final android.graphics.Path path = new android.graphics.Path();
        private float w = -1, h = -1, r = -1;

        android.graphics.Path of(float w, float h, float r) {
            if (w != this.w || h != this.h || r != this.r) {
                this.w = w;
                this.h = h;
                this.r = r;
                Corners.continuous(path, w, h, r);
            }
            return path;
        }
    }

    /**
     * The emblem at its "optical" size (all logos with the same amount of ink, plus each one's own adjustment, never
     * past 76% × 58% of the tile), already filtered to the tile at rest and focused: drawn pixel for pixel, without
     * resampling (on a 720p UI, shrinking at draw time made it look jagged), over its soft shadow. The canvas is in the
     * tile's coordinates and scaled by `s`.
     */
    private void drawEmblem(Canvas c, HomeApps.Item it, int mode, float s, float px, float py) {
        Emblem e = emblem(it, mode == 2 && it.logoDark != null);
        if (e == null) return;
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = rest ? e.rest : e.focus;
        float scale = rest ? 1 : 1 / (1 + FOCUS_GROW);   // the focused copy is 1.15× bigger: the canvas scales it back
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale, left = px - dw / 2, top = py - dh / 2;
        tmp2.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp2, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    /** The app's emblem set (made once per logo and tile size; a new logo from the TV replaces it). */
    private Emblem emblem(HomeApps.Item it, boolean dark) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (src == null) return null;
        Map<String, Emblem> set = dark ? darkEmblems : emblems;
        Emblem e = set.get(it.key);
        if (e != null && e.src == src) return e;
        e = new Emblem();
        e.src = src;
        float bw = TV_TILE_W * k * (1 + FOCUS_GROW), bh = TV_TILE_H * k * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale;
        if (it.emblemHeight > 0) {   // an icon (HomeApps' composer): a share of the tile's height, never past 76% wide
            scale = Math.min(bh * it.emblemHeight / lh, bw * 0.76f / lw);
        } else {
            scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
            scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        }
        e.focus = Ui.downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        e.rest = Ui.downscale(src, Math.round(lw * scale / (1 + FOCUS_GROW)), Math.round(lh * scale / (1 + FOCUS_GROW)));
        set.put(it.key, e);
        return e;
    }


    /** True when the Top Shelf shows a photo (the text over it is white); false over the plain gradient. */
    private boolean onPhoto() {
        return bgTo != null && bgTo.image != null;
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`: the shader of an anti-aliased rounded rectangle. A
     * clipPath there isn't anti-aliased on some GPUs (stair-stepped corners on a MediaTek TV), and on Android 8 to 11 it
     * cost a mask on every frame of a focus animation (measured on a Mi Box 3: most of a 49 ms frame). `shader` is the
     * image's own, made with it (HomeApps.Item, Backdrop) and dropped with it; the canvas does the scaling, so drawing
     * changes nothing in it and allocates nothing.
     */
    private void roundImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        // Filled and centred, never stretched: a 16:9 banner on a 5:3 tile loses a little of its sides.
        float iw = image.getWidth(), ih = image.getHeight();
        float sc = Math.max(cell.width() / iw, cell.height() / ih);
        float vw = cell.width() / sc, vh = cell.height() / sc, ox = (iw - vw) / 2, oy = (ih - vh) / 2;
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sc, sc);
        c.translate(-ox, -oy);
        imageFill.setShader(shader);
        c.drawRoundRect(ox, oy, ox + vw, oy + vh, r / sc, r / sc, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    // MARK: - Control Center: a floating glass panel on the right (tvOS's, opened by holding the TV button there)

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.add(HomeApps.sleepTimer());
        list.addAll(HomeApps.allInputs(c));
        list.addAll(HomeApps.drawerShortcuts(c));
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        for (HomeApps.Item it : list) if (it.kind == HomeApps.INPUT || it.kind == HomeApps.LIVE_TV) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.78f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    /** The panel, its 2-column bento cards (they shrink when there are many) and the weather card at the bottom. */
    private void layoutDrawer() {
        int n = drawerItems.size();
        panelRect.set(CC_LEFT * k, CC_TOP * k, CC_RIGHT * k, CC_BOTTOM * k);
        snap(panelRect);
        boolean wx = Prefs.weather();
        float left = (CC_LEFT + CC_PAD) * k, top = (CC_TOP + CC_PAD + 136) * k;
        layoutProfiles(left);
        float bottom = (CC_BOTTOM - CC_PAD - (wx ? CC_WEATHER_H + CC_GAP : 0)) * k, gap = CC_GAP * k;
        int rows = Math.max(1, (n + 1) / 2);
        float ch = Math.min(CC_CARD_H * k, (bottom - top - (rows - 1) * gap) / rows);
        for (int i = 0; i < n; i++) {
            float x = left + (i % 2) * (CC_CARD_W * k + gap), y = top + (i / 2) * (ch + gap);
            drawerRects[i].set(x, y, x + CC_CARD_W * k, y + ch);
            snap(drawerRects[i]);
        }
        weatherCard.set(left, (CC_BOTTOM - CC_PAD - CC_WEATHER_H) * k, (CC_RIGHT - CC_PAD) * k, (CC_BOTTOM - CC_PAD) * k);
        snap(weatherCard);
        quotesCard.setEmpty();
        // The names cut to fit once (never while drawing); the symbols at their size (the canvas scales the focused one).
        drawerLabels = new String[n];
        for (int i = 0; i < n && ccLabel != null; i++) {
            String label = drawerItems.get(i).label == null ? "" : drawerItems.get(i).label;
            drawerLabels[i] = TextUtils.ellipsize(label, new TextPaint(ccLabel), (CC_CARD_W - 44) * k, TextUtils.TruncateAt.END).toString();
        }
        for (HomeApps.Item it : drawerItems) Symbols.warm(getContext(), it.symbol, 34 * k);
        Symbols.warmWeather(getContext(), 44 * k);
        Symbols.warmWeather(getContext(), 26 * k);
        drawerDirty = true;
    }

    /** Control Center's top row: the profiles' avatars (84 round) and names, cut to fit once. */
    private void layoutProfiles(float left) {
        if (ccName == null) return;
        profiles = Profiles.all(getContext());
        int n = profiles.size();
        if (profileRects.length != n) {
            profileRects = new RectF[n];
            profileFocus = new Spring[n];
            for (int i = 0; i < n; i++) {
                profileRects[i] = new RectF();
                profileFocus[i] = new Spring(0.28f, 0.78f, 0);
            }
            profileIndex = Math.min(profileIndex, Math.max(0, n - 1));
        }
        float size = 84 * k, gap = 28 * k, top = (CC_TOP + CC_PAD) * k;
        profileAvatars = new Bitmap[n];
        profileNames = new String[n];
        for (int i = 0; i < n; i++) {
            float x = left + i * (size + gap);
            profileRects[i].set(x, top, x + size, top + size);
            profileAvatars[i] = Profiles.avatar(getContext(), profiles.get(i), Math.round(size));
            profileNames[i] = TextUtils.ellipsize(profiles.get(i).name, new TextPaint(ccName), size + gap - 6 * k, TextUtils.TruncateAt.END).toString();
        }
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerFrom = zone == Z_TOP ? Z_TOP : zone == Z_STATUS ? Z_STATUS : Z_SHELF;
        drawerOpen = true;
        drawer.tune(0.45f, 0.85f);   // appearing is a little slower than disappearing
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = 0;
        // On the profiles when there is more than one to switch to; else on the first card.
        ccOnProfiles = profiles.size() > 1;
        profileIndex = Profiles.current(getContext());
        for (int i = 0; i < profileFocus.length; i++) profileFocus[i].snap(ccOnProfiles && i == profileIndex ? 1 : 0);
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(!ccOnProfiles && i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.tune(0.40f, 0.88f);   // disappearing is brisker than appearing
        drawer.target = 0;
        zone = drawerFrom == Z_TOP && !lightButtons.isEmpty() ? Z_TOP : drawerFrom == Z_STATUS ? Z_STATUS : Z_SHELF;
        if (zone == Z_SHELF && tab != TAB_TV) zone = Z_STATUS;   // the Dock isn't there on Home and Welly
        for (Spring f : drawerFocus) f.snap(0);   // the panel goes away: its focus doesn't need to animate
        for (Spring f : profileFocus) f.snap(0);
    }

    /** Color for Control Center's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /**
     * A Control Center card's surface: a plain fill (white 12 % by day, black 22 % at night) in Apple's continuous
     * shape. Not glass: the panel under it already is (no glass on glass, DESIGN-TVOS.md §0b #5).
     */
    private void drawerCard(Canvas c, RectF r, Shape shape, float alpha) {
        if (alpha <= 0.004f) return;
        fill.setShader(null);
        int base = blend(0x1FFFFFFF, 0x38000000, dark());
        fill.setColor((base & 0xFFFFFF) | (Math.round((base >>> 24) * alpha) << 24));
        c.save();
        c.translate(r.left, r.top);
        c.drawPath(shape.of(r.width(), r.height(), CC_CARD_RADIUS * k), fill);
        c.restore();
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        dayLow.setColor(tone(L_DIM, 0xADFFFFFF));
        // The panel: a deep soft shadow and the Liquid Glass, a little more opaque than the Dock's (text sits on it).
        float pr = CC_RADIUS * k;
        tmp.set(panelRect);
        tmp.offset(0, 24 * k);
        shadow.setColor(tone(0x2E000000, 0x66000000));
        shadows.draw(c, tmp, pr, SHADOW_BLUR * k, shadow);
        scene.liquid(c, panelRect, pr, tone(0xFFFFFFFF, SMOKE), tone(0.46f, 0.40f), tone(0.03f, 0.03f), 34 * k, 26 * k,
                tone(0.7f, 0.9f), 1);
        drawProfiles(c);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
    }

    /** The profiles: avatars with names; the current one ringed; the focused one grows and turns its ring white. */
    private void drawProfiles(Canvas c) {
        int current = Profiles.current(getContext());
        ccName.setColor(drawerInk(0.9f));
        for (int i = 0; i < profileRects.length && i < profileAvatars.length; i++) {
            RectF r = profileRects[i];
            float f = Ui.clamp(profileFocus[i].value), s = 1 + 0.1f * profileFocus[i].value, rad = r.width() / 2;
            c.save();
            c.scale(s, s, r.centerX(), r.centerY());
            if (f > 0.01f) {
                tmp.set(r);
                tmp.offset(0, 10 * k * f);
                shadowSoft.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                shadows.draw(c, tmp, rad, KEY_BLUR * k, shadowSoft);
            }
            if (profileAvatars[i] != null) {
                c.drawBitmap(profileAvatars[i], r.left, r.top, bitmapPaint);
            } else {
                fill.setShader(null);
                fill.setColor(0xFF8E8E93);
                c.drawCircle(r.centerX(), r.centerY(), rad, fill);
            }
            if (i == current || f > 0.01f) {
                rim.setShader(null);
                rim.setColor(Ui.alpha(0xFFFFFFFF, i == current ? Math.max(0.85f, f) : f));
                rim.setStrokeWidth((3 + f) * k);
                c.drawCircle(r.centerX(), r.centerY(), rad + 5 * k, rim);
                rim.setStrokeWidth(2.5f * k);
            }
            c.restore();
            if (profileNames[i] != null) c.drawText(profileNames[i], r.centerX(), r.bottom + (30 + 8 * f) * k, ccName);
        }
    }

    /**
     * A bento card: the symbol at the top left and the name at the bottom left, on glass; an input with no device is
     * dimmed. Focused: white with ink, a little larger (the canvas scales it: the text and symbol keep their size).
     */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.06f * drawerFocus[i].value;
        RectF base = drawerRects[i];
        float r = CC_CARD_RADIUS * k;
        c.save();
        c.scale(s, s, base.centerX(), base.centerY());
        if (f > 0.01f) {
            tmp.set(base);
            tmp.offset(0, 14 * k * f);
            shadowSoft.setColor(Ui.alpha(0xFF000000, 0.4f * f));
            shadows.draw(c, tmp, r, KEY_BLUR * k, shadowSoft);
        }
        if (f < 0.99f) drawerCard(c, base, cardShape, 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.save();
            c.translate(base.left, base.top);
            c.drawPath(cardShape.of(base.width(), base.height(), r), fill);
            c.restore();
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        float pad = 22 * k, size = 34 * k;
        Symbols.draw(getContext(), c, it.symbol, base.left + pad + size / 2, base.top + pad + size / 2, size, ink);
        ccLabel.setColor(ink);
        if (i < drawerLabels.length && drawerLabels[i] != null) c.drawText(drawerLabels[i], base.left + pad, base.bottom - 20 * k, ccLabel);
        c.restore();
    }

    /** The weather: the place, now (large), the condition and the day's high and low, and the next 4 hours or days. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r, weatherShape, 1);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 24 * k;
        ccLabel.setColor(tone(L_DIM, 0xB3FFFFFF));
        c.drawText(weatherTitle, x, r.top + 40 * k, ccLabel);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 110 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 44 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 92 * k, 44 * k, drawerInk(0.95f));
        ccTemp.setColor(drawerInk(1));
        c.drawText(nowTemp, x + sw + 12 * k - Ui.leftBearing(ccTemp, nowTemp), r.top + 114 * k, ccTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 152 * k, cardDim);
        c.drawText(highLow, x, r.top + 184 * k, cardDim);
        // The next 4 hours (or days), in the card's right half.
        float hx = r.left + 236 * k, hw = (r.right - 16 * k - hx) / 4;
        int count = textDays ? Math.min(4, wx.days.size()) : Math.min(4, wx.hours.size());
        for (int i = 0; i < count; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(textDays ? dayText[i] : hourText[i], hc, r.top + 66 * k, hourPaint);
            String sym = textDays ? Symbols.weather(wx.dayCode.get(i), true) : Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i));
            Symbols.draw(getContext(), c, sym, hc, r.top + 108 * k, 26 * k, drawerInk(0.9f));
            c.drawText(textDays ? dayMaxText[i] : hourTempText[i], hc, r.top + 156 * k, hourTemp);
        }
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            textDays = Prefs.forecast().equals("days") && !wx.days.isEmpty();   // no days yet (an older forecast, offline): the hours
            for (int i = 0; textDays && i < wx.days.size() && i < 5; i++) {
                dayText[i] = HomeData.weekday(wx.days.get(i));
                dayMaxText[i] = HomeData.degrees(wx.dayMax.get(i));
                dayMinText[i] = HomeData.degrees(wx.dayMin.get(i));
            }
            for (int i = 0; !textDays && i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
        }
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot();

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        revealShelf(snap);
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        int swayNow = !snap && !sheetOpen && zone == Z_SHELF ? shelfIndex : -1;
        if (swayNow >= 0 && swayNow != swayTile) {
            swayTile = swayNow;
            swayCell = -1;
            sway();
        } else if (swayNow < 0) {
            swayTile = -1;
        }
        // The focused Dock app's own Top Shelf (or the wallpaper).
        if (zone == Z_SHELF && !sheetOpen) followShelf(!snap);
        float fullWas = full.target;
        full.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (full.target > fullWas) fullAt = SystemClock.uptimeMillis();   // the details' stagger starts now
        if (snap) full.snap(full.target);
        boolean pillChanged = pillFocus.target != (!sheetOpen && zone == Z_STATUS ? 1 : 0);
        pillFocus.target = !sheetOpen && zone == Z_STATUS ? 1 : 0;
        if (snap) pillFocus.snap(pillFocus.target);
        boolean statusChanged = pillChanged;
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER && !ccOnProfiles ? drawerIndex : -1, snap)
                | aim(profileFocus, !sheetOpen && zone == Z_DRAWER && ccOnProfiles ? profileIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        boolean houseNow = !sheetOpen && zone == Z_HOUSE, wellyNow = !sheetOpen && zone == Z_WELLY;
        if (houseNow != houseFocused) house.setFocused(houseFocused = houseNow);
        if (wellyNow != wellyFocused) welly.setFocused(wellyFocused = wellyNow);
        spoken.focusMoved();
    }

    /** The newly focused tile turns toward where the focus came from, then sways back (tvOS's tilt). */
    private void sway() {
        if (Spring.instant) return;   // animations off: no sway
        swayX.value = lastDx;
        swayX.velocity = 0;
        swayX.target = 0;
        swayY.value = lastDy;
        swayY.velocity = 0;
        swayY.target = 0;
        wake();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.30f, 0.80f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = motionDirty = true;   // the grid's first row peeks under the Dock once there are apps
    }

    /** All Apps from the remote's button. Coming back from another app, resume() opens it (it closes the sheet). */
    void showAllApps() {
        if (!running) {
            allAppsOnResume = true;
            return;
        }
        reset(false);
        openSheet();
    }

    private boolean allAppsOnResume;

    private void openSheet() {
        openSheet(0);
    }

    /** The app grid, with the focus on `index` (↓ from the Dock: the app under the focused one). */
    private void openSheet(int index) {
        sheetOpen = true;
        gridIndex = Math.max(0, Math.min(index, others.size() - 1));
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        if (!snap && sheetOpen && swayCell != gridIndex) {
            swayCell = gridIndex;
            swayTile = -1;
            sway();
        }
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        // tvOS: no title and no scrim of its own; the background dims behind it (drawLightSky).
        float shift = scroll.value * GRID_ROW * k;
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, GRID_W * 1.3f * k, TextUtils.TruncateAt.END).toString();
            }
        }
        // The focused app last: it grows over its neighbours without being covered by them.
        int top = -1;
        for (int i = 0; i < n; i++) if (gridFocus[i].value > 0.02f && (top < 0 || gridFocus[i].value > gridFocus[top].value)) top = i;
        for (int i = 0; i < n; i++) if (i != top) drawGridTile(c, i, shift);
        if (top >= 0) drawGridTile(c, top, shift);
    }

    private void drawGridTile(Canvas c, int i, float shift) {
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        int r = i / COLUMNS, col = i % COLUMNS;
        float y = GRID_TOP * k + r * GRID_ROW * k - shift;
        if (y > h || y + GRID_ROW * k < 0) return;
        float x = left + col * (GRID_W + GRID_GAP) * k;
        gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
        float f = gridFocus[i].value, fc = Ui.clamp(f);
        drawAppTile(c, others.get(i), gridCell, f, 0);
        if (sheetOpen && i == gridIndex) {
            float s = 1 + FOCUS_GROW, cx = gridCell.centerX(), cy = gridCell.centerY() - TV_FOCUS_LIFT * k;
            gridFocusCell.set(cx - gridCell.width() * s / 2, cy - gridCell.height() * s / 2, cx + gridCell.width() * s / 2, cy + gridCell.height() * s / 2);
        }
        // Only the focused app's name, centred under it, as on tvOS.
        if (fc <= 0.01f) return;
        gridLabel.setColor(Ui.alpha(tone(L_INK, WHITE), fc));
        float bottom = gridCell.bottom + gridCell.height() * TV_FOCUS_HALF * f - TV_FOCUS_LIFT * k * f;
        c.drawText(gridLabels[i], gridCell.centerX(), bottom + 22 * k + nameCap, gridLabel);
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons() : null;   // they change with Prefs too
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                if (s != null) setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK, Back and Settings (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) {
            // The remote's voice button, anywhere on the home screen: Welly listens.
            if (down && e.getRepeatCount() == 0) voice();
            return true;
        }
        // Welly's content has the focus: it takes the keys it uses; ↑ at its top and Back come back here (the tab bar).
        if (zone == Z_WELLY && !sheetOpen && !drawerOpen && welly.onKey(e)) return true;
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf. A timer, since
                // not every remote repeats a held key, or the long-press repeat, whichever comes first; the release
                // after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_NOTIFICATION:
                // The remote's Settings button (TCL's sends NOTIFICATION) opens TV Settings, as Hisense's gear does by
                // default; ours stay on the gear on screen. Hisense's gear is a Remote Buttons choice (RemoteKeys); where
                // the system takes the key first, it never gets here.
                if (RemoteKeys.HISENSE) return false;
                if (down && e.getRepeatCount() == 0) HomeApps.open(activity, HomeApps.tvSettings(), this, w / 2, h / 2, 1, 1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_HOUSE || zone == Z_WELLY) {
            focusTabBar();      // Back in a tab's content: the tab bar
            retarget(false);
        } else if (tab != TAB_TV && (zone == Z_TOP || zone == Z_STATUS)) {
            selectTab(TAB_TV);  // Back on the bar: the TV tab
            zone = Z_TOP;
            topIndex = TAB_TV;
            retarget(false);
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        lastDx = dx;
        lastDy = dy;
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default: {
                // Control Center: 2 columns; ← from the left column closes it.
                int n = drawerItems.size();
                if (dx < 0 && drawerIndex % 2 == 0) {
                    closeDrawer();
                } else if (dx != 0) {
                    int next = drawerIndex + dx;
                    if (next < 0 || next >= n || next / 2 != drawerIndex / 2) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    if (drawerIndex < 2) return;
                    drawerIndex -= 2;
                } else if (drawerIndex + 2 < n) {
                    drawerIndex += 2;
                } else if (drawerIndex / 2 < (n - 1) / 2) {
                    drawerIndex = n - 1;
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the weather is there to be read, and this is how it gets to it.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
            }
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        if (dx != 0) lean = dx;
        if (dy < 0 && r == 0) {
            // ↑ from the first row: back to the Dock, on the app above.
            float x = gridColumns()[col].centerX();
            closeSheet();
            zone = Z_SHELF;
            if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, x);
            retarget(false);
            click.move();
            return;
        }
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (!sheetOpen && zone == Z_HOUSE) {
            click.open();
            house.toggle();
            return;
        }
        if (!sheetOpen && zone == Z_WELLY) return;   // Welly handles its own OK
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = gridFocusCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            // The pill: Control Center.
            click.open();
            openDrawer();
            retarget(false);
            return;
        } else if (zone == Z_DRAWER && ccOnProfiles) {
            // A profile: it becomes the current one (its avatar goes to the pill).
            if (profileIndex >= profiles.size()) return;
            click.open();
            Profiles.setCurrent(getContext(), profileIndex);
            layoutPill();
            drawerDirty = statusDirty = true;
            return;
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {   // OK on a tab: into it, as ↓
                selectTab(topIndex);
                enterTab();
                click.move();
                retarget(false);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            // HDMI Inputs: the focus on the first input; Control Center: on its first card.
            for (int i = 0; HomeApps.TOP_INPUTS.equals(it.key) && i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move

    private void hold() {
        if (sheetOpen) toggleShelf(); else startReorder();
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int max = HomeApps.maxShelf(c), shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= max) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= max) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Free My TV › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = 0; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        out.add(a11yId(Z_STATUS, 0));
        if (tab == TAB_TV) {   // the Dock shows only on the TV tab
            for (int i = 0; i < Math.min(3, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
            for (int i = shelfFirst; i < Math.min(shelf.size(), shelfFirst + shelfVisible); i++) out.add(a11yId(Z_SHELF, i));
        }
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i >= 3) return label(lightButtons, i - 3);
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", 3);
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case Z_STATUS: return clockText + ". " + Ui.tr("Control Center");
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? tabBar : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(3, cards.size())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            case Z_STATUS:
                a11yRect.set(pillRect);
                dy = status.getTranslationY();
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() || ccOnProfiles ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case Z_STATUS: return a11yId(Z_STATUS, 0);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                ccOnProfiles = false;
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 3) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                selectTab(TAB_TV);
                shelfIndex = i;
                break;
            case Z_STATUS:
                if (drawerOpen) closeDrawer();
                pillFrom = Z_SHELF;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        if (Prefs.forecast().equals("days") && !wx.days.isEmpty()) {   // as the card: the hours until there are days
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < wx.days.size() && i < 5; i++) {
                // "Monday: High 25°, low 18°; Tuesday: …": the whole day ("Sun" and "Sat" are words of their own), and
                // which number is the high (the sentence's own end dropped).
                String range = Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.dayMax.get(i)),
                        "min", HomeData.degrees(wx.dayMin.get(i))).replaceAll("[.。।]$", "");
                if (i > 0) list.append("; ");
                list.append(HomeData.weekdayName(wx.days.get(i))).append(": ").append(range);
            }
            s.append(' ').append(Ui.trf("Next days: {list}.", "list", list));
        } else if (!wx.hours.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale)).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        for (HomeApps.Item it : lightButtons) {
            Symbols.warm(getContext(), it.symbol, 27 * k);
            Symbols.warm(getContext(), it.symbol, 27 * k * 1.1f);
        }
        Symbols.warmWeather(getContext(), 30 * k);
        Symbols.warm(getContext(), "play", 24 * k);
        layoutPill();
        heroDirty = statusDirty = true;
    }

    /**
     * The status pill (time and avatar) at the top right, and any top buttons left of it (Settings > Top Buttons):
     * measured here, never while drawing.
     */
    private void layoutPill() {
        if (tvPill == null) return;
        profiles = Profiles.all(getContext());
        int size = Math.round(PILL_AVATAR * k);
        pillAvatar = Profiles.avatar(getContext(), profiles.get(Profiles.current(getContext())), size);
        pillTimeW = tvPill.measureText(clockText);
        float right = PILL_RIGHT * k, top = PILL_TOP * k, ph = PILL_H * k;
        float width = 24 * k + pillTimeW + 18 * k + PILL_AVATAR * k + 4 * k;
        pillRect.set(right - width, top, right, top + ph);
        snap(pillRect);
        float b = PILL_H * k, x = pillRect.left - 16 * k, cy = pillRect.centerY();
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[3 + i].set(x - b, cy - b / 2, x, cy + b / 2);
            x -= b + L_BUTTON_GAP * k;
        }
        layoutTabs();
        statusDirty = true;
    }

    /** The tab bar's capsule and its three segments (topRects 0 to 2), centred at the top: measured here, not drawing. */
    private void layoutTabs() {
        if (tabText == null) return;
        tabLayoutGeneration = Ui.generation;
        String[] names = tabs();
        float total = 0;
        for (int i = 0; i < 3; i++) {
            tabTextW[i] = Math.max(tabText.measureText(names[i]) + 2 * TAB_PAD * k, TAB_MIN * k);
            total += tabTextW[i];
        }
        float inset = TAB_INSET * k, top = TAB_TOP * k, height = TAB_H * k;
        float left = (w - total) / 2 - inset;
        tabBarRect.set(left, top, left + total + 2 * inset, top + height);
        snap(tabBarRect);
        float x = tabBarRect.left + inset;
        for (int i = 0; i < 3; i++) {
            topRects[i].set(x, top + inset, x + tabTextW[i], top + height - inset);
            x += tabTextW[i];
        }
    }

    /**
     * The tab bar (§2): smoke glass like the pill; the selected tab is a lighter lens that slides between the
     * segments; the focused one is a white pill with ink, 1.06×.
     */
    private void drawTabBar(Canvas c) {
        if (tabLayoutGeneration != Ui.generation) layoutTabs();   // the language changed
        if (tabBarRect.isEmpty()) return;
        float rr = tabBarRect.height() / 2;
        tmp.set(tabBarRect);
        tmp.offset(0, 6 * k);
        shadow.setColor(0x2E000000);
        shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
        scene.liquid(c, tabBarRect, rr, SMOKE, 0.42f, 0.02f, 12 * k, 8 * k, 0.7f, 1);
        // The lens, between the two segments it's moving between.
        float l = Math.max(0, Math.min(2, lens.value));
        int i0 = Math.min(1, (int) l);
        float t = l - i0;
        RectF a = topRects[i0], b = topRects[i0 + 1];
        cell.set(a.left + (b.left - a.left) * t, a.top, a.right + (b.right - a.right) * t, a.bottom);
        fill.setShader(null);
        fill.setColor(0x2EFFFFFF);
        c.drawRoundRect(cell, cell.height() / 2, cell.height() / 2, fill);
        String[] names = tabs();
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            float f = Ui.clamp(topFocus[i].value), s = 1 + 0.06f * topFocus[i].value;
            float selected = Ui.clamp(1 - Math.abs(lens.value - i));
            c.save();
            c.scale(s, s, r.centerX(), r.centerY());
            if (f > 0.01f) {
                float pr = r.height() / 2;
                tmp.set(r);
                tmp.offset(0, 8 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.25f * f));
                shadows.draw(c, tmp, pr, SHADOW_BLUR * k, shadow);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawRoundRect(r, pr, pr, fill);
            }
            tabText.setColor(blend(blend(0xB3FFFFFF, 0xFFFFFFFF, selected), INK, f));
            c.drawText(names[i], r.centerX(), r.centerY() + Ui.halfCap(tabText), tabText);
            c.restore();
        }
    }

    /** The focus to the tab bar, on the selected tab. */
    private void focusTabBar() {
        zone = Z_TOP;
        topIndex = tab;
    }

    /** ↓ (or OK) from the bar: into the selected tab's content. */
    private void enterTab() {
        if (tab == TAB_HOME) {
            zone = Z_HOUSE;
        } else if (tab == TAB_WELLY) {
            zone = Z_WELLY;
        } else {
            zone = pillFrom == Z_HERO && hero != null ? Z_HERO : Z_SHELF;
        }
    }

    /** The remote's voice button: the Welly tab, listening. */
    private void voice() {
        wake();
        if (reordering) cancelReorder(false);
        if (sheetOpen) closeSheet();
        if (drawerOpen) closeDrawer();
        selectTab(TAB_WELLY);
        zone = Z_WELLY;
        topIndex = TAB_WELLY;
        retarget(false);
        welly.listen();
    }

    /** Your photo's background is ready (or new photos came in): draw it. */
    private final Runnable ownReady = () -> {
        if (running) updatePicks();
    };

    /** Shows a tab (TV, Home, Welly): the contents cross-fade and the background frosts and dims behind Home and Welly. */
    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        tabAway.target = index == TAB_TV ? 0 : 1;
        homeShown.target = index == TAB_HOME ? 1 : 0;
        wellyShown.target = index == TAB_WELLY ? 1 : 0;
        lens.target = index;
        if (index != TAB_TV) pillFrom = Z_SHELF;   // back on TV, ↓ from the bar goes to the Dock
        if (index == TAB_HOME) house.refresh();     // the lights' states, every time Home opens
        motionDirty = true;
        wake();
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer or app grid open: the background lightens (by day) or darkens (at night), as a filter on the drawing.
        // (Control Center: the page dims a little behind the panel; the app grid: it lightens or darkens a lot.)
        float dd = tone(0.18f, 0.30f) * Ui.clamp(drawer.value), ds = tone(0.62f, 0.6f) * Ui.clamp(sheet.value);
        // Home and Welly: the background dims behind them (0.45 black by day, 0.55 at night).
        float dt = tone(0.45f, 0.55f) * Ui.clamp(tabAway.value);
        android.graphics.ColorFilter v = dt > dd && dt > ds ? veil(dt, 0xFF000000)
                : dd > ds ? veil(dd, 0xFF000000) : veil(ds, blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (b.image != null) {   // the Top Shelf's photo, sharp; with the app grid (or Home, Welly), its frosted copy over it
            drawImage(c, b.image, alpha);
            drawImage(c, b.soft, alpha * Math.max(Ui.clamp(sheet.value), Ui.clamp(tabAway.value)));
            return;
        }
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    /**
     * The Top Shelf (DESIGN-TVOS.md §1): the shown pick's title logo at the top left, nothing else over the art; in
     * full screen (↑), the details, synopsis and ▶ Play at the bottom left over a soft gradient, and the page dots. A
     * wallpaper (no pick) has no text at all.
     */
    private void drawTopShelf(Canvas c) {
        float fs = Ui.clamp(full.value);
        if (fs > 0.004f && hero != null) {
            if (fullScrim == null) {
                fullScrim = new LinearGradient(0, h, 0, h * 0.55f, new int[] {0x80000000, 0x40000000, 0x00000000},
                        new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
            }
            fill.setShader(fullScrim);
            fill.setAlpha(Math.round(255 * fs));
            c.drawRect(0, h * 0.55f, w, h, fill);
            fill.setShader(null);
            fill.setAlpha(255);
        }
        long up = SystemClock.uptimeMillis();
        float e = heroFadeAt == 0 ? 1 : fadeEase(heroFadeAt, up);
        float slide = 48 * k * heroSlide;
        if (heroFrom != null && e < 1) drawShelfText(c, heroFrom, 1 - e, -slide * e);
        if (hero != null) drawShelfText(c, hero, e, slide * (1 - e));
        // Page dots, in full screen: right-aligned to the content's edge, on the Play button's centre line.
        if (hero != null && hero.dots > 1 && fs > 0.01f) {
            float dot = 8 * k, gap = 14 * k, total = hero.dots * dot + (hero.dots - 1) * gap;
            float x = CONTENT_R * k - total + dot / 2, y = (TV_FULL_BOTTOM - TV_BUTTON_H / 2) * k;
            fill.setShader(null);
            for (int i = 0; i < hero.dots; i++) {
                fill.setColor(Ui.alpha(0xFFFFFFFF, (i == hero.dot ? 1f : 0.4f) * fs));
                c.drawCircle(x, y, dot / 2, fill);
                x += dot + gap;
            }
        }
        if (playingTab != null) drawQuotes(c);
    }

    /**
     * One pick: its logo (or its title) fitted in 380 × 200 at the top left; in full screen, the details with their
     * outline badges, the synopsis, then "▶ Play", bottom-anchored at y 1000.
     */
    private void drawShelfText(Canvas c, Hero h, float alpha, float dx) {
        if (alpha <= 0.004f || h.titleLayout == null) return;
        int a = Math.round(255 * alpha);
        float lx = TV_LOGO_X * k + dx, ly = TV_LOGO_Y * k;
        Bitmap logo = recs.logo(h.pick);
        if (logo != null) {
            float sc = Math.min(TV_LOGO_W * k / logo.getWidth(), TV_LOGO_H * k / logo.getHeight());
            tmp2.set(lx, ly, lx + logo.getWidth() * sc, ly + logo.getHeight() * sc);
            bitmapPaint.setAlpha(a);
            c.drawBitmap(logo, null, tmp2, bitmapPaint);
            bitmapPaint.setAlpha(255);
        } else {
            tvTitle.setAlpha(a);
            c.save();
            c.translate(lx - Ui.leftBearing(tvTitle, h.title), ly);
            h.titleLayout.draw(c);
            c.restore();
        }
        float fa = alpha * Ui.clamp(full.value);
        if (fa <= 0.004f) return;
        // Entering, the details come in after each other (metadata, synopsis, Play: 40 ms apart); leaving, together.
        long since = SystemClock.uptimeMillis() - fullAt;
        boolean entering = full.target >= 0.5f;
        float a0 = fa * (entering ? stage(since, 0) : 1), a1 = fa * (entering ? stage(since, 40) : 1);
        float a2 = fa * (entering ? stage(since, 80) : 1);
        float x = TV_MARGIN * k + dx, bh = 30 * k;
        float buttonTop = (TV_FULL_BOTTOM - TV_BUTTON_H) * k;
        float overviewH = h.overviewLayout == null ? 0 : h.overviewLayout.getHeight();
        float overviewTop = buttonTop - 30 * k - overviewH;
        float metaMid = overviewTop - (overviewH > 0 ? 22 : 0) * k - bh / 2;
        tvMeta.setAlpha(Math.round(0xCC * a0));
        c.drawText(h.meta, x, metaMid + Ui.halfCap(tvMeta), tvMeta);
        // Outline badges after the details: rounded rectangles, the ★ score with a star.
        float bx = x + h.metaW + (h.meta.isEmpty() ? 0 : 18 * k);
        border.setStrokeWidth(1.5f * k);
        border.setColor(Ui.alpha(0x8CFFFFFF, a0));
        tvBadge.setAlpha(Math.round(0xD9 * a0));
        for (int i = 0; i < h.badges.length; i++) {
            badgeRect.set(bx, metaMid - bh / 2, bx + h.badgeW[i], metaMid + bh / 2);
            c.drawRoundRect(badgeRect, 7 * k, 7 * k, border);
            float tx = bx + 10 * k;
            if (i == h.ratingBadge) {
                drawStar(c, tx + 8 * k, metaMid, 18 * k, Ui.alpha(0xFFFFFFFF, a0 * 0.85f));
                tx += 16 * k + 5 * k;
            }
            c.drawText(h.badges[i], tx, metaMid + Ui.halfCap(tvBadge), tvBadge);
            bx += h.badgeW[i] + 8 * k;
        }
        border.setStrokeWidth(2 * k);
        if (h.overviewLayout != null) {
            tvOverview.setAlpha(Math.round(0xB8 * a1));
            c.save();
            c.translate(x, overviewTop);
            h.overviewLayout.draw(c);
            c.restore();
        }
        if (!h.button) return;
        // "▶ Play": focused in full screen (white with ink, 1.08×).
        float f = Ui.clamp(heroFocus.value), s = 1 + 0.08f * heroFocus.value;
        float ph = TV_BUTTON_H * k, icon = 24 * k, pad = 34 * k;
        float pw = pad + icon + 12 * k + h.appW + pad;
        heroButton.set(x, buttonTop, x + pw, buttonTop + ph);
        cell.set(heroButton.centerX() - pw * s / 2, heroButton.centerY() - ph * s / 2, heroButton.centerX() + pw * s / 2, heroButton.centerY() + ph * s / 2);
        float rr = cell.height() / 2;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f * a2));
            shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
        }
        scene.liquid(c, cell, rr, SMOKE, 0.22f, 0.05f, 16 * k, 10 * k, 0.85f, a2 * (1 - f));
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f * a2));
            c.drawRoundRect(cell, rr, rr, fill);
        }
        int ink = Ui.alpha(blend(0xFFFFFFFF, INK, f), a2);
        c.save();
        c.scale(s, s, heroButton.centerX(), heroButton.centerY());
        Symbols.draw(getContext(), c, "play", heroButton.left + pad + icon / 2, heroButton.centerY(), icon, ink);
        tvButton.setColor(ink);
        c.drawText(Ui.tr("Play"), heroButton.left + pad + icon + 12 * k, heroButton.centerY() + Ui.halfCap(tvButton), tvButton);
        c.restore();
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * The round buttons (Live TV, HDMI Inputs and Settings by default): icon-only glass circles (light by day, smoked at
     * night); the focused one turns white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        drawPill(c);
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            if (f > 0.01f) {
                String label = it.label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
        if (sleepText.isEmpty()) return;
        float right = (lightButtons.isEmpty() ? pillRect.left : topRects[3].left) - 24 * k, cy = pillRect.centerY();
        lQuote.setColor(0xE6FFFFFF);
        float tw = lQuote.measureText(sleepText), size = lQuote.getTextSize();
        c.drawText(sleepText, right - tw, cy + Ui.halfCap(lQuote), lQuote);
        Symbols.draw(getContext(), c, "bedtime", right - tw - size * 0.9f, cy, size, lQuote.getColor());
    }

    /** How far one of full screen's details has come in, `delay` ms after the others started (eased, 200 ms). */
    private static float stage(long since, long delay) {
        float t = Ui.clamp((since - delay) / 200f);
        return t * t * (3 - 2 * t);
    }

    /** A five-pointed star `size` wide, centred on (cx, cy): the path is made once, at unit size. */
    private void drawStar(Canvas c, float cx, float cy, float size, int color) {
        if (star == null) {
            star = new android.graphics.Path();
            for (int i = 0; i < 10; i++) {
                double a = -Math.PI / 2 + i * Math.PI / 5;
                float r = i % 2 == 0 ? 0.5f : 0.21f;
                float x = (float) (Math.cos(a) * r), y = (float) (Math.sin(a) * r) + 0.04f;
                if (i == 0) star.moveTo(x, y); else star.lineTo(x, y);
            }
            star.close();
        }
        c.save();
        c.translate(cx, cy);
        c.scale(size, size);
        fill.setShader(null);
        fill.setColor(color);
        c.drawPath(star, fill);
        c.restore();
    }

    private android.graphics.Path star;

    /**
     * The status pill (Apple's photo): smoke glass, the time, and the current profile's avatar inset at its right end.
     * Focused (↑ from the Dock): white with ink, 1.06×; OK opens Control Center.
     */
    private void drawPill(Canvas c) {
        if (pillRect.isEmpty()) return;
        float f = Ui.clamp(pillFocus.value), s = 1 + 0.06f * pillFocus.value, rr = pillRect.height() / 2;
        c.save();
        c.scale(s, s, pillRect.centerX(), pillRect.centerY());
        tmp.set(pillRect);
        tmp.offset(0, (6 + 8 * f) * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.18f + 0.2f * f));
        shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
        scene.liquid(c, pillRect, rr, SMOKE, 0.42f, 0.02f, 12 * k, 8 * k, 0.7f, 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawRoundRect(pillRect, rr, rr, fill);
        }
        tvPill.setColor(blend(0xFFFFFFFF, INK, f));
        c.drawText(clockText, pillRect.left + 24 * k, pillRect.centerY() + Ui.halfCap(tvPill), tvPill);
        if (pillAvatar != null) {
            float d = PILL_AVATAR * k;
            c.drawBitmap(pillAvatar, pillRect.right - 4 * k - d, pillRect.centerY() - d / 2, bitmapPaint);
        }
        c.restore();
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    lean = dx;
                    return false;   // along the Dock: move()
                }
                if (dy > 0) {
                    // ↓: the app grid comes up, focused on the app under this one.
                    if (others.isEmpty()) return true;
                    int col = shelf.isEmpty() ? 0 : nearest(gridColumns(), COLUMNS, tileRects[shelfIndex].centerX());
                    openSheet(col);
                    click.move();
                    return true;
                }
                // ↑: full screen on a pick; on a wallpaper, the tab bar.
                if (hero != null) {
                    zone = Z_HERO;
                } else {
                    pillFrom = Z_SHELF;
                    focusTabBar();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return true;
                    click.move();
                    retarget(false);
                    return true;
                } else if (dy < 0) {
                    pillFrom = Z_HERO;
                    focusTabBar();
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                // The pill ends the bar's row: ← to the round buttons, then the tabs.
                if (dx < 0) {
                    zone = Z_TOP;
                    topIndex = 2 + lightButtons.size();
                    if (topIndex < 3) selectTab(topIndex);
                } else if (dy > 0) {
                    enterTab();
                } else {
                    return true;
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = topIndex + dx;
                    if (next > 2 + lightButtons.size()) {
                        zone = Z_STATUS;
                    } else if (next < 0) {
                        return true;
                    } else {
                        topIndex = next;
                        if (next < 3) selectTab(next);   // focusing a tab selects it (tvOS)
                    }
                } else if (dy > 0) {
                    enterTab();
                } else {
                    return true;
                }
                break;
            case Z_HOUSE:
                if (house.move(dx, dy)) {
                    click.move();
                    return true;
                }
                if (dy >= 0) return true;
                focusTabBar();   // ↑ from the top light
                break;
            case Z_WELLY:
                if (dy >= 0) return true;   // a key Welly didn't use
                focusTabBar();
                break;
            case Z_DRAWER:
                if (!ccOnProfiles) {
                    if (dy < 0 && drawerIndex < 2 && profileRects.length > 0) {
                        // ↑ from the first row of cards: the profiles.
                        ccOnProfiles = true;
                        profileIndex = nearest(profileRects, drawerRects[drawerIndex].centerX());
                        break;
                    }
                    return false;   // the cards: move()
                }
                if (dx != 0) {
                    int next = profileIndex + dx;
                    if (next < 0) {
                        closeDrawer();
                    } else if (next >= profileRects.length) {
                        return true;
                    } else {
                        profileIndex = next;
                    }
                } else if (dy > 0 && !drawerItems.isEmpty()) {
                    ccOnProfiles = false;
                    drawerIndex = profileRects[profileIndex].centerX() > (CC_LEFT + CC_RIGHT) / 2 * k && drawerItems.size() > 1 ? 1 : 0;
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    /** The grid's columns, as rects (for nearest(): the column under a Dock tile). */
    private RectF[] gridColumns() {
        if (gridColumnRects == null) {
            gridColumnRects = new RectF[COLUMNS];
            float left = (1920 - (COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP)) / 2;
            for (int i = 0; i < COLUMNS; i++) {
                float x = (left + i * (GRID_W + GRID_GAP)) * k;
                gridColumnRects[i] = new RectF(x, 0, x + GRID_W * k, 1);
            }
        }
        return gridColumnRects;
    }

    private RectF[] gridColumnRects;

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    /** Rounds a shape's edges to whole pixels (crisp at rest; a scale animation moves them again). */
    private static void snap(RectF r) {
        r.set(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
