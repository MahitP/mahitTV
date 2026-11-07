package io.freemytv;

import android.graphics.RuntimeShader;

/**
 * Aurora and glass (Liquid Glass) drawn on the GPU with AGSL. The aurora is procedural, so the glass doesn't need to
 * blur what's behind it: it uses the same aurora without detail (the frosted glass, computed in Frost) and shifts the
 * sample near the edge (the refraction). The glass never sits on top of an app or a video.
 */
final class Shaders {
    private Shaders() {}

    private static final String COMMON =
        "uniform float2 res;\n" +            // full screen size (px): the same math in every layer
        "uniform float t;\n" +               // seconds
        "uniform float speed;\n" +
        "uniform float dim;\n" +
        "uniform half3 c0;\n" +              // palette: background, middle and highlight
        "uniform half3 c1;\n" +
        "uniform half3 c2;\n" +
        "float hash(float2 p) {\n" +
        "  p = fract(p * float2(123.34, 456.21));\n" +
        "  p += dot(p, p + 45.32);\n" +
        "  return fract(p.x * p.y);\n" +
        "}\n" +
        "float noise(float2 p) {\n" +
        "  float2 i = floor(p);\n" +
        "  float2 f = fract(p);\n" +
        "  float2 u = f * f * (3.0 - 2.0 * f);\n" +
        "  float a = hash(i);\n" +
        "  float b = hash(i + float2(1.0, 0.0));\n" +
        "  float c = hash(i + float2(0.0, 1.0));\n" +
        "  float d = hash(i + float2(1.0, 1.0));\n" +
        "  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);\n" +
        "}\n" +
        "float fbm2(float2 p) {\n" +
        "  return 0.6 * noise(p) + 0.3 * noise(p * 2.03 + 17.0);\n" +
        "}\n" +
        // With detail: 4 octaves. Without detail (frosted glass): only the first 2 + the mean of the rest, the same
        // image run through a low-pass filter.
        "float fbm(float2 p, float detail) {\n" +
        "  float v = 0.5 * noise(p);\n" +
        "  p = p * 2.03 + 17.0;\n" +
        "  v += 0.25 * noise(p);\n" +
        "  if (detail < 0.5) { return v + 0.09375; }\n" +
        "  p = p * 2.03 + 17.0;\n" +
        "  v += 0.125 * noise(p);\n" +
        "  p = p * 2.03 + 17.0;\n" +
        "  return v + 0.0625 * noise(p);\n" +
        "}\n" +
        // Aurora: a slowly flowing domain warp. The light bands sit in the middle of the sky; the top (clock and
        // prices) and the bottom (dock) are darker so white text stands out.
        "half3 aurora(float2 uv, float detail) {\n" +
        "  float2 p = float2(uv.x * (res.x / res.y), uv.y) * 1.7;\n" +
        "  float tt = t * 0.035 * speed;\n" +
        "  float2 q = float2(fbm2(p + float2(0.0, tt)), fbm2(p + float2(5.2, 1.3) - tt));\n" +
        "  float n = fbm(p + 2.0 * q + float2(1.7 + tt, 9.2), detail);\n" +
        "  float band = smoothstep(0.02, 0.38, uv.y) * (1.0 - smoothstep(0.5, 0.92, uv.y));\n" +
        "  float curtain = smoothstep(0.35, 0.85, n) * (0.35 + 0.65 * band);\n" +
        "  half3 col = mix(c0, c1, smoothstep(0.15, 0.85, n));\n" +
        "  col = mix(col, c2, curtain * 0.85);\n" +
        "  float vig = 1.0 - 0.38 * smoothstep(0.35, 1.0, uv.y) - 0.2 * smoothstep(0.45, 0.95, abs(uv.x - 0.5) * 1.6);\n" +
        "  float scrim = 1.0 - 0.22 * (1.0 - smoothstep(0.0, 0.42, uv.y));\n" +
        "  return col * vig * scrim * (1.0 - dim);\n" +
        "}\n";

    /**
     * Background: drawn into a small layer (¼ scale) and scaled up; gradients lose nothing. Noise of ±½ a color level
     * breaks up the banding a dark gradient would show on a big screen.
     */
    static final String AURORA = COMMON +
        "uniform float2 layer;\n" +
        "half4 main(float2 xy) {\n" +
        "  half3 col = aurora(xy / layer, 1.0) + (hash(xy) - 0.5) / 255.0;\n" +
        "  return half4(col, 1.0);\n" +
        "}\n";

