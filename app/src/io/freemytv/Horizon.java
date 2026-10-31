package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The Frosted Horizon on screens of their own (the intro, "Who's Watching?"): web/home.css's .hz-day and .hz-night,
 * drawn once into a bitmap at half the screen's size (it's only soft gradients, so it scales without a seam) and kept
 * per theme. The home screen draws the same wallpaper through Scene's shader.
 */
final class Horizon {
    private Horizon() {}

    private static Bitmap day, night;
    private static int dayW, dayH, nightW, nightH;

    /** The day (or, `dark`, twilight) Horizon for a w × h screen: made on the first call for that size, then kept. */
    static synchronized Bitmap get(int w, int h, boolean dark) {
        int bw = Math.max(16, w / 2), bh = Math.max(9, h / 2);
        if (dark) {
            if (night == null || nightW != bw || nightH != bh) {
                night = render(bw, bh, true);
                nightW = bw;
                nightH = bh;
            }
            return night;
        }
        if (day == null || dayW != bw || dayH != bh) {
            day = render(bw, bh, false);
            dayW = bw;
            dayH = bh;
        }
        return day;
    }

    /** The base gradient, the three soft lights (Wallpaper.horizonGeometry, as ellipses) and the vignette. */
    private static Bitmap render(int w, int h, boolean dark) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int[] p = Wallpaper.palette(dark);
        Paint paint = new Paint();
        paint.setDither(true);   // broad gradients band on older panels otherwise
        paint.setShader(new LinearGradient(0, 0, 0, h, new int[] {p[0], dark ? p[1] : p[2], p[3]},
                new float[] {0, 0.56f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        float[][] geometry = Wallpaper.horizonGeometry();
        float[] fade = {0.72f, 0.74f, 0.72f};
        Matrix m = new Matrix();
        // Back to front as web/home.css lists them (its first radial is on top) and as Shaders.HORIZON mixes them:
        // the coastal blue (bottom right) first, then the mist, then the warm cream glow on the left over both.
        for (int i = geometry.length - 1; i >= 0; i--) {
            int color = p[i + 1];
            RadialGradient g = new RadialGradient(0, 0, 1, new int[] {color, color & 0x00FFFFFF},
                    new float[] {0, fade[i]}, Shader.TileMode.CLAMP);
            m.setScale(geometry[i][2] * w, geometry[i][2] * h);
            m.postTranslate(geometry[i][0] * w, geometry[i][1] * h);
            g.setLocalMatrix(m);
            paint.setShader(g);
            c.drawRect(0, 0, w, h, paint);
        }
        // CSS's "ellipse at center" reaches the corners: radii of √2 × half the width and height.
        RadialGradient v = new RadialGradient(0, 0, 1, new int[] {0, 0, Ui.alpha(0xFF000000, Wallpaper.horizonVignette(dark))},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
        m.setScale(w * 0.7071f, h * 0.7071f);
        m.postTranslate(w / 2f, h / 2f);
        v.setLocalMatrix(m);
        paint.setShader(v);
        c.drawRect(0, 0, w, h, paint);
        return b;
    }

    /** Draws it over `screen` at `alpha` (0–1) with `paint` (a FILTER_BITMAP_FLAG paint the caller keeps). */
    static void draw(Canvas c, Bitmap horizon, RectF screen, Paint paint, float alpha) {
        if (horizon == null || alpha <= 0.004f) return;
        paint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(horizon, null, screen, paint);
    }
}
