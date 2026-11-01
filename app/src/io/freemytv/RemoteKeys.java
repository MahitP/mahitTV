package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 * On Vestel TVs and Formovie (Xiaomi) projectors only the Netflix button is ours: the system opens Netflix itself (a
 * global key, after the filter).
 * On KTC TVs (NextStar) only the Tabii button is ours, a global key too: the TV's hot-key receiver opens Tabii with it.
 * On Sony TVs, the app buttons (and the Google Play button of older remotes) are global keys for Sony's own receiver;
 * the filter sees them first, the same way.
 * On Xiaomi TVs whose remote has no Home button, its Mi button is BUTTON_4, a global key that Xiaomi's GlobalKeyReceiver
 * (partnercustomizer) turns into PatchWall. The filter sees it first, and by default it opens the home screen.
 * On Xiaomi TVs the YouTube button is ours too: BUTTON_3, a global key for the stock home screen (launcherx), which
 * Free My TV turns off. So is the PatchWall button (BUTTON_5, which that receiver would open PatchWall with); Netflix
 * goes to the receiver, which stays on. Their remote sends one press as a burst of presses: the app opens once.
 * On Xiaomi TVs sold in Russia, the Okko, VK, Rutube, KION, Wink and IVI buttons are global keys too, for Xiaomi's
 * hot-key receiver (partnercustomizer's GlobalKeyReceiver).
 * On Hisense TVs, the Prime Video, YouTube, Disney+, All Apps, Settings (gear) and profile buttons go to Google TV's
 * home app, which Free My TV mode turns off: without the filter they do nothing. Netflix stays with the system.
 * Double press and long press (TVOS-LAUNCHER-MERGE-SPEC.md S6 and Part 2 §4, the idea adapted from tvOS-Launcher
 * (Apache-2.0)): a button can open a second app when pressed twice within DOUBLE_MS, and a third when held LONG_MS.
 * Only a button with a second app waits DOUBLE_MS before opening its first one; only one with a held app acts on its
 * release instead of its press. Neither on Xiaomi, whose remote already sends one press as a burst.
 * Home, as the Siri Remote's TV button (TVOS-LAUNCHER-MERGE-SPEC.md Part 3 §B): a press is Home (the system's own,
 * through the service: the home screen's double press still works), held HOME_LONG_MS it opens Recent Apps. Only while
 * mahitTV is the home screen; otherwise, or if anything is off, Home passes on untouched.
 * Volume: a TV's system never gives the volume keys to the app in front (PhoneWindowManager's TV routing), so while the
 * home screen is in front (RemoteKeyService's window events) the filter takes them, changes the volume itself on
 * volumeWorker without the system's panel, and the home screen shows its own (ACTION_VOLUME → VolumeHud). Not when the
 * TV's volume is fixed; anywhere else the keys stay the system's.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Some TCL remotes (BT_RC833) send TCL's own YouTube key instead of BUTTON_3: the same button. */
    private static final int KEY_YOUTUBE_TCL = 4065;

    /**
     * The key codes are the brand's own: on other brands they can be other buttons, so nothing here runs there.
     * Vestel builds TVs sold under other brands (F&U...), so BRAND varies: check the maker too.
     */
    static final boolean TCL = is("TCL"), VESTEL = is("Vestel"), KTC = is("KTC"), SONY = is("Sony"), FORMOVIE = is("Formovie"),
            HISENSE = is("Hisense");
    /**
     * Xiaomi TVs (models MiTV-M…; MiTV-MSSP3, MediaTek, Android TV 9 tested). Not the Mi Box or Mi TV Stick, the slowest
     * devices we run on, whose remotes have a Home button; Formovie's Xiaomi projectors have their own remote.
     */
    static final boolean XIAOMI = is("Xiaomi") && !FORMOVIE && Build.MODEL.startsWith("MiTV-M");
    /** Brands whose Netflix button is STEM_1, a global key (MediaTek): only that button is ours. */
    static final boolean STEM_NETFLIX = VESTEL || FORMOVIE;

    /** Xiaomi's remote sends one press as a burst of separate presses: a press this soon after the last one is the same. */
    private static final long BURST_MS = 1000;
    private static int lastKey;
    private static long lastDown;

    /** A second press within this long opens the button's second app. */
    static final long DOUBLE_MS = 320;
    /** Holding the button this long opens its held app. */
    static final long LONG_MS = 550;
    /** Holding Home this long opens Recent Apps; a shorter press is Home. */
    static final long HOME_LONG_MS = 450;
    private static boolean homeDown, homeFired;
    private static android.accessibilityservice.AccessibilityService homeService;
    private static final Runnable openRecents = () -> {
        android.accessibilityservice.AccessibilityService s = homeService;
        homeFired = true;
        homeService = null;
        if (s == null) return;
        try {
            s.startActivity(new Intent(s, HomeActivity.class).putExtra(HomeActivity.EXTRA_RECENTS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (RuntimeException e) {
            AppLog.w("Recent Apps: " + e.getMessage());
            s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
        }
    };
    // Whether mahitTV is the home screen, checked at most every HOME_CHECK_MS (a package query on a key press).
    private static final long HOME_CHECK_MS = 10_000;
    private static long homeCheckedAt;
    private static boolean homeIsOurs;

    /** The level after a volume key (adjustVolume), for the home screen's HUD: extras index, max, muted, push. */
    static final String ACTION_VOLUME = "io.freemytv.VOLUME";
    private static final String HOME_CLASS = HomeActivity.class.getName();
    // The window in front is the home screen's (RemoteKeyService), the volume key whose press we took, the TV's volume
    // is fixed (0: not read yet, 1: fixed, -1: not), and the thread that changes it (system calls, off the filter).
    private static volatile boolean homeInFront;
    private static boolean volumeDown;
    private static int volumeFixed;
    private static java.util.concurrent.ExecutorService volumeWorker;

    /** Whether this remote's buttons can have a second and a held app (Settings shows the rows only then). */
    static boolean doublePress() {
        return supported() && !XIAOMI;
    }

    // The button being held, when it has a held app: the app opens after LONG_MS unless it's released first.
    private static int heldKey;
    private static boolean heldFired;
    private static String heldPkg;
    private static Context heldContext;
    private static final Runnable openHeld = () -> {
        Context c = heldContext;
        String pkg = heldPkg;
        heldFired = true;
        heldContext = null;
        if (c != null && pkg != null) open(c, pkg, 0);
    };

    // The press waiting for a possible second one: its key, when, and what it opens if no second press comes.
    private static Handler handler;
    private static int pendingKey;
    private static long pendingAt;
    private static String pendingPkg;
    private static Context pendingContext;
    private static final Runnable openPending = () -> {
        Context c = pendingContext;
        String pkg = pendingPkg;
        pendingKey = 0;
        pendingContext = null;
        if (c != null && pkg != null) open(c, pkg, 0);
    };

    static boolean supported() {
        return TCL || STEM_NETFLIX || KTC || SONY || XIAOMI || HISENSE;
    }

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (!supported()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        if (e.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            return c instanceof android.accessibilityservice.AccessibilityService
                    && onHome((android.accessibilityservice.AccessibilityService) c, e);
        }
        if (isVolume(e.getKeyCode())) return c instanceof android.accessibilityservice.AccessibilityService && onVolume(c, e);
        int keyCode = e.getKeyCode() == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : e.getKeyCode();
        // Sony sends BUTTON_9 for five app buttons (BUTTON_4 for Netflix, BUTTON_8 for Google Play): only the scan code
        // tells them apart.
        if (SONY && (keyCode == KeyEvent.KEYCODE_BUTTON_4 || keyCode == KeyEvent.KEYCODE_BUTTON_8
                || keyCode == KeyEvent.KEYCODE_BUTTON_9)) {
            keyCode = Prefs.SONY_KEY + e.getScanCode();
        }
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == keyCode;
        if (!ours) return false;
        String pkg = Prefs.keyApp(keyCode);
        if (pkg.isEmpty()) return false;   // "Nothing": the key goes on as if we weren't here
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            // Only on Xiaomi, where the bursts are: everywhere else a second press opens the app again, as before.
            boolean burst = XIAOMI && keyCode == lastKey && e.getEventTime() - lastDown < BURST_MS;
            lastKey = keyCode;
            lastDown = e.getEventTime();
            if (burst) return true;
            String held = doublePress() ? Prefs.keyLongApp(keyCode) : "";
            if (held.isEmpty()) {
                press(c, keyCode, pkg, e.getEventTime(), e.getKeyCode());
            } else {   // a held app: wait for the release (a press) or LONG_MS (held)
                handler.removeCallbacks(openHeld);
                heldKey = keyCode;
                heldFired = false;
                heldPkg = held;
                heldContext = c;
                handler.postDelayed(openHeld, LONG_MS);
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP && keyCode == heldKey) {
            heldKey = 0;
            handler.removeCallbacks(openHeld);
            heldContext = null;
            if (!heldFired) press(c, keyCode, pkg, e.getEventTime(), e.getKeyCode());
        }
        return true;
    }

    /**
     * Home: consumed only while mahitTV is the home screen. Its press goes on as the system's Home (GLOBAL_ACTION_HOME,
     * so the home screen gets its Home intent and its double press as before); held HOME_LONG_MS, Recent Apps.
     */
    private static boolean onHome(android.accessibilityservice.AccessibilityService s, KeyEvent e) {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) return homeDown;   // the held key repeats: still ours
            if (!homeIsOurs(s)) return false;              // the TV's own home screen (Original, the watchdog): Home passes
            homeDown = true;
            homeFired = false;
            homeService = s;
            handler.removeCallbacks(openRecents);
            handler.postDelayed(openRecents, HOME_LONG_MS);
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP && homeDown) {
            homeDown = false;
            handler.removeCallbacks(openRecents);
            homeService = null;
            if (!homeFired && !s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) {
                try {   // the system refused its own Home: the home screen directly
                    s.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ex) {
                    AppLog.w("Home: " + ex.getMessage());
                }
            }
            return true;
        }
        return false;
    }

    /** A window came to the front (RemoteKeyService): whether it's the home screen's. */
    static void windowChanged(CharSequence pkg, CharSequence cls) {
        homeInFront = pkg != null && cls != null && HomeMode.PKG.contentEquals(pkg) && HOME_CLASS.contentEquals(cls);
    }

    static boolean isVolume(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    /** The AudioManager step a volume key makes. */
    static int volumeDirection(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP ? android.media.AudioManager.ADJUST_RAISE
                : code == KeyEvent.KEYCODE_VOLUME_DOWN ? android.media.AudioManager.ADJUST_LOWER
                : android.media.AudioManager.ADJUST_TOGGLE_MUTE;
    }

    /** A volume key while the home screen is in front: taken, and the volume changed on volumeWorker. */
    private static boolean onVolume(Context c, KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) {
            boolean ours = volumeDown;
            volumeDown = false;
            return ours;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (e.getRepeatCount() > 0 && !volumeDown) return false;   // held since before: still the system's
        if (!homeInFront || !homeIsOurs(c) || volumeFixed(c)) {
            volumeDown = false;
            return false;
        }
        volumeDown = true;
        if (e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_MUTE && e.getRepeatCount() > 0) return true;   // one toggle a press
        final int dir = volumeDirection(e.getKeyCode());
        final Context app = c.getApplicationContext();
        if (volumeWorker == null) volumeWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
        volumeWorker.execute(() -> adjustVolume(app, dir));
        return true;
    }

    private static boolean volumeFixed(Context c) {
        if (volumeFixed == 0) {
            try {
                android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                volumeFixed = am != null && !am.isVolumeFixed() ? -1 : 1;
            } catch (RuntimeException ex) {
                volumeFixed = 1;
            }
        }
        return volumeFixed > 0;
    }

    /**
     * One volume step (or mute) without the system's panel (flag 0), then the level to the home screen (ACTION_VOLUME,
     * only to this app). Off the UI and filter threads: these are calls to the system. push is 1 or −1 for a press past
     * the top or the bottom.
     */
    static void adjustVolume(Context c, int dir) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int s = android.media.AudioManager.STREAM_MUSIC, max = Math.max(1, am.getStreamMaxVolume(s));
            int before = am.getStreamVolume(s);
            am.adjustStreamVolume(s, dir, 0);
            int now = am.getStreamVolume(s);
            int push = dir == android.media.AudioManager.ADJUST_RAISE && before >= max && now >= max ? 1
                    : dir == android.media.AudioManager.ADJUST_LOWER && before <= 0 && now <= 0 ? -1 : 0;
            c.sendBroadcast(new Intent(ACTION_VOLUME).setPackage(HomeMode.PKG).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("index", now).putExtra("max", max).putExtra("muted", am.isStreamMute(s)).putExtra("push", push));
        } catch (RuntimeException ex) {
            AppLog.w("volume: " + ex.getMessage());
        }
    }

    private static boolean homeIsOurs(Context c) {
        long now = android.os.SystemClock.uptimeMillis();
        if (homeCheckedAt != 0 && now - homeCheckedAt < HOME_CHECK_MS) return homeIsOurs;
        homeCheckedAt = now;
        try {
            android.content.pm.ResolveInfo r = c.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            homeIsOurs = r != null && r.activityInfo != null && HomeMode.PKG.equals(r.activityInfo.packageName);
        } catch (RuntimeException ex) {
            homeIsOurs = false;
        }
        return homeIsOurs;
    }

    /** One press of a button: its app at once, or (with a second app) after DOUBLE_MS unless a second press comes. */
    private static void press(Context c, int keyCode, String pkg, long at, int rawKey) {
        String twice = doublePress() ? Prefs.keyDoubleApp(keyCode) : "";
        if (pendingKey != 0 && pendingKey != keyCode) {   // another button: the first one's app opens now
            handler.removeCallbacks(openPending);
            openPending.run();
        }
        if (twice.isEmpty()) {
            open(c, pkg, rawKey);
        } else if (pendingKey == keyCode && at - pendingAt < DOUBLE_MS) {
            handler.removeCallbacks(openPending);
            pendingKey = 0;
            pendingContext = null;
            open(c, twice, rawKey);
        } else {
            pendingKey = keyCode;
            pendingAt = at;
            pendingPkg = pkg;
            pendingContext = c;
            handler.postDelayed(openPending, DOUBLE_MS);
        }
    }

    /** Opens `pkg` (our own package: the home screen in use; ALL_APPS: its All Apps). */
    private static void open(Context c, String pkg, int keyCode) {
        // Our own package means the home screen in use (Xiaomi's Mi button): ours, or the TV's own after the
        // watchdog handed it back. Not the settings app our launcher icon opens.
        Intent intent = pkg.equals(HomeMode.PKG) || pkg.equals(Prefs.ALL_APPS)
                ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                : launchIntent(c.getPackageManager(), pkg);
        if (pkg.equals(Prefs.ALL_APPS)) intent.putExtra(HomeActivity.EXTRA_ALL_APPS, true);
        AppLog.w("remote key " + keyCode + " -> " + pkg + (intent == null ? " (not installed)" : ""));
        if (intent != null) c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
