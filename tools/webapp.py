"""What the LG (webos/build.py) and Samsung (tizen/build.py) packages share: the settings and the web app's files.

The settings come from config.properties, the same file as the Android build. --generic leaves it out (the GitHub
release is built that way): no TMDB key, and the TV's time zone picks the city, the currency and the region.
"""
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import translate  # noqa: E402  (the catalogues and the keys the web app uses)

GENERIC = {'tmdbKey': '', 'tmdbRegion': '', 'latitude': '', 'longitude': '', 'city': '', 'language': 'auto',
           'currency': '', 'temperature': 'auto', 'theme': 'auto', 'showCrypto': False, 'hideAnimation': False, 'hideApps': [],
           'shelfApps': [], 'profiles': [], 'geminiKey': '', 'wellyModel': 'gemini-3.8-flash',
           'wellyLiteModel': 'gemini-3.5-flash-lite', 'ollamaUrl': '', 'ollamaModel': 'qwen3:8b'}


def config_from_args(args):
    """The settings for these command-line arguments (--generic, --config PATH or config.properties)."""
    if '--generic' in args:
        return dict(GENERIC)
    path = os.path.join(ROOT, 'config.properties')
    if '--config' in args:
        path = os.path.abspath(args[args.index('--config') + 1])
    return read_config(path)


def read_config(path):
    if not os.path.exists(path):
        shutil.copy(os.path.join(ROOT, 'config.example.properties'), path)
        print('Created config.properties: add your TMDB key and your city there (optional), then build again.')
    values = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip().strip('"')

    def number(key, default):
        v = values.get(key, '') or default
        if not re.match(r'^-?\d+(\.\d+)?$', v):
            sys.exit('config.properties: %s must be a number (got "%s")' % (key, v))
        return v

    return {
        'tmdbKey': values.get('tmdb_key', ''),
        'tmdbRegion': values.get('tmdb_region', 'US') or 'US',
        'latitude': number('latitude', '40.7128'),
        'longitude': number('longitude', '-74.0060'),
        'city': values.get('city', ''),
        'language': values.get('language', 'auto') or 'auto',
        'currency': values.get('currency', 'USD') or 'USD',
        'temperature': values.get('temperature', 'auto') or 'auto',
        'theme': values.get('theme', 'auto') or 'auto',
        'showCrypto': values.get('show_crypto', 'false').lower() == 'true',
        'hideAnimation': values.get('hide_animation', 'false').lower() == 'true',
        'hideApps': [s.strip() for s in values.get('hide_apps', '').split(',') if s.strip()],
        'shelfApps': [s.strip() for s in values.get('shelf_apps', '').split(',') if s.strip()],
        'profiles': profiles(values.get('profiles', '')),
        # Welly (WELLY-HOME-REMOTE-SPEC.md §3.2). The web build can't reach Kasa (no TCP from a page): no kasa key here.
        'geminiKey': values.get('gemini_key', ''),
        'wellyModel': values.get('welly_model', 'gemini-3.8-flash') or 'gemini-3.8-flash',
        'wellyLiteModel': values.get('welly_lite_model', 'gemini-3.5-flash-lite') or 'gemini-3.5-flash-lite',
        'ollamaUrl': values.get('ollama_url', ''),
        'ollamaModel': values.get('ollama_model', 'qwen3:8b') or 'qwen3:8b',
    }


def profiles(text):
    """profiles=Name:avatar.png,… (avatars in app/assets/avatars) → [{name, avatar}]; empty: the app picks one."""
    out = []
    for item in text.split(','):
        if ':' in item:
            name, avatar = item.split(':', 1)
            if name.strip():
                out.append({'name': name.strip(), 'avatar': avatar.strip()})
    return out


def coins():
    """The coins' logos from the Android app's vector drawables, as SVG paths (BTC, ETH, SOL, USDT)."""
    out = []
    for name in ('btc', 'eth', 'sol', 'usdt'):
        with open(os.path.join(ROOT, 'app', 'res', 'drawable', 'coin_%s.xml' % name), encoding='utf-8') as f:
            out.append(re.findall(r'android:pathData="([^"]+)"', f.read()))
    return out


