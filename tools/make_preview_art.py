#!/usr/bin/env python3
"""Writes preview/art/*.jpg: five generated 1920x1080 "cinematic" photos for preview builds (no TMDB key needed).

Procedural, so there's nothing copyrighted in them. Needs numpy and Pillow. Run again to rebuild them:
    python3 tools/make_preview_art.py
"""
import os

import numpy as np
from PIL import Image, ImageFilter

W, H = 1920, 1080
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'preview', 'art')
rng = np.random.default_rng(7)


def noise(octaves=5, base=6, seed=0):
    """Fractal value noise in 0..1, H x W."""
    r = np.random.default_rng(seed)
    acc = np.zeros((H, W), np.float32)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cells = base * 2 ** o
        small = r.random((max(2, cells * H // W), cells)).astype(np.float32)
        img = Image.fromarray((small * 255).astype(np.uint8)).resize((W, H), Image.BICUBIC)
        acc += np.asarray(img, np.float32) / 255 * amp
        total += amp
        amp *= 0.5
    return acc / total


def vgrad(top, bottom, curve=1.0):
    t = (np.linspace(0, 1, H, dtype=np.float32) ** curve)[:, None, None]
    return np.broadcast_to(np.array(top, np.float32) * (1 - t) + np.array(bottom, np.float32) * t, (H, W, 3)).copy()


def glow(img, cx, cy, radius, color, strength=1.0):
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    d = np.sqrt((x - cx) ** 2 + (y - cy) ** 2) / radius
    g = np.exp(-d * d) * strength
    img += g[..., None] * np.array(color, np.float32)
    return img


def ridge(seed, base_y, amp, rough=1.0):
    r = np.random.default_rng(seed)
    xs = np.linspace(0, 1, W)
    y = np.full(W, base_y, np.float32)
    for k in range(1, 7):
        y += np.sin(xs * np.pi * (k * 1.7 + r.random()) + r.random() * 6) * amp / k ** rough
    return y


def fill_below(img, ys, color, fade=None):
    rows = np.arange(H, dtype=np.float32)[:, None]
    mask = rows >= ys[None, :]
    col = np.array(color, np.float32)
    if fade is not None:   # lighter near the ridge line (haze)
        depth = np.clip((rows - ys[None, :]) / fade, 0, 1)[..., None]
        layer = col * (0.75 + 0.25 * (1 - depth))
    else:
        layer = np.broadcast_to(col, (H, W, 3))
    img[mask] = layer[mask] if fade is not None else col
    return img


def blurred(img, radius):
    return np.asarray(Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius)), np.float32)


def rays(img, cx, cy, strength=0.5, threshold=170):
    """God rays: the brightest parts zoom-blurred outward from the light source."""
    lum = img.mean(axis=2)
    bright = img * (np.clip((lum - threshold) / (255 - threshold), 0, 1))[..., None]
    src = Image.fromarray(np.clip(bright, 0, 255).astype(np.uint8))
    acc = np.zeros_like(img)
    steps = 14
    for i in range(steps):
        s = 1 + 0.035 * i
        w, h = int(W * s), int(H * s)
        z = src.resize((w, h), Image.BILINEAR)
        left, top = int(cx * s - cx), int(cy * s - cy)
        acc += np.asarray(z.crop((left, top, left + W, top + H)), np.float32) * (1 - i / steps)
    return img + blurred(acc / steps, 6) * strength * 2.2


def save(img, name, blur=0.6, warm=(1.04, 1.0, 0.94), cool=(0.92, 1.0, 1.08), out_dir=None, quality=88):
    """The finish of a graded film still: bloom, split-tone grade, lens vignette, a touch of chromatic aberration, grain."""
    if blur:
        img = blurred(img, blur)
    # bloom: the highlights spread softly
    lum = img.mean(axis=2, keepdims=True)
    img = img + blurred(img * np.clip((lum - 150) / 105, 0, 1), 26) * 0.45
    # split-tone: cool shadows, warm highlights, then a gentle S-curve
    t = np.clip(img.mean(axis=2, keepdims=True) / 255, 0, 1)
    img = img * (np.array(cool) * (1 - t) + np.array(warm) * t)
    x = np.clip(img / 255, 0, 1)
    img = (x * x * (3 - 2 * x) * 0.35 + x * 0.65) * 255
    # vignette
    y, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    d = np.sqrt(((xx - W / 2) / (W / 2)) ** 2 + ((y - H / 2) / (H / 2)) ** 2)
    img *= (1 - 0.32 * np.clip(d - 0.55, 0, 1) ** 1.5)[..., None]
    # chromatic aberration: red slightly larger, blue slightly smaller, toward the edges
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    r, g, b = out.split()
    r = r.resize((W + 4, H + 2), Image.BILINEAR).crop((2, 1, W + 2, H + 1))
    b = b.resize((W - 4, H - 2), Image.BILINEAR)
    b2 = Image.new('L', (W, H)); b2.paste(b, (2, 1)); b2.paste(g.crop((0, 0, W, 1)), (0, 0))
    out = Image.merge('RGB', (r, g, b2))
    grain = (rng.standard_normal((H, W, 1)) * 4.2).astype(np.float32)
    out = Image.fromarray(np.clip(np.asarray(out, np.float32) + grain, 0, 255).astype(np.uint8))
    out.save(os.path.join(out_dir or OUT, name), quality=quality, optimize=True, progressive=True)
    print('wrote', name)


def neon_city():
    img = vgrad((14, 10, 40), (120, 30, 90), 1.4)
    img = glow(img, 1250, 760, 520, (255, 60, 160), 0.55)
    img = glow(img, 600, 820, 420, (40, 160, 255), 0.45)
    r = np.random.default_rng(3)
    lit = np.zeros((H, W), np.float32)
    for layer, (lo, hi, shade) in enumerate([(380, 620, 28), (480, 760, 16), (600, 860, 8)]):
        x = 0
        while x < W:
            bw = int(r.integers(60, 190))
            top = int(r.integers(lo, hi))
            img[top:830, x:x + bw] = (shade, shade * 0.8, shade * 1.6)
            if layer < 2:   # windows
                for wy in range(top + 14, 820, 22):
                    for wx in range(x + 10, x + bw - 10, 18):
                        if r.random() < 0.32:
                            c = (255, 190, 120) if r.random() < 0.6 else (120, 220, 255)
                            img[wy:wy + 8, wx:wx + 6] = c
                            lit[wy:wy + 8, wx:wx + 6] = 1
            x += bw + int(r.integers(0, 14))
    # wet street: the skyline mirrored and smeared
    refl = img[830 - (H - 830):830][::-1].copy() * 0.55
    img[830:] = refl
    street = Image.fromarray(np.clip(img[830:], 0, 255).astype(np.uint8)).filter(ImageFilter.BoxBlur(6))
    img[830:] = np.asarray(street, np.float32) * vgrad((1, 1, 1), (0.6, 0.6, 0.7))[830:]
    # bokeh
    for _ in range(60):
        cx, cy, rad = r.random() * W, 760 + r.random() * 320, 8 + r.random() * 34
        col = (255, 80, 180) if r.random() < 0.5 else (80, 200, 255)
        img = glow(img, cx, cy, rad, col, 0.35)
    img += noise(4, 4, 11)[..., None] * np.array((30, 10, 40), np.float32)
    # haze between the skyline's layers, lit from below by the street
    haze = np.exp(-((np.arange(H, dtype=np.float32) - 780) / 140) ** 2)[:, None] * (0.5 + 0.5 * noise(3, 4, 12))
    img += haze[..., None] * np.array((90, 30, 80), np.float32)
    save(img, 'neon-meridian.jpg', 1.0, warm=(1.06, 0.98, 1.0), cool=(0.9, 0.98, 1.12))


def dunes():
    img = vgrad((40, 60, 120), (250, 170, 90), 0.8)
    img = glow(img, 1380, 470, 240, (255, 220, 160), 0.75)
    img = glow(img, 1380, 470, 52, (255, 250, 230), 1.6)
    for i, (base, amp, color) in enumerate([(560, 40, (196, 110, 60)), (640, 55, (170, 86, 46)),
                                            (760, 70, (130, 62, 34)), (900, 80, (86, 38, 24))]):
        ys = ridge(20 + i, base, amp, 1.4)
        img = fill_below(img, ys, color, fade=260)
        # the lit side of each crest
        rows = np.arange(H)[:, None]
        band = (rows >= ys[None, :]) & (rows < ys[None, :] + 10)
        img[band] = img[band] * 0.6 + np.array((255, 200, 140)) * 0.4
    # sand ripples and light falling across the dunes from the sun
    ripples = 0.5 + 0.5 * np.sin(np.arange(H, dtype=np.float32)[:, None] * 0.55 + noise(3, 14, 8) * 22)
    ground = np.arange(H)[:, None] > 560
    img = np.where(ground[..., None], img * (0.94 + 0.08 * ripples[..., None]), img)
    xs = np.arange(W, dtype=np.float32)[None, :]
    img *= (0.82 + 0.3 * np.exp(-((xs - 1380) / 700) ** 2))[..., None]
    img = img * (0.92 + 0.16 * noise(5, 8, 5)[..., None])
    img = rays(img, 1380, 470, 0.28, 215)
    save(img, 'long-dunes.jpg', 0.8)


def peak():
    img = vgrad((60, 90, 160), (250, 180, 190), 1.1)
    img = glow(img, 520, 560, 380, (255, 210, 200), 0.6)
    cols = [(150, 140, 175), (110, 105, 150), (72, 70, 115), (38, 40, 70)]
    for i, (base, amp) in enumerate([(430, 160), (520, 120), (610, 90), (700, 70)]):
        ys = ridge(40 + i, base, amp, 0.9) + (noise(3, 20, 60 + i)[0] - 0.5) * 40
        img = fill_below(img, ys, cols[i], fade=320)
        if i == 0:   # snow on the far peaks
            rows = np.arange(H)[:, None]
            snow = (rows >= ys[None, :]) & (rows < ys[None, :] + 40 + 30 * noise(2, 30, 9)[0][None, :]) & (ys[None, :] < 330)
            img[snow] = img[snow] * 0.3 + np.array((250, 240, 245)) * 0.7
    lake = 820
    img[lake:] = img[lake - (H - lake):lake][::-1] * vgrad((0.8, 0.85, 0.95), (0.5, 0.55, 0.7))[lake:]
    ripples = noise(3, 60, 77)[lake:]
    img[lake:] *= (0.9 + 0.2 * ripples)[..., None]
    img = rays(img, 520, 560, 0.35, 210)
    # mist in the valleys
    mist = np.exp(-((np.arange(H, dtype=np.float32) - 700) / 90) ** 2)[:, None] * (0.6 + 0.4 * noise(3, 5, 61))
    img += mist[..., None] * np.array((60, 55, 80), np.float32)
    save(img, 'quiet-peak.jpg', 0.9)


def orbital():
    img = np.zeros((H, W, 3), np.float32)
    n = noise(6, 3, 21)
    neb = np.clip((n - 0.45) * 3.2, 0, 1) ** 1.6
    img += neb[..., None] * np.array((60, 180, 210), np.float32)
    n2 = noise(6, 4, 22)
    img += (np.clip((n2 - 0.5) * 3.4, 0, 1) ** 1.8)[..., None] * np.array((190, 60, 200), np.float32)
    r = np.random.default_rng(9)
    for _ in range(2600):
        x, y = int(r.random() * W), int(r.random() * H)
        b = r.random() ** 4 * 255
        img[y, x] += b
    img = np.asarray(Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.7)), np.float32)
    for _ in range(40):
        img = glow(img, r.random() * W, r.random() * H, 3 + r.random() * 4, (255, 255, 255), 0.9)
    # a planet's limb at the bottom right, with its atmosphere
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    d = np.sqrt((x - 1500) ** 2 + (y - 1900) ** 2)
    planet = d < 1150
    shade = np.clip((1150 - d) / 600, 0, 1)
    img[planet] = (np.array((20, 40, 70)) * (0.3 + 0.7 * shade[planet][:, None]))
    atmo = np.exp(-((d - 1150) / 26) ** 2)
    img += atmo[..., None] * np.array((90, 170, 255), np.float32)
    save(img, 'orbital.jpg', 0.5, warm=(1.0, 1.0, 1.0), cool=(0.9, 1.0, 1.1))