    /**
     * Apple Frosted Horizon: three very broad, fixed color fields over a vertical sky. Geometry is normalized so the
     * same composition is used at every resolution. The tiny deterministic dither removes gradient banding without
     * introducing a clock or any per-frame work.
     */
    static final String HORIZON =
        "uniform float2 res;\n" +
        "uniform float2 layer;\n" +
        "uniform half3 c0;\n" +
        "uniform half3 c1;\n" +
        "uniform half3 c2;\n" +
        "uniform half3 c3;\n" +
        "uniform float dark;\n" +
        "float hash(float2 p) {\n" +
        "  p = fract(p * float2(123.34, 456.21));\n" +
        "  p += dot(p, p + 45.32);\n" +
        "  return fract(p.x * p.y);\n" +
        "}\n" +
        "float field(float2 uv, float2 center, float radius, float fade) {\n" +
        "  return 1.0 - clamp(length((uv - center) / radius) / fade, 0.0, 1.0);\n" +
        "}\n" +
        "half4 main(float2 xy) {\n" +
        "  float2 uv = (xy * (res / layer)) / res;\n" +
        // The mid-sky stop: c2 by day, c1 at night (as Horizon.java and web/home.css .hz-night).
        "  half3 col = mix(c0, dark > 0.5 ? c1 : c2, half(clamp(uv.y / 0.56, 0.0, 1.0)));\n" +
        "  col = mix(col, c3, half(clamp((uv.y - 0.56) / 0.44, 0.0, 1.0)));\n" +
        "  col = mix(col, c3, half(field(uv, float2(1.06, 1.04), 1.04, 0.72)));\n" +
        "  col = mix(col, c2, half(field(uv, float2(0.53, 0.48), 0.78, 0.74)));\n" +
        "  col = mix(col, c1, half(field(uv, float2(-0.08, 0.69), 0.83, 0.72)));\n" +
        // CSS's default farthest-corner ellipse reaches 1.0 at a normalized viewport corner: distance × sqrt(2).
        "  float edge = clamp((length((uv - 0.5) * 1.41421356) - 0.45) / 0.55, 0.0, 1.0);\n" +
        "  col *= half(1.0 - mix(0.12, 0.18, dark) * edge);\n" +
        "  col += half3((hash(xy) - 0.5) / 255.0);\n" +
        "  return half4(col, 1.0);\n" +
        "}\n";

