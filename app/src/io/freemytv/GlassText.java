package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Text as a sheet of glass, for the refractive shaders (Shaders.GLASS_TEXT: the screensaver's clock; Shaders.GLASS_WORD:
 * the startup's wordmark). From one line of text it builds, off the UI thread: `cover`, the sharp rendering (its alpha
 * is the glass's shape); `slope`, opaque: R and G = the surface slope (the height gradient, pointing into the stroke;
 * 0.5 = flat), B = the height (0.5 at the edge, 1 in the middle of the strokes); and `shadow`, the soft rendering.
 */
final class GlassText {
    private GlassText() {}

    static final class Built {
        final Bitmap cover, slope, shadow;
        /** Empty margin around the ink on every side (px), and the ink's bounds relative to the text origin. */
        final int pad;
        final Rect ink;

        Built(Bitmap cover, Bitmap slope, Bitmap shadow, int pad, Rect ink) {
            this.cover = cover;
            this.slope = slope;
            this.shadow = shadow;
            this.pad = pad;
            this.ink = ink;
        }
    }

    /** `paint` is drawn white (its color is ignored); `k` is the screen's scale (1 at 1080p). */
    static Built build(String text, Paint paint, float k) {
        Paint white = new Paint(paint);
        white.setColor(0xFFFFFFFF);
        white.setShader(null);
        white.clearShadowLayer();
        Rect ink = new Rect();
        white.getTextBounds(text, 0, text.length(), ink);
        int pad = Math.round(40 * k);
        int w = ink.width() + 2 * pad, h = ink.height() + 2 * pad;
        Bitmap cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(cover).drawText(text, pad - ink.left, pad - ink.top, white);
        int[] px = new int[w * h];
        cover.getPixels(px, 0, w, 0, 0, w, h);
        int[] alpha = new int[px.length];
        for (int i = 0; i < px.length; i++) alpha[i] = (px[i] >>> 24) * 16;
        int[] height = blur(alpha, w, h, Math.max(2, Math.round(6 * k)), 3);
        int[] soft = blur(alpha, w, h, Math.max(3, Math.round(12 * k)), 3);
        float max = 1;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = height[i + 1] - height[i - 1], gy = height[i + w] - height[i - w];
                max = Math.max(max, (float) Math.sqrt(gx * gx + gy * gy));
            }
        }
        int[] slope = new int[px.length], shadow = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float gx = x > 0 && x < w - 1 ? height[i + 1] - height[i - 1] : 0;
                float gy = y > 0 && y < h - 1 ? height[i + w] - height[i - w] : 0;
                int r = 128 + Math.round(127 * gx / max), g = 128 + Math.round(127 * gy / max);
                int b = Math.min(255, height[i] / 16);
                slope[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                shadow[i] = Math.min(255, soft[i] / 16) << 24;
            }
        }
        return new Built(cover, Bitmap.createBitmap(slope, w, h, Bitmap.Config.ARGB_8888),
                Bitmap.createBitmap(shadow, w, h, Bitmap.Config.ARGB_8888), pad, ink);
    }

    /** The alpha of `cover` blurred by about `r` px (three box passes), as an ALPHA_8 mask drawn in a paint's color. */
    static Bitmap defocused(Bitmap cover, int r) {
        int w = cover.getWidth(), h = cover.getHeight();
        int[] px = new int[w * h];
        cover.getPixels(px, 0, w, 0, 0, w, h);
        int[] alpha = new int[px.length];
        for (int i = 0; i < px.length; i++) alpha[i] = (px[i] >>> 24) * 16;
        int[] soft = blur(alpha, w, h, Math.max(1, r), 3);
        for (int i = 0; i < px.length; i++) px[i] = (Math.min(255, soft[i] / 16) << 24) | 0xFFFFFF;
        Bitmap argb = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        Bitmap mask = argb.extractAlpha();
        argb.recycle();
        return mask;
    }

    /** Box blur (passes of radius r in both directions); anything outside the image counts as empty. */
    static int[] blur(int[] src, int w, int h, int r, int passes) {
        int[] a = src.clone(), b = new int[src.length];
        for (int pass = 0; pass < passes; pass++) {
            box(a, b, w, h, r, true);
            box(b, a, w, h, r, false);
        }
        return a;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep, sum = 0;
            for (int i = 0; i <= r && i < n; i++) sum += in[base + i * step];
            for (int i = 0; i < n; i++) {
                out[base + i * step] = (sum + div / 2) / div;
                int add = i + r + 1, sub = i - r;
                if (add < n) sum += in[base + add * step];
                if (sub >= 0) sum -= in[base + sub * step];
            }
        }
    }
}
