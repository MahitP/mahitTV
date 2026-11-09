package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import java.io.File;
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
    /** Narrow activity-owned seam for optional ambient video. */
    interface AerialPlayback { void show(long generation, String url); void hide(long generation); }
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
    // Tile focus (Apple TV style): it grows from the centre, as measured on Apple's photo (×1.23, DESIGN-TVOS.md §0),
    // with no lift.
    private static final float FOCUS_GROW = 0.23f, TV_FOCUS_HALF = FOCUS_GROW / 2, TV_FOCUS_LIFT = 0;
    // The Dock: 6 tiles of 244 × 146 (5:3; TV_TILE_H sets the shape), 57.4 apart, the same 1751 px span as Apple's
    // photo (x 85–1835), top 822; the glass 48 past them on the sides and 40 above and below (x 37–1883, y 782–1008),
    // radius 64.
    private static final float TV_MARGIN = 85, TV_TILE_W = 244, TV_TILE_H = 146, TV_TILE_GAP = 57.4f, TV_TILE_RADIUS = 24;
    private static final float TV_DOCK_PAD_X = 48, TV_DOCK_PAD = 40, TV_DOCK_TOP = 782, TV_DOCK_SHOWN = 6, TV_DOCK_RADIUS = 64;
    /** The thin ⌃ above the Dock (only when the focused app's pick shows): its centre's y. */
    private static final float TV_CHEVRON_Y = 718;
    // The Top Shelf: the title's logo fitted in 380 × 200 at the top left; full screen (↑): the details at the bottom
    // left, their bottom 40 above the Dock, which only recesses (36 down, to 0.75: it never leaves, nor do the tab bar
    // and the status pill; TVOS-LAUNCHER-MERGE-SPEC.md V1).
    private static final float TV_LOGO_X = 64, TV_LOGO_Y = 44, TV_LOGO_W = 380, TV_LOGO_H = 200, TV_FULL_BOTTOM = 742;
    private static final float TV_TITLE_W = 640, TV_TEXT_W = 860, TV_BUTTON_H = 60, TV_FULL_DROP = 36, TV_RECESS = 0.75f;
    // The status pill (§1): x up to 1875, y 48–105, the time 24 from its left, the avatar 49 round inset 4 at its right;
    // Control Center's bubble 14 left of it, a 48 px glass circle (Apple's two toggles). The bubble opens Control Center,
    // the pill the profile panel.
    private static final float PILL_RIGHT = 1875, PILL_TOP = 48, PILL_H = 57, PILL_AVATAR = 49, BUBBLE_GAP = 14, BUBBLE_D = 48;
    /**
     * How far the page glides up when the app grid opens: the Dock to y = 60, the grid's first row (peeking at y 1052
     * at rest) to 330.
     */
    private static final float TV_GRID_LIFT = 722;
    // The two panels under the top-right cluster (tvOS's): floating glass on the right, 504 wide inside. Control Center
    // (the bubble) holds the controls; the profile panel (the pill) the profiles, the weather, Inputs and the rest
    // (layoutDrawer). Capsule cards are 244 × 64, 16 apart across.
    private static final float CC_LEFT = 1300, CC_RIGHT = 1860, CC_TOP = 128, CC_BOTTOM = 1020, CC_RADIUS = 44, CC_PAD = 28;
    private static final float CC_CARD_W = 244, CC_CARD_H = 64, CC_GAP = 16, CC_ROW_GAP = 12, CC_CARD_RADIUS = 32;
    private static final float CC_WEATHER_H = 150, CC_SECTION = 14;
    /** Cards never shrink below this (the rest scroll); the volume capsule's height, at the bottom. */
    private static final float CC_CARD_MIN = 56, CC_VOLUME_H = 64;
    /** The Inputs list inside the panel: its rows. */
    private static final float CC_INPUT_ROW = 72, CC_INPUT_GAP = 8;
    private static final float CC_SLIDE = 480;   // the panel slides in from 480 px to the right
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
    private static final long TOAST_MS = 6000, FADE_MS = 700;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 44, KEY_BLUR = 16;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, TABBAR = 8;
    private static final int CHEVRON = 9, MOVE = 10, LAUNCH = 11;
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
    private final View dimmer;
    private final WelcomeView welcome;
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
        // The weather card opens the forecast; the quotes are only read.
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS || (id - 1) % 1000 == 0; }
        @Override public boolean holdable(int id) { int g = (id - 1) / 1000; return board == null && (g == GRID || g == Z_SHELF); }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, tabBar, moveLayer, launchLayer;
    private final HouseView house;
    private final WellyView welly;
    /** "Who's Watching?" over everything, and the golden intro over that (MAHITTV-PROFILES-SPEC.md §2–3). */
    private final LockView lock;
    /** App Lock's passcode sheet over the home screen (AppLock, DESIGN-PASSCODE.md). */
    private final AppLockView appLock;
    private final Intro intro;
    /** The intro has played in this process (first creation); the screen came on while we weren't showing. */
    private static boolean booted, introPending;
    /** Settings → Preview Startup Animation: the whole startup and "Who's Watching?" as the home screen comes back. */
    static boolean replayIntro;
    private long touchedAt;
    private int zone = Z_SHELF;
    // The tabs: tabAway = the TV tab's content hidden and the background frosted and dimmed (Home or Welly); the two
    // contents cross-fade (smooth); lens = where the selected tab's lighter lens is (0 to 2, snappy).
    private final Spring tabAway = new Spring(Spring.TAB_SWAP, 0), homeShown = new Spring(Spring.TAB_SWAP, 0);
    private final Spring wellyShown = new Spring(Spring.TAB_SWAP, 0);
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
    /** The Frosted Horizon, day and twilight (Horizon's cached bitmaps): under any image that hasn't arrived yet. */
    private Bitmap horizonDay, horizonNight;

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
    private Paint tvLabel, tvMeta, tvBadge, tvButton, tvPill, tvName, ccName, ccSmall, ccNum, ccCell;
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
    /**
     * The glare (tvOS's hardware glint): a narrow band of light, its edge bright along the bevel, that sweeps across the
     * focused tile the way the focus came, once per arrival: -1.3 … 1.3 tile half-widths from the middle (critically
     * damped: light doesn't overshoot). Made for one tile width; moved by the canvas, never a new matrix per frame.
     */
    private final Spring glare = new Spring(new float[] {1.1f, 1f}, 1.3f);
    private HomeApps.Item glareFor;
    private Shader glareShader;
    private float glareW;
    private final Paint glareRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Apple's continuous-corner shapes (Corners), made once per size: the tiles, the bento cards, the weather card. */
    private final Shape tileShape = new Shape(), cardShape = new Shape(), weatherShape = new Shape();
    private float tileFxW;
    private final android.graphics.Matrix imageMatrix = new android.graphics.Matrix();
    // The focused tile sways toward where the focus came from, then settles (tvOS's tilt): angles from these springs.
    private final Spring swayX = new Spring(Spring.TILT, 0), swayY = new Spring(Spring.TILT, 0);
    private final android.graphics.Camera tiltCamera = new android.graphics.Camera();
    private final android.graphics.Matrix tiltMatrix = new android.graphics.Matrix();
    private int lastDx, lastDy;
    /** The velocity that tilts a tile ~3° (TILT's peak for this push is ~1). */
    private static final float SWAY_KICK = 30;
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
        TILE_STYLE.put("applemusic", new int[] {0xFF3A3A3C, 0xFF000000, 1});   // tvOS 27.2: a white note on black glass
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    /** The first app the shelf shows: with more apps than fit, it scrolls with the focus. */
    private int shelfFirst;
    private final int shelfVisible;

    // Moving apps (hold OK on one in the Dock, All Apps or an open folder): it rises out of its place and rides a
    // cursor that goes line, outline, line…, in both themes white with a dark edge. A line between two apps puts it
    // there; an outline on an app makes a folder of the two (on a folder: into it; on a Dock app: they swap). OK does
    // it, Back puts everything back as it was. The tiles slide to their new places (FLIP: each starts from where it was).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300, LAND_MS = 700;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.6f, CARRY_SCALE = 0.5f;
    /** The cursor, in 1920-px units: the line's width and how far past the tiles it reaches; the outline's gap and stroke. */
    private static final float LINE_W = 8, LINE_REACH = 16, RING_GAP = 11, RING_STROKE = 6, PART = 12;
    /** An outlined app shrinks into the folder-to-be (its focus spring at −0.3); an outlined folder grows to take it. */
    private static final float HOVER_FOLDER = -0.3f, HOVER_INTO = 0.25f;
    private static final int MOVING = 1, NAMING = 2, LANDING = 3;
    private boolean okHeld, holdArmed;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };
    private AppLayout.Board board;
    private int moveState;
    /** What's moving (drawn on the move layer), and the tile it lands on (hidden until it's there). */
    private HomeApps.Item carried, landing;
    private boolean landFade;
    private long moveAt, landAt;
    private float wiggle;
    private final Map<String, HomeApps.Item> moveItems = new HashMap<>(), madeFolders = new HashMap<>();
    private List<HomeApps.Item> shelfBefore, othersBefore;
    private boolean sheetBefore;
    private int zoneBefore, shelfIndexBefore, gridIndexBefore, shelfFirstBefore;
    private int dockSlots;   // the Dock keeps its width while something moves
    private Spring[] tileSlide = new Spring[0], gridSlideX = new Spring[0], gridSlideY = new Spring[0];
    private final Spring carryX = new Spring(0.36f, 0.8f, 0).px(), carryY = new Spring(0.36f, 0.8f, 0).px();
    private final Spring carryS = new Spring(0.36f, 0.86f, 1), carryA = new Spring(0.24f, 1, 1);
    private final Spring cursorX = new Spring(0.26f, 0.86f, 0).px(), cursorY = new Spring(0.26f, 0.86f, 0).px();
    private final Spring cursorW = new Spring(0.26f, 0.9f, 0).px(), cursorH = new Spring(0.26f, 0.9f, 0).px();
    private final Spring moveShown = new Spring(0.3f, 1, 0);
    private final Map<HomeApps.Item, float[]> flipFrom = new HashMap<>();
    private final android.graphics.Path cursorPath = new android.graphics.Path();
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG), cursorEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cursorRect = new RectF(), moveRect = new RectF(), hintRect = new RectF();
    private final Shape formShape = new Shape();
    private String hintName = "", hintWhere = "", hintVerb = "";
    private Paint hintBold, hintText, hintKey;

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background: picks are only promoted after an explicit, focused media resolution.
    private List<Recs.Pick> picks = new ArrayList<>(), appPicks = new ArrayList<>();
    private static final int BACKGROUND_PROFILE = 0, BACKGROUND_MEDIA = 1;
    private int backgroundMode = -1;
    private String backgroundPackage;
    private final Wallpaper.HeroVisibility heroVisibility = new Wallpaper.HeroVisibility();
    private final Wallpaper.BackgroundFocus backgroundFocus = new Wallpaper.BackgroundFocus();
    /** Every asynchronous watch/art callback is owned by this generation. */
    private long backgroundGeneration;
    /** Full screen (↑ on a pick): 0 = the Dock, 1 = the details at the bottom and the Dock away. */
    private final Spring full = new Spring(Spring.SPATIAL_SCROLL, 0);   // smooth (apple-design-motion: push-like transitions)
    // Explicit profile wallpaper still, if one is being decoded from the validated Aerial cache.
    private Backdrop wallpaper;
    private boolean wallpaperLoading;
    private final AerialCache aerialCache;
    private long aerialGeneration;
    private String liveUrl;
    private boolean liveVideoVisible;
    private String officialStill;
    private String officialStillLoaded;
    private final AerialPlayback aerialPlayback;
    // The status pill: its focus, and the current profile's avatar at its size.
    private final Spring pillFocus = new Spring(Spring.SNAPPY_FOCUS, 0), bubbleFocus = new Spring(Spring.SNAPPY_FOCUS, 0);
    /** In Z_STATUS: the focus is on Control Center's bubble (else on the pill). */
    private boolean onBubble;
    private final RectF bubbleRect = new RectF();
    /** The AirPods glyph left of the bubble, while AirPods or Beats are the output (empty otherwise). */
    private final RectF airRect = new RectF();
    private final RectF miniDst = new RectF();
    /** The tab ↑ left for Control Center's bubble (← goes back to it), or −1. */
    private int topFromTab = -1;
    private Bitmap pillAvatar;
    private int pillFrom = Z_SHELF;          // where ↓ from the pill goes back to
    private float pillTimeW;
    private android.graphics.Path chevron;
    private Shader fullScrim;   // full screen: the soft dark gradient under the details (made once per size)
    private Shader fullScrimBright;
    // Control Center's profiles row (its top row): avatars, names, focus.
    private List<ProfileManager.Profile> profiles = new ArrayList<>();
    private Bitmap[] profileAvatars = new Bitmap[0];
    private String[] profileNames = new String[0];
    private RectF[] profileRects = new RectF[0];
    private Spring[] profileFocus = new Spring[0];
    private boolean ccOnProfiles;
    /** Control Center's volume slider has the focus (←/→ change the volume, as the remote's keys do). */
    private boolean ccOnVolume;
    private final Spring volumeFocus = new Spring(Spring.SNAPPY_FOCUS, 0), weatherFocus = new Spring(Spring.SNAPPY_FOCUS, 0);
    private final Spring volumeLevel = new Spring(Spring.SNAPPY_FOCUS, 0);
    /** Control Center's brightness slider (0..1, Prefs.brightness), and whether the Sleep Timer runs (its circle lit). */
    private final Spring brightLevel = new Spring(Spring.SNAPPY_FOCUS, 1);
    private boolean sleepOn;
    /** The sliders' levels as text, made once (never while drawing). */
    private static final String[] PERCENT = new String[101];
    static { for (int i = 0; i <= 100; i++) PERCENT[i] = Integer.toString(i); }

    /** The cards scroll when there are more than fit (they keep at least CC_CARD_MIN); how far, px. */
    private final Spring ccScroll = new Spring(Spring.SPATIAL_SCROLL, 0).px();
    private float ccScrollMax;
    private final RectF volumeRect = new RectF(), cardsArea = new RectF();
    private final Shape volumeShape = new Shape(), inputShape = new Shape();
    private String[] drawerDetails = new String[0];
    private Paint ccDetail;
    private android.media.AudioManager audio;
    // The volume as last read on LOADER (volumeAvailable: the output isn't fixed), and the step the slider shows.
    private int volumeMax = 1, volumeIndex;
    private boolean volumeAvailable;
    /** The forecast and Recent Apps (TVOS-LAUNCHER-MERGE-SPEC.md S3, S4), over the home and under the lock. */
    private WeatherOverlay weatherOverlay;
    private RecentsOverlay recentsOverlay;
    private VolumeHud volumeHud;
    private boolean volumeKeyDown;   // the volume key whose press we took (its release is ours too)
    private final long[] volumeHeld = new long[2];   // the held volume key's last step and press (RemoteKeys.heldSteps)
    private int profileIndex;
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(Spring.SNAPPY_FOCUS, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    // What the glass shows now (setFrost / setGlassImage copy pixels and make a shader: only when these change).
    private int[] frostShown, glassShown;

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    // Control Center's Inputs list (Live TV and every HDMI port), shown in the panel in place of the platter and cards.
    private List<HomeApps.Item> inputItems = new ArrayList<>();
    private RectF[] inputRects = new RectF[0];
    private Spring[] inputFocus = new Spring[0];
    private String[] inputLabels = new String[0], inputStates = new String[0];
    private float[] inputStateW = new float[0];
    private int inputIndex;
    private String inputsTitle = "";
    private boolean ccInputs;
    private final Spring inputsShown = new Spring(Spring.SHEET_OPEN, 0);
    private final RectF inputsHeader = new RectF();
    private Paint ccTitle;
    private int drawerInputs;          // how many of Control Center's cards are inputs (Live TV and HDMI)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF(), panelRect = new RectF();
    private String[] drawerLabels = new String[0];
    private int drawerFrom = Z_SHELF;            // where the focus goes back when Control Center closes
    private Paint ccTime, ccDate, ccLabel, ccTemp;
    private final Spring drawer = new Spring(Spring.SHEET_OPEN, 0);   // Control Center: SHEET_OPEN, SHEET_CLOSE
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(Spring.SPATIAL_SCROLL, 0);   // the grid's lift: smooth
    private final Spring scroll = new Spring(Spring.SPATIAL_SCROLL, 0);
    private final RectF focusedCell = new RectF(), gridFocusCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // AirPods: an AirPods or Beats output joining opens its live activity in the dynamic dot for 3.5 s.
    private static final long AIR_MS = 3500;
    private String airName;
    private boolean airSpatial;
    private final android.media.AudioDeviceCallback airWatch = new android.media.AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] added) {
            String name = AirPods.find(added);
            if (name != null) airConnected(name, true);
        }
        @Override public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] removed) {
            if (airName != null && AirPods.find(removed) != null) airConnected(AirPods.connected(audio), false);
        }
    };

    // Motion (ANIMATIONS-SPEC.md): dead ends, the OK press, an app opening, coming back, fast scrubbing.
    /** A press toward nothing: the focused thing's nudge (1920-px units; drawn through the rubber band, bumpX/Y). */
    private final Spring nudgeX = new Spring(Spring.BOUNDARY_BUMP, 0).px(), nudgeY = new Spring(Spring.BOUNDARY_BUMP, 0).px();
    private float bumpX, bumpY;
    /** OK held down: the focused thing presses in (MICRO_PRESS), and comes back on release. */
    private final Spring press = new Spring(Spring.MICRO_PRESS, 0);
    private static final float TILE_PRESS = 0.08f, PRESS = 0.04f;
    /** An app opening from a tile: the tile grows on and the home recedes (reset when the home comes back). */
    private final Spring launch = new Spring(Spring.TAB_SWAP, 0);
    private static final int LAUNCH_GRID = -2;
    private int launchZone = -1, launchIndex = -1;
    private long launchAt;
    /** The launch screen (startLaunch): its zoom from the tile (0) to the whole screen (1), opacity, and spinner. */
    private static final long LAUNCH_SPIN_MS = 900, LAUNCH_GIVE_UP_MS = 12_000;
    private final Spring zoom = new Spring(0.42f, 0.9f, 0), launchFade = new Spring(0.2f, 1, 0), spinShown = new Spring(0.3f, 1, 0);
    private boolean launchUp, launchBack;
    private HomeApps.Item launchPicture;
    private Bitmap launchLogo;
    private LinearGradient launchShader;
    private float launchPlateW, launchPlateH;
    private int launchInk = 0xFFFFFFFF;
    private final RectF launchFrom = new RectF(), launchCard = new RectF(), launchPlate = new RectF();
    private final Paint launchPaint = new Paint(Paint.ANTI_ALIAS_FLAG), launchImage = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix launchMatrix = new android.graphics.Matrix();
    /** Back on the home screen (from an app or the screensaver): it settles from 0.98 while fading in (320 ms). */
    private final Spring wakeScale = new Spring(Spring.TITLE_SETTLE, 1), wakeAlpha = new Spring(0.32f, 1f, 1);
    /** An arrow held down (key repeat): focus on FAST_FOCUS, without tilt, sheen or names (scrub: 0 to 1). */
    private boolean scrubbing;
    private final Spring scrub = new Spring(Spring.ROOM_FADE, 0);
    /** The tab bar's lens: its centre and width (px), stretched by its speed; the direction of the last tab change. */
    private final Spring lensX = new Spring(Spring.LIQUID_PILL, 0).px(), lensW = new Spring(Spring.LIQUID_PILL, 0).px();
    private boolean lensPlaced;
    private int tabDir = 1;
    private static final float TAB_SLIDE = 60;
    /** The Top Shelf: the new title settles in 80 ms after the old one starts leaving (220 ms); the dots slide. */
    private final Spring titleIn = new Spring(Spring.TITLE_SETTLE, 1), dotAt = new Spring(Spring.SNAPPY_FOCUS, 0);
    /** Ken Burns on the Top Shelf's photo since kenAt (16 s), and its counter-parallax against the Dock's focus (px). */
    private static final long KEN_MS = 16_000;
    private long kenAt;
    private int kenDir = 1;
    private final Spring parallax = new Spring(Spring.SPATIAL_SCROLL, 0).px();
    /** The ⌃ above the Dock: its own small layer, so it fades and breathes without redrawing the tiles. */
    private Layer chevronLayer;
    private final Spring chevronShown = new Spring(Spring.SNAPPY_FOCUS, 0);
    private float chevronAlpha, chevronY;
    /** The status pill's avatar, cross-dissolving on a profile switch. The pill keeps its size while something plays. */
    private final Spring avatarFade = new Spring(Spring.ROOM_FADE, 1);
    // MARK: The dynamic dot: live activities (DESIGN-DYNAMIC-DOT.md)
    /**
     * One Liquid Glass dot beside the tab bar, as tall as the bar, while any live activity runs: what plays, Focus (Do
     * Not Disturb), AirPods connected, the sleep timer, in that order of importance. It shows the first one's white
     * symbol, as the round buttons do. Focused (→ from Welly), it opens rightward into a capsule with that activity's
     * words and its one control; ↓ ↑ step through the others (the old one slides out as the next slides in), and past
     * either end it knocks as everything else does. AirPods joining opens it by itself for a moment, as an alert. The bar
     * and the dot centre together, never closer than ISLAND_GAP to the buttons at the right nor to the Top Shelf's logo
     * at the left (ISLAND_LEFT).
     */
    private static final float ISLAND_D = TAB_H, ISLAND_H = 72, ISLAND_TEXT = 380, ISLAND_GAP = 32, ISLAND_SPACE = 14;
    private static final float ISLAND_LEFT = TV_LOGO_X + TV_LOGO_W + ISLAND_GAP, ISLAND_ICON = 28;
    private static final float[] ISLAND_OPEN = {0.40f, 0.76f}, ISLAND_CLOSE = {0.30f, 0.92f}, ISLAND_SWAP = {0.34f, 0.88f};
    private static final long ISLAND_PAUSE_MS = 30000;   // paused: the song stays this long, so OK can play it again
    private static final int LIVE_MUSIC = 0, LIVE_FOCUS = 1, LIVE_AIR = 2, LIVE_SLEEP = 3;

    /** One live activity: its symbol and its words (cut when they change), and whether it runs. */
    private static final class Live {
        String icon;   // the song's: music, or tv for a video app that keeps playing
        String title = "", sub = "", titleFit = "", subFit = "";
        float w;
        boolean on;

        Live(String icon) {
            this.icon = icon;
        }
    }

    private final Live[] lives = {new Live("music"), new Live("moon"), new Live("airpods"), new Live("bedtime")};
    /** There (0..1), open (0..1), its open width (px), and the step from one activity to the next (0..1). */
    private final Spring islandShown = new Spring(Spring.LIQUID_PILL, 0), islandOpen = new Spring(ISLAND_OPEN, 0);
    private final Spring islandWidth = new Spring(Spring.LIQUID_PILL, 0), islandSwap = new Spring(ISLAND_SWAP, 1);
    private boolean onIsland, islandFocused, islandWasPlaying, sleepDown, sleepUp, dndOn;
    private int islandAt;          // the activity the focus is on, while onIsland
    private int islandCtrl = 1;    // focused, what's playing: ⏮ (0) ⏯ (1) ⏭ (2) Music Glow (3); the sleep timer: − (0) + (1) × (2)
    private int islandShows = -1;  // the activity the dot shows
    private int islandFrom = -1, islandDir = 1;   // while it steps: the one leaving, and which way (1: ↓)
    private long islandPausedUntil, airAlertUntil;
    private float islandClusterLeft;   // the right cluster's left end, measured with the layout
    private Paint islandTitlePaint, islandSubPaint;
    private final Paint islandArtPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix islandMatrix = new android.graphics.Matrix();
    private final android.graphics.Path islandClip = new android.graphics.Path();
    private final RectF islandNow = new RectF();
    private Bitmap islandArt;
    private BitmapShader islandArtShader;
    private final Runnable islandCheck = this::updateIsland;
    private final BroadcastReceiver dndChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            readDnd();
        }
    };
    private Bitmap pillAvatarFrom;
    private int pillAvatarProfile = -1;
    private final android.graphics.Path pillClip = new android.graphics.Path();
    private final android.graphics.Path wellyIcon = new android.graphics.Path();
    private final android.graphics.Path wellyEye = new android.graphics.Path();
    private final android.graphics.Matrix wellyEyeMatrix = new android.graphics.Matrix();
    private final RectF pillNow = new RectF();
    /** Control Center's rows come in 25 ms apart from this time. */
    private long drawerOpenedAt;
    private static final long CC_ROW_MS = 18, CC_ROW_IN_MS = 240;
    /** The grid's lift over the last frames (rows lag 18 ms each behind the one above). */
    private final long[] liftAt = new long[32];
    private final float[] liftValue = new float[32];
    private int liftHead, liftCount;
    private static final long ROW_LAG_MS = 18;

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
    private final Paint horizonPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
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
    private String weatherTitle, nowTemp, highLow, platterDate, platterSky, platterHighLow, noWeather, platterCity;
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

        // Without a GPU layer, fading a view that may overlap itself means a full-screen offscreen pass for each of them
        // on every frame. These fade for a moment and their parts barely overlap, so the alpha goes on each draw instead.
        @Override public boolean hasOverlappingRendering() {
            return !Ui.LEGACY;
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
                case CHEVRON: drawChevron(c, w / 2f, TV_CHEVRON_Y * k); break;
                case MOVE: drawMove(c); break;
                case LAUNCH: drawLaunch(c); break;
                default:
                    drawToast(c);
                    drawMoveHint(c);
                    drawNaming(c);
                    break;
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

    HomeView(Activity a, AerialCache aerialCache, AerialPlayback aerialPlayback) {
        super(a);
        activity = a;
        this.aerialPlayback = aerialPlayback;
        this.aerialCache = aerialCache;
        data = HomeData.get(a);
        recs = Recs.get(a);
        playing = new NowPlaying.Watch(a, this::onPlaying);
        click = new Click(a);
        shelfVisible = Math.min((int) TV_DOCK_SHOWN, HomeApps.shelfVisible(a));
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        try {
            if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        } catch (RuntimeException e) {   // some projectors (Rockchip rk3566 HY300) ship without the launcherapps service
            AppLog.w("no app-change callback: " + e.getMessage());
        }
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        moveLayer = new Layer(a, MOVE);   // the cursor and the carried tile, over the Dock and All Apps (only while moving)
        moveLayer.setVisibility(INVISIBLE);
        launchLayer = new Layer(a, LAUNCH);   // an app's launch screen, over everything of the home screen (startLaunch)
        launchLayer.setVisibility(INVISIBLE);
        tabBar = new Layer(a, TABBAR);
        chevronLayer = new Layer(a, CHEVRON);
        house = new HouseView(a);
        welly = new WellyView(a, scene);
        lock = new LockView(a, scene, lockListener);
        appLock = new AppLockView(a, new AppLockView.Listener() {
            @Override public void onUnlocked(HomeApps.Item it) {
                // The app grows out of the sheet's card as the sheet swells and fades into it.
                int cw = Math.round(352 * k), ch = Math.round(198 * k);
                if (!HomeApps.open(activity, it, HomeView.this, getWidth() / 2 - cw / 2, getHeight() / 2 - ch / 2, cw, ch)) {
                    showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
                }
            }

            @Override public void onGone() {
                wake();
            }
        });
        welcome = new WelcomeView(a, scene, welcomeListener);
        intro = new Intro(a, introListener);
        weatherOverlay = new WeatherOverlay(a, scene);
        recentsOverlay = new RecentsOverlay(a, scene, item -> {
            recentsOverlay.hide();
            if (!HomeApps.open(a, item, null)) showToast(Ui.trf("Couldn't open {app}.", "app", item.label));
        });
        volumeHud = new VolumeHud(a, scene);
        // Control Center's brightness below 100: black over everything in the home window (the lock and the intro too).
        dimmer = new View(a);
        dimmer.setBackgroundColor(0xFF000000);
        dimmer.setFocusable(false);
        dimmer.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        audio = (android.media.AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer, tabBar}) {
            v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        }
        // Home and Welly stay ready in theirs even on those: drawing either for a tab swap took 50 to 70 ms a frame on a
        // 2.45 GB TCL. Hidden, they keep it by staying visible at alpha 0 (see show), which isn't composited or redrawn.
        house.setLayerType(LAYER_TYPE_HARDWARE, null);
        welly.setLayerType(LAYER_TYPE_HARDWARE, null);
        // The Dock's glass too, where it's a shader: without a layer that shader ran over the whole Dock on every frame,
        // whatever moved. It's only drawn again when the photo behind it changes.
        if (Ui.SMALL_MOTION) platter.setLayerType(LAYER_TYPE_HARDWARE, null);
        // The Home and Welly tabs sit on the background, under the TV tab's layers and the tab bar.
        for (View v : new View[] {sky, house, welly, heroLayer, status, tabBar, platter, tiles, chevronLayer, drawerLayer, sheetLayer, moveLayer, toastLayer, launchLayer}) {
            // TalkBack reads the items (Spoken); Welly speaks for itself.
            if (v != welly) v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(v);
        }
        addView(weatherOverlay);
        addView(recentsOverlay);
        addView(volumeHud);
        addView(appLock);
        addView(lock);
        addView(welcome);
        addView(intro);
        addView(dimmer);
        dimmer.setTranslationZ(3);
        // The volume shows over the forecast and Recent Apps (which come to the front when they open), never over the
        // lock or the intro: drawn in this order whatever bringToFront does.
        volumeHud.setTranslationZ(1);
        lock.setTranslationZ(2);
        welcome.setTranslationZ(2);
        appLock.setTranslationZ(1.5f);   // over the volume and Recent Apps, under "Who's Watching?"
        intro.setTranslationZ(2);
        intro.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        house.setAlpha(0);
        welly.setAlpha(0);
        if (!Ui.LEGACY) {
            house.setVisibility(INVISIBLE);
            welly.setVisibility(INVISIBLE);
        }
        setContentDescription("mahitTV");
        WatchLog.seedPreview(a);   // preview builds: Mahit's and Dhruvin's sample Docks and histories (once)
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
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
        setInputItems(HomeApps.allInputs(a));
        readDrawerState(true);   // the volume slider's row is ready before Control Center first opens
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        AppLock.gate = this::askUnlock;
        glowShown = Prefs.glowOn();   // Settings may have changed it
        // Text Size changed in Settings (another process): every text again at the new size, as after a resize.
        float scale = Prefs.textScale();
        if (scale != Ui.textScale) {
            Ui.textScale = scale;
            if (w > 0) onSizeChanged(w, h, w, h);
        }
        Scene.frosted = "frosted".equals(Prefs.glass());
        applyBrightness();
        // Animations off in the TV's settings (animator duration scale 0): every spring jumps, nothing sways.
        Spring.instant = android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        checkNight(false);
        boolean h24 = Ui.h24();
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
        if (audio != null) {
            // Already connected: the glyph and the HUD's buds, no card (it greets a new connection only).
            airConnected(AirPods.connected(audio), false);
            audio.registerAudioDeviceCallback(airWatch, handler);
        }
        recs.refreshIfStale();
        Photos.importInbox(getContext(), ownReady);   // photos a computer just sent
        ImmichPhotos.syncIfDue(getContext(), ownReady);   // or a new choice from the Immich server, every 6 hours
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        handler.removeCallbacks(rotate);
        handler.postDelayed(rotate, ROTATE_MS);
        playing.start();
        warnTimeZone();
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
        // Back from an app or the screensaver: the opening tile's motion is over; the home settles in from 1.04
        // (SPATIAL_SCROLL, the app's window shrinking back into it).
        press.snap(0);
        if (launchUp && launchBack) {
            // already shrinking back (a burst of Home presses)
        } else if (launchUp && !Spring.instant) {
            returnLaunch();   // the app's screen shrinks back into its tile as the home comes forward
        } else if (!again && !Spring.instant) {
            endLaunch();
            launch.snap(0);
            wakeScale.tune(Spring.SPATIAL_SCROLL);
            wakeScale.snap(1.04f);
            wakeScale.target = 1;
            wakeAlpha.snap(0);
            wakeAlpha.target = 1;
        } else {
            endLaunch();
            launch.snap(0);
            wakeScale.snap(1);
            wakeAlpha.snap(1);
        }
        applyHome();
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
        welly.resume();
        if (tab == TAB_HOME) house.refresh();
        // The gate (§3): the intro and "Who's Watching?" only when the TV comes on (the screen on again, or a start
        // after it was off or restarted); Home and coming back from an app go straight to whoever is signed in. A new
        // home screen process (Android closes it under a big app) keeps them in; nobody signed in: the lock alone.
        if (replayIntro) {
            booted = true;
            introPending = replayIntro = false;
            gate(true, true);
        } else if (introPending) {
            introPending = false;
            booted = true;
            gate(true);
        } else if (!booted) {
            booted = true;
            if (!ProfileManager.resumeSignedIn(getContext())) gate(true);
        } else if (!lock.showing() && !intro.showing() && ProfileManager.needsLock(getContext())) {
            gate(false);
        }
        // Settings → Run Setup Again: the welcome setup now (on a new TV it comes after the intro instead).
        if (!Prefs.setupDone() && !intro.showing() && !welcome.showing() && !lock.showing()) welcome.show(false);
    }

    void pause() {
        AppLock.gate = null;
        if (!running) return;
        aerialPlayback.hide(++aerialGeneration);
        liveUrl = null;
        setLiveVideoVisible(false);
        running = false;
        welly.pause();
        cancelMove(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        volumeHud.dismiss();
        volumeKeyDown = false;
        if (audio != null) audio.unregisterAudioDeviceCallback(airWatch);
        handler.removeCallbacks(islandCheck);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(rotate);
        handler.removeCallbacks(reloadSoon);
        playing.stop();
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        intro.stop();
        try {
            if (launcherApps != null) launcherApps.unregisterCallback(packages);
        } catch (RuntimeException ignored) {
        }
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        weatherOverlay.hide();   // Home (and All Apps) leave the forecast and Recent Apps too
        recentsOverlay.hide();
        cancelMove(!animated);
        cancelLaunch();   // Home while an app opens: its screen shrinks back
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
            for (Spring sp : new Spring[] {tabAway, homeShown, wellyShown, lensX, lensW}) sp.snap(sp.target);
        }
        retarget(!animated);
        motionDirty = true;
    }

    /** Home pressed twice quickly (HomeActivity): Recent Apps, as tvOS's double press of the TV button. Never over the lock. */
    void showRecents() {
        if (lock.showing() || intro.showing()) return;
        reset(true);
        recentsOverlay.show();
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
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, tabBar, chevronLayer}) v.invalidate();
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
        // Move mode's cursor and carried tile, and an app's launch screen: full screen. Never measured or laid out
        // before, they were 0 × 0 on Android and drew nothing (the line, the outline, the small app, the zoom).
        measure(moveLayer, width, height);
        measure(launchLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        measure(tabBar, width, Math.round(160 * kk));
        measure(chevronLayer, Math.round(80 * kk), Math.round(48 * kk));
        measure(house, width, height);
        measure(welly, width, height);
        measure(weatherOverlay, width, height);
        measure(recentsOverlay, width, height);
        measure(volumeHud, Math.round(VolumeHud.VIEW_W * kk), Math.round(VolumeHud.VIEW_H * kk));
        measure(lock, width, height);
        measure(welcome, width, height);
        measure(intro, width, height);
        measure(dimmer, width, height);
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
        moveLayer.layout(0, 0, width, height);
        launchLayer.layout(0, 0, width, height);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        place(tabBar, 0, 0);
        place(chevronLayer, (width - chevronLayer.getMeasuredWidth()) / 2, Math.round(TV_CHEVRON_Y * kk) - chevronLayer.getMeasuredHeight() / 2);
        house.layout(0, 0, width, height);
        welly.layout(0, 0, width, height);
        weatherOverlay.layout(0, 0, width, height);
        recentsOverlay.layout(0, 0, width, height);
        place(volumeHud, 0, Math.round(VolumeHud.CENTER_Y * kk) - volumeHud.getMeasuredHeight() / 2);   // the middle of the left edge
        lock.layout(0, 0, width, height);
        appLock.layout(0, 0, width, height);
        welcome.layout(0, 0, width, height);
        intro.layout(0, 0, width, height);
        dimmer.layout(0, 0, width, height);
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
        Symbols.warm(getContext(), "airpods", 40 * k);
        Symbols.warm(getContext(), "airpods", 24 * k);
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
        horizonDay = Horizon.get(w, h, false);
        horizonNight = Horizon.get(w, h, true);
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
        islandTitlePaint = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);   // Apple: medium weight or heavier, large
        islandSubPaint = Ui.text(Ui.medium, 19 * k, 0xB3FFFFFF);
        for (Paint p : new Paint[] {islandTitlePaint, islandSubPaint}) p.setShadowLayer(8 * k, 0, 1 * k, 0x40000000);
        tabText = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
        tabText.setTextAlign(Paint.Align.CENTER);
        tabLayoutGeneration = -1;
        ccName = Ui.text(Ui.semibold, 21 * k, L_INK);
        ccName.setTextAlign(Paint.Align.CENTER);
        ccSmall = Ui.text(Ui.semibold, 15 * k, L_INK);
        ccSmall.setTextAlign(Paint.Align.CENTER);
        ccCell = Ui.text(Ui.semibold, 18 * k, L_INK);   // the profile panel's cells' names
        ccCell.setTextAlign(Paint.Align.CENTER);
        ccNum = Ui.numbers(Ui.semibold, 19 * k, L_INK);   // the sliders' levels, the weather's hours
        ccNum.setTextAlign(Paint.Align.CENTER);
        fullScrim = fullScrimBright = null;
        tvName = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
        ccTime = Ui.numbers(Ui.light, 57 * k, L_INK);
        ccTime.setLetterSpacing(-0.01f);
        ccDate = Ui.text(Ui.medium, 25 * k, L_DIM);
        ccLabel = Ui.text(Ui.semibold, 21 * k, L_INK);
        ccDetail = Ui.text(Ui.medium, 17 * k, L_DIM);
        ccTitle = Ui.text(Ui.displaySemibold, 34 * k, L_INK);
        ccTitle.setLetterSpacing(-0.01f);
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
        onPlaying();   // the line fits the new size; a title that came before the first layout bounces and is said now
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(1 / 30f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean swaying = swayX.step(dt) | swayY.step(dt);
        // Dead-end nudges and the OK press move whatever has the focus.
        boolean nudging = nudgeX.step(dt) | nudgeY.step(dt) | press.step(dt) | scrub.step(dt);
        bumpX = Spring.rubber(nudgeX.value) * k;
        bumpY = Spring.rubber(nudgeY.value) * k;
        if (nudging) {
            View v = focusLayer();
            if (v != null) v.invalidate();
        }
        boolean launching = stepLaunch(dt, up);   // the launch screen holds until the app comes, however long it takes
        boolean homeMoving = launch.step(dt) | wakeScale.step(dt) | wakeAlpha.step(dt);
        if (homeMoving) {
            applyHome();
            if (launchZone == LAUNCH_GRID) sheetLayer.invalidate();
            else if (launchZone >= 0) tiles.invalidate();
        }
        boolean lensMoving = lensX.step(dt) | lensW.step(dt);
        // The Top Shelf: the new title comes in 80 ms after the old one starts to leave.
        if (heroFadeAt != 0 && titleIn.target < 1 && up - heroFadeAt >= 80) titleIn.target = 1;
        boolean titleMoving = titleIn.step(dt) | dotAt.step(dt);
        boolean parallaxMoving = parallax.step(dt);
        boolean kenMoving = kenBurns(up);
        if (titleMoving) heroLayer.invalidate();
        if (kenMoving || parallaxMoving) sky.invalidate();
        boolean pillMoving = avatarFade.step(dt) | islandShown.step(dt) | islandOpen.step(dt) | islandWidth.step(dt)
                | islandSwap.step(dt);
        if (pillMoving) {
            status.invalidate();
            tabBar.invalidate();
        }
        boolean chevronMoving = chevronShown.step(dt) | breathe(up);
        // Control Center's rows come in one after another while it opens.
        boolean ccStagger = drawer.target > 0.5f && Ui.SMALL_MOTION && !Spring.instant
                && up - drawerOpenedAt < CC_ROW_MS * (drawerItems.size() / 2 + 3) + CC_ROW_IN_MS;
        if (ccStagger) drawerLayer.invalidate();
        boolean tilesMoving = step(tileFocus, dt) | step(tileSlide, dt) | (swaying && !sheetOpen) | glare.step(dt);
        boolean moveMoving = stepMove(dt, up) | stepNaming(dt);
        boolean statusMoving = step(statusFocus, dt) | pillFocus.step(dt) | bubbleFocus.step(dt);
        boolean fullMoving = full.step(dt);
        boolean heroMoving = heroFocus.step(dt) | fullMoving;
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt);
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt) | step(profileFocus, dt) | step(inputFocus, dt) | inputsShown.step(dt)
                | volumeFocus.step(dt)
                | weatherFocus.step(dt) | volumeLevel.step(dt) | brightLevel.step(dt) | ccScroll.step(dt);
        boolean scrollMoving = scroll.step(dt);
        boolean gridMoving = step(gridFocus, dt) | step(gridSlideX, dt) | step(gridSlideY, dt) | scrollMoving | (swaying && sheetOpen) | folderShown.step(dt) | step(folderFocus, dt) | folderScroll.step(dt)
                | badgeFocus.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        if (sheetMoving) {
            recordLift(up);
            if (Ui.SMALL_MOTION) sheetLayer.invalidate();   // the rows trail one another as the grid comes up
        } else {
            liftCount = 0;
        }
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);
        boolean tabMoving = tabAway.step(dt) | homeShown.step(dt) | wellyShown.step(dt);

        // With animations off the springs jump without reporting motion: the layers follow every frame then.
        if (drawerMoving || sheetMoving || scrollMoving || fullMoving || tabMoving || chevronMoving || motionDirty || Spring.instant) {
            updateMotion();
        }
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
            if (drawerOpen) drawerLayer.invalidate();   // the platter's time
        }

        // Busy: something is moving. At rest, one frame a second (the clock), or when the toast on screen ends.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving || scrollMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || tabMoving || lensMoving
                || nudging || homeMoving || titleMoving || parallaxMoving || kenMoving || pillMoving
                || chevronMoving || ccStagger || moveMoving || launching || !toasts.isEmpty();
        idle = !busy;
        if (busy) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            long next = 1000;
            if (toast != null) next = Math.max(16, Math.min(next, toastAt + TOAST_MS - 450 - up));
            Choreographer.getInstance().postFrameCallbackDelayed(this, next);
        }
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
        // Full screen: the Dock and the grid's peek recess (36 px down, to 0.75), never hidden.
        float drop = full.value * TV_FULL_DROP * k, away = 1 - (1 - TV_RECESS) * smoothstep(0, 0.7f, fs);
        // Control Center: the page behind it steps back (0.975) and dims 15% (the sky darkens a little too, drawLightSky).
        float dim = 1 - 0.15f * dc, back = 1 - 0.025f * dc;
        // The tabs (TAB_SWAP): the one leaving slides 60 px away and shrinks to 0.96 as it fades (quickly); the one
        // coming in arrives from 60 px on the other side. Moving right (TV → Home → Welly) the leaving one goes left.
        float tv = 1 - Ui.clamp(tabAway.value);
        // No shrink on LEGACY: a new scale draws every shape again, and a slide reads the same at this speed.
        float tvA = tabAlpha(tv, TAB_TV), tvX = tabX(tv, TAB_TV), tvS = (Ui.LEGACY ? 1 : 0.96f + 0.04f * tv) * back;
        show(heroLayer, top * dim * tvA, -rise, tvX, tvS);
        // The pill and the tab bar stay with Control Center (its panel starts under the pill, CC_TOP) and in full
        // screen; they leave with the title logo only when the app grid owns the screen.
        show(status, top, -sheet.value * TV_GRID_LIFT * k, 0, 1);
        show(tabBar, top, -sheet.value * TV_GRID_LIFT * k, 0, 1);
        show(platter, dim * away * tvA, -rise + drop, tvX, tvS);
        show(tiles, dim * away * tvA, -rise + drop, tvX, tvS);
        // The ⌃ points at the Top Shelf: in full screen we're there, so it goes.
        chevronAlpha = dim * (1 - smoothstep(0, 0.5f, fs)) * tvA * Ui.clamp(chevronShown.value);
        chevronY = -rise + drop;
        show(chevronLayer, chevronAlpha, chevronY + chevronBreath, tvX, 1);
        float hv = Ui.clamp(homeShown.value), wv = Ui.clamp(wellyShown.value);
        show(house, dim * tabAlpha(hv, TAB_HOME), 0, tabX(hv, TAB_HOME), (Ui.LEGACY ? 1 : 0.96f + 0.04f * hv) * back);
        show(welly, dim * tabAlpha(wv, TAB_WELLY), 0, tabX(wv, TAB_WELLY), (Ui.LEGACY ? 1 : 0.96f + 0.04f * wv) * back);
        welly.setImportantForAccessibility(wv > 0.002f ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
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

    /** A layer's opacity, position and scale (around the screen's centre), without redrawing it. */
    private void show(View v, float alpha, float translationY, float translationX, float scale) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setTranslationX(translationX);
        if (v.getScaleX() != scale) {
            v.setPivotX(w / 2f - v.getLeft());
            v.setPivotY(h / 2f - v.getTop());
            v.setScaleX(scale);
            v.setScaleY(scale);
        }
        v.setVisibility(alpha > 0.002f || (Ui.LEGACY && (v == house || v == welly)) ? VISIBLE : INVISIBLE);
    }

    /** A tab's content offset (px) when it's `shown` (0 to 1): the selected one comes in, the others go out. */
    private float tabX(float shown, int index) {
        float off = (1 - shown) * TAB_SLIDE * k * tabDir;
        return index == tab ? off : -off;
    }

    /** A tab's content opacity: coming in, as shown; going out, faster (gone in ~220 ms of the swap). */
    private float tabAlpha(float shown, int index) {
        return index == tab ? shown : Ui.clamp((shown - 0.45f) / 0.55f);
    }

    /** The whole home screen: settling in when it comes back, receding behind an app opening. */
    private void applyHome() {
        float l = Ui.clamp(launch.value), sc = wakeScale.value * (1 - 0.05f * l);
        setScaleX(sc);
        setScaleY(sc);
        // Fading the whole screen takes an offscreen pass: Android 8 to 11's GPUs only get the scale.
        setAlpha(Ui.LEGACY ? 1 : Ui.clamp(wakeAlpha.value) * (1 - 0.5f * l));
    }

    /** The layer drawing what has the focus (to redraw it while it nudges or presses). */
    private View focusLayer() {
        if (sheetOpen) return sheetLayer;
        switch (zone) {
            case Z_SHELF: return tiles;
            case Z_HERO: return heroLayer;
            case Z_STATUS: return status;
            case Z_TOP: return topIndex < 3 ? tabBar : status;
            case Z_DRAWER:
            case WIDGETS: return drawerLayer;
            default: return null;
        }
    }

    /** No neighbour that way: the focused thing tugs toward it through the rubber band, with the boundary knock. */
    private void deadEnd(int dx, int dy) {
        nudgeX.impulse(dx * Spring.BUMP_VELOCITY);
        nudgeY.impulse(dy * Spring.BUMP_VELOCITY);
        click.bump();
        wake();
    }

    /** An arrow held down (key repeat) or let go: the focus keeps up without tilt, sheen or names. */
    private void setScrubbing(boolean on) {
        if (on == scrubbing) return;
        scrubbing = on;
        scrub.target = on ? 1 : 0;
        float[] preset = on ? Spring.FAST_FOCUS : Spring.SNAPPY_FOCUS;
        for (Spring[] group : new Spring[][] {tileFocus, gridFocus, topFocus, drawerFocus, profileFocus}) {
            for (Spring f : group) f.tune(preset);
        }
        pillFocus.tune(preset);
        bubbleFocus.tune(preset);
        wake();
    }


    /** Ken Burns on the Top Shelf's photo: true while it moves (only there, and not on Android 8 to 11). */
    private boolean kenBurns(long up) {
        return tab == TAB_TV && !sheetOpen && !Spring.instant && !Ui.LEGACY && bgTo != null && bgTo.image != null
                && kenAt != 0 && up - kenAt < KEN_MS;
    }

    /** The ⌃ breathes (±2.5 px over 3.2 s) while the Dock has the focus and a pick shows. */
    private float chevronBreath;

    private boolean breathe(long up) {
        chevronShown.target = hero != null && tab == TAB_TV ? 1 : 0;
        boolean on = chevronShown.value > 0.01f && zone == Z_SHELF && !sheetOpen && !Spring.instant && !Ui.LEGACY;
        float b = on ? (float) Math.sin(up * 2 * Math.PI / 3200) * 2.5f * k : 0;
        if (b == chevronBreath && !on) return false;
        chevronBreath = b;
        chevronLayer.setTranslationY(chevronY + b);
        return on;
    }

    /** Keeps the grid's lift over the last frames (for the rows' lag). */
    private void recordLift(long up) {
        liftHead = (liftHead + 1) % liftAt.length;
        liftAt[liftHead] = up;
        liftValue[liftHead] = sheet.value;
        liftCount = Math.min(liftCount + 1, liftAt.length);
    }

    /** The grid's lift at time `t` (the oldest kept if t is older). */
    private float liftAgo(long t) {
        float v = sheet.value;
        for (int i = 0; i < liftCount; i++) {
            int at = (liftHead - i + liftAt.length) % liftAt.length;
            v = liftValue[at];
            if (liftAt[at] <= t) break;
        }
        return v;
    }

    private void updateSleep() {
        int left = SleepTimer.minutesLeft(getContext());   // only the dynamic dot shows it now (no banner over the apps)
        String text = left == 0 ? "" : Ui.trf("Off in {time}", "time", SleepTimer.duration(left));
        sleepDown = left > 0 && sleepStep(left, -1) > 0;
        sleepUp = left > 0 && sleepStep(left, 1) > 0;
        if (text.equals(sleepText)) return;
        sleepText = text;   // the sleep timer's live activity (the dynamic dot) shows it
        updateIsland();
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        if (cardDim != null) {   // Control Center's platter: the date cut to its first column
            platterDate = TextUtils.ellipsize(dateText, new TextPaint(cardDim), (CC_CARD_W - 34) * k, TextUtils.TruncateAt.END).toString();
        }
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
        if (backgroundMode != BACKGROUND_MEDIA) return null;
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
        // Their photos ready ahead only when the Top Shelf shows them; otherwise each app has its own titles.
        if (Prefs.topShelf().equals("picks")) for (Recs.Pick p : list) recs.backdrop(p);
        for (HomeApps.Item it : shelf) if (Wallpaper.videoApp(it.pkg)) recs.forApp(it.pkg);   // old or missing lists come in the background
        syncBackgroundFocus(false);
        preloadShelf();
        Recs.Pick shown = currentPick();
        if (shown != null && recs.backdrop(shown) != bgTo) updateBackground();   // its photo has just been decoded
        refreshHero(false);
        spoken.focusMoved();
    }

    /**
     * The focused Dock app's picks (tvOS shows the focused app's Top Shelf): when they change, the Top Shelf shows its
     * first one, or the wallpaper when it has none.
     */
    private void followShelf(boolean animate) { syncBackgroundFocus(animate); }

    /**
     * Android has no implicit "current app art": only a video app focused in TV's Shelf/Hero may own it.
     * Tabs, the app grid, Settings and music all return to the profile background immediately.
     */
    private void syncBackgroundFocus(boolean animate) {
        // The Top Shelf (Settings, or the welcome setup): "app" the focused video app's Continue Watching (the
        // default), "all" a Continue Watching title whatever is focused (the Dock's first video app's when it isn't
        // one), "picks" the recommendations whatever is focused, "off" only the wallpaper.
        String mode = Prefs.topShelf();
        String pkg = shelf.isEmpty() || shelfIndex >= shelf.size() ? null : shelf.get(shelfIndex).pkg;
        if (mode.equals("all") && !Wallpaper.videoApp(pkg)) pkg = firstVideoApp();
        String where = zone == Z_HERO ? "hero" : zone == Z_SHELF ? "shelf" : "";
        boolean onTv = tab == TAB_TV && !sheetOpen;
        boolean media = !mode.equals("off") && onTv
                && Wallpaper.media(where, mode.equals("picks") ? !picks.isEmpty() : Wallpaper.videoApp(pkg));
        if (mode.equals("picks")) pkg = PICKS_KEY;
        if (media && backgroundMode == BACKGROUND_MEDIA && pkg.equals(backgroundPackage)) return;
        if (!media && backgroundMode == BACKGROUND_PROFILE) return;
        final String profileId = ProfileManager.current(getContext()).id;
        final long generation = backgroundGeneration = backgroundFocus.start(profileId);
        backgroundPackage = media ? pkg : null;
        appPicks = new ArrayList<>();
        pickIndex = 0;
        heroSlide = 1;
        if (!media) {
            backgroundMode = BACKGROUND_PROFILE;
            if (zone == Z_HERO) zone = Z_SHELF;
            clearHeroForProfile();
            showProfileBackground(generation);
            return;
        }
        backgroundMode = BACKGROUND_MEDIA;
        heroVisibility.media();
        if (PICKS_KEY.equals(pkg)) {   // Recommendations & News: this hour's picks, whatever app is focused
            appPicks = new ArrayList<>(picks);
            refreshHero(animate);
            updateBackground();
            handler.removeCallbacks(rotate);
            handler.postDelayed(rotate, ROTATE_MS);
            return;
        }
        refreshHero(animate);       // no stale title while the profile log resolves
        final String watchPkg = pkg;
        recs.resolveWatch(watchPkg, generation, (callbackGeneration, watched) -> {
            if (!backgroundFocus.current(callbackGeneration, profileId)
                    || !Wallpaper.current(callbackGeneration, backgroundGeneration)
                    || backgroundMode != BACKGROUND_MEDIA || !watchPkg.equals(backgroundPackage)) return;
            // What they last opened there, this hour's picks on it, then the app's own titles.
            List<Recs.Pick> mediaPicks = new ArrayList<>();
            java.util.Set<String> seenPicks = new java.util.HashSet<>();
            if (watched != null && seenPicks.add(Recs.key(watched))) mediaPicks.add(watched);
            for (Recs.Pick p : picks) if (watchPkg.equals(p.pkg) && seenPicks.add(Recs.key(p))) mediaPicks.add(p);
            for (Recs.Pick p : recs.forApp(watchPkg)) {
                if (mediaPicks.size() < Recs.APP_PAGES && seenPicks.add(Recs.key(p))) mediaPicks.add(p);
            }
            if (mediaPicks.isEmpty()) {
                backgroundMode = BACKGROUND_PROFILE;
                backgroundPackage = null;
                if (zone == Z_HERO) zone = Z_SHELF;
                clearHeroForProfile();
                showProfileBackground(callbackGeneration);
                return;
            }
            appPicks = mediaPicks;
            pickIndex = 0;
            refreshHero(animate);
            updateBackground();
            preloadShelf();
            handler.removeCallbacks(rotate);
            handler.postDelayed(rotate, ROTATE_MS);
        });
    }

    /** The next page's photo and the neighbouring Dock apps' first ones, decoded ahead: moving along the Dock is instant. */
    private void preloadShelf() {
        if (appPicks.size() > 1) recs.backdrop(appPicks.get((pickIndex + 1) % appPicks.size()));
        for (int d = -1; d <= 1; d += 2) {
            int i = shelfIndex + d;
            if (i < 0 || i >= shelf.size() || !Wallpaper.videoApp(shelf.get(i).pkg)) continue;
            List<Recs.Pick> titles = recs.forApp(shelf.get(i).pkg);
            if (!titles.isEmpty()) recs.backdrop(titles.get(0));
        }
    }

    /** backgroundPackage while the Top Shelf shows Recommendations & News (no app owns it). */
    private static final String PICKS_KEY = "picks:";

    /** The Dock's first video app (All Continue Watching shows its title on any other Dock app), or null. */
    private String firstVideoApp() {
        for (HomeApps.Item it : shelf) if (Wallpaper.videoApp(it.pkg)) return it.pkg;
        return null;
    }

    /** Profile entry is a state boundary, not a hero fade: clear every text/dot source before the next draw. */
    private void clearHeroForProfile() {
        heroVisibility.profile();
        hero = null;
        heroFrom = null;
        heroFadeAt = 0;
        titleIn.snap(1);
        dotAt.snap(0);
        dotAt.target = 0;
        heroFocus.snap(0);
        heroFocus.target = 0;
        full.snap(0);
        full.target = 0;
        heroButton.setEmpty();
        heroDirty = tilesDirty = true;
    }

    /** A profile switch is a hard ownership boundary, even when both people focus the same streaming package. */
    private void invalidateProfileBackground() {
        String profileId = ProfileManager.current(getContext()).id;
        backgroundGeneration = backgroundFocus.start(profileId);
        backgroundMode = BACKGROUND_PROFILE;
        backgroundPackage = null;
        appPicks = new ArrayList<>();
        pickIndex = 0;
        ++aerialGeneration;
        liveUrl = null;
        aerialPlayback.hide(aerialGeneration);
        setLiveVideoVisible(false);
        clearHeroForProfile();
        scene.setHorizon(Wallpaper.dark(Prefs.wallpaper(), data.isNight(System.currentTimeMillis())));
        // Do not cross-fade an outgoing person's art into the new profile's first frame.
        bgFrom = null;
        bgTo = null;
        bgFadeAt = 0;
        nightPair = false;
        neutralGlass();
    }

    /**
     * The background (DESIGN-TVOS.md §5): the shown pick's photo; without one, your photo (Photos), else this hour's
     * aerial. One still loading keeps the current background until it's ready (Recs and the loader call back).
     */
    private void updateBackground() {
        long generation = ++aerialGeneration;
        setLiveVideoVisible(false);
        Recs.Pick p = currentPick();
        Backdrop b;
        if (p != null) {
            scene.setAurora();
            clearOfficialStill();
            aerialPlayback.hide(generation);
            b = recs.backdrop(p);
        } else {
            showProfileBackground(generation);
            return;
        }
        // Not decoded yet: what's there stays until it is (updatePicks), rather than a flash of the wallpaper in between.
        if (b != null) showBackdrop(b);
        else if (bgTo == null) showProfileBackground(generation);
    }

    /** The default profile surface is Frosted Horizon; aerial/video/photos are opt-in wallpaper choices only. */
    private void showProfileBackground(long generation) {
        setLiveVideoVisible(false);
        clearOfficialStill();
        String choice = Prefs.wallpaper();
        if (Photos.on(getContext()) && "photos".equals(choice)) {
            aerialPlayback.hide(generation);
            Backdrop photo = Photos.backdrop(getContext(), ownReady);
            if (photo != null) { scene.setAurora(); showBackdrop(photo); return; }
        }
        if ("live:aerial".equals(choice)) {
            scene.setAurora();
            updateAerialVideo(generation);
            showBackdrop(null);
            return;
        }
        Wallpaper.Aerial aerial = Wallpaper.find(choice);
        if (aerial != null && Wallpaper.allowed(aerial.preview)) {
            scene.setAurora();
            aerialPlayback.hide(generation);
            Backdrop still = officialStill(aerial.preview);
            if (still != null) { showBackdrop(still); return; }
        } else aerialPlayback.hide(generation);
        scene.setHorizon(Wallpaper.dark(choice, data.isNight(System.currentTimeMillis())));
        showBackdrop(null);
        neutralGlass();
    }

    private void updateAerialVideo(long generation) {
        if (!"live:aerial".equals(Prefs.wallpaper())) { aerialPlayback.hide(generation); return; }
        Wallpaper.Aerial aerial = Wallpaper.liveAerial(System.currentTimeMillis() / 86_400_000L);
        liveUrl = aerial == null ? null : aerial.video;
        if (liveUrl == null || liveUrl.isEmpty()) aerialPlayback.hide(generation); else aerialPlayback.show(generation, liveUrl);
    }

    /** Apple preview frames use the same validated cache as video; an unavailable frame leaves Frosted Horizon visible. */
    private Backdrop officialStill(String url) {
        if (!url.equals(officialStill)) { officialStill = url; officialStillLoaded = null; wallpaper = null; }
        if (url.equals(officialStillLoaded)) return wallpaper;
        File cached = aerialCache.get(url);
        if (cached != null) loadOfficialStill(cached, url);
        else aerialCache.fetch(url, file -> { if (file != null) loadOfficialStill(file, url); else handler.post(() -> failOfficialStill(url)); });
        return wallpaper;
    }

    private void clearOfficialStill() { officialStill = null; officialStillLoaded = null; }

    private void loadOfficialStill(File file, String url) {
        if (wallpaperLoading) return;
        wallpaperLoading = true;
        LOADER.execute(() -> {
            Backdrop built = null;
            try { Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath()); if (bitmap != null) built = Backdrop.make(bitmap, false); }
            catch (Exception | OutOfMemoryError e) { AppLog.w("aerial preview: " + e.getMessage()); }
            final Backdrop ready = built;
            handler.post(() -> {
                wallpaperLoading = false;
                if (ready == null) { failOfficialStill(url); return; }
                if (!url.equals(officialStill)) return;
                wallpaper = ready;
                officialStillLoaded = url;
                if (running) updateBackground();
            });
        });
    }

    void aerialFailed(long generation, String url) {
        if (generation != aerialGeneration || url == null || !url.equals(liveUrl)) return;
        liveUrl = null;
        setLiveVideoVisible(false);
        // No video: the Frosted Horizon for good (until the wallpaper changes), not the empty aurora's fallback.
        scene.setHorizon(Wallpaper.dark(Prefs.wallpaper(), data.isNight(System.currentTimeMillis())));
        showBackdrop(null);
        neutralGlass();
    }

    void aerialReady(long generation, String url) {
        if (generation != aerialGeneration || url == null || !url.equals(liveUrl)) return;
        setLiveVideoVisible(true);
    }

    private void setLiveVideoVisible(boolean visible) { if (liveVideoVisible != visible) { liveVideoVisible = visible; sky.invalidate(); } }

    private void failOfficialStill(String url) {
        if (!url.equals(officialStill)) return;
        wallpaper = null;
        officialStillLoaded = null;
        showBackdrop(null);
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
        if (animate && other && !Spring.instant) {
            // The old title leaves (−14 px, 220 ms); the new one settles in from +22 px 80 ms later (TITLE_SETTLE).
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
            titleIn.snap(0);
        } else if (heroFadeAt == 0) {
            titleIn.snap(1);
        }
        if (next.pick != null && (hero == null || hero.dots != next.dots)) dotAt.snap(next.dot);
        dotAt.target = next.dot;
        hero = next.pick == null ? null : next;
        heroVisibility.track(hero != null, heroFrom != null, heroFadeAt != 0);
        heroDirty = tilesDirty = true;   // the tiles layer holds the chevron
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) return h;
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
        preloadShelf();
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
        if (b != null && b.image != null) {   // a new photo: its slow Ken Burns starts over, the other way
            kenAt = up;
            kenDir = -kenDir;
        }
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null || Spring.instant) {
            showFrost(frostOf(b));
            showGlass(glassOf(b));
        } else {
            // Both images once; each step of the fade only moves the mix (tickBackgroundFade).
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
            scene.setFrostPair(frostOf(bgFrom), frostOf(b));
            frostShown = glassShown = null;
        }
    }

    private void showFrost(int[] frost) {
        if (frost == frostShown && frost != frostNight) return;
        frostShown = frost;
        scene.setFrost(frost);
    }

    private void showGlass(int[] glass) {
        if (glass == glassShown && glass != glassNight) return;
        glassShown = glass;
        scene.setGlassImage(glass);
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
        if (neutralFrostPx[0] == color && frostShown == neutralFrostPx && glassShown == neutralGlassPx) return;
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        frostShown = neutralFrostPx;
        glassShown = neutralGlassPx;
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
        Scene.frosted = "frosted".equals(Prefs.glass());
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            horizonFollowsNight();
            nightChanged();
        }
    }

    /** The Frosted Horizon on screen takes the night's colours (or stays, when its day or night look was the one picked). */
    private void horizonFollowsNight() {
        if (backgroundMode == BACKGROUND_PROFILE && scene.isHorizon()) scene.setHorizon(Wallpaper.dark(Prefs.wallpaper(), nightTarget));
    }

    /** Day to night is under way behind a Frosted Horizon that follows it: the two looks crossfade (drawLightSky). */
    private boolean horizonFading() {
        String picked = Prefs.wallpaper();
        return nightAt != 0 && Wallpaper.dark(picked, true) != Wallpaper.dark(picked, false);
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Spring.instant ? 1 : Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
            horizonFollowsNight();
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && glassOf(bgTo) != null) {
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {   // both looks once; each step only moves the mix
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    if (bgTo.nightFrost != null) scene.setFrostPair(bgTo.frost, bgTo.nightFrost);
                    else scene.setFrost(bgTo.frost);
                    frostShown = glassShown = null;
                    nightPair = true;
                }
                scene.setGlassMix(night);
                scene.setFrostMix(night);
            } else {
                showFrost(frostOf(bgTo));
                showGlass(glassOf(bgTo));
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
            scene.setFrostMix(e);
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                showFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) showGlass(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (Spring.instant || (up - heroFadeAt >= 220 && titleIn.target >= 1 && titleIn.settled())) {
            heroFadeAt = 0;
            heroFrom = null;
            heroVisibility.track(hero != null, false, false);
            titleIn.snap(1);
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        if (Spring.instant) return 1;   // animations off: every fade is already over
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
    /** The tint Home and Welly lay over the Frosted Horizon. */
    private static final int SLATE = 0xFF16212D;

    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        // 64 levels (a step is invisible on a moving fade), each made once and kept: no filter made per frame.
        int level = Math.round(amount * 64);
        amount = level / 64f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        long key = ((long) level << 32) | (color & 0xFFFFFFFFL);
        android.graphics.ColorFilter cached = veils.get(key);
        if (cached != null) return veilFilter = cached;
        float keep = 1 - amount;
        veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
        if (veils.size() > 512) veils.clear();
        veils.put(key, veilFilter);
        return veilFilter;
    }

    private final android.util.LongSparseArray<android.graphics.ColorFilter> veils = new android.util.LongSparseArray<>();

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
                statusFocus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
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
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = dockKey(list.get(i)).equals(dockKey(shelf.get(i)));
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(scrubbing ? Spring.FAST_FOCUS : Spring.SNAPPY_FOCUS, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0).px();
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
        placeShelf();
        emblems.clear();
        darkEmblems.clear();
    }

    /** The Dock's glass and tiles (without dropping the logos' cache: moving an app only changes their places). */
    private void placeShelf() {
        int n = Math.min(shelf.size(), shelfVisible), slots = Math.min(Math.max(n, dockSlots), shelfVisible);
        float padX = TV_DOCK_PAD_X * k, pad = TV_DOCK_PAD * k, gap = TV_TILE_GAP * k, tw = TV_TILE_W * k, th = TV_TILE_H * k;
        // The Dock: up to 6 tiles in tvOS's 5:3, centred (x 85–1835 with 6); with more apps, it scrolls. While an app
        // moves it keeps its width (dockSlots), its tiles centred in it.
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float glass = slots * tw + Math.max(0, slots - 1) * gap, glassLeft = (w - glass) / 2f;
        float top = TV_DOCK_TOP * k;
        platterRect.set(glassLeft - padX, top, glassLeft + glass + padX, top + th + 2 * pad);
        snap(platterRect);
        shelfFirst = Math.max(0, Math.min(shelfFirst, shelf.size() - n));
        float x = left - shelfFirst * (tw + gap), y = top + pad;
        for (int i = 0; i < shelf.size(); i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            snap(tileRects[i]);
            x += tw + gap;
        }
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
        wake();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty() && dockSlots == 0) return;
        float radius = TV_DOCK_RADIUS * k;   // concentric with the tiles (24 + 40)
        tmp.set(platterRect);
        tmp.offset(0, 16 * k);
        shadow.setColor(tone(0x33000000, 0x66000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // Liquid Glass (DESIGN-TVOS.md §2): thin, so the photo's colors bleed through; white by day, smoked at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.17f, 0.13f), tone(0.02f, 0.035f),
                44 * k, 34 * k, tone(0.85f, 0.95f), 1);
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

    private String shownTitle;   // kept while the screen is hidden: showing it again with the same title is no change

    /** What's playing changed (or stopped). */
    private void onPlaying() {
        fitPlaying();
        heroDirty = statusDirty = true;
        fitIsland();
        updateIsland();
        refreshMedia();
        wake();
        String title = playing.title, now = playing.text;
        if (NowPlaying.newTitle(title, now, running, shownTitle)) {
            shownTitle = title;
            if (readerOn()) announceForAccessibility(Ui.trf("Now playing: {what}", "what", now.replace(" · ", ", ")));
        }
    }

    /** The song changed: its art for the dot (a shader made here, never while drawing). */
    private void fitIsland() {
        Bitmap art = playing.mediaArt;
        if (art != islandArt) {
            islandArt = art;
            islandArtShader = art == null ? null : new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
    }

    /** Focus: whether Do Not Disturb is on (anything but "all" lets less through). */
    private void readDnd() {
        android.app.NotificationManager nm = (android.app.NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        boolean on = nm != null && nm.getCurrentInterruptionFilter() > android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
        if (on == dndOn) return;
        dndOn = on;
        updateIsland();
    }

    /**
     * Which live activities run, and their words:
     * - what plays: while it plays, ISLAND_PAUSE_MS after it pauses, and while focused; gone at once when the player goes;
     * - Focus: while Do Not Disturb is on;
     * - AirPods: while connected (joining opens the dot by itself for AIR_MS, the alert);
     * - the sleep timer: while it runs (and SleepOverlay isn't showing it).
     * The dot comes when the first starts and goes with the last; one that stops under the focus hands it to the next.
     */
    private void updateIsland() {
        long now = SystemClock.uptimeMillis();
        boolean has = playing.hasMedia(), playingNow = has && playing.mediaPlaying;
        if (islandWasPlaying && !playingNow && has) {
            islandPausedUntil = now + ISLAND_PAUSE_MS;
            handler.postDelayed(islandCheck, ISLAND_PAUSE_MS + 50);
        }
        islandWasPlaying = playingNow;
        Live music = lives[LIVE_MUSIC], focus = lives[LIVE_FOCUS], air = lives[LIVE_AIR], sleep = lives[LIVE_SLEEP];
        music.icon = videoApp(playing.mediaPkg) ? "tv" : "music";
        music.title = playing.mediaTitle == null ? "" : playing.mediaTitle;
        music.sub = playing.mediaDetail == null ? "" : playing.mediaDetail;
        focus.title = Ui.tr("Do Not Disturb");
        focus.sub = Ui.tr("state|On");
        air.title = airName == null ? "" : airName;
        air.sub = airSpatial ? Ui.tr("Connected · Spatial Audio") : Ui.tr("Connected");
        sleep.title = Ui.tr("Sleep Timer");
        sleep.sub = sleepText;
        boolean was = anyLive();
        music.on = has && (playingNow || now < islandPausedUntil || focusedLive(LIVE_MUSIC));
        focus.on = dndOn;
        air.on = airName != null;
        sleep.on = !sleepText.isEmpty();
        boolean any = anyLive();
        islandShown.target = any ? 1 : 0;
        if (Spring.instant) islandShown.snap(islandShown.target);
        if (onIsland && !lives[islandAt].on) {
            int n = nextLive(islandAt, 1);
            if (n < 0) n = nextLive(islandAt, -1);
            if (n >= 0) {
                islandAt = n;
                islandCtrl = 1;
            } else {
                onIsland = false;
                onBubble = true;   // the last one stopped under the focus: the bubble
            }
            if (zone == Z_STATUS) retarget(false);
        }
        if (tvPill != null) {
            if (any != was) layoutPill(); else layoutIsland();
        }
        aimIsland(false);
        if (tabBar != null) tabBar.invalidate();
        wake();
    }

    /**
     * A video app that keeps playing behind the home screen (picture in picture, a live channel): the TV for its symbol.
     * Video apps usually pause when they're left (Android TV pauses them), so they rarely show; music apps do.
     */
    private static boolean videoApp(String pkg) {
        if (pkg == null) return false;
        if (pkg.startsWith("com.apple.atve")) return true;
        switch (pkg) {
            case "com.netflix.ninja": case "com.google.android.youtube.tv": case "com.google.android.youtube.tvunplugged":
            case "com.disney.disneyplus": case "com.amazon.amazonvideo.livingroom": case "com.hulu.livingroomplus":
            case "com.wbd.stream": case "tv.twitch.android.app": case "com.cbs.ott": case "com.peacocktv.peacockandroid":
            case "com.tubitv": case "com.crunchyroll.crunchyroid": case "com.google.android.tv":
                return true;
            default:
                return false;
        }
    }

    private boolean focusedLive(int i) {
        return zone == Z_STATUS && onIsland && islandAt == i;
    }

    /**
     * What the dot shows (the focused activity, else AirPods' alert, else the first that runs), whether it's open, and
     * how wide: a new one steps in (from below when it's further down the list, from above when it's further up).
     */
    private void aimIsland(boolean snap) {
        long now = SystemClock.uptimeMillis();
        boolean focused = !sheetOpen && zone == Z_STATUS && onIsland, alert = lives[LIVE_AIR].on && now < airAlertUntil;
        int shows = focused ? islandAt : alert ? LIVE_AIR : nextLive(-1, 1);
        if (shows >= 0 && shows != islandShows) {
            if (islandShows >= 0 && islandShown.value > 0.5f && !snap && !Spring.instant) {
                islandFrom = islandShows;
                islandDir = shows > islandShows ? 1 : -1;
                islandSwap.snap(0);
                islandSwap.target = 1;
            }
            islandShows = shows;
        }
        float open = shows >= 0 && (focused || alert) ? 1 : 0;
        if (open != islandOpen.target) {
            islandOpen.tune(open > 0 ? ISLAND_OPEN : ISLAND_CLOSE);   // closing is brisker than opening
            islandOpen.target = open;
        }
        if (islandShows >= 0) islandWidth.target = lives[islandShows].w;
        if (snap || islandWidth.value == 0) islandWidth.snap(islandWidth.target);
        if (snap) islandOpen.snap(open);
    }

    private boolean anyLive() {
        return nextLive(-1, 1) >= 0;
    }

    /** The next running activity from `from` going `dir` (±1), or −1. */
    private int nextLive(int from, int dir) {
        for (int i = from + dir; i >= 0 && i < lives.length; i += dir) if (lives[i].on) return i;
        return -1;
    }

    /** The room the dot takes beside the bar now (px): its circle while it comes or goes, its capsule while open. */
    private float islandRoomNow() {
        float d = ISLAND_D * k;
        return Ui.clamp(islandShown.value) * ((ISLAND_D + ISLAND_SPACE) * k + Math.max(0, islandOpen.value) * (islandWidth.value - d));
    }

    /**
     * How far the tab bar (and the dot beside it) is drawn from its centred place (px): the pair stays centred as the
     * dot comes, goes, opens and closes, but never comes nearer than ISLAND_GAP to the buttons at the right, nor past
     * ISLAND_LEFT (the Top Shelf's logo) at the left.
     */
    private float tabShift() {
        if (tabBarRect.isEmpty()) return 0;
        float room = islandRoomNow();
        float shift = Math.min(-room / 2, islandClusterLeft - ISLAND_GAP * k - tabBarRect.right - room);
        return Math.max(shift, Math.min(0, ISLAND_LEFT * k - tabBarRect.left));
    }

    /** OK on the open dot: the activity's one control. */
    private void islandOk() {
        if (islandAt == LIVE_MUSIC && islandCtrl == 3) {
            // Music Glow on or off (the light around the picture, GlowRing): it fades in or breathes out at once.
            boolean on = !Prefs.glowOn();
            click.toggle(on);
            Prefs.setGlow(on, Prefs.glowStyle());
            glowShown = on;
            announceForAccessibility(Ui.tr("Music Glow") + ", " + (on ? Ui.tr("On") : Ui.tr("Off")));
            tabBar.invalidate();
        } else if (islandAt == LIVE_MUSIC && islandCtrl != 1) {
            int dx = islandCtrl == 0 ? -1 : 1;
            if (playing.skip(dx)) click.open(); else deadEnd(dx, 0);   // a player that can't: the knock
        } else if (islandAt == LIVE_MUSIC) {
            click.toggle(!playing.mediaPlaying);
            playing.playPause();
        } else if (islandAt == LIVE_FOCUS) {
            click.toggle(false);
            toggleDnd();   // Do Not Disturb off (Control Center turns it back on)
        } else if (islandAt == LIVE_SLEEP && islandCtrl == 2) {
            click.toggle(false);
            setSleep(0);   // ×: the timer off, and with it this activity
        } else if (islandAt == LIVE_SLEEP) {
            // − and +: the presets only (15 min, 30 min, 1 h, 2 h), the one below or above the time left; none: the knock.
            int dir = islandCtrl == 0 ? -1 : 1, to = sleepStep(SleepTimer.minutesLeft(getContext()), dir);
            if (to > 0) {
                click.open();
                setSleep(to);
            } else {
                deadEnd(dir, 0);
            }
        } else {
            click.open();   // AirPods: Control Center, with Listen Alone and the volume
            openDrawer(false);
            retarget(false);
        }
    }

    /** Music Glow's state as its switch in the dot shows it (read as the focus arrives and when it's flipped). */
    private boolean glowShown = true;

    /**
     * Music Glow's switch in the dot: a little screen with light around it. On: the frame glows. Off: dim, struck
     * through. `color` already carries the focus's ink and the fade.
     */
    private void drawGlowSwitch(Canvas c, float x, float y, int color, boolean on) {
        float g = k, hw = 11 * g, hh = 7.5f * g;
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeCap(Paint.Cap.ROUND);
        if (on) {   // the glow: a soft wider frame behind
            fill.setStrokeWidth(5 * g);
            fill.setColor(Ui.alpha(color, 0.3f));
            c.drawRoundRect(x - hw, y - hh, x + hw, y + hh, 3.5f * g, 3.5f * g, fill);
        }
        fill.setStrokeWidth(2 * g);
        fill.setColor(on ? color : Ui.alpha(color, 0.55f));
        c.drawRoundRect(x - hw, y - hh, x + hw, y + hh, 3 * g, 3 * g, fill);
        if (!on) c.drawLine(x - hw - 2 * g, y + hh + 2 * g, x + hw + 2 * g, y - hh - 2 * g, fill);
        fill.setStyle(Paint.Style.FILL);
        fill.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Do Not Disturb on or off; its access is allowed once through mahitTV's ADB (Original takes it back). */
    private void toggleDnd() {
        final Context ctx = getContext().getApplicationContext();
        LOADER.execute(() -> {
            android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean ok = nm != null && (nm.isNotificationPolicyAccessGranted() || HomeMode.allowDnd(ctx));
            if (ok) {
                try {
                    boolean on = nm.getCurrentInterruptionFilter() > android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
                    nm.setInterruptionFilter(on ? android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                            : android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                } catch (RuntimeException e) {
                    AppLog.w("Do Not Disturb: " + e.getMessage());
                    ok = false;
                }
            }
            final boolean done = ok;
            handler.post(() -> {
                if (!done) showToast(Ui.tr("Do Not Disturb needs mahitTV mode."));
                readDrawerState(false);   // the card's new state
                readDnd();
            });
        });
    }

    /** The preset below (dir −1) or above (+1) `left` minutes, or 0 where there's none. */
    private static int sleepStep(int left, int dir) {
        if (dir > 0) {
            for (int m : SLEEP_PRESETS) if (m > left + 1) return m;
            return 0;
        }
        int to = 0;
        for (int m : SLEEP_PRESETS) if (m < left - 1) to = m;
        return to;
    }

    private int nextSleep() {
        int left = SleepTimer.minutesLeft(getContext()), next = 0;
        for (int m : SLEEP_PRESETS) if (m > left + 1) { next = m; break; }
        return next;
    }

    private void setSleep(int minutes) {
        SleepTimer.set(getContext(), minutes);
        sleepOn = minutes > 0;
        showAppearance();
        layoutDetails();
        updateSleep();
        drawerDirty = true;
        wake();
    }

    /** Control Center open: its playback card comes or goes, or its text changes, with the focus staying on the same card. */
    private void refreshMedia() {
        if (!drawerOpen || allDrawer.isEmpty()) return;
        if ((kindIndex(HomeApps.MEDIA) >= 0) == playing.hasMedia()) {
            layoutDrawer();
            return;
        }
        String focused = drawerIndex < drawerItems.size() ? drawerItems.get(drawerIndex).key : null;
        setDrawerItems(allDrawer);
        for (int i = 0; i < drawerItems.size(); i++) if (drawerItems.get(i).key.equals(focused)) drawerIndex = i;
        retarget(true);
    }

    private static boolean zoneWarned;

    /** The TV's time zone can't be the city's: the clock is off, and only the TV's settings can change it. Once a run. */
    private void warnTimeZone() {
        if (zoneWarned) return;
        zoneWarned = true;
        int offset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000;
        if (!TimeCheck.zoneLooksWrong(offset, Prefs.longitude())) return;
        AppLog.w("time zone " + java.util.TimeZone.getDefault().getID() + " doesn't fit the city");
        handler.postDelayed(() -> showToast(Ui.tr("The TV's clock looks set to the wrong time zone. Fix it in Home Settings › Location & Media.")), 8000);
    }

    /**
     * A screen reader is on (TalkBack, or another service that speaks): only then is a new title announced. Other
     * accessibility services (a button mapper, a sleep timer) would get the announcement too, for nothing.
     */
    private boolean readerOn() {
        android.view.accessibility.AccessibilityManager am =
                getContext().getSystemService(android.view.accessibility.AccessibilityManager.class);
        return am != null && am.isEnabled() && !am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
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
            float cap = clock ? playingClockCap : playingTabCap;
            float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + cap / 2;
            // Only the title: no equalizer anywhere on the home screen.
            float x = clock ? MARGIN * k : right - playingTabWidth;
            c.drawText(line, x, base, text);
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
                // Moving apps: the tile slides from where it was (only then: no extra work otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                if (dx == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (scrolls) c.restore();
        // No names under the Dock's apps (as in Apple's photo). Above the Dock, a thin ⌃ while a pick shows: ↑ goes full
        // screen.
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
        if (moveState == LANDING && shelf.get(i) == landing && !landFade) return;   // the carried tile is on its way here
        float go = launchZone == Z_SHELF && i == launchIndex && !launchUp ? launch.value : 0;
        drawAppTile(c, shelf.get(i), tileRects[i], tileFocus[i].value, 0, go);
    }

    /**
     * An app tile as tvOS shows one (DESIGN-TVOS.md §0b #2–#4), in Apple's continuous-corner shape and in the tile's
     * own coordinates: at rest only the back layer (the brand's backdrop, or the app's TV banner) and its emblem, flat;
     * the system adds the light. Focused (`f`): 1.23×, one soft lift shadow, a 3° sway toward where the focus came
     * from with the emblem leaning 4 px (parallax), and one specular highlight that moves with the sway. `l`: lifted
     * to be moved. Nothing is allocated here.
     */
    private void drawAppTile(Canvas c, HomeApps.Item it, RectF base, float f, float l, float go) {
        // Focus 1.23× (SNAPPY_FOCUS); OK pressed: back to 1.15×; opening: it keeps growing as the app comes up.
        float fc = Ui.clamp(f), p = press.value * fc;
        float s = 1 + FOCUS_GROW * f + LIFT_GROW * l - TILE_PRESS * p + 0.6f * go;
        float bw = base.width(), bh = base.height(), r0 = TV_TILE_RADIUS * k;
        float cx = base.centerX() + bumpX * fc, cy = base.centerY() - TV_FOCUS_LIFT * k * f + bumpY * fc;
        tileFx(bw, bh);
        android.graphics.Path shape = tileShape.of(bw, bh, r0);
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        float lift = Math.max(fc, Ui.clamp(l));
        // Two shadows, crossfaded with the lift (at the resting size; the transform enlarges them, the blur stays
        // cached): resting y 6, blur 18, 18%; lifted y 28 (12 pressed), blur 46, 48%.
        // A small-memory TV's GPU can't also afford this for all 18 of All Apps' tiles on every frame (measured: 45 fps).
        if (lift < 0.99f && !(Ui.LEGACY && drawingGrid && sheet.value > 0.5f && lift < 0.01f)) {
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tmp.offset(0, 6 * k);
            tmp.inset(4 * k, 4 * k);
            shadowSoft.setColor(Ui.alpha(0xFF000000, tone(0.12f, 0.18f) * (1 - lift)));
            shadows.draw(c, tmp, r0, KEY_BLUR * k, shadowSoft);
        }
        if (lift > 0.01f) {
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tmp.offset(0, (6 + 22 * lift - 16 * p) * k);
            tmp.inset(6 * k, 4 * k);
            shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.34f, 0.48f) * lift));
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
            float iw = it.tile.getWidth(), ih = it.tile.getHeight(), sc = Math.max(bw / iw, bh / ih) * it.tileZoom;
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
        // Layer parallax (tvOS's layered icons): the back stays, the logo shifts 6 px with the tilt and grows 1.03×.
        float px = swayX.value * 6 * k * fc, py = swayY.value * 4 * k * fc;
        if (emblem) drawEmblem(c, it, style == null ? 0 : style[2], s, px, py, 1 + 0.03f * fc);
        float sheenA = fc * (1 - Ui.clamp(scrub.value));   // no sheen while scrubbing fast
        if (fc > 0.5f && it != glareFor && Ui.SMALL_MOTION && !Spring.instant && scrub.value < 0.01f) {
            glareFor = it;   // a new arrival: the glare crosses from the side the focus came from
            float from = lastDx < 0 ? 1.3f : -1.3f;
            glare.snap(from);
            glare.target = -from;
        }
        float through = it == glareFor ? Ui.clamp(1 - Math.abs(glare.value) / 1.3f) * sheenA : 0;
        if (through > 0.01f) {
            if (glareShader == null || glareW != bw) {
                glareW = bw;
                float band = bw * 0.2f;
                glareShader = new android.graphics.LinearGradient(-band / 2, 0, band / 2, 0,
                        new int[] {0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
            }
            float bx = bw / 2 + glare.value * bw * 0.62f + px;   // it rides the tilt like the logo
            int save = c.save();
            c.translate(bx - bw / 2, -bh / 2);
            shape.offset(-bx, 0);
            tileFill.setShader(glareShader);
            tileFill.setAlpha(Math.round(255 * through));
            c.drawPath(shape, tileFill);
            // The bevel's 1.5 px glint: the shape's edge, lit only where the band is.
            glareRim.setStyle(Paint.Style.STROKE);
            glareRim.setStrokeWidth(3 * k);   // half of it inside the tile: 1.5 px
            glareRim.setShader(glareShader);
            glareRim.setAlpha(Math.round(255 * Math.min(1, through * 2.2f)));
            c.drawPath(shape, glareRim);
            tileFill.setShader(null);
            tileFill.setAlpha(255);
            shape.offset(bx, 0);
            c.restoreToCount(save);
        }
        if (sheenA > 0.01f) {
            // The one specular highlight: soft, ≤ 14 %, sitting up and to the left and following the sway. The canvas
            // moves and the shape moves back: the shader keeps its matrix (a new local matrix every frame rebuilds the
            // native gradient on Android 8 to 12).
            // The sheen slides the other way from the tilt (the light stays where it is as the tile turns).
            float hx = -bw * (0.22f - 0.12f * swayX.value), hy = -bh * (0.3f - 0.1f * swayY.value);
            c.translate(hx - bw / 2, hy - bh / 2);
            shape.offset(-hx, -hy);
            tileFill.setShader(tileSheen);
            tileFill.setAlpha(Math.round(255 * sheenA));
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
    private void drawEmblem(Canvas c, HomeApps.Item it, int mode, float s, float px, float py, float zoom) {
        Emblem e = emblem(it, mode == 2 && it.logoDark != null);
        if (e == null) return;
        // The resting copy only at rest: as soon as the tile grows, the focused (sharper, bigger) copy, so the logo never
        // swaps mid-grow.
        boolean rest = s < 1.01f && zoom < 1.001f;
        Bitmap logo = rest ? e.rest : e.focus;
        float scale = (rest ? 1 : 1 / (1 + FOCUS_GROW)) * zoom;   // the focused copy is 1.23× bigger: the canvas scales it back
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
        return HomeApps.drawerShortcuts(c);
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        allDrawer = list;
        list = panelItems(list);
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
                drawerFocus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    /**
     * The panel: the profiles, its 2-column bento cards (they shrink when there are many, down to CC_CARD_MIN, then
     * scroll), the volume slider and the weather card at the bottom.
     */
    /**
     * The panel, top to bottom: the profiles; the Time & Weather platter (OK opens the forecast); the capsule cards in
     * 2 columns (they shrink when there are many, down to CC_CARD_MIN, then scroll); the volume capsule.
     */
    private void layoutDrawer() {
        int n = drawerItems.size();
        boolean vol = volumeShown();
        float left = (CC_LEFT + CC_PAD) * k, right = (CC_RIGHT - CC_PAD) * k, body = right - left, gap = CC_GAP * k;
        float top = (CC_TOP + CC_PAD) * k;
        // The profile panel: the profiles, then the weather the whole width; Control Center starts with its cluster.
        if (ccProfile) {
            layoutProfiles(left, right);
            float platterTop = top + CC_HEAD_H * k;
            weatherCard.set(left, platterTop, right, platterTop + CC_PLATTER_H * k);
            snap(weatherCard);
            top = weatherCard.bottom + gap;
        } else {
            clearProfiles();
            weatherCard.setEmpty();
        }
        // The playback card first, while a session has something; then the rest under it.
        int mediaAt = kindIndex(HomeApps.MEDIA);
        if (mediaAt >= 0) {
            drawerRects[mediaAt].set(left, top, right, top + CC_MEDIA_H * k);
            snap(drawerRects[mediaAt]);
            top = drawerRects[mediaAt].bottom + CC_ROW_GAP * k;
            fitMedia(body);
        }
        // The pills two a row in their order, the volume's bar under them, then the cells sharing the last row.
        int cells = 0, pill = 0, cell = 0;
        for (HomeApps.Item it : drawerItems) if (ccShape(it.kind) == CC_ROUND) cells++;
        float rowGap = CC_ROW_GAP * k, y = top;
        clusterPlate.setEmpty();
        for (int i = 0; i < n; i++) {
            if (ccShape(drawerItems.get(i).kind) != CC_PILL) continue;
            float x = left + (pill % 2) * (CC_CARD_W * k + gap), py = top + (pill / 2) * (CC_CARD_H * k + rowGap);
            drawerRects[i].set(x, py, x + CC_CARD_W * k, py + CC_CARD_H * k);
            snap(drawerRects[i]);
            y = drawerRects[i].bottom;
            pill++;
        }
        for (int i = 0; i < n; i++) {
            if (ccShape(drawerItems.get(i).kind) != CC_SLIDER) continue;
            RectF r = drawerRects[i];
            if (!vol) {
                r.setEmpty();
                continue;
            }
            if (y > top) y += gap;
            r.set(left, y, right, y + CC_VOLUME_H * k);
            snap(r);
            y = r.bottom;
        }
        if (cells > 0 && y > top) y += rowGap;
        float cw = cells == 0 ? 0 : (body - (cells - 1) * CC_CELL_GAP * k) / cells;
        for (int i = 0; i < n; i++) {
            if (ccShape(drawerItems.get(i).kind) != CC_ROUND) continue;
            float x = left + cell * (cw + CC_CELL_GAP * k);
            drawerRects[i].set(x, y, x + cw, y + CC_CELL_H * k);
            snap(drawerRects[i]);
            cell++;
        }
        if (cells > 0) y += CC_CELL_H * k;
        float bottom = y + CC_PAD * k;
        panelRect.set(CC_LEFT * k, CC_TOP * k, CC_RIGHT * k, Math.min(CC_BOTTOM * k, bottom));
        snap(panelRect);
        cardsArea.set(panelRect.left, top, panelRect.right, panelRect.bottom);
        ccScrollMax = 0;
        ccScroll.target = 0;
        volumeRect.setEmpty();
        quotesCard.setEmpty();
        // The names cut to fit once (never while drawing): a pill's beside its plate, a circle's or round button's under it.
        drawerLabels = new String[n];
        for (int i = 0; i < n && ccLabel != null; i++) {
            HomeApps.Item it = drawerItems.get(i);
            String label = it.label == null ? "" : it.label;
            int shape = ccShape(it.kind);
            TextPaint paint = new TextPaint(shape == CC_ROUND ? ccCell : ccLabel);
            float room = shape == CC_PILL ? drawerRects[i].width() - CC_TEXT_X * k - 18 * k : drawerRects[i].width() - 16 * k;
            drawerLabels[i] = TextUtils.ellipsize(label, paint, room, TextUtils.TruncateAt.END).toString();
        }
        layoutDetails();
        layoutInputs();
        // The Inputs list open: the panel as tall as it.
        int inputCount = inputItems.size();
        if (ccInputs && inputCount > 0 && inputRects.length >= inputCount) {
            panelRect.bottom = Math.min(CC_BOTTOM * k, Math.max(panelRect.bottom, inputRects[inputCount - 1].bottom + CC_PAD * k));
            cardsArea.bottom = panelRect.bottom;
        }
        for (HomeApps.Item it : drawerItems) Symbols.warm(getContext(), it.symbol, CC_SYMBOL * k);
        Symbols.warm(getContext(), "hdmi", CC_SYMBOL * k);
        Symbols.warm(getContext(), "tv", CC_SYMBOL * k);
        Symbols.warm(getContext(), "check", 24 * k);
        Symbols.warm(getContext(), "chevron_right", 24 * k);
        Symbols.warm(getContext(), "sunny", 30 * k);
        Symbols.warm(getContext(), "volume", 30 * k);
        Symbols.warmWeather(getContext(), 40 * k);
        Symbols.warmWeather(getContext(), 28 * k);
        drawerDirty = true;
    }

    /** Control Center has no profiles row: none to draw or reach. */
    private void clearProfiles() {
        if (profileRects.length == 0) return;
        profileRects = new RectF[0];
        profileFocus = new Spring[0];
        profileAvatars = new Bitmap[0];
        profileNames = new String[0];
    }

    // The shapes the cards take by kind: pills two a row (Control Center's and the profile panel's Inputs | Screensaver),
    // the volume's bar the whole width, and the profile panel's cells (Lock, Home Settings, TV Settings) sharing a row.
    private static final int CC_CIRCLE = 2, CC_SLIDER = 3, CC_PILL = 4, CC_ROUND = 5, CC_MEDIA = 6;
    /** The rows under the weather, by the shapes in them (drawDrawer). */
    private static final int[][] CC_ROWS = {{CC_MEDIA}, {CC_PILL}, {CC_SLIDER}, {CC_ROUND}};
    /** The playback card: its height and each of its three buttons' width. */
    private static final float CC_MEDIA_H = 92, CC_MEDIA_SLOT = 52;
    private static final float CC_HEAD_H = 128, CC_PLATTER_H = 152;
    private static final float CC_CELL_W = 122, CC_CELL_H = 92, CC_CELL_GAP = 12, CC_CIRCLE_D = 64, CC_SLIDER_W = 114, CC_ROUND_D = 72;
    private final RectF clusterPlate = new RectF();
    private final Shape plateShape = new Shape(), sliderShape = new Shape(), wideShape = new Shape();

    private static int ccShape(int kind) {
        switch (kind) {
            case HomeApps.VOLUME: return CC_SLIDER;
            case HomeApps.MEDIA: return CC_MEDIA;
            case HomeApps.LOCK: case HomeApps.SETTINGS: case HomeApps.ANDROID_SETTINGS: return CC_ROUND;
            default: return CC_PILL;
        }
    }

    /** ccNearest's answers besides a card's index: nothing that way, the weather, a profile (PROFILE_STOP − its index). */
    private static final int NO_STOP = -1, WEATHER_STOP = -2, PROFILE_STOP = -100;

    /**
     * Control Center's next stop from `from` that way (dx or dy ±1): of the cards (not `skip`), the weather and the
     * profiles, the one whose near edge lies that way past `from`'s centre, nearest along the move plus 2.5× how far it
     * sits off the move's line. NO_STOP when nothing does.
     */
    private int ccNearest(RectF from, int dx, int dy, int skip) {
        float fx = from.centerX(), fy = from.centerY(), best = Float.MAX_VALUE;
        int pick = NO_STOP;
        for (int i = 0; i < drawerItems.size(); i++) {
            if (i == skip || drawerRects[i].isEmpty()) continue;
            float score = ccScore(fx, fy, drawerRects[i], dx, dy);
            if (score < best) {
                best = score;
                pick = i;
            }
        }
        if (zone != WIDGETS && !widgets().isEmpty() && !weatherCard.isEmpty()) {
            float score = ccScore(fx, fy, weatherCard, dx, dy);
            if (score < best) {
                best = score;
                pick = WEATHER_STOP;
            }
        }
        for (int j = 0; j < profileRects.length; j++) {
            if (zone == Z_DRAWER && ccOnProfiles) break;
            float score = ccScore(fx, fy, profileRects[j], dx, dy);
            if (score < best) {
                best = score;
                pick = PROFILE_STOP - j;
            }
        }
        return pick;
    }

    private static float ccScore(float fx, float fy, RectF to, int dx, int dy) {
        float along, off;
        if (dx != 0) {
            along = dx > 0 ? to.left - fx : fx - to.right;
            off = Math.max(0, Math.max(to.top - fy, fy - to.bottom));
        } else {
            along = dy > 0 ? to.top - fy : fy - to.bottom;
            off = Math.max(0, Math.max(to.left - fx, fx - to.right));
        }
        return along < 0 ? Float.MAX_VALUE : along + 2.5f * off;
    }

    /** Goes to a stop ccNearest found; false for NO_STOP. */
    private boolean ccStop(int to) {
        if (to == NO_STOP) return false;
        if (to == WEATHER_STOP) {
            zone = WIDGETS;
            widgetIndex = widgets().get(0);
            ccOnProfiles = false;
        } else if (to <= PROFILE_STOP) {
            zone = Z_DRAWER;
            ccOnProfiles = true;
            profileIndex = PROFILE_STOP - to;
        } else {
            zone = Z_DRAWER;
            ccOnProfiles = false;
            drawerIndex = to;
        }
        ccOnVolume = false;
        return true;
    }

    /** Every card (HomeApps.drawerShortcuts), and which panel shows: the profile panel (the pill) or Control Center. */
    private List<HomeApps.Item> allDrawer = new ArrayList<>();
    private boolean ccProfile;

    /** The profile panel's cards: Inputs, Screensaver, Lock, Home Settings, TV Settings; Control Center has the rest. */
    private static boolean profileCard(int kind) {
        return kind == HomeApps.INPUT_SHEET || kind == HomeApps.SAVER || kind == HomeApps.LOCK || kind == HomeApps.SETTINGS
                || kind == HomeApps.ANDROID_SETTINGS;
    }

    private List<HomeApps.Item> panelItems(List<HomeApps.Item> all) {
        List<HomeApps.Item> out = new ArrayList<>();
        for (HomeApps.Item it : all) {
            if (it.kind == HomeApps.MEDIA && (playing == null || !playing.hasMedia())) continue;
            if (profileCard(it.kind) == ccProfile) out.add(it);
        }
        return out;
    }

    /** The first card of a kind, or −1. */
    private int kindIndex(int kind) {
        for (int i = 0; i < drawerItems.size(); i++) if (drawerItems.get(i).kind == kind) return i;
        return -1;
    }

    /** A card's round symbol plate (40) and the room for its name and state beside it. */
    private static final float CC_PLATE = 40, CC_SYMBOL = 24, CC_TEXT_X = 66, CC_TEXT_W = CC_CARD_W - 66 - 18;

    /** The cards' states (Wi-Fi: Connected, Audio Output: TV Speakers) cut to fit under their name, once. */
    private void layoutDetails() {
        int n = drawerItems.size();
        drawerDetails = new String[n];
        for (int i = 0; i < n && ccDetail != null; i++) {
            String d = drawerItems.get(i).detail;
            if (d != null) drawerDetails[i] = TextUtils.ellipsize(d, new TextPaint(ccDetail), CC_TEXT_W * k,
                    TextUtils.TruncateAt.END).toString();
        }
    }

    /** Control Center's Inputs list: Live TV and the HDMI ports, the ones without a device dimmed. */
    private void setInputItems(List<HomeApps.Item> list) {
        inputItems = list;
        if (inputFocus.length != list.size()) {
            inputFocus = new Spring[list.size()];
            inputRects = new RectF[list.size()];
            for (int i = 0; i < inputFocus.length; i++) {
                inputFocus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
                inputRects[i] = new RectF();
            }
            inputIndex = Math.min(inputIndex, Math.max(0, list.size() - 1));
            if (list.isEmpty() && ccInputs) closeInputs();
        }
        layoutInputs();
        drawerDirty = true;
    }

    /** The Inputs list where the platter and the cards are: a header ("‹ Inputs"), then a row per input, 72 tall. */
    private void layoutInputs() {
        if (ccDetail == null) return;
        float left = (CC_LEFT + CC_PAD) * k, right = (CC_RIGHT - CC_PAD) * k, top = weatherCard.top;
        inputsHeader.set(left, top, right, top + 56 * k);
        inputsTitle = Ui.tr("Inputs");
        int n = inputItems.size();
        inputLabels = new String[n];
        inputStates = new String[n];
        inputStateW = new float[n];
        float y = inputsHeader.bottom + 10 * k;
        for (int i = 0; i < n; i++) {
            inputRects[i].set(left, y, right, y + CC_INPUT_ROW * k);
            snap(inputRects[i]);
            y += (CC_INPUT_ROW + CC_INPUT_GAP) * k;
            HomeApps.Item it = inputItems.get(i);
            String state = it.kind == HomeApps.INPUT ? (it.connected ? Ui.tr("Connected") : Ui.tr("No signal")) : null;
            inputLabels[i] = TextUtils.ellipsize(it.label == null ? "" : it.label, new TextPaint(ccLabel), 260 * k,
                    TextUtils.TruncateAt.END).toString();
            inputStates[i] = state;
            inputStateW[i] = state == null ? 0 : ccDetail.measureText(state);
        }
    }

    /** OK on the Inputs card: the list slides in over the platter and cards (SHEET_OPEN), on the first input. */
    private void openInputs() {
        if (inputItems.isEmpty()) {
            showToast(Ui.tr("This TV lists no inputs."));
            return;
        }
        if (!ccInputs) click.sheet(true, 0.78f);
        ccInputs = true;
        ccOnProfiles = ccOnVolume = false;
        if (zone == WIDGETS) zone = Z_DRAWER;
        inputIndex = 0;
        inputsShown.tune(Spring.CC_OPEN);
        inputsShown.target = 1;
        layoutDrawer();   // the panel grows to the list
        drawerDirty = true;
    }

    /** Back or ←: the list slides away (SHEET_CLOSE) and the focus returns to the Inputs card. */
    private void closeInputs() {
        if (ccInputs) click.sheet(false, 0.78f);
        ccInputs = false;
        inputsShown.tune(Spring.SHEET_CLOSE);
        inputsShown.target = 0;
        for (int i = 0; i < drawerItems.size(); i++) {
            if (drawerItems.get(i).kind == HomeApps.INPUT_SHEET) drawerIndex = i;
        }
        layoutDrawer();   // back to its own height
        drawerDirty = true;
    }

    /** The volume slider shows when the TV lets apps change the volume (not on a fixed-volume output), as last read. */
    private boolean volumeShown() {
        return volumeAvailable;
    }

    /**
     * Control Center's live state, read on LOADER (system calls, never on the UI thread): Wi-Fi's and Bluetooth's state
     * for their cards and the volume for the slider; `snap` puts the slider there at once (the panel opening).
     */
    private void readDrawerState(boolean snap) {
        final Context c = getContext().getApplicationContext();
        final android.media.AudioManager am = audio;
        LOADER.execute(() -> {
            String[] state = HomeApps.connectivity(c);
            final boolean sleeping = SleepTimer.minutesLeft(c) > 0;
            boolean avail = false;
            int max = 1, index = 0;
            if (am != null) {
                try {
                    max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                    avail = !am.isVolumeFixed() && max > 0;
                    index = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                } catch (RuntimeException e) {
                    AppLog.w("volume: " + e.getMessage());
                }
            }
            final boolean available = avail;
            final int top = Math.max(1, max), level = Math.max(0, Math.min(top, index));
            post(() -> {
                HomeApps.showConnectivity(drawerItems, state);
                sleepOn = sleeping;
                showAppearance();
                volumeMax = top;
                volumeIndex = level;
                if (available != volumeAvailable) {
                    volumeAvailable = available;
                    if (!available) ccOnVolume = false;
                    layoutDrawer();   // the slider's row comes or goes
                } else {
                    layoutDetails();
                }
                volumeLevel.target = level / (float) top;
                if (snap) volumeLevel.snap(volumeLevel.target);
                drawerDirty = true;
                wake();
            });
        });
    }

    /** The pills' states that the panel keeps itself: Dark Mode, the Sleep Timer and Brightness (its level). */
    private void showAppearance() {
        for (HomeApps.Item it : drawerItems) {
            if (it.kind == HomeApps.APPEARANCE) it.detail = nightTarget ? Ui.tr("state|On") : Ui.tr("state|Off");
            else if (it.kind == HomeApps.SLEEP) {
                int left = sleepOn ? SleepTimer.minutesLeft(getContext()) : 0;
                it.detail = left > 0 ? SleepTimer.duration(left) : Ui.tr("state|Off");
            }
            else if (it.kind == HomeApps.BRIGHTNESS) it.detail = PERCENT[Prefs.brightness()] + "%";
            else if (it.kind == HomeApps.LISTEN_ALONE) {
                // The AirPods' name while they play the TV's sound (lit); else what OK does.
                String air = AirPods.connected(audio);
                it.connected = air != null;
                it.detail = air != null ? air : Ui.tr("Pair AirPods");
            }
        }
    }

    private static final int[] SLEEP_PRESETS = {15, 30, 60, 120};

    /** When Do Not Disturb was pressed (uptime): its moon swings in from −40° over DND_POP_MS once it's on. */
    private long dndAt;
    private static final long DND_POP_MS = 700;
    /** The panel drawn every frame while the moon moves (it's a cached picture otherwise). */
    private final Runnable dndFrames = new Runnable() {
        @Override public void run() {
            drawerDirty = true;
            wake();
            if (SystemClock.uptimeMillis() - dndAt < DND_POP_MS + 100) handler.postDelayed(this, 16);
        }
    };

    /** OK on Dark Mode: this profile's theme, Light or Dark (Home Settings still offers Automatic); the screen fades over. */
    private void toggleDark() {
        Prefs.setTheme(nightTarget ? "light" : "dark");
        checkNight(true);
        showAppearance();
        layoutDetails();
        drawerDirty = true;
        wake();
    }

    /** OK on Brightness: a quarter down, and from 25 % back to full (the HUD on the left shows it). */
    private void cycleBrightness() {
        int b = Prefs.brightness(), next = b > 75 ? 75 : b > 50 ? 50 : b > 25 ? 25 : 100;
        stepBrightness(next - b);
        showAppearance();
        layoutDetails();
        wake();
    }

    /**
     * Brightness by `delta` percent (the channel keys: 1 a press, 5 a step while held; Control Center's pill: a quarter).
     * The dimmer over mahitTV's screens and the window's brightness change at once; the TV's own brightness follows in
     * mahitTV mode (HomeMode.brightness; Original puts it back). False when it's already at that end.
     */
    private boolean stepBrightness(int delta) {
        int was = Prefs.brightness(), b = Math.max(0, Math.min(100, was + delta));
        if (b == was) return false;
        Prefs.setBrightness(b);
        brightLevel.target = b / 100f;
        applyBrightness();
        HomeMode.brightness(getContext(), b);
        announceForAccessibility(Ui.trf("Brightness, {n}%", "n", b));
        drawerDirty = true;
        return true;
    }

    /** The dimmer (0.78 black at 0 %, none at 100 %) and the window's own brightness, as Prefs has them. */
    void applyBrightness() {
        int b = Prefs.brightness();
        brightLevel.target = b / 100f;
        if (dimmer != null) {
            dimmer.setAlpha(0.78f * (100 - b) / 100f);
            dimmer.setVisibility(b < 100 ? VISIBLE : GONE);
        }
        android.view.Window win = activity.getWindow();
        android.view.WindowManager.LayoutParams lp = win.getAttributes();
        float want = b < 100 ? Math.max(0.05f, b / 100f) : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (lp.screenBrightness != want) {
            lp.screenBrightness = want;
            win.setAttributes(lp);
        }
    }

    /**
     * ←/→ on the slider: one step, exactly as the remote's volume keys (no system panel: the slider is the panel). The
     * step is counted here; LOADER reads the real level afterwards.
     */
    private boolean stepVolume(int dx) {
        if (audio == null || !volumeAvailable) return false;
        int next = Math.max(0, Math.min(volumeMax, volumeIndex + (dx > 0 ? 1 : -1)));
        if (next == volumeIndex) return false;
        audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                dx > 0 ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, 0);
        volumeIndex = next;
        volumeLevel.target = next / (float) volumeMax;
        drawerDirty = true;
        readDrawerState(false);
        return true;
    }

    /** Control Center's top row: the profiles' avatars (84 round) and names, spread across the panel, cut to fit once. */
    private void layoutProfiles(float left, float right) {
        if (ccName == null) return;
        profiles = ProfileManager.all(getContext());
        int n = profiles.size();
        if (profileRects.length != n) {
            profileRects = new RectF[n];
            profileFocus = new Spring[n];
            for (int i = 0; i < n; i++) {
                profileRects[i] = new RectF();
                profileFocus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
            }
            profileIndex = Math.min(profileIndex, Math.max(0, n - 1));
        }
        // Five 84s fit the 504 inside with 21 between; fewer spread out up to 28 apart and centre.
        float size = 84 * k, top = (CC_TOP + CC_PAD) * k;
        float gap = n < 2 ? 0 : Math.min(28 * k, (right - left - n * size) / (n - 1));
        if (gap < 6 * k) {   // more profiles than fit: smaller faces
            size = (right - left - (n - 1) * 6 * k) / n;
            gap = 6 * k;
        }
        float start = left + (right - left - (n * size + Math.max(0, n - 1) * gap)) / 2;
        profileAvatars = new Bitmap[n];
        profileNames = new String[n];
        for (int i = 0; i < n; i++) {
            float x = start + i * (size + gap);
            profileRects[i].set(x, top, x + size, top + size);
            profileAvatars[i] = ProfileManager.avatar(getContext(), profiles.get(i), Math.round(size));
            profileNames[i] = TextUtils.ellipsize(profiles.get(i).name, new TextPaint(ccName), size + gap - 6 * k, TextUtils.TruncateAt.END).toString();
        }
    }

    /** Control Center (the bubble). */
    private void openDrawer() {
        openDrawer(false);
    }

    /**
     * Opens a panel under the top-right cluster: Control Center's controls (the bubble), or the profile panel (the
     * pill: the profiles, the weather, Inputs, Screensaver, Lock and the settings).
     */
    private void openDrawer(boolean profile) {
        if (profile != ccProfile || drawerItems.isEmpty()) {
            ccProfile = profile;
            setDrawerItems(allDrawer);
        }
        if (drawerItems.isEmpty()) return;
        drawerFrom = zone == Z_TOP ? Z_TOP : zone == Z_STATUS ? Z_STATUS : Z_SHELF;
        if (!drawerOpen) click.sheet(true, 0.82f);   // Control Center comes from the top right
        drawerOpen = true;
        drawer.tune(Spring.CC_OPEN);   // tvOS 27's quicker Control Center (still a little slower than leaving)
        drawer.target = 1;
        drawerOpenedAt = SystemClock.uptimeMillis();
        motionDirty = true;
        zone = Z_DRAWER;
        drawerIndex = 0;
        // The profile panel: on you, when there's someone to switch to; Control Center: on its first control. The focus
        // grows in as the panel arrives (retarget), not already there.
        ccOnProfiles = ccProfile && profiles.size() > 1;
        ccOnVolume = false;
        ccInputs = false;
        inputsShown.snap(0);
        ccScroll.target = 0;
        ccScroll.snap(0);
        profileIndex = ProfileManager.currentIndex(getContext());
        // Wi-Fi's and Bluetooth's state and the volume as they are now (read on LOADER, never while drawing).
        readDrawerState(true);
        drawerDirty = true;
    }

    private void closeDrawer() {
        if (drawerOpen) click.sheet(false, 0.82f);
        drawerOpen = false;
        drawer.tune(Spring.SHEET_CLOSE);   // disappearing is brisker than appearing
        drawer.target = 0;
        motionDirty = true;
        zone = drawerFrom == Z_TOP && !lightButtons.isEmpty() ? Z_TOP : drawerFrom == Z_STATUS ? Z_STATUS : Z_SHELF;
        if (zone == Z_SHELF && tab != TAB_TV) zone = Z_STATUS;   // the Dock isn't there on Home and Welly
    }

    /** Color for Control Center's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /**
     * A tile inside the panels: a flat, quiet fill (white 42 % by day, white 9 % at night) in Apple's continuous shape.
     * Not glass, and no rim or highlight: the panel under it already is the glass (no glass on glass, DESIGN-TVOS.md
     * §0b #5).
     */
    private void drawerCard(Canvas c, RectF r, Shape shape, float alpha) {
        drawerCard(c, r, shape, CC_CARD_RADIUS * k, alpha);
    }

    private void drawerCard(Canvas c, RectF r, Shape shape, float radius, float alpha) {
        if (alpha <= 0.004f) return;
        float d = dark();
        fill.setShader(null);
        int base = blend(0x6BFFFFFF, 0x17FFFFFF, d);
        fill.setColor((base & 0xFFFFFF) | (Math.round((base >>> 24) * alpha) << 24));
        c.save();
        c.translate(r.left, r.top);
        c.drawPath(shape.of(r.width(), r.height(), radius), fill);
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
        long since = SystemClock.uptimeMillis() - drawerOpenedAt;
        int save = rowIn(c, 0, since, panelRect.left, panelRect.top, panelRect.right, weatherCard.top);
        if (ccProfile) drawProfiles(c);
        if (save >= 0) c.restoreToCount(save);
        // The Inputs list slides in over the platter and the cards (they slide 40 px left and fade): one layer each
        // while it moves.
        float e = Ui.clamp(inputsShown.value);
        if (e > 0.004f) {
            int list = c.saveLayerAlpha(panelRect.left, weatherCard.top - 12 * k, panelRect.right, panelRect.bottom, Math.round(255 * e));
            c.translate(40 * k * (1 - e), 0);
            drawInputs(c);
            c.restoreToCount(list);
            if (e >= 0.996f) return;
        }
        int rest = e > 0.004f ? c.saveLayerAlpha(panelRect.left, weatherCard.top - 12 * k, panelRect.right, panelRect.bottom,
                Math.round(255 * (1 - e))) : -1;
        if (rest >= 0) c.translate(-40 * k * e, 0);
        if (!weatherCard.isEmpty()) {
            save = rowIn(c, 1, since, panelRect.left, weatherCard.top, panelRect.right, weatherCard.bottom);
            drawWeatherCard(c, weatherCard);
            if (save >= 0) c.restoreToCount(save);
        }
        // The rest, row by row (the cluster and the sliders, the pills, the round buttons); the focused card last, over
        // its neighbours.
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) {
            int shape = ccShape(drawerItems.get(i).kind);
            if (drawerRects[i].isEmpty()) continue;
            if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        }
        int[][] rows = CC_ROWS;
        for (int row = 0; row < rows.length; row++) {
            float rt = Float.MAX_VALUE, rb = -1;
            for (int i = 0; i < drawerItems.size(); i++) {
                int shape = ccShape(drawerItems.get(i).kind);
                if ((shape == rows[row][0] || (rows[row].length > 1 && shape == rows[row][1])) && !drawerRects[i].isEmpty()) {
                    rt = Math.min(rt, drawerRects[i].top);
                    rb = Math.max(rb, drawerRects[i].bottom);
                }
            }
            if (rb < 0) continue;
            save = rowIn(c, 2 + row, since, panelRect.left, rt, panelRect.right, rb + 40 * k);
            if (row == 0 && !clusterPlate.isEmpty()) drawerCard(c, clusterPlate, plateShape, 32 * k, 1);
            for (int i = 0; i < drawerItems.size(); i++) {
                int shape = ccShape(drawerItems.get(i).kind);
                if (i == top || drawerRects[i].isEmpty() || (shape != rows[row][0] && (rows[row].length < 2 || shape != rows[row][1]))) continue;
                drawCcCard(c, i);
            }
            if (save >= 0) c.restoreToCount(save);
        }
        if (top >= 0) drawCcCard(c, top);
        if (rest >= 0) c.restoreToCount(rest);
    }

    /** A card in its shape: a cluster circle, a slider, a pill or a round button. */
    private void drawCcCard(Canvas c, int i) {
        switch (ccShape(drawerItems.get(i).kind)) {
            case CC_CIRCLE: drawCcCircle(c, i); break;
            case CC_SLIDER: drawCcSlider(c, i); break;
            case CC_MEDIA: drawCcMedia(c, i); break;
            case CC_PILL: drawDrawerTile(c, i); break;
            default: drawCcRound(c, i); break;
        }
    }

    /**
     * A glass disc (the cluster's, the round buttons', ⏻): the panel's frosted fill and rim, blue when its toggle is on
     * (iOS's #007AFF), white when focused; the symbol in the middle.
     */
    private void ccDisc(Canvas c, float cx, float cy, float r, float f, boolean lit, String symbol, float size) {
        if (f > 0.01f) {
            tmp.set(cx - r, cy - r, cx + r, cy + r);
            tmp.offset(0, 10 * k * f);
            shadowSoft.setColor(Ui.alpha(0xFF000000, 0.3f * f));
            shadows.draw(c, tmp, r, KEY_BLUR * k, shadowSoft);
        }
        float d = dark();
        fill.setShader(null);
        int base = lit ? 0xFF007AFF : blend(blend(0x6BFFFFFF, 0x17FFFFFF, d), 0xFFFFFFFF, f);
        fill.setColor(base);
        c.drawCircle(cx, cy, r, fill);
        if (lit && f > 0.01f) {   // on and focused: a white ring round the blue
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, f));
            rim.setStrokeWidth(4 * k);
            c.drawCircle(cx, cy, r + 4 * k, rim);
            rim.setStrokeWidth(2.5f * k);
        }
        int ink = lit ? 0xFFFFFFFF : blend(tone(L_INK, 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, symbol, cx, cy, size, ink);
    }

    /** Whether a cluster toggle is on: Wi-Fi up, Bluetooth on, Do Not Disturb on, the Sleep Timer running. */
    private boolean ccLit(HomeApps.Item it) {
        if (it.kind == HomeApps.SLEEP) return sleepOn;
        if (it.kind == HomeApps.APPEARANCE) return nightTarget;   // Dark Mode
        return it.connected && (it.kind == HomeApps.WIFI || it.kind == HomeApps.BLUETOOTH || it.kind == HomeApps.DND
                || it.kind == HomeApps.LISTEN_ALONE);
    }

    private void drawCcCircle(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        RectF r = drawerRects[i];
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.1f * drawerFocus[i].value - PRESS * press.value * f;
        float cx = r.centerX(), cy = r.top + (6 + CC_CIRCLE_D / 2) * k;
        c.save();
        c.translate(bumpX * f, bumpY * f);
        c.scale(s, s, cx, cy);
        ccDisc(c, cx, cy, CC_CIRCLE_D / 2 * k, f, ccLit(it), it.symbol, 26 * k);
        c.restore();
        ccSmall.setColor(drawerInk(0.62f + 0.38f * f));
        if (i < drawerLabels.length && drawerLabels[i] != null) c.drawText(drawerLabels[i], cx, r.top + 90 * k, ccSmall);
    }

    /** A cell of the profile panel's last row: its symbol over its name; focused, white with ink and a lift. */
    private void drawCcRound(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        RectF r = drawerRects[i];
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.05f * drawerFocus[i].value - PRESS * press.value * f;
        float rr = 26 * k;
        c.save();
        c.translate(bumpX * f, bumpY * f);
        c.scale(s, s, r.centerX(), r.centerY());
        if (f > 0.01f) {
            tmp.set(r);
            tmp.offset(0, 12 * k * f);
            shadowSoft.setColor(Ui.alpha(0xFF000000, 0.32f * f));
            shadows.draw(c, tmp, rr, KEY_BLUR * k, shadowSoft);
        }
        if (f < 0.99f) drawerCard(c, r, plateShape, rr, 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.save();
            c.translate(r.left, r.top);
            c.drawPath(plateShape.of(r.width(), r.height(), rr), fill);
            c.restore();
        }
        int ink = blend(tone(L_INK, 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, r.centerX(), r.top + 32 * k, 28 * k, ink);
        ccCell.setColor(ink);
        if (i < drawerLabels.length && drawerLabels[i] != null) c.drawText(drawerLabels[i], r.centerX(), r.top + 72 * k, ccCell);
        c.restore();
    }

    /**
     * The volume's bar the whole width: the speaker, the level as a capsule track and its number. Focused: white with
     * ink, the track a little thicker; ← → step it, as the remote's keys. The level glides to each new value.
     */
    private void drawCcSlider(Canvas c, int i) {
        RectF r = drawerRects[i];
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.03f * drawerFocus[i].value - PRESS * press.value * f;
        float rr = r.height() / 2, lv = Ui.clamp(volumeLevel.value);
        c.save();
        c.translate(bumpX * f, 0);
        c.scale(s, s, r.centerX(), r.centerY());
        if (f > 0.01f) {
            tmp.set(r);
            tmp.offset(0, 14 * k * f);
            shadowSoft.setColor(Ui.alpha(0xFF000000, 0.32f * f));
            shadows.draw(c, tmp, rr, KEY_BLUR * k, shadowSoft);
        }
        if (f < 0.99f) drawerCard(c, r, sliderShape, rr, 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.save();
            c.translate(r.left, r.top);
            c.drawPath(sliderShape.of(r.width(), r.height(), rr), fill);
            c.restore();
        }
        int ink = blend(tone(L_INK, 0xF2FFFFFF), INK, f);
        float pad = 22 * k, size = 28 * k, cy = r.centerY();
        Symbols.draw(getContext(), c, "volume", r.left + pad + size / 2, cy, size, ink);
        float x0 = r.left + pad + size + 18 * k, x1 = r.right - 84 * k, th = (8 + 2 * f) * k;
        fill.setShader(null);
        fill.setColor(blend(tone(0x3D787880, 0x38FFFFFF), 0x26000000, f));
        tmp.set(x0, cy - th / 2, x1, cy + th / 2);
        c.drawRoundRect(tmp, th / 2, th / 2, fill);
        if (lv > 0.001f) {
            fill.setColor(ink);
            tmp.set(x0, cy - th / 2, x0 + Math.max(th, (x1 - x0) * lv), cy + th / 2);
            c.drawRoundRect(tmp, th / 2, th / 2, fill);
        }
        ccNum.setColor(ink);
        c.drawText(PERCENT[Math.round(lv * 100)], r.right - 46 * k, cy + Ui.halfCap(ccNum), ccNum);
        c.restore();
    }

    /** The Inputs list: "‹ Inputs", then a capsule row per input (its symbol, name and state); the focused one white. */
    private void drawInputs(Canvas c) {
        int ink = drawerInk(0.95f);
        c.save();   // a chevron pointing back (‹): chevron_right, mirrored
        c.scale(-1, 1, inputsHeader.left + 12 * k, inputsHeader.centerY());
        Symbols.draw(getContext(), c, "chevron_right", inputsHeader.left + 12 * k, inputsHeader.centerY(), 24 * k, drawerInk(0.6f));
        c.restore();
        ccTitle.setColor(ink);
        c.drawText(inputsTitle, inputsHeader.left + 32 * k, inputsHeader.centerY() + Ui.halfCap(ccTitle), ccTitle);
        float rr = CC_INPUT_ROW / 2 * k;
        for (int i = 0; i < inputItems.size() && i < inputRects.length; i++) {
            HomeApps.Item it = inputItems.get(i);
            RectF r = inputRects[i];
            float f = Ui.clamp(inputFocus[i].value), s = 1 + 0.03f * inputFocus[i].value - PRESS * press.value * f;
            c.save();
            c.translate(bumpX * f, bumpY * f);
            c.scale(s, s, r.centerX(), r.centerY());
            if (f > 0.01f) {
                tmp.set(r);
                tmp.offset(0, 12 * k * f);
                shadowSoft.setColor(Ui.alpha(0xFF000000, 0.32f * f));
                shadows.draw(c, tmp, rr, KEY_BLUR * k, shadowSoft);
            }
            if (f < 0.99f) drawerCard(c, r, inputShape, rr, 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.save();
                c.translate(r.left, r.top);
                c.drawPath(inputShape.of(r.width(), r.height(), rr), fill);
                c.restore();
            }
            boolean off = it.kind == HomeApps.INPUT && !it.connected;
            int rowInk = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
            float plate = CC_PLATE * k, px = r.left + 16 * k + plate / 2, cy = r.centerY();
            fill.setColor(blend(tone(0x14000000, 0x24FFFFFF), 0x14000000, f));
            c.drawCircle(px, cy, plate / 2, fill);
            Symbols.draw(getContext(), c, it.kind == HomeApps.LIVE_TV ? "tv" : "hdmi", px, cy, CC_SYMBOL * k, rowInk);
            ccLabel.setColor(rowInk);
            if (inputLabels[i] != null) c.drawText(inputLabels[i], r.left + 72 * k, cy + Ui.halfCap(ccLabel), ccLabel);
            if (inputStates[i] != null) {
                ccDetail.setColor(blend(tone(L_DIM, 0xADFFFFFF), 0x991C1C1E, f));
                c.drawText(inputStates[i], r.right - 24 * k - inputStateW[i], cy + Ui.halfCap(ccDetail), ccDetail);
            }
            c.restore();
        }
    }

    /**
     * The volume slider: the speaker, then the level as a capsule track. Focused it turns white with ink like the
     * cards and its track thickens; ←/→ step it. The level glides (SNAPPY_FOCUS) to each new value.
     */
    private void drawVolume(Canvas c) {
        float f = Ui.clamp(volumeFocus.value), s = 1 + 0.03f * volumeFocus.value;
        RectF r = volumeRect;
        float rr = CC_CARD_RADIUS * k;
        c.save();
        c.translate(bumpX * f, 0);
        c.scale(s, s, r.centerX(), r.centerY());
        if (f > 0.01f) {
            tmp.set(r);
            tmp.offset(0, 14 * k * f);
            shadowSoft.setColor(Ui.alpha(0xFF000000, 0.4f * f));
            shadows.draw(c, tmp, rr, KEY_BLUR * k, shadowSoft);
        }
        if (f < 0.99f) drawerCard(c, r, volumeShape, 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.save();
            c.translate(r.left, r.top);
            c.drawPath(volumeShape.of(r.width(), r.height(), rr), fill);
            c.restore();
        }
        int ink = blend(tone(L_INK, 0xF2FFFFFF), INK, f);
        float pad = 22 * k, size = 30 * k, cy = r.centerY();
        Symbols.draw(getContext(), c, "volume", r.left + pad + size / 2, cy, size, ink);
        float x0 = r.left + pad + size + 22 * k, x1 = r.right - pad - 8 * k, th = (8 + 4 * f) * k;
        fill.setShader(null);
        fill.setColor(blend(tone(0x2E000000, 0x38FFFFFF), 0x26000000, f));
        tmp.set(x0, cy - th / 2, x1, cy + th / 2);
        c.drawRoundRect(tmp, th / 2, th / 2, fill);
        float lv = Ui.clamp(volumeLevel.value);
        if (lv > 0.001f) {
            fill.setColor(ink);
            tmp.set(x0, cy - th / 2, x0 + Math.max(th, (x1 - x0) * lv), cy + th / 2);
            c.drawRoundRect(tmp, th / 2, th / 2, fill);
        }
        c.restore();
    }

    /**
     * While Control Center opens, its rows come in 25 ms apart (fading in and sliding 24 px from the right): row `row`
     * in a layer of its own until it's in. Returns the save count to restore, or -1 when the row is already in.
     */
    private int rowIn(Canvas c, int row, long since, float l, float t, float r, float b) {
        if (drawer.target < 0.5f || !Ui.SMALL_MOTION || Spring.instant) return -1;
        float u = Ui.clamp((since - CC_ROW_MS * row) / (float) CC_ROW_IN_MS);
        if (u >= 1) return -1;
        float e = 1 - (1 - u) * (1 - u) * (1 - u);   // ease out
        int save = c.saveLayerAlpha(l - 40 * k, t - 40 * k, r + 40 * k, b + 40 * k, Math.round(255 * e));
        c.translate(24 * k * (1 - e), 0);
        return save;
    }

    /** The profiles: avatars with names; the current one ringed; the focused one grows and turns its ring white. */
    private void drawProfiles(Canvas c) {
        int current = ProfileManager.currentIndex(getContext());
        ccName.setColor(drawerInk(0.9f));
        for (int i = 0; i < profileRects.length && i < profileAvatars.length; i++) {
            RectF r = profileRects[i];
            float f = Ui.clamp(profileFocus[i].value), s = 1 + 0.1f * profileFocus[i].value - PRESS * press.value * f;
            float rad = r.width() * ProfileManager.CORNER;
            c.save();
            c.translate(bumpX * f, bumpY * f);
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
                c.drawRoundRect(r, rad, rad, fill);
            }
            if (i == current || f > 0.01f) {
                rim.setShader(null);
                rim.setColor(Ui.alpha(0xFFFFFFFF, i == current ? Math.max(0.85f, f) : f));
                rim.setStrokeWidth((3 + f) * k);
                c.drawRoundRect(r.left - 5 * k, r.top - 5 * k, r.right + 5 * k, r.bottom + 5 * k, rad + 5 * k, rad + 5 * k, rim);
                rim.setStrokeWidth(2.5f * k);
            }
            c.restore();
            if (profileNames[i] != null) c.drawText(profileNames[i], r.centerX(), r.bottom + (30 + 8 * f) * k, ccName);
        }
    }

    /**
     * A capsule card: the symbol on a round plate at the left (coloured when the toggle is on: Wi-Fi and Bluetooth blue,
     * Do Not Disturb indigo, as tvOS's), the name beside it and its state under the name. Focused: white with ink, 4 %
     * larger (the canvas scales it: the text and symbol keep their size).
     */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.04f * drawerFocus[i].value - PRESS * press.value * f;
        RectF base = drawerRects[i];
        float r = Math.min(CC_CARD_RADIUS * k, base.height() / 2);
        c.save();
        c.translate(bumpX * f, bumpY * f);
        c.scale(s, s, base.centerX(), base.centerY());
        if (f > 0.01f) {
            tmp.set(base);
            tmp.offset(0, 12 * k * f);
            shadowSoft.setColor(Ui.alpha(0xFF000000, 0.32f * f));
            shadows.draw(c, tmp, r, KEY_BLUR * k, shadowSoft);
        }
        if (f < 0.99f) drawerCard(c, base, cardShape, r, 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.save();
            c.translate(base.left, base.top);
            c.drawPath(cardShape.of(base.width(), base.height(), r), fill);
            c.restore();
        }
        boolean lit = ccLit(it);
        int ink = blend(tone(L_INK, 0xF2FFFFFF), INK, f);
        float plate = CC_PLATE * k, px = base.left + 12 * k + plate / 2, cy = base.centerY();
        fill.setShader(null);
        fill.setColor(lit ? (it.kind == HomeApps.DND ? 0xFF5E5CE6 : 0xFF0A84FF) : blend(tone(0x14000000, 0x24FFFFFF), 0x14000000, f));
        c.drawCircle(px, cy, plate / 2, fill);
        long pop = SystemClock.uptimeMillis() - dndAt;
        if (it.kind == HomeApps.DND && lit && pop < DND_POP_MS && !Spring.instant) {
            // An overshooting ease (cubic-bezier 0.34, 1.56, 0.64, 1, as the web's moonin): rotated in and grown to size.
            float t = pop / (float) DND_POP_MS, e = 1 + 2.70158f * (float) Math.pow(t - 1, 3) + 1.70158f * (float) Math.pow(t - 1, 2);
            c.save();
            c.rotate(-40 * (1 - e), px, cy);
            float sc = 0.55f + 0.45f * e;
            c.scale(sc, sc, px, cy);
            Symbols.draw(getContext(), c, it.symbol, px, cy, CC_SYMBOL * k, 0xFFFFFFFF);
            c.restore();
        } else {
            Symbols.draw(getContext(), c, it.symbol, px, cy, CC_SYMBOL * k, lit ? 0xFFFFFFFF : ink);
        }
        float tx = base.left + CC_TEXT_X * k;
        ccLabel.setColor(ink);
        boolean detail = i < drawerDetails.length && drawerDetails[i] != null;
        if (i < drawerLabels.length && drawerLabels[i] != null) {
            c.drawText(drawerLabels[i], tx, detail ? cy - 3 * k : cy + Ui.halfCap(ccLabel), ccLabel);
        }
        if (detail) {
            ccDetail.setColor(blend(tone(L_DIM, 0xADFFFFFF), 0x991C1C1E, f));
            c.drawText(drawerDetails[i], tx, cy + 20 * k, ccDetail);
        }
        c.restore();
    }

    private final Shape mediaShape = new Shape();
    private final android.graphics.Path mediaGlyph = new android.graphics.Path();
    private String mediaLine, mediaSub;

    /** The card's title and detail cut to fit beside its buttons, once (never while drawing). */
    private void fitMedia(float body) {
        if (ccLabel == null || ccDetail == null || playing == null) return;
        float room = body - (24 + 3 * CC_MEDIA_SLOT + 24) * k;
        String t = playing.mediaTitle, d = playing.mediaDetail;
        mediaLine = t == null ? null : TextUtils.ellipsize(t, new TextPaint(ccLabel), room, TextUtils.TruncateAt.END).toString();
        mediaSub = d == null || d.isEmpty() ? null : TextUtils.ellipsize(d, new TextPaint(ccDetail), room, TextUtils.TruncateAt.END).toString();
    }

    /** What's playing and ⏮ ⏯ ⏭, the skips dimmed where the player has none: OK plays or pauses, ← → skip, as the volume's row. */
    private void drawCcMedia(Canvas c, int i) {
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.02f * drawerFocus[i].value - PRESS * press.value * f;
        RectF base = drawerRects[i];
        float r = CC_CARD_RADIUS * k;
        c.save();
        c.translate(bumpX * f, bumpY * f);
        c.scale(s, s, base.centerX(), base.centerY());
        if (f > 0.01f) {
            tmp.set(base);
            tmp.offset(0, 12 * k * f);
            shadowSoft.setColor(Ui.alpha(0xFF000000, 0.32f * f));
            shadows.draw(c, tmp, r, KEY_BLUR * k, shadowSoft);
        }
        if (f < 0.99f) drawerCard(c, base, mediaShape, r, 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.save();
            c.translate(base.left, base.top);
            c.drawPath(mediaShape.of(base.width(), base.height(), r), fill);
            c.restore();
        }
        int ink = blend(tone(L_INK, 0xF2FFFFFF), INK, f);
        float cy = base.centerY(), tx = base.left + 24 * k;
        ccLabel.setColor(ink);
        if (mediaLine != null) c.drawText(mediaLine, tx, mediaSub != null ? cy - 4 * k : cy + Ui.halfCap(ccLabel), ccLabel);
        if (mediaSub != null) {
            ccDetail.setColor(blend(tone(L_DIM, 0xADFFFFFF), 0x991C1C1E, f));
            c.drawText(mediaSub, tx, cy + 20 * k, ccDetail);
        }
        float slot = CC_MEDIA_SLOT * k, next = base.right - 20 * k - slot / 2;
        fill.setShader(null);
        mediaSkip(c, next - 2 * slot, cy, -1, playing.canSkip(-1) ? ink : Ui.alpha(ink, 0.3f));
        fill.setColor(ink);
        mediaGlyph.reset();
        float g = k, mid = next - slot;
        if (playing.mediaPlaying) {
            c.drawRect(mid - 8 * g, cy - 11 * g, mid - 2 * g, cy + 11 * g, fill);
            c.drawRect(mid + 2 * g, cy - 11 * g, mid + 8 * g, cy + 11 * g, fill);
        } else {
            mediaGlyph.moveTo(mid - 8 * g, cy - 12 * g);
            mediaGlyph.lineTo(mid - 8 * g, cy + 12 * g);
            mediaGlyph.lineTo(mid + 11 * g, cy);
            mediaGlyph.close();
            c.drawPath(mediaGlyph, fill);
        }
        mediaSkip(c, next, cy, 1, playing.canSkip(1) ? ink : Ui.alpha(ink, 0.3f));
        c.restore();
    }

    /** ⏭ (or ⏮, `dir` −1): a triangle and a bar. */
    private void mediaSkip(Canvas c, float x, float y, int dir, int color) {
        float g = k;
        fill.setColor(color);
        mediaGlyph.reset();
        mediaGlyph.moveTo(x - dir * 9 * g, y - 10 * g);
        mediaGlyph.lineTo(x - dir * 9 * g, y + 10 * g);
        mediaGlyph.lineTo(x + dir * 5 * g, y);
        mediaGlyph.close();
        c.drawPath(mediaGlyph, fill);
        float bar = x + dir * 7 * g;
        c.drawRect(Math.min(bar, bar + dir * 3 * g), y - 10 * g, Math.max(bar, bar + dir * 3 * g), y + 10 * g, fill);
    }

    /** The weather: the place, now (large), the condition and the day's high and low, and the next 4 hours or days. */
    private void drawWeatherCard(Canvas c, RectF r) {
        // Focusable: OK opens the forecast; focused it grows a little and a white ring lights around it.
        float f = Ui.clamp(weatherFocus.value), s = 1 + 0.02f * weatherFocus.value;
        float rr = CC_CARD_RADIUS * k;
        c.save();
        c.translate(bumpX * f, bumpY * f);
        c.scale(s, s, r.centerX(), r.centerY());
        if (f > 0.01f) {
            tmp.set(r);
            tmp.offset(0, 14 * k * f);
            shadowSoft.setColor(Ui.alpha(0xFF000000, 0.32f * f));
            shadows.draw(c, tmp, rr, KEY_BLUR * k, shadowSoft);
        }
        drawerCard(c, r, weatherShape, 1);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), weatherContent);
        else drawWeather(c, r);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, f));
            rim.setStrokeWidth(4 * k);
            c.save();
            c.translate(r.left, r.top);
            c.drawPath(weatherShape.of(r.width(), r.height(), rr), rim);
            c.restore();
            rim.setStrokeWidth(2.5f * k);
        }
        c.restore();
    }

    private final Consumer<Canvas> weatherContent = cc -> drawWeather(cc, weatherCard);

    /**
     * The weather platter (the profile panel's): the city, the temperature now, the sky and the day's high and low in
     * the left block; the next 4 hours (or days, with Forecast: Next days) in fixed columns to its right, each label once
     * (HomeData drops the clocks-back night's repeated hour).
     */
    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 22 * k;
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        if (platterCity != null) c.drawText(platterCity, x, r.top + 34 * k, cardDim);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            if (noWeather != null) c.drawText(noWeather, x, r.top + 90 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 36 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 72 * k, 36 * k, drawerInk(0.95f));
        ccTemp.setColor(drawerInk(1));
        c.drawText(nowTemp, x + sw + 10 * k - Ui.leftBearing(ccTemp, nowTemp), r.top + 92 * k, ccTemp);
        cardText.setColor(drawerInk(0.95f));
        if (platterSky != null) c.drawText(platterSky, x, r.top + 120 * k, cardText);
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        if (platterHighLow != null) c.drawText(platterHighLow, x, r.top + 142 * k, hourPaint);
        int n = 4;
        float x0 = r.left + 240 * k, col = 252 * k / n;
        int shown = textDays ? Math.min(n, wx.days.size()) : Math.min(n, wx.hours.size());
        for (int i = 0; i < shown; i++) {
            float cx = x0 + col * (i + 0.5f);
            ccNum.setColor(tone(L_DIM, 0xADFFFFFF));
            c.drawText(textDays ? dayText[i] : hourText[i], cx, r.top + 40 * k, ccNum);
            String sym = textDays ? Symbols.weather(wx.dayCode.get(i), true) : Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i));
            Symbols.draw(getContext(), c, sym, cx, r.top + 78 * k, 28 * k, drawerInk(0.95f));
            ccNum.setColor(drawerInk(0.95f));
            c.drawText(textDays ? dayMaxText[i] : hourTempText[i], cx, r.top + 124 * k, ccNum);
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
            platterCity = TextUtils.ellipsize(weatherTitle, new TextPaint(cardDim), 200 * k,
                    TextUtils.TruncateAt.END).toString();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            // The platter's lines, cut to its columns once (never while drawing).
            float col = 200 * k;
            platterSky = Double.isNaN(wx.temp) ? null : TextUtils.ellipsize(HomeData.condition(wx.code, wx.isDay),
                    new TextPaint(cardText), col, TextUtils.TruncateAt.END).toString();
            platterHighLow = Double.isNaN(wx.temp) ? null : TextUtils.ellipsize(HomeData.highLow(wx, "  "),
                    new TextPaint(hourPaint), col, TextUtils.TruncateAt.END).toString();
            noWeather = TextUtils.ellipsize(Ui.tr("No weather data right now."), new TextPaint(cardDim), col,
                    TextUtils.TruncateAt.END).toString();
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
    private long drawerCardsKey() {
        long key = data.weatherAt;
        key = key * 31 + data.pricesAt;
        key = key * 31 + Prefs.version;
        key = key * 31 + Ui.language.hashCode();
        key = key * 31 + Float.floatToIntBits(dark());
        return key * 31 + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private long key;
        private boolean drawn;

        void draw(Canvas c, RectF r, long key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                drawn = false;
            }
            if (!drawn || key != this.key) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
                drawn = true;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot();

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        click.focusAt(focusPan());   // the tick of this move plays where the focus lands
        revealShelf(snap);
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        // Moving: the cursor shows where things go, and the tiles' springs show what it's on (aimCursor), not the focus.
        boolean tilesChanged = !moving() && aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        int swayNow = !snap && !sheetOpen && zone == Z_SHELF ? shelfIndex : -1;
        if (swayNow >= 0 && swayNow != swayTile) {
            swayTile = swayNow;
            swayCell = -1;
            sway();
        } else if (swayNow < 0) {
            swayTile = -1;
        }
        // The focused Dock app's own Top Shelf (or the wallpaper); its photo shifts ±8 px against the focus.
        if (zone == Z_SHELF && !sheetOpen) {
            followShelf(!snap);
            int n = Math.max(1, shelf.size() - 1);
            parallax.target = shelf.size() < 2 || Spring.instant ? 0 : -8 * (2f * shelfIndex / n - 1);
            if (snap) parallax.snap(parallax.target);
        }
        syncBackgroundFocus(!snap);
        float fullWas = full.target;
        full.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (full.target != fullWas) motionDirty = true;
        if (full.target > fullWas) fullAt = SystemClock.uptimeMillis();   // the details' stagger starts now
        if (snap) full.snap(full.target);
        boolean onStatus = !sheetOpen && zone == Z_STATUS;
        boolean pillChanged = pillFocus.target != (onStatus && !onBubble && !onIsland ? 1 : 0)
                || bubbleFocus.target != (onStatus && onBubble ? 1 : 0);
        pillFocus.target = onStatus && !onBubble && !onIsland ? 1 : 0;
        bubbleFocus.target = onStatus && onBubble ? 1 : 0;
        aimIsland(snap);
        boolean onLive = onStatus && onIsland;
        if (islandFocused && !onLive) handler.post(islandCheck);   // left the song paused: it may go now
        islandFocused = onLive;
        if (snap) {
            pillFocus.snap(pillFocus.target);
            bubbleFocus.snap(bubbleFocus.target);
        }
        boolean statusChanged = pillChanged;
        boolean onCards = !sheetOpen && zone == Z_DRAWER && !ccOnProfiles && !ccOnVolume && !ccInputs;
        boolean drawerChanged = aim(drawerFocus, onCards ? drawerIndex : -1, snap)
                | aim(inputFocus, !sheetOpen && zone == Z_DRAWER && ccInputs ? inputIndex : -1, snap)
                | aim(profileFocus, !sheetOpen && zone == Z_DRAWER && ccOnProfiles ? profileIndex : -1, snap);
        float vf = !sheetOpen && zone == Z_DRAWER && ccOnVolume ? 1 : 0, wf = !sheetOpen && zone == WIDGETS && widgetIndex == 0 ? 1 : 0;
        drawerChanged |= volumeFocus.target != vf || weatherFocus.target != wf;
        volumeFocus.target = vf;
        weatherFocus.target = wf;
        if (snap) {
            volumeFocus.snap(vf);
            weatherFocus.snap(wf);
        }
        if (onCards && drawerIndex < drawerRects.length) {
            // The focused card in view: the cards scroll under the profiles and above the volume (only when they don't fit).
            RectF fr = drawerRects[drawerIndex];
            float want = ccScroll.target;
            if (fr.top - want < cardsArea.top) want = fr.top - cardsArea.top;
            if (fr.bottom - want > cardsArea.bottom) want = fr.bottom - cardsArea.bottom;
            ccScroll.target = Math.max(0, Math.min(ccScrollMax, want));
            if (snap) ccScroll.snap(ccScroll.target);
        }
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
        if (Spring.instant || scrubbing) return;   // animations off, or the arrow held down: no tilt
        // An impulse toward where the focus came from (TILT peaks at ~1, so 3°, and settles flat); a tilt already
        // under way carries on and bends.
        swayX.target = 0;
        swayY.target = 0;
        swayX.impulse(lastDx * SWAY_KICK);
        swayY.impulse(lastDy * SWAY_KICK);
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
        list = HomeApps.folders(list);   // the TV's utilities in one folder at the end
        List<String> dock = new ArrayList<>();
        for (HomeApps.Item it : shelf) dock.add(dockKey(it));
        AppLayout layout = Prefs.layout();
        list = HomeApps.arrange(list, layout, dock);   // as this profile arranged it
        noteNewApps(list, layout);
        others = list;
        gridFocus = new Spring[list.size()];
        gridSlideX = new Spring[list.size()];
        gridSlideY = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i] = new Spring(scrubbing ? Spring.FAST_FOCUS : Spring.SNAPPY_FOCUS, 0);
            gridSlideX[i] = new Spring(0.32f, 0.86f, 0).px();
            gridSlideY[i] = new Spring(0.32f, 0.86f, 0).px();
        }
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = motionDirty = true;   // the grid's first row peeks under the Dock once there are apps
    }

    /**
     * Apps that weren't in All Apps before (installed from the Play Store, or off the Dock): arrange put them at its end;
     * the order is kept with them from now on, and a note says where they are. The first time, the order as it is now
     * is kept (no note), so every app installed from then on goes at the end, as on Apple TV.
     */
    private void noteNewApps(List<HomeApps.Item> list, AppLayout layout) {
        java.util.Set<String> known = new java.util.HashSet<>(layout.grid);
        List<HomeApps.Item> fresh = new ArrayList<>();
        boolean changed = false;
        for (HomeApps.Item it : list) {
            if (known.contains(it.key)) continue;
            changed = true;
            if (it.kind == HomeApps.APP) fresh.add(it);
        }
        if (!changed) return;
        boolean first = layout.grid.isEmpty();
        layout.grid.clear();
        for (HomeApps.Item it : list) layout.grid.add(it.key);
        Prefs.setLayout(layout);
        if (first || fresh.isEmpty()) return;
        showToast(fresh.size() == 1 ? Ui.trf("{app} is now in All Apps, at the end", "app", fresh.get(0).label)
                : Ui.plural("plural|one:{n} new app is in All Apps, at the end|other:{n} new apps are in All Apps, at the end", fresh.size()));
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

    /** Where the focus is across the screen (0 left … 1 right): the focus tick is panned there. */
    private float focusPan() {
        if (w <= 0) return 0.5f;
        RectF r = null;
        if (folderOpen != null) {
            r = folderIndex >= 0 && folderIndex < folderCells.length ? folderCells[folderIndex] : folderBadge;
        } else if (sheetOpen) {
            return (gridIndex % COLUMNS + 0.5f) / COLUMNS;
        } else if (zone == Z_SHELF) {
            r = shelfIndex < tileRects.length ? tileRects[shelfIndex] : null;
        } else if (zone == Z_TOP) {
            r = topIndex < topRects.length ? topRects[topIndex] : null;
        } else if (zone == Z_CARDS) {
            r = cardIndex < cardRects.length ? cardRects[cardIndex] : null;
        } else if (zone == Z_HERO) {
            r = heroButton;
        } else if (zone == Z_STATUS) {
            r = onIsland ? islandNow : onBubble ? bubbleRect : pillRect;
        } else if (zone == Z_DRAWER) {
            r = ccInputs ? (inputIndex < inputRects.length ? inputRects[inputIndex] : null)
                    : drawerIndex < drawerRects.length ? drawerRects[drawerIndex] : null;
        }
        return r == null || r.isEmpty() ? 0.5f : r.centerX() / w;
    }

    private void openSheet() {
        openSheet(0);
    }

    /** The app grid, with the focus on `index` (↓ from the Dock: the app under the focused one). */
    private void openSheet(int index) {
        if (!sheetOpen) click.sheet(true, 0.5f);
        sheetOpen = true;
        gridIndex = Math.max(0, Math.min(index, others.size() - 1));
        scroll.snap(0);
        for (Spring f : gridFocus) f.snap(0);
        retargetGrid(false);   // the focused app grows as the grid comes up
        retarget(false);
        sheet.target = 1;
        gridDirty = motionDirty = true;
    }

    private void closeSheet() {
        if (sheetOpen) click.sheet(false, 0.5f);
        sheetOpen = false;
        sheet.target = 0;
        retargetGrid(false);   // the focused app shrinks as the grid goes down
        motionDirty = true;
        retarget(false);
        if (appsStale && board == null) reload();   // the grid was kept as it was while open: its new lists now
    }

    private void retargetGrid(boolean snap) {
        if (!snap && sheetOpen && swayCell != gridIndex) {
            swayCell = gridIndex;
            swayTile = -1;
            sway();
        }
        for (int i = 0; i < gridFocus.length && !moving(); i++) {
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

    private boolean drawingGrid;

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
        drawingGrid = true;
        for (int i = 0; i < n; i++) if (i != top) drawGridTile(c, i, shift - rowLag(i / COLUMNS));
        if (top >= 0) drawGridTile(c, top, shift - rowLag(top / COLUMNS));
        drawingGrid = false;
        if (folderShown.value > 0.004f) drawFolder(c);
    }

    // MARK: - Folders: a frosted squircle with four of its apps in All Apps; OK zooms it open over the grid

    // As tvOS 9.2's folders: 3 rows in view, the rest a scroll away (the apps slide; the card stays put).
    private static final int FOLDER_ROWS = 3;
    /** AppGrid.folderColumns: as many across as keep its rows even. */
    private int FOLDER_COLUMNS = 3;
    private final Spring folderScroll = new Spring(Spring.SPATIAL_SCROLL, 0).px();
    private int folderTop;
    private static final float FOLDER_GAP = 40, FOLDER_PAD = 60, FOLDER_RADIUS = 40;
    private static final float FOLDER_ROW = GRID_H + 84;   // room for a focused app's name above the next row
    private final Paint folderRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private HomeApps.Item folderOpen;
    private int folderIndex;
    private final Spring folderShown = new Spring(Spring.SHEET_OPEN, 0), badgeFocus = new Spring(Spring.SNAPPY_FOCUS, 0);
    private Spring[] folderFocus = new Spring[0];
    private RectF[] folderCells = new RectF[0];
    private String[] folderLabels = new String[0];
    private final RectF folderCard = new RectF(), folderFrom = new RectF(), folderBadge = new RectF(), miniRect = new RectF();
    private final Shape folderShape = new Shape();
    private final android.graphics.Matrix miniMatrix = new android.graphics.Matrix();
    private float folderBadgeW;

    /** The folder's tile: frosted glass (the panel's fill and rim), its first four apps as a 2×2 of little tiles. */
    private void drawFolderTile(Canvas c, HomeApps.Item it, RectF base, float f) {
        float fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f - TILE_PRESS * press.value * fc;
        float cx = base.centerX() + bumpX * fc, cy = base.centerY() - TV_FOCUS_LIFT * k * f + bumpY * fc;
        float bw = base.width(), bh = base.height(), r0 = TV_TILE_RADIUS * k;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        if (fc > 0.01f) {
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tmp.offset(0, 14 * k * fc);
            shadowSoft.setColor(Ui.alpha(0xFF000000, 0.3f * fc));
            shadows.draw(c, tmp, r0, KEY_BLUR * k, shadowSoft);
        }
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        drawerCard(c, tmp, folderShape, r0, 1);
        if (it.children != null) {
            float mw = (bw - 2 * 18 * k - 12 * k) / 2, mh = (bh - 2 * 14 * k - 10 * k) / 2;
            for (int i = 0; i < Math.min(4, it.children.size()); i++) {
                float mx = -bw / 2 + 18 * k + (i % 2) * (mw + 12 * k), my = -bh / 2 + 14 * k + (i / 2) * (mh + 10 * k);
                miniRect.set(mx, my, mx + mw, my + mh);
                drawMini(c, it.children.get(i), miniRect);
            }
        }
        c.restore();
    }

    /** An app's card, small and flat (the folder's previews): its tile image, or its colour with its symbol. */
    private void drawMini(Canvas c, HomeApps.Item it, RectF r) {
        float rr = 10 * k;
        if (it.tile != null && it.tileShader != null) {
            miniMatrix.setScale(r.width() / it.tile.getWidth(), r.height() / it.tile.getHeight());
            miniMatrix.postTranslate(r.left, r.top);
            it.tileShader.setLocalMatrix(miniMatrix);
            imageFill.setShader(it.tileShader);
            c.drawRoundRect(r, rr, rr, imageFill);
            imageFill.setShader(null);
            it.tileShader.setLocalMatrix(null);
        } else {
            fill.setShader(null);
            fill.setColor(it.style != null ? it.style[1] : 0xFF48484A);
            c.drawRoundRect(r, rr, rr, fill);
            if (it.logo != null && it.style != null) {   // a composed icon (the Apple guides): its emblem at half height
                float eh = r.height() * 0.5f, ew = eh * it.logo.getWidth() / it.logo.getHeight();
                miniDst.set(r.centerX() - ew / 2, r.centerY() - eh / 2, r.centerX() + ew / 2, r.centerY() + eh / 2);
                c.drawBitmap(it.logo, null, miniDst, bitmapPaint);
            }
        }
    }

    /** OK on a folder: its apps in a floating frosted card, springing out of the folder's tile. */
    private void openFolder(HomeApps.Item folder, RectF from) {
        click.sheet(true, 0.5f);
        folderOpen = folder;
        folderIndex = 0;
        FOLDER_COLUMNS = AppGrid.folderColumns(folder.children.size());
        int n = folder.children.size(), cols = Math.min(FOLDER_COLUMNS, n), rows = Math.min(FOLDER_ROWS, (n + FOLDER_COLUMNS - 1) / FOLDER_COLUMNS);
        folderTop = 0;
        folderScroll.snap(0);
        float cw = (2 * FOLDER_PAD + cols * GRID_W + (cols - 1) * FOLDER_GAP) * k, ch = (156 + rows * FOLDER_ROW + 24) * k;
        folderCard.set((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
        snap(folderCard);
        folderFrom.set(from);
        folderFocus = new Spring[n];
        folderCells = new RectF[n];
        folderLabels = new String[n];
        for (int i = 0; i < n; i++) {
            folderFocus[i] = new Spring(Spring.SNAPPY_FOCUS, 0);
            float x = folderCard.left + (FOLDER_PAD + (i % FOLDER_COLUMNS) * (GRID_W + FOLDER_GAP)) * k;
            float y = folderCard.top + (132 + (i / FOLDER_COLUMNS) * FOLDER_ROW) * k;
            folderCells[i] = new RectF(x, y, x + GRID_W * k, y + GRID_H * k);
            folderLabels[i] = TextUtils.ellipsize(folder.children.get(i).label, gridLabel, GRID_W * 1.3f * k, TextUtils.TruncateAt.END).toString();
        }
        folderBadgeW = ccLabel.measureText(folder.label) + 60 * k;
        folderBadge.set(folderCard.centerX() - folderBadgeW / 2, folderCard.top + 34 * k, folderCard.centerX() + folderBadgeW / 2, folderCard.top + 86 * k);
        folderFocus[0].target = 1;
        badgeFocus.snap(0);
        folderShown.tune(Spring.SHEET_OPEN);
        folderShown.target = 1;
        gridDirty = motionDirty = true;
        spoken.focusMoved();
    }

    /** Back (or an app opening): the card zooms back into its tile, the focus on the folder. */
    private void closeFolder() {
        if (folderOpen == null) return;
        click.sheet(false, 0.5f);
        folderOpen = null;
        folderShown.tune(Spring.SHEET_CLOSE);
        folderShown.target = 0;
        gridDirty = motionDirty = true;
        spoken.focusMoved();
    }

    private void moveFolder(int dx, int dy) {
        // Above the first row, the folder's name (OK renames it); from there, ↓ comes back to the apps.
        int next = folderIndex < 0 ? (dy > 0 ? 0 : -2)
                : AppGrid.folderMove(folderIndex, dx, dy, folderOpen.children.size(), FOLDER_COLUMNS);
        if (next == -1 && dy < 0 && folderIndex / FOLDER_COLUMNS == 0) {
            folderIndex = -1;
            badgeFocus.target = 1;
            for (Spring f : folderFocus) f.target = 0;
            gridDirty = true;
            click.move();
            announceForAccessibility(Ui.trf("{folder}. OK to rename it", "folder", folderOpen.label));
            return;
        }
        if (next < 0) {
            deadEnd(dx, dy);
            return;
        }
        badgeFocus.target = 0;
        folderIndex = next;
        int row = next / FOLDER_COLUMNS;
        if (row < folderTop) folderTop = row;
        if (row > folderTop + FOLDER_ROWS - 1) folderTop = row - FOLDER_ROWS + 1;
        folderScroll.target = folderTop * FOLDER_ROW * k;
        for (int i = 0; i < folderFocus.length; i++) folderFocus[i].target = i == folderIndex ? 1 : 0;
        gridDirty = true;
        click.move();
        spoken.focusMoved();
    }

    /** The open folder: the grid dims, the card springs from the folder's tile, the white badge, its apps in even rows (3 in view, then they scroll). */
    private void drawFolder(Canvas c) {
        float e = Ui.clamp(folderShown.value);
        fill.setShader(null);
        fill.setColor(Ui.alpha(dark() > 0.5f ? 0xFF000000 : 0xFF162130, (dark() > 0.5f ? 0.5f : 0.4f) * e));
        c.drawRect(0, 0, w, h, fill);
        c.save();
        float z = 0.3f + 0.7f * e;
        c.translate((folderFrom.centerX() - folderCard.centerX()) * (1 - e), (folderFrom.centerY() - folderCard.centerY()) * (1 - e));
        c.scale(z, z, folderCard.centerX(), folderCard.centerY());
        tmp.set(folderCard);
        tmp.offset(0, 30 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.32f * e));
        shadows.draw(c, tmp, FOLDER_RADIUS * k, SHADOW_BLUR * k, shadow);
        // A clean card, as on the web: at this opacity the glass only showed as a smudge along its edge.
        fill.setColor(Ui.alpha(dark() > 0.5f ? 0xFF282C36 : 0xFFF6F7FA, 0.985f * e));
        c.drawRoundRect(folderCard, FOLDER_RADIUS * k, FOLDER_RADIUS * k, fill);
        folderRim.setStyle(Paint.Style.STROKE);
        folderRim.setStrokeWidth(1.5f * k);
        folderRim.setColor(Ui.alpha(WHITE, (dark() > 0.5f ? 0.14f : 0.7f) * e));
        c.drawRoundRect(folderCard, FOLDER_RADIUS * k, FOLDER_RADIUS * k, folderRim);
        // The name's badge; focused (↑ from the first row), it lifts and, on the light card, turns dark: OK renames.
        float bf = Ui.clamp(badgeFocus.value), bs = 1 + 0.12f * bf;
        c.save();
        c.scale(bs, bs, folderBadge.centerX(), folderBadge.centerY());
        if (bf > 0.01f) {
            tmp.set(folderBadge);
            tmp.offset(0, 8 * k);
            shadowSoft.setColor(Ui.alpha(0xFF000000, 0.3f * bf * e));
            shadows.draw(c, tmp, folderBadge.height() / 2, KEY_BLUR * k, shadowSoft);
        }
        int badge = blend(0xFFFFFFFF, tone(INK, 0xFFFFFFFF), bf);
        fill.setColor(Ui.alpha(badge, e));
        c.drawRoundRect(folderBadge, folderBadge.height() / 2, folderBadge.height() / 2, fill);
        ccLabel.setColor(Ui.alpha(blend(INK, tone(WHITE, INK), bf), e));
        c.drawText(folderOpen != null ? folderOpen.label : "", folderBadge.left + 30 * k, folderBadge.centerY() + Ui.halfCap(ccLabel), ccLabel);
        c.restore();
        if (folderOpen != null) {
            // The apps in their window (the rows in view, with room for a focused tile's lift and name), scrolled.
            c.save();
            c.clipRect(folderCard.left, folderCard.top + 92 * k, folderCard.right, folderCard.bottom);
            c.translate(0, -folderScroll.value);
            int top = folderIndex;
            for (int i = 0; i < folderCells.length; i++) if (i != top) drawAppTile(c, folderOpen.children.get(i), folderCells[i], folderFocus[i].value, 0, 0);
            if (top >= 0 && top < folderCells.length) {
                drawAppTile(c, folderOpen.children.get(top), folderCells[top], folderFocus[top].value, 0, 0);
                float fc = Ui.clamp(folderFocus[top].value);
                gridLabel.setColor(Ui.alpha(tone(L_INK, WHITE), fc * e));
                RectF r = folderCells[top];
                float bottom = r.bottom + r.height() * TV_FOCUS_HALF * fc - TV_FOCUS_LIFT * k * fc;
                c.drawText(folderLabels[top], r.centerX(), bottom + 22 * k + nameCap, gridLabel);
            }
            c.restore();
        }
        c.restore();
    }

    /** How far row `r` trails the grid's lift (each row 18 ms behind the one above, while it moves). */
    private float rowLag(int r) {
        if (liftCount == 0 || r == 0 || !Ui.SMALL_MOTION) return 0;
        long now = liftAt[liftHead];
        return (sheet.value - liftAgo(now - ROW_LAG_MS * r)) * TV_GRID_LIFT * k;
    }

    private void drawGridTile(Canvas c, int i, float shift) {
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        int r = i / COLUMNS, col = i % COLUMNS;
        float y = GRID_TOP * k + r * GRID_ROW * k - shift + slide(gridSlideY, i);
        if (y > h || y + GRID_ROW * k < 0) return;
        float x = left + col * (GRID_W + GRID_GAP) * k + slide(gridSlideX, i);
        gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
        HomeApps.Item it = others.get(i);
        if (moveState == LANDING && it == landing && !landFade) return;   // the carried tile is on its way here
        float f = gridFocus[i].value, fc = Ui.clamp(f);
        // Moving: an outlined app shrinks into the folder-to-be (its focus below 0), drawn behind it.
        if (f < -0.01f && it.kind != HomeApps.FOLDER) drawForming(c, gridCell, Ui.clamp(f / HOVER_FOLDER));
        if (others.get(i).kind == HomeApps.FOLDER) drawFolderTile(c, others.get(i), gridCell, f);
        else drawAppTile(c, others.get(i), gridCell, f, 0, launchZone == LAUNCH_GRID && i == launchIndex && !launchUp ? launch.value : 0);
        if (sheetOpen && i == gridIndex) {
            float s = 1 + FOCUS_GROW, cx = gridCell.centerX(), cy = gridCell.centerY() - TV_FOCUS_LIFT * k;
            gridFocusCell.set(cx - gridCell.width() * s / 2, cy - gridCell.height() * s / 2, cx + gridCell.width() * s / 2, cy + gridCell.height() * s / 2);
        }
        // Only the focused app's name, centred under it, as on tvOS: it rises 6 px into place with the focus, and
        // leaves fast (~120 ms) so two names never overlap while scrubbing; none while an arrow is held.
        float la = (gridFocus[i].target > 0.5f ? fc : Ui.clamp(2 * fc - 1)) * (1 - Ui.clamp(scrub.value));
        if (la <= 0.01f) return;
        gridLabel.setColor(Ui.alpha(tone(L_INK, WHITE), la));
        float bottom = gridCell.bottom + gridCell.height() * TV_FOCUS_HALF * f - TV_FOCUS_LIFT * k * f + 6 * k * (1 - fc);
        c.drawText(gridLabels[i], gridCell.centerX() + bumpX * fc, bottom + 22 * k + nameCap + bumpY * fc, gridLabel);
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
        float a = Spring.instant ? (age < TOAST_MS / 1000f ? 1 : 0) : Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 124 * k - (1 - a) * 16 * k;   // under the tab bar and the clock, never over them
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
            final List<HomeApps.Item> in = HomeApps.allInputs(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons() : null;   // they change with Prefs too
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                if (s != null && board == null) setShelf(s);
                else if (s != null) appsStale = true;   // moving: the lists come when it's done
                setStatusItems(st);
                setDrawerItems(d);
                setInputItems(in);
                if (o != null && !sheetOpen && board == null) setOthers(o);
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

    // MARK: - Profiles: the intro, the lock, and the home made again for whoever comes in

    /** Locks and shows the gate: the intro first (`withIntro`, when Settings has it on), then "Who's Watching?". */
    private void gate(boolean withIntro) {
        gate(withIntro, false);
    }

    /** `preview`: the intro plays even with Startup animation off (Settings → Preview Startup Animation). */
    private void gate(boolean withIntro, boolean preview) {
        ProfileManager.lock(getContext());
        if (sheetOpen) closeSheet();
        weatherOverlay.hide();     // the next profile starts on its own home, not on the last one's forecast
        recentsOverlay.hide();
        if (withIntro && (preview || Prefs.intro())) {
            intro.start();
        } else {
            intro.stop();
            if (withIntro && !Prefs.setupDone()) welcome.show(true);   // no startup: the welcome setup, then the lock
            else lock.show(false, null);
        }
    }

    /** Someone is using the TV: noted for resumeSignedIn (written at most every 30 s). */
    private void touch() {
        long up = SystemClock.uptimeMillis();
        if (touchedAt != 0 && up - touchedAt < 30_000) return;
        touchedAt = up;
        ProfileManager.touch(getContext());
    }

    /**
     * The home for the profile now in: its Dock and top buttons (reload: Prefs.version moved), theme, wallpaper and
     * avatar, here and now instead of HomeActivity's recreate; then the TV tab unfolds from 0.98.
     */
    private void applyProfile() {
        if (activity instanceof HomeActivity) ((HomeActivity) activity).profileApplied();
        touchedAt = 0;
        reload();
        invalidateProfileBackground();
        setLightButtons(HomeApps.topButtons());
        checkNight(true);
        layoutPill();
        reset(false);
        if (!Spring.instant) {
            wakeScale.tune(Spring.TITLE_SETTLE);
            wakeScale.snap(0.98f);
            wakeScale.target = 1;
            wakeAlpha.snap(0);
            wakeAlpha.target = 1;
        }
        heroDirty = statusDirty = drawerDirty = tilesDirty = true;
        motionDirty = true;
        invalidateAll();
        wake();
    }

    private final LockView.Listener lockListener = new LockView.Listener() {
        @Override public void onUnlock(ProfileManager.Profile p) {
            applyProfile();
        }

        @Override public void pillTarget(RectF out) {
            float d = PILL_AVATAR * k, ax = pillRect.right - 4 * k - d, ay = pillRect.centerY() - d / 2;
            out.set(ax, ay, ax + d, ay + d);
        }

        @Override public void onLockGone() {
            wake();
            invalidateAll();
        }

        @Override public void onLockCovers(boolean covers) {
            lockCovers = covers;
            invalidate();
        }
    };

    // Under the opaque lock none of the home screen shows: still drawing it took "Who's Watching?" to 42 fps on a 2.45 GB TCL.
    private boolean lockCovers;

    @Override protected boolean drawChild(Canvas c, View child, long drawingTime) {
        // Not Home and Welly on LEGACY: out of the drawing they'd lose the layers that keep them ready.
        if (lockCovers && (child instanceof Layer || (!Ui.LEGACY && (child == house || child == welly)))) return false;
        return super.drawChild(c, child, drawingTime);
    }

    private final Intro.Listener introListener = new Intro.Listener() {
        @Override public void onIntroEnding() {
            if (Prefs.setupDone()) lock.show(true, null);
            else welcome.show(true);   // a new TV: the welcome setup first, then "Who's Watching?"
            intro.bringToFront();   // the lock comes in under the intro's dissolve
        }

        @Override public void onIntroDone() {
            if (!welcome.showing() && !lock.showing() && ProfileManager.needsLock(getContext())) lock.show(false, null);
        }
    };

    private final WelcomeView.Listener welcomeListener = new WelcomeView.Listener() {
        @Override public void onLook() {
            checkNight(false);
            updatePalette();
            invalidateAll();
        }

        @Override public void onDone(boolean fromBoot) {
            followShelf(false);   // the Top Shelf it picked
            if (fromBoot) lock.show(false, null);
            wake();
            invalidateAll();
        }
    };

    // MARK: - Volume (VolumeHud)

    /** The level after a volume key (RemoteKeys.adjustVolume): the HUD, and Control Center's slider with it. */
    private final BroadcastReceiver volumeChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!running) return;
            int top = Math.max(1, i.getIntExtra("max", 1)), level = Math.max(0, Math.min(top, i.getIntExtra("index", 0)));
            boolean muted = i.getBooleanExtra("muted", false);
            volumeMax = top;
            volumeIndex = level;
            volumeLevel.target = muted ? 0 : level / (float) top;
            drawerDirty = true;
            wake();
            int percent = muted ? 0 : Math.round(100f * level / top);
            volumeHud.show(percent, muted, i.getIntExtra("push", 0));
            AccessibilityManager a11y = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (a11y != null && a11y.isTouchExplorationEnabled()) {
                announceForAccessibility(Ui.trf("Volume, {n}%", "n", percent));
            }
        }
    };

    /**
     * The volume keys, where the system gives them to the app in front (not a TV's: those reach RemoteKeys' filter,
     * which does the same): one step on LOADER without the system's panel, then the HUD through ACTION_VOLUME. Not
     * when the volume is fixed (volumeAvailable), nor under the lock or the intro: the system's own panel there.
     */
    boolean volumeKey(KeyEvent e) {
        if (!RemoteKeys.isVolume(e.getKeyCode())) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            boolean ours = volumeKeyDown;
            volumeKeyDown = false;
            return ours;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (e.getRepeatCount() > 0 && !volumeKeyDown) return false;
        if (audio == null || !volumeAvailable || lock.showing() || intro.showing()) {
            volumeKeyDown = false;
            return false;
        }
        volumeKeyDown = true;
        if (e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_MUTE && e.getRepeatCount() > 0) return true;
        final int steps = RemoteKeys.heldSteps(e, volumeHeld);   // 1 a press, faster and faster while held
        if (steps == 0) return true;
        final int dir = RemoteKeys.volumeDirection(e.getKeyCode());
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> RemoteKeys.adjustVolume(c, dir, steps));
        return true;
    }

    /**
     * The channel keys, as a second pair of volume keys for the brightness (channels have no use on the home screen): a
     * press is 1 %, held it goes 5 % a step (RemoteKeys.heldSteps), and the HUD on the left shows it with the sun; past
     * either end it stretches.
     */
    private boolean brightnessKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int steps = RemoteKeys.heldSteps(e, brightHeld);
        if (steps > 0) brightnessBy((e.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1) * steps);
        return true;
    }

    /** The brightness by `delta` percent from the channel keys, with the HUD (stretching past either end). */
    private void brightnessBy(int delta) {
        if (!running || delta == 0) return;
        boolean moved = stepBrightness(delta);
        volumeHud.showBrightness(Prefs.brightness(), moved ? 0 : Integer.signum(delta));
        if (drawerOpen) {
            showAppearance();   // Control Center's slider follows
            layoutDetails();
        }
        wake();
    }

    /** RemoteKeys' filter took a channel key while the home screen is in front (onChannel). */
    private final BroadcastReceiver brightnessKeys = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            brightnessBy(i.getIntExtra("delta", 0));
        }
    };

    private final long[] brightHeld = new long[2];   // the held channel key's last step and press (RemoteKeys.heldSteps)

    /** Power: off locks at once (nothing of the home shows when it comes back on), on plays the intro and the lock. */
    private final BroadcastReceiver screenPower = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                intro.stop();
                ProfileManager.lock(c);
                lock.show(false, null);
                introPending = true;
            } else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                if (intro.showing()) {   // resume() came first and already started it
                    introPending = false;
                    return;
                }
                introPending = true;
                if (running) {
                    introPending = false;
                    gate(true);
                }
            }
        }
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        getContext().registerReceiver(screenPower, f);
        // Only from this app (RemoteKeys, in the key filter's process); Android 13 and newer can say so.
        getContext().registerReceiver(volumeChanged, new IntentFilter(RemoteKeys.ACTION_VOLUME),
                android.os.Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        getContext().registerReceiver(brightnessKeys, new IntentFilter(RemoteKeys.ACTION_BRIGHTNESS),
                android.os.Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        // Focus: Do Not Disturb turned on or off, here or anywhere (a system broadcast).
        getContext().registerReceiver(dndChanged, new IntentFilter(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED));
        readDnd();
    }

    @Override protected void onDetachedFromWindow() {
        for (BroadcastReceiver r : new BroadcastReceiver[] {screenPower, volumeChanged, dndChanged, brightnessKeys}) {
            try {
                getContext().unregisterReceiver(r);
            } catch (IllegalArgumentException ignored) {
            }
        }
        super.onDetachedFromWindow();
    }

    // MARK: - Keys: only the arrows, OK, Back and Settings (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        if (intro.showing()) return intro.onKey(e);
        if (welcome.showing()) return welcome.onKey(e);
        if (lock.showing()) return lock.onKey(e);
        if (appLock.showing()) return appLock.onKey(e);
        touch();
        if (weatherOverlay.showing()) return weatherOverlay.onKey(e);
        if (recentsOverlay.showing()) return recentsOverlay.onKey(e);
        if (e.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_UP || e.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_DOWN) return brightnessKey(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (launchUp && !launchBack) return launchKey(e, down);
        if (naming) return namingKey(e, down);
        if (board != null) return moveKey(e, down);
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
        boolean arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (arrow) setScrubbing(down && e.getRepeatCount() > 0);   // held down: fast scrub until it's let go
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
                        press.target = 1;   // the focused thing presses in (MICRO_PRESS) on the frame the key goes down
                        wake();
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = canMove() || canOpenFromIsland();
                        if (holdArmed) {
                            handler.postDelayed(holdStart, HOLD_MS);
                            click.holdStart();   // the tension builds toward the hold, from the frame OK goes down
                        }
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    press.target = 0;
                    if (holdArmed && !okHeld) click.holdCancel();   // let go before the hold: the tension eases off
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
                if (RemoteKeys.HISENSE || RemoteKeys.TCL) return false;
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
        if (folderOpen != null) {
            closeFolder();
        } else if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_HOUSE || zone == Z_WELLY) {
            focusTabBar();      // Back in a tab's content: the tab bar
            retarget(false);
        } else if (tab != TAB_TV && (zone == Z_TOP || zone == Z_STATUS)) {
            selectTab(TAB_TV);  // Back on the bar: the TV tab
            zone = Z_TOP;
            topIndex = TAB_TV;
            retarget(false);
        } else if (zone == Z_DRAWER && ccInputs) {
            closeInputs();   // Back in the Inputs list: the cards
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
        if (folderOpen != null) {
            moveFolder(dx, dy);
            return;
        }
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) {
                        deadEnd(dx, 0);
                        return;
                    }
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        deadEnd(0, dy);
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) {
                        deadEnd(0, dy);
                        return;
                    }
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) {
                        deadEnd(dx, 0);
                        return;
                    }
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) {
                        deadEnd(0, dy);
                        return;
                    }
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) {
                        deadEnd(dx, 0);
                        return;
                    }
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    deadEnd(0, dy);
                    return;
                }
                break;
            case WIDGETS: {
                // The weather platter: the nearest stop that way (the profiles ↑, Audio Output →, the cluster ↓).
                if (!ccStop(ccNearest(weatherCard, dx, dy, -1))) {
                    if (dx < 0) {
                        closeDrawer();
                    } else {
                        deadEnd(dx, dy);
                        return;
                    }
                }
                break;
            }
            default: {
                // The panel: the nearest card that way (spatial: the pills, the volume, the cells), else the weather or a
                // profile; ← → on the volume change it; ← with nothing there closes the panel.
                if (drawerItems.isEmpty()) return;
                HomeApps.Item cur = drawerItems.get(drawerIndex);
                if (ccShape(cur.kind) == CC_MEDIA && dx != 0) {
                    if (playing.skip(dx)) click.move();
                    else deadEnd(dx, 0);
                    wake();
                    return;
                }
                if (ccShape(cur.kind) == CC_SLIDER && dx != 0) {
                    boolean moved = stepVolume(dx);
                    if (!moved) deadEnd(dx, 0);
                    else click.move();
                    drawerDirty = true;
                    wake();
                    return;
                }
                if (!ccStop(ccNearest(drawerRects[drawerIndex], dx, dy, drawerIndex))) {
                    if (dx < 0) {
                        closeDrawer();
                    } else {
                        deadEnd(dx, dy);
                        return;
                    }
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
        if (next == gridIndex) {
            deadEnd(dx, dy);
            return;
        }
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    /** How long Play waits for a title's page before opening its app's home instead. */
    private static final long PLAY_WAIT_MS = 2000;
    private Recs.Pick lookingFor, looked;
    private final Runnable playLooked = new Runnable() {
        @Override public void run() {
            handler.removeCallbacks(this);
            Recs.Pick p = lookingFor;
            lookingFor = null;
            if (p == null || !running || sheetOpen || zone != Z_HERO || currentPick() != p) return;   // they moved on
            looked = p;
            activate();
        }
    };

    /** True: open it now. False: its page is being looked up, and activate() comes again when that's done. */
    private boolean playNow(Recs.Pick p) {
        if (looked == p) return true;
        if (lookingFor != null) return false;   // a second press while it looks
        if (!recs.canLookUpLink(p)) return true;
        click.open();
        lookingFor = p;
        handler.postDelayed(playLooked, PLAY_WAIT_MS);
        recs.lookUpLink(p, () -> {
            if (lookingFor == p) playLooked.run();
        });
        return false;
    }

    private void activate() {
        wake();
        if (naming) {   // TalkBack's click on the naming card: that name
            chooseName();
            return;
        }
        if (board != null) {   // TalkBack's click while moving: OK
            if (moveState == MOVING) commitMove();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) {
            if (widgetIndex != 0) return;   // the quotes are only read
            click.open();
            closeDrawer();
            retarget(false);
            weatherOverlay.show();
            return;
        }
        if (!sheetOpen && zone == Z_DRAWER && ccOnVolume) return;   // ←/→ move it; OK has nothing to do
        if (!sheetOpen && zone == Z_HOUSE) {
            click.open();
            house.toggle();
            return;
        }
        if (!sheetOpen && zone == Z_WELLY) return;   // Welly handles its own OK
        if (folderOpen != null && folderIndex < 0) {   // the folder's name: rename it
            click.open();
            startNaming(folderOpen);
            return;
        }
        if (folderOpen != null) {
            it = folderOpen.children.get(folderIndex);
            r = folderCells[folderIndex];
            closeFolder();   // the app opens from its card; back home, the grid
        } else if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = gridFocusCell;
            if (it.kind == HomeApps.FOLDER) {
                click.open();
                openFolder(it, r);
                return;
            }
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null || !playNow(p)) return;
            it = HomeApps.app(p);
            r = heroButton;
        } else if (zone == Z_STATUS && onIsland) {
            islandOk();   // a live activity: its one control
            return;
        } else if (zone == Z_STATUS) {
            // The bubble: Control Center. The pill: "Who's Watching?" (Back keeps this profile).
            click.open();
            openDrawer(!onBubble);
            retarget(false);
            return;
        } else if (zone == Z_DRAWER && ccOnProfiles) {
            // A profile: the lock opens on it (its PIN first); in, its avatar flies to the pill.
            if (profileIndex >= profiles.size()) return;
            click.open();
            ProfileManager.Profile p = profiles.get(profileIndex);
            closeDrawer();
            retarget(false);
            if (!p.id.equals(ProfileManager.current(getContext()).id)) lock.show(false, p);
            return;
        } else if (zone == Z_DRAWER && ccInputs) {
            if (inputIndex >= inputItems.size()) return;
            it = inputItems.get(inputIndex);
            r = inputRects[inputIndex];
            click.open();
            if (HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height()))) {
                closeDrawer();   // the TV goes to the input; back home, the Dock
                retarget(true);
            } else {
                showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
            }
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
            it = HomeApps.app(p);
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        if (looked == null) click.open();   // else it played when Play was pressed
        looked = null;
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.RECENTS) {
            closeDrawer();
            retarget(false);
            recentsOverlay.show();
            return;
        }
        if (it.kind == HomeApps.INPUT_SHEET) {
            openInputs();
            retarget(false);
            return;
        }
        if (it.kind == HomeApps.APPEARANCE) {
            toggleDark();
            retarget(false);
            return;
        }
        if (it.kind == HomeApps.BRIGHTNESS) {
            cycleBrightness();
            return;
        }
        if (it.kind == HomeApps.SLEEP && zone == Z_DRAWER) {
            // tvOS 17's presets in place: Off → 15 min → 30 min → 1 h → 2 h → Off (Settings keeps the long list).
            setSleep(nextSleep());
            return;
        }
        if (it.kind == HomeApps.VOLUME) return;   // ← → change it
        if (it.kind == HomeApps.MEDIA) {
            playing.playPause();
            return;
        }
        if (it.kind == HomeApps.POWER) {
            // The screen off now, through mahitTV's own ADB (a fixed key command in HomeMode).
            final Context ctx = getContext().getApplicationContext();
            LOADER.execute(() -> {
                if (!HomeMode.screenOff(ctx)) handler.post(() -> showToast(Ui.tr("Turning the screen off needs mahitTV mode.")));
            });
            return;
        }
        if (it.kind == HomeApps.DND) {
            dndAt = SystemClock.uptimeMillis();   // its moon swings in if it comes on (drawDrawerTile)
            handler.removeCallbacks(dndFrames);
            handler.post(dndFrames);
            toggleDnd();
            return;
        }
        if (it.kind == HomeApps.CLOSE_BG) {
            // Android's own "background" kill: only cached processes of the apps listed, nothing in front, no data.
            final Context ctx = getContext().getApplicationContext();
            LOADER.execute(() -> {
                HomeApps.closeBackgroundApps(ctx);
                handler.post(() -> showToast(Ui.tr("Background apps closed.")));
            });
            return;
        }
        if (it.kind == HomeApps.LOCK) {
            // Control Center's Lock: back to "Who's Watching?".
            closeDrawer();
            retarget(true);
            gate(false);
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            // HDMI Inputs: Control Center opens on its Inputs list; Control Center: on its first card.
            if (HomeApps.TOP_INPUTS.equals(it.key)) openInputs();
            retarget(false);
            return;
        }
        // A locked app (App Lock): its passcode first, over the home screen; right, it opens from the sheet.
        if (AppLock.needs(getContext(), it)) {
            askUnlock(it);
            return;
        }
        // An app: its tile becomes its launch screen at once, and the app's window fades in over it.
        boolean card = it.kind == HomeApps.APP && (sheetOpen || zone == Z_SHELF || zone == Z_HERO || zone == Z_CARDS);
        if (card) startLaunch(it, r, sheetOpen ? sheetLayer : zone == Z_SHELF ? tiles : heroLayer, zone == Z_SHELF && !sheetOpen ? 1 + FOCUS_GROW : 1);
        boolean opened = card ? HomeApps.openFromLaunch(activity, it) : HomeApps.open(activity, it, this, Math.round(r.left),
                Math.round(r.top), Math.round(r.width()), Math.round(r.height()));
        if (!opened) {
            launch.target = 0;
            if (card) cancelLaunch();
            showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
        }
    }

    // MARK: - Opening an app, as tvOS does: the tile becomes its launch screen

    /**
     * OK on an app: its tile grows into a full-screen launch screen at once (its colours, its logo or picture in the
     * middle), on a smooth spring, while the home recedes behind it. It stays until the app's window fades in over it
     * (launch_enter), however long the app takes to start; a slow one gets tvOS's spinner under the logo. `r` is the
     * tile in `layer`'s coordinates (a Dock tile at rest size; a grid cell or a button as drawn).
     */
    private void startLaunch(HomeApps.Item it, RectF r, View layer, float scale) {
        launchZone = sheetOpen ? LAUNCH_GRID : zone == Z_SHELF ? Z_SHELF : -1;
        launchIndex = sheetOpen ? gridIndex : shelfIndex;
        launch.snap(0);
        launch.target = 1;   // the home recedes behind it
        launchAt = SystemClock.uptimeMillis();
        if (Spring.instant) return;
        float cx = r.centerX() + layer.getTranslationX(), cy = r.centerY() + layer.getTranslationY();
        float hw = r.width() * scale / 2, hh = r.height() * scale / 2;
        launchFrom.set(cx - hw, cy - hh, cx + hw, cy + hh);
        launchLook(it);
        launchUp = true;
        launchBack = false;
        zoom.tune(0.42f, 0.9f);
        zoom.snap(0);
        zoom.target = 1;
        launchFade.snap(1);
        launchFade.target = 1;
        spinShown.snap(0);
        launchLayer.setVisibility(VISIBLE);
        wake();
    }

    /**
     * The launch screen's look, from the app's tile (the Dock's or All Apps' for a pick): its brand colours with its
     * logo, or its picture over the picture's own colour.
     */
    private void launchLook(HomeApps.Item it) {
        HomeApps.Item look = it;
        if (it.logo == null && it.tile == null && it.pkg != null) {
            for (HomeApps.Item s : shelf) if (it.pkg.equals(s.pkg)) look = s;
            if (look == it) for (HomeApps.Item s : others) if (it.pkg.equals(s.pkg)) look = s;
        }
        int[] style = TILE_STYLE.get(look.key);
        if (style == null) style = look.style;
        launchPicture = null;
        launchLogo = null;
        int top = 0xFF2C2C2E, bottom = 0xFF000000;
        float w0 = launchFrom.width(), h0 = launchFrom.height();
        if (look.logo != null && style != null) {
            top = style[0];
            bottom = style[1];
            launchLogo = style[2] == 2 && look.logoDark != null ? look.logoDark : look.logo;
            // At rest the logo sits in the tile as the Dock draws it: within 62 % × 42 % of it, its own shape.
            float lw = launchLogo.getWidth(), lh = launchLogo.getHeight(), fit = Math.min(w0 * 0.62f / lw, h0 * 0.42f / lh);
            launchPlateW = lw * fit * look.logoScale;
            launchPlateH = lh * fit * look.logoScale;
        } else if (look.tile != null && look.tileShader != null) {
            // A picture (the app's TV banner): the whole tile, over the picture's own average colour.
            launchPicture = look;
            Bitmap one = Bitmap.createScaledBitmap(look.tile, 1, 1, true);
            int avg = one.getPixel(0, 0) | 0xFF000000;
            one.recycle();
            top = blend(avg, 0xFF000000, 0.15f);
            bottom = blend(avg, 0xFF000000, 0.45f);
            launchPlateW = w0;
            launchPlateH = h0;
        }
        launchShader = new LinearGradient(0, 0, 0, 1, top, bottom, Shader.TileMode.CLAMP);
        float lum = (0.2126f * ((bottom >> 16) & 255) + 0.7152f * ((bottom >> 8) & 255) + 0.0722f * (bottom & 255)) / 255f;
        launchInk = lum > 0.6f ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    /** Back while an app opens (or it never comes): the launch screen shrinks back into its tile. */
    private void cancelLaunch() {
        if (!launchUp || launchBack) return;
        launchBack = true;
        zoom.tune(0.36f, 0.94f);
        zoom.target = 0;
        spinShown.target = 0;
        launch.target = 0;
        wake();
    }

    /**
     * Back on the home screen from the app (the mirror of opening): the launch screen, whole again, shrinks into the
     * Dock tile the app came from (from All Apps, which is closed now: into the middle) as the home comes forward.
     */
    private void returnLaunch() {
        launchBack = true;
        if (launchZone == Z_SHELF && launchIndex >= 0 && launchIndex < tileRects.length) {
            RectF t = tileRects[launchIndex];
            float s = 1 + FOCUS_GROW, cx = t.centerX(), cy = t.centerY() + tiles.getTranslationY();
            launchFrom.set(cx - t.width() * s / 2, cy - t.height() * s / 2, cx + t.width() * s / 2, cy + t.height() * s / 2);
        } else {
            float hw = TV_TILE_W * k * (1 + FOCUS_GROW) / 2, hh = TV_TILE_H * k * (1 + FOCUS_GROW) / 2;
            launchFrom.set(w / 2f - hw, h / 2f - hh, w / 2f + hw, h / 2f + hh);
        }
        zoom.tune(0.38f, 0.92f);
        zoom.snap(1);
        zoom.target = 0;
        launchFade.snap(1);
        launchFade.target = 1;
        spinShown.snap(0);
        launch.snap(1);
        launch.target = 0;   // the home comes forward from behind it
        launchAt = SystemClock.uptimeMillis();
        launchLayer.setVisibility(VISIBLE);
        launchLayer.invalidate();
    }

    private void endLaunch() {
        launchUp = launchBack = false;
        launchPicture = null;
        launchLogo = null;
        launchZone = launchIndex = -1;
        zoom.snap(0);
        launchLayer.setVisibility(INVISIBLE);
    }

    /** One frame of the launch screen; true while it shows (its spinner turns, the app may come any frame). */
    private boolean stepLaunch(float dt, long up) {
        if (!launchUp) return false;
        zoom.step(dt);
        launchFade.step(dt);
        spinShown.step(dt);
        if (!launchBack) {
            if (up - launchAt > LAUNCH_SPIN_MS) spinShown.target = 1;
            if (up - launchAt > LAUNCH_GIVE_UP_MS) cancelLaunch();   // it never came: back to the tile
        } else {
            // Nearly back in the tile: the screen fades into the tile under it.
            if (zoom.value < 0.18f) launchFade.target = 0;
            if (launchFade.target == 0 && launchFade.value < 0.01f) {
                endLaunch();
                return false;
            }
        }
        launchLayer.invalidate();
        return true;
    }

    /** Keys while an app opens: Back stops it (the screen shrinks back), volume goes to the system, the rest wait. */
    private boolean launchKey(KeyEvent e, boolean down) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE
                || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_SLEEP) return false;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (down && e.getRepeatCount() == 0) backDown = true;
            else if (!down && backDown && !e.isCanceled()) {
                backDown = false;
                cancelLaunch();
                click.sheet(false, 0.5f);
            }
        }
        if (!down) okDown = okHeld = false;
        return true;
    }

    /**
     * The launch screen: a card from the tile to the whole screen (its corners squaring off as it grows) in the app's
     * colours, its logo or picture in the middle growing to 1.6× and tvOS's spinner under it once the app is slow.
     */
    private void drawLaunch(Canvas c) {
        if (!launchUp || launchShader == null) return;
        float z = zoom.value, zc = Ui.clamp(z), a = Ui.clamp(launchFade.value);
        if (a < 0.004f) return;
        launchCard.set(launchFrom.left * (1 - z), launchFrom.top * (1 - z), launchFrom.right + (w - launchFrom.right) * z,
                launchFrom.bottom + (h - launchFrom.bottom) * z);
        float radius = TV_TILE_RADIUS * k * (1 + FOCUS_GROW) * (1 - zc);
        int alpha = Math.round(255 * a);
        // The lift shadow while it's still tile-sized (gone once it fills the screen).
        if (zc < 0.98f) {
            launchPlate.set(launchCard);
            launchPlate.offset(0, 22 * k * (1 - zc));
            shadowDeep.setColor(Ui.alpha(0xFF000000, 0.45f * (1 - zc) * a));
            shadows.draw(c, launchPlate, radius, DEEP_BLUR * k, shadowDeep);
        }
        launchMatrix.setScale(1, Math.max(1, launchCard.height()));
        launchMatrix.postTranslate(0, launchCard.top);
        launchShader.setLocalMatrix(launchMatrix);
        launchPaint.setShader(launchShader);
        launchPaint.setAlpha(alpha);
        c.drawRoundRect(launchCard, radius, radius, launchPaint);
        launchPaint.setShader(null);
        float grow = 1 + 0.6f * zc, pw = launchPlateW * grow, ph = launchPlateH * grow;
        float cx = launchCard.centerX(), cy = launchCard.centerY();
        launchPlate.set(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2);
        if (launchLogo != null) {
            launchImage.setAlpha(alpha);
            c.drawBitmap(launchLogo, null, launchPlate, launchImage);
        } else if (launchPicture != null && launchPicture.tileShader != null) {
            // The picture as a plate with the tile's corners, lifted off its colour once the card has grown around it.
            float pr = TV_TILE_RADIUS * k * grow * (1 + FOCUS_GROW) / 1.23f;
            if (zc > 0.02f) {
                launchCard.set(launchPlate);
                launchCard.offset(0, 18 * k);
                shadowDeep.setColor(Ui.alpha(0xFF000000, 0.4f * zc * a));
                shadows.draw(c, launchCard, pr, DEEP_BLUR * k, shadowDeep);
            }
            Bitmap b = launchPicture.tile;
            float sc = Math.max(pw / b.getWidth(), ph / b.getHeight());
            launchMatrix.setScale(sc, sc);
            launchMatrix.postTranslate(cx - b.getWidth() * sc / 2, cy - b.getHeight() * sc / 2);
            launchPicture.tileShader.setLocalMatrix(launchMatrix);
            launchImage.setShader(launchPicture.tileShader);
            launchImage.setAlpha(alpha);
            c.drawRoundRect(launchPlate, pr, pr, launchImage);
            launchImage.setShader(null);
            launchPicture.tileShader.setLocalMatrix(null);
        }
        float sa = Ui.clamp(spinShown.value) * zc * a;
        if (sa > 0.01f) drawSpinner(c, w / 2f, Math.min(h - 120 * k, launchPlate.bottom + 110 * k), sa);
    }

    /** tvOS's activity indicator: 8 rounded spokes, the lit one stepping round once a second, a fading tail behind it. */
    private void drawSpinner(Canvas c, float cx, float cy, float a) {
        long t = SystemClock.uptimeMillis();
        int lead = (int) ((t / 125) % 8);
        launchPaint.setStyle(Paint.Style.STROKE);
        launchPaint.setStrokeCap(Paint.Cap.ROUND);
        launchPaint.setStrokeWidth(5 * k);
        float inner = 11 * k, outer = 23 * k;
        for (int i = 0; i < 8; i++) {
            int age = (lead - i + 8) % 8;
            launchPaint.setColor(Ui.alpha(launchInk, a * (1 - age * 0.11f)));
            double ang = i * Math.PI / 4 - Math.PI / 2;
            float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang);
            c.drawLine(cx + dx * inner, cy + dy * inner, cx + dx * outer, cy + dy * outer, launchPaint);
        }
        launchPaint.setStyle(Paint.Style.FILL);
        launchPaint.setStrokeCap(Paint.Cap.BUTT);
    }

    // MARK: - Holding OK: what's under the focus lifts, to be moved (moving apps: see the fields' note)

    private void hold() {
        if (!holdArmed) return;   // the timer and the remote's long-press repeat: whichever comes first, once
        holdArmed = false;
        handler.removeCallbacks(holdStart);
        click.holdCommit();
        press.target = 0;
        if (canOpenFromIsland()) {
            okHeld = true;   // its release opens nothing
            openFromIsland();
            return;
        }
        startMove();
    }

    /** OK held on what's playing in the dynamic dot: its app opens (as a tap on the Dynamic Island does). */
    private boolean canOpenFromIsland() {
        return zone == Z_STATUS && onIsland && islandAt == LIVE_MUSIC && playing.mediaPkg != null && !sheetOpen && !drawerOpen;
    }

    private void openFromIsland() {
        String pkg = playing.mediaPkg;
        if (pkg == null) return;
        HomeApps.Item it = HomeApps.app(pkg, playing.mediaApp != null ? playing.mediaApp : pkg);
        float shift = tabShift();
        HomeApps.open(activity, it, this, Math.round(islandNow.left + shift), Math.round(islandNow.top),
                Math.round(islandNow.width()), Math.round(islandNow.height()));
    }

    /** TalkBack's long click: the focused item lifts (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (okDown) okHeld = true;     // the remote's release must not open it
        if (board != null || !canMove()) return;
        click.holdCommit();
        startMove();
    }

    /** Something that can move has the focus: a Dock app, anything in All Apps, an app in an open folder. */
    private boolean canMove() {
        if (board != null || naming || tab != TAB_TV || drawerOpen) return false;
        if (folderOpen != null) return folderIndex >= 0 && folderIndex < folderOpen.children.size();
        if (sheetOpen) return gridIndex < others.size();
        if (zone != Z_SHELF || shelfIndex >= shelf.size()) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Moving (the cursor is out), as opposed to landing after OK or Back. */
    private boolean moving() {
        return board != null && moveState != LANDING;
    }

    private static String dockKey(HomeApps.Item it) {
        return it.pkg != null ? it.pkg : it.key;
    }

    /** It rises out of its place (the others close up behind it) and the cursor shows where it was. */
    private void startMove() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canMove()) return;
        wake();
        okHeld = true;   // the press that lifted it: its repeats and release do nothing
        shelfBefore = new ArrayList<>(shelf);
        othersBefore = new ArrayList<>(others);
        sheetBefore = sheetOpen;
        zoneBefore = zone;
        shelfIndexBefore = shelfIndex;
        gridIndexBefore = gridIndex;
        shelfFirstBefore = shelfFirst;
        // The board works on keys (a Dock app by its package); each key's tile is kept here.
        moveItems.clear();
        madeFolders.clear();
        List<String> dock = new ArrayList<>(), grid = new ArrayList<>();
        Map<String, List<String>> folders = new java.util.LinkedHashMap<>();
        java.util.Set<String> dockable = new java.util.HashSet<>();
        for (HomeApps.Item it : shelf) {
            dock.add(dockKey(it));
            moveItems.put(dockKey(it), it);
            if (it.kind == HomeApps.APP && it.pkg != null) dockable.add(dockKey(it));
        }
        for (HomeApps.Item it : others) {
            grid.add(it.key);
            moveItems.put(it.key, it);
            if (it.kind == HomeApps.APP && it.pkg != null) dockable.add(it.key);
            if (it.kind != HomeApps.FOLDER || it.children == null) continue;
            List<String> kids = new ArrayList<>();
            for (HomeApps.Item c : it.children) {
                kids.add(c.key);
                moveItems.put(c.key, c);
                if (c.kind == HomeApps.APP && c.pkg != null) dockable.add(c.key);
            }
            folders.put(it.key, kids);
        }
        board = new AppLayout.Board(dock, grid, folders, dockable, COLUMNS, HomeApps.maxShelf(getContext()));
        // Where it rises from, in page coordinates (the Dock's layer; the grid sits TV_GRID_LIFT under it).
        RectF from = moveRect;
        boolean lifted;
        if (folderOpen != null) {
            HomeApps.Item folder = folderOpen;
            int i = folderIndex;
            from.set(folderCells[i]);
            from.offset(0, sheetLayer.getTranslationY() - tiles.getTranslationY() - folderScroll.value);
            closeFolder();
            lifted = board.lift(AppLayout.Board.FROM_FOLDER, i, folder.key);
        } else if (sheetOpen) {
            gridPage(gridIndex, from);
            lifted = board.lift(AppLayout.Board.FROM_GRID, gridIndex, null);
        } else {
            from.set(tileRects[shelfIndex]);
            from.offset(slide(tileSlide, shelfIndex), 0);
            lifted = board.lift(AppLayout.Board.FROM_DOCK, shelfIndex, null);
        }
        if (!lifted) {
            board = null;
            return;
        }
        carried = moveItems.get(board.lifted);
        moveState = MOVING;
        moveAt = SystemClock.uptimeMillis();
        landing = null;
        dockSlots = Math.min(shelf.size(), shelfVisible);
        carryX.snap(from.centerX());
        carryY.snap(from.centerY());
        carryS.snap((1 + FOCUS_GROW) / carryBase());   // focused, as it was
        carryA.snap(1);
        flipStart();
        showBoard();
        aimCursor(true);
        moveShown.target = 1;
        moveLayer.setVisibility(VISIBLE);
        click.open();
        spoken.focusMoved();
    }

    /** The size the carried tile is drawn at before carryS: an app lifted (LIFT_GROW), a folder focused. */
    private float carryBase() {
        return carried != null && carried.kind == HomeApps.FOLDER ? 1 + FOCUS_GROW : 1 + LIFT_GROW;
    }

    private static float slide(Spring[] s, int i) {
        return i >= 0 && i < s.length ? s[i].value : 0;
    }

    /** Grid slot `i` in page coordinates: the grid, scrolled with the Dock, sits TV_GRID_LIFT under it. */
    private void gridPage(int i, RectF out) {
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
        float y = (GRID_TOP + TV_GRID_LIFT + (i / COLUMNS) * GRID_ROW) * k;
        out.set(x, y, x + GRID_W * k, y + GRID_H * k);
    }

    private final Map<String, float[]> flipKeys = new HashMap<>();

    /** Before the lists change: where each tile is now (page coordinates, with its slide), to slide from there. */
    private void flipStart() {
        flipFrom.clear();
        flipKeys.clear();
        for (int i = 0; i < shelf.size() && i < tileRects.length; i++) {
            float[] at = {tileRects[i].left + slide(tileSlide, i), tileRects[i].top};
            flipFrom.put(shelf.get(i), at);
            flipKeys.put(dockKey(shelf.get(i)), at);
        }
        for (int i = 0; i < others.size(); i++) {
            gridPage(i, cursorRect);
            float[] at = {cursorRect.left + slide(gridSlideX, i), cursorRect.top + slide(gridSlideY, i)};
            flipFrom.put(others.get(i), at);
            flipKeys.put(others.get(i).key, at);
        }
    }

    /** Where a tile was before (flipStart), by itself, by its key, or (a new folder) by its first app; null: nowhere. */
    private float[] flipOf(HomeApps.Item it) {
        float[] f = flipFrom.get(it);
        if (f == null) f = flipKeys.get(dockKey(it));
        if (f == null) f = flipKeys.get(it.key);
        if (f == null && it.kind == HomeApps.FOLDER && it.children != null && !it.children.isEmpty()) f = flipFrom.get(it.children.get(0));
        return f;
    }

    /** The Dock and All Apps as the board has them now. */
    private void showBoard() {
        List<HomeApps.Item> dock = new ArrayList<>(), grid = new ArrayList<>();
        for (String k : board.dock) {
            HomeApps.Item it = moveItems.get(k);
            if (it != null) dock.add(it);
        }
        for (String k : board.grid) {
            HomeApps.Item it = boardItem(k);
            if (it != null) grid.add(it);
        }
        setMoveLists(dock, grid);
    }

    /** A grid key's tile. A folder whose apps changed is a new tile: the one from before stays as it was, for Back. */
    private HomeApps.Item boardItem(String key) {
        HomeApps.Item it = moveItems.get(key);
        List<String> kids = board == null ? null : board.folders.get(key);
        if (kids == null) return it != null ? it : madeFolders.get(key);
        if (it != null && it.children != null && sameKeys(it.children, kids)) return it;
        HomeApps.Item made = madeFolders.get(key);
        if (made != null && sameKeys(made.children, kids)) return made;
        List<HomeApps.Item> children = new ArrayList<>();
        for (String k : kids) {
            HomeApps.Item c = moveItems.get(k);
            if (c != null) children.add(c);
        }
        String label = it != null ? it.label : made != null ? made.label : Ui.tr("Folder");
        made = HomeApps.folderItem(key, label, children);
        madeFolders.put(key, made);
        return made;
    }

    private static boolean sameKeys(List<HomeApps.Item> items, List<String> keys) {
        if (items.size() != keys.size()) return false;
        for (int i = 0; i < keys.size(); i++) if (!items.get(i).key.equals(keys.get(i))) return false;
        return true;
    }

    /** New lists for the Dock and All Apps: each tile keeps its focus spring and slides from where it was. */
    private void setMoveLists(List<HomeApps.Item> dock, List<HomeApps.Item> grid) {
        Spring[] focus = new Spring[dock.size()], tslide = new Spring[dock.size()];
        RectF[] rects = new RectF[dock.size()];
        for (int i = 0; i < dock.size(); i++) {
            int was = shelf.indexOf(dock.get(i));
            focus[i] = was >= 0 && was < tileFocus.length ? tileFocus[was] : new Spring(Spring.SNAPPY_FOCUS, 0);
            tslide[i] = new Spring(0.32f, 0.86f, 0).px();
            rects[i] = new RectF();
        }
        shelf = dock;
        tileFocus = focus;
        tileSlide = tslide;
        tileRects = rects;
        shelfIndex = Math.max(0, Math.min(shelfIndex, dock.size() - 1));
        placeShelf();
        for (int i = 0; i < dock.size(); i++) {
            float[] f = flipOf(dock.get(i));
            if (f != null && Math.abs(f[1] - tileRects[i].top) < 1) tslide[i].value = f[0] - tileRects[i].left;   // along the Dock
        }
        Spring[] gfocus = new Spring[grid.size()], sx = new Spring[grid.size()], sy = new Spring[grid.size()];
        for (int i = 0; i < grid.size(); i++) {
            int was = others.indexOf(grid.get(i));
            gfocus[i] = was >= 0 && was < gridFocus.length ? gridFocus[was] : new Spring(Spring.SNAPPY_FOCUS, 0);
            sx[i] = new Spring(0.32f, 0.86f, 0).px();
            sy[i] = new Spring(0.32f, 0.86f, 0).px();
        }
        others = grid;
        gridFocus = gfocus;
        gridSlideX = sx;
        gridSlideY = sy;
        gridLabels = null;
        for (int i = 0; i < grid.size(); i++) {
            float[] f = flipOf(grid.get(i));
            if (f == null) continue;
            gridPage(i, cursorRect);
            sx[i].value = f[0] - cursorRect.left;
            sy[i].value = f[1] - cursorRect.top;
        }
        tilesDirty = gridDirty = motionDirty = true;
        platter.invalidate();
        wake();
    }

    /**
     * The cursor to the board's place, and everything around it: the two apps beside a line part a little, an
     * outlined app shrinks into the folder-to-be (an outlined folder grows to take it, a Dock app dips to swap), the
     * row scrolls into view, and All Apps comes up once the cursor goes below the Dock.
     */
    private void aimCursor(boolean snap) {
        int r = board.row, p = board.pos, count = board.count(r);
        boolean ring = p % 2 == 1, inDock = r == AppLayout.Board.DOCK;
        int item = ring ? (p - 1) / 2 : -1, gap = ring ? -1 : p / 2;
        float th = TV_TILE_H * k, half = TV_TILE_GAP * k / 2;
        if (!inDock && !sheetOpen) {
            sheetOpen = true;
            sheet.target = 1;
            click.sheet(true, 0.5f);
            motionDirty = true;
        }
        float cx, cy;
        if (inDock) {
            int show = ring ? item : Math.min(gap, count - 1);
            if (show >= 0) {
                shelfIndex = show;
                revealShelf(snap);
            }
            cy = (TV_DOCK_TOP + TV_DOCK_PAD) * k + th / 2;
            if (count == 0) cx = w / 2f;
            else if (ring) cx = tileRects[item].centerX();
            else if (gap == 0) cx = tileRects[0].left - half;
            else if (gap == count) cx = tileRects[count - 1].right + half;
            else cx = (tileRects[gap - 1].right + tileRects[gap].left) / 2;
            scroll.target = 0;
        } else {
            int base = r * COLUMNS;
            gridPage(base, cursorRect);
            cy = cursorRect.centerY();
            if (count == 0) {
                cx = cursorRect.left - half;
            } else if (ring) {
                gridPage(base + item, cursorRect);
                cx = cursorRect.centerX();
            } else if (gap == count) {
                gridPage(base + count - 1, cursorRect);
                cx = cursorRect.right + half;
            } else {
                gridPage(base + gap, cursorRect);
                cx = cursorRect.left - half;
            }
            int rows = board.rows(), visible = Math.min(rows, VISIBLE_ROWS);
            float target = scroll.target;
            if (r < target) target = r;
            else if (r > target + visible - 1) target = r - visible + 1;
            scroll.target = Math.max(0, Math.min(rows - visible, target));
        }
        if (snap) scroll.snap(scroll.target);
        for (int i = 0; i < tileFocus.length; i++) {
            tileFocus[i].target = inDock && i == item ? HOVER_FOLDER : 0;
            tileSlide[i].target = inDock && gap >= 0 ? (i == gap - 1 ? -PART * k : i == gap ? PART * k : 0) : 0;
        }
        int base = inDock ? 0 : r * COLUMNS;
        for (int i = 0; i < gridFocus.length; i++) {
            int j = i - base;
            boolean here = !inDock && j >= 0 && j < count;
            boolean folder = others.get(i).kind == HomeApps.FOLDER;
            gridFocus[i].target = here && j == item ? (folder ? HOVER_INTO : HOVER_FOLDER) : 0;
            gridSlideX[i].target = here && gap >= 0 ? (j == gap - 1 ? -PART * k : j == gap ? PART * k : 0) : 0;
        }
        float ring2 = 2 * (RING_GAP + RING_STROKE) * k;
        cursorX.target = cx;
        cursorY.target = cy;
        cursorW.target = ring ? TV_TILE_W * k + ring2 : LINE_W * k;
        cursorH.target = ring ? th + ring2 : th + 2 * LINE_REACH * k;
        if (snap) {
            cursorX.snap(cx);
            cursorY.snap(cy);
            cursorW.snap(cursorW.target);
            cursorH.snap(0);   // the line grows out of the gap it left
        }
        tilesDirty = gridDirty = motionDirty = true;
        updateHint();
        wake();
    }

    /** An arrow while moving: the cursor's next place, or a nudge where there's none. */
    private void moveCursor(int dx, int dy) {
        lastDx = dx;
        lastDy = dy;
        if (board.move(dx, dy)) {
            aimCursor(false);
            click.move();
            return;
        }
        cursorX.impulse(dx * Spring.BUMP_VELOCITY * 0.5f * k);
        cursorY.impulse(dy * Spring.BUMP_VELOCITY * 0.5f * k);
        carryX.impulse(dx * Spring.BUMP_VELOCITY * 0.3f * k);
        carryY.impulse(dy * Spring.BUMP_VELOCITY * 0.3f * k);
        click.bump();
        wake();
    }

    /** Keys while moving: nothing else on the home screen gets them (volume and power still go to the system). */
    private boolean moveKey(KeyEvent e, boolean down) {
        int code = e.getKeyCode();
        if (moveState == LANDING) {   // a key while it lands: it's there at once, and the key does what it always does
            if (down) endLanding(true);
            return false;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    moveCursor(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0,
                            code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted it is still held: its repeats and release do nothing; a new press does it.
                if (down && e.getRepeatCount() == 0 && !okHeld) {
                    okDown = false;
                    commitMove();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) cancelMove(false);
                    backDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
                return false;
            default:
                return true;
        }
    }

    /** OK while moving: a folder is named first; anything else goes where the cursor is. */
    private void commitMove() {
        if (board == null || moveState != MOVING) return;
        if (board.action() == AppLayout.Board.NEW_FOLDER) {
            startNaming(null);
            return;
        }
        drop(null);
    }

    /** It goes where the cursor is (a new folder named `name`), the tiles slide to their places, and it's saved. */
    private void drop(String name) {
        if (board == null || moveState != MOVING) return;
        String lifted = board.lifted;
        String key = "folder:u" + Long.toString(System.currentTimeMillis(), 36);
        flipStart();
        int kind = board.drop(key);
        if (kind == AppLayout.Board.NEW_FOLDER) {
            List<HomeApps.Item> kids = new ArrayList<>();
            for (String k : board.folders.get(key)) kids.add(moveItems.get(k));
            madeFolders.put(key, HomeApps.folderItem(key, name == null || name.trim().isEmpty() ? Ui.tr("Folder") : name.trim(), kids));
        }
        dockSlots = 0;
        showBoard();
        saveMove(kind);
        HomeApps.Item it = moveItems.get(lifted);
        land(it, kind == AppLayout.Board.NONE ? Ui.trf("{app} stays where it was", "app", it.label) : moveDone(kind, it));
        click.open();
    }

    /** What TalkBack says once it's done. */
    private String moveDone(int kind, HomeApps.Item it) {
        switch (kind) {
            case AppLayout.Board.NEW_FOLDER:
            case AppLayout.Board.ADD: {
                HomeApps.Item f = folderHolding(it);
                return Ui.trf("{app} is in {folder}", "app", it.label, "folder", f != null ? f.label : Ui.tr("Folder"));
            }
            case AppLayout.Board.SWAP: return Ui.trf("{app} is in the Dock", "app", it.label);
            default: return Ui.trf("Moved {app}", "app", it.label);
        }
    }

    private HomeApps.Item folderHolding(HomeApps.Item it) {
        for (HomeApps.Item f : others) if (f.kind == HomeApps.FOLDER && f.children != null && f.children.contains(it)) return f;
        return null;
    }

    /**
     * After OK or Back: the carried tile flies to `it`'s place (into its folder, if it's in one), the focus follows it
     * there (the Dock: All Apps goes down; the grid: it stays up), and the cursor fades.
     */
    private void land(HomeApps.Item it, String said) {
        int inDock = shelf.indexOf(it), inGrid = others.indexOf(it);
        landFade = inDock < 0 && inGrid < 0;
        landing = it;
        if (landFade) {
            HomeApps.Item f = folderHolding(it);
            landing = f;
            inGrid = f != null ? others.indexOf(f) : -1;
        }
        moveState = LANDING;
        landAt = SystemClock.uptimeMillis();
        moveShown.target = 0;
        for (Spring f : tileFocus) f.target = 0;   // no more dips or folders-to-be
        for (Spring f : gridFocus) f.target = 0;
        if (inDock >= 0) {
            zone = Z_SHELF;
            shelfIndex = inDock;
            if (sheetOpen) closeSheet();
            else retarget(false);
            tileFocus[inDock].snap(1);   // hidden until it lands, then focused at once
        } else if (inGrid >= 0) {
            gridIndex = inGrid;
            if (!sheetOpen) openSheet(inGrid);
            retargetGrid(false);
            if (!landFade) gridFocus[inGrid].snap(1);
        } else {
            retarget(false);
        }
        for (Spring s : tileSlide) s.target = 0;
        for (Spring s : gridSlideX) s.target = 0;
        tilesDirty = gridDirty = true;
        if (said != null) announceForAccessibility(said);
        spoken.focusMoved();
        wake();
    }

    /** Landed (or a key came first: `now`): the move is over and the tile it landed on shows again. */
    private void endLanding(boolean now) {
        if (board == null) return;
        if (now) {
            carryX.snap(carryX.target);
            carryY.snap(carryY.target);
            moveShown.snap(0);
        }
        board = null;
        carried = landing = null;
        moveState = 0;
        madeFolders.clear();
        moveItems.clear();
        shelfBefore = othersBefore = null;
        moveLayer.setVisibility(INVISIBLE);
        tilesDirty = gridDirty = true;
        toastLayer.invalidate();
        if (appsStale) reload();   // the lists with the new layout (and a moved app's own tile)
        wake();
    }

    /** Back (or leaving the home screen): everything goes back as it was; it flies to its place, unless `snap`. */
    private void cancelMove(boolean snap) {
        if (naming) closeNaming(snap);
        if (board == null) return;
        if (moveState == LANDING) {
            if (snap) endLanding(true);
            return;
        }
        HomeApps.Item it = moveItems.get(board.lifted);
        flipStart();
        dockSlots = 0;
        shelfFirst = shelfFirstBefore;
        setMoveLists(new ArrayList<>(shelfBefore), new ArrayList<>(othersBefore));
        zone = zoneBefore;
        shelfIndex = Math.max(0, Math.min(shelfIndexBefore, shelf.size() - 1));
        gridIndex = Math.max(0, Math.min(gridIndexBefore, others.size() - 1));
        if (sheetOpen && !sheetBefore) {
            closeSheet();
        } else if (!sheetOpen && sheetBefore) {
            sheetOpen = true;
            sheet.target = 1;
            motionDirty = true;
        }
        if (sheetBefore) {
            HomeApps.Item f = folderHolding(it);
            int at = others.indexOf(f != null ? f : it);
            if (at >= 0) gridIndex = at;
        }
        land(it, Ui.trf("{app} is back where it was", "app", it.label));
        click.sheet(false, 0.5f);
        if (snap) {
            for (Spring s : tileSlide) s.snap(0);
            for (Spring s : gridSlideX) s.snap(0);
            for (Spring s : gridSlideY) s.snap(0);
            sheet.snap(sheet.target);
            scroll.snap(scroll.target);
            motionDirty = true;
            endLanding(true);
        }
    }

    /** Saves what OK did: the Dock (when it changed) and All Apps' order and the person's folders. */
    private void saveMove(int kind) {
        if (kind == AppLayout.Board.NONE) return;
        List<String> before = new ArrayList<>();
        for (HomeApps.Item it : shelfBefore) before.add(dockKey(it));
        if (!before.equals(board.dock)) {
            // The shown apps in their new order, then what the saved list has that the Dock didn't show (not installed).
            List<String> save = new ArrayList<>(board.dock), saved = Prefs.shelf();
            if (saved != null) for (String p : saved) if (!before.contains(p) && !save.contains(p)) save.add(p);
            Prefs.setShelfQuietly(save);
        }
        AppLayout l = Prefs.layout();
        l.grid.clear();
        l.grid.addAll(board.grid);
        for (String key : board.dissolved) l.folders.remove(key);
        java.util.Set<String> mine = new java.util.LinkedHashSet<>(l.folders.keySet());
        mine.addAll(board.touched);
        for (String key : mine) {
            List<String> apps = board.folders.get(key);
            if (apps == null) continue;   // not in All Apps now (its apps on the Dock, or not installed): kept as it was
            HomeApps.Item f = boardItem(key);
            AppLayout.Folder saved = l.folder(key, f != null ? f.label : "");
            saved.apps.clear();
            saved.apps.addAll(apps);
        }
        Prefs.setLayout(l);
        appsStale = true;
    }

    /**
     * One frame of moving: the cursor, the carried tile (riding just above the cursor and wiggling; landing, flying to
     * its place), the hint's fade. Returns true while any of it moves.
     */
    private boolean stepMove(float dt, long up) {
        boolean hint = moveShown.step(dt);
        if (board == null) {
            if (hint) toastLayer.invalidate();
            return hint;
        }
        if (moveState == LANDING) {
            landTarget();
        } else {
            boolean ring = board.pos % 2 == 1;
            float carriedH = TV_TILE_H * k * CARRY_SCALE * (1 + LIFT_GROW);
            carryX.target = cursorX.target;
            carryY.target = cursorY.target - cursorH.target / 2 - carriedH / 2 + (ring ? 26 : 18) * k;
            carryS.target = CARRY_SCALE * (1 + LIFT_GROW) / carryBase();
            carryA.target = 1;
        }
        boolean moving = carryX.step(dt) | carryY.step(dt) | carryS.step(dt) | carryA.step(dt)
                | cursorX.step(dt) | cursorY.step(dt) | cursorW.step(dt) | cursorH.step(dt);
        float amp = moveState == LANDING ? Ui.clamp(1 - (up - landAt) / 180f) : 1;
        wiggle = WIGGLE_DEG * amp * (float) Math.sin((up - moveAt) * (2 * Math.PI / WIGGLE_MS));
        moveLayer.invalidate();
        if (hint || (moveShown.value > 0.01f && sheet.value > 0.01f && sheet.value < 0.99f)) toastLayer.invalidate();
        if (moveState == LANDING && (!moving || up - landAt > LAND_MS)) endLanding(false);
        return true;
    }

    /** Landing: where the tile it lands on is now (it may still be sliding, or the page moving). */
    private void landTarget() {
        if (landing == null) {
            carryA.target = 0;
            return;
        }
        int i = shelf.indexOf(landing);
        if (i >= 0) {
            cursorRect.set(tileRects[i]);
            cursorRect.offset(tileSlide[i].value, 0);
        } else {
            int j = others.indexOf(landing);
            if (j < 0) {
                carryA.target = 0;
                return;
            }
            gridPage(j, cursorRect);
            cursorRect.offset(gridSlideX[j].value, gridSlideY[j].value);
        }
        carryX.target = cursorRect.centerX();
        carryY.target = cursorRect.centerY();
        carryS.target = landFade ? 0.3f : (1 + FOCUS_GROW) / carryBase();
        carryA.target = landFade ? 0 : 1;
    }

    // MARK: - Drawing what moves: the cursor and the carried tile (the move layer, in page coordinates), the hint

    private void drawMove(Canvas c) {
        if (board == null) return;
        c.save();
        c.translate(tiles.getTranslationX(), tiles.getTranslationY());
        float a = Ui.clamp(moveShown.value);
        if (a > 0.01f) drawCursor(c, a);
        if (carried != null) drawCarried(c);
        c.restore();
    }

    /**
     * The cursor: one shape that's a line (narrow: a solid capsule) or an outline (wide: a ring), so it morphs from one
     * to the other. White, over a dark halo and a crisp dark edge, so it reads on the light grid as on the dark one.
     */
    private void drawCursor(Canvas c, float a) {
        float cw = Math.max(cursorW.value, 1), ch = cursorH.value, stroke = RING_STROKE * k;
        if (ch < 2) return;
        float radius = Math.min(Math.min(cw, ch) / 2, (TV_TILE_RADIUS + RING_GAP + RING_STROKE) * k);
        float cx = cursorX.value, cy = cursorY.value;
        cursorRect.set(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2);
        cursorPath.reset();
        cursorPath.setFillType(android.graphics.Path.FillType.EVEN_ODD);
        cursorPath.addRoundRect(cursorRect, radius, radius, android.graphics.Path.Direction.CW);
        if (cw - 2 * stroke > 0.5f && ch - 2 * stroke > 0.5f) {
            moveRect.set(cursorRect);
            moveRect.inset(stroke, stroke);
            float r = Math.max(0, radius - stroke);
            cursorPath.addRoundRect(moveRect, r, r, android.graphics.Path.Direction.CW);
        }
        // The halo: a shadow 3 px down, then a soft dark band along the edges.
        c.save();
        c.translate(0, 3 * k);
        cursorPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * a));
        c.drawPath(cursorPath, cursorPaint);
        c.restore();
        cursorEdge.setStyle(Paint.Style.STROKE);
        cursorEdge.setStrokeJoin(Paint.Join.ROUND);
        cursorEdge.setStrokeWidth(7 * k);
        cursorEdge.setColor(Ui.alpha(0xFF000000, tone(0.16f, 0.22f) * a));
        c.drawPath(cursorPath, cursorEdge);
        cursorPaint.setColor(Ui.alpha(0xFFFFFFFF, a));
        c.drawPath(cursorPath, cursorPaint);
        cursorEdge.setStrokeWidth(1.5f * k);
        cursorEdge.setColor(Ui.alpha(0xFF000000, tone(0.4f, 0.2f) * a));
        c.drawPath(cursorPath, cursorEdge);
    }

    /** The carried tile: lifted (its deep shadow), half size above the cursor, wiggling; it fades into a folder. */
    private void drawCarried(Canvas c) {
        float alpha = Ui.clamp(carryA.value), s = carryS.value;
        if (alpha < 0.01f || s <= 0) return;
        float tw = TV_TILE_W * k, th = TV_TILE_H * k, x = carryX.value, y = carryY.value;
        int save;
        if (alpha < 0.99f) {
            float rx = tw * s, ry = th * s;
            save = c.saveLayerAlpha(x - rx, y - ry, x + rx, y + ry + 40 * k, Math.round(255 * alpha));
        } else {
            save = c.save();
        }
        c.translate(x, y);
        c.rotate(wiggle);
        c.scale(s, s);
        moveRect.set(-tw / 2, -th / 2, tw / 2, th / 2);
        if (carried.kind == HomeApps.FOLDER) drawFolderTile(c, carried, moveRect, 1);
        else drawAppTile(c, carried, moveRect, 0, 1, 0);
        c.restoreToCount(save);
    }

    /** Behind an outlined app, the folder-to-be: the folder tile's frosted card, a little bigger than the app. */
    private void drawForming(Canvas c, RectF cell, float a) {
        float grow = 12 * k;
        moveRect.set(cell.left - grow, cell.top - grow, cell.right + grow, cell.bottom + grow);
        formShape.of(moveRect.width(), moveRect.height(), TV_TILE_RADIUS * k + grow);
        drawerCard(c, moveRect, formShape, TV_TILE_RADIUS * k + grow, a);
    }

    /** The hint's text for where the cursor is: what OK does there, in words and on the OK key. */
    private void updateHint() {
        if (board == null || carried == null) return;
        String t = board.target(), a = board.before(), b = board.after();
        boolean dock = board.row == AppLayout.Board.DOCK;
        switch (board.action()) {
            case AppLayout.Board.NONE:
                hintWhere = Ui.tr("Where it was");
                hintVerb = Ui.tr("Done");
                break;
            case AppLayout.Board.NEW_FOLDER:
                hintWhere = Ui.trf("New folder with {app}", "app", label(t));
                hintVerb = Ui.tr("Make Folder");
                break;
            case AppLayout.Board.ADD:
                hintWhere = Ui.trf("Add to {folder}", "folder", label(t));
                hintVerb = Ui.tr("Add");
                break;
            case AppLayout.Board.SWAP:
                hintWhere = Ui.trf("Swap with {app}", "app", label(t));
                hintVerb = Ui.tr("Swap");
                break;
            default:
                hintVerb = Ui.tr("Place");
                if (a != null && b != null) {
                    hintWhere = dock ? Ui.trf("In the Dock, between {a} and {b}", "a", label(a), "b", label(b))
                            : Ui.trf("Between {a} and {b}", "a", label(a), "b", label(b));
                } else if (b != null) {
                    hintWhere = dock ? Ui.tr("First in the Dock") : Ui.trf("Before {app}", "app", label(b));
                } else if (a != null) {
                    hintWhere = dock ? Ui.tr("Last in the Dock") : Ui.trf("After {app}", "app", label(a));
                } else {
                    hintWhere = dock ? Ui.tr("In the Dock") : Ui.tr("Here");
                }
        }
        hintName = carried.label;
        if (hintBold == null) {
            hintBold = Ui.text(Ui.semibold, 25 * k, 0xFFFFFFFF);
            hintText = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
            hintKey = Ui.text(Ui.semibold, 17 * k, 0xFFFFFFFF);
            hintKey.setTextAlign(Paint.Align.CENTER);
        }
        hintName = TextUtils.ellipsize(hintName, new TextPaint(hintBold), 260 * k, TextUtils.TruncateAt.END).toString();
        hintWhere = TextUtils.ellipsize(hintWhere, new TextPaint(hintText), 620 * k, TextUtils.TruncateAt.END).toString();
        announceForAccessibility(hintWhere);
        toastLayer.invalidate();
    }

    private String label(String key) {
        HomeApps.Item it = boardItem(key);
        return it != null ? it.label : "";
    }

    /** The hint: on the home screen under the tab bar (where toasts show); over All Apps at the bottom, under the grid. */
    private void drawMoveHint(Canvas c) {
        float a = Ui.clamp(moveShown.value);
        if (a < 0.01f || carried == null || naming || hintBold == null) return;
        float s = smoothstep(0.3f, 0.7f, Ui.clamp(sheet.value));
        if (s < 0.99f) drawHintAt(c, 124 * k, a * (1 - s));
        if (s > 0.01f) drawHintAt(c, h - 98 * k, a * s);
    }

    private void drawHintAt(Canvas c, float top, float a) {
        float hh = 62 * k, pad = 14 * k, miniW = 58 * k, miniH = 35 * k;
        String ok = Ui.tr("OK"), back = Ui.tr("Back"), cancel = Ui.tr("Cancel");
        float nameW = hintBold.measureText(hintName), whereW = hintText.measureText(hintWhere);
        float okW = Math.max(40 * k, hintKey.measureText(ok) + 20 * k), backW = hintKey.measureText(back) + 20 * k;
        float verbW = hintText.measureText(hintVerb), cancelW = hintText.measureText(cancel);
        float width = pad + miniW + 16 * k + nameW + 36 * k + whereW + 44 * k + okW + 10 * k + verbW + 28 * k + backW + 10 * k
                + cancelW + 26 * k;
        hintRect.set((w - width) / 2, top, (w + width) / 2, top + hh);
        int save = c.saveLayerAlpha(hintRect.left - 4, hintRect.top - 4, hintRect.right + 4, hintRect.bottom + 4, Math.round(255 * a));
        scene.glass(c, hintRect, hh / 2, SMOKE, 0.62f, 0, 0, 0.02f, 1);
        float x = hintRect.left + pad, cy = hintRect.centerY();
        moveRect.set(x, cy - miniH / 2, x + miniW, cy + miniH / 2);
        if (carried.kind == HomeApps.FOLDER) {
            fill.setShader(null);
            fill.setColor(0x33FFFFFF);
            c.drawRoundRect(moveRect, 10 * k, 10 * k, fill);
            Symbols.draw(getContext(), c, "folder", moveRect.centerX(), cy, 24 * k, 0xF2FFFFFF);
        } else {
            drawMini(c, carried, moveRect);
        }
        x += miniW + 16 * k;
        float base = cy + Ui.halfCap(hintBold);
        c.drawText(hintName, x, base, hintBold);
        x += nameW + 18 * k;
        fill.setShader(null);
        fill.setColor(0x40FFFFFF);
        c.drawRect(x - 1 * k, cy - 14 * k, x + 1 * k, cy + 14 * k, fill);
        x += 18 * k;
        c.drawText(hintWhere, x, base, hintText);
        x += whereW + 44 * k;
        x = drawKey(c, ok, x, okW, cy);
        c.drawText(hintVerb, x + 10 * k, base, hintBold);
        x += 10 * k + verbW + 28 * k;
        x = drawKey(c, back, x, backW, cy);
        c.drawText(cancel, x + 10 * k, base, hintText);
        c.restoreToCount(save);
    }

    /** A remote key's cap: its name in a thin rounded frame. Returns where the frame ends. */
    private float drawKey(Canvas c, String text, float x, float width, float cy) {
        float kh = 32 * k;
        moveRect.set(x, cy - kh / 2, x + width, cy + kh / 2);
        cursorEdge.setStyle(Paint.Style.STROKE);
        cursorEdge.setStrokeWidth(1.6f * k);
        cursorEdge.setColor(0xB3FFFFFF);
        c.drawRoundRect(moveRect, 9 * k, 9 * k, cursorEdge);
        c.drawText(text, moveRect.centerX(), cy + Ui.halfCap(hintKey), hintKey);
        return x + width;
    }

    // MARK: - Naming a folder: made by OK on an outlined app, or renamed from an open folder's title

    private static final int NAME_SUGGESTED = 4, NAME_WELLY = 2;
    private boolean naming;
    private String renameKey;                    // renaming this folder (null: a new one, while moving)
    private final List<String> names = new ArrayList<>();
    private int wellyFrom = -1, nameIndex, namingSession;
    private Spring[] nameFocus = new Spring[0];
    private RectF[] nameRects = new RectF[0];
    private float[] nameTextW = new float[0];
    private final Spring namingShown = new Spring(Spring.SHEET_OPEN, 0);
    private final RectF namingCard = new RectF(), namingTile = new RectF();
    private String namingCaption = "", namingTitle = "", namingBig = "";
    private HomeApps.Item namingPreview;
    private Paint nameBig, namePill, nameCaption, nameTitle;
    private android.widget.PopupWindow namePopup;

    /** The card that names a folder: a big name over suggestions (the apps' brand or what they're for, then Welly's). */
    private void startNaming(HomeApps.Item renaming) {
        List<HomeApps.Item> kids = new ArrayList<>();
        if (renaming != null) {
            renameKey = renaming.key;
            namingPreview = renaming;
            if (renaming.children != null) kids.addAll(renaming.children);
            namingTitle = Ui.tr("Rename Folder");
        } else {
            renameKey = null;
            moveState = NAMING;
            kids.add(boardItem(board.target()));
            kids.add(carried);
            namingPreview = HomeApps.folderItem("folder:preview", "", kids);
            namingTitle = Ui.tr("New Folder");
        }
        List<String> labels = new ArrayList<>(), pkgs = new ArrayList<>();
        for (HomeApps.Item it : kids) {
            labels.add(it.label);
            pkgs.add(it.pkg != null ? it.pkg : it.key);
        }
        namingCaption = kids.size() == 2 ? Ui.trf("{a} and {b}", "a", labels.get(0), "b", labels.get(1))
                : Ui.plural("plural|one:{n} app|other:{n} apps", kids.size());
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (HomeApps.Item it : others) {
            if (it.kind == HomeApps.FOLDER && (renaming == null || !it.key.equals(renaming.key))) taken.add(it.label.toLowerCase(Ui.locale()));
        }
        names.clear();
        if (renaming != null) names.add(renaming.label);
        for (String n : FolderNames.suggest(labels, pkgs)) {
            String t = folderName(n);
            if (names.size() >= NAME_SUGGESTED + (renaming != null ? 1 : 0)) break;
            if (!taken.contains(t.toLowerCase(Ui.locale())) && !names.contains(t)) names.add(t);
        }
        wellyFrom = -1;
        nameIndex = 0;
        naming = true;
        layoutNaming();
        for (Spring f : nameFocus) f.snap(0);
        nameFocus[0].snap(1);
        namingShown.tune(Spring.SHEET_OPEN);
        namingShown.target = 1;
        click.sheet(true, 0.5f);
        askWelly(labels, ++namingSession);
        announceForAccessibility(namingTitle + ". " + names.get(0));
        toastLayer.invalidate();
        wake();
    }

    /** A suggested folder name (FolderNames, in English) in the person's language: literal calls, so translate.py finds them. */
    private static String folderName(String n) {
        switch (n) {
            case "Streaming": return Ui.tr("folder|Streaming");
            case "Movies & TV": return Ui.tr("folder|Movies & TV");
            case "Watch": return Ui.tr("folder|Watch");
            case "Kids": return Ui.tr("folder|Kids");
            case "Family": return Ui.tr("folder|Family");
            case "Live TV": return Ui.tr("folder|Live TV");
            case "Channels": return Ui.tr("folder|Channels");
            case "Sports": return Ui.tr("folder|Sports");
            case "News": return Ui.tr("folder|News");
            case "Music": return Ui.tr("folder|Music");
            case "Listen": return Ui.tr("folder|Listen");
            case "Games": return Ui.tr("folder|Games");
            case "Play": return Ui.tr("folder|Play");
            case "Fitness": return Ui.tr("folder|Fitness");
            case "Health": return Ui.tr("folder|Health");
            case "Learning": return Ui.tr("folder|Learning");
            case "Explore": return Ui.tr("folder|Explore");
            case "Photos": return Ui.tr("folder|Photos");
            case "Memories": return Ui.tr("folder|Memories");
            case "Social": return Ui.tr("folder|Social");
            case "Shopping": return Ui.tr("folder|Shopping");
            case "Utilities": return Ui.tr("folder|Utilities");
            case "Tools": return Ui.tr("folder|Tools");
            case "TV": return Ui.tr("folder|TV");
            case "Entertainment": return Ui.tr("folder|Entertainment");
            case "Fun": return Ui.tr("folder|Fun");
            case "Favorites": return Ui.tr("folder|Favorites");
            case "My Apps": return Ui.tr("folder|My Apps");
            default: return n;   // a brand (YouTube, TCL): as it is
        }
    }

    /** Welly's ideas (when it has a brain), added after ours as they come; never in the way of a choice already made. */
    private void askWelly(final List<String> labels, final int session) {
        if (!WellyBrain.configured()) return;
        new Thread(() -> {
            final List<String> got = WellyBrain.folderNames(labels);
            handler.post(() -> {
                if (!naming || session != namingSession || got.isEmpty()) return;
                int added = 0;
                for (String n : got) {
                    boolean seen = false;
                    for (String m : names) seen |= m.equalsIgnoreCase(n);
                    if (seen || added >= NAME_WELLY) continue;
                    if (wellyFrom < 0) wellyFrom = names.size();
                    names.add(n);
                    added++;
                }
                if (added == 0) return;
                layoutNaming();
                toastLayer.invalidate();
                wake();
            });
        }, "folder-names").start();
    }

    /** The card's size and its pills (the names, then "Type a Name…"), in rows that fit it. */
    private void layoutNaming() {
        if (nameBig == null) {
            nameBig = Ui.text(Ui.displaySemibold, 58 * k, 0xFFFFFFFF);
            nameBig.setTextAlign(Paint.Align.CENTER);
            nameTitle = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
            nameTitle.setTextAlign(Paint.Align.CENTER);
            nameTitle.setLetterSpacing(0.04f);
            nameCaption = Ui.text(Ui.medium, 25 * k, 0xFFFFFFFF);
            nameCaption.setTextAlign(Paint.Align.CENTER);
            namePill = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
            namePill.setTextAlign(Paint.Align.CENTER);
        }
        int n = names.size() + 1;
        Spring[] focus = new Spring[n];
        for (int i = 0; i < n; i++) focus[i] = i < nameFocus.length ? nameFocus[i] : new Spring(Spring.SNAPPY_FOCUS, 0);
        nameFocus = focus;
        nameRects = new RectF[n];
        nameTextW = new float[n];
        float pillH = 64 * k, gap = 18 * k, padX = 30 * k, maxRow = 1060 * k, icon = 30 * k;
        float[] widths = new float[n];
        for (int i = 0; i < n; i++) {
            nameTextW[i] = namePill.measureText(nameText(i));
            widths[i] = nameTextW[i] + 2 * padX + (i >= wellyFrom && wellyFrom >= 0 && i < names.size() ? icon : 0);
        }
        // Rows: as many as fit, then the next row; each row centred.
        List<int[]> rows = new ArrayList<>();
        int start = 0;
        float rowW = 0, widest = 0;
        for (int i = 0; i < n; i++) {
            float add = (i > start ? gap : 0) + widths[i];
            if (i > start && rowW + add > maxRow) {
                rows.add(new int[] {start, i});
                widest = Math.max(widest, rowW);
                start = i;
                rowW = widths[i];
            } else {
                rowW += add;
            }
        }
        rows.add(new int[] {start, n});
        widest = Math.max(widest, rowW);
        float cw = Math.max(820 * k, widest + 2 * 64 * k);
        float ch = (56 + 146 + 36 + 70 + 44 + 50) * k + rows.size() * pillH + (rows.size() - 1) * gap + 60 * k;
        namingCard.set((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
        snap(namingCard);
        float tw = TV_TILE_W * k, th = TV_TILE_H * k, top = namingCard.top + 56 * k;
        namingTile.set(namingCard.centerX() - tw / 2, top, namingCard.centerX() + tw / 2, top + th);
        float y = namingTile.bottom + (36 + 70 + 44 + 50) * k;
        for (int[] r : rows) {
            float total = 0;
            for (int i = r[0]; i < r[1]; i++) total += widths[i] + (i > r[0] ? gap : 0);
            float x = namingCard.centerX() - total / 2;
            for (int i = r[0]; i < r[1]; i++) {
                nameRects[i] = new RectF(x, y, x + widths[i], y + pillH);
                x += widths[i] + gap;
            }
            y += pillH + gap;
        }
        nameIndex = Math.min(nameIndex, n - 1);
        for (int i = 0; i < n; i++) nameFocus[i].target = i == nameIndex ? 1 : 0;
        bigName();
    }

    /** The big name, cut to the card's width (made when the focus moves, never while drawing). */
    private void bigName() {
        namingBig = TextUtils.ellipsize(namingName(), new TextPaint(nameBig), namingCard.width() - 120 * k, TextUtils.TruncateAt.END).toString();
    }

    private String nameText(int i) {
        return i < names.size() ? names.get(i) : Ui.tr("Type a Name…");
    }

    /** The name the card shows big: the focused suggestion (on "Type a Name…", the one before it). */
    private String namingName() {
        return names.get(Math.min(nameIndex, names.size() - 1));
    }

    private boolean namingKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) moveName(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveName(0, e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    okDown = false;
                    okHeld = true;   // its release opens nothing
                    chooseName();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        closeNaming(false);   // a new folder: back to moving, the cursor still on the app
                        click.sheet(false, 0.5f);
                    }
                    backDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
                return false;
            default:
                return true;
        }
    }

    /** ← → along the pills; ↑ ↓ to the nearest one in the row above or below. */
    private void moveName(int dx, int dy) {
        int n = nameRects.length, next = nameIndex;
        if (dx != 0) {
            next = nameIndex + dx;
        } else {
            RectF from = nameRects[nameIndex];
            float best = Float.MAX_VALUE;
            next = -1;
            for (int i = 0; i < n; i++) {
                float rowDy = nameRects[i].centerY() - from.centerY();
                if (dy < 0 ? rowDy > -1 : rowDy < 1) continue;
                float d = Math.abs(rowDy) * 4 + Math.abs(nameRects[i].centerX() - from.centerX());
                if (d < best) {
                    best = d;
                    next = i;
                }
            }
        }
        if (next < 0 || next >= n) {
            click.bump();
            return;
        }
        nameIndex = next;
        for (int i = 0; i < n; i++) nameFocus[i].target = i == nameIndex ? 1 : 0;
        bigName();
        click.move();
        announceForAccessibility(nameText(nameIndex));
        toastLayer.invalidate();
        wake();
    }

    private void chooseName() {
        if (nameIndex >= names.size()) {
            openNameKeyboard();
            return;
        }
        finishNaming(names.get(nameIndex));
    }

    /** The name is chosen: a new folder is made with it (the move ends), or the open folder takes it. */
    private void finishNaming(String name) {
        String key = renameKey;
        closeNaming(false);
        if (key == null) {
            drop(name);
            return;
        }
        click.open();
        renameFolder(key, name);
    }

    /** The card goes (Back, or a name chosen). A new folder's: back to moving, the cursor where it was. */
    private void closeNaming(boolean snap) {
        if (!naming) return;
        naming = false;
        namingSession++;
        if (namePopup != null) namePopup.dismiss();
        namingShown.tune(Spring.SHEET_CLOSE);
        namingShown.target = 0;
        if (snap) namingShown.snap(0);
        if (moveState == NAMING) {
            moveState = MOVING;
            updateHint();
        }
        renameKey = null;
        toastLayer.invalidate();
        wake();
    }

    /** "Type a Name…": the system keyboard, over the big name, with the suggestion in it to change. */
    private void openNameKeyboard() {
        if (namePopup != null) return;
        android.widget.EditText edit = new android.widget.EditText(getContext());
        edit.setSingleLine(true);
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        edit.setBackground(null);
        edit.setPadding(0, 0, 0, 0);
        edit.setGravity(android.view.Gravity.CENTER);
        edit.setTypeface(Ui.displaySemibold);
        edit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 58 * k);
        edit.setTextColor(tone(INK, WHITE));
        edit.setHintTextColor(Ui.alpha(tone(INK, WHITE), 0.4f));
        edit.setHint(Ui.tr("Folder Name"));
        edit.setText(namingName());
        edit.selectAll();
        edit.setOnEditorActionListener((v, action, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE && action != android.view.inputmethod.EditorInfo.IME_ACTION_GO && !enter) return false;
            String typed = edit.getText().toString().trim();
            if (namePopup != null) namePopup.dismiss();
            if (!typed.isEmpty()) finishNaming(typed.length() > 40 ? typed.substring(0, 40) : typed);
            return true;
        });
        int pw = Math.round(namingCard.width() - 120 * k), ph = Math.round(84 * k);
        android.widget.PopupWindow p = new android.widget.PopupWindow(edit, pw, ph, true);
        p.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        p.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NEEDED);
        p.setOnDismissListener(() -> {
            namePopup = null;
            android.view.inputmethod.InputMethodManager imm = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(edit.getWindowToken(), 0);
            toastLayer.invalidate();
        });
        int[] at = new int[2];
        getLocationOnScreen(at);
        namePopup = p;
        float nameY = namingTile.bottom + (36 + 70) * k;   // the big name's baseline
        p.showAtLocation(this, android.view.Gravity.NO_GRAVITY, at[0] + Math.round(namingCard.centerX() - pw / 2f),
                at[1] + Math.round(nameY - ph + 16 * k));
        edit.requestFocus();
        edit.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (imm != null) imm.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 120);
        toastLayer.invalidate();
    }

    /** A folder's new name, shown at once and kept with the profile's layout (the folder is theirs now). */
    private void renameFolder(String key, String name) {
        HomeApps.Item folder = null;
        for (HomeApps.Item it : others) if (it.key.equals(key)) folder = it;
        if (folder == null || name.trim().isEmpty()) return;
        folder.label = name.trim();
        gridLabels = null;
        if (folderOpen == folder) {
            folderBadgeW = ccLabel.measureText(folder.label) + 60 * k;
            folderBadge.set(folderCard.centerX() - folderBadgeW / 2, folderBadge.top, folderCard.centerX() + folderBadgeW / 2, folderBadge.bottom);
        }
        AppLayout l = Prefs.layout();
        if (l.grid.isEmpty()) for (HomeApps.Item it : others) l.grid.add(it.key);
        AppLayout.Folder f = l.folder(key, folder.label);
        f.apps.clear();
        if (folder.children != null) for (HomeApps.Item c : folder.children) f.apps.add(c.key);
        Prefs.setLayout(l);
        gridDirty = true;
        announceForAccessibility(Ui.trf("Renamed to {name}", "name", folder.label));
        wake();
    }

    /** One frame of the naming card; true while it moves. */
    private boolean stepNaming(float dt) {
        boolean moving = namingShown.step(dt) | step(nameFocus, dt);
        if (moving) toastLayer.invalidate();
        return moving;
    }

    /** The naming card, over everything: the folder-to-be, its name big, the suggestions as pills (focused: inverted). */
    private void drawNaming(Canvas c) {
        float e = Ui.clamp(namingShown.value);
        if (e < 0.004f || nameBig == null || namingPreview == null || nameRects.length == 0) return;
        boolean night = dark() > 0.5f;
        fill.setShader(null);
        fill.setColor(Ui.alpha(night ? 0xFF000000 : 0xFF162130, (night ? 0.5f : 0.4f) * e));
        c.drawRect(0, 0, w, h, fill);
        c.save();
        float z = 0.94f + 0.06f * e;
        c.scale(z, z, namingCard.centerX(), namingCard.centerY());
        moveRect.set(namingCard);
        moveRect.offset(0, 30 * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.32f * e));
        shadows.draw(c, moveRect, FOLDER_RADIUS * k, SHADOW_BLUR * k, shadow);
        fill.setColor(Ui.alpha(night ? 0xFF282C36 : 0xFFF6F7FA, 0.985f * e));
        c.drawRoundRect(namingCard, FOLDER_RADIUS * k, FOLDER_RADIUS * k, fill);
        folderRim.setStyle(Paint.Style.STROKE);
        folderRim.setStrokeWidth(1.5f * k);
        folderRim.setColor(Ui.alpha(WHITE, (night ? 0.14f : 0.7f) * e));
        c.drawRoundRect(namingCard, FOLDER_RADIUS * k, FOLDER_RADIUS * k, folderRim);
        drawFolderTile(c, namingPreview, namingTile, 0);
        int ink = tone(INK, WHITE), dim = tone(L_DIM, 0xB3FFFFFF);
        float y = namingTile.bottom + 36 * k + nameTitle.getTextSize();
        nameTitle.setColor(Ui.alpha(dim, e));
        c.drawText(namingTitle, namingCard.centerX(), y, nameTitle);
        y += 70 * k;
        if (namePopup == null) {
            nameBig.setColor(Ui.alpha(ink, e));
            c.drawText(namingBig, namingCard.centerX(), y, nameBig);
        }
        y += 44 * k;
        nameCaption.setColor(Ui.alpha(dim, e));
        c.drawText(namingCaption, namingCard.centerX(), y, nameCaption);
        for (int i = 0; i < nameRects.length; i++) {
            if (i != nameIndex) drawNamePill(c, i, e, ink);
        }
        drawNamePill(c, nameIndex, e, ink);
        c.restore();
    }

    private void drawNamePill(Canvas c, int i, float e, int ink) {
        RectF r = nameRects[i];
        if (r == null) return;
        float f = Ui.clamp(nameFocus[i].value), s = 1 + 0.06f * f;
        c.save();
        c.scale(s, s, r.centerX(), r.centerY());
        float radius = r.height() / 2;
        if (f > 0.01f) {
            moveRect.set(r);
            moveRect.offset(0, 10 * k);
            shadowSoft.setColor(Ui.alpha(0xFF000000, 0.28f * f * e));
            shadows.draw(c, moveRect, radius, KEY_BLUR * k, shadowSoft);
        }
        // Resting: a faint fill; focused: inverted (ink on the light card, white on the dark one), as tvOS's buttons.
        int rest = tone(0x14000000, 0x24FFFFFF), focused = tone(INK, 0xFFFFFFFF);
        fill.setShader(null);
        fill.setColor(Ui.alpha(blend(rest, focused, f), e));
        c.drawRoundRect(r, radius, radius, fill);
        int text = blend(ink, tone(WHITE, INK), f);
        boolean welly = wellyFrom >= 0 && i >= wellyFrom && i < names.size();
        float tx = r.centerX();
        if (welly) {
            float icon = 30 * k;
            tx += icon / 2;
            Symbols.draw(getContext(), c, "sparkles", tx - nameTextW[i] / 2 - icon / 2 - 4 * k, r.centerY(), 22 * k, Ui.alpha(text, e));
        }
        namePill.setColor(Ui.alpha(text, e));
        c.drawText(nameText(i), tx, r.centerY() + Ui.halfCap(namePill), namePill);
        c.restore();
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;
    /** Control Center's Inputs list, while it's open (it takes the cards' place). */
    private static final int INPUT_LIST = 12;

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
        if (drawerOpen && ccInputs) {
            for (int i = 0; i < inputItems.size(); i++) out.add(a11yId(INPUT_LIST, i));
            return;
        }
        for (int i = 0; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        out.add(a11yId(Z_STATUS, 0));
        if (tab == TAB_TV) {   // the Dock shows only on the TV tab
            for (int i = 0; i < Math.min(3, cards.size()); i++) out.add(a11yId(Z_CARDS, i));
            for (int i = shelfFirst; i < Math.min(shelf.size(), shelfFirst + shelfVisible); i++) out.add(a11yId(Z_SHELF, i));
        }
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && volumeShown()) out.add(a11yId(WIDGETS, VOLUME_ITEM));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    /** TalkBack's id for Control Center's volume row, beside the widgets (0 weather, 1 quotes); not a widget itself. */
    private static final int VOLUME_ITEM = 2;

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
            case Z_SHELF: return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case INPUT_LIST: {
                String state = i < inputStates.length ? inputStates[i] : null;
                return state == null ? label(inputItems, i) : label(inputItems, i) + ", " + state;
            }
            case Z_STATUS:
                if (onIsland) {
                    Live l = lives[islandAt];
                    String what = l.sub.isEmpty() ? l.title : l.title + ", " + l.sub;
                    return islandAt == LIVE_MUSIC ? Ui.trf("Now playing: {what}", "what", what) : what;
                }
                return onBubble ? Ui.tr("Control Center") : clockText + ". " + Ui.tr("Profile");
            case WIDGETS:
                if (i == VOLUME_ITEM) return Ui.trf("Volume, {n}%", "n", Math.round(100f * volumeIndex / Math.max(1, volumeMax)));
                return i == 0 ? weatherText() : quotesText();
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
                dy = drawerOpen ? -ccScroll.value : h;   // the cards may be scrolled
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == VOLUME_ITEM ? volumeRect : i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            case INPUT_LIST:
                if (!drawerOpen || !ccInputs || i >= inputItems.size() || i >= inputRects.length) return false;
                a11yRect.set(inputRects[i]);
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
            case Z_DRAWER:
                if (ccInputs) return inputItems.isEmpty() ? -1 : a11yId(INPUT_LIST, inputIndex);
                if (ccOnVolume) return a11yId(WIDGETS, VOLUME_ITEM);
                return drawerItems.isEmpty() || ccOnProfiles ? -1 : a11yId(Z_DRAWER, drawerIndex);
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
        if (sheetOpen || board != null) return;
        if (group == INPUT_LIST) {
            if (!drawerOpen || !ccInputs || i >= inputItems.size()) return;
            inputIndex = i;
            zone = Z_DRAWER;
            retarget(false);
            drawerDirty = true;
            click.move();
            return;
        }
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                ccOnProfiles = ccOnVolume = false;
                drawerIndex = i;
                break;
            case WIDGETS:
                if (i == VOLUME_ITEM) {   // the volume row lives in the cards' zone
                    if (!drawerOpen || !volumeShown()) return;
                    ccOnProfiles = false;
                    ccOnVolume = true;
                    zone = Z_DRAWER;
                    retarget(false);
                    click.move();
                    return;
                }
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
        profiles = ProfileManager.all(getContext());
        int size = Math.round(PILL_AVATAR * k);
        int current = ProfileManager.currentIndex(getContext());
        Bitmap avatar = ProfileManager.avatar(getContext(), profiles.get(current), size, ProfileManager.CORNER_NESTED);
        if (pillAvatarProfile >= 0 && current != pillAvatarProfile && pillAvatar != null && !Spring.instant) {
            pillAvatarFrom = pillAvatar;   // another profile: the avatars cross-dissolve
            avatarFade.snap(0);
            avatarFade.target = 1;
            wake();
        }
        pillAvatarProfile = current;
        pillAvatar = avatar;
        pillTimeW = tvPill.measureText(clockText);
        float right = PILL_RIGHT * k, top = PILL_TOP * k, ph = PILL_H * k;
        float width = 24 * k + pillTimeW + 18 * k + PILL_AVATAR * k + 4 * k;
        pillRect.set(right - width, top, right, top + ph);
        snap(pillRect);
        float bx = pillRect.left - BUBBLE_GAP * k;
        bubbleRect.set(bx - BUBBLE_D * k, (PILL_TOP + (PILL_H - BUBBLE_D) / 2) * k, bx, (PILL_TOP + (PILL_H + BUBBLE_D) / 2) * k);
        Symbols.warm(getContext(), "controls", 26 * k);
        float b = PILL_H * k, x = bubbleRect.left - 16 * k, cy = pillRect.centerY();
        if (airName != null) {
            float aw = 40 * k;
            airRect.set(x - aw, cy - aw / 2, x, cy + aw / 2);
            x -= aw + 14 * k;
        } else {
            airRect.setEmpty();
        }
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[3 + i].set(x - b, cy - b / 2, x, cy + b / 2);
            x -= b + L_BUTTON_GAP * k;
        }
        layoutTabs();
        layoutIsland();
        statusDirty = true;
    }

    /**
     * The right cluster's left end, and each live activity's open width: what its words need (at most ISLAND_TEXT), but
     * never more than the logo and the cluster leave beside the bar; its words are cut to fit.
     */
    private void layoutIsland() {
        float left = !lightButtons.isEmpty() ? topRects[3].left : !airRect.isEmpty() ? airRect.left : bubbleRect.left;
        islandClusterLeft = left;
        if (islandTitlePaint == null) return;
        float d = ISLAND_D * k;
        for (Live l : lives) Symbols.warm(getContext(), l.icon, ISLAND_ICON * k);
        Symbols.warm(getContext(), "tv", ISLAND_ICON * k);
        Symbols.warm(getContext(), "chevron_right", 22 * k);
        float room = tabBarRect.isEmpty() ? Float.MAX_VALUE : left - ISLAND_GAP * k - ISLAND_LEFT * k - tabBarRect.width() - ISLAND_SPACE * k;
        for (int i = 0; i < lives.length; i++) {
            Live l = lives[i];
            float frame = (80 + (i == LIVE_MUSIC ? 212 : i == LIVE_SLEEP ? 164 : 80)) * k;   // ⏮ ⏯ ⏭ ✦, − + ×, else one
            float want = Math.min(ISLAND_TEXT * k, Math.max(islandTitlePaint.measureText(l.title), islandSubPaint.measureText(l.sub)));
            l.w = Math.max(d, Math.min(want + frame, room));
            float text = Math.max(0, l.w - frame);
            fitPaint.set(islandTitlePaint);
            l.titleFit = TextUtils.ellipsize(l.title, fitPaint, text, TextUtils.TruncateAt.END).toString();
            fitPaint.set(islandSubPaint);
            l.subFit = TextUtils.ellipsize(l.sub, fitPaint, text, TextUtils.TruncateAt.END).toString();
        }
        if (islandShows >= 0) islandWidth.target = lives[islandShows].w;
    }

    /** The tab bar's capsule and its three segments (topRects 0 to 2), centred at the top: measured here, not drawing. */
    private void layoutTabs() {
        if (tabText == null) return;
        tabLayoutGeneration = Ui.generation;
        String[] names = tabs();
        float total = 0;
        for (int i = 0; i < 3; i++) {
            tabTextW[i] = Math.max(tabText.measureText(names[i]) + 2 * TAB_PAD * k + (i == TAB_WELLY ? 28 * k : 0), TAB_MIN * k);
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
        lensX.target = topRects[tab].centerX();
        lensW.target = topRects[tab].width();
        if (!lensPlaced) {
            lensX.snap(lensX.target);
            lensW.snap(lensW.target);
            lensPlaced = true;
        }
    }

    /**
     * The tab bar (§2): smoke glass like the pill; the selected tab is a lighter lens that slides between the
     * segments; the focused one is a white pill with ink, 1.06×.
     */
    private void drawTabBar(Canvas c) {
        float shift = tabShift();   // the bar and the dynamic dot beside it stay centred together
        c.save();
        c.translate(shift, 0);
        drawIsland(c);   // first: it slides out from under the bar's right end
        drawTabBarAt(c);
        c.restore();
    }

    private void drawTabBarAt(Canvas c) {
        if (tabLayoutGeneration != Ui.generation) layoutTabs();   // the language changed
        if (tabBarRect.isEmpty()) return;
        float rr = tabBarRect.height() / 2;
        tmp.set(tabBarRect);
        tmp.offset(0, 6 * k);
        shadow.setColor(0x2E000000);
        shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
        // The lens (LIQUID_PILL): its speed stretches it like a drop (sx = 1 + |v|·0.00018 up to +12%, sy = 1/√sx), and
        // turns the glass's light so its rim catches it as it moves.
        float vx = lensX.velocity / k, sx = 1 + Math.min(0.12f, Math.abs(vx) * 0.00018f), sy = 1 / (float) Math.sqrt(sx);
        float turn = Math.max(-0.7f, Math.min(0.7f, vx * 0.0006f));
        if (turn != 0) {
            float cs = (float) Math.cos(turn), sn = (float) Math.sin(turn);
            scene.setLight(Scene.LIGHT_X * cs - Scene.LIGHT_Y * sn, Scene.LIGHT_X * sn + Scene.LIGHT_Y * cs);
        }
        scene.liquid(c, tabBarRect, rr, SMOKE, 0.42f, 0.02f, 12 * k, 8 * k, 0.7f, 1);
        scene.lightDefault();
        float lcx = lensX.value, lw = lensW.value * sx, lh = topRects[0].height() * sy, lcy = topRects[0].centerY();
        cell.set(lcx - lw / 2, lcy - lh / 2, lcx + lw / 2, lcy + lh / 2);
        fill.setShader(null);
        // While the focus is in the dynamic dot, the selected tab's lens and name step back: the focus isn't on Welly.
        float away = zone == Z_STATUS && onIsland ? Ui.clamp(islandOpen.value) : 0;
        fill.setColor(Ui.alpha(0xFFFFFFFF, 0.18f * (1 - 0.75f * away)));
        c.drawRoundRect(cell, cell.height() / 2, cell.height() / 2, fill);
        String[] names = tabs();
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            float f = Ui.clamp(topFocus[i].value), s = 1 + 0.06f * topFocus[i].value - PRESS * press.value * f;
            float selected = Ui.clamp(1 - Math.abs(lcx - r.centerX()) / Math.max(1, r.width()));
            c.save();
            c.translate(bumpX * f, bumpY * f);
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
            tabText.setColor(blend(blend(0xB3FFFFFF, 0xFFFFFFFF, selected * (1 - away)), INK, f));
            float textX = r.centerX() + (i == TAB_WELLY ? 12 * k : 0);
            if (i == TAB_WELLY) drawWellyIcon(c, textX - tabText.measureText(names[i]) / 2 - 18 * k, r.centerY(), 10 * k);
            c.drawText(names[i], textX, r.centerY() + Ui.halfCap(tabText), tabText);
            c.restore();
        }
    }

    private void drawWellyIcon(Canvas c, float cx, float cy, float radius) {
        wellyIcon.reset(); wellyIcon.setFillType(android.graphics.Path.FillType.EVEN_ODD);
        for (int i=0;i<WellyBot.PROFILE_SAMPLES;i++) {
            double a=i*Math.PI*2/WellyBot.PROFILE_SAMPLES;
            float x=cx+(float)Math.cos(a)*(float)WellyBot.galet(i)*radius;
            float y=cy+(float)Math.sin(a)*(float)WellyBot.galet(i)*radius;
            if(i==0)wellyIcon.moveTo(x,y);else wellyIcon.lineTo(x,y);
        }
        wellyIcon.close();
        wellyEye.reset();wellyEye.addOval(cx-radius*.31f,cy-radius*.31f,cx-radius*.13f,cy+radius*.22f,android.graphics.Path.Direction.CW);
        wellyEyeMatrix.reset();wellyEyeMatrix.setRotate(-26,cx-radius*.22f,cy-radius*.045f);wellyEye.transform(wellyEyeMatrix);wellyIcon.addPath(wellyEye);
        wellyEye.reset();wellyEye.addOval(cx+radius*.10f,cy-radius*.34f,cx+radius*.28f,cy+radius*.19f,android.graphics.Path.Direction.CW);
        wellyEyeMatrix.reset();wellyEyeMatrix.setRotate(-26,cx+radius*.19f,cy-radius*.075f);wellyEye.transform(wellyEyeMatrix);wellyIcon.addPath(wellyEye);
        fill.setColor(WellyBot.COLOR);c.drawPath(wellyIcon,fill);
        wellyIcon.reset();wellyIcon.setFillType(android.graphics.Path.FillType.EVEN_ODD);
        wellyIcon.addCircle(cx+radius*.76f,cy-radius*.68f,radius*.16f,android.graphics.Path.Direction.CW);
        wellyIcon.addCircle(cx+radius*.65f,cy-radius*.56f,radius*.09f,android.graphics.Path.Direction.CW);
        fill.setColor(WellyBot.NOTIF_BLUE);c.drawPath(wellyIcon,fill);
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
    /** The remote's microphone button from any app (HomeActivity.EXTRA_WELLY): Welly listens, once the screen is up. */
    /** App Lock's sheet for `it` (AppLock.Gate): over whatever is open here, which closes first. */
    boolean askUnlock(HomeApps.Item it) {
        if (it == null || it.pkg == null) return false;
        if (it.tile == null) {   // the Dock's own card for it, when it's there: the sheet shows it
            for (HomeApps.Item s : shelf) {
                if (it.pkg.equals(s.pkg) && s.tile != null) {
                    it.setTile(s.tile);
                    break;
                }
            }
        }
        wake();
        appLock.show(it);
        return true;
    }

    /** A locked app opened from outside the home screen (HomeActivity.EXTRA_UNLOCK): its passcode here. */
    void askUnlock(String pkg, String uri) {
        if (pkg == null) return;
        String label = pkg;
        try {
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) {
        }
        final HomeApps.Item it = uri != null ? HomeApps.app(pkg, label, uri) : HomeApps.app(pkg, label);
        post(() -> askUnlock(it));
    }

    void listenWelly() {
        post(this::voice);
    }

    private void voice() {
        wake();
        if (board != null) cancelMove(false);
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
        tabDir = index > tab ? 1 : -1;
        tab = index;
        lensX.target = topRects[index].centerX();
        lensW.target = topRects[index].width();
        tabAway.target = index == TAB_TV ? 0 : 1;
        homeShown.target = index == TAB_HOME ? 1 : 0;
        wellyShown.target = index == TAB_WELLY ? 1 : 0;
        if (index != TAB_TV) pillFrom = Z_SHELF;   // back on TV, ↓ from the bar goes to the Dock
        if (index == TAB_HOME) {
            house.tabShown();
            house.refresh();     // the lights' states, every time Home opens
        } else if (index == TAB_WELLY) {
            welly.tabShown();
        }
        motionDirty = true;
        wake();
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        if (liveVideoVisible) return;   // video sits below this View; glass layers still draw above it.
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer or app grid open: the background lightens (by day) or darkens (at night), as a filter on the drawing.
        // (Control Center: the page dims a little behind the panel; the app grid: it lightens or darkens a lot.)
        // Light dims only (the wallpaper stays bright): Control Center 0.18 by day, 0.22 at night; the app grid's
        // white lift by day, 0.25 black at night (as web/home.css).
        float dd = tone(0.18f, 0.22f) * Ui.clamp(drawer.value), ds = tone(0.62f, 0.25f) * Ui.clamp(sheet.value);
        // Home and Welly: a slate tint over any wallpaper (0.34 by day, 0.22 at night), never black, as on the web.
        float dt = tone(0.34f, 0.22f) * Ui.clamp(tabAway.value);
        android.graphics.ColorFilter v = dt > dd && dt > ds ? veil(dt, SLATE)
                : dd > ds ? veil(dd, 0xFF000000) : veil(ds, blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (backgroundMode == BACKGROUND_PROFILE && scene.isHorizon() && !horizonFading()) {
                scene.drawBackground(c);
                // Over the Frosted Horizon the dims are see-through layers, not a filter: Home and Welly tint it slate
                // (as web/home.css), so its colours stay under their white text.
                float amount = dt > dd && dt > ds ? dt : Math.max(dd, ds);
                int over = dt > dd && dt > ds ? SLATE : dd > ds ? 0xFF000000 : blend(0xFFFFFFFF, 0xFF000000, night);
                if (amount > 0.002f) {
                    fill.setColor(Ui.alpha(over, amount));
                    c.drawRect(screen, fill);
                    fill.setColor(0xFF000000);
                }
            } else {
                // An image that hasn't arrived (Live Aerial loading, a Top Shelf fade): the Frosted Horizon under it, day
                // and twilight by the night value, never a black or grey void.
                horizonPaint.setColorFilter(v);
                if (night < 0.999f) Horizon.draw(c, horizonDay, screen, horizonPaint, 1);
                if (night > 0.001f) Horizon.draw(c, horizonNight, screen, horizonPaint, night);
                horizonPaint.setColorFilter(null);
            }
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (b.image != null) {   // the Top Shelf's photo, sharp; with the app grid (or Home, Welly), its frosted copy over it
            // Ken Burns (1.00 → 1.04 and 12 px over 16 s, from each new photo) over a 2% margin, and the counter-parallax
            // against the Dock's focus (±8 px).
            float e = 0;
            if (b == bgTo && kenAt != 0 && !Spring.instant && !Ui.LEGACY) {
                float t = Ui.clamp((SystemClock.uptimeMillis() - kenAt) / (float) KEN_MS);
                e = 0.5f - 0.5f * (float) Math.cos(Math.PI * t);
            }
            float sc = 1.02f + 0.04f * e, tx = (12 * e * kenDir + parallax.value) * k;
            c.save();
            c.translate(w / 2f + tx, h / 2f);
            c.scale(sc, sc);
            c.translate(-w / 2f, -h / 2f);
            float soft = Math.max(Ui.clamp(sheet.value), Ui.clamp(tabAway.value));
            if (soft < 0.995f || b.soft == null) drawImage(c, b.image, alpha);   // the frosted copy covers it once it's opaque
            drawImage(c, b.soft, alpha * soft);
            c.restore();
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
        // A media fade must never leak into the profile wallpaper frame.
        if (!heroVisibility.draw()) return;
        float fs = Ui.clamp(full.value);
        if (fs > 0.004f && hero != null) {
            if (fullScrim == null) {
                // Light: just enough under the details for their white text, the art stays bright.
                fullScrim = new LinearGradient(0, h * 0.82f, 0, h * 0.3f, new int[] {0x59000000, 0x2E000000, 0x00000000},
                        new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
            }
            // A bright photo (a poster on white) needs more under the white text, in place of the light scrim.
            float bright = Ui.clamp((scene.photoLum() - 0.5f) / 0.25f);
            if (bright > 0 && fullScrimBright == null) {
                fullScrimBright = new LinearGradient(0, h * 0.82f, 0, h * 0.3f, new int[] {0xA6000000, 0x99000000, 0x00000000},
                        new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
            }
            if (bright < 1) {
                fill.setShader(fullScrim);
                fill.setAlpha(Math.round(255 * fs * (1 - bright)));
                c.drawRect(0, h * 0.3f, w, h, fill);
            }
            if (bright > 0) {
                fill.setShader(fullScrimBright);
                fill.setAlpha(Math.round(255 * fs * bright));
                c.drawRect(0, h * 0.3f, w, h, fill);
            }
            fill.setShader(null);
            fill.setAlpha(255);
        }
        long up = SystemClock.uptimeMillis();
        // The old title leaves (−14 px, 220 ms); the new one settles in from +22 px (TITLE_SETTLE), 80 ms later.
        float out = heroFadeAt == 0 ? 1 : Ui.clamp((up - heroFadeAt) / 220f);
        if (heroFrom != null && out < 1) drawShelfText(c, heroFrom, 1 - out, -14 * k * heroSlide * out);
        if (hero != null) drawShelfText(c, hero, Ui.clamp(titleIn.value), 22 * k * heroSlide * (1 - titleIn.value));
        // Page dots, in full screen: right-aligned to the content's edge, on the Play button's centre line; the current
        // one slides between them.
        if (hero != null && hero.dots > 1 && fs > 0.01f) {
            float dot = 8 * k, gap = 14 * k, total = hero.dots * dot + (hero.dots - 1) * gap;
            float x0 = CONTENT_R * k - total + dot / 2, x = x0, y = (TV_FULL_BOTTOM - TV_BUTTON_H / 2) * k;
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fs));
            for (int i = 0; i < hero.dots; i++) {
                c.drawCircle(x, y, dot / 2, fill);
                x += dot + gap;
            }
            fill.setColor(Ui.alpha(0xFFFFFFFF, fs));
            c.drawCircle(x0 + dotAt.value * (dot + gap), y, dot / 2, fill);
        }
        if (playingTab != null && !picksOn()) drawQuotes(c);   // with the picks, the dynamic dot shows it
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
        // Entering, the details come in after each other (badges, synopsis, Play: 40 ms apart, rising 16 px); leaving,
        // all together in ~180 ms.
        long since = SystemClock.uptimeMillis() - fullAt;
        boolean entering = full.target >= 0.5f;
        float fs = Ui.clamp(full.value), fa = alpha * (entering ? fs : smoothstep(0.55f, 1, fs));
        if (fa <= 0.004f) return;
        float s0 = entering ? stage(since, 0) : 1, s1 = entering ? stage(since, 40) : 1, s2 = entering ? stage(since, 80) : 1;
        float a0 = fa * s0, a1 = fa * s1, a2 = fa * s2;
        float r0 = 16 * k * (1 - s0), r1 = 16 * k * (1 - s1), r2 = 16 * k * (1 - s2);
        float x = TV_MARGIN * k + dx, bh = 30 * k;
        float buttonTop = (TV_FULL_BOTTOM - TV_BUTTON_H) * k + r2;
        float overviewH = h.overviewLayout == null ? 0 : h.overviewLayout.getHeight();
        float overviewTop = buttonTop - 30 * k - overviewH;
        float metaMid = overviewTop - r2 + r0 - (overviewH > 0 ? 22 : 0) * k - bh / 2;
        overviewTop += r1 - r2;
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
        // "▶ Play": focused in full screen (white with ink, 1.08×; pressed, a little less; a dead end nudges it).
        float f = Ui.clamp(heroFocus.value), s = 1 + 0.08f * heroFocus.value - PRESS * press.value * f;
        float ph = TV_BUTTON_H * k, icon = 24 * k, pad = 34 * k;
        float pw = pad + icon + 12 * k + h.appW + pad;
        heroButton.set(x + bumpX * f, buttonTop + bumpY * f, x + pw + bumpX * f, buttonTop + ph + bumpY * f);
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
        drawBubble(c);
        drawPill(c);
        c.save();
        if (!airRect.isEmpty()) {
            Symbols.draw(getContext(), c, "airpods", airRect.centerX(), airRect.centerY(), 24 * k, 0xEBFFFFFF);
        }
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.1f * f - PRESS * press.value * f, rad = r.width() / 2 * s;
            float bx = bumpX * f, by = bumpY * f;
            cell.set(r.centerX() - rad + bx, r.centerY() - rad + by, r.centerX() + rad + bx, r.centerY() + rad + by);
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
        c.restore();
    }

    /** How far one of full screen's details has come in, `delay` ms after the others started (eased, 200 ms). */
    private static float stage(long since, long delay) {
        if (Spring.instant) return 1;
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
        // Focused: 1.08× with a brighter rim, then white with ink; the Memoji pops 1.12× inside its circle. Something
        // playing changes nothing here: no equalizer, no wider pill (it pushed into Control Center's button).
        float f = Ui.clamp(pillFocus.value), s = 1 + 0.08f * pillFocus.value - PRESS * press.value * f;
        float rr = pillRect.height() / 2;
        pillNow.set(pillRect);
        c.save();
        c.translate(bumpX * f, bumpY * f);
        c.scale(s, s, pillNow.centerX(), pillNow.centerY());
        tmp.set(pillNow);
        tmp.offset(0, (6 + 8 * f) * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.18f + 0.2f * f));
        shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
        scene.liquid(c, pillNow, rr, SMOKE, 0.42f, 0.02f, 12 * k, 8 * k, 0.7f + 0.6f * f, 1 - f);
        float white = Math.max(f, drawerOpen && ccProfile ? 0.86f : 0);   // lit while its profile panel is open
        if (white > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, white));
            c.drawRoundRect(pillNow, rr, rr, fill);
        }
        int ink = blend(0xFFFFFFFF, INK, white);
        tvPill.setColor(ink);
        float base = pillNow.centerY() + Ui.halfCap(tvPill);
        c.drawText(clockText, pillRect.left + 24 * k, base, tvPill);
        if (pillAvatar != null) {
            float d = PILL_AVATAR * k, ax = pillNow.right - 4 * k - d, ay = pillNow.centerY() - d / 2;
            boolean pop = f > 0.01f;
            if (pop) {
                c.save();
                pillClip.rewind();
                pillClip.addRoundRect(ax, ay, ax + d, ay + d, d * ProfileManager.CORNER_NESTED, d * ProfileManager.CORNER_NESTED, android.graphics.Path.Direction.CW);
                c.clipPath(pillClip);
                float z = 1 + 0.12f * f;
                c.scale(z, z, ax + d / 2, ay + d / 2);
            }
            float fade = Ui.clamp(avatarFade.value);
            if (pillAvatarFrom != null && fade < 0.999f) {
                bitmapPaint.setAlpha(Math.round(255 * (1 - fade)));
                c.drawBitmap(pillAvatarFrom, ax, ay, bitmapPaint);
                bitmapPaint.setAlpha(Math.round(255 * fade));
            } else {
                pillAvatarFrom = null;
            }
            c.drawBitmap(pillAvatar, ax, ay, bitmapPaint);
            bitmapPaint.setAlpha(255);
            if (pop) c.restore();
        }
        c.restore();
    }

    /** Control Center's bubble: the pill's smoke glass in a 48 px circle; white with ink focused, light while it's open. */
    private void drawBubble(Canvas c) {
        if (bubbleRect.isEmpty()) return;
        float f = Ui.clamp(bubbleFocus.value), open = drawerOpen && !ccProfile ? 1 : 0;
        float s = 1 + 0.12f * bubbleFocus.value - PRESS * press.value * f, r = bubbleRect.height() / 2;
        c.save();
        c.translate(bumpX * f, bumpY * f);
        c.scale(s, s, bubbleRect.centerX(), bubbleRect.centerY());
        tmp.set(bubbleRect);
        tmp.offset(0, (6 + 8 * f) * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.18f + 0.2f * f));
        shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        scene.liquid(c, bubbleRect, r, SMOKE, 0.42f, 0.02f, 10 * k, 6 * k, 0.7f + 0.6f * f, 1 - f);
        float white = Math.max(f, 0.86f * open);
        if (white > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, white));
            c.drawCircle(bubbleRect.centerX(), bubbleRect.centerY(), r, fill);
        }
        Symbols.draw(getContext(), c, "controls", bubbleRect.centerX(), bubbleRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, white));
        c.restore();
    }

    /**
     * The dot, in the tab bar's Liquid Glass. Coming, it slides out from under the bar's right end, growing 0.35× → 1 on
     * a liquid spring that carries it a little past; open, it's a 72 px capsule (as wide as what it shows needs) with a
     * brighter rim and a deeper shadow. Stepping to another activity, the old one slides out up (or down) as the new
     * one slides in from the other side; past either end, it knocks (the nudge every focus gets).
     */
    private void drawIsland(Canvas c) {
        float sv = islandShown.value;
        if (sv < 0.01f || islandShows < 0) return;
        float e = Math.max(0, islandOpen.value), ec = Ui.clamp(e), d = ISLAND_D * k, cy = tabBarRect.centerY();
        float x = tabBarRect.right + ISLAND_SPACE * k, wNow = d + (Math.max(d, islandWidth.value) - d) * e;
        float hNow = Math.max(d * 0.9f, d + (ISLAND_H * k - d) * e), r = hNow / 2;
        islandNow.set(x, cy - hNow / 2, x + wNow, cy + hNow / 2);
        float grow = Math.max(0, 0.35f + 0.65f * sv), slide = -(ISLAND_SPACE * k + d * 0.7f) * (1 - Ui.clamp(sv));
        float f = zone == Z_STATUS && onIsland ? 1 : 0;
        c.save();
        c.translate(slide + bumpX * f, bumpY * f);
        c.scale(grow, grow, x + d / 2, cy);
        tmp.set(islandNow);
        tmp.offset(0, (6 + 10 * ec) * k);
        shadow.setColor(Ui.alpha(0xFF000000, 0.16f + 0.2f * ec));
        shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        scene.liquid(c, islandNow, r, SMOKE, 0.42f, 0.02f, 12 * k, 8 * k, 0.7f + 0.6f * ec, 1);
        float p = Ui.clamp(islandSwap.value);
        if (p < 0.999f && islandFrom >= 0) {
            // Stepping: both inside the shape, the old one leaving the way the step goes, the new one arriving.
            c.save();
            islandClip.rewind();
            islandClip.addRoundRect(islandNow, r, r, android.graphics.Path.Direction.CW);
            c.clipPath(islandClip);
            float travel = hNow * 0.7f;
            drawLive(c, islandFrom, cy - islandDir * travel * p, 1 - p, e);
            drawLive(c, islandShows, cy + islandDir * travel * (1 - p), p, e);
            c.restore();
        } else {
            drawLive(c, islandShows, cy, 1, e);
        }
        c.restore();
    }

    /**
     * One activity's content in the dot, centred on `cy` at alpha `a`: its white symbol at the left end (where the dot's
     * centre is at rest; the song's art comes in over it as it opens, on a concentric margin), then, open, its words
     * and its one control.
     */
    private void drawLive(Canvas c, int i, float cy, float a, float e) {
        if (a < 0.01f) return;
        Live l = lives[i];
        float ec = Ui.clamp(e), hNow = islandNow.height();
        float pad = (6 + 2 * ec) * k, ad = Math.max(0, hNow - 2 * pad), ax = islandNow.left + pad + ad / 2;
        float art = i == LIVE_MUSIC && islandArtShader != null ? ec : 0;
        if (art < 0.99f) Symbols.draw(getContext(), c, l.icon, ax, cy, ISLAND_ICON * k, Ui.alpha(0xFFFFFFFF, (1 - art) * a));
        if (art > 0.01f) {
            float sc = ad / Math.max(1, islandArt.getWidth());
            islandMatrix.setScale(sc, sc);
            islandMatrix.postTranslate(ax - ad / 2, cy - ad / 2);
            islandArtShader.setLocalMatrix(islandMatrix);
            islandArtPaint.setShader(islandArtShader);
            islandArtPaint.setAlpha(Math.round(255 * art * a));
            c.drawCircle(ax, cy, ad / 2, islandArtPaint);
            islandArtPaint.setShader(null);
        }
        float ta = Ui.clamp((e - 0.55f) / 0.4f) * a;
        if (ta < 0.01f) return;
        c.save();
        c.clipRect(islandNow);
        float tx = islandNow.left + pad + ad + 16 * k;
        islandTitlePaint.setAlpha(Math.round(255 * ta));
        boolean two = !l.subFit.isEmpty();
        c.drawText(l.titleFit, tx, two ? cy - 3 * k : cy + Ui.halfCap(islandTitlePaint), islandTitlePaint);
        if (two) {
            islandSubPaint.setAlpha(Math.round(0xB3 * ta));
            c.drawText(l.subFit, tx, cy + 23 * k, islandSubPaint);
        }
        // Its controls at the right end: what's playing has ⏮ ⏯ ⏭, the rest one each. The one the focus is on is a white
        // circle with an ink glyph (the tvOS focus), so it's clear the focus is in the dot.
        boolean focused = zone == Z_STATUS && onIsland && islandAt == i && !sheetOpen;
        fill.setShader(null);
        if (i == LIVE_MUSIC || i == LIVE_SLEEP) {
            // ⏮ ⏯ ⏭ and Music Glow's switch, or − + × for the sleep timer (a preset down or up, or off); one that
            // can't is dim.
            int n = i == LIVE_MUSIC ? 4 : 3;
            float slot = 48 * k, last = islandNow.right - 12 * k - slot / 2, g = k;
            for (int b = 0; b < n; b++) {
                float gx = last - (n - 1 - b) * slot;
                boolean on = focused && islandCtrl == b;
                boolean can = i == LIVE_MUSIC ? b == 1 || b == 3 || playing.canSkip(b == 0 ? -1 : 1) : b == 2 || (b == 0 ? sleepDown : sleepUp);
                int ink = on ? INK : 0xFFFFFFFF;
                if (on) {
                    fill.setColor(Ui.alpha(0xFFFFFFFF, ta));
                    c.drawCircle(gx, cy, 22 * k, fill);
                }
                int color = Ui.alpha(ink, ta * (can ? 1 : 0.35f));
                if (i == LIVE_MUSIC) {
                    if (b == 3) drawGlowSwitch(c, gx, cy, color, glowShown);
                    else if (b == 1) drawPlayPause(c, gx, cy, color);
                    else mediaSkip(c, gx, cy, b == 0 ? -1 : 1, color);
                } else {
                    fill.setColor(color);
                    c.save();
                    if (b == 2) c.rotate(45, gx, cy);
                    c.drawRoundRect(gx - 10 * g, cy - 2 * g, gx + 10 * g, cy + 2 * g, 2 * g, 2 * g, fill);
                    if (b > 0) c.drawRoundRect(gx - 2 * g, cy - 10 * g, gx + 2 * g, cy + 10 * g, 2 * g, 2 * g, fill);
                    c.restore();
                }
            }
        } else {
            float gx = islandNow.right - pad - ad / 2, g = k;
            if (focused) {
                fill.setColor(Ui.alpha(0xFFFFFFFF, ta));
                c.drawCircle(gx, cy, 22 * k, fill);
            }
            int color = Ui.alpha(focused ? INK : 0xFFFFFFFF, ta);
            if (i == LIVE_AIR) {
                Symbols.draw(getContext(), c, "chevron_right", gx, cy, 22 * k, color);   // Control Center
            } else {
                // × to turn Do Not Disturb off.
                fill.setColor(color);
                c.save();
                c.rotate(45, gx, cy);
                c.drawRoundRect(gx - 11 * g, cy - 2 * g, gx + 11 * g, cy + 2 * g, 2 * g, 2 * g, fill);
                c.drawRoundRect(gx - 2 * g, cy - 11 * g, gx + 2 * g, cy + 11 * g, 2 * g, 2 * g, fill);
                c.restore();
            }
        }
        c.restore();
    }

    /** ‖ while it plays, ▶ while it doesn't, centred on (gx, cy). */
    private void drawPlayPause(Canvas c, float gx, float cy, int color) {
        float g = k;
        fill.setColor(color);
        if (playing.mediaPlaying) {
            c.drawRoundRect(gx - 8 * g, cy - 11 * g, gx - 2.5f * g, cy + 11 * g, 2 * g, 2 * g, fill);
            c.drawRoundRect(gx + 2.5f * g, cy - 11 * g, gx + 8 * g, cy + 11 * g, 2 * g, 2 * g, fill);
        } else {
            mediaGlyph.reset();
            mediaGlyph.moveTo(gx - 7 * g, cy - 12 * g);
            mediaGlyph.lineTo(gx - 7 * g, cy + 12 * g);
            mediaGlyph.lineTo(gx + 11 * g, cy);
            mediaGlyph.close();
            c.drawPath(mediaGlyph, fill);
        }
    }

    /** AirPods or Beats joined (greet: a new connection, so the card too) or left (name null). */
    private void airConnected(String name, boolean greet) {
        boolean changed = name == null ? airName != null : !name.equals(airName);
        airName = name;
        volumeHud.setAirPods(name != null);
        if (changed && !drawerItems.isEmpty()) {   // Listen Alone's line: their name, or "Pair AirPods"
            showAppearance();
            layoutDetails();
            drawerDirty = true;
        }
        if (changed && pillRect.width() > 0) {
            layoutPill();
            status.invalidate();
        }
        if (name == null) {
            airAlertUntil = 0;
        } else if (greet && changed) {
            // Joined: the dot opens on them by itself for AIR_MS (the Dynamic Island's alert), then closes again.
            airSpatial = AirPods.spatial(audio);
            airAlertUntil = SystemClock.uptimeMillis() + AIR_MS;
            handler.postDelayed(islandCheck, AIR_MS);
        }
        updateIsland();
        if (drawerOpen) readDrawerState(false);
        wake();
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
                    if (others.isEmpty()) {
                        deadEnd(0, dy);
                        return true;
                    }
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
                    if (!browse(dx)) {
                        deadEnd(dx, 0);
                        return true;
                    }
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
                // The dynamic dot (while something plays) sits right of the tabs: ← Welly, → the round buttons, then
                // the bubble.
                if (onIsland) {
                    if (dx == 0) {
                        // ↓ ↑: the next or the previous activity in the dot; past either end, the knock.
                        int n = nextLive(islandAt, dy > 0 ? 1 : -1);
                        if (n < 0) {
                            deadEnd(0, dy);
                            return true;
                        }
                        islandAt = n;
                        islandCtrl = 1;   // on ⏯
                        break;
                    }
                    int lastCtrl = islandAt == LIVE_MUSIC ? 3 : 2;
                    if ((islandAt == LIVE_MUSIC || islandAt == LIVE_SLEEP) && islandCtrl + dx >= 0 && islandCtrl + dx <= lastCtrl) {
                        islandCtrl += dx;   // ← → between ⏮ ⏯ ⏭ ✦ (or − + ×); past them, out of the dot
                        if (islandCtrl == 3) glowShown = Prefs.glowOn();   // read once, as the focus arrives
                        tabBar.invalidate();
                        break;
                    }
                    onIsland = false;
                    if (dx < 0) {
                        zone = Z_TOP;   // ← Welly
                        topIndex = 2;
                        selectTab(2);
                    } else if (!lightButtons.isEmpty()) {
                        zone = Z_TOP;   // → the first round button
                        topIndex = 3;
                    } else {
                        onBubble = true;   // → no round buttons: the bubble
                    }
                    break;
                }
                // The pill, then Control Center's bubble, end the bar's row: ← to the round buttons, then the tabs.
                if (dx > 0 && onBubble) {
                    onBubble = false;   // → from the bubble: the pill
                } else if (dx < 0 && !onBubble) {
                    onBubble = true;    // ← from the pill: the bubble
                } else if (dx < 0 && onBubble && lightButtons.isEmpty() && anyLive()) {
                    onBubble = false;   // ← from the bubble, no round buttons: the dot, on its first activity
                    onIsland = true;
                    islandAt = nextLive(-1, 1);
                    islandCtrl = 1;
                } else if (dx < 0 && topFromTab >= 0) {
                    zone = Z_TOP;   // reached with ↑ from a tab: ← goes back to it
                    topIndex = topFromTab;
                    topFromTab = -1;
                } else if (dx < 0) {
                    zone = Z_TOP;
                    topIndex = 2 + lightButtons.size();
                    if (topIndex < 3) selectTab(topIndex);
                } else if (dy > 0) {
                    topFromTab = -1;
                    enterTab();
                } else {
                    deadEnd(dx, dy);
                    return true;
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = topIndex + dx;
                    if (anyLive() && (topIndex == 2 && dx > 0 || topIndex == 3 && dx < 0)) {
                        zone = Z_STATUS;   // between Welly and the round buttons: the dot, on its first activity
                        topFromTab = -1;
                        onIsland = true;
                        onBubble = false;
                        islandAt = nextLive(-1, 1);
                        islandCtrl = 1;
                    } else if (next > 2 + lightButtons.size()) {
                        zone = Z_STATUS;
                        topFromTab = -1;
                        onIsland = false;   // the bubble comes first, then the pill
                        onBubble = true;
                    } else if (next < 0) {
                        deadEnd(dx, 0);
                        return true;
                    } else {
                        topIndex = next;
                        if (next < 3) selectTab(next);   // focusing a tab selects it (tvOS)
                    }
                } else if (dy > 0) {
                    enterTab();
                } else {
                    // ↑ from the tabs: Control Center's bubble, without walking across (and switching) the tabs.
                    topFromTab = topIndex < 3 ? topIndex : -1;
                    zone = Z_STATUS;
                    onBubble = true;
                    onIsland = false;
                }
                break;
            case Z_HOUSE:
                if (house.move(dx, dy)) {
                    if (!house.bumped()) click.move();   // a dead end there: the house nudged and knocked itself
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
                if (ccOnVolume) {
                    if (dx != 0) {
                        if (!stepVolume(dx)) deadEnd(dx, 0);   // already at the end
                        return true;
                    }
                    if (dy < 0) {
                        ccOnVolume = false;   // ↑: back to the last row of cards
                        drawerIndex = Math.max(0, (drawerItems.size() - 1) / 2 * 2);
                    } else {
                        deadEnd(0, dy);   // the volume is the panel's last row
                        return true;
                    }
                    break;
                }
                if (ccInputs) {
                    // The Inputs list: ↑/↓ along it; ← (or Back) back to the cards.
                    if (dx < 0) {
                        closeInputs();
                    } else if (dx > 0) {
                        deadEnd(dx, 0);
                        return true;
                    } else {
                        int next = inputIndex + dy;
                        if (next < 0 || next >= inputItems.size()) {
                            deadEnd(0, dy);
                            return true;
                        }
                        inputIndex = next;
                    }
                    break;
                }
                if (!ccOnProfiles) return false;   // the cards: move(), spatially
                if (dx != 0) {
                    int next = profileIndex + dx;
                    if (next < 0) {
                        closeDrawer();
                    } else if (next >= profileRects.length) {
                        deadEnd(dx, 0);
                        return true;
                    } else {
                        profileIndex = next;
                    }
                } else if (dy > 0 && profileIndex < profileRects.length
                        && ccStop(ccNearest(profileRects[profileIndex], 0, 1, -1))) {
                    // ↓ from the profiles: the weather, or what's under them
                } else {
                    deadEnd(0, dy);
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
