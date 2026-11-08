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
 * On Google TV Streamer, YouTube and the custom star button go to that home app too; Netflix goes to Google's service.
 * On the JVC G24 (Skyworth Android 11), the input shortcut's vendor receiver crashes without Google TV's content
 * provider, and Quick Settings targets its disabled notification panel. Open the native TV menus directly.
 * Double press and long press (TVOS-LAUNCHER-MERGE-SPEC.md S6 and Part 2 §4, the idea adapted from tvOS-Launcher
 * (Apache-2.0)): a button can open a second app when pressed twice within DOUBLE_MS, and a third when held LONG_MS.
 * Only a button with a second app waits DOUBLE_MS before opening its first one; only one with a held app acts on its
 * release instead of its press. Neither on Xiaomi, whose remote already sends one press as a burst.
 * Home, as the Siri Remote's TV button (TVOS-LAUNCHER-MERGE-SPEC.md Part 3 §B): a press is Home (the system's own,
 * through the service: the home screen's double press still works), held HOME_LONG_MS it opens Recent Apps. Only while
 * mahitTV is the home screen; otherwise, or if anything is off, Home passes on untouched.
 * Volume: a TV's system never gives the volume keys to the app in front (PhoneWindowManager's TV routing), so while
 * mahitTV is the home screen the filter takes them in every app, changes the volume itself on volumeWorker without the
 * system's panel (1 a press, 5 % a step held), and shows the iPhone-style HUD: the home screen's own (ACTION_VOLUME →
 * VolumeHud) over it, KeysOverlay's over anything else. Not when the TV's volume is fixed: the system's then.
 * Channels: the channel keys are the brightness the same way, everywhere (ACTION_BRIGHTNESS to the home screen, or
 * KeysOverlay's dimmer and HUD).
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
    static final boolean GOOGLE_STREAMER = is("Google") && Build.MODEL.equals("Google TV Streamer");
    static final boolean JVC_SKYWORTH = SkyworthRemote.supported(Build.MANUFACTURER, Build.BRAND,
            Build.MODEL, Build.VERSION.SDK_INT);
    /**
     * Xiaomi TVs whose remote has a Home button and hands every app button to Xiaomi's receiver, which stays on
     * (MiTV-AXSO2, Amlogic, Android TV 11 tested): only the PatchWall button (BUTTON_5) is ours, left to the TV until an
     * app is picked. By model name, since the other buttons' codes were not checked on this remote.
     */
    static final boolean XIAOMI_PATCHWALL_ONLY = is("Xiaomi") && Build.MODEL.equals("MiTV-AXSO2");
    /**
     * Xiaomi TVs (models MiTV-M…; MiTV-MSSP3, MediaTek, Android TV 9 tested). Not the Mi Box or Mi TV Stick, the slowest
     * devices we run on, whose remotes have a Home button; Formovie's Xiaomi projectors have their own remote.
     */
    static final boolean XIAOMI = is("Xiaomi") && !FORMOVIE && (Build.MODEL.startsWith("MiTV-M") || XIAOMI_PATCHWALL_ONLY);
    /** Brands whose Netflix button is STEM_1, a global key (MediaTek): only that button is ours. */
    static final boolean STEM_NETFLIX = VESTEL || FORMOVIE;
    /**
     * With a key filter on, TCL's Android 11 changes the volume twice for one press, and a held key gets stuck (#325).
     * There the service stays off: the buttons work on our home screen, whose window gets them (BUTTON_3 never does).
     */
    static final boolean FILTER_SAFE = !(TCL && Build.VERSION.SDK_INT == Build.VERSION_CODES.R);

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
    /** The channel keys while the home screen is in front: the brightness, by the extra "delta" percent. */
    static final String ACTION_BRIGHTNESS = "io.freemytv.BRIGHTNESS";
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
        return TCL || STEM_NETFLIX || KTC || SONY || XIAOMI || HISENSE || GOOGLE_STREAMER || JVC_SKYWORTH;
    }

    /** The key service is on wherever the filter is safe: whatever the buttons open, Home hold and volume run on it. */
    static boolean serviceWanted() {
        return supported() && FILTER_SAFE;
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
        if (isMic(e.getKeyCode())) return c instanceof android.accessibilityservice.AccessibilityService && onMic(c, e);
        if (isVolume(e.getKeyCode())) return c instanceof android.accessibilityservice.AccessibilityService && onVolume(c, e);
        if (isChannel(e.getKeyCode())) return c instanceof android.accessibilityservice.AccessibilityService && onChannel(c, e);
        int keyCode = TCL && e.getKeyCode() == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : e.getKeyCode();
        if (JVC_SKYWORTH) {
            if (SkyworthRemote.settings(keyCode)) keyCode = Prefs.KEY_JVC_SETTINGS;
            else if (SkyworthRemote.shortcut(keyCode, e.getScanCode())) keyCode = Prefs.KEY_JVC_SHORTCUT;
            else return false;
        }
        // Sony sends BUTTON_9 for five app buttons (BUTTON_4 for Netflix, BUTTON_8 for Google Play): only the scan code
        // tells them apart. The BRAVIA VU31's remote sends BUTTON_2 and BUTTON_15 too (Prefs.sonyKey).
        if (SONY && (keyCode == KeyEvent.KEYCODE_BUTTON_4 || keyCode == KeyEvent.KEYCODE_BUTTON_8
                || keyCode == KeyEvent.KEYCODE_BUTTON_9 || keyCode == KeyEvent.KEYCODE_BUTTON_2
                || keyCode == KeyEvent.KEYCODE_BUTTON_15)) {
            keyCode = Prefs.sonyKey(e.getScanCode());
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
        if (overlay != null) overlay.home(homeInFront);
    }

    /** The volume and the brightness over other apps (RemoteKeyService's windows), while the service runs. */
    private static KeysOverlay overlay;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    static void serviceStarted(android.accessibilityservice.AccessibilityService s) {
        if (overlay == null) overlay = new KeysOverlay(s);
    }

    static void serviceStopped() {
        if (overlay != null) overlay.gone();
        overlay = null;
    }

    static boolean isVolume(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    /** The remote's microphone (or search) button: Google's assistant's, unless mahitTV is the home screen. */
    static boolean isMic(int code) {
        return code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_ASSIST;
    }

    private static boolean micDown;

    /**
     * The microphone button, anywhere while mahitTV is the home screen: Welly instead of Google's assistant (Gemini). The
     * home screen comes forward on Welly's tab, listening (HomeActivity.EXTRA_WELLY); Google's never hears the key.
     */
    private static boolean onMic(Context c, KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) {
            boolean ours = micDown;
            micDown = false;
            return ours;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (e.getRepeatCount() > 0) return micDown;   // held: still the one press
        if (!homeIsOurs(c)) {
            micDown = false;
            return false;
        }
        micDown = true;
        AppLog.w("mic key " + e.getKeyCode() + ": Welly");
        try {
            c.startActivity(new Intent(c, HomeActivity.class).putExtra(HomeActivity.EXTRA_WELLY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ex) {
            AppLog.w("couldn't open Welly: " + ex.getMessage());
            micDown = false;
            return false;
        }
        return true;
    }

    static boolean isChannel(int code) {
        return code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN;
    }

    private static boolean channelDown;
    private static final long[] channelHeld = new long[2];

    /**
     * A channel key, anywhere while mahitTV is the home screen: the brightness instead of the channel (the owner never
     * uses channels), as a second pair of volume keys. 1 % a press, 5 % a step while held. Over the home screen it
     * changes it and shows its HUD (ACTION_BRIGHTNESS); over any other app, KeysOverlay does (its dimmer and HUD).
     */
    private static boolean onChannel(Context c, KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) {
            boolean ours = channelDown;
            channelDown = false;
            return ours;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (e.getRepeatCount() > 0 && !channelDown) return false;   // held since before: still the TV's
        if (!homeIsOurs(c) || !homeInFront && overlay == null) {
            channelDown = false;
            return false;
        }
        channelDown = true;
        int steps = heldSteps(e, channelHeld);
        if (steps == 0) return true;
        int delta = (e.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1) * steps;
        if (homeInFront) {
            c.sendBroadcast(new Intent(ACTION_BRIGHTNESS).setPackage(HomeMode.PKG).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("delta", delta));
        } else {
            overlay.brightness(delta);
        }
        return true;
    }

    /** The fastest a held volume or channel key steps (repeats that come sooner wait for the next). */
    static final long HELD_STEP_MS = 60;
    /** Held: percent a second, from HELD_SLOW, speeding up to HELD_FAST over HELD_RAMP_MS (after HELD_RAMP_AT). */
    static final float HELD_SLOW = 30, HELD_FAST = 140;
    static final long HELD_RAMP_AT = 300, HELD_RAMP_MS = 700;
    private static final long[] volumeHeld = new long[2];

    /**
     * A press is one step (1 %). Held, it speeds up as a TV's own volume does: each repeat steps by the time since the
     * last at a rate that ramps from HELD_SLOW to HELD_FAST % a second, so 0 to 100 takes about a second and a half held,
     * whatever pace the remote repeats at (0 for a repeat sooner than HELD_STEP_MS). `held` keeps [last step, press].
     */
    static int heldSteps(KeyEvent e, long[] held) {
        long now = e.getEventTime();
        if (e.getRepeatCount() == 0) {
            held[0] = held[1] = now;
            return 1;
        }
        long dt = now - held[0];
        if (dt < HELD_STEP_MS) return 0;
        held[0] = now;
        float ramp = Math.max(0, Math.min(1, (now - held[1] - HELD_RAMP_AT) / (float) HELD_RAMP_MS));
        float rate = HELD_SLOW + (HELD_FAST - HELD_SLOW) * ramp * ramp;
        return Math.max(1, Math.round(dt * rate / 1000f));
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
        boolean ours = homeIsOurs(c), fixed = volumeFixed(c);
        if (e.getRepeatCount() == 0) {
            // One line a press, to tell why a TV still shows its own volume bar (adb's keys never reach this filter).
            AppLog.w("volume key " + e.getKeyCode() + ": home in front " + homeInFront + ", ours " + ours + ", fixed " + fixed
                    + ", overlay " + (overlay != null));
        }
        if (!ours || fixed || !homeInFront && overlay == null) {
            volumeDown = false;
            return false;
        }
        volumeDown = true;
        if (e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_MUTE && e.getRepeatCount() > 0) return true;   // one toggle a press
        final int steps = heldSteps(e, volumeHeld);   // 1 a press, 5 a step while held
        if (steps == 0) return true;
        final int dir = volumeDirection(e.getKeyCode());
        final Context app = c.getApplicationContext();
        if (volumeWorker == null) volumeWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
        final boolean home = homeInFront;
        volumeWorker.execute(() -> {
            int[] now = adjustVolume(app, dir, steps);
            // Over another app: our HUD, as on the home screen (which shows its own from ACTION_VOLUME).
            if (now != null && !home) MAIN.post(() -> { if (overlay != null) overlay.volume(now[0], now[1] != 0, now[2]); });
        });
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
     * One volume step, or `steps` percent while held (or mute), without the system's panel (flag 0), then the level to the
     * home screen (ACTION_VOLUME, only to this app). Off the UI and filter threads: these are calls to the system. push
     * is 1 or −1 for a press past the top or the bottom.
     */
    static int[] adjustVolume(Context c, int dir, int steps) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int s = android.media.AudioManager.STREAM_MUSIC, max = Math.max(1, am.getStreamMaxVolume(s));
            int before = am.getStreamVolume(s);
            if (steps <= 1 || dir == android.media.AudioManager.ADJUST_TOGGLE_MUTE) {
                am.adjustStreamVolume(s, dir, 0);
            } else {
                // Held: `steps` percent at once (at least one of the TV's own steps), unmuting as a raise does.
                int by = Math.max(1, Math.round(steps * max / 100f)), up = dir == android.media.AudioManager.ADJUST_RAISE ? 1 : -1;
                if (up > 0 && am.isStreamMute(s)) am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_UNMUTE, 0);
                am.setStreamVolume(s, Math.max(0, Math.min(max, before + up * by)), 0);
            }
            int now = am.getStreamVolume(s);
            int push = dir == android.media.AudioManager.ADJUST_RAISE && before >= max && now >= max ? 1
                    : dir == android.media.AudioManager.ADJUST_LOWER && before <= 0 && now <= 0 ? -1 : 0;
            boolean muted = am.isStreamMute(s);
            c.sendBroadcast(new Intent(ACTION_VOLUME).setPackage(HomeMode.PKG).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("index", now).putExtra("max", max).putExtra("muted", muted).putExtra("push", push));
            return new int[] {muted ? 0 : Math.round(100f * now / max), muted ? 1 : 0, push};
        } catch (RuntimeException ex) {
            AppLog.w("volume: " + ex.getMessage());
            return null;
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
                : JVC_SKYWORTH && pkg.equals(Prefs.QUICK_SETTINGS)
                ? new Intent(c, QuickSettingsActivity.class)
                : JVC_SKYWORTH && pkg.equals(Prefs.TV_SHORTCUTS)
                ? new Intent(Intent.ACTION_MAIN).setClassName("com.smartdevice.dailyshortcut",
                        "com.smartdevice.dailyshortcut.ui.MainActivity")
                : TCL && pkg.equals(Prefs.QUICK_PANEL)
                ? new Intent(c, QuickPanelActivity.class)
                : (JVC_SKYWORTH || TCL) && pkg.equals("com.android.tv.settings")
                ? new Intent(android.provider.Settings.ACTION_SETTINGS).setPackage(pkg)
                : launchIntent(c.getPackageManager(), pkg);
        if (pkg.equals(Prefs.ALL_APPS)) intent.putExtra(HomeActivity.EXTRA_ALL_APPS, true);
        // A locked app (App Lock): the home screen asks for its passcode first, then opens it.
        if (intent != null && AppLock.lockedOnFile(c, pkg)) {
            intent = new Intent(c, HomeActivity.class).putExtra(HomeActivity.EXTRA_UNLOCK, pkg);
        }
        AppLog.w("remote key " + keyCode + " -> " + pkg + (intent == null ? " (not installed)" : ""));
        // Consumed even when it doesn't open: its key-up is ours too, and a key-down alone would reach the TV.
        if (intent != null) {
            try {
                c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (android.content.ActivityNotFoundException | SecurityException ex) {
                AppLog.w("remote target unavailable: " + pkg);
            }
        }
    }

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