    /**
     * Glass in a rounded-corner shape. The glass background comes from `frost`: the aurora without detail in a 48×27
     * image computed on the CPU (Frost), or the blurred recommendation image (Backdrop), scaled up with linear
     * filtering. That is the frosted glass, at one texture read per pixel (a TV GPU such as the PowerVR BXE-4-32 can't
     * afford to recompute the aurora at every glass pixel).
     * tint: color and strength of the smoky tint; glow: accent color rising from the bottom; lift: extra brightness
     * (focus); body: 1 = full glass, 0 = only the rim highlight (on top of an app icon). Returns premultiplied color
     * (Skia's convention).
     */
    static final String GLASS =
        "uniform shader frost;\n" +
        "uniform shader frostB;\n" +         // the next blurred image during a fade (day ⇄ night, a new photo)
        "uniform float frostMix;\n" +        // how much of frostB shows (0: only frost)
        "uniform float2 frostScale;\n" +     // frosted image size / screen size
        "uniform float2 offset;\n" +         // layer offset on screen (the layer moves without being redrawn)
        "uniform float2 center;\n" +
        "uniform float2 halfSize;\n" +
        "uniform float2 invHalf;\n" +        // 1 / halfSize (per-shape math done on the CPU, not per pixel)
        "uniform float radius;\n" +
        "uniform float top;\n" +
        "uniform float4 depth;\n" +          // 1/refraction band, refraction shift (px), 1/highlight band, 1/height
        "uniform half4 tint;\n" +
        "uniform half4 glow;\n" +
        "uniform float lift;\n" +
        "uniform float body;\n" +
        "float hash(float2 p) {\n" +
        "  p = fract(p * float2(123.34, 456.21));\n" +
        "  p += dot(p, p + 45.32);\n" +
        "  return fract(p.x * p.y);\n" +
        "}\n" +
        "half4 main(float2 xy) {\n" +
        "  float2 d = xy - center;\n" +
        "  float ext = min(radius * 1.245, min(halfSize.x, halfSize.y));\n" +
        "  float ne = radius * 1.245 >= min(halfSize.x, halfSize.y) - 0.5 ? 2.0 : 2.62;\n" +
        "  float2 q = abs(d) - (halfSize - ext);\n" +
        "  float2 m = max(q, 0.0);\n" +
        "  float sd = ((m.x > 0.0 && m.y > 0.0) ? pow(pow(m.x, ne) + pow(m.y, ne), 1.0 / ne) : max(m.x, m.y)) + min(max(q.x, q.y), 0.0) - ext;\n" +
        "  float aa = clamp(0.5 - sd, 0.0, 1.0);\n" +
        "  if (aa <= 0.0) { return half4(0.0); }\n" +
        "  float2 dir = d * invHalf;\n" +
        "  float ty = xy.y - top;\n" +
        "  float rim = 1.0 - clamp(-sd * 0.4545, 0.0, 1.0);\n" +
        "  float light = clamp(-dir.y * 0.75 - dir.x * 0.25 + 0.3, 0.0, 1.0);\n" +
        "  float sheen = (1.0 - clamp(ty * depth.z, 0.0, 1.0)) * 0.06;\n" +
        "  float hl = rim * (0.08 + 0.5 * light) + sheen * body;\n" +
        "  half3 col = half3(0.0);\n" +
        "  if (body > 0.0) {\n" +
        "    float edge = 1.0 - clamp(-sd * depth.x, 0.0, 1.0);\n" +
        "    float2 sxy = xy - dir * (edge * edge * depth.y);\n" +
        "    float2 fp = (sxy + offset) * frostScale;\n" +
        "    half3 bg = frost.eval(fp).rgb;\n" +
        "    if (frostMix > 0.0) { bg = mix(bg, frostB.eval(fp).rgb, half(frostMix)); }\n" +
        "    bg = bg * 1.12 + half3(0.02);\n" +
        "    col = mix(bg, tint.rgb, tint.a);\n" +
        "    col = mix(col, glow.rgb, glow.a * smoothstep(0.1, 1.0, ty * depth.w));\n" +
        "    col += half3(lift);\n" +
        "    col *= 1.0 - 0.2 * (1.0 - clamp(-sd * 0.0625, 0.0, 1.0)) * clamp(dir.y, 0.0, 1.0);\n" +
        "    col += (hash(xy) - 0.5) * 0.0039;\n" +
        "  }\n" +
        "  float a = body + hl * (1.0 - body);\n" +
        "  return half4((col * body + half3(hl)) * aa, a * aa);\n" +
        "}\n";

