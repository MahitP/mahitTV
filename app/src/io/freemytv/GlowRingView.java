package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.Choreographer;
import android.view.View;

/**
 * Music Glow's light (DESIGN-MUSIC-GLOW.md): a ring around the whole picture in the album art's two colours. A thin hot
 * core right at the edge and a soft bloom inward; the colours flow around the rim as two slow waves with a fine
 * shimmer; each beat flashes the bloom, whitens the core and widens the band; loudness sets how bright and how fast.
 * Music in: it swells in. Out: it breathes out, its flow slowing as it fades, and then it isn't drawn at all.
 * Android 13+: one AGSL shader over the edge bands only. Older: the accent fading in from each edge.
 * No allocation in onDraw or doFrame.
 */
final class GlowRingView extends View implements Choreographer.FrameCallback {
    /** How the light behaves now, read once a frame (GlowRing writes it from the audio thread). */
    interface Source {
        /** Beats seen so far: a new number is a beat. */
        int beats();

        /** 0–1: how loud the music is against its own recent peak; -1: no audio data (a steady glow instead). */
        float energy();
    }

    interface Listener {
        /** The light has breathed out completely: the window can go. */
        void onDark();
    }

    // Glow Style: core width, bloom width (px at 1080p) and strength.
    private static final float[][] STYLES = {{2.6f, 40, 0.8f}, {4f, 64, 1f}, {5.5f, 92, 1.15f}};

    private static final String SHADER =
        "uniform float2 res;\n" +
        "uniform float edge;\n" +      // the core's width (px)
        "uniform float glow;\n" +      // the bloom's width (px)
        "uniform float band;\n" +      // how far in it's drawn: it reaches 0 there
        "uniform float phase;\n" +     // the colours' flow around the rim (turns)
        "uniform float t;\n" +         // seconds, for the shimmer
        "uniform float amount;\n" +    // 0–1: in and out
        "uniform float energy;\n" +    // 0–1: loudness
        "uniform float bloom;\n" +     // 0–1: the beat's flash
        "uniform half3 accent;\n" +
        "uniform half3 deep;\n" +
        "float hash(float2 p) {\n" +
        "  p = fract(p * float2(123.34, 456.21));\n" +
        "  p += dot(p, p + 45.32);\n" +
        "  return fract(p.x * p.y);\n" +
        "}\n" +
        // Light from each of the four edges, added: the corners blend with no crease (a rounded box's distance has one).
        "float edges(float2 xy, float w) {\n" +
        "  return exp(-xy.x / w) + exp(-(res.x - xy.x) / w) + exp(-xy.y / w) + exp(-(res.y - xy.y) / w);\n" +
        "}\n" +
        "half4 main(float2 xy) {\n" +
        "  float2 c = res * 0.5;\n" +
        "  float dmin = min(min(xy.x, res.x - xy.x), min(xy.y, res.y - xy.y));\n" +
        "  float2 v = (xy - c) / c;\n" +
        "  float u = atan(v.y, v.x) / 6.2831853;\n" +              // around the rim, -0.5..0.5
        "  float w1 = 0.5 + 0.5 * sin((u * 2.0 + phase) * 6.2831853);\n" +
        "  float w2 = 0.5 + 0.5 * sin((u * 3.0 - phase * 0.7 + 0.25 * sin(t * 0.31)) * 6.2831853);\n" +
        "  float m = smoothstep(0.12, 0.88, mix(w1, w2, 0.38));\n" +
        "  half3 col = mix(deep, accent, half(m));\n" +
        "  float coreW = edge * (1.0 + 0.9 * bloom);\n" +
        "  float haloW = glow * (1.0 + 0.22 * bloom + 0.12 * energy);\n" +
        "  float core = min(edges(xy, coreW), 1.0);\n" +
        "  float shimmer = 1.0 + 0.09 * sin(u * 37.7 + t * 1.4) * (0.35 + bloom);\n" +
        "  float halo = (1.0 - exp(-edges(xy, haloW) * 1.15)) * shimmer;\n" +
        "  halo *= 1.0 - smoothstep(band * 0.5, band, dmin);\n" +   // nothing at the band's inner edge, or the middle
        "  float strength = amount * (0.52 + 0.30 * energy + 0.42 * bloom);\n" +
        "  float a = (core * 0.9 + halo * 0.68) * strength;\n" +
        "  half3 rgb = mix(col, half3(1.0), half(core * 0.38 * (0.35 + bloom)));\n" +
        "  a = clamp(a + (hash(xy + t) - 0.5) / 255.0, 0.0, 1.0);\n" +   // dither: no bands on an 8-bit panel
        "  return half4(rgb * half(a), half(a));\n" +
        "}\n";

