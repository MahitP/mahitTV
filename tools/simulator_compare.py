"""Side by side: a tvOS Simulator frame and the same mahitTV screen (TVOS-EVOLUTION-AUDIT.md, last section).

Both are brought to 1920 x 1080 (the Apple TV 4K Simulator saves 3840 x 2160), then written as one image: the
Simulator on the left, mahitTV on the right, and under them a heat map of where they differ, with the mean difference.
Optional boxes (x,y,w,h in 1920 x 1080) crop both to one region first, e.g. Control Center's panel.

    xcrun simctl io booted screenshot sim.png
    python3 webos/build.py --preview && node tools/screenshots.js      # previews/web-tvos-*.png
    python3 tools/simulator_compare.py sim.png previews/web-tvos-light-control-center.png out.png [--box 1300,124,560,560]

Needs Pillow (pip install pillow).
"""
import argparse

from PIL import Image, ImageChops, ImageDraw, ImageOps


def load(path, box):
    im = Image.open(path).convert('RGB').resize((1920, 1080), Image.LANCZOS)
    return im.crop((box[0], box[1], box[0] + box[2], box[1] + box[3])) if box else im


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('simulator')
    ap.add_argument('mahittv')
    ap.add_argument('out')
    ap.add_argument('--box', help='x,y,w,h in 1920 x 1080 to compare only that region')
    a = ap.parse_args()
    box = [int(v) for v in a.box.split(',')] if a.box else None
    sim, ours = load(a.simulator, box), load(a.mahittv, box)
    diff = ImageChops.difference(sim, ours).convert('L')
    mean = sum(i * n for i, n in enumerate(diff.histogram())) / (diff.width * diff.height)
    heat = ImageOps.colorize(ImageOps.autocontrast(diff), black='#000000', white='#FF3B30', mid='#FF9500')
    w, h = sim.size
    sheet = Image.new('RGB', (2 * w, 2 * h + 40), '#1C1C1E')
    sheet.paste(sim, (0, 0))
    sheet.paste(ours, (w, 0))
    sheet.paste(heat, (w // 2, h + 40))
    d = ImageDraw.Draw(sheet)
    d.text((12, h + 10), 'tvOS Simulator', fill='#FFFFFF')
    d.text((w + 12, h + 10), 'mahitTV', fill='#FFFFFF')
    d.text((w // 2 + 12, 2 * h + 14), 'mean difference %.1f / 255' % mean, fill='#FFFFFF')
    sheet.save(a.out)
    print('%s: mean difference %.1f / 255' % (a.out, mean))


if __name__ == '__main__':
    main()
