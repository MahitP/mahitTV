package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Sample picks for preview builds (`preview=true` in config.properties): five generated photos bundled from
 * preview/art (tools/make_preview_art.py), so the Top Shelf can be judged before a TMDB key is set. Release builds
 * carry neither the flag nor the photos.
 */
final class Preview {
    private Preview() {}

    private static final String[][] SAMPLES = {
        // file, kind, title, year, genre and length, app key, package, rating, overview, certification, badges
        {"neon-meridian", "movie", "Neon Meridian", "2026", "Sci-Fi · 2h 4m", "appletv", "com.apple.atve.androidtv.appletv", "8.1",
            "A courier crosses a drowned city to deliver one last message before the lights go out for good.",
            "PG-13", "4K,Dolby Vision,Dolby Atmos,CC"},
        {"long-dunes", "tv", "The Long Dunes", "2025", "Drama · 2 seasons", "netflix", "com.netflix.ninja", "7.6",
            "Two sisters inherit a caravan route across the open desert, and every oasis on it keeps a secret.",
            "TV-MA", "4K,HDR10,CC"},
        {"quiet-peak", "movie", "Quiet Peak", "2026", "Adventure · 1h 52m", "prime", "com.amazon.amazonvideo.livingroom", "7.9",
            "A mountain guide's last season turns into a rescue nobody on the ridge thought was possible.",
            "PG", "4K,Dolby Vision,CC"},
        {"orbital", "tv", "Orbital", "2026", "Sci-Fi · 1 season", "disney", "com.disney.disneyplus", "8.4",
            "The crew of a failing station has ninety days to choose who goes home on the only seat left.",
            "TV-14", "4K,Dolby Vision,Dolby Atmos,CC"},
        {"tidebreak", "movie", "Tidebreak", "2025", "Thriller · 1h 47m", "max", "com.wbd.stream", "7.2",
            "When the storm takes the harbor's lighthouse dark, its keeper is the only witness to what came ashore.",
            "R", "4K,Dolby Atmos,CC"},
    };

    private static List<Recs.Pick> all;

    /** The samples of one kind ("movie", "tv"), or the first three of all (null), as Recs.now() hands them out. */
    static synchronized List<Recs.Pick> picks(String kind) {
        if (all == null) {
            all = new ArrayList<>();
            for (int i = 0; i < SAMPLES.length; i++) {
                String[] s = SAMPLES[i];
                Recs.Pick p = new Recs.Pick();
                p.id = 9000 + i;
                p.kind = s[1];
                p.title = s[2];
                p.year = s[3];
                p.backdrop = "preview/" + s[0] + ".jpg";
                p.app = s[5];
                p.pkg = s[6];
                p.rating = Double.parseDouble(s[7]);
                p.overview = s[8];
                p.cert = s[9];
                p.badges = s[10];
                p.logo = "preview/" + s[0] + "-logo.png";   // used when the build carries it (preview/art)
                p.meta = s[4] + " · " + Recs.serviceName(p);
                p.detailed = true;
                all.add(p);
            }
        }
        List<Recs.Pick> out = new ArrayList<>();
        for (Recs.Pick p : all) if (kind == null || kind.equals(p.kind)) out.add(p);
        return kind == null ? new ArrayList<>(out.subList(0, 3)) : out;
    }

    static boolean isAsset(Recs.Pick p) {
        return isAsset(p.backdrop);
    }

    static boolean isAsset(String path) {
        return Config.PREVIEW && path != null && path.startsWith("preview/");
    }

    /** The sample's photo, from the APK's assets. */
    static Bitmap image(Context c, Recs.Pick p) {
        return image(c, p.backdrop);
    }

    /** A sample's photo or logo, from the APK's assets, or null. */
    static Bitmap image(Context c, String path) {
        try (InputStream in = c.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            AppLog.w("preview art: " + e.getMessage());
            return null;
        }
    }
}