    /**
     * Liquid Glass (macOS 27 style) for the shelf and the cards: the background shows through the glass, lightly
     * blurred (`imageA`: the photo at 480×270, or the frosted aurora), with a slight color boost; near the edge the
     * glass is "thick" and bends the image outward (a lens), with slight color dispersion (DESIGN-TVOS.md §2); the rim has a thin
     * highlight, strong where it faces the light (top left), and an echo on the opposite side, plus a soft inner halo.
     * `imageB`/`mixB`: the next photo during a transition, blended here (no texture rebuild every frame). band = width
     * of the thick rim (px), refraction = how much it bends (px), specular = highlight strength. Premultiplied color.
     */
    static final String LIQUID =
        "uniform shader imageA;\n" +
        "uniform shader imageB;\n" +
        "uniform float mixB;\n" +
        "uniform float2 imageScale;\n" +
        "uniform float2 offset;\n" +
        "uniform float2 center;\n" +
        "uniform float2 halfSize;\n" +
        "uniform float radius;\n" +
        "uniform half4 tint;\n" +
        "uniform float lift;\n" +
        "uniform float band;\n" +
        "uniform float refraction;\n" +
        "uniform float specular;\n" +
        "uniform float2 light;\n" +          // the key light's direction (top left; it turns as the tab bar's lens moves)
        "float hash(float2 p) {\n" +
        "  p = fract(p * float2(123.34, 456.21));\n" +
        "  p += dot(p, p + 45.32);\n" +
        "  return fract(p.x * p.y);\n" +
        "}\n" +
        "half3 bgAt(float2 p) {\n" +
        "  half3 a = imageA.eval(p * imageScale).rgb;\n" +
        "  if (mixB <= 0.0) { return a; }\n" +
        "  return mix(a, imageB.eval(p * imageScale).rgb, half(mixB));\n" +
        "}\n" +
        "half4 main(float2 xy) {\n" +
        "  float2 d = xy - center;\n" +
        // Apple's continuous corner (Corners.java): its closest superellipse, exponent 2.62 spanning 1.245 r (within
        // 0.3 % of r); a capsule keeps its true semicircle ends.
        "  float ext = min(radius * 1.245, min(halfSize.x, halfSize.y));\n" +
        "  float ne = radius * 1.245 >= min(halfSize.x, halfSize.y) - 0.5 ? 2.0 : 2.62;\n" +
        "  float2 q = abs(d) - (halfSize - ext);\n" +
        "  float2 m = max(q, 0.0);\n" +
        "  float corner = (m.x > 0.0 && m.y > 0.0) ? pow(pow(m.x, ne) + pow(m.y, ne), 1.0 / ne) : max(m.x, m.y);\n" +
        "  float sd = corner + min(max(q.x, q.y), 0.0) - ext;\n" +
        "  float aa = clamp(0.5 - sd, 0.0, 1.0);\n" +
        "  if (aa <= 0.0) { return half4(0.0); }\n" +
        "  float2 sg = float2(d.x < 0.0 ? -1.0 : 1.0, d.y < 0.0 ? -1.0 : 1.0);\n" +
        "  float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) * sg : (q.x > q.y ? float2(sg.x, 0.0) : float2(0.0, sg.y));\n" +
        "  float inner = -sd;\n" +
        // Lensing: the rim band bends the image outward as a dome's edge does (a circle's profile: steep at the rim,
        // flat inside, as Kyant's AndroidLiquidGlass models it), with a little color dispersion.
        "  float x = 1.0 - clamp(inner / band, 0.0, 1.0);\n" +
        "  float bend = 1.0 - sqrt(max(1.0 - x * x, 0.0));\n" +
        "  float2 p = xy + offset - n * (bend * refraction);\n" +
        "  half3 bg;\n" +
        "  if (bend > 0.01) {\n" +
        "    float2 ca = n * (bend * refraction * 0.28);\n" +
        "    bg = half3(bgAt(p + ca).r, bgAt(p).g, bgAt(p - ca).b);\n" +
        "  } else {\n" +
        "    bg = bgAt(p);\n" +
        "  }\n" +
        // Vibrancy: what's behind keeps its color, a little stronger.
        "  half l = dot(bg, half3(0.2126, 0.7152, 0.0722));\n" +
        "  bg = mix(half3(l), bg, 1.6) * 1.02;\n" +
        "  half3 col = mix(bg, tint.rgb, tint.a) + half3(lift);\n" +
        "  float ny = d.y / halfSize.y;\n" +
        "  col *= half(1.0 - 0.07 * smoothstep(0.1, 1.0, ny));\n" +
        "  col += half3(0.06 * (1.0 - smoothstep(-1.0, -0.2, ny)));\n" +
        // Key light from the top left, as iOS 26 draws it (measured by Glisse): the rim's line is brightest at the very
        // edge and gone 1.2 px in; it lights the whole top and left side, fading over the two corners between, and its
        // reflection the right side and bottom; a soft inner halo.
        "  float2 L = light;\n" +
        "  float facing = smoothstep(-0.2, 0.5, dot(n, L));\n" +
        "  float back = smoothstep(-0.2, 0.5, -dot(n, L));\n" +
        "  float rim = 1.0 - smoothstep(0.0, 1.22, inner);\n" +
        "  float halo = 1.0 - smoothstep(0.0, 14.0, inner);\n" +
        "  float spec = rim * (0.18 + 0.82 * facing + 0.35 * back) + halo * (0.14 * facing + 0.06 * back);\n" +
        // The glass's thickness: just inside the rim it darkens a touch, away from the light.
        "  col *= half(1.0 - 0.14 * (1.0 - facing) * (1.0 - smoothstep(1.5, 12.0, inner)));\n" +
        "  col += half3(spec * specular);\n" +
        "  col += half3((hash(xy) - 0.5) * 0.0039);\n" +
        "  return half4(col * half(aa), half(aa));\n" +
        "}\n";

