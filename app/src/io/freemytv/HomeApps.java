package io.freemytv;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What shows up on the home screen and in the "All Apps" sheet, and how to open each thing.
 *
 * On the home shelf, each app is its colored logo on a dark card: the official logo in assets/logos/<key>.png
 * (downloaded from Wikimedia Commons by tools/logos.py) or, without one, the logo lifted from the app's TV art (the
 * Android TV "banner"), saved to disk after the first time. In "All Apps", 16:9 cards with the whole TV art (or the
 * icon on a dark card). Shortcuts (live TV, inputs, All Apps, settings) are icon buttons.
 */
final class HomeApps {
    private HomeApps() {}

    static final int APP = 0, LIVE_TV = 1, INPUT = 2, ALL_APPS = 3, SETTINGS = 4, ANDROID_SETTINGS = 5, DEVICE = 6;
    /** The "HDMI Inputs" button: opens the bottom drawer on the inputs (the home screen handles it). */
    static final int INPUTS = 7;
    /** Control Center's Sleep Timer card: Free My TV's Sleep Timer screen. */
    static final int SLEEP = 8;
    /** Control Center's Lock card: back to "Who's Watching?" (the home screen handles it). */
    static final int LOCK = 9;
    /**
     * Control Center's cards from TVOS-LAUNCHER-MERGE-SPEC.md S2/S3: Recent Apps and Close Background Apps (the home
     * screen handles both), Wi-Fi and Bluetooth (the TV's own pages; their state as the card's detail, nothing
     * written), Screensaver (starts it now).
     */
    static final int RECENTS = 10, WIFI = 11, BLUETOOTH = 12, SAVER = 13, CLOSE_BG = 14;
    /** Image size of each card (16:9; on screen it shows up to ~330 px wide). */
    static final int TILE_W = 384, TILE_H = 216;

    static final class Item {
        int kind;
        String key, label, pkg, inputId, number;
        String uri;         // opens this address in the app instead of the app's home (a title's page)
        String title;       // the film or show it opens (a pick), for Continue Watching; null for a plain app
        String mediaKind;   // the pick's "movie" or "tv"
        long tmdbId;        // the pick's TMDB id, 0 when none
        Intent intent;      // DEVICE: the TV maker's own screen it opens
        String symbol;      // icon for the shortcuts and special cards
        String detail;      // a Control Center card's state under its symbol (Wi-Fi: "Connected"), or null
        Bitmap tile;        // 16:9 card (apps and specials): set with setTile
        BitmapShader tileShader;   // the card's, for its rounded corners (made with the card, never while drawing)
        Bitmap logo;        // colored logo with no background (shelf); null = use the card
        Bitmap logoDark;    // version for a light background (YouTube's white icon), if any
        float logoInk;      // fraction of the logo with ink (thin logos are drawn larger, with the same "weight")
        float logoScale = 1; // size tweak by eye (compact logos need more)
        int[] style;        // the composed backdrop (top, bottom colour, logo mode) when the app has no brand style
        float emblemHeight; // > 0: the emblem's height as a share of the tile's (an icon); 0: sized by its ink (a logo)
        boolean connected = true;

        void setTile(Bitmap b) {
            tile = b;
            tileShader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
    }

    /**
     * Apps on the home shelf, in this order, when installed (the first FEW_APPS): key, name, package, brand color (for
     * its logo on glass) and how to lift the logo from the app's TV art: "tint" = a white logo takes the art's
     * background color (Prime Video, Globoplay); "play" = the white triangle inside the red button stays white
     * (YouTube); "white" = only the white of the art, for a white logo over a busy gradient (Kinopoisk); empty = the
     * art's colors as they are. Last, a visual size tweak for the logo. Edit freely.
     */
    private static final String[][] SHELF = {
        {"netflix", "Netflix", "com.netflix.ninja", "E50914", "", "1"},
        {"youtube", "YouTube", "com.google.android.youtube.tv", "FF0033", "play", "1"},
        {"youtube", "YouTube", "com.amazon.firetv.youtube", "FF0033", "play", "1"},
        {"prime", "Prime Video", "com.amazon.amazonvideo.livingroom", "1A98FF", "tint", "0.96"},
        {"prime", "Prime Video", "com.amazon.firebat", "1A98FF", "tint", "0.96"},
        {"prime", "Prime Video", "com.amazon.avod", "1A98FF", "tint", "0.96"},
        {"disney", "Disney+", "com.disney.disneyplus", "1838C8", "", "1"},
        {"max", "Max", "com.wbd.stream", "6C3BE0", "", "1.32"},
        {"max", "HBO Max", "com.hbo.hbonow", "6C3BE0", "", "1.32"},
        {"appletv", "Apple TV", "com.apple.atve.androidtv.appletv", "B8BCC6", "", "1.1"},
        {"appletv", "Apple TV", "com.apple.atve.sony.appletv", "B8BCC6", "", "1.1"},   // Sony's own build
        {"appletv", "Apple TV", "com.apple.atve.amazon.appletv", "B8BCC6", "", "1.1"},
        {"hulu", "Hulu", "com.hulu.livingroomplus", "1CE783", "", "1"},
        {"hulu", "Hulu", "com.hulu.plus", "1CE783", "", "1"},
        {"paramount", "Paramount+", "com.cbs.ott", "0064FF", "", "1"},
        {"paramount", "Paramount+", "com.cbs.ca", "0064FF", "", "1"},          // Paramount+ outside the US (a Sony in the UK; Canada)
        {"now", "NOW", "com.bskyb.nowtv.beta", "1B9C8E", "", "1"},              // UK: Sky's NOW
        {"peacock", "Peacock", "com.peacocktv.peacockandroid", "6E55DC", "", "1"},
        {"peacock", "Peacock", "com.peacock.peacockfiretv", "6E55DC", "", "1"},
        {"globoplay", "Globoplay", "com.globo.globotv", "FF5A1F", "tint", "1"},
        {"kinopoisk", "Кинопоиск", "ru.kinopoisk.tv", "FF5500", "white", "1"},
        {"okko", "Okko", "ru.more.play", "6D43FF", "", "1"},
        {"premier", "Premier", "gpm.tnt_premier", "FF2D55", "", "1"},
        {"amediateka", "Амедиатека", "ru.amediateka", "E31E24", "", "1"},
        {"wink", "Wink", "ru.rt.video.app.tv", "FF5A1F", "", "1"},
        {"applemusic", "Apple Music", "com.apple.android.music", "FA233B", "", "1"},   // the Dock's music (Spotify: an ordinary app)
    };
    /**
     * Apps the shelf shows at once (it scrolls for the rest): 8, today's smallest tiles (178 wide in HomeView's 1760 of
     * content), so a shelf of up to 8 looks as it always did. The built-in list keeps 8, and low-memory TVs hold 8 at
     * most, without scrolling: every app on the shelf keeps its logo in memory, at two sizes.
     */
    private static final int SHELF_VISIBLE = 8, FEW_APPS = 8;

    /** Low memory (TCL's 1 GB models): every MB counts. */
    static boolean lowRam(Context c) {
        return c.getSystemService(android.app.ActivityManager.class).isLowRamDevice();
    }

    static int shelfVisible(Context c) {
        return lowRam(c) ? FEW_APPS : SHELF_VISIBLE;
    }

    static int maxShelf(Context c) {
        return lowRam(c) ? FEW_APPS : Integer.MAX_VALUE;
    }
    /** Live TV apps of some brands (the antenna's channels open through the standard Android request first). */
    static final String[] LIVE_TV_APPS = {"com.tcl.tv", "com.google.android.tv", "com.mediatek.wwtv.tvcenter", "com.sony.dtv.tvx",
            "com.smartdevice.livetv", "com.mediatek.tv.oneworld.tvcenter", "com.amazon.tv.livetv", "com.amazon.mediatek.livetv"};
    /** Kept out of "All Apps" (only hidden, still installed). Add the ones your TV shows that you never use. */
    private static final Set<String> HIDDEN = new HashSet<>(java.util.Arrays.asList(
            "com.google.android.play.games",        // Google Play Games
            "com.tcl.t_solo",                       // TCL: T-Solo
            "com.tcl.waterfall.overseas",           // TCL Channel
            "com.tcl.dashboard",                    // TCL Home
            "com.tcl.ocean.instructions",           // TCL: User Guide
            "com.tcl.guard",                        // TCL: Safety Guard
            "com.homwee.factory",                   // Philco: factory menu
            "com.mstar.android.tv.disclaimercustomization",   // Philco: disclaimer
            "com.homwee.chtvmanager",               // Philco: TV Manager (cleaner)
            "com.homwee.multiscreenshare",          // Philco: Midiacast (Chromecast still works)
            "com.xiaomi.mitv.manualhelp",           // Xiaomi: user manual
            "com.Smartdevice.emanual",              // Skyworth: user manual
            "com.google.android.videos",            // Google Play Movies (discontinued)
            "com.disney.starplus",                  // Star+ (merged into Disney+)
            "com.tcl.exhibit"));                    // TCL: T-Exhibition

    private static final Map<String, Bitmap> TILES = new HashMap<>();   // key → ready card
    private static final Map<String, Bitmap> LOGOS = new HashMap<>();   // key → colored logo with no background