    private final Source source;
    private final Listener listener;
    private final Paint paint = new Paint();
    private final Paint[] edges = new Paint[4];
    private Object shader;   // RuntimeShader on Android 13+
    private boolean posted, wanted, screenOn = true;
    private long lastNanos;
    private float w, h, k = 1, clock, phase, flow;
    private int style = 1, lastBeats;
    // In and out, the beat's flash (its target decays), loudness, and the colours' cross-fade.
    private final Spring amount = new Spring(0.9f, 1f, 0), energy = new Spring(Spring.SNAPPY_FOCUS, 0.5f);
    private float bloom, bloomTarget;
    private final float[] accent = {1, 1, 1}, deep = {0.5f, 0.6f, 0.8f}, accentTo = {1, 1, 1}, deepTo = {0.5f, 0.6f, 0.8f};
    private final float[] accentFrom = new float[3], deepFrom = new float[3];
    private final Spring blend = new Spring(1.4f, 1f, 1);

    GlowRingView(Context c, Source source, Listener listener) {
        super(c);
        this.source = source;
        this.listener = listener;
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                shader = new RuntimeShader(SHADER);
            } catch (RuntimeException e) {
                AppLog.w("music glow: shader: " + e.getMessage());
            }
        }
        for (int i = 0; i < 4; i++) edges[i] = new Paint();
    }

    /** Whether the light's shader compiles on this TV (logged once as the key service starts). */
    static String shaderCheck() {
        if (Build.VERSION.SDK_INT < 33) return "plain edges (Android " + Build.VERSION.SDK_INT + ")";
        try {
            new RuntimeShader(SHADER);
            return "shader ready";
        } catch (RuntimeException e) {
            return "shader failed: " + e.getMessage();
        }
    }

    /** Glow Style: 0 Subtle, 1 Balanced, 2 Vivid. */
    void setStyle(int s) {
        style = Math.max(0, Math.min(2, s));
        layoutEdges();
        invalidate();
    }

    /** The album art's colours (0–1 RGB): they cross-fade in. */
    void setColors(float[] a, float[] d) {
        System.arraycopy(accent, 0, accentFrom, 0, 3);
        System.arraycopy(deep, 0, deepFrom, 0, 3);
        System.arraycopy(a, 0, accentTo, 0, 3);
        System.arraycopy(d, 0, deepTo, 0, 3);
        if (amount.value < 0.01f) {   // dark: the new colours at once
            blend.snap(1);
            mixColors();
        } else {
            blend.snap(0);
            blend.target = 1;
        }
        layoutEdges();
        wake();
    }

    /** Music playing (true) or not: it swells in, or breathes out. */
    void setOn(boolean on) {
        wanted = on;
        amount.tune(on ? 0.9f : 1.6f, 1f);
        amount.target = on ? 1 : 0;
        if (on) lastBeats = source.beats();   // no flash for beats from before
        wake();
    }

    /** The screen went off (nothing drawn) or came back. */
    void setScreen(boolean on) {
        screenOn = on;
        if (on) wake();
    }

    boolean lit() {
        return amount.value > 0.002f || amount.target > 0;
    }

    private void wake() {
        if (posted || !screenOn) return;
        posted = true;
        lastNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long nanos) {
        posted = false;
        if (!screenOn) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(1 / 20f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        clock += dt;
        amount.step(dt);
        blend.step(dt);
        mixColors();
        // The beat: a fast rise (about 35 ms) to its target, which falls away over about 200 ms.
        int beats = source.beats();
        float e = source.energy();
        if (beats != lastBeats && wanted) {
            lastBeats = beats;
            bloomTarget = 1;
        }
        if (e < 0) {   // no audio data: a steady glow that breathes every 4.5 s (Lumn's idle glow)
            bloomTarget = 0;
            energy.target = 0.55f + 0.12f * (float) Math.sin(clock * 2 * Math.PI / 4.5);
        } else {
            energy.target = e;
        }
        bloom += (bloomTarget - bloom) * Math.min(1, dt / 0.035f);
        bloomTarget *= (float) Math.exp(-dt / 0.2f);
        energy.step(dt);
        // The colours flow faster with the music and slow down as it fades out.
        float speed = (0.035f + 0.07f * Ui.clamp(energy.value) + 0.16f * bloom) * Ui.clamp(amount.value);
        flow += (speed - flow) * Math.min(1, dt / 0.6f);
        phase = (phase + flow * dt) % 1f;
        invalidate();
        if (!wanted && amount.value <= 0.002f && amount.settled()) {
            amount.snap(0);
            bloom = bloomTarget = 0;
            listener.onDark();
            return;
        }
        posted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void mixColors() {
        float t = Ui.clamp(blend.value);
        for (int i = 0; i < 3; i++) {
            accent[i] = accentFrom[i] + (accentTo[i] - accentFrom[i]) * t;
            deep[i] = deepFrom[i] + (deepTo[i] - deepFrom[i]) * t;
        }
        if (blend.value >= 0.999f) {
            System.arraycopy(accentTo, 0, accent, 0, 3);
            System.arraycopy(deepTo, 0, deep, 0, 3);
        }
    }

    @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
        w = width;
        h = height;
        k = w / 1920f;
        layoutEdges();
    }

    /** The plainer light (Android 8–12): each edge's gradient in the accent colour, fading inward. */
    private void layoutEdges() {
        if (w == 0 || shader != null) return;
        float band = STYLES[style][1] * 2.2f * k;
        int c = rgb(accentTo), clear = c & 0x00FFFFFF;
        edges[0].setShader(new LinearGradient(0, 0, 0, band, c, clear, Shader.TileMode.CLAMP));
        edges[1].setShader(new LinearGradient(0, h, 0, h - band, c, clear, Shader.TileMode.CLAMP));
        edges[2].setShader(new LinearGradient(0, 0, band, 0, c, clear, Shader.TileMode.CLAMP));
        edges[3].setShader(new LinearGradient(w, 0, w - band, 0, c, clear, Shader.TileMode.CLAMP));
    }

    private static int rgb(float[] c) {
        return 0xFF000000 | Math.round(c[0] * 255) << 16 | Math.round(c[1] * 255) << 8 | Math.round(c[2] * 255);
    }

    @Override protected void onDraw(Canvas c) {
        float a = Ui.clamp(amount.value);
        if (a <= 0.002f || w == 0) return;
        float[] st = STYLES[style];
        if (shader != null && Build.VERSION.SDK_INT >= 33) {
            RuntimeShader s = (RuntimeShader) shader;
            s.setFloatUniform("res", w, h);
            s.setFloatUniform("edge", st[0] * k);
            s.setFloatUniform("glow", st[1] * k);
            float band = Math.min(h / 2, (st[1] * 5 + st[0] * 6) * k * (1 + 0.22f * Ui.clamp(bloom)));
            s.setFloatUniform("band", band);
            s.setFloatUniform("phase", phase);
            s.setFloatUniform("t", clock);
            s.setFloatUniform("amount", a * st[2]);
            s.setFloatUniform("energy", Ui.clamp(energy.value));
            s.setFloatUniform("bloom", Ui.clamp(bloom));
            s.setFloatUniform("accent", accent[0], accent[1], accent[2]);
            s.setFloatUniform("deep", deep[0], deep[1], deep[2]);
            paint.setShader(s);
            // Only the bands along the edges: the middle of the picture is never touched.
            c.drawRect(0, 0, w, band, paint);
            c.drawRect(0, h - band, w, h, paint);
            c.drawRect(0, band, band, h - band, paint);
            c.drawRect(w - band, band, w, h - band, paint);
            return;
        }
        float strength = a * st[2] * (0.5f + 0.3f * Ui.clamp(energy.value) + 0.4f * bloom);
        int alpha = Math.round(255 * Math.min(1, strength));
        float band = st[1] * 2.2f * k;
        for (Paint p : edges) p.setAlpha(alpha);
        c.drawRect(0, 0, w, band, edges[0]);
        c.drawRect(0, h - band, w, h, edges[1]);
        c.drawRect(0, 0, band, h, edges[2]);
        c.drawRect(w - band, 0, w, h, edges[3]);
    }
}