    /**
     * Glass clock for the screensaver (like the iOS 26 lock screen's): the digits are clear glass over the photo
     * moving behind them. `slope` (opaque): R and G = the surface slope (the height gradient, pointing into the
     * stroke; 0.5 = flat), B = the height (0.5 at the edge, 1 in the middle of the strokes); `cover`: the sharp
     * rendering of the digits (the soft edge comes from it). Near the edge the glass bends the photo (a lens), with
     * slight color dispersion; the core brightens a little; a thin highlight on the edge facing the light (top left)
     * and an echo on the opposite side. Photos: `imageA`/`imageB` with each one's on-screen position (x, y) and scale
     * (the same slow zoom as the background), and `mixB` during a transition. Premultiplied color.
     */
    static final String GLASS_TEXT =
        "uniform shader imageA;\n" +
        "uniform shader imageB;\n" +
        "uniform shader slope;\n" +
        "uniform shader cover;\n" +
        "uniform float3 placeA;\n" +
        "uniform float3 placeB;\n" +
        "uniform float mixB;\n" +
        "uniform float2 origin;\n" +        // the clock's top-left corner on screen
        "uniform float2 ink;\n" +           // top and height of the digits (in the bitmaps)
        "uniform float refraction;\n" +     // how many px the edge bends the photo
        "uniform float specular;\n" +
        "uniform float alpha;\n" +
        "half3 photo(float2 p) {\n" +
        "  half3 a = imageA.eval((p - placeA.xy) / placeA.z).rgb;\n" +
        "  if (mixB <= 0.0) { return a; }\n" +
        "  return mix(a, imageB.eval((p - placeB.xy) / placeB.z).rgb, half(mixB));\n" +
        "}\n" +
        "half4 main(float2 xy) {\n" +
        "  float2 local = xy - origin;\n" +
        "  half a = cover.eval(local).a;\n" +
        "  if (a <= 0.0) { return half4(0.0); }\n" +
        "  half3 s = slope.eval(local).rgb;\n" +
        "  float2 n = (float2(s.r, s.g) - 0.5) * 2.0;\n" +
        "  float bend = length(n);\n" +
        "  float height = float(s.b);\n" +
        "  float2 p = xy + n * refraction;\n" +
        "  half3 bg;\n" +
        "  if (bend > 0.02) {\n" +
        "    float2 ca = n * (refraction * 0.2);\n" +
        "    bg = half3(photo(p + ca).r, photo(p).g, photo(p - ca).b);\n" +
        "  } else {\n" +
        "    bg = photo(p);\n" +
        "  }\n" +
        "  half l = dot(bg, half3(0.2126, 0.7152, 0.0722));\n" +
        "  bg = mix(half3(l), bg, 1.15);\n" +
        "  float ty = clamp((local.y - ink.x) / ink.y, 0.0, 1.0);\n" +
        // Clear glass: most of the photo, plus a light white veil that is stronger at the top (the face's sheen).
        "  half3 col = bg * 0.86 + half3(0.07 + 0.11 * (1.0 - ty));\n" +
        "  float2 dir = n / max(bend, 0.001);\n" +
        "  float2 L = float2(-0.447, -0.894);\n" +
        "  float facing = clamp(-dot(dir, L), 0.0, 1.0);\n" +
        "  float back = clamp(dot(dir, L), 0.0, 1.0);\n" +
        "  float edge = smoothstep(0.05, 0.35, bend);\n" +
        "  float rim = (1.0 - smoothstep(0.5, 0.6, height)) * edge;\n" +
        "  float glow = (1.0 - smoothstep(0.5, 0.86, height)) * edge;\n" +
        "  col += half3((rim * (0.28 + 0.9 * facing + 0.45 * back) + glow * (0.05 + 0.1 * facing)) * specular);\n" +
        "  col *= half(1.0 - 0.1 * back * bend * (1.0 - rim));\n" +
        "  half k = a * half(alpha);\n" +
        "  return half4(col * k, k);\n" +
        "}\n";

