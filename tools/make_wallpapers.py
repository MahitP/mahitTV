#!/usr/bin/env python3
"""Writes the bundled wallpapers and avatars (generated, so nothing copyrighted in them). Needs numpy and Pillow.

  app/assets/aerials/*.jpg   aerial-style 1920x1080 wallpapers: the background when the focused app has no Top Shelf
  app/assets/avatars/default-*.png   cartoon profile avatars (drop your own memoji.png beside them)

    python3 tools/make_wallpapers.py
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_preview_art as art  # noqa: E402  (noise, gradients, glow, rays and the film finish)

W, H = art.W, art.H
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
AERIALS = os.path.join(ROOT, 'app', 'assets', 'aerials')
AVATARS = os.path.join(ROOT, 'app', 'assets', 'avatars')


def ridge_field(seed, base, amp, octaves=6, rough=0.55):
    """A mountain skyline with fractal detail (y per column)."""
    r = np.random.default_rng(seed)
    xs = np.linspace(0, 1, W)
    y = np.full(W, base, np.float32)
    a = amp
    for o in range(octaves):
        f = 2 ** o * (1.2 + r.random())
        y += np.sin(xs * np.pi * f + r.random() * 6) * a
        a *= rough
    return y


def valley():
    """A granite valley at golden hour, seen from above its rim (Yosemite-like)."""
    img = art.vgrad((96, 140, 196), (250, 206, 150), 1.3)
    img = art.glow(img, 1500, 380, 300, (255, 200, 130), 0.45)
    layers = [(430, 120, (150, 140, 150), 11), (520, 150, (118, 104, 104), 12), (610, 110, (86, 74, 66), 13),
              (720, 70, (52, 60, 40), 14), (840, 50, (30, 40, 26), 15)]
    rows = np.arange(H, dtype=np.float32)[:, None]
    for i, (base, amp, col, seed) in enumerate(layers):
        ys = ridge_field(seed, base, amp)
        if i == 1:   # a great granite dome on the left, like the valley's famous one
            xs = np.arange(W, dtype=np.float32)
            ys = np.minimum(ys, 300 + ((xs - 420) / 260) ** 2 * 120)
        tex = art.noise(4, 24 + i * 10, 30 + i)
        depth = np.clip((rows - ys[None, :]) / 380, 0, 1)
        shade = np.array(col, np.float32) * (0.82 + 0.3 * tex[..., None]) * (1 - 0.25 * depth[..., None])
        # the sun catches the right-facing slopes
        lit = np.clip(np.gradient(ys)[None, :] * -0.05, -0.2, 0.25)
        shade *= (1 + lit)[..., None]
        mask = (rows >= ys[None, :])[..., None]
        haze = (0.55 - i * 0.11)
        shade = shade * (1 - haze) + np.array((220, 190, 160), np.float32) * haze
        img = np.where(mask, shade, img)
    img = art.rays(img, 1500, 380, 0.2, 225)
    mist = np.exp(-((rows - 760) / 70) ** 2) * (0.5 + 0.5 * art.noise(3, 6, 40))
    img += mist[..., None] * np.array((80, 70, 60), np.float32)
    art.save(img, 'valley.jpg', 0.9, out_dir=AERIALS, quality=82)


def coast():
    """A coastline from straight above: turquoise shallows, surf, a curving beach."""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    shore = 760 + 180 * np.sin(yy / H * 2.6 + 0.6) + 60 * (art.noise(3, 4, 51) - 0.5)
    d = xx - shore   # < 0: sea, > 0: land
    sea_deep = np.array((8, 58, 104), np.float32)
    sea_shallow = np.array((40, 196, 200), np.float32)
    t = np.clip(-d / 520, 0, 1)[..., None]
    img = sea_shallow * (1 - t) + sea_deep * t
    img *= (0.9 + 0.2 * art.noise(5, 10, 52))[..., None]
    sand = np.array((236, 214, 170), np.float32) * (0.9 + 0.12 * art.noise(4, 30, 53))[..., None]
    land_green = np.array((60, 96, 58), np.float32) * (0.8 + 0.4 * art.noise(5, 12, 54))[..., None]
    img = np.where((d > 0)[..., None], sand, img)
    img = np.where((d > 150 + 40 * art.noise(3, 8, 55))[..., None], land_green, img)
    # surf: white bands just off the beach, broken by noise
    for off, a in [(-14, 0.95), (-60, 0.55), (-120, 0.3)]:
        band = np.exp(-((d - off) / (6 + abs(off) * 0.06)) ** 2) * np.clip(art.noise(4, 18, 56 + abs(off)) * 1.6 - 0.35, 0, 1)
        img += band[..., None] * np.array((255, 255, 255), np.float32) * a
    art.save(img, 'coast.jpg', 0.7, out_dir=AERIALS, quality=82)


def earth():
    """The Earth at dawn from orbit: the limb, the atmosphere's glow, cloud cover, the sun just rising."""
    img = np.zeros((H, W, 3), np.float32)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    cx, cy, R = 960, 2950, 2450
    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    planet = dist < R
    lat = np.clip((R - dist) / 900, 0, 1)
    clouds = np.clip(art.noise(6, 6, 61) * 1.5 - 0.45, 0, 1)
    ocean = np.array((18, 58, 110), np.float32) * (0.75 + 0.6 * lat[..., None])
    surf = ocean + clouds[..., None] * np.array((210, 220, 235), np.float32) * (0.25 + 0.75 * lat[..., None])
    light = np.clip(1.15 - np.abs(xx - 1300) / 1700, 0.3, 1)[..., None]
    img = np.where(planet[..., None], surf * light, img)
    atmo = np.exp(-((dist - R) / 22) ** 2) + 0.4 * np.exp(-((dist - R) / 90) ** 2) * (dist > R)
    img += atmo[..., None] * np.array((90, 170, 255), np.float32) * light
    img = art.glow(img, 1300, cy - R - 6, 120, (255, 220, 170), 1.4)
    r = np.random.default_rng(62)
    for _ in range(900):
        x, y = int(r.random() * W), int(r.random() * 600)
        if not planet[y, x]:
            img[y, x] += r.random() ** 3 * 200
    art.save(img, 'earth.jpg', 0.5, warm=(1.05, 1.0, 0.95), cool=(0.9, 1.0, 1.1), out_dir=AERIALS, quality=82)