def tidebreak():
    img = vgrad((30, 40, 60), (230, 120, 80), 1.6)
    clouds = noise(6, 4, 31)
    img[:600] = img[:600] * (0.7 + 0.5 * clouds[:600, :, None]) + (clouds[:600, :, None] ** 3) * np.array((255, 170, 120)) * 0.5
    img = glow(img, 900, 600, 300, (255, 160, 90), 0.9)
    sea_top = 600
    rows = np.arange(H - sea_top, dtype=np.float32)[:, None]
    xs = np.arange(W, dtype=np.float32)[None, :]
    sea = vgrad((70, 80, 100), (8, 26, 38), 0.7)[sea_top:]
    # long horizontal swells: thin near the horizon, wider toward the viewer
    warp = noise(4, 6, 41)[sea_top:] * 9
    swell = 0.5 + 0.5 * np.sin(rows ** 1.35 * 0.09 + warp + xs * 0.002)
    sea *= (0.78 + 0.32 * swell)[..., None]
    # the sun's path on the water, broken by the swells
    path = np.exp(-((xs - 900) / (50 + rows * 0.7)) ** 2) * np.clip((swell - 0.45) * 2.2, 0, 1)
    sea += path[..., None] * np.array((255, 170, 100)) * 0.9
    img[sea_top:] = sea
    # a lighthouse on a rocky headland at the right
    x = np.arange(W, dtype=np.float32)
    rock = np.where(x > 1380, 700 + ((x - 1700) / 330) ** 2 * 170 + (noise(2, 40, 56)[0] - 0.5) * 30, H + 1)
    img = fill_below(img, rock.astype(np.float32), (14, 14, 20))
    img[540:735, 1680:1712] = (225, 222, 226)
    img[530:560, 1672:1720] = (40, 40, 48)
    img = glow(img, 1696, 545, 46, (255, 240, 200), 1.3)
    img = rays(img, 900, 600, 0.45, 190)
    save(img, 'tidebreak.jpg', 0.8)


