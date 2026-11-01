package io.freemytv;

/**
 * A damped spring, as Apple parameterises them (SwiftUI spring(response:dampingFraction:)): response (s) and damping
 * (1 = no bounce), unit mass, k = (2π/response)², c = 4π·damping/response. Each step applies the exact solution over
 * dt (ANIMATIONS-SPEC.md §1.1), so it's the same at any frame rate. Retargeting only changes `target`: position and
 * velocity carry over, so a spring redirected mid-flight bends instead of restarting.
 */
final class Spring {
    // The presets (ANIMATIONS-SPEC.md §1.2): {response, damping}.
    static final float[] MICRO_PRESS = {0.18f, 0.92f}, SNAPPY_FOCUS = {0.30f, 0.80f}, FAST_FOCUS = {0.16f, 0.95f};
    static final float[] BOUNDARY_BUMP = {0.24f, 0.76f}, TILT = {0.42f, 0.60f}, LIQUID_PILL = {0.36f, 0.84f};
    static final float[] TAB_SWAP = {0.42f, 0.90f}, SHEET_OPEN = {0.44f, 0.88f}, SHEET_CLOSE = {0.34f, 0.96f};
    static final float[] SPATIAL_SCROLL = {0.46f, 0.92f}, TITLE_SETTLE = {0.38f, 0.88f}, CAMERA_ORBIT = {0.68f, 0.94f};
    static final float[] WALL_CUT = {0.45f, 0.92f}, ROOM_FADE = {0.40f, 1.00f}, IGNITE = {0.25f, 0.75f};
    static final float[] CARD_ENTER = SNAPPY_FOCUS, ORB = {0.50f, 0.80f};
    /** Control Center's panels and the Inputs list: a momentum-weighted glide. */
    static final float[] CC_OPEN = {0.42f, 0.85f};

    /** Dead ends: the push given to the focused thing, and the rubber band its offset goes through (§1.3). */
    static final float BUMP_VELOCITY = 1100, BAND = 40, BAND_C = 0.55f;
    /** A longer gap between frames (the app was away) settles springs instead of jumping them through it. */
    private static final float MAX_DT = 1 / 30f, GAP = 0.25f;

    float value, velocity, target;
    private float response, damping, restX = 0.0005f, restV = 0.005f;
    /** The system's animations are off (Settings: animator duration scale 0): every spring jumps to its target. */
    static boolean instant;

    Spring(float response, float damping, float initial) {
        this.response = response;
        this.damping = damping;
        value = target = initial;
    }

    Spring(float[] preset, float initial) {
        this(preset[0], preset[1], initial);
    }

    /** For a spring in pixels: at rest within a quarter pixel. */
    Spring px() {
        restX = 0.25f;
        restV = 2f;
        return this;
    }

    /** For a spring in degrees. */
    Spring deg() {
        restX = 0.02f;
        restV = 0.2f;
        return this;
    }

    /** New response and damping (a sheet presents slower than it dismisses); allocates nothing. */
    void tune(float response, float damping) {
        this.response = response;
        this.damping = damping;
    }

    void tune(float[] preset) {
        tune(preset[0], preset[1]);
    }

    void snap(float v) {
        value = target = v;
        velocity = 0;
    }

    /** Adds velocity (a dead-end bump, an arrival tilt): the spring carries it and comes back to its target. */
    void impulse(float dv) {
        if (!instant) velocity += dv;
    }

    /** Advances `dt` seconds; returns true while still moving. */
    boolean step(float dt) {
        if (instant || dt > GAP || settled()) {
            value = target;
            velocity = 0;
            return false;
        }
        if (dt <= 0) return true;
        if (dt > MAX_DT) dt = MAX_DT;
        double w = 2 * Math.PI / response, z = damping, x0 = value - target, v0 = velocity, x, v;
        if (z < 0.9999) {
            double wd = w * Math.sqrt(1 - z * z), e = Math.exp(-z * w * dt);
            double cos = Math.cos(wd * dt), sin = Math.sin(wd * dt), b = (v0 + z * w * x0) / wd;
            x = e * (x0 * cos + b * sin);
            v = e * (-z * w * (x0 * cos + b * sin) + wd * (b * cos - x0 * sin));
        } else if (z <= 1.0001) {
            double e = Math.exp(-w * dt), b = v0 + w * x0;
            x = e * (x0 + b * dt);
            v = e * (v0 - w * b * dt);
        } else {
            double s = Math.sqrt(z * z - 1), r1 = -w * (z - s), r2 = -w * (z + s);
            double a = (v0 - r2 * x0) / (r1 - r2), b = x0 - a, e1 = Math.exp(r1 * dt), e2 = Math.exp(r2 * dt);
            x = a * e1 + b * e2;
            v = r1 * a * e1 + r2 * b * e2;
        }
        value = (float) (target + x);
        velocity = (float) v;
        return true;
    }

    boolean settled() {
        return Math.abs(value - target) < restX && Math.abs(velocity) < restV;
    }

    /** UIScrollView's rubber band: x pulled against a band of size d gives f < d, and gives less the further it goes. */
    static float rubber(float x, float d, float c) {
        float a = Math.abs(x), f = a * d * c / (d + c * a);
        return x < 0 ? -f : f;
    }

    static float rubber(float x) {
        return rubber(x, BAND, BAND_C);
    }
}