    /**
     * Home screen apps. `extract`: also lifts the logos that are not on disk yet (slow: background only); without it,
     * those apps keep their TV art until the next load.
     */
    static List<Item> shelf(Context c, boolean extract) {
        List<Item> items = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        int max = maxShelf(c);
        for (String pkg : shelfPackages(c)) {
            if (items.size() >= max) break;
            Intent launch = launchIntent(pm, pkg);
            if (launch == null) continue;
            ResolveInfo ri = pm.resolveActivity(launch, 0);
            String[] d = known(pkg);
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            Bitmap bundled = bundledTile(c, pkg, extract);
            if (bundled != null) {
                // The app's HD icon bundled in assets/logos/<package>.png: the whole card, edge to edge.
                it.key = d == null ? pkg : d[0];
                it.label = d != null ? d[1] : ri != null ? String.valueOf(ri.loadLabel(pm)) : pkg;
                it.setTile(bundled);
                items.add(it);
                continue;
            }
            if (d == null) {
                // An app picked in Settings > Shelf that isn't in SHELF: its own name and TV art.
                it.key = pkg;
                it.label = ri != null ? String.valueOf(ri.loadLabel(pm)) : pkg;
                compose(c, it, extract);
            } else {
                it.key = d[0];
                it.label = d[1];
                it.logo = shelfLogo(c, d[0], d[2], d[4], extract);
                if (it.logo == null) it.setTile(appTile(c, d[0], d[2], 0xFF000000 | Integer.parseInt(d[3], 16)));
                if (it.logo != null) it.logoInk = ink(it.logo);
                it.logoDark = ownLogoCached(c, d[0] + "-dark");
                it.logoScale = Float.parseFloat(d[5]);
            }
            items.add(it);
        }
        return items;
    }

    private static String[] known(String pkg) {
        for (String[] d : SHELF) if (d[2].equals(pkg)) return d;
        return null;
    }

    /** The shelf's packages in order: the user's list (Settings > Shelf), or the built-in one. */
    static List<String> shelfPackages(Context c) {
        List<String> custom = Prefs.shelf();
        if (custom != null) return custom;
        List<String> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        PackageManager pm = c.getPackageManager();
        for (String[] d : SHELF) {
            if (out.size() < FEW_APPS && !keys.contains(d[0]) && launchIntent(pm, d[2]) != null) {
                keys.add(d[0]);
                out.add(d[2]);
            }
        }
        return out;
    }

    /** Every app the shelf could hold, as {package, name}: the ones from SHELF first, then the other TV apps by name. */
    static List<String[]> shelfCandidates(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] d : SHELF) if (launchIntent(pm, d[2]) != null && seen.add(d[2])) out.add(new String[] {d[2], d[1]});
        List<String[]> more = new ArrayList<>();
        for (String[] app : launchable(c)) {
            if (app[0].equals(c.getPackageName()) || HIDDEN.contains(app[0]) || !seen.add(app[0])) continue;
            more.add(app);
        }
        Collections.sort(more, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        out.addAll(more);
        return out;
    }

    // MARK: - Top buttons (the round ones at the top right)

    static final String TOP_CONTROL = "control", TOP_LIVE = "live", TOP_INPUTS = "inputs", TOP_SETTINGS = "settings";
    /** As tvOS: none; the status pill at the top right opens Control Center (everything else lives in it). */
    static final List<String> TOP_DEFAULT = Collections.unmodifiableList(new ArrayList<String>());
    /** What Settings > Top Buttons can add, left of the pill. */
    static final List<String> TOP_ALL = Collections.unmodifiableList(java.util.Arrays.asList(TOP_LIVE, TOP_INPUTS, TOP_SETTINGS));
    static final int MAX_TOP = TOP_ALL.size();

    /** The keys of the top buttons, in order (Settings > Top Buttons; none by default). */
    static List<String> topButtonKeys() {
        List<String> saved = Prefs.topButtons();
        if (saved == null) return TOP_DEFAULT;
        List<String> keys = new ArrayList<>();
        for (String key : saved) if (TOP_ALL.contains(key) && !keys.contains(key)) keys.add(key);
        return keys;
    }

    /** The top buttons, ready to draw (0 to 3 of them). */
    static List<Item> topButtons() {
        List<Item> items = new ArrayList<>();
        for (String key : topButtonKeys()) items.add(topButton(key));
        return items;
    }

    private static Item topButton(String key) {
        switch (key) {
            case TOP_CONTROL: return action(INPUTS, TOP_CONTROL, Ui.tr("Control Center"), "tv_settings");
            case TOP_LIVE: return action(LIVE_TV, TOP_LIVE, Ui.tr("light|Live TV"), "live_tv");
            case TOP_INPUTS: return action(INPUTS, TOP_INPUTS, Ui.tr("HDMI Inputs"), "hdmi");
            default: return action(SETTINGS, TOP_SETTINGS, Ui.tr("light|Settings"), "settings");
        }
    }

    /** What can go in the top buttons, as {key, name}. */
    static List<String[]> topCandidates() {
        List<String[]> out = new ArrayList<>();
        for (String key : TOP_ALL) out.add(new String[] {key, topButton(key).label});
        return out;
    }

    /** Live TV, the HDMI inputs with a device sending a signal, and All Apps. */
    static List<Item> shortcuts(Context c) {
        List<Item> items = new ArrayList<>();
        if (liveTv(c) != null) {
            items.add(shortcut(LIVE_TV, "live", Ui.tr("Live TV"), "tv"));
        }
        items.addAll(inputs(c));
        items.add(shortcut(ALL_APPS, "all", Ui.tr("All Apps"), "apps"));
        return items;
    }

    private static Item shortcut(int kind, String key, String label, String symbol) {
        Item it = new Item();
        it.kind = kind;
        it.key = key;
        it.label = label;
        it.symbol = symbol;
        return it;
    }

    /** Icon button (the ones in the corner of the home screen). */
    static Item action(int kind, String key, String label, String symbol) {
        return shortcut(kind, key, label, symbol);
    }

    /** Free My TV settings, in the corner of the home screen. */
    /** The TV's own settings (android.settings.SETTINGS). */
    static Item tvSettings() {
        return shortcut(ANDROID_SETTINGS, "tv-settings", Ui.tr("TV Settings"), "tv_settings");
    }

    static Item settings() {
        return shortcut(SETTINGS, "settings", Ui.tr("Settings"), "settings");
    }

    /** Shortcuts in the bottom drawer. */
    /** Control Center's Sleep Timer card. */
    static Item sleepTimer() {
        return shortcut(SLEEP, "sleep", Ui.tr("Sleep Timer"), "bedtime");
    }

    static List<Item> drawerShortcuts(Context c) {
        List<Item> items = new ArrayList<>();
        items.add(shortcut(ALL_APPS, "all", Ui.tr("All Apps"), "apps"));
        items.add(shortcut(RECENTS, "recents", Ui.tr("Recent Apps"), "recents"));
        items.add(shortcut(WIFI, "wifi", Ui.tr("Wi-Fi"), "wifi"));
        items.add(shortcut(BLUETOOTH, "bluetooth", Ui.tr("Bluetooth"), "bluetooth"));
        items.add(shortcut(SAVER, "screensaver", Ui.tr("Screensaver"), "screensaver"));
        items.add(shortcut(CLOSE_BG, "close-bg", Ui.tr("Close Background Apps"), "memory"));
        items.add(shortcut(LOCK, "lock", Ui.tr("Lock"), "lock"));
        items.add(shortcut(SETTINGS, "settings", Ui.tr("Home Settings"), "settings"));
        items.add(shortcut(ANDROID_SETTINGS, "tv-settings", Ui.tr("TV Settings"), "tv_settings"));
        // HY300 projectors (Hotack, Allwinner H713): keystone and focus live in the maker's settings app, and the
        // stock home screen was the only way to its Miracast receiver. Each shows only where the TV has it.
        device(c, items, "keystone", Ui.tr("Keystone"), "keystone", new Intent("action.htcsettings.prjsettings"));
        device(c, items, "mirroring", Ui.tr("Screen Mirroring"), "cast",
                new Intent().setComponent(new ComponentName("com.softwinner.miracastReceiver", "com.softwinner.miracastReceiver.Miracast")));
        return items;
    }

