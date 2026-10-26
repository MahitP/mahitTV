package io.freemytv;

import android.graphics.Path;

/**
 * Apple's continuous ("squircle") rounded rectangle: each corner three cubic Béziers whose curvature runs on into the
 * straight edges (the UIBezierPath continuous-corner constants, in units of the corner radius r). A circular corner
 * kinks where the arc meets the edge; this one doesn't. The shaders use its closest superellipse (exponent 2.62 over
 * 1.245 r; DESIGN-TVOS.md §0b #1).
 */
final class Corners {
    private Corners() {}

    /** How far along each edge a corner reaches, in r. */
    static final float SPAN = 1.52866483f;
    /** The corner as points measured from the box corner: (along the first edge, in along the second), in r. */
    private static final float[] PTS = {
        1.08849299f, 0f, 0.86840701f, 0f, 0.63149399f, 0.07491100f,
        0.37282401f, 0.16905899f, 0.16906001f, 0.37282401f, 0.07491100f, 0.63149399f,
        0f, 0.86840701f, 0f, 1.08849299f, 0f, SPAN,
    };
    // Each corner, clockwise from the top right: its origin (as a multiple of w, h) and where "along" and "in" point.
    private static final float[][] FRAMES = {
        {1, 0, -1, 0, 0, 1},    // top right: along the top edge to the left, in = down
        {1, 1, 0, -1, -1, 0},   // bottom right: along the right edge upward, in = left
        {0, 1, 1, 0, 0, -1},    // bottom left: along the bottom edge to the right, in = up
        {0, 0, 0, 1, 1, 0},     // top left: along the left edge downward, in = right
    };

    /** Rebuilds `p` as a `w` × `h` continuous rounded rect at the origin, corner `r` (shrunk so the corners fit). */
    static void continuous(Path p, float w, float h, float r) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2 / SPAN));
        p.rewind();
        for (int c = 0; c < 4; c++) {
            float[] f = FRAMES[c];
            float ox = f[0] * w, oy = f[1] * h;
            // (along, in) → x = ox + along·(f2) + in·(f4), y = oy + along·(f3) + in·(f5)
            float sx = ox + SPAN * r * f[2], sy = oy + SPAN * r * f[3];
            if (c == 0) p.moveTo(sx, sy); else p.lineTo(sx, sy);
            for (int i = 0; i < PTS.length; i += 6) {
                p.cubicTo(x(f, ox, PTS[i], PTS[i + 1], r), y(f, oy, PTS[i], PTS[i + 1], r),
                        x(f, ox, PTS[i + 2], PTS[i + 3], r), y(f, oy, PTS[i + 2], PTS[i + 3], r),
                        x(f, ox, PTS[i + 4], PTS[i + 5], r), y(f, oy, PTS[i + 4], PTS[i + 5], r));
            }
        }
        p.close();
    }

    private static float x(float[] f, float ox, float along, float in, float r) {
        return ox + along * r * f[2] + in * r * f[4];
    }

    private static float y(float[] f, float oy, float along, float in, float r) {
        return oy + along * r * f[3] + in * r * f[5];
    }
}
