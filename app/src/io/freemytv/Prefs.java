package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** TCL's Settings button: NOTIFICATION, which only Google TV's home app (off in our mode) answers. */
    static final int KEY_TCL_SETTINGS = KeyEvent.KEYCODE_NOTIFICATION;
    /** A TCL Settings button's value that opens our quick settings panel over what's playing. */
    static final String QUICK_PANEL = HomeMode.PKG + "#panel";
    /** The Netflix button on Vestel TVs and Formovie projectors: STEM_1, which the TV hands to its Netflix key receiver (dumpsys window policy). */
    static final int KEY_STEM_NETFLIX = KeyEvent.KEYCODE_STEM_1;
    /** KTC's (NextStar) Tabii button: BUTTON_8, which the TV hands to its hot-key receiver (its log: "keycode = 195"). */
    static final int KEY_KTC_TABII = KeyEvent.KEYCODE_BUTTON_8;
    /** Xiaomi's Mi button: BUTTON_4, which the TV hands to its GlobalKeyReceiver for PatchWall (its log: "keyCode = 191"). */
    static final int KEY_MI = KeyEvent.KEYCODE_BUTTON_4;
    /** Xiaomi's PatchWall button: BUTTON_5, which the TV hands to the same receiver to open PatchWall (its log: "192"). */
    static final int KEY_XIAOMI_PATCHWALL = KeyEvent.KEYCODE_BUTTON_5;
    /** Sony's app buttons, as SONY_KEY + scan code (read from a BRAVIA's log; the key codes are shared). */
    static final int SONY_KEY = 100_000;
    static final int KEY_SONY_CORE = SONY_KEY + 496, KEY_SONY_NETFLIX = SONY_KEY + 583, KEY_SONY_DISNEY = SONY_KEY + 491,
            KEY_SONY_PRIME = SONY_KEY + 490, KEY_SONY_YOUTUBE = SONY_KEY + 485, KEY_SONY_CRUNCHYROLL = SONY_KEY + 507,
            KEY_SONY_PLAY = SONY_KEY + 587;   // the Google Play button of older remotes: BUTTON_8 (BRAVIA 4K UR3, Android 10)
    /** The SonyLIV button of the BRAVIA VU31's remote (India, Google TV 14), a key code of its own (#395). */
    static final int KEY_SONY_LIV = 3004;

    /**
     * A Sony app button by scan code. The BRAVIA VU31's remote (Google TV 14, #395) sends other codes for the same
     * buttons: BUTTON_4 with 758 for Netflix, BUTTON_2 with 652 for Prime Video, BUTTON_15 with 653 for YouTube.
     */
    static int sonyKey(int scanCode) {
        switch (scanCode) {
            case 758: return KEY_SONY_NETFLIX;
            case 652: return KEY_SONY_PRIME;
            case 653: return KEY_SONY_YOUTUBE;
            default: return SONY_KEY + scanCode;
        }
    }
    /**
     * Xiaomi's app buttons (remotes sold in Russia), handed to its hot-key receiver (its log: "dealWithHotkey
     * keycode:199"). None has a default here: "Xiaomi default" (empty) leaves the key to the TV.
     */
    static final int KEY_XIAOMI_OKKO = KeyEvent.KEYCODE_BUTTON_13, KEY_XIAOMI_VK = KeyEvent.KEYCODE_BUTTON_1,
            KEY_XIAOMI_RUTUBE = KeyEvent.KEYCODE_BUTTON_16, KEY_XIAOMI_KION = KeyEvent.KEYCODE_BUTTON_11,
            KEY_XIAOMI_WINK = KeyEvent.KEYCODE_BUTTON_10, KEY_XIAOMI_IVI = KeyEvent.KEYCODE_BUTTON_12;
    /**
     * Hisense's buttons (Q6N, MediaTek, Android TV 12 tested; read from the key filter): global keys for Google TV's
     * home app (launcherx), or Hisense's 607 for Disney+. The gear sends NOTIFICATION; ALL_APPS (284) and PROFILE_SWITCH
     * (288) are API 28/29 names, so numbers here.
     */
    static final int KEY_HISENSE_PRIME = 295, KEY_HISENSE_YOUTUBE = KeyEvent.KEYCODE_BUTTON_3, KEY_HISENSE_DISNEY = 607,
            KEY_HISENSE_APPS = 284, KEY_HISENSE_SETTINGS = KeyEvent.KEYCODE_NOTIFICATION, KEY_HISENSE_PROFILE = 288;
    /** Google TV Streamer's Vendor_18d1_Product_9451.kl; MACRO_1 (313) was added in API 34. */
    static final int KEY_GOOGLE_YOUTUBE = KeyEvent.KEYCODE_BUTTON_3, KEY_GOOGLE_NETFLIX = KeyEvent.KEYCODE_BUTTON_4,
            KEY_GOOGLE_CUSTOM = 313;
    /** A button's value that opens this home screen's All Apps. */
    static final String ALL_APPS = HomeMode.PKG + "#all";
    /**
     * The JVC G24's (Skyworth) Inputs button, one key here whichever way it comes (BUTTON_16 with scan code 383 or 79,
     * or BUTTON_14 with 240: SkyworthRemote.shortcut), and its gear, NOTIFICATION; TV_SHORTCUTS and QUICK_SETTINGS
     * are the values that open the TV's input list and our Quick Settings panel.
     */
    static final int KEY_JVC_SHORTCUT = 200_383, KEY_JVC_SETTINGS = KeyEvent.KEYCODE_NOTIFICATION;
    static final String TV_SHORTCUTS = HomeMode.PKG + "#tvshortcuts";
    static final String QUICK_SETTINGS = HomeMode.PKG + "#quicksettings";
    static final int[] KEYS = RemoteKeys.GOOGLE_STREAMER
            ? new int[] {KEY_GOOGLE_YOUTUBE, KEY_GOOGLE_NETFLIX, KEY_GOOGLE_CUSTOM}
            : RemoteKeys.JVC_SKYWORTH ? new int[] {KEY_JVC_SHORTCUT, KEY_JVC_SETTINGS}
            : RemoteKeys.HISENSE ? new int[] {KEY_HISENSE_PRIME, KEY_HISENSE_YOUTUBE, KEY_HISENSE_DISNEY,
                    KEY_HISENSE_APPS, KEY_HISENSE_SETTINGS, KEY_HISENSE_PROFILE}
            : RemoteKeys.SONY
            ? new int[] {KEY_SONY_CORE, KEY_SONY_NETFLIX, KEY_SONY_DISNEY, KEY_SONY_PRIME, KEY_SONY_YOUTUBE, KEY_SONY_CRUNCHYROLL,
                    KEY_SONY_PLAY, KEY_SONY_LIV}
            : RemoteKeys.STEM_NETFLIX ? new int[] {KEY_STEM_NETFLIX}
            : RemoteKeys.KTC ? new int[] {KEY_KTC_TABII}
            : RemoteKeys.XIAOMI_PATCHWALL_ONLY ? new int[] {KEY_XIAOMI_PATCHWALL}
            : RemoteKeys.XIAOMI ? new int[] {KEY_MI, KEY_YOUTUBE, KEY_XIAOMI_PATCHWALL, KEY_XIAOMI_OKKO, KEY_XIAOMI_VK,
                    KEY_XIAOMI_RUTUBE, KEY_XIAOMI_KION, KEY_XIAOMI_WINK, KEY_XIAOMI_IVI}
            : new int[] {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL, KEY_TCL_SETTINGS};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
        Ui.textScale = textScale();
    }

    /** The TV's own 12/24-hour setting (Settings → System → Date & time). */
    static boolean clock24() {
        return app != null && android.text.format.DateFormat.is24HourFormat(app);
    }

    /** "auto" (the TV's own 12/24-hour setting), "24" or "12". */
    static String clockFormat() {
        String v = sp().getString("clock", "auto");
        return v.equals("24") || v.equals("12") ? v : "auto";
    }

    static void setClockFormat(String v) { put("clock", v); }

    /** The app's context in this process (null before App.onCreate). */
    static Context context() {
        return app;
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /**
     * Reads the file again if another process changed it. On Android 11 the screensaver runs in the main process
     * (ScreensaverAlone), which otherwise keeps the values it first read, whatever the settings screen in :home saves.
     */
    @SuppressWarnings("deprecation")
    static void reload() {
        String before = language();
        app.getSharedPreferences("settings", Context.MODE_MULTI_PROCESS);
        version++;
        if (!language().equals(before)) Ui.applyLanguage();
        Ui.textScale = textScale();
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    // MARK: - Per profile (MAHITTV-PROFILES-SPEC.md §1.3)

    /** Keys each profile keeps for itself (ProfileManager.prefs); everything else is for the whole TV. */
    private static final String[] PER_PROFILE = {"theme", "sounds", "photos", "shelf", "topButtons", "wallpaper"};

    /** The current profile's own settings. The first time, the manager takes over what the TV had before profiles. */
    private static SharedPreferences pp() {
        SharedPreferences own = ProfileManager.prefs(app);
        if (!own.getBoolean("migrated", false)) {
            SharedPreferences.Editor e = own.edit().putBoolean("migrated", true);
            if (ProfileManager.current(app).manager()) {
                SharedPreferences tv = sp();
                for (String k : PER_PROFILE) {
                    Object v = tv.getAll().get(k);
                    if (v instanceof String) e.putString(k, (String) v);
                    else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                }
            }
            e.apply();
        }
        return own;
    }

    private static void putOwn(String key, String value) {
        pp().edit().putString(key, value).apply();
        version++;
    }

    private static void putOwn(String key, boolean value) {
        pp().edit().putBoolean(key, value).apply();
        version++;
    }

    /** Another profile is in (or the TV was reset): everything reading Prefs should read again. */
    static void profileChanged() {
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    /**
     * Without bumping `version`: a new version makes the home screen start over (and fetch the weather and prices
     * again). Continue Watching's lists don't need that: the home screen reads them on every return (reload).
     */
    private static void putQuietly(String key, Set<String> value) {
        sp().edit().putStringSet(key, new HashSet<>(value)).apply();
    }

    // MARK: - Language and units

    /** "auto" or a language code as picked ("pt", "fr", "zh-CN"…); Ui.applyLanguage finds its catalogue. */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim();
        return v.isEmpty() || v.equalsIgnoreCase("auto") ? "auto" : v;
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** Cartoons and anime out of the picks (Home Settings > Picks): hide_animation in config.properties until changed here. */
    static boolean hideAnimation() {
        return sp().getBoolean("hideAnimation", Config.HIDE_ANIMATION);
    }

    static void setHideAnimation(boolean hide) { put("hideAnimation", hide); }

    // MARK: - Picks from

    private static String offList;
    private static int offListAt = -1;

    /** The services (Recs' keys: "netflix", "prime"...) whose titles are left out of the picks, as ",a,b,". */
    private static String offServices() {
        if (offList == null || offListAt != version) {
            String saved = sp().getString("servicesOff", null);
            if (saved == null) {   // not chosen on the TV yet: what hide_services says
                StringBuilder first = new StringBuilder();
                for (String key : Config.HIDE_SERVICES.split(",")) {
                    key = key.trim().toLowerCase(Locale.ROOT);
                    if (!key.isEmpty()) first.append(first.length() > 0 ? "," : "").append(key);
                }
                saved = first.toString();
            }
            offList = saved.isEmpty() ? "," : "," + saved + ",";
            offListAt = version;
        }
        return offList;
    }

    /** A service found by its app's name is keyed by its package, and is off until switched on. */
    private static boolean byName(String key) {
        return key.indexOf('.') >= 0;
    }

    private static String onServices() {
        String saved = sp().getString("servicesOn", "");
        return saved.isEmpty() ? "," : "," + saved + ",";
    }

    static boolean serviceOff(String key) {
        if (key == null) return false;
        return byName(key) ? !onServices().contains("," + key + ",") : offServices().contains("," + key + ",");
    }

    static void setServiceOff(String key, boolean off) {
        if (byName(key)) {
            String on = onServices().replace("," + key + ",", ",");
            if (!off) on += key + ",";
            put("servicesOn", on.length() > 1 ? on.substring(1, on.length() - 1) : "");
            return;
        }
        String list = offServices();
        list = off ? (serviceOff(key) ? list : list + key + ",") : list.replace("," + key + ",", ",");
        put("servicesOff", list.length() > 1 ? list.substring(1, list.length() - 1) : "");
    }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = pp().getString("theme", Config.THEME.trim());
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { putOwn("theme", v); }

    /** Text Size (tvOS 27's, for the whole TV): "small", "default" or "large". */
    static String textSize() {
        String v = sp().getString("textSize", "default");
        return v.equals("small") || v.equals("large") ? v : "default";
    }

    static void setTextSize(String v) {
        put("textSize", v);
        Ui.textScale = textScale();
    }

    /** What Text Size scales text by: 90 %, 100 % or 115 %. */
    static float textScale() {
        String v = textSize();
        return v.equals("small") ? 0.9f : v.equals("large") ? 1.15f : 1f;
    }

    /** Wi-Fi/Ethernet, Bluetooth and USB icons at the top-left of the home screen. */
    static boolean statusIcons() { return sp().getBoolean("statusicons", false); }
    static void setStatusIcons(boolean on) { put("statusicons", on); }

    // MARK: - City

    static String city() { return sp().getString("city", Place.city()).trim(); }
    static String latitude() { return sp().getString("latitude", Place.latitude()); }
    static String longitude() { return sp().getString("longitude", Place.longitude()); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    /** The home screen's soft click when moving and opening. */
    static boolean sounds() { return pp().getBoolean("sounds", true); }
    static void setSounds(boolean on) { putOwn("sounds", on); }

    /** OK on the screensaver opens the title it shows, in its app; any other key only closes it. Off: any key closes it. */
    static boolean saverWatch() { return sp().getBoolean("saverWatch", false); }
    static void setSaverWatch(boolean on) { put("saverWatch", on); }
    /**
     * The screensaver's look: "photos" (the picks or your photos, the default), "shuffle" (tvOS 27.2: Aerials, Nature
     * Clock, the photos and the Aurora in turn through the day), "nature" (the depth-layered clock),
     * "aurora" (the Aurora sky and a clock), "aerials" (the coast, valley, earth and aurora stills), "landscapes" (five real places, credited) or "black" (a dim
     * clock on black).
     */
    static String saverStyle() { return sp().getString("saverStyle", "photos"); }
    static void setSaverStyle(String style) { put("saverStyle", style); }
    /** The Nature Clock's scene: "rotate" (a new one every minute, the default), "night", "golden", "water", "fog" or "dawn". */
    static String saverScene() { return sp().getString("saverScene", "rotate"); }
    static void setSaverScene(String scene) { put("saverScene", scene); }
    /** The Nature Clock's face: "tall" (Antonio, the default) or "rounded" (Varela Round). */
    static String saverClock() { return sp().getString("saverClock", "tall"); }
    static void setSaverClock(String style) { put("saverClock", style); }

    // MARK: - Brightness, glass, the Top Shelf, Settings' style and the welcome setup

    /**
     * The screen's brightness, 0–100 (Control Center's slider): the TV's own screen_brightness where mahitTV mode can set
     * it (HomeMode.brightness, put back by Original), and a dimmer over mahitTV's screens below 100 (0.78 black at 0).
     */
    static int brightness() { return Math.max(0, Math.min(100, sp().getInt("brightness", 100))); }
    static void setBrightness(int v) {
        sp().edit().putInt("brightness", Math.max(0, Math.min(100, v))).apply();
        version++;
    }
    /** The glass: "liquid" (refractive, the default) or "frosted" (a plain soft blur, no lensing). */
    static String glass() { return "frosted".equals(sp().getString("glass", "liquid")) ? "frosted" : "liquid"; }
    static void setGlass(String v) { put("glass", v); }
    /**
     * The Top Shelf: "app" (the focused streaming app's Continue Watching, the Frosted Horizon elsewhere; the default),
     * "picks" (the recommendations whatever is focused), "all" (a Continue Watching title whatever is focused) or "off".
     */
    static String topShelf() {
        String v = sp().getString("topShelf", "app");
        return v.equals("picks") || v.equals("all") || v.equals("off") ? v : "app";
    }
    static void setTopShelf(String v) { put("topShelf", v); }
    /** Settings: "full" (tvOS's two panes, the default) or "sidebar" (a Liquid Glass sidebar over the home screen). */
    static String settingsStyle() { return "sidebar".equals(sp().getString("settingsStyle", "full")) ? "sidebar" : "full"; }
    static void setSettingsStyle(String v) { put("settingsStyle", v); }
    /** The welcome setup has run (false on a new TV, and after Run Setup Again). */
    static boolean setupDone() { return sp().getBoolean("setupDone", false); }
    static void setSetupDone(boolean on) { put("setupDone", on); }

    // MARK: - Top of the home screen

    static final String TOP_PICKS = "picks", TOP_CONTINUE = "continue", TOP_NONE = "none";
    static final String[] TOP_CONTENT = {TOP_PICKS, TOP_CONTINUE, TOP_NONE};

    /** What the cards under the title show: the recommendations (TMDB), what you were watching in your apps, or nothing. */
    static String topContent() {
        String v = sp().getString("topContent", TOP_PICKS);
        return v.equals(TOP_CONTINUE) || v.equals(TOP_NONE) ? v : TOP_PICKS;
    }

    static void setTopContent(String v) { put("topContent", v); }

    /** Apps left out of Continue Watching (Settings), by package. */
    static Set<String> watchOff() { return new HashSet<>(sp().getStringSet("watchOff", new HashSet<>())); }
    static void setWatchOff(Set<String> packages) { putQuietly("watchOff", packages); }

    /** Single items hidden from Continue Watching (holding OK on a card), as WatchNext.hideKey. */
    static Set<String> watchHidden() { return new HashSet<>(sp().getStringSet("watchHidden", new HashSet<>())); }
    static void setWatchHidden(Set<String> keys) { putQuietly("watchHidden", keys); }

    /** Recent Apps: "off" (the default: Android manages memory on its own), "drawer" (a shortcut in the bottom drawer and the
     *  remote's app-switch key) or "home" (those, and pressing Home twice). The top button is its own choice (Top Buttons). */
    static String recents() {
        String v = sp().getString("recents", "off");
        return v.equals("drawer") || v.equals("home") ? v : "off";
    }

    static void setRecents(String v) { put("recents", v); }

    /**
     * After the TV starts from off, never from standby (Home Settings > At power-on, #396 and #346): "tv" (the TV's
     * choice), "home" (ours, where a TV app takes the screen at boot) or "live" (Live TV, where the TV starts on ours).
     */
    static final String[] POWER_ON = {"tv", "home", "live"};

    static String powerOn() {
        String v = sp().getString("powerOn", "tv");
        return v.equals("home") || v.equals("live") ? v : "tv";
    }

    static void setPowerOn(String v) { put("powerOn", v); }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }
    /** The weather card's forecast: "hours" (the next 6 hours) or "days" (the next 5 days). */
    static String forecast() { return "days".equals(sp().getString("forecast", "hours")) ? "days" : "hours"; }
    static void setForecast(String v) { put("forecast", v); }

    static boolean nowPlaying() { return sp().getBoolean("nowPlaying", false); }
    static void setNowPlaying(boolean on) {   // committed: the main process reads it right away to allow the listener
        sp().edit().putBoolean("nowPlaying", on).commit();
        version++;
    }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    /** Your photos (Photos, sent from a computer) on the screensaver and behind the home screen, once imported. */
    static boolean photos() { return pp().getBoolean("photos", true); }
    static void setPhotos(boolean on) { putOwn("photos", on); }

    /** The profile's normalized wallpaper catalogue ID; Horizon Auto is the default. */
    static String wallpaper() {
        String raw = pp().getString("wallpaper", "horizon:auto");
        String clean = Wallpaper.normalize(raw);
        if (!clean.equals(raw)) putOwn("wallpaper", clean);
        return clean;
    }
    static void setWallpaper(String id) { putOwn("wallpaper", Wallpaper.normalize(id)); }

    /** The golden mahitTV intro at power-on (MAHITTV-PROFILES-SPEC.md §2); for the whole TV. */
    static boolean intro() { return sp().getBoolean("intro", true); }
    static void setIntro(boolean on) { put("intro", on); }

    /** System apps that only have a phone-style launcher entry, listed in All Apps too (§5). */
    static boolean systemApps() { return sp().getBoolean("systemApps", false); }
    static void setSystemApps(boolean on) { put("systemApps", on); }

    /**
     * With an Immich server, where My Photos come from: "default" (none of yours: the picks' photos, as without
     * Immich), "immich" (all its photos) or "album" (photoAlbum, its id; photoAlbumName to show).
     */
    static String photoSource() { return sp().getString("photoSource", "immich"); }
    static String photoAlbum() { return sp().getString("photoAlbum", ""); }
    static String photoAlbumName() { return sp().getString("photoAlbumName", ""); }

    static void setPhotoSource(String source, String album, String name) {
        sp().edit().putString("photoSource", source).putString("photoAlbum", album).putString("photoAlbumName", name).apply();
        version++;
    }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    /**
     * Also list the TV's own system apps (Settings, Files, the manufacturer's apps) in All Apps and the shelf editor.
     * Off keeps the list as it always was: the apps people installed, plus the system ones a TV needs there.
     */
    static boolean androidApps() { return sp().getBoolean("androidapps", false); }
    static void setAndroidApps(boolean on) { put("androidapps", on); }

    // MARK: - TMDB key

    /**
     * The TMDB key given on the TV (Home Settings > TMDB key: from a phone, or typed), or "": then config.properties'
     * one (Recs.key). Never logged.
     */
    static String tmdbKey() { return app == null ? "" : sp().getString("tmdbKey", "").trim(); }

    static void setTmdbKey(String key) {   // committed: ScreensaverAlone reads it from the main process
        sp().edit().putString("tmdbKey", key).commit();
        version++;
    }

    /** The Watchmode key given on the TV, or "": then config.properties' one (Recs.watchmodeKey). Never logged. */
    static String watchmodeKey() { return app == null ? "" : sp().getString("watchmodeKey", "").trim(); }

    static void setWatchmodeKey(String key) {
        sp().edit().putString("watchmodeKey", key).commit();
        version++;
    }

    // MARK: - Shelf

    /**
     * The current profile's Dock: packages in order; until it edits its own, default_dock_apps from config.properties,
     * and null without that (the built-in list applies).
     */
    static List<String> shelf() {
        String v = pp().getString("shelf", null);
        if (v == null) v = Config.DEFAULT_DOCK.trim().isEmpty() ? null : Config.DEFAULT_DOCK.trim();
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        putOwn("shelf", joined(packages));
    }

    /**
     * The Dock as moved on the home screen (hold OK on an app): no new `version`, which would make the home screen start
     * over when it comes back; it already shows the change.
     */
    static void setShelfQuietly(List<String> packages) {
        pp().edit().putString("shelf", joined(packages)).apply();
    }

    private static String joined(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        return sb.toString();
    }

    // MARK: - All Apps' layout (the current profile's order and folders: AppLayout), saved as it's moved

    static AppLayout layout() {
        return AppLayout.parse(pp().getString("layout", null));
    }

    static void setLayout(AppLayout layout) {
        pp().edit().putString("layout", layout.encode()).apply();
    }

    // MARK: - Sleep timer

    /** When the sleep timer ends (System.currentTimeMillis); SleepTimer's alarm says whether it's still on. */
    static long sleepAt() { return sp().getLong("sleepAt", 0); }

    /** When it was set, in uptimeMillis (dumpsys power's clock): a TV turned off since ended it (SleepTimer.check). */
    static long sleepFrom() { return sp().getLong("sleepFrom", 0); }

    // No version++ for the sleep settings: HomeActivity would rebuild the home screen and fetch the weather again for a
    // timer; HomeView reads what it needs on resume and every 15 s (updateSleep).
    static void setSleepAt(long at, long from) {
        sp().edit().putLong("sleepAt", at).putLong("sleepFrom", from).apply();
    }

    /** The sleep timer's minutes left on screen: over any app (SleepOverlay), or where this TV can't, on the home screen. */
    static boolean sleepShown() { return sp().getBoolean("sleepShown", true); }
    static void setSleepShown(boolean on) { sp().edit().putBoolean("sleepShown", on).apply(); }

    /** Where it shows over other apps: SleepOverlay's places, the top center first. */
    static int sleepPlace() { return sp().getInt("sleepPlace", 0); }

    static void setSleepPlace(int place) { sp().edit().putInt("sleepPlace", place).apply(); }

    // MARK: - Top buttons

    /**
     * The round buttons at the top right, in order (HomeApps.TOP_ALL). null until the user edits
     * them (then HomeApps.TOP_DEFAULT applies); empty when they took them all off.
     */
    static List<String> topButtons() {
        String v = pp().getString("topButtons", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setTopButtons(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String p : keys) sb.append(sb.length() == 0 ? "" : ",").append(p);
        putOwn("topButtons", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        if (RemoteKeys.GOOGLE_STREAMER) {
            return keyCode == KEY_GOOGLE_YOUTUBE ? "com.google.android.youtube.tv" : "";
        }
        if (RemoteKeys.JVC_SKYWORTH) {
            if (keyCode == KEY_JVC_SHORTCUT) return TV_SHORTCUTS;
            return keyCode == KEY_JVC_SETTINGS ? QUICK_SETTINGS : "";
        }
        if (RemoteKeys.SONY) {
            if (!sonyGoogleTv()) return "";   // "Sony default": Sony's own receiver opens it
            switch (keyCode) {
                case KEY_SONY_NETFLIX: return "com.netflix.ninja";
                case KEY_SONY_DISNEY: return "com.disney.disneyplus";
                case KEY_SONY_PRIME: return "com.amazon.amazonvideo.livingroom";
                case KEY_SONY_YOUTUBE: return "com.google.android.youtube.tv";
                case KEY_SONY_CRUNCHYROLL: return "com.crunchyroll.crunchyroid";
                case KEY_SONY_LIV: return "com.sonyliv";
                default: return "";
            }
        }
        if (RemoteKeys.HISENSE) {
            switch (keyCode) {
                case KEY_HISENSE_PRIME: return "com.amazon.amazonvideo.livingroom";
                case KEY_HISENSE_YOUTUBE: return "com.google.android.youtube.tv";
                case KEY_HISENSE_DISNEY: return "com.disney.disneyplus";
                case KEY_HISENSE_APPS: return ALL_APPS;
                case KEY_HISENSE_SETTINGS: return "com.android.tv.settings";
                default: return "";
            }
        }
        switch (keyCode) {
            case KEY_NETFLIX: case KEY_STEM_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            case KEY_TCL_SETTINGS: return "com.android.tv.settings";   // TV Settings, as from our home screen (#289)
            case KEY_MI: return HomeMode.PKG;   // our home screen: the remote has no other Home button
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static Boolean sonyGoogleTv;

    /**
     * A Sony with Google TV's home app (launcherx), which takes the app buttons from Sony's receiver and which Free My
     * TV mode turns off: there "Sony default" does nothing (BRAVIA VU31, #395), so the buttons open their apps unless
     * the person picks otherwise. Sony's Android TV sets (VH22) keep Sony's default.
     */
    private static boolean sonyGoogleTv() {
        if (sonyGoogleTv == null) {
            boolean has;
            try {
                app.getPackageManager().getApplicationInfo("com.google.android.apps.tv.launcherx",
                        PackageManager.MATCH_DISABLED_COMPONENTS);
                has = true;
            } catch (PackageManager.NameNotFoundException e) {
                has = false;
            }
            sonyGoogleTv = has;
        }
        return sonyGoogleTv;
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    /** The app a quick second press opens ("<key>d=<package>" lines; "" or none = no double press). */
    private static final String[] keyDoubles = new String[KEYS.length];
    /** The app holding the button opens ("<key>l=<package>" lines; "" or none = no long press). */
    private static final String[] keyLongs = new String[KEYS.length];
    private static long keysStamp = Long.MIN_VALUE;
    /** Every button saved as Nothing (the key service off): a button added since stays Nothing too, not its default. */

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Reads the file again when it changed (call holding keyApps). */
    private static void readKeys() {
        File f = keysFile();
        long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
        if (stamp == keysStamp) return;
        keysStamp = stamp;
        java.util.Arrays.fill(keyApps, null);
        java.util.Arrays.fill(keyDoubles, null);
        java.util.Arrays.fill(keyLongs, null);
        if (stamp == 0) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                for (int i = 0; i < KEYS.length; i++) {
                    String prefix = KEYS[i] + "=", twice = KEYS[i] + "d=", held = KEYS[i] + "l=";
                    if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                    else if (line.startsWith(twice)) keyDoubles[i] = line.substring(twice.length());
                    else if (line.startsWith(held)) keyLongs[i] = line.substring(held.length());
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            readKeys();
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    /** Package a quick second press of the key opens ("" = none: the key opens its app at once). */
    static String keyDoubleApp(int keyCode) {
        synchronized (keyApps) {
            readKeys();
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyDoubles[i] != null) return keyDoubles[i];
            return "";
        }
    }

    /** Package holding the key (RemoteKeys.LONG_MS) opens ("" = none: the key acts on its press as before). */
    static String keyLongApp(int keyCode) {
        synchronized (keyApps) {
            readKeys();
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyLongs[i] != null) return keyLongs[i];
            return "";
        }
    }

    static void setKeyApp(int keyCode, String pkg) {
        writeKeyChoice(keyCode, pkg, PRESS_ONCE);
    }

    static void setKeyDoubleApp(int keyCode, String pkg) {
        writeKeyChoice(keyCode, pkg, PRESS_TWICE);
    }

    static void setKeyLongApp(int keyCode, String pkg) {
        writeKeyChoice(keyCode, pkg, PRESS_HOLD);
    }

    /** Which of a button's three choices a write changes. */
    static final int PRESS_ONCE = 0, PRESS_TWICE = 1, PRESS_HOLD = 2;

    private static void writeKeyChoice(int keyCode, String pkg, int which) {
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) {
            boolean mine = key == keyCode;
            sb.append(key).append('=').append(mine && which == PRESS_ONCE ? pkg : keyApp(key)).append('\n');
            String second = mine && which == PRESS_TWICE ? pkg : keyDoubleApp(key);
            if (!second.isEmpty()) sb.append(key).append("d=").append(second).append('\n');
            String held = mine && which == PRESS_HOLD ? pkg : keyLongApp(key);
            if (!held.isEmpty()) sb.append(key).append("l=").append(held).append('\n');
        }
        if (writeKeys(sb.toString())) version++;
    }
}
