"""What the LG (webos/build.py) and Samsung (tizen/build.py) packages share: the settings and the web app's files.

The settings come from config.properties, the same file as the Android build. --generic leaves it out (the GitHub
release is built that way): no TMDB key, and the TV's time zone picks the city, the currency and the region.
"""
import hashlib
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
           'shelfApps': [], 'defaultDock': [], 'profiles': [], 'pinSalt': '', 'geminiKey': '', 'wellyModel': 'gemini-3.8-flash',
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

    salt = os.urandom(16).hex()   # this build's PIN salt
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
        # default_dock_apps (MAHITTV-PROFILES-SPEC.md §5): each new profile's Dock; shelf_apps is its older name.
        'defaultDock': [s.strip() for s in (values.get('default_dock_apps', '') or values.get('shelf_apps', '')).split(',')
                        if s.strip()],
        'profiles': profiles(values.get('profiles', ''), salt),
        'pinSalt': salt,
        # Welly (WELLY-HOME-REMOTE-SPEC.md §3.2). The web build can't reach Kasa (no TCP from a page): no kasa key here.
        'geminiKey': values.get('gemini_key', ''),
        'wellyModel': values.get('welly_model', 'gemini-3.8-flash') or 'gemini-3.8-flash',
        'wellyLiteModel': values.get('welly_lite_model', 'gemini-3.5-flash-lite') or 'gemini-3.5-flash-lite',
        'ollamaUrl': values.get('ollama_url', ''),
        'ollamaModel': values.get('ollama_model', 'qwen3:8b') or 'qwen3:8b',
    }


def profiles(text, salt):
    """profiles=id:Name:role:PIN[:avatar],… → [{id, name, role, pin, avatar}] (MAHITTV-PROFILES-SPEC.md §1): the PIN
    only as sha256(salt + PIN). The old form Name:avatar.png gives a standard profile without a PIN."""
    out = []
    for item in text.split(','):
        parts = [p.strip() for p in item.strip().split(':')]
        if not parts[0]:
            continue
        if len(parts) <= 2:
            name, role, pin, avatar = parts[0], 'standard', '', parts[1] if len(parts) > 1 else ''
            pid = parts[0]
        else:
            pid, name, role = parts[0], parts[1], parts[2]
            pin = parts[3] if len(parts) > 3 else ''
            avatar = parts[4] if len(parts) > 4 else ''
        pid = re.sub(r'[^a-z0-9_-]', '', pid.lower())
        if role not in ('manager', 'standard', 'guest'):
            raise SystemExit('config.properties: profiles: role must be manager, standard or guest (got "%s")' % role)
        if pin and not re.fullmatch(r'[0-9]{4}', pin):
            raise SystemExit('config.properties: profiles: %s\'s PIN must be 4 digits' % name)
        out.append({'id': pid, 'name': name, 'role': role, 'avatar': avatar,
                    'pin': hashlib.sha256((salt + pin).encode('utf-8')).hexdigest() if pin else ''})
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
    # Your family's avatars by profile id (app/assets/profiles/<id>.png, personal), packed as profiles/<id>.png.
    own = os.path.join(ROOT, 'app', 'assets', 'profiles')
    config['profileAvatars'] = sorted(n for n in os.listdir(own) if n.endswith('.png')) if os.path.isdir(own) else []
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
    for folder in ('aerials', 'avatars', 'profiles'):
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