def aurora():
    """Ribbons of light through glass: soft flowing colour, the abstract wallpaper."""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    img = art.vgrad((14, 10, 40), (6, 4, 16), 1.0)
    warp = art.noise(4, 3, 71) * 260
    for c, (col, off, w) in enumerate([((60, 200, 255), 0, 120), ((150, 80, 255), 180, 160), ((255, 90, 170), 360, 110),
                                       ((70, 255, 190), -200, 90)]):
        centre = 520 + off * 0.6 + 220 * np.sin(xx / W * 3.1 + c * 1.3) + warp
        band = np.exp(-((yy - centre) / w) ** 2)
        img += band[..., None] * np.array(col, np.float32) * 0.5
    img = art.blurred(img, 18)
    # a glassy sheen: a soft highlight streak
    streak = np.exp(-((yy - (0.32 * xx + 120)) / 40) ** 2) * 0.16
    img += streak[..., None] * 255
    art.save(img, 'aurora.jpg', 0, warm=(1.0, 1.0, 1.0), cool=(1.0, 1.0, 1.0), out_dir=AERIALS, quality=84)


# Apple's avatar backgrounds (Contacts and Memoji): 18 top-to-bottom gradients, in Apple's order, measured off an
# iOS screenshot of the picker (its Display P3 converted to sRGB; two methods agree within 3/255). Avatars use only these.
APPLE_AVATAR_GRADIENTS = [
    ('#ABAEB6', '#878C96'),   # 1
    ('#CBC0F6', '#B5A4F2'),   # 2
    ('#9FC8EB', '#74B2E2'),   # 3
    ('#F5A4BF', '#EF7DA3'),   # 4
    ('#F2CE9B', '#EDB96E'),   # 5
    ('#BDEDAB', '#9FE787'),   # 6
    ('#D9B7B3', '#C89793'),   # 7
    ('#ECB393', '#E39163'),   # 8
    ('#CAC0D5', '#B4A4C3'),   # 9
    ('#B9CDE0', '#9BB7D3'),   # 10
    ('#C4E2E4', '#ABD5D8'),   # 11
    ('#ECA4E7', '#E27CDD'),   # 12
    ('#99ECEE', '#66E4E7'),   # 13
    ('#A0F1B3', '#72EB92'),   # 14
    ('#CEC8C1', '#BAB1A7'),   # 15
    ('#DFCAAD', '#D1B48A'),   # 16
    ('#C7D3C4', '#B0C2AA'),   # 17
    ('#707070', '#333333'),   # 18
]


def hexrgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def avatar(name, bg, skin, hair, hair_style, shirt):
    """A friendly cartoon head on a gradient disc (Memoji-like in spirit, drawn here), 256 px."""
    S = 1024
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    top, bottom = hexrgb(bg[0]), hexrgb(bg[1])
    for y in range(S):   # Apple's background: a plain gradient, top to bottom
        t = y / (S - 1)
        d.line((0, y, S, y), fill=tuple(round(top[j] * (1 - t) + bottom[j] * t) for j in range(3)) + (255,))
    mask = Image.new('L', (S, S), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, S, S), fill=255)
    face = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    f = ImageDraw.Draw(face)
    f.ellipse((180, 860, 844, 1300), fill=shirt)                               # shoulders
    f.rounded_rectangle((440, 640, 584, 820), 40, fill=tuple(int(c * 0.9) for c in skin))   # neck
    if hair_style == 'long':
        f.ellipse((236, 230, 788, 770), fill=hair)
    f.ellipse((290, 250, 734, 790), fill=skin)                                 # head
    f.ellipse((262, 470, 330, 580), fill=skin)                                 # ears
    f.ellipse((694, 470, 762, 580), fill=skin)
    if hair_style in ('short', 'long'):
        f.chord((280, 200, 744, 600), 180, 360, fill=hair)
        f.ellipse((300, 260, 520, 420), fill=hair)
    elif hair_style == 'curly':
        for cx, cy in [(330, 330), (420, 270), (520, 250), (620, 280), (690, 350), (380, 300), (580, 260)]:
            f.ellipse((cx - 90, cy - 90, cx + 90, cy + 90), fill=hair)
    elif hair_style == 'bun':
        f.chord((280, 220, 744, 600), 180, 360, fill=hair)
        f.ellipse((440, 120, 584, 264), fill=hair)
    for ex in (420, 604):                                                      # eyes
        f.ellipse((ex - 46, 470, ex + 46, 566), fill=(255, 255, 255))
        f.ellipse((ex - 26, 492, ex + 26, 552), fill=(60, 40, 30))
        f.ellipse((ex - 6, 500, ex + 10, 516), fill=(255, 255, 255))
        f.arc((ex - 56, 420, ex + 56, 470), 200, 340, fill=tuple(int(c * 0.7) for c in hair), width=14)   # brows
    cheek = tuple(int(c * 0.65 + p * 0.35) for c, p in zip(skin, (255, 120, 130)))   # cheeks: skin blushed
    f.ellipse((360, 590, 430, 640), fill=cheek)
    f.ellipse((594, 590, 664, 640), fill=cheek)
    f.chord((440, 600, 584, 700), 0, 180, fill=(120, 40, 40))                  # smile
    f.chord((462, 600, 562, 640), 0, 180, fill=(255, 255, 255))
    face = face.filter(ImageFilter.GaussianBlur(1.2))
    im.alpha_composite(face)
    im.putalpha(Image.composite(im.getchannel('A'), Image.new('L', (S, S), 0), mask))
    im = im.resize((256, 256), Image.LANCZOS)
    im.save(os.path.join(AVATARS, name), optimize=True)
    print('wrote', name)


if __name__ == '__main__':
    os.makedirs(AERIALS, exist_ok=True)
    os.makedirs(AVATARS, exist_ok=True)
    valley()
    coast()
    earth()
    aurora()
    avatar('default-1.png', APPLE_AVATAR_GRADIENTS[4], (246, 205, 170), (238, 170, 70), 'long', (90, 130, 230))
    avatar('default-2.png', APPLE_AVATAR_GRADIENTS[2], (200, 150, 110), (40, 30, 26), 'short', (240, 90, 90))
    avatar('default-3.png', APPLE_AVATAR_GRADIENTS[5], (120, 82, 60), (24, 20, 18), 'curly', (250, 200, 60))
    avatar('default-4.png', APPLE_AVATAR_GRADIENTS[1], (252, 220, 196), (150, 70, 40), 'bun', (40, 170, 160))