FONT = os.path.join(os.path.dirname(OUT), '..', 'app', 'assets', 'fonts', 'InterVariable.ttf')


def wordmark(name, text, weight, size, tracking=0.0, upper=False, glow_color=None, gradient=None, sub=None):
    """A title treatment (the logo a streaming service supplies): white type on a transparent PNG, ≤ 1200 × 400."""
    from PIL import ImageDraw, ImageFont
    text = text.upper() if upper else text
    font = ImageFont.truetype(FONT, size)
    font.set_variation_by_axes([32, weight])   # Inter's axes: optical size, then weight
    widths = [font.getlength(ch) for ch in text]
    total = sum(widths) + tracking * size * (len(text) - 1)
    pad = int(size * 0.35)
    sub_h = int(size * 0.42) if sub else 0
    w, h = int(total + 2 * pad), int(size * 1.25 + 2 * pad + sub_h)
    canvas = Image.new('L', (w, h), 0)
    d = ImageDraw.Draw(canvas)
    x = pad
    for ch, cw in zip(text, widths):
        d.text((x, pad), ch, font=font, fill=255)
        x += cw + tracking * size
    if sub:
        sf = ImageFont.truetype(FONT, int(size * 0.24))
        sf.set_variation_by_axes([14, 600])
        sw = sf.getlength(sub) + 0.5 * size * 0.24 * (len(sub) - 1)
        sx = (w - sw) / 2
        for ch in sub:
            d.text((sx, pad + size * 1.12), ch, font=sf, fill=230)
            sx += sf.getlength(ch) + 0.5 * size * 0.24
    alpha = np.asarray(canvas, np.float32) / 255
    rgb = np.ones((h, w, 3), np.float32) * 255
    if gradient:   # a vertical tint across the letters
        t = np.linspace(0, 1, h, dtype=np.float32)[:, None, None]
        rgb = np.array(gradient[0], np.float32) * (1 - t) + np.array(gradient[1], np.float32) * t
        rgb = np.broadcast_to(rgb, (h, w, 3)).copy()
    out = np.dstack([rgb, alpha * 255])
    if glow_color:
        g = np.asarray(Image.fromarray((alpha * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(size * 0.18)), np.float32) / 255
        ga = np.clip(g * 0.9, 0, 1)
        base = np.dstack([np.broadcast_to(np.array(glow_color, np.float32), (h, w, 3)), ga * 255])
        a_top = alpha[..., None]
        comb_rgb = out[..., :3] * a_top + base[..., :3] * (1 - a_top)
        comb_a = np.maximum(alpha, ga) * 255
        out = np.dstack([comb_rgb, comb_a])
    img = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), 'RGBA')
    img = img.crop(img.getbbox())
    img.thumbnail((1200, 400), Image.LANCZOS)
    img.save(os.path.join(OUT, name + '-logo.png'), optimize=True)
    print('wrote', name + '-logo.png')


def logos():
    wordmark('neon-meridian', 'Neon Meridian', 800, 150, tracking=-0.02, glow_color=(255, 70, 190))
    wordmark('long-dunes', 'The Long Dunes', 300, 130, tracking=0.12, upper=True, gradient=((255, 236, 205), (240, 178, 110)))
    wordmark('quiet-peak', 'Quiet Peak', 200, 160, tracking=0.04, upper=True, sub='A MOUNTAIN STORY')
    wordmark('orbital', 'Orbital', 700, 170, tracking=0.32, upper=True, glow_color=(90, 200, 255))
    wordmark('tidebreak', 'Tidebreak', 900, 170, tracking=-0.03, upper=True, gradient=((255, 255, 255), (200, 220, 230)))


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    neon_city()
    dunes()
    peak()
    orbital()
    tidebreak()
    logos()