def catalogues():
    """Each language's catalogue (app/assets/i18n), with only the keys the web app uses: {lang: {key: text}}."""
    keys = translate.web_keys()
    return {lang: {k: v for k, v in sorted(translate.load(lang).items()) if k in keys} for lang in translate.languages()}


def files(config, made_by):
    """The web app's files, {path inside the app: bytes}: web/, the settings, the catalogues, the app's symbols and font."""
    out = {}
    # Your Memoji, when you dropped app/assets/avatars/memoji.png in: the default profile's avatar.
    memoji = os.path.exists(os.path.join(ROOT, 'app', 'assets', 'avatars', 'memoji.png'))
    config = dict(config, defaultAvatar='memoji.png' if memoji else 'default-1.png')
    web = os.path.join(ROOT, 'web')
    for name in sorted(os.listdir(web)):
        if os.path.isdir(os.path.join(web, name)):
            continue
        with open(os.path.join(web, name), 'rb') as f:
            out[name] = f.read()
    icons = os.path.join(web, 'icons')
    for name in sorted(os.listdir(icons)):
        with open(os.path.join(icons, name), 'rb') as f:
            out['icons/' + name] = f.read()
    out['config.js'] = ('/* Generated by %s. */\nwindow.CONFIG = %s;\n'
                        % (made_by, json.dumps(config, ensure_ascii=False))).encode('utf-8')
    out['coins.js'] = ('window.COINS = %s;\n' % json.dumps(coins())).encode('utf-8')
    out['i18n.js'] = ('/* Generated by %s from app/assets/i18n. */\nwindow.I18N_CATALOGUES = %s;\n'
                      % (made_by, json.dumps(catalogues(), ensure_ascii=False, separators=(',', ':')))).encode('utf-8')
    symbols = os.path.join(ROOT, 'app', 'assets', 'symbols')
    for name in sorted(os.listdir(symbols)):
        if name.endswith('.png') or name.startswith('LICENSE'):
            with open(os.path.join(symbols, name), 'rb') as f:
                out['symbols/' + name] = f.read()
    # The wallpapers (aerials) and the profile avatars (yours too: avatars/memoji.png), shared with the Android app.
    for folder in ('aerials', 'avatars'):
        src = os.path.join(ROOT, 'app', 'assets', folder)
        for name in sorted(os.listdir(src)) if os.path.isdir(src) else []:
            if name.lower().endswith(('.jpg', '.png')):
                with open(os.path.join(src, name), 'rb') as f:
                    out[folder + '/' + name] = f.read()
    # The Home tab's house and Welly's prompt pills, shared with the Android app, as scripts (TVs can't always read
    # a JSON file from file://).
    for name, var in (('house', 'HOUSE'), ('welly-preview', 'WELLY_PREVIEW')):
        with open(os.path.join(ROOT, 'app', 'assets', name + '.json'), encoding='utf-8') as f:
            data = json.load(f)
        out[name + '-data.js'] = ('window.%s = %s;\n' % (var, json.dumps(data, ensure_ascii=False, separators=(',', ':')))).encode('utf-8')
    for name in ('InterVariable.ttf', 'OFL.txt'):
        with open(os.path.join(ROOT, 'app', 'assets', 'fonts', name), 'rb') as f:
            out['fonts/' + name] = f.read()
    return out


def preview(app, out_dir):
    """Leaves the app's files in out_dir, to open index.html in a browser.

    Adds preview/preview.js and the generated photos (preview/art): without a TMDB key, sample picks show on the Top
    Shelf. Only here: the LG and Samsung packages never carry them.
    """
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    app = dict(app)
    art = os.path.join(ROOT, 'preview', 'art')
    for name in sorted(os.listdir(art)):
        with open(os.path.join(art, name), 'rb') as f:
            app['preview/' + name] = f.read()
    with open(os.path.join(ROOT, 'preview', 'preview.js'), 'rb') as f:
        app['preview.js'] = f.read()
    app['index.html'] = app['index.html'].replace(b'<script src="data.js">', b'<script src="preview.js"></script>\n<script src="data.js">')
    for path, content in app.items():
        target = os.path.join(out_dir, path)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, 'wb') as f:
            f.write(content)