    /**
     * The startup's Liquid Glass wordmark ("Anamorphic Horizon & Optical Liquid Glass Ignition", Act II–III): GLASS_TEXT's
     * refractive letters over the Frosted Horizon (`sky`, in screen space), seven glyphs that each condense on their own
     * spring. `glyph[i]` = (cell left, cell right in the bitmaps' px, the glyph's pivot x on screen, its scale) and
     * `glyphB[i]` = (its rise in px, its alpha): a pixel is the glyph whose cell it lands in once that glyph's transform
     * is undone, composited front to back. Layer 1, the refractive body: the sky bent `refraction` px along the surface
     * normal, split R/B by `fringe`, frosted with `body` (white 0.24 by day, 0.16 at night). Layer 2: the inner top
     * bevel (`bevel`, on the edges facing the light) and the inner optical shadow (`depth`: the glass's thickness
     * darkening it toward its edges, most on the far side). Layer 3: the 1.5 px specular rim, which the −28° caustic
     * beam (`sweepDir`, centred on `sweepAt` on screen, `sweepWidth` px) brightens and splits into three tints
     * (`prismA` leading, `prismB` centre, `prismC` trailing) `prismPx` apart. `zoom` scales the whole word around `pivot`
     * (the exhale) without a canvas transform, so the sky stays put. Premultiplied color.
     */
    static final String GLASS_WORD =
        "uniform shader sky;\n" +
        "uniform shader slope;\n" +
        "uniform shader cover;\n" +
        "uniform float2 origin;\n" +
        "uniform float2 pivot;\n" +
        "uniform float4 glyph[7];\n" +
        "uniform float4 glyphB[7];\n" +
        "uniform float zoom;\n" +
        "uniform float refraction;\n" +
        "uniform float fringe;\n" +
        "uniform half4 body;\n" +
        "uniform float bevel;\n" +
        "uniform float depth;\n" +
        "uniform float2 sweepDir;\n" +
        "uniform float2 sweepAt;\n" +
        "uniform float sweepWidth;\n" +
        "uniform half3 prismA;\n" +
        "uniform half3 prismB;\n" +
        "uniform half3 prismC;\n" +
        "uniform float prismPx;\n" +
        "uniform float alpha;\n" +
        "float rimAt(float2 local) {\n" +
        "  half3 s = slope.eval(local).rgb;\n" +
        "  float bend = length((float2(s.r, s.g) - 0.5) * 2.0);\n" +
        "  return (1.0 - smoothstep(0.5, 0.6, float(s.b))) * smoothstep(0.05, 0.35, bend);\n" +
        "}\n" +
        "half4 glass(float2 xy, float2 local, half a) {\n" +
        "  half3 s = slope.eval(local).rgb;\n" +
        "  float2 n = (float2(s.r, s.g) - 0.5) * 2.0;\n" +
        "  float bend = length(n);\n" +
        "  float height = float(s.b);\n" +
        "  float2 p = xy + n * refraction;\n" +
        "  half3 bg = sky.eval(p).rgb;\n" +
        "  if (bend > 0.02) {\n" +
        "    bg = half3(sky.eval(p + n * fringe).r, bg.g, sky.eval(p - n * fringe).b);\n" +
        "  }\n" +
        "  half3 col = mix(bg, body.rgb, body.a);\n" +
        "  float2 dir = n / max(bend, 0.001);\n" +
        "  float2 L = float2(-0.447, -0.894);\n" +
        "  float facing = clamp(-dot(dir, L), 0.0, 1.0);\n" +
        "  float back = clamp(dot(dir, L), 0.0, 1.0);\n" +
        "  float rim = (1.0 - smoothstep(0.5, 0.6, height)) * smoothstep(0.05, 0.35, bend);\n" +
        "  float lip = (1.0 - smoothstep(0.5, 0.86, height)) * smoothstep(0.05, 0.35, bend);\n" +
        "  col += half3(rim * (0.22 + bevel * facing + 0.30 * back) + lip * 0.08 * facing);\n" +
        "  col *= half(1.0 - 0.14 * back * bend * (1.0 - rim));\n" +
        "  col *= half(1.0 - depth * (1.0 - smoothstep(0.5, 0.9, height)) * (0.6 + 0.4 * back));\n" +
        "  float along = dot(xy - sweepAt, sweepDir);\n" +
        "  float band = exp(-(along * along) / (sweepWidth * sweepWidth * 0.18));\n" +
        "  if (band > 0.003) {\n" +
        "    float2 across = sweepDir * prismPx;\n" +
        "    half3 prism = prismA * half(rimAt(local - across)) + prismB * half(rim) + prismC * half(rimAt(local + across));\n" +
        "    col += half3(0.42 * band) + prism * half(0.95 * band);\n" +
        "  }\n" +
        "  return half4(clamp(col, half3(0.0), half3(1.0)) * a, a);\n" +
        "}\n" +
        "half4 main(float2 xy) {\n" +
        "  float2 w = (xy - pivot) / zoom + pivot;\n" +
        "  half4 acc = half4(0.0);\n" +
        "  for (int i = 0; i < 7; i++) {\n" +
        "    float4 g = glyph[i];\n" +
        "    float4 b = glyphB[i];\n" +
        "    if (b.y > 0.002) {\n" +
        "      float2 at = float2(g.z, pivot.y);\n" +
        "      float2 local = (w - float2(0.0, b.x) - at) / g.w + at - origin;\n" +
        "      if (local.x >= g.x && local.x < g.y) {\n" +
        "        half a = cover.eval(local).a * half(b.y);\n" +
        "        if (a > 0.0) {\n" +
        "          half4 c = glass(xy, local, a);\n" +
        "          acc = c + acc * (1.0 - c.a);\n" +
        "        }\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "  return acc * half(alpha);\n" +
        "}\n";