    /**
     * Control Center's "Close Background Apps": Android's killBackgroundProcesses (what the system itself does when it
     * needs memory: only processes in the background, never the app in front, no data touched) for the apps the TV
     * lists to open, the ones installed or updated by the person and the services' apps; not mahitTV, not Live TV, and
     * not the TV's own system apps. Runs off the UI thread.
     */
    static void closeBackgroundApps(Context c) {
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new HashSet<>(java.util.Arrays.asList(LIVE_TV_APPS));
        skip.add(c.getPackageName());
        for (String[] app : launchable(c)) {
            if (skip.contains(app[0])) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(app[0], 0);
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (!system) am.killBackgroundProcesses(app[0]);
            } catch (PackageManager.NameNotFoundException | SecurityException e) {
                AppLog.w("close " + app[0] + ": " + e.getMessage());
            }
        }
    }

    /**
     * Wi-Fi's and Bluetooth's state as their cards' detail, read only (Android's own pages change them). Bluetooth's
     * needs BLUETOOTH_CONNECT from Android 12, which mahitTV doesn't ask for: there the card shows no state.
     */
    /**
     * Wi-Fi's and Bluetooth's state as Control Center's cards show it: {wifi, bluetooth}, null where unknown. These are
     * system calls: HomeView reads them on its loader thread when the panel opens, never on the UI thread.
     */
    static String[] connectivity(Context c) {
        String wifi = null, bluetooth = null;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) wifi = Ui.tr("Connected");
            else if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) wifi = Ui.tr("Ethernet");
            else if (wm != null) wifi = wm.isWifiEnabled() ? Ui.tr("state|On") : Ui.tr("state|Off");
        } catch (RuntimeException e) {
            AppLog.w("Wi-Fi state: " + e.getMessage());
        }
        try {
            // isEnabled needs BLUETOOTH up to Android 11 (in the manifest) and no permission from 12 on; a TV that
            // refuses anyway (SecurityException) just shows no state.
            android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (bt != null) bluetooth = bt.isEnabled() ? Ui.tr("state|On") : Ui.tr("state|Off");
        } catch (RuntimeException e) {
            AppLog.w("Bluetooth state: " + e.getMessage());
        }
        return new String[] {wifi, bluetooth};
    }

    /** Puts connectivity()'s states on the cards (on the UI thread). */
    static void showConnectivity(List<Item> items, String[] state) {
        for (Item it : items) {
            if (it.kind == WIFI) it.detail = state[0];
            else if (it.kind == BLUETOOTH) it.detail = state[1];
        }
    }

    private static void device(Context c, List<Item> items, String key, String label, String symbol, Intent intent) {
        if (c.getPackageManager().resolveActivity(intent, 0) == null) return;
        Item it = shortcut(DEVICE, key, label, symbol);
        it.intent = intent;
        items.add(it);
    }

    /** A streaming service's app (the "Watch" button of the featured pick). */
    static Item app(String pkg, String label, String uri) {
        Item it = app(pkg, label);
        it.uri = uri;
        return it;
    }

    /** A pick (Top Shelf, screensaver, Welly): its app, opened on the title, logged by title in Continue Watching. */
    static Item app(Recs.Pick p) {
        Item it = app(p.pkg, Recs.serviceName(p), p.link);
        it.title = p.title;
        it.mediaKind = p.kind;
        it.tmdbId = p.id;
        return it;
    }

    static Item app(String pkg, String label) {
        Item it = new Item();
        it.kind = APP;
        it.key = pkg;
        it.pkg = pkg;
        it.label = label;
        return it;
    }

    /** For the bottom drawer: live TV and every HDMI input (the ones with no device are shown dimmed). */
    static List<Item> allInputs(Context c) {
        List<Item> items = new ArrayList<>();
        if (liveTv(c) != null) items.add(shortcut(LIVE_TV, "live", Ui.tr("Live TV"), "tv"));
        items.addAll(inputs(c, true));
        return items;
    }

    private static List<Item> inputs(Context c) {
        return inputs(c, false);
    }

    /**
     * HDMI inputs: only the ones with a device sending a signal (or all of them, with `all`). A name you gave the input
     * in the TV's settings is used as the label; without one, the name the device reports over HDMI-CEC ("PlayStation 5").
     * The icon follows what that name says the device is (see deviceSymbol).
     */
    private static List<Item> inputs(Context c, boolean all) {
        List<Item> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> list = new ArrayList<>();
        // A device that talks HDMI-CEC gets its own input, a child of the port's, named after the device: port input id
        // -> that name.
        Map<String, String> devices = new HashMap<>();
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() == null) {
                    list.add(info);
                } else {
                    devices.put(info.getParentId(), String.valueOf(info.loadLabel(c)).trim());
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
        Pattern digit = Pattern.compile("(\\d)");
        for (int i = 0; i < list.size(); i++) {
            TvInputInfo info = list.get(i);
            int number = i + 1;
            Matcher m = digit.matcher(String.valueOf(info.loadLabel(c)));
            if (m.find()) number = Integer.parseInt(m.group(1));
            // TCL TVs report "connected standby" for every port, with or without a device; only truly "connected"
            // (a device that is on and sending a signal) makes the input show up.
            int state = TvInputManager.INPUT_STATE_DISCONNECTED;
            try {
                state = tim.getInputState(info.getId());
            } catch (RuntimeException ignored) {
            }
            if (!all && state != TvInputManager.INPUT_STATE_CONNECTED) continue;
            CharSequence custom = null;
            try {
                custom = info.loadCustomLabel(c);
            } catch (RuntimeException ignored) {
            }
            String reported = devices.containsKey(info.getId()) ? devices.get(info.getId()) : "";
            String label = custom != null && custom.length() > 0 ? custom.toString()
                    : !reported.isEmpty() && state == TvInputManager.INPUT_STATE_CONNECTED ? reported : "HDMI " + number;
            String symbol = state == TvInputManager.INPUT_STATE_CONNECTED || custom != null
                    ? deviceSymbol(label) : "hdmi";
            Item it = shortcut(INPUT, "hdmi" + number, label, symbol);
            it.inputId = info.getId();
            it.number = String.valueOf(number);
            it.connected = state == TvInputManager.INPUT_STATE_CONNECTED;
            out.add(it);
        }
        Collections.sort(out, (a, b) -> a.number.compareTo(b.number));
        // Allwinner's player opens only HDMI 1 (HY300: one port, though the board lists three)
        if (allwinnerInputs(c)) out.removeIf(it -> !"1".equals(it.number));
        // MIUI TV knows three HDMI ports; a board reporting a fourth leaves it with no way to be opened (a Redmi TV
        // lists HDMI 4 and answers no number for it), so it isn't shown.
        else if (xiaomiInputs(c)) out.removeIf(it -> !xiaomiPort(it));
        return out;
    }

    /** Allwinner's live TV player (HY300 projectors), the stock home screen's way to HDMI: it ignores the input asked for. */
    private static final ComponentName ALLWINNER_TV = new ComponentName("com.softwinner.awlivetv", "com.softwinner.awlivetv.MainActivity");
    /** Xiaomi's own source player (MIUI TV, the Chinese firmware): it takes the port as a number of its own. */
    private static final ComponentName XIAOMI_SOURCES = new ComponentName("com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.tvplayer.ExternalSourceActivity");
    private static final String XIAOMI_SOURCES_ACTION = "com.xiaomi.mitv.tvplayer.EXTSRC_PLAY";
    /** MIUI TV's numbers for HDMI 1 to 3 (VGA is 0 and AV 2). A number it doesn't know drops the TV to no source at all. */
    private static final int[] XIAOMI_HDMI = {23, 24, 25};

    /** Whether the activity is really there: Intent.resolveActivity hands an explicit component back without looking. */
    private static boolean exists(Context c, ComponentName component) {
        return new Intent().setComponent(component).resolveActivityInfo(c.getPackageManager(), 0) != null;
    }

    /** No app opens a passthrough input, so only the maker's own player can reach the TV's inputs. */
    private static boolean noPassthrough(Context c) {
        Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput("x"));
        return view.resolveActivity(c.getPackageManager()) == null;
    }

    /** No app opens a passthrough input, but Allwinner's player is there. */
    private static boolean allwinnerInputs(Context c) {
        return noPassthrough(c) && exists(c, ALLWINNER_TV);
    }

    /** No app opens a passthrough input, but Xiaomi's source player is there (MIUI TV, a Redmi TV on Android 9 tested). */
    private static boolean xiaomiInputs(Context c) {
        return noPassthrough(c) && exists(c, XIAOMI_SOURCES);
    }

    /** The port's number, from the digit in its name, or 0 when it had none. */
    private static int port(Item it) {
        try {
            return Integer.parseInt(it.number);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A port MIUI TV has a number for: the others can't be opened at all, so they aren't shown. */
    private static boolean xiaomiPort(Item it) {
        return port(it) >= 1 && port(it) <= XIAOMI_HDMI.length;
    }

    /**
     * How to open an HDMI input: the standard passthrough request, or, where no app answers it, the maker's own
     * player. Xiaomi's takes the port as its own "input" number.
     */
    private static Intent inputIntent(Context c, Item it) {
        if (allwinnerInputs(c)) return new Intent().setComponent(ALLWINNER_TV);
        if (xiaomiInputs(c) && xiaomiPort(it)) {
            return new Intent(XIAOMI_SOURCES_ACTION).setComponent(XIAOMI_SOURCES)
                    .putExtra("input", XIAOMI_HDMI[port(it) - 1]);
        }
        return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.inputId));
    }

    /** Words in a device's name, by the icon they pick: a game console, a streaming box, a sound system, a TV box. */
    private static final String[][] DEVICE_WORDS = {
        {"gamepad", "playstation", "ps5", "ps4", "ps3", "xbox", "nintendo", "switch", "steam deck", "console", "game"},
        {"play", "apple tv", "chromecast", "fire tv", "firetv", "roku", "shield", "mi box", "tv box", "tvbox", "stick", "streaming", "google tv", "android tv"},
        {"speaker", "soundbar", "sound bar", "receiver", "denon", "yamaha", "marantz", "onkyo", "bose", "sonos", "jbl", "audio", "home theater"},
        {"tv", "cable", "set-top", "decoder", "decodificador", "tuner", "sky", "claro", "vivo"},
    };

    /** The icon for an HDMI device, from the words in its name; a name that says nothing (or a plain port) is the HDMI plug. */
    static String deviceSymbol(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String[] group : DEVICE_WORDS) {
            for (int i = 1; i < group.length; i++) if (n.contains(group[i])) return group[0];
        }
        return "hdmi";
    }

    /** Installed, enabled TV apps that are not on the home screen (the TV's Settings is among them), plus our own. */
    static List<Item> others(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new HashSet<>();
        // A built-in app is skipped only while it is on the shelf: one the person took off must still be listed here.
        List<String> onShelf = shelfPackages(c);
        for (String[] d : SHELF) if (onShelf.contains(d[2])) skip.add(d[2]);
        skip.addAll(java.util.Arrays.asList(LIVE_TV_APPS));
        skip.add(c.getPackageName());
        skip.addAll(HIDDEN);
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>(), installed = new HashSet<>();
        for (String[] app : launchable(c)) {
            String pkg = app[0];
            installed.add(pkg);
            if (skip.contains(pkg) || !seen.add(pkg)) continue;
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            it.key = pkg;
            it.label = app[1];
            compose(c, it, true);
            items.add(it);
        }
        synchronized (COMPOSED) {
            COMPOSED.keySet().removeIf(k -> !installed.contains(k));   // uninstalled apps' icons go
        }
        Collections.sort(items, (a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        synchronized (HomeApps.class) {
            TILES.keySet().removeIf(k -> k.contains(".") && !seen.contains(k));
        }
        items.add(special(c, SETTINGS, "freemytv-settings", "mahitTV", "sparkles"));
        return items;
    }

    /** mahitTV's own entry: a white symbol on graphite, like tvOS's Settings icon. */
    private static Item special(Context c, int kind, String key, String label, String symbol) {
        Item it = shortcut(kind, key, label, symbol);
        it.setTile(symbolTile(c, key, symbol));
        Composed out = new Composed();
        out.style = GRAPHITE;
        out.logo = symbolLogo(c, symbol);
        out.emblemHeight = 0.5f;
        out.ink = 0.4f;
        if (out.logo != null) out.applyTo(it);
        return it;
    }

    /** Opens the item with the system zoom transition growing out of the card (the app starts opening right away). */
    static boolean open(Activity a, Item it, View from, int x, int y, int w, int h) {
        return open(a, it, ActivityOptions.makeScaleUpAnimation(from, x, y, Math.max(w, 1), Math.max(h, 1)).toBundle());
    }

    /** Opens the item; `options` for the transition, or null (the screensaver has no card to grow out of). */
    static boolean open(Context a, Item it, android.os.Bundle options) {
        Intent intent, plain = null;   // plain: the app's own home, when it won't take the title's address
        switch (it.kind) {
            case APP:
                intent = launchIntent(a.getPackageManager(), it.pkg);
                plain = intent;
                if (plain != null) plain.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                if (intent != null && it.uri != null) {
                    intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it.uri)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(it.pkg);
                }
                break;
            case LIVE_TV:
                intent = liveTv(a);
                break;
            case INPUT:
                if (HomeMode.AMAZON && fireInput(a, it)) return true;
                intent = inputIntent(a, it);
                break;
            case SETTINGS:
                intent = new Intent().setComponent(new ComponentName(a, SettingsActivity.class));
                break;
            case SLEEP:
                intent = new Intent().setComponent(new ComponentName(a, SettingsActivity.class))
                        .putExtra(SettingsActivity.EXTRA_SLEEP, true);
                break;
            case ANDROID_SETTINGS:
                intent = new Intent(Settings.ACTION_SETTINGS);
                break;
            case WIFI:
                intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                plain = new Intent(Settings.ACTION_SETTINGS);   // a TV without that page: its settings
                break;
            case BLUETOOTH:
                intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                plain = new Intent(Settings.ACTION_SETTINGS);
                break;
            case SAVER:
                // The system's dream (mahitTV's screensaver in mahitTV mode); without SystemUI's starter, the preview.
                intent = new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator");
                plain = new Intent().setComponent(new ComponentName(a, SettingsActivity.class)).putExtra("screensaver", true);
                break;
            case DEVICE:
                // A fresh task: else Back walks through whatever the maker's settings app had open before
                intent = new Intent(it.intent).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                break;
            default:
                return false;
        }
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(intent, options);
            softEnter(a, it, options);
            opened(a, it);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + it.label + ": " + e.getMessage());
            if (plain == null || plain == intent) return false;
            plain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                a.startActivity(plain);
                softEnter(a, it, null);
                opened(a, it);
                return true;
            } catch (ActivityNotFoundException | SecurityException e2) {
                return false;
            }
        }
    }

    /**
     * An app opened with no card to grow out of (a pick from Welly, the screensaver, Recent Apps): its window comes in
     * from 0.97 and 65% and the home recedes to 1.025 and 25% (200 ms). Adapted from tvOS-Launcher (Apache-2.0).
     */
    private static void softEnter(Context c, Item it, android.os.Bundle options) {
        if (options != null || it.kind != APP || !(c instanceof Activity)) return;
        ((Activity) c).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
    }

    /** Every launch counts as use (the 10-minute lock), and an app or title goes into the profile's Continue Watching. */
    private static void opened(Context c, Item it) {
        ProfileManager.touch(c);
        if (it.kind != APP || it.pkg == null) return;
        WatchLog.launched(c, it.title, it.tmdbId, it.mediaKind, it.label, it.pkg, it.uri);
    }

    /**
     * Fire TV (a TV with Fire OS built in): an HDMI input through Amazon's own input switch, as its home screen does.
     * The standard request goes to Vewd's HbbTV player or to Amazon's Live TV, which both play the antenna's last
     * channel (an app's, like RaiPlay's) instead, and Amazon's input player wants a permission only Amazon's apps have.
     * The switch takes the port's hardware number: HW2 is HDMI 1. Tested on a Xiaomi Fire TV (MediaTek, Fire OS 8).
     */
    private static final Pattern HW = Pattern.compile("/HW(\\d+)$");

    private static boolean fireInput(Context c, Item it) {
        Matcher m = HW.matcher(it.inputId);
        if (!m.find()) return false;
        Intent sw = new Intent().setComponent(new ComponentName("com.amazon.tv.inputpreference.service",
                "com.amazon.tv.inputpreference.service.KaminoInputSwitchService"));
        if (c.getPackageManager().resolveService(sw, 0) == null) return false;
        try {
            if (c.startService(sw.putExtra("inputType", Integer.parseInt(m.group(1)))) == null) return false;
            AppLog.w("Fire TV input: HW" + m.group(1) + " for " + it.label);   // another board may number its ports differently
            return true;
        } catch (RuntimeException e) {
            AppLog.w("Amazon's input switch: " + e.getMessage());
            return false;
        }
    }

    /**
     * Live TV: tune the antenna input directly. MediaTek TV Center otherwise restores the last source used, which can
     * be HDMI, so its explicit activity receives the tuner URI. Other TV apps keep the standard channel-list request.
     */
    static Intent liveTv(Context c) {
        PackageManager pm = c.getPackageManager();
        // Fire TV: the antenna (digital terrestrial) entry of Amazon's Live TV, which plays the antenna's channels or
        // opens the channel scan when there are none yet. The channels request would also offer Vewd's HbbTV player,
        // and Live TV on its own plays the last channel, which can be an app's (RaiPlay's).
        if (HomeMode.AMAZON) {
            ComponentName dtt = new ComponentName("com.amazon.tv.livetv", "com.amazon.tv.livetv.DttIconWrapperActivity");
            // exists(), not resolveActivity: that hands an explicit component back without looking (the sticks have none)
            if (exists(c, dtt)) return new Intent().setComponent(dtt);
        }
        // MediaTek OneWorld TV Center (KTC/NextStar) has one input per tuner mode (antenna, cable, satellite): tuning one
        // switches the TV to that mode and hides the other channel lists, so it opens as it is, on its own last channel.
        Intent oneWorld = launchIntent(pm, "com.mediatek.tv.oneworld.tvcenter");
        if (oneWorld != null) return oneWorld;
        String tuner = tunerInput(c);
        Intent mediaTek = launchIntent(pm, "com.mediatek.wwtv.tvcenter");
        if (mediaTek != null && tuner != null) {
            return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(tuner))
                    .setComponent(mediaTek.getComponent());
        }
        if (tuner != null) {
            Intent channels = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tuner));
            if (channels.resolveActivity(pm) != null) return channels;
        }
        if (mediaTek != null) return mediaTek;
        for (String pkg : LIVE_TV_APPS) {
            Intent app = launchIntent(pm, pkg);
            if (app != null) return app;
        }
        return null;
    }

    /**
     * The TV's antenna (tuner) input, or null. The TV maker's hardware tuner comes first: system apps also declare
     * tuner inputs (TCL: AirPlay), and Android names the hardware ones "…/HW<id>".
     */
    static String tunerInput(Context c) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        String hardware = null, system = null, fallback = null;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_TUNER || info.isPassthroughInput()) continue;
                if (info.getServiceInfo() != null && info.getServiceInfo().applicationInfo != null
                        && (info.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Several hardware tuners (MediaTek: Tuner, Cable, Analog): digital antenna first, analog last.
                    // Haier H32S80EFX lists AnalogInputService/HW1 first, which opens on no channels in DVB-T2 countries.
                    if (info.getId().contains("/HW") && (hardware == null || tunerRank(info.getId()) < tunerRank(hardware))) {
                        hardware = info.getId();
                    }
                    if (system == null) system = info.getId();
                }
                if (fallback == null) fallback = info.getId();
            }
        } catch (RuntimeException ignored) {
        }
        return hardware != null ? hardware : system != null ? system : fallback;
    }

    private static int tunerRank(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        return s.contains("analog") ? 2 : s.contains("cable") ? 1 : 0;
    }

    static List<String[]> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(tv, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (seen.add(pkg)) out.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
        }
        // Phone-style apps too; the system's own only with Settings > Show system apps (and never ours or HIDDEN).
        boolean system = Prefs.systemApps();
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(phone, 0)) {
            String pkg = ri.activityInfo.packageName;
            boolean sys = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (sys && (!system || pkg.equals(c.getPackageName()) || HIDDEN.contains(pkg))) continue;
            if (!seen.add(pkg)) continue;
            out.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
        }
        return out;
    }

    /** How many apps mahitTV found on the TV (Settings > About): what All Apps lists, without our own entry. */
    static int count(Context c) {
        int n = 0;
        for (String[] app : launchable(c)) if (!app[0].equals(c.getPackageName()) && !HIDDEN.contains(app[0])) n++;
        return n;
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    // MARK: - Colored logos (shelf)

    /** Bump when the way logos are lifted changes (the ones saved on disk are redone). */
    private static final int LOGO_VERSION = 3;

    private static Bitmap shelfLogo(Context c, String key, String pkg, String mode, boolean extract) {
        synchronized (LOGOS) {
            if (LOGOS.containsKey(key)) return LOGOS.get(key);
        }
        Bitmap logo = ownLogo(c, key);
        File file = logoFile(c, key, pkg), none = new File(file.getPath() + ".none");
        if (logo == null && file.exists()) logo = BitmapFactory.decodeFile(file.getPath());
        if (logo == null) {
            if (none.exists()) {
                logo = null;
            } else if (!extract) {
                return null;
            } else {
                logo = bannerLogo(c, pkg, mode.equals("tint"), mode.equals("play"), mode.equals("white"));
                saveLogo(logo, file, none);
            }
        }
        if (logo != null) logo.setHasMipMap(true);
        synchronized (LOGOS) {
            LOGOS.put(key, logo);
        }
        return logo;
    }

    /** File for the logo lifted from the TV art: it changes when the app is updated (the art may have changed). */
    private static File logoFile(Context c, String key, String pkg) {
        long updated = 0;
        try {
            updated = c.getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return new File(new File(c.getCacheDir(), "logos"), key + "-" + updated + "-v" + LOGO_VERSION + ".png");
    }

    private static void saveLogo(Bitmap logo, File file, File none) {
        saveLogo(logo, file, none, 0);
    }

    /** `color`: the background a composed logo was lifted from, kept beside it (0: none). */
    private static void saveLogo(Bitmap logo, File file, File none, int color) {
        File dir = file.getParentFile();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String key = file.getName().substring(0, file.getName().lastIndexOf('-', file.getName().lastIndexOf("-v") - 1));
        File[] old = dir.listFiles((d, name) -> name.matches(java.util.regex.Pattern.quote(key) + "-\\d+-v\\d+\\.png(\\.none|\\.color)?"));
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        try (FileOutputStream out = new FileOutputStream(logo != null ? file : none)) {
            if (logo != null) logo.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            AppLog.w("logo not saved: " + e.getMessage());
        }
        if (logo == null || color == 0) return;
        try (FileOutputStream out = new FileOutputStream(file.getPath() + ".color")) {
            out.write(Integer.toHexString(color).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            AppLog.w("logo colour not saved: " + e.getMessage());
        }
    }

    /** Fraction (0 to 1) of the logo's pixels that have ink, by sampling. */
    private static float ink(Bitmap logo) {
        int w = logo.getWidth(), h = logo.getHeight(), hits = 0, total = 0;
        for (int y = 0; y < h; y += Math.max(1, h / 48)) {
            for (int x = 0; x < w; x += Math.max(1, w / 96)) {
                if ((logo.getPixel(x, y) >>> 24) > 128) hits++;
                total++;
            }
        }
        return total == 0 ? 0.4f : Math.max(0.08f, (float) hits / total);
    }

    /**
     * Logo lifted from the app's TV art: the background is removed (estimated from the edges, gradients are fine), the
     * brand colors stay and black turns white (the card is dark). With `tint`, a white logo on a colored background
     * takes the background's color; with `holes`, holes inside colored shapes (the play triangle) turn white; with
     * `white`, only what is white is kept (anything colored or dark is background). The edges are "decontaminated" (no
     * halo from the background).
     */
    private static Bitmap bannerLogo(Context c, String pkg, boolean tint, boolean holes, boolean white) {
        Drawable d = bannerDrawable(c, pkg);
        if (d == null) return null;
        return liftLogo(bannerPixels(d), BANNER_W, BANNER_H, tint, holes, white, false);
    }

    private static final int BANNER_W = 768, BANNER_H = 432;

    /** The banner drawn at its own resolution, then filtered down to 768 × 432 (where the lifting works), as pixels. */
    private static int[] bannerPixels(Drawable d) {
        final int w = BANNER_W, h = BANNER_H;
        float k = detail(d, w, h);
        Bitmap big = Bitmap.createBitmap(Math.round(w * k), Math.round(h * k), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(big);
        canvas.drawColor(0xFF15161C);
        d.setBounds(0, 0, big.getWidth(), big.getHeight());
        d.draw(canvas);
        Bitmap src = shrink(big, w, h);
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        src.recycle();
        return px;
    }

    /**
     * The logo lifted off the banner's pixels (w × h) as described above. `keepDark`: the logo will sit on the banner's
     * own (light) colour again, so its dark ink stays dark.
     */
    private static Bitmap liftLogo(int[] px, int w, int h, boolean tint, boolean holes, boolean white, boolean keepDark) {
        // Edge profiles (a 3% band), smoothed: the background at any point is the "patch" spanning the four sides.
        int band = Math.round(h * 0.03f);
        float[][] left = new float[h][3], right = new float[h][3], top = new float[w][3], bottom = new float[w][3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < band; x++) {
                add(left[y], px[y * w + x], 1f / band);
                add(right[y], px[y * w + w - 1 - x], 1f / band);
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < band; y++) {
                add(top[x], px[y * w + x], 1f / band);
                add(bottom[x], px[(h - 1 - y) * w + x], 1f / band);
            }
        }
        smooth(left, 25);
        smooth(right, 25);
        smooth(top, 41);
        smooth(bottom, 41);
        float[] p00 = top[0], p10 = top[w - 1], p01 = bottom[0], p11 = bottom[w - 1];
        float[] mean = new float[3];
        for (int i = 0; i < 3; i++) mean[i] = (p00[i] + p10[i] + p01[i] + p11[i] + top[w / 2][i] + bottom[w / 2][i] + left[h / 2][i] + right[h / 2][i]) / 8;
        float bgLum = lum(mean), bgSat = sat(mean);
        // Distance of each pixel from the background, then the largest one within 2 px: the ink beside an edge pixel.
        float[] dist = new float[w * h], near = new float[w * h], tmp = new float[w * h];
        float[] bg = new float[3], col = new float[3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                background(bg, x, y, w, h, top, bottom, left, right);
                dist[y * w + x] = distance(px[y * w + x], bg);
            }
        }
        maxFilter(dist, tmp, w, h, 2, true);
        maxFilter(tmp, near, w, h, 2, false);
        int[] out = new int[w * h];
        int opaque = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int p = px[idx];
                col[0] = ((p >> 16) & 255) / 255f;
                col[1] = ((p >> 8) & 255) / 255f;
                col[2] = (p & 255) / 255f;
                float a;
                if (white) {
                    // Only what is white is ink: on a busy gradient the distance from the background says nothing.
                    a = smoothstep(0.55f, 0.85f, lum(col)) * (1 - smoothstep(0.12f, 0.3f, sat(col)));
                    if (a <= 0.004f) continue;
                    col[0] = col[1] = col[2] = 1f;
                } else {
                    // Is there ink here (the old thresholds, on the nearby ink), and how much of this pixel it covers
                    // (its distance against that ink's): solid parts stay opaque, edges keep their anti-aliasing.
                    float gate = smoothstep(0.07f, 0.22f, near[idx]);
                    if (gate <= 0.004f) continue;
                    a = gate * clamp((dist[idx] - 0.04f) / (0.85f * (near[idx] - 0.04f)));
                    if (a <= 0.004f) continue;
                    background(bg, x, y, w, h, top, bottom, left, right);
                    for (int i = 0; i < 3; i++) col[i] = clamp((col[i] - (1 - a) * bg[i]) / a);   // no background halo
                    float l = lum(col), s = sat(col);
                    if (!keepDark && bgLum > 0.6f && l < 0.42f && s < 0.3f) {
                        col[0] = col[1] = col[2] = 1f;                  // black text on a light background → white
                    } else if (tint && bgLum <= 0.6f && bgLum > 0.1f && bgSat > 0.35f && l > 0.7f && s < 0.25f) {
                        vivid(bg, col);                                 // white logo on brand color → that color, vivid
                    }
                }
                out[idx] = (Math.round(a * 255) << 24) | (Math.round(col[0] * 255) << 16) | (Math.round(col[1] * 255) << 8) | Math.round(col[2] * 255);
                if (a > 0.5f) opaque++;
            }
        }
        float area = (float) opaque / (w * h);
        if (area < 0.01f || area > 0.6f) return null;    // couldn't split the logo from the background: keep the card
        if (holes) fillHoles(out, w, h);
        Bitmap logo = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
        return trimLogo(logo);
    }

    private static Drawable bannerDrawable(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            Intent launch = launchIntent(pm, pkg);
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                int res = ai.getBannerResource();
                if (res != 0) {
                    android.content.res.Resources r = pm.getResourcesForApplication(ai.applicationInfo);
                    android.util.TypedValue value = new android.util.TypedValue();
                    r.getValue(res, value, true);
                    Drawable d = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                    if (d != null) AppLog.w("TV banner for " + pkg + ": " + value.string + " (" + d.getIntrinsicWidth() + "×" + d.getIntrinsicHeight() + ")");
                    return d;
                }
            }
            return pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** The background at (x, y): the patch spanning the four smoothed edge profiles (gradients are fine). */
    private static void background(float[] bg, int x, int y, int w, int h, float[][] top, float[][] bottom,
                                   float[][] left, float[][] right) {
        float u = (float) x / (w - 1), v = (float) y / (h - 1);
        float[] p00 = top[0], p10 = top[w - 1], p01 = bottom[0], p11 = bottom[w - 1];
        for (int i = 0; i < 3; i++) {
            bg[i] = (1 - v) * top[x][i] + v * bottom[x][i] + (1 - u) * left[y][i] + u * right[y][i]
                    - ((1 - u) * (1 - v) * p00[i] + u * (1 - v) * p10[i] + (1 - u) * v * p01[i] + u * v * p11[i]);
        }
    }

    /** Color distance (0 to 1, RMS of the channels) between a pixel and the background. */
    private static float distance(int p, float[] bg) {
        float r = ((p >> 16) & 255) / 255f - bg[0], g = ((p >> 8) & 255) / 255f - bg[1], b = (p & 255) / 255f - bg[2];
        return (float) Math.sqrt((r * r + g * g + b * b) / 3);
    }

    /** Largest value within r in one direction. */
    private static void maxFilter(float[] in, float[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            for (int i = 0; i < n; i++) {
                float m = 0;
                for (int j = Math.max(0, i - r); j <= Math.min(n - 1, i + r); j++) m = Math.max(m, in[base + j * step]);
                out[base + i * step] = m;
            }
        }
    }

    /** Closed holes inside colored shapes (the play triangle) turn white; the ones in letters don't. */
    private static void fillHoles(int[] px, int w, int h) {
        byte[] mark = new byte[w * h];   // 1 = outside (reached from the border), 2 = hole already visited
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) {
            for (int y : new int[] {0, h - 1}) {
                int i = y * w + x;
                if (mark[i] == 0 && (px[i] >>> 24) < 128) { mark[i] = 1; queue[tail++] = i; }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x : new int[] {0, w - 1}) {
                int i = y * w + x;
                if (mark[i] == 0 && (px[i] >>> 24) < 128) { mark[i] = 1; queue[tail++] = i; }
            }
        }
        while (head < tail) {
            int i = queue[head++], x = i % w, y = i / w;
            int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
            for (int n : next) {
                if (n >= 0 && mark[n] == 0 && (px[n] >>> 24) < 128) { mark[n] = 1; queue[tail++] = n; }
            }
        }
        for (int start = 0; start < w * h; start++) {
            if (mark[start] != 0 || (px[start] >>> 24) >= 128) continue;
            head = 0;
            tail = 0;
            queue[tail++] = start;
            mark[start] = 2;
            float satSum = 0;
            int border = 0;
            while (head < tail) {
                int i = queue[head++], x = i % w, y = i / w;
                int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
                for (int n : next) {
                    if (n < 0) continue;
                    if ((px[n] >>> 24) >= 128) {
                        float[] c = {((px[n] >> 16) & 255) / 255f, ((px[n] >> 8) & 255) / 255f, (px[n] & 255) / 255f};
                        satSum += sat(c);
                        border++;
                    } else if (mark[n] == 0) {
                        mark[n] = 2;
                        queue[tail++] = n;
                    }
                }
            }
            if (border > 0 && satSum / border > 0.45f && tail < w * h / 20) {
                for (int k = 0; k < tail; k++) px[queue[k]] = 0xFFFFFFFF;
            }
        }
    }

    private static Bitmap trimLogo(Bitmap b) {
        Bitmap t = trim(b);
        int pad = Math.max(2, Math.round(Math.max(t.getWidth(), t.getHeight()) * 0.02f));
        Bitmap out = Bitmap.createBitmap(t.getWidth() + 2 * pad, t.getHeight() + 2 * pad, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(t, pad, pad, null);
        return out;
    }

    private static void add(float[] acc, int p, float k) {
        acc[0] += ((p >> 16) & 255) / 255f * k;
        acc[1] += ((p >> 8) & 255) / 255f * k;
        acc[2] += (p & 255) / 255f * k;
    }

    /** Moving average (odd window) on each channel of the profile. */
    private static void smooth(float[][] profile, int window) {
        int n = profile.length, r = window / 2;
        float[][] copy = new float[n][];
        for (int i = 0; i < n; i++) copy[i] = profile[i].clone();
        for (int i = 0; i < n; i++) {
            float[] sum = new float[3];
            int count = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(n - 1, i + r); j++) {
                for (int k = 0; k < 3; k++) sum[k] += copy[j][k];
                count++;
            }
            for (int k = 0; k < 3; k++) profile[i][k] = sum[k] / count;
        }
    }

    private static float lum(float[] c) {
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    }

    private static float sat(float[] c) {
        float max = Math.max(c[0], Math.max(c[1], c[2])), min = Math.min(c[0], Math.min(c[1], c[2]));
        return max <= 0 ? 0 : (max - min) / max;
    }

    /** The local background color (gradients included), vivid and bright: the logo stands out on the dark card. */
    private static void vivid(float[] bg, float[] out) {
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV(Math.round(bg[0] * 255), Math.round(bg[1] * 255), Math.round(bg[2] * 255), hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = 1f;
        int c = android.graphics.Color.HSVToColor(hsv);
        out[0] = ((c >> 16) & 255) / 255f;
        out[1] = ((c >> 8) & 255) / 255f;
        out[2] = (c & 255) / 255f;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    // MARK: - The automatic tvOS icon composer (DESIGN-TVOS.md §7)

    /** What the composer made for an app: a backdrop (a picture `tile`, or a gradient `style`) and an emblem. */
    private static final class Composed {
        Bitmap tile, logo;
        int[] style;
        float ink = 0.4f, emblemHeight;

        void applyTo(Item it) {
            if (tile != null) it.setTile(tile);
            it.logo = logo;
            it.logoInk = ink;
            it.style = style;
            it.emblemHeight = emblemHeight;
        }
    }

    private static final Map<String, Composed> COMPOSED = new HashMap<>();   // package → its icon
    /** tvOS's Settings grey, top to bottom, with a white emblem. */
    private static final int[] GRAPHITE = {0xFF8E8E93, 0xFF48484A, 1};
    /** Neutral icons (white, grey, black): a darker graphite, their own colours on it. */
    private static final int[] NEUTRAL = {0xFF5A5A5F, 0xFF2C2C2E, 0};
    /** Bump when the composer changes the logos it saves (they are lifted again). */
    private static final int COMPOSE_VERSION = 1;

    /**
     * Gives an app that has no brand style a tvOS-like icon: (1) a TV banner on a flat colour → that colour as a rich
     * gradient and the logo lifted off it; (2) a banner that is a picture → the picture (HomeView crops it to 5:3); (3)
     * only a launcher icon → an adaptive icon's background layer as the backdrop (a gradient of its colour when flat)
     * and its foreground as the emblem, or a legacy icon centred on a gradient of its dominant vivid colour. HomeView
     * adds the depth, the gloss and the rim. `extract`: may do the slow part (lifting a banner's logo, then saved on
     * disk); without it, an app not lifted yet shows its banner until the next load.
     */
    static void compose(Context c, Item it, boolean extract) {
        String pkg = it.pkg;
        Composed done;
        synchronized (COMPOSED) {
            done = COMPOSED.get(pkg);
        }
        if (done != null) {
            done.applyTo(it);
            return;
        }
        Composed out;
        Drawable banner = bannerDrawable(c, pkg);
        Bitmap bundled = bundledTile(c, pkg, extract);
        if (bundled != null) {
            out = new Composed();
            out.tile = bundled;
        } else if (banner != null) {
            out = flatBanner(c, pkg, banner, extract);
            if (out == null || out == PICTURE) {
                Composed picture = new Composed();
                picture.tile = appTile(c, null, pkg, 0);
                picture.applyTo(it);
                if (out == null) return;   // not lifted yet: tried again on the next load (in the background)
                out = picture;
            }
        } else {
            out = iconComposed(c, pkg);
        }
        synchronized (COMPOSED) {
            COMPOSED.put(pkg, out);
        }
        out.applyTo(it);
    }

    private static final Composed PICTURE = new Composed();

    /**
     * A banner on a flat colour: the logo lifted off it and that colour (both kept on disk); PICTURE when the banner is
     * a picture (or the logo wouldn't come off); null when it isn't known yet and `extract` is false.
     */
    private static Composed flatBanner(Context c, String pkg, Drawable d, boolean extract) {
        String key = "auto" + COMPOSE_VERSION + "-" + pkg;
        File file = logoFile(c, key, pkg), none = new File(file.getPath() + ".none"), color = new File(file.getPath() + ".color");
        if (none.exists()) return PICTURE;
        if (file.exists() && color.exists()) {
            Bitmap logo = BitmapFactory.decodeFile(file.getPath());
            int bg = readColor(color);
            if (logo != null && bg != 0) return liftedComposed(logo, bg);
        }
        if (!extract) return null;
        int[] px = bannerPixels(d);
        int bg = flatColor(px, BANNER_W, BANNER_H);
        Bitmap logo = null;
        if (bg != 0) {
            float[] c3 = {((bg >> 16) & 255) / 255f, ((bg >> 8) & 255) / 255f, (bg & 255) / 255f};
            logo = liftLogo(px, BANNER_W, BANNER_H, false, false, false, lum(c3) > 0.6f);
            if (logo != null) logo = fit(logo, 360, 160);
        }
        saveLogo(logo, file, none, bg);
        return logo == null ? PICTURE : liftedComposed(logo, bg);
    }

    private static Composed liftedComposed(Bitmap logo, int bg) {
        Composed out = new Composed();
        out.logo = logo;
        out.ink = ink(logo);
        out.style = richGradient(bg, 0);
        return out;
    }

    private static int readColor(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[16];
            int n = in.read(b);
            return n <= 0 ? 0 : (int) Long.parseLong(new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim(), 16);
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The banner's background colour when it is flat (≥ 85% of a 3% band all round within a small distance of the
     * band's mean), else 0.
     */
    private static int flatColor(int[] px, int w, int h) {
        int band = Math.max(1, Math.round(h * 0.03f));
        float[] mean = new float[3];
        int n = 0;
        for (int pass = 0; pass < 2; pass++) {
            int close = 0;
            for (int y = 0; y < h; y += 2) {
                for (int x = 0; x < w; x += 2) {
                    if (y >= band && y < h - band && x >= band && x < w - band) continue;
                    int p = px[y * w + x];
                    if (pass == 0) {
                        add(mean, p, 1);
                        n++;
                    } else if (distance(p, mean) < 0.07f) {
                        close++;
                    }
                }
            }
            if (pass == 0) {
                if (n == 0) return 0;
                for (int i = 0; i < 3; i++) mean[i] /= n;
            } else if (close < n * 0.85f) {
                return 0;
            }
        }
        return 0xFF000000 | (Math.round(mean[0] * 255) << 16) | (Math.round(mean[1] * 255) << 8) | Math.round(mean[2] * 255);
    }

    /** A rich two-stop gradient from a colour: 12% lighter at the top, 28% darker at the bottom (HomeView adds the lift). */
    private static int[] richGradient(int color, int mode) {
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        int top = 0xFF000000 | (Math.round(r + (255 - r) * 0.12f) << 16) | (Math.round(g + (255 - g) * 0.12f) << 8) | Math.round(b + (255 - b) * 0.12f);
        int bottom = 0xFF000000 | (Math.round(r * 0.72f) << 16) | (Math.round(g * 0.72f) << 8) | Math.round(b * 0.72f);
        return new int[] {top, bottom, mode};
    }

    /** An app with only a launcher icon. */
    private static Composed iconComposed(Context c, String pkg) {
        Composed out = new Composed();
        Drawable icon = appIcon(c, pkg);
        if (icon == null) {
            out.style = NEUTRAL;
            out.tile = darkTile(TILE_W, TILE_H);
            return out;
        }
        if (icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            // The background layer fills the tile (its colour as a gradient when it's flat); the foreground is the emblem.
            Bitmap back = a.getBackground() == null ? null : drawCovering(a.getBackground(), 400, 240);
            int flat = back == null ? 0 : flatColor(pixels(back), back.getWidth(), back.getHeight());
            if (back == null || flat != 0) {
                out.style = richGradient(back == null ? 0xFF3A3A3C : flat, 0);
                if (back != null) back.recycle();
            } else {
                out.tile = back;
            }
            if (a.getForeground() != null) {
                Bitmap fore = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888);
                a.getForeground().setBounds(0, 0, 432, 432);
                a.getForeground().draw(new Canvas(fore));
                Bitmap trimmed = trim(fore);
                out.logo = trimmed.getWidth() < 4 ? null : fit(trimmed, 360, 200);
            }
            out.emblemHeight = 0.5f;
        } else {
            // A legacy icon (a free-form picture): on a gradient of its own dominant vivid colour, centred at 58%.
            Bitmap pic = Bitmap.createBitmap(288, 288, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 288, 288);
            icon.draw(new Canvas(pic));
            Bitmap trimmed = trim(pic);
            int[] edges = filledSquareEdges(trimmed);
            if (edges != null) {
                // A filled square (its own background): its top and bottom edge colours behind it and the square
                // larger, so it reads as one picture rather than a sticker. Adapted from tvOS-Launcher (Apache-2.0).
                out.style = new int[] {edges[0], edges[1], 0};
                out.logo = fit(trimmed, 240, 240);
                out.emblemHeight = 0.82f;
            } else {
                int dominant = dominantColor(trimmed);
                out.style = dominant == 0 ? NEUTRAL : richGradient(dominant, 0);
                out.logo = fit(trimmed, 240, 240);
                out.emblemHeight = 0.58f;
            }
        }
        if (out.logo != null) out.ink = ink(out.logo);
        if (out.logo == null && out.tile == null) out.tile = darkTile(TILE_W, TILE_H);
        return out;
    }

    /**
     * A legacy icon that is a filled, nearly square picture (corners opaque, aspect 0.85 to 1.18): the mean colours of
     * its top and bottom edge bands, else null.
     */
    private static int[] filledSquareEdges(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        if (w < 16 || h < 16) return null;
        float aspect = (float) w / h;
        if (aspect < 0.85f || aspect > 1.18f) return null;
        int inset = Math.max(2, Math.round(Math.min(w, h) * 0.12f));
        int[] corners = {b.getPixel(inset, inset), b.getPixel(w - 1 - inset, inset), b.getPixel(inset, h - 1 - inset),
                b.getPixel(w - 1 - inset, h - 1 - inset)};
        for (int px : corners) if ((px >>> 24) < 0xF0) return null;
        int band = Math.max(1, Math.round(h * 0.04f));
        return new int[] {bandMean(b, 0, band), bandMean(b, h - band, h)};
    }

    private static int bandMean(Bitmap b, int y0, int y1) {
        long r = 0, g = 0, bl = 0, n = 0;
        int w = b.getWidth(), step = Math.max(1, w / 48);
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < w; x += step) {
                int px = b.getPixel(x, y);
                if ((px >>> 24) < 0x80) continue;
                r += (px >> 16) & 255;
                g += (px >> 8) & 255;
                bl += px & 255;
                n++;
            }
        }
        if (n == 0) return 0xFF3A3A3C;
        return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (bl / n);
    }

    private static Drawable appIcon(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.icon != 0) {
                try {
                    Drawable d = pm.getResourcesForApplication(ai).getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
                    if (d != null) return d;
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
            return pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** `d` drawn to cover w × h (centred, its aspect kept). */
    private static Bitmap drawCovering(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int side = Math.max(w, h);
        d.setBounds((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
        d.draw(new Canvas(b));
        return b;
    }

    private static int[] pixels(Bitmap b) {
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        return px;
    }

    /**
     * The icon's dominant vivid colour: a hue histogram (24 bins) weighted by saturation × value, the heaviest bin's
     * mean, made rich (saturation ≥ 0.55, value 0.45 to 0.8); 0 when the icon is neutral (white, grey or black).
     */
    private static int dominantColor(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        float[] weight = new float[24], r = new float[24], g = new float[24], bl = new float[24];
        float[] hsv = new float[3];
        float total = 0;
        int count = 0;
        int step = Math.max(1, Math.min(w, h) / 64);
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int p = b.getPixel(x, y);
                if ((p >>> 24) < 128) continue;
                count++;
                android.graphics.Color.colorToHSV(p, hsv);
                float wt = hsv[1] * hsv[2];
                if (wt < 0.08f) continue;
                int bin = Math.min(23, (int) (hsv[0] / 15));
                weight[bin] += wt;
                r[bin] += wt * ((p >> 16) & 255);
                g[bin] += wt * ((p >> 8) & 255);
                bl[bin] += wt * (p & 255);
                total += wt;
            }
        }
        if (count == 0 || total < count * 0.12f) return 0;
        int best = 0;
        for (int i = 1; i < 24; i++) if (weight[i] > weight[best]) best = i;
        int color = 0xFF000000 | (Math.round(r[best] / weight[best]) << 16) | (Math.round(g[best] / weight[best]) << 8)
                | Math.round(bl[best] / weight[best]);
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = Math.max(0.45f, Math.min(0.8f, hsv[2]));
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** Filtered down to fit w × h (never up). */
    private static Bitmap fit(Bitmap b, int w, int h) {
        float s = Math.min(1f, Math.min((float) w / b.getWidth(), (float) h / b.getHeight()));
        if (s >= 1f) return b;
        return Ui.downscale(b, Math.max(1, Math.round(b.getWidth() * s)), Math.max(1, Math.round(b.getHeight() * s)));
    }

    /** A symbol in white on a transparent bitmap (the emblem of Free My TV's own entry). */
    private static Bitmap symbolLogo(Context c, String symbol) {
        int size = 160;
        Symbols.warm(c, symbol, size * 0.8f);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Symbols.draw(c, new Canvas(b), symbol, size / 2f, size / 2f, size * 0.8f, 0xFFFFFFFF);
        return trim(b);
    }

    // MARK: - Cards

    private static final Map<String, Bitmap> BUNDLED = new HashMap<>();   // package → its bundled icon's card (or null)

    /**
     * The app's HD icon bundled in assets/logos/<package>.png (a square, full-bleed tvOS-style icon, from
     * tvOS-Launcher (Apache-2.0)) as a 16:9 card: the square fills the card's height in the middle and its edges carry
     * on to the sides (each row's edge colour), so it reads as one artwork; transparent parts sit on white. Null when
     * the app has none. Made once per process, only when `load` (the background load); else only if already made.
     */
    static Bitmap bundledTile(Context c, String pkg, boolean load) {
        synchronized (BUNDLED) {
            if (BUNDLED.containsKey(pkg) || !load) return BUNDLED.get(pkg);
        }
        Bitmap tile = null;
        try (InputStream in = c.getAssets().open("logos/" + pkg + ".png")) {
            Bitmap icon = BitmapFactory.decodeStream(in);
            if (icon != null) {
                Bitmap square = Bitmap.createBitmap(TILE_H, TILE_H, Bitmap.Config.ARGB_8888);
                Canvas sc = new Canvas(square);
                sc.drawColor(0xFFFFFFFF);
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                sc.drawBitmap(icon, null, new RectF(0, 0, TILE_H, TILE_H), p);
                icon.recycle();
                tile = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888);
                Canvas tc = new Canvas(tile);
                BitmapShader edge = new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                m.setTranslate((TILE_W - TILE_H) / 2f, 0);
                edge.setLocalMatrix(m);
                p.setShader(edge);
                tc.drawRect(0, 0, TILE_W, TILE_H, p);
                square.recycle();
                tile.setHasMipMap(true);
            }
        } catch (IOException ignored) {
            // no bundled icon for this app
        }
        synchronized (BUNDLED) {
            BUNDLED.put(pkg, tile);
        }
        return tile;
    }

    private static synchronized Bitmap appTile(Context c, String key, String pkg, int brand) {
        String cacheKey = key != null ? key : pkg;
        if (TILES.containsKey(cacheKey)) return TILES.get(cacheKey);
        Bitmap logo = key == null ? null : ownLogo(c, key);
        Bitmap tile = logo != null ? logoTile(logo, brand) : bannerTile(c, pkg);
        if (tile == null) tile = iconTile(c, pkg);
        tile.setHasMipMap(true);
        TILES.put(cacheKey, tile);
        return tile;
    }

    /** The app's official TV art (the Android TV banner), at the highest density the app has. */
    private static Bitmap bannerTile(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Drawable d = null;
        try {
            Intent launch = launchIntent(pm, pkg);
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                int res = ai.getBannerResource();
                if (res != 0) d = pm.getResourcesForApplication(ai.applicationInfo).getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
            }
            if (d == null) d = pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d == null) return null;
        float k = detail(d, TILE_W, TILE_H);
        int w = Math.round(TILE_W * k), h = Math.round(TILE_H * k);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        canvas.drawColor(0xFF15161C);   // banners with transparency sit on a dark background
        d.setBounds(0, 0, w, h);
        d.draw(canvas);
        return shrink(b, TILE_W, TILE_H);
    }

    /** No banner: the app's icon (in its proper shape, with some margin) on a dark card. */
    private static Bitmap iconTile(Context c, String pkg) {
        Drawable icon = null;
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.icon != 0) {
                try {
                    icon = pm.getResourcesForApplication(ai).getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
            if (icon == null) icon = pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (icon == null) return darkTile(TILE_W, TILE_H);
        boolean adaptive = icon instanceof AdaptiveIconDrawable;
        // Drawn at the icon's own resolution (the whole tile, so the squircle's edge is smooth too), then filtered down.
        float drawn = TILE_H * (adaptive ? 0.56f * 1.5f : 0.46f);
        float k = detail(icon, drawn, drawn);
        int tw = Math.round(TILE_W * k), th = Math.round(TILE_H * k);
        Bitmap b = darkTile(tw, th);
        Canvas canvas = new Canvas(b);
        int size = Math.round(th * 0.56f);
        float left = (tw - size) / 2f, top = (th - size) / 2f;
        if (adaptive) {
            // Adaptive icon: the layers in a squircle, like an Apple icon. Drawn apart, then filling an anti-aliased
            // rounded rectangle (clipPath leaves stair-stepped corners).
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            Bitmap layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas lc = new Canvas(layers);
            int pad = size / 4;
            for (Drawable layer : new Drawable[] {a.getBackground(), a.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(-pad, -pad, size + pad, size + pad);
                layer.draw(lc);
            }
            int x = Math.round(left), y = Math.round(top);
            BitmapShader shader = new BitmapShader(layers, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setTranslate(x, y);
            shader.setLocalMatrix(m);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(shader);
            canvas.drawRoundRect(x, y, x + size, y + size, size * 0.2237f, size * 0.2237f, p);
        } else {
            // Legacy icon (a free-form drawing with transparency): smaller, no frame.
            int s = Math.round(th * 0.46f);
            icon.setBounds((tw - s) / 2, (th - s) / 2, (tw + s) / 2, (th + s) / 2);
            icon.draw(canvas);
        }
        return shrink(b, TILE_W, TILE_H);
    }

    /**
     * How much bigger than w × h to draw d so it's near its own resolution (vectors: 2×), at most 2×: after an update
     * every tile is made again, some on the UI thread.
     */
    private static float detail(Drawable d, float w, float h) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        float k = iw > 0 && ih > 0 ? Math.max(iw / w, ih / h) : 2f;
        return Math.max(1f, Math.min(2f, k));
    }

    /** Filtered down to w × h (Ui.downscale); the big one is recycled. */
    private static Bitmap shrink(Bitmap big, int w, int h) {
        Bitmap out = Ui.downscale(big, w, h);
        if (out != big) big.recycle();
        return out;
    }

    /** Settings and the like: the icon in white on a dark card. */
    private static synchronized Bitmap symbolTile(Context c, String key, String symbol) {
        if (TILES.containsKey(key)) return TILES.get(key);
        Bitmap b = darkTile(TILE_W, TILE_H);
        Symbols.warm(c, symbol, TILE_H * 0.36f);
        Symbols.draw(c, new Canvas(b), symbol, TILE_W / 2f, TILE_H / 2f, TILE_H * 0.36f, 0xF2FFFFFF);
        b.setHasMipMap(true);
        TILES.put(key, b);
        return b;
    }

    /** The official logo (no background) on dark glass with a faint brand tint coming from below. */
    private static Bitmap logoTile(Bitmap logo, int brand) {
        Bitmap b = darkTile(TILE_W, TILE_H);
        Canvas canvas = new Canvas(b);
        Paint glow = new Paint();
        glow.setShader(new RadialGradient(TILE_W / 2f, TILE_H * 1.15f, TILE_W * 0.75f, Ui.alpha(brand, 0.38f), 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, TILE_W, TILE_H, glow);
        boolean wide = logo.getWidth() > logo.getHeight() * 1.35f;
        float maxW = TILE_W * (wide ? 0.62f : 0.4f), maxH = TILE_H * (wide ? 0.42f : 0.56f);
        float scale = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
        // Filtered down to the exact size first, then drawn pixel for pixel.
        Bitmap small = Ui.downscale(logo, Math.round(logo.getWidth() * scale), Math.round(logo.getHeight() * scale));
        canvas.drawBitmap(small, Math.round((TILE_W - small.getWidth()) / 2f), Math.round((TILE_H - small.getHeight()) / 2f),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        if (small != logo) small.recycle();
        return b;
    }

    private static Bitmap darkTile(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, 0, h, 0xFF2B2E3A, 0xFF17191F, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, w, h, bg);
        return b;
    }

    /** Official logo (tools/logos.py): trims the transparent margins and scales it down to at most 512 px. */
    /** The bundled logo, read once per process: reading it again on every load made a new image and filled caches. */
    private static Bitmap ownLogoCached(Context c, String key) {
        synchronized (LOGOS) {
            if (LOGOS.containsKey("own:" + key)) return LOGOS.get("own:" + key);
        }
        Bitmap b = ownLogo(c, key);
        synchronized (LOGOS) {
            LOGOS.put("own:" + key, b);
        }
        return b;
    }

    private static Bitmap ownLogo(Context c, String key) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getAssets().open("logos/" + key + ".png")) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / (opts.inSampleSize * 2) >= 640) opts.inSampleSize *= 2;
            Bitmap b;
            try (InputStream in = c.getAssets().open("logos/" + key + ".png")) {
                b = BitmapFactory.decodeStream(in, null, opts);
            }
            if (b == null) return null;
            b = trim(b);
            float scale = Math.min(1f, 512f / Math.max(b.getWidth(), b.getHeight()));
            if (scale < 1f) b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)), Math.max(1, Math.round(b.getHeight() * scale)), true);
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        int top = h, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > 10) {
                    if (y < top) top = y;
                    bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        if (bottom < 0) return b;
        return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
    }
}
