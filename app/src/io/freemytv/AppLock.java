package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * App Lock (DESIGN-PASSCODE.md): apps of a profile that ask for a passcode before they open from mahitTV (the Dock, All
 * Apps, Control Center, Welly, Recent Apps, the screensaver, the remote's app buttons). Each profile keeps its own
 * list and how they unlock: with the profile's passcode, or with one App Passcode for all of them, which the profile's
 * passcode resets when it's forgotten. AppLockView asks; HomeApps.open checks (needs) and lets one opening through after
 * the right code (pass). The remote's buttons run in `:keys`, so the current profile's list is also kept in a file there
 * (locked-apps.txt; no SharedPreferences across processes).
 */
final class AppLock {
    private AppLock() {}

    static final String WITH_PROFILE = "profile", WITH_APP = "app";
    private static final String FILE = "locked-apps.txt";
    /** How long a right code lets its app through (the opening that asked for it). */
    private static final long PASS_MS = 8_000;

    /** Who asks for the code: the home screen while it's in front (AppLockView), else it's brought forward to ask. */
    interface Gate {
        /** Asks for `it`'s code over the home screen, and opens it when it's right. False: can't ask now. */
        boolean ask(HomeApps.Item it);
    }

    static Gate gate;
    private static String passPkg;
    private static long passUntil;

    private static SharedPreferences prefs(Context c) {
        return ProfileManager.prefs(c);
    }

    /** The current profile's locked apps (none for Guest). */
    static Set<String> lockedApps(Context c) {
        if (ProfileManager.current(c).guest()) return Collections.emptySet();
        return new HashSet<>(prefs(c).getStringSet("lockedApps", Collections.emptySet()));
    }

    static boolean locked(Context c, String pkg) {
        return pkg != null && lockedApps(c).contains(pkg);
    }

    static void setLocked(Context c, String pkg, boolean on) {
        Set<String> apps = lockedApps(c);
        if (on ? !apps.add(pkg) : !apps.remove(pkg)) return;
        prefs(c).edit().putStringSet("lockedApps", apps).apply();
        writeFile(c, apps);
    }

    /** "profile" (the profile's passcode) or "app" (the App Passcode). */
    static String with(Context c) {
        String v = prefs(c).getString("lockWith", "");
        if (!v.isEmpty()) return v;
        return ProfileManager.needsPin(c, ProfileManager.current(c)) ? WITH_PROFILE : WITH_APP;
    }

    static void setWith(Context c, String v) {
        prefs(c).edit().putString("lockWith", v).apply();
    }

    static boolean usesProfile(Context c) {
        return WITH_PROFILE.equals(with(c));
    }

    static boolean hasAppCode(Context c) {
        return !prefs(c).getString("appCodeHash", "").isEmpty();
    }

    /** The one App Passcode for all the locked apps (a remote passcode). */
    static void setAppCode(Context c, String code) {
        String salt = ProfileManager.newSalt();
        prefs(c).edit().putString("appCodeSalt", salt).putString("appCodeHash", ProfileManager.sha256(salt + code))
                .putInt("appCodeLength", code.length()).apply();
    }

    private static String waitKey(Context c) {
        return "app-lock:" + ProfileManager.current(c).id;
    }

    /** A code that unlocks the locked apps: the profile's or the App Passcode, with the same wait after five wrong. */
    static boolean check(Context c, String code) {
        ProfileManager.Profile me = ProfileManager.current(c);
        if (usesProfile(c)) return ProfileManager.checkPin(c, me, code);
        if (!hasAppCode(c)) return true;
        SharedPreferences sp = prefs(c);
        boolean right = ProfileManager.sha256(sp.getString("appCodeSalt", "") + code).equals(sp.getString("appCodeHash", ""));
        return ProfileManager.checkHash(waitKey(c), right);
    }

    /** Milliseconds before another try (0: now). */
    static long waitLeft(Context c) {
        return usesProfile(c) ? ProfileManager.waitLeft(ProfileManager.current(c)) : ProfileManager.waitLeft(waitKey(c));
    }

    /** The code that unlocks them is typed with the arrows (else it's an older four-digit PIN). */
    static boolean remoteCode(Context c) {
        return !usesProfile(c) || ProfileManager.remotePin(c, ProfileManager.current(c));
    }

    static int codeLength(Context c) {
        if (usesProfile(c)) return ProfileManager.pinLength(c, ProfileManager.current(c));
        return prefs(c).getInt("appCodeLength", PasscodePad.REMOTE_LENGTH);
    }

    /** There's a code to ask for at all: the profile's, or a set App Passcode. */
    static boolean hasCode(Context c) {
        return usesProfile(c) ? ProfileManager.needsPin(c, ProfileManager.current(c)) : hasAppCode(c);
    }

    /**
     * Who can reset a forgotten code: for the App Passcode, this profile (with its passcode); for the profile's own,
     * the manager. Null: nobody can (it isn't offered).
     */
    static ProfileManager.Profile recoverer(Context c) {
        ProfileManager.Profile me = ProfileManager.current(c), manager = ProfileManager.manager(c);
        if (!usesProfile(c) && ProfileManager.needsPin(c, me)) return me;
        if (manager != null && !manager.id.equals(me.id) && ProfileManager.needsPin(c, manager)) return manager;
        return null;
    }

    // MARK: - Opening

    /** `it` asks for its code before it opens (and no right code just let it through). */
    static boolean needs(Context c, HomeApps.Item it) {
        if (it == null || it.kind != HomeApps.APP || it.pkg == null || !locked(c, it.pkg) || !hasCode(c)) return false;
        if (it.pkg.equals(passPkg) && SystemClock.uptimeMillis() < passUntil) {
            passPkg = null;   // one opening
            return false;
        }
        return true;
    }

    /** The right code: `pkg`'s next opening goes through. */
    static void pass(String pkg) {
        passPkg = pkg;
        passUntil = SystemClock.uptimeMillis() + PASS_MS;
    }

    /** Asks for the code (the home screen in front asks itself; elsewhere it comes forward to ask). */
    static boolean ask(Context c, HomeApps.Item it) {
        Gate g = gate;
        if (g != null && g.ask(it)) return true;
        try {
            c.startActivity(new Intent(c, HomeActivity.class).putExtra(HomeActivity.EXTRA_UNLOCK, it.pkg)
                    .putExtra(HomeActivity.EXTRA_UNLOCK_URI, it.uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            AppLog.w("app lock: couldn't ask: " + e.getMessage());
            return false;
        }
    }

    // MARK: - The remote's buttons (:keys)

    /** Another profile is in: its list for the remote's buttons. */
    static void profileChanged(Context c) {
        writeFile(c, lockedApps(c));
    }

    private static void writeFile(Context c, Set<String> apps) {
        if (!hasCode(c)) apps = Collections.emptySet();   // nothing to ask for: the buttons open them
        File f = new File(c.getFilesDir(), FILE), tmp = new File(c.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(String.join("\n", apps).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.w("app lock: " + e.getMessage());
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    /** For `:keys`: `pkg` is locked in the current profile (the file the home screen keeps). */
    static boolean lockedOnFile(Context c, String pkg) {
        File f = new File(c.getFilesDir(), FILE);
        if (pkg == null || !f.isFile() || f.length() == 0 || f.length() > 64_000) return false;
        try {
            for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
                if (line.trim().equals(pkg)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
