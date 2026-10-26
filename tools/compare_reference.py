#!/usr/bin/env python3
"""Side by side: a reference photo of Apple TV's screen and our screenshot, at the same 1920×1080 scale.

    python3 tools/compare_reference.py REFERENCE.png OURS.png OUT.png [--crop x0,y0,x1,y1]

--crop takes the TV screen out of a product photo (pixels in the reference) before scaling it to 1920×1080.
"""
import sys

from PIL import Image, ImageDraw, ImageFont

ref, ours, out = sys.argv[1:4]
crop = None
if '--crop' in sys.argv:
    crop = tuple(int(v) for v in sys.argv[sys.argv.index('--crop') + 1].split(','))
a = Image.open(ref).convert('RGB')
if crop:
    a = a.crop(crop)
a = a.resize((1920, 1080), Image.LANCZOS)
b = Image.open(ours).convert('RGB').resize((1920, 1080), Image.LANCZOS)
pad, label = 24, 64
sheet = Image.new('RGB', (1920 * 2 + pad * 3, 1080 + label + pad), (18, 18, 20))
sheet.paste(a, (pad, label))
sheet.paste(b, (1920 + pad * 2, label))
d = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype('app/assets/fonts/InterVariable.ttf', 36)
    font.set_variation_by_axes([14, 600])
except Exception:
    font = ImageFont.load_default()
d.text((pad, 14), 'Apple TV (tvOS 26), Apple press photo', fill=(235, 235, 240), font=font)
d.text((1920 + pad * 2, 14), 'Free My TV (this build)', fill=(235, 235, 240), font=font)
sheet.save(out, optimize=True)
print('wrote', out)
