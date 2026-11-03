package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The people who use the TV (MAHITTV-PROFILES-SPEC.md §1): their accounts, PINs, who is in, and each one's own
 * settings. `profiles=id:Name:role:PIN,…` in config.properties; build.sh only ever puts sha256(salt + PIN) in the app.
 * Each profile keeps its Dock, theme, wallpaper, top buttons, Welly memory and watch log in its own preferences file
 * (`profile_<id>`); Guest keeps them in memory only, wiped when it locks. The lock comes back after 10 minutes away.
 */
final class ProfileManager {
    private ProfileManager() {}

    static final String MANAGER = "manager", STANDARD = "standard", GUEST = "guest";
    /** Away longer than this (screensaver, an app, the TV off), and the lock asks again. */
    static final long GRACE_MS = 10 * 60 * 1000L;
    /** Wrong PINs in a row before a wait, and the wait. */
    static final int TRIES = 5;
    static final long WAIT_MS = 30_000L;

    static final class Profile {
        final String id, name, role, pinHash, avatar;

        Profile(String id, String name, String role, String pinHash, String avatar) {
            this.id = id;
            this.name = name;
            this.role = role;
            this.pinHash = pinHash;
            this.avatar = avatar;
        }

        boolean manager() { return MANAGER.equals(role); }
        boolean guest() { return GUEST.equals(role); }
    }

    private static List<Profile> all;
    private static final Map<String, Bitmap> AVATARS = new HashMap<>();
    private static final Map<String, Integer> wrong = new HashMap<>();
    private static final Map<String, Long> waitUntil = new HashMap<>();
    private static MemoryPrefs guestPrefs = new MemoryPrefs();
    private static boolean unlocked;

    // MARK: - The accounts

