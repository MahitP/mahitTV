package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * Background (aurora) and glass, using the shaders in Shaders. The aurora is drawn into a ¼-scale layer, scaled up,
 * and only redrawn when it moves (advance, up to ~30 times per second); in the frames between steps the GPU just
 * reuses the layer. The glass reads the frosted aurora computed in Frost or, with a recommendation's image as the
 * background, its blurred version (setFrost).
 * If AGSL fails (it shouldn't on Android 13+), it falls back to a gradient and plain translucent glass: the home screen
 * is never left blank.
 * Without AGSL (Android 8 to 12) the glass over a photo is still glass: the photo's blurred copy (the same `int[]` the
 * shaders would read, box-averaged into a small bitmap that bilinear filtering spreads into a blur) is drawn through
 * the rounded shape, mapped to the screen so it shows what is really behind it, then the tint, the rim and a sheen
 * along the top. The small bitmaps and their pixel buffers are made once and reused; nothing is allocated per frame.
 */
final class Scene {
    final Shaders.Palette palette = new Shaders.Palette();
    private RuntimeShader aurora, horizon, glass, liquid;
    private final Frost frost = new Frost();
    private final Paint auroraPaint = new Paint();
    private final Paint horizonPaint = new Paint();
    private final Paint glassPaint = new Paint();
    private final Paint liquidPaint = new Paint();
    private final Paint flatFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallback = new Paint();
    private final Recording auroraNode = Recording.create("aurora");
    private final Recording horizonNode = Recording.create("horizon");
    // The layer is drawn at a quarter size and scaled up: filtered, its soft shapes stay soft instead of stepping.
    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int w = 1, h = 1, lw = 1, lh = 1;
    private float speed = 1, baseDim, dim, time;
    private boolean auroraDirty = true, horizonDirty = true, horizonMode, horizonDark, fallbackReady,
            imageFrost, imageGlass;
    private Bitmap frostImage, frostImageB, glassA, glassB;
    /** The key light's direction for the Liquid Glass (Shaders.LIQUID), top left unless something turns it. */
    static final float LIGHT_X = -0.4512f, LIGHT_Y = -0.8924f;
    private float lightX = LIGHT_X, lightY = LIGHT_Y, frostMix;
    private BitmapShader glassShaderA;
    private final boolean withGlass;

