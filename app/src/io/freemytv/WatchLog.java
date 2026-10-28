package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What each profile watched (MAHITTV-PROFILES-SPEC.md §4): every title or app opened from mahitTV, newest first, with
 * the position the app reported (NowPlaying, when its access is on). Private to the profile, in its own preferences;
 * nothing for Guest. Welly's "Continue watching" reads it; the TV tab never shows it.
 */
final class WatchLog {
    private WatchLog() {}

    static final int MAX = 30;
    /** A position report within this long of a launch from the same app belongs to that launch. */
    private static final long SAME_SESSION_MS = 6 * 60 * 60 * 1000L;

    static final class Entry {
        String title, kind, app, pkg, uri;
        long tmdbId, at, position, duration;

        JSONObject json() throws Exception {
            return new JSONObject().put("title", title).put("kind", kind).put("app", app).put("pkg", pkg).put("uri", uri)
                    .put("tmdb", tmdbId).put("at", at).put("pos", position).put("dur", duration);
        }

        static Entry from(JSONObject o) {
            Entry e = new Entry();
            e.title = o.optString("title");
            e.kind = o.optString("kind");
            e.app = o.optString("app");
            e.pkg = o.optString("pkg");
            e.uri = o.optString("uri");
            e.tmdbId = o.optLong("tmdb");
            e.at = o.optLong("at");
            e.position = o.optLong("pos");
            e.duration = o.optLong("dur");
            return e;
        }

        /** Watched fraction 0–1, or -1 when the app never said. */
        float progress() {
            return duration > 0 ? Math.max(0, Math.min(1, position / (float) duration)) : -1;
        }
    }

    /** The current profile's log, newest first (empty for Guest). */
    static synchronized List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        if (ProfileManager.current(c).guest()) return out;
        try {
            JSONArray a = new JSONArray(prefs(c).getString("watchLog", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Entry.from(a.getJSONObject(i)));
        } catch (Exception e) {
            AppLog.w("watch log: " + e.getMessage());
        }
        return out;
    }

    /**
     * Something was opened from mahitTV (HomeApps.open): a title (with its TMDB id when it's a pick) or just an app.
     * The same title again moves to the top instead of repeating.
     */
    static synchronized void launched(Context c, String title, long tmdbId, String kind, String app, String pkg, String uri) {
        if (ProfileManager.current(c).guest() || pkg == null || pkg.isEmpty()) return;
        List<Entry> all = list(c);
        Entry e = null;
        for (int i = 0; i < all.size(); i++) {
            Entry x = all.get(i);
            boolean same = tmdbId > 0 ? x.tmdbId == tmdbId : title != null && !title.isEmpty() && title.equals(x.title);
            if (same) {
                e = all.remove(i);
                break;
            }
        }
        if (e == null) e = new Entry();
        e.title = title == null ? "" : title;
        e.tmdbId = tmdbId > 0 ? tmdbId : e.tmdbId;
        e.kind = kind == null ? "" : kind;
        e.app = app == null ? "" : app;
        e.pkg = pkg;
        e.uri = uri == null ? "" : uri;
        e.at = System.currentTimeMillis();
        all.add(0, e);
        save(c, all);
    }

    /**
     * What's playing, from the app's media session (NowPlaying): its title and position go into the latest entry from
     * that app, or a new entry when the title is new.
     */
    static synchronized void playing(Context c, String pkg, String title, long position, long duration) {
        if (ProfileManager.current(c).guest() || pkg == null || pkg.isEmpty()) return;
        List<Entry> all = list(c);
        long now = System.currentTimeMillis();
        Entry e = null;
        for (Entry x : all) {
            if (!pkg.equals(x.pkg) || now - x.at > SAME_SESSION_MS) continue;
            if (title == null || title.isEmpty() || x.title.isEmpty() || title.equals(x.title)) e = x;
            break;
        }
        if (e == null) {
            if (title == null || title.isEmpty()) return;
            e = new Entry();
            e.pkg = pkg;
            e.app = label(c, pkg);
            e.kind = "";
            e.uri = "";
            all.add(0, e);
        }
        if (title != null && !title.isEmpty()) e.title = title;
        if (position >= 0) e.position = position;
        if (duration > 0) e.duration = duration;
        e.at = now;
        save(c, all);
    }

    private static String label(Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    static synchronized void clear(Context c) {
        prefs(c).edit().remove("watchLog").apply();
    }

    private static SharedPreferences prefs(Context c) {
        return ProfileManager.prefs(c);
    }

    private static void save(Context c, List<Entry> all) {
        JSONArray a = new JSONArray();
        try {
            for (int i = 0; i < all.size() && i < MAX; i++) a.put(all.get(i).json());
        } catch (Exception e) {
            AppLog.w("watch log: " + e.getMessage());
            return;
        }
        prefs(c).edit().putString("watchLog", a.toString()).apply();
    }

    /**
     * Preview builds: Mahit and Dhruvin get different sample histories and Docks (their own files, once), so switching
     * profile visibly changes the Dock and Welly's "What am I watching?". Uses the preview's sample pick titles.
     */
    static void seedPreview(Context c) {
        if (!Config.PREVIEW) return;
        long now = System.currentTimeMillis(), h = 60 * 60 * 1000L;
        seed(c, "mahit", now, h, new Object[][] {
            {"Orbital", 9003L, "tv", "Disney+", "com.disney.disneyplus", 41 * 60_000L, 52 * 60_000L},
            {"Neon Meridian", 9000L, "movie", "Apple TV", "com.apple.atve.androidtv.appletv", 63 * 60_000L, 124 * 60_000L},
            {"The Long Dunes", 9001L, "tv", "Netflix", "com.netflix.ninja", 12 * 60_000L, 48 * 60_000L},
        }, "com.netflix.ninja,com.google.android.youtube.tv,com.apple.atve.androidtv.appletv,com.disney.disneyplus,"
                + "com.amazon.amazonvideo.livingroom,com.apple.android.music");
        seed(c, "dhruvin", now, h, new Object[][] {
            {"Quiet Peak", 9002L, "movie", "Prime Video", "com.amazon.amazonvideo.livingroom", 28 * 60_000L, 112 * 60_000L},
            {"Tidebreak", 9004L, "movie", "Max", "com.wbd.stream", 95 * 60_000L, 107 * 60_000L},
        }, "com.google.android.youtube.tv,com.amazon.amazonvideo.livingroom,com.wbd.stream,com.netflix.ninja,"
                + "com.crunchyroll.crunchyroid,com.spotify.tv.android");
    }

    private static void seed(Context c, String id, long now, long h, Object[][] rows, String dock) {
        ProfileManager.Profile p = ProfileManager.byId(c, id);
        if (p == null) return;
        SharedPreferences sp = ProfileManager.prefs(c, p);
        if (sp.getBoolean("previewSeeded", false)) return;
        JSONArray a = new JSONArray();
        try {
            for (int i = 0; i < rows.length; i++) {
                Object[] r = rows[i];
                Entry e = new Entry();
                e.title = (String) r[0];
                e.tmdbId = (Long) r[1];
                e.kind = (String) r[2];
                e.app = (String) r[3];
                e.pkg = (String) r[4];
                e.uri = "";
                e.position = (Long) r[5];
                e.duration = (Long) r[6];
                e.at = now - (i * 20 + 2) * h;
                a.put(e.json());
            }
        } catch (Exception e) {
            return;
        }
        sp.edit().putString("watchLog", a.toString()).putString("shelf", dock).putBoolean("migrated", true)
                .putBoolean("previewSeeded", true).apply();
    }
}