    /**
     * The startup's living Frosted Horizon (Act I–III), opaque: the sky (`sky`, screen space) breathing under two slow
     * travelling atmospheric waves (`drift` px of displacement, `freq` per px, `time` s, `driftLight` of luminance), a
     * gossamer glass ring of `radius` and `width` around `center` bending it `amp` px along the ring's normal with
     * `ringLight` on the ring, and the −28° volumetric beam (`beamDir`, centred on `beamAt`, `beamWidth` px, `beamLight`).
     * With every strength at 0 it's the Horizon pixel for pixel (the lock's, at the handover).
     */
    static final String HORIZON_RIPPLE =
        "uniform shader sky;\n" +
        "uniform float time;\n" +
        "uniform float drift;\n" +
        "uniform float freq;\n" +
        "uniform float driftLight;\n" +
        "uniform float2 center;\n" +
        "uniform float radius;\n" +
        "uniform float width;\n" +
        "uniform float amp;\n" +
        "uniform float ringLight;\n" +
        "uniform float2 beamDir;\n" +
        "uniform float2 beamAt;\n" +
        "uniform float beamWidth;\n" +
        "uniform float beamLight;\n" +
        "half4 main(float2 xy) {\n" +
        "  float2 q = xy * freq;\n" +
        "  float w1 = sin(q.x + 0.5 * q.y - 1.2 * time);\n" +
        "  float w2 = sin(-0.62 * q.x + 1.1 * q.y - 0.8 * time + 1.7);\n" +
        "  float2 p = xy + float2(w1, w2) * drift;\n" +
        "  float2 d = xy - center;\n" +
        "  float dist = length(d);\n" +
        "  float x = (dist - radius) / width;\n" +
        "  float ring = exp(-x * x * 4.0);\n" +
        "  p -= (d / max(dist, 1.0)) * (x * ring * amp);\n" +
        "  half3 c = sky.eval(p).rgb;\n" +
        "  c *= half(1.0 + driftLight * w1 * w2);\n" +
        "  float along = dot(xy - beamAt, beamDir);\n" +
        "  c += half3(ringLight * ring + beamLight * exp(-(along * along) / (beamWidth * beamWidth)));\n" +
        "  return half4(c, 1.0);\n" +
        "}\n";

    /** Aurora colors: three tones (background, middle, highlight), from 0 to 1. */
    static final class Palette {
        final float[] c = new float[9];

        Palette set(int a, int b, int d) {
            put(0, a); put(3, b); put(6, d);
            return this;
        }

