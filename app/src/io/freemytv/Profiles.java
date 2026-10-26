package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The people who use the TV (DESIGN-TVOS.md §6): a name and an avatar each, as in tvOS's top-right pill and Control
 * Center. `profiles=Name:avatar.png,…` in config.properties; the avatars are files in assets/avatars (drop your own
 * Memoji there as memoji.png). Without the setting, one profile with memoji.png when it is there, else default-1.png.
 * The current one is remembered on the TV. For now a profile is its name and avatar.
 */
final class Profiles {
    private Profiles() {}

    static final class Profile {
        final String name, avatar;

        Profile(String name, String avatar) {
            this.name = name;
            this.avatar = avatar;
        }
    }

    private static List<Profile> all;
    private static final Map<String, Bitmap> AVATARS = new HashMap<>();   // "file@size" → circle-cropped avatar

    /** The profiles, from Config.PROFILES (read once per process; the assets don't change while it runs). */
    static synchronized List<Profile> all(Context c) {
        if (all != null) return all;
        List<String> files = avatarFiles(c);
        List<Profile> out = new ArrayList<>();
        for (String entry : Config.PROFILES.split(",")) {
            int colon = entry.indexOf(':');
            String name = (colon < 0 ? entry : entry.substring(0, colon)).trim();
            String avatar = colon < 0 ? "" : entry.substring(colon + 1).trim();
            if (name.isEmpty()) continue;
            if (!files.contains(avatar)) avatar = fallback(files, out.size());
            out.add(new Profile(name, avatar));
        }
        if (out.isEmpty()) out.add(new Profile(Ui.tr("Me"), fallback(files, 0)));
        return all = out;
    }

    /** The avatar for the n-th profile without one: memoji.png first (yours), then the bundled defaults in turn. */
    private static String fallback(List<String> files, int n) {
        if (n == 0 && files.contains("memoji.png")) return "memoji.png";
        List<String> defaults = new ArrayList<>();
        for (String f : files) if (f.startsWith("default-")) defaults.add(f);
        if (defaults.isEmpty()) return files.isEmpty() ? "" : files.get(0);
        java.util.Collections.sort(defaults);
        return defaults.get(n % defaults.size());
    }

    private static List<String> avatarFiles(Context c) {
        try {
            String[] list = c.getAssets().list("avatars");
            return list == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(list));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** The current profile's index (always a valid one). */
    static int current(Context c) {
        int n = all(c).size();
        return Math.max(0, Math.min(n - 1, sp(c).getInt("profile", 0)));
    }

    static void setCurrent(Context c, int index) {
        sp(c).edit().putInt("profile", index).apply();
    }

    /**
     * The profile's avatar as a circle `size` px wide (cached: made once per avatar and size, off the draw path), or
     * null without one.
     */
    static synchronized Bitmap avatar(Context c, Profile p, int size) {
        if (p == null || p.avatar.isEmpty() || size <= 0) return null;
        String key = p.avatar + "@" + size;
        if (AVATARS.containsKey(key)) return AVATARS.get(key);
        Bitmap out = null;
        try (InputStream in = c.getAssets().open("avatars/" + p.avatar)) {
            Bitmap src = BitmapFactory.decodeStream(in);
            if (src != null) {
                // Centre square, filtered down to the size, then cut into a circle (anti-aliased).
                int side = Math.min(src.getWidth(), src.getHeight());
                Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
                Bitmap small = Ui.downscale(square, size, size);
                out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                paint.setShader(new BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                new Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint);
            }
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("avatar " + p.avatar + ": " + e.getMessage());
        }
        AVATARS.put(key, out);
        return out;
    }
}