    // --- Without AGSL: the glass drawn with bitmaps
    /** The photo's glass copy (480×270) box-averaged 8×, and its frost copy (192×108) 4×: 60×33 and 48×27. */
    private static final int SOFT = 8, SOFT_FROST = 4;
    private static final int SW = Backdrop.GW / SOFT, SH = Backdrop.GH / SOFT, SFW = Backdrop.FW / SOFT_FROST, SFH = Backdrop.FH / SOFT_FROST;
    private final Paint softPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The faint echo of the rim along the bottom edge (the shaders' light from the top left, seen through). */
    private final Paint echoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // The glass's continuous-curvature ("squircle") outline, as the shaders draw it: made once per size and radius.
    private final android.graphics.Path squircle = new android.graphics.Path();
    private float squircleW = -1, squircleH = -1, squircleR = -1;
    /** The corners' superellipse exponent: 4, as in Shaders.LIQUID (DESIGN-TVOS.md §2). */
    private final Matrix softMatrix = new Matrix();
    private final RectF sheenRect = new RectF();
    private int[] softPxA, softPxB, softPxFrost, softPxFrostB;         // reused pixel buffers
    private Bitmap softA, softB, softFrost, softFrostB;               // reused small bitmaps
    // Remade after each setPixels (a shader keeps the pixels as they were).
    private BitmapShader softShaderA, softShaderB, softShaderFrost, softShaderFrostB;
    private float glassMix, offX, offY;

    Scene() {
        this(true);
    }

    Scene(boolean withGlass) {
        this.withGlass = withGlass;
        try {
            aurora = new RuntimeShader(Shaders.AURORA);
            glass = new RuntimeShader(Shaders.GLASS);
            liquid = new RuntimeShader(Shaders.LIQUID);
            auroraPaint.setShader(aurora);
            glassPaint.setShader(glass);
            liquidPaint.setShader(liquid);
            liquid.setFloatUniform("light", LIGHT_X, LIGHT_Y);
            glass.setFloatUniform("frostMix", 0);
        } catch (RuntimeException | LinkageError e) {   // Android 8 to 12: no AGSL, flat look instead
            AppLog.w("shader unavailable, using gradient: " + e.getMessage());
            aurora = null;
            glass = null;
            liquid = null;
        }
        try {
            horizon = new RuntimeShader(Shaders.HORIZON);
            horizonPaint.setShader(horizon);
        } catch (RuntimeException | LinkageError e) {
            AppLog.w("Horizon shader unavailable, using gradient: " + e.getMessage());
            horizon = null;
        }
        auroraNode.setUseCompositingLayer(true, layerPaint);
        horizonNode.setUseCompositingLayer(true, layerPaint);
        flatRim.setStyle(Paint.Style.STROKE);
        flatRim.setColor(0x33FFFFFF);
        flatRim.setStrokeWidth(1.5f);
        if (glass == null) {
            sheenPaint.setStyle(Paint.Style.STROKE);
            sheenPaint.setStrokeWidth(1.5f);
            echoPaint.setStyle(Paint.Style.STROKE);
            echoPaint.setStrokeWidth(1.5f);
        }
    }

    void setSize(int width, int height) {
        boolean sizeChanged = w != Math.max(1, width) || h != Math.max(1, height);
        w = Math.max(1, width);
        h = Math.max(1, height);
        lw = Math.max(1, Math.round(w / 4f));
        lh = Math.max(1, Math.round(h / 4f));
        auroraNode.setPosition(0, 0, lw, lh);
        horizonNode.setPosition(0, 0, lw, lh);
        if (glass == null) {
            // The sheen fades over a fixed height from each glass's top edge, drawn with a translate: a local matrix
            // per glass would rebuild the native gradient on every draw on Android 8 to 12.
            // The rim's light, from the glass's top-left corner along the diagonal (drawn moved to each glass's corner):
            // a catchlight there, fading along the top and left edges to a faint line.
            float reach = Math.max(1, h * 0.45f);
            sheenPaint.setShader(new android.graphics.LinearGradient(0, 0, reach, reach * 0.55f,
                    new int[] {0xC0FFFFFF, 0x40FFFFFF, 0x18FFFFFF}, new float[] {0, 0.35f, 1}, Shader.TileMode.CLAMP));
            echoPaint.setShader(new android.graphics.LinearGradient(0, 0, 0, -Math.max(1, h * 0.05f),
                    new int[] {0x2EFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        }
        if (aurora != null) {
            aurora.setFloatUniform("res", w, h);
            aurora.setFloatUniform("layer", lw, lh);
            setFrostScale();
            if (imageGlass) liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
        }
        if (horizon != null) {
            horizon.setFloatUniform("res", w, h);
            horizon.setFloatUniform("layer", lw, lh);
            horizonDirty = true;
        }
        if (sizeChanged || !fallbackReady) rebuildFallback();
        push();
    }

    /** speed: 1 on the home screen, less on the screensaver; dim: how much to darken (0 to 1). */
    void setMotion(float speed, float dim) {
        this.speed = speed;
        baseDim = dim;
        push();
    }

    /** Call after changing `palette`. Bright palettes are dimmed a little so white text stays readable. */
    void applyPalette() {
        push();
        if (!horizonMode) rebuildFallback();
    }

    /** Uses the fixed Frosted Horizon palette. `dark` selects its night colors. */
    void setHorizon(boolean dark) {
        if (horizonMode && horizonDark == dark) return;
        horizonMode = true;
        horizonDark = dark;
        if (horizon != null) {
            int[] colors = Wallpaper.palette(dark);
            color(horizon, "c0", colors[0]);
            color(horizon, "c1", colors[1]);
            color(horizon, "c2", colors[2]);
            color(horizon, "c3", colors[3]);
            horizon.setFloatUniform("dark", dark ? 1 : 0);
            horizonDirty = true;
        }
        rebuildFallback();
    }

    /** Returns to the existing animated Aurora background without disturbing its glass or animation state. */
    void setAurora() {
        if (!horizonMode) return;
        horizonMode = false;
        rebuildFallback();
    }

    boolean isHorizon() { return horizonMode; }

    private static void color(RuntimeShader shader, String name, int color) {
        shader.setFloatUniform(name, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f);
    }

    /** Rebuilt only when setSize or the selected background/theme changes, never from drawBackground. */
    private void rebuildFallback() {
        if (horizonMode) {
            int[] colors = Wallpaper.palette(horizonDark);
            fallback.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                    new int[] {colors[0], colors[2], colors[3]}, new float[] {0, 0.56f, 1}, Shader.TileMode.CLAMP));
        } else {
            fallback.setShader(Ui.gradient(h, palette.color(1), palette.color(0)));
        }
        fallbackReady = true;
    }

    private void push() {
        dim = Math.min(0.6f, baseDim + Math.max(0, Math.min(0.22f, (palette.brightest() - 0.45f) * 0.5f)));
        if (aurora == null) return;
        aurora.setFloatUniform("speed", speed);
        aurora.setFloatUniform("dim", dim);
        palette.apply(aurora);
        advance(time);
    }

    /** Moves the aurora to `seconds`: redraws the layer on the next draw, recomputes the glass's frosted aurora now. */
    void advance(float seconds) {
        time = seconds;
        if (aurora == null) return;
        aurora.setFloatUniform("t", seconds);
        if (withGlass && (!imageFrost || !imageGlass)) {
            BitmapShader s = frost.render(seconds, speed, dim, palette.c, (float) w / h);
            if (!imageFrost) {
                glass.setInputShader("frost", s);
                glass.setInputShader("frostB", s);
                glass.setFloatUniform("frostMix", 0);
            }
            if (!imageGlass) {
                liquid.setInputShader("imageA", s);
                liquid.setInputShader("imageB", s);
                liquid.setFloatUniform("mixB", 0);
                liquid.setFloatUniform("imageScale", (float) Frost.W / w, (float) Frost.H / h);
            }
        }
        auroraDirty = true;
    }

    /** The liquid glass now shows this photo (Backdrop.GW × GH, slightly blurred). */
    void setGlassImage(int[] pixels) {
        setGlassImages(pixels, null);
    }

    /** Photo transition: both photos in the glass, blended by setGlassMix (0 = the first one). */
    void setGlassImages(int[] from, int[] to) {
        imageGlass = true;
        glassMix = 0;
        if (liquid == null) {           // no AGSL (Android 8 to 12): the bitmaps path reads small soft copies instead
            if (softPxA == null) softPxA = new int[SW * SH];
            if (softA == null) softA = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
            softShaderA = soften(from, Backdrop.GW, SOFT, SW, SH, softPxA, softA, true);
            if (to != null) {
                if (softPxB == null) softPxB = new int[SW * SH];
                if (softB == null) softB = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
                softShaderB = soften(to, Backdrop.GW, SOFT, SW, SH, softPxB, softB, true);
            } else {
                softShaderB = null;
            }
            return;
        }
        glassA = fill(glassA, from);
        if (to != null) glassB = fill(glassB, to);
        glassShaderA = linear(glassA);
        liquid.setInputShader("imageA", glassShaderA);
        liquid.setInputShader("imageB", to != null ? linear(glassB) : glassShaderA);
        liquid.setFloatUniform("mixB", 0);
        liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
    }

    void setGlassMix(float t) {
        glassMix = Math.max(0, Math.min(1, t));
        if (liquid != null) liquid.setFloatUniform("mixB", glassMix);
    }

    /** The liquid glass goes back to the frosted aurora. */
    void clearGlass() {
        if (!imageGlass) return;
        imageGlass = false;
        advance(time);
    }

    private static Bitmap fill(Bitmap b, int[] pixels) {
        if (b == null) b = Bitmap.createBitmap(Backdrop.GW, Backdrop.GH, Bitmap.Config.ARGB_8888);
        b.setPixels(pixels, 0, Backdrop.GW, 0, 0, Backdrop.GW, Backdrop.GH);
        return b;
    }

    private static BitmapShader linear(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return s;
    }

    /**
     * Box-averages `src` (stride `srcW`) by `f` on each side into `px` (`w` × `h`) and `into`, and returns a shader
     * over it (made again each time: a BitmapShader keeps the pixels as they were when it was created). Scaled up on
     * screen with bilinear filtering, the small copy reads as a wide blur. ~20k to 130k pixel reads: well under a
     * millisecond, so it can run on every step of a photo fade. `vivid`: the Liquid Glass's color boost (saturation
     * ×1.35, brightness ×1.02, as in Shaders.LIQUID), baked in here instead of computed per pixel on screen.
     */
    private static BitmapShader soften(int[] src, int srcW, int f, int w, int h, int[] px, Bitmap into, boolean vivid) {
        int n = f * f;
        for (int y = 0; y < h; y++) {
            int rowBase = y * f * srcW;
            for (int x = 0; x < w; x++) {
                int r = 0, g = 0, b = 0, base = rowBase + x * f;
                for (int j = 0; j < f; j++) {
                    int i = base + j * srcW;
                    for (int k = 0; k < f; k++) {
                        int c = src[i + k];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                    }
                }
                r /= n;
                g /= n;
                b /= n;
                if (vivid) {
                    float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    r = Math.min(255, Math.max(0, Math.round((l + (r - l) * 1.35f) * 1.02f)));
                    g = Math.min(255, Math.max(0, Math.round((l + (g - l) * 1.35f) * 1.02f)));
                    b = Math.min(255, Math.max(0, Math.round((l + (b - l) * 1.35f) * 1.02f)));
                }
                px[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        into.setPixels(px, 0, w, 0, 0, w, h);
        return Frost.filter(new BitmapShader(into, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), BitmapShader.FILTER_MODE_LINEAR);
    }

    /**
     * Liquid Glass in rectangle `r` (screen coordinates). band and refraction in px (the thick rim and how much it
     * bends the image), specular = rim highlight, alpha = opacity of the whole glass.
     */
    void liquid(Canvas c, RectF r, float radius, int tint, float tintAlpha, float lift, float band, float refraction,
                float specular, float alpha) {
        if (alpha <= 0.004f) return;
        if (liquid == null) {
            if (imageGlass && softShaderA != null) {
                // Mapped by what the copy covers: 270 / 8 = 33.75 rows, of which the bitmap holds 33.
                bitmapGlass(c, r, radius, softShaderA, Backdrop.GW / (float) SOFT, Backdrop.GH / (float) SOFT, softShaderB,
                        glassMix, tint, Math.max(0.24f, tintAlpha), lift, 1, specular, alpha);
            } else {
                glass(c, r, radius, tint, Math.max(0.5f, tintAlpha), 0, 0, lift, 1, alpha);
            }
            return;
        }
        liquid.setFloatUniform("center", r.centerX(), r.centerY());
        liquid.setFloatUniform("halfSize", r.width() / 2, r.height() / 2);
        liquid.setFloatUniform("radius", Math.min(radius, Math.min(r.width(), r.height()) / 2));
        liquid.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        liquid.setFloatUniform("lift", lift);
        liquid.setFloatUniform("band", Math.max(1, band));
        liquid.setFloatUniform("refraction", refraction);
        liquid.setFloatUniform("specular", specular);
        liquidPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, liquidPaint);
        liquidPaint.setAlpha(255);
    }

    /** The glass now reads the blurred background image (Backdrop.FW × FH, ARGB). */
    void setFrost(int[] pixels) {
        setFrostPair(pixels, null);
    }

    /**
     * A fade between two blurred images (day ⇄ night, one photo to the next): both are loaded once here, and each step
     * of the fade only moves setFrostMix (no pixels copied, no shader made per frame). `b` null: just `a`.
     */
    void setFrostPair(int[] a, int[] b) {
        imageFrost = true;
        frostMix = 0;
        if (glass == null) {            // as above: small soft copies for the bitmaps path
            if (softPxFrost == null) softPxFrost = new int[SFW * SFH];
            if (softFrost == null) softFrost = Bitmap.createBitmap(SFW, SFH, Bitmap.Config.ARGB_8888);
            softShaderFrost = soften(a, Backdrop.FW, SOFT_FROST, SFW, SFH, softPxFrost, softFrost, false);
            if (b != null) {
                if (softPxFrostB == null) softPxFrostB = new int[SFW * SFH];
                if (softFrostB == null) softFrostB = Bitmap.createBitmap(SFW, SFH, Bitmap.Config.ARGB_8888);
                softShaderFrostB = soften(b, Backdrop.FW, SOFT_FROST, SFW, SFH, softPxFrostB, softFrostB, false);
            } else {
                softShaderFrostB = null;
            }
            return;
        }
        if (frostImage == null) frostImage = Bitmap.createBitmap(Backdrop.FW, Backdrop.FH, Bitmap.Config.ARGB_8888);
        frostImage.setPixels(a, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
        BitmapShader s = linear(frostImage);
        glass.setInputShader("frost", s);
        if (b != null) {
            if (frostImageB == null) frostImageB = Bitmap.createBitmap(Backdrop.FW, Backdrop.FH, Bitmap.Config.ARGB_8888);
            frostImageB.setPixels(b, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
            glass.setInputShader("frostB", linear(frostImageB));
        } else {
            glass.setInputShader("frostB", s);
        }
        glass.setFloatUniform("frostMix", 0);
        setFrostScale();
    }

    /** How far a setFrostPair fade has come (0 = the first image). */
    void setFrostMix(float t) {
        frostMix = Math.max(0, Math.min(1, t));
        if (glass != null) glass.setFloatUniform("frostMix", frostMix);
    }

    /** Turns the Liquid Glass's key light (a unit direction); lightDefault() puts it back at the top left. */
    void setLight(float x, float y) {
        if (x == lightX && y == lightY) return;
        lightX = x;
        lightY = y;
        if (liquid != null) liquid.setFloatUniform("light", x, y);
    }

    void lightDefault() {
        setLight(LIGHT_X, LIGHT_Y);
    }

    /** Back to the frosted aurora. */
    void clearFrost() {
        if (!imageFrost) return;
        imageFrost = false;
        setFrostScale();
        advance(time);
    }

    /** True where the glass is drawn by shaders (Android 13+); without them a plain color needs no image pass. */
    boolean shaded() {
        return glass != null;
    }

    /** The current blurred image (so the bottom drawer can blur the background), or null with the aurora. */
    Bitmap frostImage() {
        return imageFrost ? frostImage : null;
    }

    /** Offset (px) of the layer that will draw glass, if it was moved on screen without being redrawn. */
    void setOffset(float x, float y) {
        offX = x;
        offY = y;
        if (glass != null) glass.setFloatUniform("offset", x, y);
        if (liquid != null) liquid.setFloatUniform("offset", x, y);
    }

    private void setFrostScale() {
        if (glass == null) return;
        if (imageFrost) glass.setFloatUniform("frostScale", (float) Backdrop.FW / w, (float) Backdrop.FH / h);
        else glass.setFloatUniform("frostScale", (float) Frost.W / w, (float) Frost.H / h);
    }

    void drawBackground(Canvas c) {
        if (horizonMode) {
            if (horizon == null) {
                c.drawRect(0, 0, w, h, fallback);
                return;
            }
            if (horizonDirty) {
                Canvas rc = horizonNode.beginRecording(lw, lh);
                try {
                    rc.drawPaint(horizonPaint);
                } finally {
                    horizonNode.endRecording();
                }
                horizonDirty = false;
            }
            drawLayer(c, horizonNode);
            return;
        }
        if (aurora == null) {
            c.drawRect(0, 0, w, h, fallback);
            return;
        }
        if (auroraDirty) {
            Canvas rc = auroraNode.beginRecording(lw, lh);
            try {
                rc.drawPaint(auroraPaint);
            } finally {
                auroraNode.endRecording();
            }
            auroraDirty = false;
        }
        drawLayer(c, auroraNode);
    }

    private void drawLayer(Canvas c, Recording node) {
        c.save();
        c.scale((float) w / lw, (float) h / lh);
        node.draw(c);
        c.restore();
    }

    /**
     * Glass in rectangle `r` (screen coordinates, no canvas transform: the shader uses the real position).
     * body = 1: full glass; body = 0: just the light rim, on top of something already drawn.
     */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body) {
        glass(c, r, radius, tint, tintAlpha, glow, glowAlpha, lift, body, 1);
    }

    /** Same, with an opacity (0 to 1) for the whole glass. */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body, float alpha) {
        if (alpha <= 0.004f) return;
        if (glass == null) {
            if (imageFrost && softShaderFrost != null) {
                bitmapGlass(c, r, radius, softShaderFrost, Backdrop.FW / (float) SOFT_FROST, Backdrop.FH / (float) SOFT_FROST,
                        softShaderFrostB, frostMix,
                        tint, Math.max(0.35f, tintAlpha), lift, body, 1, alpha);
                return;
            }
            if (body > 0) {
                flatFill.setColor(Ui.alpha(tint | 0xFF000000, Math.max(0.35f, tintAlpha)));
                c.drawRoundRect(r, radius, radius, flatFill);
            }
            c.drawRoundRect(r, radius, radius, flatRim);
            return;
        }
        float hw = r.width() / 2, hh = r.height() / 2, inner = Math.min(hw, hh);
        glass.setFloatUniform("center", r.centerX(), r.centerY());
        glass.setFloatUniform("halfSize", hw, hh);
        glass.setFloatUniform("invHalf", 1 / Math.max(hw, 1), 1 / Math.max(hh, 1));
        glass.setFloatUniform("radius", radius);
        glass.setFloatUniform("top", r.top);
        glass.setFloatUniform("depth", 1 / Math.max(inner * 0.6f, 1), Math.min(24, inner * 0.5f), 1 / Math.max(inner * 0.9f, 1), 1 / Math.max(r.height(), 1));
        glass.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        glass.setFloatUniform("glow", Color.red(glow) / 255f, Color.green(glow) / 255f, Color.blue(glow) / 255f, glowAlpha);
        glass.setFloatUniform("lift", lift);
        glass.setFloatUniform("body", body);
        glassPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, glassPaint);
        glassPaint.setAlpha(255);
    }

    /**
     * The glass without shaders: the soft copy `a` (`aw` × `ah`; blended with `b` by `mix` during a photo change)
     * stretched to the screen and drawn through the rounded rectangle, minus the layer's offset, so the glass shows
     * what is really behind it; then the same tint as the flat fill, a faint lift, the rim and a sheen on the top
     * edge. The paints and matrices are reused: nothing is allocated here.
     */
    private void bitmapGlass(Canvas c, RectF r, float radius, BitmapShader a, float aw, float ah, BitmapShader b, float mix,
                             int tint, float tintAlpha, float lift, float body, float specular, float alpha) {
        radius = Math.min(radius, Math.min(r.width(), r.height()) / 2);
        // A capsule (the pill, the buttons) keeps the plain round rect: its size changes as it grows with the focus,
        // and a path per size would be a new mask every frame. The Dock's glass is a squircle, as the shaders draw it.
        boolean curved = radius < Math.min(r.width(), r.height()) / 2 - 0.5f;
        if (curved) squircle(r.width(), r.height(), radius);
        if (body > 0) {
            drawThrough(c, r, radius, a, aw, ah, alpha * body, curved);
            if (b != null && mix > 0.004f) drawThrough(c, r, radius, b, aw, ah, alpha * body * mix, curved);
            // The tint and the lift in one pass (fill rate is what this GPU runs out of): the lift pulls the tint a
            // little toward white instead of a second full-area fill.
            int tinted = lift > 0.001f ? blend(tint | 0xFF000000, 0xFFFFFFFF, Math.min(0.25f, lift * 2)) : tint | 0xFF000000;
            flatFill.setColor(Ui.alpha(tinted, tintAlpha * alpha * body));
            shape(c, r, radius, flatFill, curved);
        }
        flatRim.setAlpha(Math.round(0x24 * alpha));
        shape(c, r, radius, flatRim, curved);
        flatRim.setAlpha(0x33);
        if (specular > 0.001f) {
            // The rim's highlight: bright along the top, fading down; and its faint echo along the bottom edge.
            int a8 = Math.round(255 * Math.min(1, specular) * alpha);
            sheenPaint.setAlpha(a8);
            c.save();
            c.translate(r.left, r.top);
            sheenRect.set(0, 0, r.width(), r.height());
            shape(c, sheenRect, radius, sheenPaint, curved);
            c.restore();
            echoPaint.setAlpha(a8);
            c.save();
            c.translate(0, r.bottom);
            sheenRect.set(r.left, -r.height(), r.right, 0);
            shape(c, sheenRect, radius, echoPaint, curved);
            c.restore();
        }
    }

    /** Apple's continuous-corner outline for a `w` × `h` glass with corner `radius`, at the origin (kept while the size stays). */
    private void squircle(float w, float h, float radius) {
        if (w == squircleW && h == squircleH && radius == squircleR) return;
        squircleW = w;
        squircleH = h;
        squircleR = radius;
        Corners.continuous(squircle, w, h, radius);
    }

    /**
     * Fills or strokes the glass's outline: the cached squircle (moved to `r`, then back, so every paint's shader stays
     * in screen coordinates) or a plain rounded rectangle.
     */
    private void shape(Canvas c, RectF r, float radius, Paint p, boolean curved) {
        if (!curved) {
            c.drawRoundRect(r, radius, radius, p);
            return;
        }
        squircle.offset(r.left, r.top);
        c.drawPath(squircle, p);
        squircle.offset(-r.left, -r.top);
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** `shader` (covering `bw` × `bh` of its bitmap's scale) stretched over the screen, inside the rounded rectangle. */
    private void drawThrough(Canvas c, RectF r, float radius, BitmapShader shader, float bw, float bh, float alpha, boolean curved) {
        softMatrix.setScale(w / bw, h / bh);
        softMatrix.postTranslate(-offX, -offY);
        shader.setLocalMatrix(softMatrix);
        softPaint.setShader(shader);
        softPaint.setAlpha(Math.round(255 * Math.max(0, Math.min(1, alpha))));
        shape(c, r, radius, softPaint, curved);
        softPaint.setShader(null);
    }
}