        private void put(int i, int rgb) {
            c[i] = ((rgb >> 16) & 255) / 255f;
            c[i + 1] = ((rgb >> 8) & 255) / 255f;
            c[i + 2] = (rgb & 255) / 255f;
        }

        int color(int i) {
            return 0xFF000000 | (Math.round(c[i * 3] * 255) << 16) | (Math.round(c[i * 3 + 1] * 255) << 8) | Math.round(c[i * 3 + 2] * 255);
        }

        /** Luminance of the brightest tone (0 to 1): bright palettes (day, dawn) get darkened a little. */
        float brightest() {
            float max = 0;
            for (int i = 0; i < 9; i += 3) max = Math.max(max, 0.2126f * c[i] + 0.7152f * c[i + 1] + 0.0722f * c[i + 2]);
            return max;
        }

        void apply(RuntimeShader s) {
            s.setFloatUniform("c0", c[0], c[1], c[2]);
            s.setFloatUniform("c1", c[3], c[4], c[5]);
            s.setFloatUniform("c2", c[6], c[7], c[8]);
        }
    }

    // Sky phases through the day.
    private static final int[] NIGHT = {0x070B1E, 0x16225A, 0x4B2F8F};
    private static final int[] DAWN = {0x24183F, 0xE89A55, 0xF6D59A};
    private static final int[] DAY = {0x1E57C8, 0x4FB9EC, 0x9EE2EE};
    private static final int[] SUNSET = {0x2E1650, 0xE2546C, 0xF39A4F};
    private static final int[] DUSK = {0x10173A, 0x36348A, 0x8A4BB0};

    /**
     * Palette for the sun's current position at your location. Uses the sunrise and sunset times from the last weather
     * fetch (they shift ~1 min a day, so yesterday's times are fine today); without data, 5:30 and 17:40.
     */
    static void sky(Palette out, long now, long sunrise, long sunset) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long midnight = cal.getTimeInMillis();
        long rise = midnight + timeOfDay(sunrise, (5 * 60 + 30) * 60_000L);
        long set = midnight + timeOfDay(sunset, (17 * 60 + 40) * 60_000L);
        if (sunrise != 0 && sunset != 0) {   // instants, so a TV in the wrong time zone still follows the real sun
            rise = TimeCheck.sunriseOfDay(now, sunrise, sunset);
            set = TimeCheck.sunsetAfter(rise, sunrise, sunset);
        }
        long m = 60_000L;
        long[] at = {rise - 60 * m, rise - 15 * m, rise + 45 * m, set - 45 * m, set, set + 45 * m, set + 105 * m};
        int[][] key = {NIGHT, DAWN, DAY, DAY, SUNSET, DUSK, NIGHT};
        if (now <= at[0] || now >= at[at.length - 1]) {
            out.set(NIGHT[0], NIGHT[1], NIGHT[2]);
            return;
        }
        for (int i = 0; i < at.length - 1; i++) {
            if (now >= at[i] && now < at[i + 1]) {
                float f = (float) (now - at[i]) / (at[i + 1] - at[i]);
                out.set(mix(key[i][0], key[i + 1][0], f), mix(key[i][1], key[i + 1][1], f), mix(key[i][2], key[i + 1][2], f));
                return;
            }
        }
    }

    /** Time of day (ms since local midnight) of an instant; `fallback` if there is no data. */
    static long timeOfDay(long at, long fallback) {
        if (at == 0) return fallback;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(at);
        return ((c.get(java.util.Calendar.HOUR_OF_DAY) * 60L + c.get(java.util.Calendar.MINUTE)) * 60 + c.get(java.util.Calendar.SECOND)) * 1000L;
    }

    /** Screensaver: always the night palette, by day and by night. */
    static void dream(Palette out) {
        out.set(NIGHT[0], NIGHT[1], NIGHT[2]);
    }

    /**
     * Settings: dark and nearly neutral at any hour, like the home screen's plain background at night (the day's sky
     * read as a flat bright blue there, above all without AGSL, where it's a plain gradient); a trace of violet moves in
     * the aurora.
     */
    static void settings(Palette out) {
        out.set(0x08090C, 0x141519, 0x2B2838);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return (r << 16) | (g << 8) | bl;
    }
}
