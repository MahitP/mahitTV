"""Writes the web app's browser preview into a folder of your choice, without packaging anything or touching out/.

webos/build.py --preview leaves it in out/webos, which ./build.sh empties; this keeps a copy elsewhere (for
screenshots, or several builds side by side). Then serve the folder (http, not file://) and open index.html.

Usage: python3 tools/preview_site.py OUT_DIR
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import webapp  # noqa: E402

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = os.path.abspath(sys.argv[1])
    app = webapp.files(webapp.config_from_args([]), 'tools/preview_site.py')
    for name in ('icon.png', 'largeIcon.png'):
        with open(os.path.join(ROOT, 'webos', name), 'rb') as f:
            app[name] = f.read()
    webapp.preview(app, out)
    print('Preview in', out)


if __name__ == '__main__':
    main()