    /** The profiles from Config.PROFILES (read once per process); without any, one manager without a PIN and Guest. */
    static synchronized List<Profile> all(Context c) {
        if (all != null) return all;
        List<Profile> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String entry : Config.PROFILES.split(",")) {
            String[] p = entry.split(":", -1);
            if (p.length < 3 || p[0].trim().isEmpty() || !ids.add(p[0].trim())) continue;
            String role = p[2].trim();
            out.add(new Profile(p[0].trim(), p[1].trim(), role.isEmpty() ? STANDARD : role, p.length > 3 ? p[3].trim() : "",
                    p.length > 4 ? p[4].trim() : ""));
        }
        if (out.isEmpty()) {
            out.add(new Profile("me", Ui.tr("Me"), MANAGER, "", ""));
            out.add(new Profile("guest", Ui.tr("Guest"), GUEST, "", ""));
        }
        return all = out;
    }

    static Profile byId(Context c, String id) {
        for (Profile p : all(c)) if (p.id.equals(id)) return p;
        return null;
    }

    static Profile manager(Context c) {
        for (Profile p : all(c)) if (p.manager()) return p;
        return all(c).get(0);
    }

    private static SharedPreferences settings(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** The profile using the TV now (the last one unlocked; the first one before anyone has). */
    static Profile current(Context c) {
        Profile p = byId(c, settings(c).getString("profileId", ""));
        return p != null ? p : all(c).get(0);
    }

    static int currentIndex(Context c) {
        return Math.max(0, all(c).indexOf(current(c)));
    }

    // MARK: - Locked or not

    /** Whether the lock must show now: nobody unlocked in this process, or away longer than GRACE_MS. */
    static boolean needsLock(Context c) {
        if (!unlocked) return true;
        long last = settings(c).getLong("lastActive", 0);
        return last == 0 || System.currentTimeMillis() - last > GRACE_MS;
    }

    /** Someone used the TV (a key, a launch): the grace period starts again from now. */
    static void touch(Context c) {
        settings(c).edit().putLong("lastActive", System.currentTimeMillis()).apply();
    }

    /** Lets `p` in (after its PIN, or Guest): it becomes the current profile. */
    static void unlock(Context c, Profile p) {
        Profile before = current(c);
        if (before.guest() && !before.id.equals(p.id)) guestPrefs = new MemoryPrefs();
        settings(c).edit().putString("profileId", p.id).putLong("lastActive", System.currentTimeMillis()).apply();
        unlocked = true;
        Prefs.profileChanged();
    }

    /** Back to "Who's Watching?". Guest's session is forgotten. */
    static void lock(Context c) {
        unlocked = false;
        if (current(c).guest()) guestPrefs = new MemoryPrefs();
    }

    // MARK: - PINs

    static boolean needsPin(Context c, Profile p) {
        return !p.guest() && !pinHash(c, p).isEmpty();
    }

    /** Milliseconds left before `p` may try again after too many wrong PINs (0: now). */
    static long waitLeft(Profile p) {
        Long until = waitUntil.get(p.id);
        return until == null ? 0 : Math.max(0, until - SystemClock.elapsedRealtime());
    }

    /** Checks four digits; too many wrong ones in a row and the profile waits WAIT_MS. */
    static synchronized boolean checkPin(Context c, Profile p, String digits) {
        if (!needsPin(c, p)) return true;
        if (waitLeft(p) > 0) return false;
        boolean ok = sha256(pinSalt(c, p) + digits).equals(pinHash(c, p));
        if (ok) {
            wrong.remove(p.id);
        } else {
            int n = (wrong.containsKey(p.id) ? wrong.get(p.id) : 0) + 1;
            wrong.put(p.id, n);
            if (n >= TRIES) {
                wrong.remove(p.id);
                waitUntil.put(p.id, SystemClock.elapsedRealtime() + WAIT_MS);
            }
        }
        return ok;
    }

    /** A new PIN for `p` (four digits), stored as a hash with a salt of its own. Empty: no PIN. */
    static void setPin(Context c, Profile p, String digits) {
        if (p.guest()) return;
        if (digits == null || digits.isEmpty()) {
            prefs(c, p).edit().putString("pinSalt", "").putString("pinHash", "").putBoolean("pinSet", true).apply();
            return;
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = hex(salt);
        prefs(c, p).edit().putString("pinSalt", s).putString("pinHash", sha256(s + digits)).putBoolean("pinSet", true).apply();
    }

    private static String pinSalt(Context c, Profile p) {
        SharedPreferences sp = prefs(c, p);
        return sp.getBoolean("pinSet", false) ? sp.getString("pinSalt", "") : Config.PIN_SALT;
    }

    private static String pinHash(Context c, Profile p) {
        SharedPreferences sp = prefs(c, p);
        return sp.getBoolean("pinSet", false) ? sp.getString("pinHash", "") : p.pinHash;
    }

    static String sha256(String text) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    // MARK: - Each profile's own settings

    /** `p`'s own preferences (its Dock, theme, wallpaper, Welly memory, watch log); Guest's are in memory. */
    static SharedPreferences prefs(Context c, Profile p) {
        if (p.guest()) return guestPrefs;
        return c.getApplicationContext().getSharedPreferences("profile_" + p.id, Context.MODE_PRIVATE);
    }

    static SharedPreferences prefs(Context c) {
        return prefs(c, current(c));
    }

    /**
     * Back to the default profiles: every profile's file, Welly's memory, the watch logs and the recommendation cache
     * go; the TV-wide settings (language, city, remote buttons, mode) stay. (Settings → Reset mahitTV Data.)
     */
    static synchronized void resetAll(Context c) {
        Context app = c.getApplicationContext();
        for (Profile p : all(app)) {
            if (p.guest()) continue;
            prefs(app, p).edit().clear().commit();
            app.deleteSharedPreferences("profile_" + p.id);
        }
        guestPrefs = new MemoryPrefs();
        File welly = new File(app.getFilesDir(), "welly");
        File[] files = welly.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        app.getSharedPreferences("recs", Context.MODE_PRIVATE).edit().clear().commit();
        settings(app).edit().remove("profileId").remove("lastActive").remove("profile").commit();
        wrong.clear();
        waitUntil.clear();
        unlocked = false;
        Prefs.profileChanged();
    }

    // MARK: - Avatars

    /** An avatar's corner, as a share of its size: a tile's continuous corner. */
    static final float CORNER = 0.225f;
    /** In a capsule (the pill): rounder, so it nests in the capsule's end. */
    static final float CORNER_NESTED = 0.36f;

    /**
     * `p`'s avatar as a rounded square `size` px wide, `corner` its share of the size (made once per profile and size, off the draw path): assets/profiles/<id>.png
     * (yours), else the one named in config.properties, else memoji.png for the manager, guest.png for Guest, and the
     * bundled defaults in turn for the others.
     */
    static Bitmap avatar(Context c, Profile p, int size) {
        return avatar(c, p, size, CORNER);
    }

    static synchronized Bitmap avatar(Context c, Profile p, int size, float corner) {
        if (p == null || size <= 0) return null;
        String key = p.id + "@" + size + "/" + corner;
        if (AVATARS.containsKey(key)) return AVATARS.get(key);
        Bitmap out = null;
        String path = avatarPath(c, p);
        if (path != null) {
            try (InputStream in = c.getAssets().open(path)) {
                Bitmap src = BitmapFactory.decodeStream(in);
                if (src != null) {
                    // Centre square, filtered down to the size, then cut to the shape (anti-aliased). Round art (a disc on
                    // transparent corners, as the default avatars) is read closer, so the disc fills the shape.
                    int side = Math.min(src.getWidth(), src.getHeight());
                    if (src.getPixel(0, 0) >>> 24 == 0) side = Math.round(side / 1.42f);
                    Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
                    Bitmap small = Ui.downscale(square, size, size);
                    out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    paint.setShader(new BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                    android.graphics.Path shape = new android.graphics.Path();
                    Corners.continuous(shape, size, size, size * corner);
                    new Canvas(out).drawPath(shape, paint);
                }
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("avatar " + path + ": " + e.getMessage());
            }
        }
        AVATARS.put(key, out);
        return out;
    }

    private static String avatarPath(Context c, Profile p) {
        List<String> own = list(c, "profiles"), bundled = list(c, "avatars");
        if (own.contains(p.id + ".png")) return "profiles/" + p.id + ".png";
        if (!p.avatar.isEmpty() && bundled.contains(p.avatar)) return "avatars/" + p.avatar;
        if (p.manager() && bundled.contains("memoji.png")) return "avatars/memoji.png";
        if (p.guest() && bundled.contains("guest.png")) return "avatars/guest.png";
        List<String> defaults = new ArrayList<>();
        for (String f : bundled) if (f.startsWith("default-")) defaults.add(f);
        if (defaults.isEmpty()) return null;
        java.util.Collections.sort(defaults);
        int n = Math.max(0, all(c).indexOf(p));
        return "avatars/" + defaults.get(n % defaults.size());
    }

    private static List<String> list(Context c, String folder) {
        try {
            String[] l = c.getAssets().list(folder);
            return l == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(l));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Guest's preferences: a map in memory, nothing on disk. */
    private static final class MemoryPrefs implements SharedPreferences {
        private final Map<String, Object> map = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(map); }
        @Override public String getString(String k, String d) { Object v = map.get(k); return v instanceof String ? (String) v : d; }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String k, Set<String> d) { Object v = map.get(k); return v instanceof Set ? (Set<String>) v : d; }
        @Override public int getInt(String k, int d) { Object v = map.get(k); return v instanceof Integer ? (Integer) v : d; }
        @Override public long getLong(String k, long d) { Object v = map.get(k); return v instanceof Long ? (Long) v : d; }
        @Override public float getFloat(String k, float d) { Object v = map.get(k); return v instanceof Float ? (Float) v : d; }
        @Override public boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : d; }
        @Override public boolean contains(String k) { return map.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        @Override public Editor edit() {
            final Map<String, Object> pending = new HashMap<>();
            final Set<String> removed = new HashSet<>();
            final boolean[] clear = {false};
            return new Editor() {
                @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, v == null ? null : new HashSet<>(v)); return this; }
                @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
                @Override public Editor remove(String k) { removed.add(k); return this; }
                @Override public Editor clear() { clear[0] = true; return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    synchronized (map) {
                        if (clear[0]) map.clear();
                        for (String k : removed) map.remove(k);
                        for (Map.Entry<String, Object> e : pending.entrySet()) {
                            if (e.getValue() == null) map.remove(e.getKey());
                            else map.put(e.getKey(), e.getValue());
                        }
                    }
                }
            };
        }
    }
}
