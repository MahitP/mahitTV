package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.SparseArray;

/**
 * Multicolour weather symbols drawn in code, as Apple's Weather draws them: a yellow sun, a pale moon, white clouds,
 * blue rain, a yellow bolt; keyed by Open-Meteo's WMO code. Each is drawn once into a bitmap (with a soft shadow, so the
 * white clouds read on the bright day Horizon too) and kept, keyed by an int: a frame only looks one up.
 * Adapted from tvOS-Launcher (Apache-2.0).
 */
final class WeatherIcons {
    private WeatherIcons() {}

    private static final SparseArray<Bitmap> CACHE = new SparseArray<>();
    private static final int SUN_A = 0xFFFFE15A, SUN_B = 0xFFFFB300, RAIN = 0xFF4AB4FF, BOLT = 0xFFFFD60A;
    private static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, DRIZZLE = 4, WET = 5, SNOW = 6, THUNDER = 7;

    /** The symbol for `code` (day or night) at `size` px; made on the first call, then kept. Call off onDraw first. */
    static Bitmap get(int code, boolean day, int size) {
        size = Math.max(8, Math.min(512, size));
        int key = (kind(code) * 2 + (day ? 1 : 0)) * 1024 + size;
        Bitmap b = CACHE.get(key);
        if (b != null) return b;
        b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        draw(new Canvas(b), kind(code), day, size);
        if (CACHE.size() > 64) CACHE.clear();
        CACHE.put(key, b);
        return b;
    }

    private static int kind(int code) {
        if (code == 0) return CLEAR;
        if (code == 1 || code == 2) return PARTLY;
        if (code == 3) return CLOUDY;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 57) return DRIZZLE;
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return WET;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return SNOW;
        if (code >= 95) return THUNDER;
        return CLOUDY;
    }

    /** Drawn a little inside the square (0.86), so the shadow has room. */
    private static void draw(Canvas c, int kind, boolean day, float size) {
        float s = size * 0.86f;
        c.translate(size * 0.07f, size * 0.05f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShadowLayer(s * 0.05f, 0, s * 0.02f, 0x38000000);
        switch (kind) {
            case CLEAR:
                if (day) sun(c, p, s * 0.5f, s * 0.5f, s * 0.22f, true);
                else moon(c, p, s * 0.5f, s * 0.5f, s * 0.3f);
                break;
            case PARTLY:
                if (day) sun(c, p, s * 0.64f, s * 0.36f, s * 0.16f, true);
                else moon(c, p, s * 0.64f, s * 0.36f, s * 0.2f);
                cloud(c, p, s * 0.06f, s * 0.4f, s * 0.7f, false);
                break;
            case FOG:
                cloud(c, p, s * 0.14f, s * 0.14f, s * 0.72f, false);
                p.setShader(null);
                p.setColor(0xCCE5E5EA);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(s * 0.06f);
                c.drawLine(s * 0.16f, s * 0.7f, s * 0.84f, s * 0.7f, p);
                c.drawLine(s * 0.26f, s * 0.84f, s * 0.74f, s * 0.84f, p);
                break;
            case DRIZZLE:
                cloud(c, p, s * 0.12f, s * 0.12f, s * 0.76f, false);
                drops(c, p, s, 0.1f);
                break;
            case WET:
                cloud(c, p, s * 0.12f, s * 0.12f, s * 0.76f, false);
                drops(c, p, s, 0.17f);
                break;
            case SNOW:
                cloud(c, p, s * 0.12f, s * 0.12f, s * 0.76f, false);
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                float[][] flakes = {{0.32f, 0.74f}, {0.52f, 0.82f}, {0.72f, 0.74f}, {0.42f, 0.92f}, {0.62f, 0.94f}};
                for (float[] d : flakes) c.drawCircle(s * d[0], s * d[1], s * 0.045f, p);
                break;
            case THUNDER:
                cloud(c, p, s * 0.12f, s * 0.1f, s * 0.76f, false);
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setColor(BOLT);
                Path bolt = new Path();
                bolt.moveTo(s * 0.54f, s * 0.56f);
                bolt.lineTo(s * 0.38f, s * 0.78f);
                bolt.lineTo(s * 0.5f, s * 0.78f);
                bolt.lineTo(s * 0.43f, s * 0.97f);
                bolt.lineTo(s * 0.64f, s * 0.7f);
                bolt.lineTo(s * 0.52f, s * 0.7f);
                bolt.lineTo(s * 0.6f, s * 0.56f);
                bolt.close();
                c.drawPath(bolt, p);
                break;
            default:   // CLOUDY
                cloud(c, p, s * 0.3f, s * 0.2f, s * 0.62f, true);
                cloud(c, p, s * 0.06f, s * 0.36f, s * 0.74f, false);
        }
    }

    private static void sun(Canvas c, Paint p, float cx, float cy, float r, boolean rays) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(cx, cy - r, cx, cy + r, SUN_A, SUN_B, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        if (!rays) return;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(r * 0.28f);
        p.setShader(null);
        p.setColor(SUN_B);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float x0 = (float) Math.cos(a), y0 = (float) Math.sin(a);
            c.drawLine(cx + x0 * r * 1.45f, cy + y0 * r * 1.45f, cx + x0 * r * 1.85f, cy + y0 * r * 1.85f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static void moon(Canvas c, Paint p, float cx, float cy, float r) {
        Path full = new Path(), bite = new Path();
        full.addCircle(cx, cy, r, Path.Direction.CW);
        bite.addCircle(cx + r * 0.55f, cy - r * 0.4f, r * 0.82f, Path.Direction.CW);
        full.op(bite, Path.Op.DIFFERENCE);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(cx, cy - r, cx, cy + r, 0xFFFFFFFF, 0xFFD9DCE8, Shader.TileMode.CLAMP));
        c.drawPath(full, p);
        p.setShader(null);
    }

    /** A cloud w wide (0.6 w tall) with its top-left at (l, t); `back` is the grey one behind. */
    private static void cloud(Canvas c, Paint p, float l, float t, float w, boolean back) {
        float h = w * 0.6f;
        Path path = new Path(), part = new Path();
        path.addCircle(l + w * 0.56f, t + h * 0.44f, w * 0.27f, Path.Direction.CW);
        part.addCircle(l + w * 0.3f, t + h * 0.62f, w * 0.19f, Path.Direction.CW);
        path.op(part, Path.Op.UNION);
        part.reset();
        part.addCircle(l + w * 0.8f, t + h * 0.68f, w * 0.16f, Path.Direction.CW);
        path.op(part, Path.Op.UNION);
        part.reset();
        part.addRoundRect(new RectF(l + w * 0.12f, t + h * 0.55f, l + w * 0.96f, t + h * 0.95f), h * 0.2f, h * 0.2f,
                Path.Direction.CW);
        path.op(part, Path.Op.UNION);
        p.setStyle(Paint.Style.FILL);
        p.setShader(back
                ? new LinearGradient(0, t, 0, t + h, 0xFFB8BECB, 0xFF8E95A3, Shader.TileMode.CLAMP)
                : new LinearGradient(0, t, 0, t + h, 0xFFFFFFFF, 0xFFDDE1EA, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
    }

    private static void drops(Canvas c, Paint p, float s, float len) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(s * 0.065f);
        p.setColor(RAIN);
        for (int i = 0; i < 3; i++) {
            float x = s * (0.34f + i * 0.17f), y = s * 0.68f;
            c.drawLine(x, y, x - s * len * 0.4f, y + s * len, p);
        }
        p.setStyle(Paint.Style.FILL);
    }
}
