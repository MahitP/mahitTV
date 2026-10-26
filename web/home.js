/*
 * The home screen for web-app TVs (LG webOS, Samsung Tizen), with the Android app's design and behavior (HomeView,
 * DESIGN-TVOS.md), after Apple TV's:
 *  - the Top Shelf: this hour's pick edge to edge, its title, rating, details and synopsis at the bottom left, page
 *    dots; ← → page through the picks, and it pages by itself;
 *  - a glass pill with the time and the weather and icon-only glass buttons at the top right (Live TV, HDMI Inputs,
 *    which opens the drawer with the inputs and the weather, and Settings);
 *  - the glass Dock with the apps; ↓ brings up the other apps as a grid; ↑ or Back goes back;
 *  - a night look from sunset to sunrise; a screensaver after a few idle minutes.
 * The remote's arrows, OK and Back move the focus; a pointer (LG's Magic Remote) focuses what it points at.
 */
(function () {
  'use strict';

  function $(id) { return document.getElementById(id); }

  function el(tag, cls, parent, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    if (parent) parent.appendChild(e);
    return e;
  }

  function sym(name, parent, size) {
    var s = el('span', 'sym', parent);
    s.style.webkitMaskImage = 'url(symbols/' + name + '.png)';
    s.style.maskImage = 'url(symbols/' + name + '.png)';
    if (size) { s.style.width = size + 'px'; s.style.height = size + 'px'; }
    return s;
  }

  function coin(i, parent) {
    var c = window.COINS && window.COINS[i];
    if (!c) return;
    var ns = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 32 32');
    // Old WebKit (Tizen 2) draws an SVG without them at 300×150; the CSS still sets the size everywhere it's shown.
    svg.setAttribute('width', '15');
    svg.setAttribute('height', '15');
    for (var k = 0; k < c.length; k++) {
      var p = document.createElementNS(ns, 'path');
      p.setAttribute('d', c[k]);
      p.setAttribute('fill-rule', 'evenodd');
      svg.appendChild(p);
    }
    parent.appendChild(svg);
  }

  function hasClass(e, c) { return (' ' + e.className + ' ').indexOf(' ' + c + ' ') >= 0; }
  function setClass(e, c, on) {
    if (!e) return;
    if (on && !hasClass(e, c)) e.className = (e.className + ' ' + c).replace(/^\s+/, '');
    else if (!on && hasClass(e, c)) e.className = (' ' + e.className + ' ').replace(' ' + c + ' ', ' ').replace(/^\s+|\s+$/g, '');
  }

  var stage = $('stage'), body = document.body;

  /** The 1920 × 1080 stage fills the window (on the TV it already is 1920 × 1080). */
  function fit() {
    var s = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
    stage.style.webkitTransform = stage.style.transform = s === 1 ? '' : 'scale(' + s + ')';
  }

  // MARK: - Apps on the shelf

  /** Shelf apps in this order when installed, like the Android app: key, LG app id, name on the TV, brand gradient. */
  var SHELF = [
    ['netflix', /^netflix$/, /netflix/i, ['#141414', '#000000']],
    ['youtube', /youtube/, /^youtube$/i, ['#202020', '#0B0B0B']],
    ['prime', /^amazon$/, /prime video|amazon/i, ['#1FA2FF', '#0A62C9']],
    ['disney', /disney/, /disney/i, ['#1A3E9A', '#0A1854']],
    ['max', /hbo|wbd/, /^(hbo )?max$|hbo max/i, ['#5B2EEA', '#1F1477']],
    ['appletv', /appletv|apple\.tv/, /apple tv/i, ['#2C2C2E', '#000000']],
    ['hulu', /^hulu/, /hulu/i, ['#1CE783', '#0E9F57']],
    ['paramount', /paramount|cbs/, /paramount/i, ['#0064FF', '#002A86']],
    ['peacock', /peacock/, /peacock/i, ['#1C1C1E', '#000000']],
    ['nfl', /nfl/, /^nfl/i, ['#013369', '#00163D']],
    ['globoplay', /globoplay/, /globoplay/i, ['#FF8A1F', '#FF2D5A']],
    ['jellyfin', /jellyfin/i, /jellyfin/i, ['#AA5CC3', '#00A4DC']],
    ['spotify', /spotify/, /spotify/i, ['#202020', '#050505']],
    ['plex', /^cdp-30$|plex/i, /^plex$/i, ['#282A2D', '#121315']],
    ['crunchyroll', /crunchyroll/, /crunchyroll/i, ['#FF8A1F', '#E8590C']]
  ];
  // Apps shown at once, as on Android: 8 (today's smallest tiles, 178 wide); the shelf scrolls for the rest. The
  // built-in list keeps 8 too.
  // The Dock shows 5 at once (tvOS's 5:3 tiles, 300 × 180) and scrolls for the rest.
  // As in Apple's photo (DESIGN-TVOS.md §1): 6 columns of 251 × 150, 49 apart, 85 px margins; the Dock's glass pads them
  // 48 × 40 from y 782; the grid's rows are 250 apart, its first row peeking at y 1052 until ↓ lifts the page 722.
  var SHELF_VISIBLE = 6, FEW_APPS = 8, TILE_W = 251, TILE_H = 150, TILE_GAP = 49, DOCK_PAD_X = 48, DOCK_PAD_Y = 40, DOCK_TOP = 782;
  var COLS = 6, GRID_LIFT = 722, GRID_ROW = 250, GRID_TOP = 330, GRID_LEFT = 85;
  var AERIALS = ['valley', 'coast', 'earth', 'aurora'];

  function shelfKey(app) {
    for (var i = 0; i < SHELF.length; i++) if (SHELF[i][1].test(app.id) || SHELF[i][2].test(app.title)) return SHELF[i];
    return null;
  }

  function darken(hex, f) {
    var m = /^#?([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return '#161618';
    var n = parseInt(m[1], 16), r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    return 'rgb(' + Math.round(r * f) + ',' + Math.round(g * f) + ',' + Math.round(b * f) + ')';
  }

  /** A name that fits the tile on one line (the font shrinks for long names). */
  function wordFit(title, width, max) {
    return Math.max(18, Math.min(max, Math.floor(width * 0.8 / (Math.max(4, title.length) * 0.56))));
  }

  // MARK: - Logos

  // The TV's icons are small squares with their own background (white behind Netflix, black behind Max). Like the
  // Android app does with the TV art, the logo is lifted off it once: the background goes transparent and the rest is
  // cropped to the logo, which then fills the tile on the brand's color.
  var lifted = {};   // icon url -> {src, bg: [r, g, b], ink: luminance histogram} or false (keep the icon as it is)
  var LIFT_SIZE = 240;

  // sRGB (0-255) to linear light, computed once: lift() needs it for every pixel, and old TVs are slow at Math.pow.
  var LINEAR = [];
  for (var li = 0; li < 256; li++) LINEAR[li] = li <= 10 ? li / 255 / 12.92 : Math.pow((li / 255 + 0.055) / 1.055, 2.4);

  /** Relative luminance (0-1) of an sRGB color (whole numbers, as read from the canvas). */
  function luminance(r, g, b) {
    return 0.2126 * LINEAR[r] + 0.7152 * LINEAR[g] + 0.0722 * LINEAR[b];
  }

  function rgbOf(color) {
    var m = /^#?([0-9a-f]{6})$/i.exec(color || '');
    if (m) { var n = parseInt(m[1], 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; }
    m = /rgb\((\d+),\s*(\d+),\s*(\d+)\)/.exec(color || '');
    return m ? [+m[1], +m[2], +m[3]] : [0, 0, 0];
  }

  function lift(img) {
    var w = img.naturalWidth, h = img.naturalHeight;
    if (!w || !h) return false;
    var f = Math.min(1, LIFT_SIZE / Math.max(w, h));
    w = Math.max(1, Math.round(w * f));
    h = Math.max(1, Math.round(h * f));
    var canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    var g = canvas.getContext('2d');
    var data;
    try {
      g.drawImage(img, 0, 0, w, h);
      data = g.getImageData(0, 0, w, h);
    } catch (e) {
      return false;   // a TV that doesn't let the page read its icons
    }
    var px = data.data;
    // The background: the most common color a little inside the edges (rounded icons have transparent corners).
    var votes = {}, best = null, bestN = 0, inset = Math.round(Math.min(w, h) * 0.08);
    for (var s = 0; s < 64; s++) {
      var t = s % 16 / 16, side = s >> 4;
      var x = side < 2 ? Math.round(inset + t * (w - 2 * inset)) : side === 2 ? inset : w - 1 - inset;
      var y = side < 2 ? (side === 0 ? inset : h - 1 - inset) : Math.round(inset + t * (h - 2 * inset));
      var i = (y * w + x) * 4;
      var key = px[i + 3] < 128 ? 'clear' : (px[i] >> 4) + ',' + (px[i + 1] >> 4) + ',' + (px[i + 2] >> 4);
      votes[key] = (votes[key] || 0) + 1;
      if (votes[key] > bestN) { bestN = votes[key]; best = key === 'clear' ? null : [px[i], px[i + 1], px[i + 2]]; }
    }
    var minX = w, minY = h, maxX = -1, maxY = -1, ink = [0, 0, 0, 0, 0, 0, 0, 0], inkSum = 0;
    for (var p = 0; p < w * h; p++) {
      var j = p * 4, a = px[j + 3] / 255;
      if (best) {
        // color to alpha: how far each channel is from the background, as a share of how far it could go, so the
        // anti-aliased edges come out as the logo's color, partly clear, without a fringe of the background
        var k = 0, c;
        for (c = 0; c < 3; c++) {
          var v = px[j + c], o = best[c];
          k = Math.max(k, v > o ? (v - o) / Math.max(1, 255 - o) : (o - v) / Math.max(1, o));
        }
        k = Math.max(0, Math.min(1, (k - 0.1) / 0.9));   // compression noise stays clear
        if (k > 0 && k < 1) {
          for (c = 0; c < 3; c++) px[j + c] = Math.max(0, Math.min(255, best[c] + (px[j + c] - best[c]) / k));
        }
        a *= k;
      }
      px[j + 3] = Math.round(a * 255);
      if (a > 0.3) {
        var px0 = p % w, py0 = (p / w) | 0;
        if (px0 < minX) minX = px0;
        if (px0 > maxX) maxX = px0;
        if (py0 < minY) minY = py0;
        if (py0 > maxY) maxY = py0;
        ink[Math.min(7, Math.floor(Math.sqrt(luminance(px[j], px[j + 1], px[j + 2])) * 8))] += a;
        inkSum += a;
      }
    }
    // Nothing left, or nothing taken away (a picture rather than a logo on a background): keep the icon.
    if (maxX < 0 || (maxX - minX > w * 0.96 && maxY - minY > h * 0.96)) return false;
    var crop = document.createElement('canvas');
    crop.width = maxX - minX + 1;
    crop.height = maxY - minY + 1;
    g.putImageData(data, 0, 0);
    crop.getContext('2d').drawImage(canvas, minX, minY, crop.width, crop.height, 0, 0, crop.width, crop.height);
    for (var b = 0; b < 8; b++) ink[b] /= inkSum;
    try {
      return {src: crop.toDataURL('image/png'), bg: best, ink: ink};
    } catch (e2) {
      return false;
    }
  }

  /**
   * Share of the logo that hardly shows on a tile of this luminance (YouTube's black type on a dark tile). Each bin
   * of the histogram holds the ink whose square-root luminance falls in it.
   */
  function unseen(ink, tileLum) {
    var share = 0;
    for (var b = 0; b < 8; b++) {
      var l = Math.pow((b + 0.5) / 8, 2);
      if ((Math.max(l, tileLum) + 0.05) / (Math.min(l, tileLum) + 0.05) < 1.8) share += ink[b];
    }
    return share;
  }

  function paint(box, top, bottom) {
    box.style.background = '-webkit-linear-gradient(top, ' + top + ', ' + bottom + ')';
    box.style.background = 'linear-gradient(' + top + ', ' + bottom + ')';
  }

  /** A color that is clearly a hue (Crunchyroll's orange), not black, white or a gray. */
  function colorful(rgb) {
    return Math.max(rgb[0], rgb[1], rgb[2]) - Math.min(rgb[0], rgb[1], rgb[2]) >= 96;
  }

  /**
   * The logo's box in its own proportions, as large as object-fit: contain draws it. Chrome 38 (webOS 3) ignores
   * object-fit while a tile next to it animates, and the logo stretched to its 70% x 50% box until the move ended.
   * Not laid out yet (a closed sheet): object-fit stays, and fitLogos() runs when the sheet opens.
   */
  function fitLogo(img) {
    var box = img.parentNode, bw = img.offsetWidth, bh = img.offsetHeight, nw = img.naturalWidth, nh = img.naturalHeight;
    if (!box || !bw || !bh || !nw || !nh || img.style.width) return;
    var s = Math.min(bw / nw, bh / nh);
    img.style.width = nw * s / box.offsetWidth * 100 + '%';
    img.style.height = nh * s / box.offsetHeight * 100 + '%';
  }

  function fitLogos(root) {
    var imgs = root.querySelectorAll('img.logo');
    for (var i = 0; i < imgs.length; i++) if (imgs[i].complete) fitLogo(imgs[i]);
  }

  /** own: the tile has the TV's color for the app, not one of SHELF's. */
  function showLogo(img, box, top, bottom, logo, own) {
    img.className = 'logo';
    img.onload = function () { img.onload = null; fitLogo(img); };
    img.src = logo.src;
    if (img.complete) fitLogo(img);
    var a = rgbOf(top), z = rgbOf(bottom);
    var tileLum = (luminance(a[0], a[1], a[2]) + luminance(z[0], z[1], z[2])) / 2;
    // The tile takes the icon's background when the logo only reads on it, and when that background is the brand's
    // color: LG's bgColor isn't always the icon's (Crunchyroll's is black, on an orange icon).
    if (logo.bg && ((own && colorful(logo.bg)) || unseen(logo.ink, tileLum) > 0.35)) {
      var bg = 'rgb(' + logo.bg.join(',') + ')';
      var hex = '#' + ((1 << 24) | (logo.bg[0] << 16) | (logo.bg[1] << 8) | logo.bg[2]).toString(16).slice(1);
      paint(box, bg, darken(hex, 0.93));
    }
  }

  /** The icon's most vivid colour, weighted by saturation (the hue an app's icon is "about"), or null. */
  function dominant(img) {
    try {
      var c = document.createElement('canvas'), g = c.getContext('2d');
      c.width = c.height = 24;
      g.drawImage(img, 0, 0, 24, 24);
      var px = g.getImageData(0, 0, 24, 24).data, bins = {}, best = null, bestW = 0;
      for (var i = 0; i < px.length; i += 4) {
        if (px[i + 3] < 200) continue;
        var mx = Math.max(px[i], px[i + 1], px[i + 2]), mn = Math.min(px[i], px[i + 1], px[i + 2]);
        var sat = mx ? (mx - mn) / mx : 0, key = (px[i] >> 5) + ',' + (px[i + 1] >> 5) + ',' + (px[i + 2] >> 5);
        var b = bins[key] || (bins[key] = {w: 0, r: 0, g: 0, b: 0});
        var w = 0.15 + sat * sat * (mx / 255);
        b.w += w; b.r += px[i] * w; b.g += px[i + 1] * w; b.b += px[i + 2] * w;
        if (b.w > bestW) { bestW = b.w; best = b; }
      }
      return best ? [Math.round(best.r / best.w), Math.round(best.g / best.w), Math.round(best.b / best.w)] : null;
    } catch (e) {
      return null;
    }
  }

  /** A rich tvOS backdrop from one colour: lighter at the top left, darker at the bottom right. */
  function richPaint(box, rgb) {
    function tone(f) { return 'rgb(' + rgb.map(function (v) { return Math.max(0, Math.min(255, Math.round(f > 1 ? v + (255 - v) * (f - 1) : v * f))); }).join(',') + ')'; }
    box.style.background = '-webkit-linear-gradient(-35deg, ' + tone(1.12) + ', ' + tone(1) + ' 45%, ' + tone(0.72) + ')';
    box.style.background = 'linear-gradient(125deg, ' + tone(1.12) + ', ' + tone(1) + ' 45%, ' + tone(0.72) + ')';
  }

  /** An app's face: its logo on its brand color (or its name, when the icon can't be read). */
  function face(app, box, wordSize) {
    if (!app.icon && /(^|\.)settings$/i.test(app.id)) {   // the TV's settings: graphite with a white gear, as tvOS draws it
      paint(box, '#8E8E93', '#48484A');
      sym('settings', box).className += ' big';
      return;
    }
    var k = shelfKey(app);
    var top = k ? k[3][0] : app.color ? darken(app.color, 0.92) : '#2A2A2E';
    var bottom = k ? k[3][1] : app.color ? darken(app.color, 0.62) : '#161618';
    paint(box, top, bottom);
    var word = el('div', 'word', box, app.title);
    if (wordSize) word.style.fontSize = wordSize + 'px';
    if (app.icon) {
      var img = el('img', '', box), url = app.icon;
      word.style.visibility = 'hidden';
      img.onerror = function () { if (img.parentNode) img.parentNode.removeChild(img); word.style.visibility = ''; };
      if (lifted[url]) { showLogo(img, box, top, bottom, lifted[url], !k); return; }
      if (lifted[url] !== false) {
        img.onload = function () {
          img.onload = null;
          if (lifted[url] === undefined) lifted[url] = lift(img);
          if (lifted[url]) { showLogo(img, box, top, bottom, lifted[url], !k); return; }
          // A picture, not a logo on a flat colour: the composer puts the icon, rounded, on a backdrop of its colour.
          if (!k) {
            var rgb = dominant(img);
            if (rgb) richPaint(box, rgb);
            img.className = 'icon';
          }
        };
      }
      img.src = url;
    }
  }

  var apps = [], shelf = [], others = [], inputs = [];

  /** shelf_apps in config.properties: the shelf in that order, by id or name (part of it). */
  var WANTED = (CONFIG.shelfApps || []).map(function (s) { return s.toLowerCase(); });

  function pickShelf() {
    if (WANTED.length && Prefs.shelfConfig() !== WANTED.join(',')) {
      Prefs.set('shelf', null);
      Prefs.set('shelfConfig', WANTED.join(','));
    }
    var chosen = Prefs.shelf(), out = [];
    if (chosen) {
      for (var i = 0; i < chosen.length; i++) {
        for (var j = 0; j < apps.length; j++) if (apps[j].id === chosen[i]) out.push(apps[j]);
      }
    } else if (WANTED.length) {
      for (var w = 0; w < WANTED.length; w++) {
        for (var k = 0; k < apps.length; k++) {
          var id = apps[k].id.toLowerCase(), title = apps[k].title.toLowerCase();
          if ((id.indexOf(WANTED[w]) >= 0 || title.indexOf(WANTED[w]) >= 0) && out.indexOf(apps[k]) < 0) { out.push(apps[k]); break; }
        }
      }
      if (!out.length) out = apps.slice(0, FEW_APPS);
    } else {
      for (var s = 0; s < SHELF.length && out.length < FEW_APPS; s++) {
        for (var a = 0; a < apps.length; a++) {
          if ((SHELF[s][1].test(apps[a].id) || SHELF[s][2].test(apps[a].title)) && out.indexOf(apps[a]) < 0) { out.push(apps[a]); break; }
        }
      }
      if (!out.length) out = apps.slice(0, FEW_APPS);
    }
    shelf = out;
    others = apps.filter(function (x) { return shelf.indexOf(x) < 0; });
  }

  // MARK: - Focus

  var Z = {TOP: 'top', HERO: 'hero', SHELF: 'shelf', DRAWER: 'drawer', APPS: 'apps', SETTINGS: 'settings', EDIT: 'edit', LANG: 'lang', ABOUT: 'about'};
  var st = {zone: Z.SHELF, top: 3, page: 0, shelf: 0, drawer: 0, grid: 0, row: 0, edit: 0, lang: 0};
  var cards = [], lean = 0;

  function sheetOpen() { return st.zone === Z.APPS || st.zone === Z.SETTINGS || st.zone === Z.EDIT || st.zone === Z.LANG || st.zone === Z.ABOUT; }

  function updateFocus() {
    var i, list;
    setClass($('pill'), 'focus', st.zone === Z.TOP);
    setClass($('watch'), 'focus', st.zone === Z.HERO);
    setClass(body, 'full', st.zone === Z.HERO);
    list = $('tiles').children;
    for (i = 0; i < list.length; i++) setClass(list[i], 'focus', st.zone === Z.SHELF && st.shelf === i);
    revealShelf();
    placeName();
    list = document.querySelectorAll('#drawer .who');
    for (i = 0; i < list.length; i++) setClass(list[i], 'focus', st.zone === Z.DRAWER && st.drawer === i);
    list = document.querySelectorAll('#drawer .key');
    for (i = 0; i < list.length; i++) setClass(list[i], 'focus', st.zone === Z.DRAWER && st.drawer === people().length + i);
    list = document.querySelectorAll('#apps .cell');
    for (i = 0; i < list.length; i++) setClass(list[i], 'focus', st.zone === Z.APPS && st.grid === i);
    // The grid shows 2 rows under the risen Dock; past them, the page (Dock and all) scrolls on.
    var rows = Math.max(1, Math.ceil(others.length / COLS)), r = Math.floor(st.grid / COLS), first = Math.max(0, Math.min(rows - 2, r - 1));
    var grid = st.zone === Z.APPS;
    $('apps').lastChild.style.webkitTransform = $('apps').lastChild.style.transform = 'translateY(' + (-first * GRID_ROW) + 'px)';
    var home = grid ? 'translateY(' + (-(GRID_LIFT + first * GRID_ROW)) + 'px)' : '';
    $('home').style.webkitTransform = $('home').style.transform = home;
    focusRows($('settings'), st.row, st.zone === Z.SETTINGS);
    focusRows($('shelfedit'), st.edit, st.zone === Z.EDIT);
    focusRows($('langpick'), st.lang, st.zone === Z.LANG);
    if (langHint) langHint.textContent = (st.lang + 1) + ' / ' + languageChoices().length + '  ·  ' + tr('OK picks, Back cancels.');
    setClass(body, 'drawer-open', st.zone === Z.DRAWER);
    setClass(body, 'grid-open', grid);
    setClass(body, 'lean-l', lean < 0);
    setClass(body, 'lean-r', lean > 0);
  }

  function focusRows(sheet, index, on) {
    var rows = sheet.querySelectorAll('.row');
    for (var i = 0; i < rows.length; i++) setClass(rows[i], 'focus', on && i === index);
    var holder = sheet.querySelector('.rows');
    if (!holder) return;
    var visible = 7, first = Math.max(0, Math.min(rows.length - visible, index - 3));
    holder.style.webkitTransform = holder.style.transform = 'translateY(' + (-first * 78) + 'px)';
  }

  function nearest(nodes, x) {
    var best = 0, bestD = 1e9;
    for (var i = 0; i < nodes.length; i++) {
      var r = nodes[i].getBoundingClientRect(), d = Math.abs((r.left + r.right) / 2 - x);
      if (d < bestD) { bestD = d; best = i; }
    }
    return best;
  }

  function centerX(node) {
    if (!node) return 960;
    var r = node.getBoundingClientRect();
    return (r.left + r.right) / 2;
  }

  // MARK: - Top: the status pill, the buttons, the quotes

  function renderTop() {
    $('buttons').innerHTML = '';
    hover($('pill'), Z.TOP, 3);
    hover($('watch'), Z.HERO, 0);
    renderPill();
  }

  /** The profiles: profiles= in config.properties (avatars in app/assets/avatars), else one with your memoji.png. */
  function people() {
    var list = CONFIG.profiles && CONFIG.profiles.length ? CONFIG.profiles : [{name: tr('Me'), avatar: CONFIG.defaultAvatar || 'default-1.png'}];
    return list;
  }

  function person() {
    var list = people();
    return list[Math.max(0, Math.min(list.length - 1, Prefs.profile()))];
  }

  function avatarUrl(p) { return 'url(avatars/' + p.avatar + ')'; }

  /** The status pill: the time, then the current profile's avatar; the quotes (or what's playing) left of it. */
  function renderPill() {
    var pill = $('pill');
    pill.firstChild.textContent = formatDate('format|h:mm a', Date.now());
    pill.lastChild.style.backgroundImage = avatarUrl(person());
    $('quotes').style.right = (45 + pill.offsetWidth + 24) + 'px';
  }

  function renderQuotes() {
    var q = $('quotes'), coins = Data.activeCoins();
    q.innerHTML = '';
    for (var i = 0; i < coins.length; i++) {
      var item = el('span', 'q', q);
      coin(coins[i], item);
      el('span', '', item, Data.compact(Data.prices[coins[i]]));
    }
  }

  // MARK: - Top Shelf: the picks, their photos and text

  var photoFront = 'photoA', photoUrl = null, heroTimer = 0;

  function renderCards() {
    var list = Data.shelf();
    setClass(body, 'no-picks', !list.length);
    var same = list.length === cards.length;
    for (var i = 0; same && i < list.length; i++) same = list[i].id === cards[i].id && list[i].backdrop === cards[i].backdrop && list[i].meta === cards[i].meta;
    if (same && photoUrl !== null) return;
    cards = list;
    st.page = Math.min(st.page, Math.max(0, list.length - 1));
    if (!list.length && st.zone === Z.HERO) st.zone = Z.SHELF;
    follow();
    updateFocus();
  }

  /** The focused Dock app's picks (tvOS shows the focused app's Top Shelf). */
  function appPicks() {
    var app = shelf[st.shelf], out = [];
    for (var i = 0; app && i < cards.length; i++) if (Data.appFor(cards[i].app) === app) out.push(i);
    return out;
  }

  /** The background for the focused app: its pick, or a wallpaper when it has none. */
  function follow(dir) {
    var own = appPicks();
    if (own.length) {
      showPage(own.indexOf(st.page) >= 0 ? st.page : own[0], dir || 0);
    } else {
      showWallpaper();
    }
  }

  /** A bundled aerial-style wallpaper (a new one each hour), with nothing over it. */
  function showWallpaper() {
    setClass(body, 'wallpaper', true);
    var url = 'aerials/' + AERIALS[Math.floor(Date.now() / 3600000) % AERIALS.length] + '.jpg';
    photo(url);
    backdrop(url);
  }

  /** Pick `index` on the Top Shelf: its photo cross-fades in, its logo top left, its details for full screen. */
  function showPage(index, dir) {
    st.page = index;
    setClass(body, 'wallpaper', false);
    var p = cards[index];
    photo(p ? Data.image(p.backdrop, 'original') : '');
    backdrop(p ? Data.image(p.backdrop, 'w300') : '');
    var dots = $('dots'), own = appPicks();
    dots.innerHTML = '';
    for (var d = 0; own.length > 1 && d < own.length; d++) setClass(el('span', '', dots), 'on', own[d] === index);
    var hero = $('hero');
    clearTimeout(heroTimer);
    if (!dir) { fillHero(p); return; }
    setClass(hero, 'out', true);
    heroTimer = setTimeout(function () {
      fillHero(cards[st.page]);
      setClass(hero, 'out', false);
    }, 220);
  }

  function fillHero(p) {
    var hero = $('hero'), watch = $('watch'), details = $('details');
    if (!p) { hero.children[1].textContent = ''; return; }
    // The title's own logo (TMDB's title treatment) when it has one; the title in type otherwise.
    var logo = hero.children[0], url = p.logo ? Data.image(p.logo, 'w500') : '';
    setClass(hero, 'has-logo', false);
    logo.onload = function () { if (logo.getAttribute('src') === url) setClass(hero, 'has-logo', true); };
    if (url) logo.setAttribute('src', url); else logo.removeAttribute('src');
    if (url && logo.complete && logo.naturalWidth) setClass(hero, 'has-logo', true);
    hero.children[1].textContent = p.title;
    var meta = details.children[0];
    meta.innerHTML = '';
    meta.appendChild(document.createTextNode(p.year ? (p.meta ? p.meta.replace(/ · /, ' · ' + p.year + ' · ') : p.year) : p.meta || ''));
    var badges = [];
    if (p.cert) badges.push(p.cert);
    if (p.rating > 0) badges.push('\u2605 ' + fmtRating(p.rating));
    var extra = typeof p.badges === 'string' ? p.badges.split(',') : p.badges || [];
    for (var i = 0; i < extra.length; i++) if (extra[i]) badges.push(extra[i]);
    for (var b = 0; b < badges.length; b++) el('span', 'badge', meta, badges[b]);
    details.children[1].textContent = p.overview || '';
    var app = Data.appFor(p.app);
    watch.innerHTML = '';
    watch.style.display = app ? '' : 'none';
    if (app) {
      sym('play', watch);
      watch.appendChild(document.createTextNode(tr('Play')));
    }
  }

  /** "TONIGHT" above the title, as on Android (Recs.periodLabel). */
  function periodLabel() {
    var h = new Date().getHours();
    return h >= 5 && h < 12 ? tr('THIS MORNING') : h >= 12 && h < 18 ? tr('THIS AFTERNOON') : tr('TONIGHT');
  }

  function fmtRating(v) {
    var t = Math.round(v * 10);
    return Math.floor(t / 10) + (I18N.base === 'en' ? '.' : ',') + t % 10;
  }

  /** The Top Shelf's photo, edge to edge, cross-faded. */
  function photo(url) {
    if (url === photoUrl) return;
    photoUrl = url;
    setClass(body, 'has-photo', !!url);
    var next = photoFront === 'photoA' ? 'photoB' : 'photoA';
    if (!url) {
      setClass($('photoA'), 'on', false);
      setClass($('photoB'), 'on', false);
      return;
    }
    var img = new Image();
    img.onload = function () {
      if (photoUrl !== url) return;
      $(next).style.backgroundImage = 'url(' + url + ')';
      setClass($(next), 'on', true);
      setClass($(photoFront), 'on', false);
      photoFront = next;
    };
    img.src = url;
  }

  /** ← → in full screen (or by itself): the focused app's next pick. */
  function page(dir) {
    var own = appPicks(), at = own.indexOf(st.page);
    if (own.length < 2) return false;
    showPage(own[(at + dir + own.length) % own.length], dir);
    return true;
  }

  var bgUrl = null, bgFront = 'bgA';

  /** The pick's photo drawn tiny and stretched (as soft as a heavy blur): behind the grid, and in the Dock's glass. */
  function backdrop(url) {
    if (url === bgUrl) return;
    bgUrl = url;
    var next = bgFront === 'bgA' ? 'bgB' : 'bgA';
    if (!url) {
      setClass($('bgA'), 'on', false);
      setClass($('bgB'), 'on', false);
      return;
    }
    var img = new Image();
    img.onload = function () {
      if (bgUrl !== url) return;
      var canvas = $(next), ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      setClass(canvas, 'on', true);
      setClass($(bgFront), 'on', false);
      bgFront = next;
      // The glass's copies (the Dock, Control Center), with Liquid Glass's color boost baked in.
      var glasses = [$('platter').firstChild, $('drawer').firstChild];
      for (var gi = 0; gi < glasses.length; gi++) {
        var gc = glasses[gi].getContext('2d');
        gc.drawImage(img, 0, 0, glasses[gi].width, glasses[gi].height);
        vivid(gc, glasses[gi].width, glasses[gi].height);
      }
    };
    img.src = url;
  }

  /** Saturation ×1.35 (DESIGN-TVOS.md §2) on a tiny canvas: old Chromium has no CSS filter for it on every TV. */
  function vivid(ctx, w, h) {
    try {
      var data = ctx.getImageData(0, 0, w, h), px = data.data;
      for (var i = 0; i < px.length; i += 4) {
        var l = 0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2];
        for (var c = 0; c < 3; c++) px[i + c] = Math.max(0, Math.min(255, (l + (px[i + c] - l) * 1.35) * 1.02));
      }
      ctx.putImageData(data, 0, 0);
    } catch (e) {}   // a photo from another origin can't be read back: it stays as it is
  }

  function renderClock() {
    var d = new Date(), c = $('clock');
    c.firstChild.textContent = formatDate('format|h:mm', d.getTime());
    c.lastChild.textContent = formatDate('format|EEEE, MMMM d', d.getTime());
    renderPill();
  }

  /** On the minute, so the clock never runs late (the 30-second refresh could, by up to 30 s). */
  function tickClock() {
    renderClock();
    setTimeout(tickClock, 60000 - Date.now() % 60000 + 50);
  }

  // MARK: - Shelf

  var shelfFirst = 0, tileStep = 0, tileLeft = 0;

  function renderShelf() {
    var n = Math.min(shelf.length, SHELF_VISIBLE), gap = TILE_GAP, pad = DOCK_PAD_X, tw = TILE_W, th = TILE_H;
    var width = n * tw + Math.max(0, n - 1) * gap, left = (1920 - width) / 2;
    var platter = $('platter');
    platter.style.display = n ? '' : 'none';
    platter.style.left = (left - pad) + 'px';
    platter.style.width = (width + 2 * pad) + 'px';
    platter.style.top = DOCK_TOP + 'px';
    platter.style.height = (th + 2 * DOCK_PAD_Y) + 'px';
    // The glass's copy of the photo sits where the photo is on screen.
    var bleed = platter.firstChild;
    bleed.style.left = -(left - pad) + 'px';
    bleed.style.top = -DOCK_TOP + 'px';
    var tiles = $('tiles');
    tiles.innerHTML = '';
    tileStep = tw + gap;
    tileLeft = left;
    shelfFirst = Math.max(0, Math.min(shelfFirst, shelf.length - n));
    for (var i = 0; i < shelf.length; i++) {
      var t = el('div', 'tile', tiles);
      t.style.top = (DOCK_TOP + DOCK_PAD_Y) + 'px';
      t.style.width = tw + 'px';
      t.style.height = th + 'px';
      face(shelf[i], t, wordFit(shelf[i].title, tw, 32));
      hover(t, Z.SHELF, i);
    }
    // More apps than fit: a small arrow inside the glass's edge on each side that has more, where no tile reaches
    // even focused (inside the screen's safe area, as on Android).
    var old = platter.querySelectorAll('.more');
    for (var o = 0; o < old.length; o++) platter.removeChild(old[o]);
    for (var a = 0; a < 2; a++) {
      var arrow = sym('chevron_right', platter);
      arrow.className += a ? ' more' : ' more back';
      arrow.style.left = (a ? width + 2 * pad - 13 : 13) - 8.5 + 'px';
      arrow.style.top = (DOCK_PAD_Y + th / 2 - 8.5) + 'px';
    }
    st.shelf = Math.min(st.shelf, Math.max(0, shelf.length - 1));
    placeTiles();
    if (st.zone !== Z.APPS) renderApps();   // the grid is always there: its first row peeks under the Dock
  }

  /** The focused app's name, under its tile. */
  function placeName() {
    var name = $('dockname'), on = st.zone === Z.SHELF && !!shelf[st.shelf];
    setClass(name, 'on', on);
    if (!on) return;
    name.textContent = shelf[st.shelf].title;
    name.style.left = (tileLeft + (st.shelf - shelfFirst) * tileStep + TILE_W / 2) + 'px';
  }

  /** The row slides (a transform on #tiles, which old TVs animate), and the apps it takes past the glass fade out. */
  function placeTiles() {
    var row = $('tiles'), tiles = row.children, n = Math.min(shelf.length, SHELF_VISIBLE);
    for (var i = 0; i < shelf.length; i++) {
      var shown = i >= shelfFirst && i < shelfFirst + n;
      tiles[i].style.left = (tileLeft + i * tileStep) + 'px';
      tiles[i].style.opacity = shown ? '' : '0';
      tiles[i].style.pointerEvents = shown ? '' : 'none';
    }
    row.style.webkitTransform = row.style.transform = 'translateX(' + (-shelfFirst * tileStep) + 'px)';
    var arrows = $('platter').querySelectorAll('.more');
    arrows[0].style.visibility = shelfFirst > 0 ? '' : 'hidden';
    arrows[1].style.visibility = shelfFirst + n < shelf.length ? '' : 'hidden';
  }

  /** Scrolls the shelf, one app at a time, so the focused app is shown. */
  function revealShelf() {
    var n = Math.min(shelf.length, SHELF_VISIBLE);
    var first = Math.max(0, Math.min(Math.max(shelfFirst, st.shelf - n + 1), Math.min(st.shelf, shelf.length - n)));
    if (first === shelfFirst || !shelf.length) return;
    shelfFirst = first;
    placeTiles();
  }

  // MARK: - Drawer

  var drawerItems = [], drawerInputs = 0;

  /** Control Center: the time, a 2-column bento of glass cards (the inputs, All Apps, the settings), the weather. */
  function renderDrawer() {
    var d = $('drawer').lastChild;
    d.innerHTML = '';
    drawerItems = [];
    for (var i = 0; i < inputs.length; i++) {
      var x = inputs[i];
      drawerItems.push({label: x.label || tr('Live TV'), symbol: x.symbol, off: !x.connected, run: launcher(x.id, x.label)});
    }
    drawerInputs = drawerItems.length;
    drawerItems.push({label: tr('All Apps'), symbol: 'apps', run: function () { closeDrawer(); openApps(0); }});
    drawerItems.push({label: tr('Home Settings'), symbol: 'settings', run: openSettings});
    if (TV.canOpenSettings) drawerItems.push({label: tr('TV Settings'), symbol: 'tv_settings', run: tvSettings});
    // Profiles first, as on tvOS: the avatars, the current one ringed; OK switches.
    var row = el('div', 'profiles', d), list = people(), P = list.length;
    for (var w = 0; w < P; w++) {
      var who = el('div', 'who' + (list[w] === person() ? ' on' : ''), row);
      who.style.left = (w * 116) + 'px';
      el('span', 'face', who).style.backgroundImage = avatarUrl(list[w]);
      el('span', 'name', who, list[w].name);
      hover(who, Z.DRAWER, w);
    }
    var rows = Math.min(4, Math.ceil(drawerItems.length / 2));
    drawerItems = drawerItems.slice(0, rows * 2);
    for (var k = 0; k < drawerItems.length; k++) {
      var item = drawerItems[k], key = el('div', 'key' + (item.off ? ' off' : ''), d);
      key.style.left = (k % 2) * 240 + 'px';
      key.style.top = (148 + Math.floor(k / 2) * 136) + 'px';
      sym(item.symbol, key);
      el('div', 'label', key, item.label);
      hover(key, Z.DRAWER, P + k);
    }
    var w = el('div', '', d);
    w.id = 'weather';
    renderPanels();
    updateFocus();
  }

  function renderPanels() {
    var w = $('weather');
    if (!w) return;
    w.innerHTML = '';
    w.style.display = Prefs.weather() ? '' : 'none';
    if (Prefs.weather()) renderWeather(w);
  }

  function renderWeather(card) {
    el('div', 'head', card, Data.city || tr('Weather'));
    var w = Data.weather;
    if (!w) {
      el('div', 'cond', card, tr('No weather data right now.')).style.top = '60px';
      return;
    }
    var now = el('div', 'now', card);
    sym(Data.symbol(w.code, w.isDay), now);
    el('span', 'temp', now, Data.degrees(w.temp));
    el('div', 'cond', card, Data.condition(w.code, w.isDay));
    el('div', 'range', card, trf('H:{max}', {max: Data.degrees(w.max)}) + '  ' + trf('L:{min}', {min: Data.degrees(w.min)}));
    // The next 4 hours (or days, with Home Settings → Forecast).
    var days = Prefs.forecast() === 'days' && w.days && w.days.length > 0, list = days ? w.days : w.hours, n = 4;
    var hours = el('div', 'hours', card), hw = 278 / n;
    for (var i = 0; list && i < list.length && i < n; i++) {
      var h = el('div', 'hour', hours), x = list[i];
      h.style.left = (hw * i) + 'px';
      h.style.width = hw + 'px';
      el('div', 't', h, days ? Data.weekday(x.at) : Data.hour(x.at));
      sym(Data.symbol(x.code, days || x.day), h);
      el('div', 'd', h, Data.degrees(days ? x.max : x.temp));
    }
  }

  var drawerFrom = Z.SHELF;

  function openDrawer() {
    if (!drawerItems.length) return;
    drawerFrom = st.zone === Z.DRAWER ? drawerFrom : st.zone;
    st.zone = Z.DRAWER;
    st.drawer = Math.max(0, Math.min(people().length - 1, Prefs.profile()));
    renderPill();
    updateFocus();
  }

  /** Back where the focus was (the button that opened it, or the Dock). */
  function closeDrawer() {
    st.zone = drawerFrom === Z.TOP || drawerFrom === Z.HERO ? drawerFrom : Z.SHELF;
    updateFocus();
  }

  // MARK: - All Apps

  function renderApps() {
    var sheet = $('apps');
    sheet.firstChild.textContent = tr('All Apps');
    var grid = sheet.lastChild;
    grid.innerHTML = '';
    for (var i = 0; i < others.length; i++) {
      var r = Math.floor(i / COLS), c = i % COLS, cell = el('div', 'cell', grid);
      cell.style.left = (GRID_LEFT + c * (TILE_W + TILE_GAP)) + 'px';
      cell.style.top = (GRID_TOP + r * GRID_ROW) + 'px';
      var f = el('div', 'face', cell);
      face(others[i], f, wordFit(others[i].title, TILE_W, 34));
      el('div', 'label', cell, others[i].title);
      hover(cell, Z.APPS, i);
    }
  }

  function openApps(index) {
    renderApps();
    st.zone = Z.APPS;
    st.grid = Math.max(0, Math.min(others.length - 1, index || 0));
    setClass($('apps'), 'open', true);
    fitLogos($('apps'));
    updateFocus();
  }

  function closeSheets() {
    setClass($('apps'), 'open', false);
    setClass($('settings'), 'open', false);
    setClass($('shelfedit'), 'open', false);
    setClass($('langpick'), 'open', false);
    setClass($('about'), 'open', false);
    langHint = null;
    st.zone = Z.SHELF;
    updateFocus();
  }

  // MARK: - Home Settings

  function settingRows() {
    var unit = Prefs.temperature(), theme = Prefs.theme();
    var auto = tr('Automatic'), picked = pickedLanguage();
    var rows = [
      {label: tr('Language'), value: picked === 'auto' ? auto : I18N.languageName(picked), opens: true, language: true,
        step: function (d) { if (d >= 0) openLanguages(); }},
      {label: tr('Temperature'), value: unit === 'C' ? '°C' : unit === 'F' ? '°F' : tr('unit|Automatic'),
        step: function (d) { cycle('temperature', ['auto', 'C', 'F'], unit, d); renderPanels(); }},
      {label: tr('Theme'), value: theme === 'light' ? tr('Light') : theme === 'dark' ? tr('Dark') : auto,
        step: function (d) { cycle('theme', ['auto', 'light', 'dark'], theme, d); checkNight(); }},
      {label: tr('Watch from the screensaver'), toggle: Prefs.saverWatch(),
        step: function () { Prefs.set('saverWatch', !Prefs.saverWatch()); }},
      {label: tr('Weather widget'), toggle: Prefs.weather(),
        step: function () { Prefs.set('weather', !Prefs.weather()); renderPanels(); }},
      {label: tr('Forecast'), value: Prefs.forecast() === 'days' ? tr('Next days') : tr('Next hours'),
        step: function (d) { cycle('forecast', ['hours', 'days'], Prefs.forecast(), d); renderPanels(); }},
      {label: tr('Quotes widget'), toggle: Prefs.quotes(),
        step: function () { Prefs.set('quotes', !Prefs.quotes()); Data.settingsChanged(); }}
    ];
    var names = ['Bitcoin', 'Ethereum', 'Solana', tr('Dollar')];
    var count = Data.currency === 'BRL' ? 4 : 3;
    for (var i = 0; i < count; i++) {
      (function (i) {
        rows.push({label: names[i], toggle: Prefs.quotes() && Prefs.coin(i), dim: !Prefs.quotes(),
          step: function () { if (Prefs.quotes()) { Prefs.setCoin(i, !Prefs.coin(i)); Data.settingsChanged(); } }});
      })(i);
    }
    rows.push({label: tr('Shelf'), value: '›', shelf: true, step: function (d) { if (d >= 0) openShelfEdit(); }});
    rows.push({label: tr('About this TV'), value: '›', about: true, step: function (d) { if (d >= 0) openAbout(); }});
    if (TV.canOpenSettings) rows.push({label: tr('TV Settings'), value: '›', step: function (d) { if (d >= 0) tvSettings(); }});
    return rows;
  }

  function cycle(name, values, current, d) {
    var i = values.indexOf(current);
    Prefs.set(name, values[(i + (d || 1) + values.length) % values.length]);
  }

  var rowsNow = [];

  function renderSettings() {
    var box = $('settings').firstChild;
    box.innerHTML = '';
    var title = el('div', 'title', box);
    sym('settings', title);
    title.appendChild(document.createTextNode(tr('Home Settings')));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    rowsNow = settingRows();
    list.style.height = Math.min(rowsNow.length, 7) * 78 + 'px';
    for (var i = 0; i < rowsNow.length; i++) {
      var r = rowsNow[i], row = el('div', 'row' + (r.dim ? ' dim' : ''), holder, r.label);
      if (r.toggle !== undefined) setClass(el('div', 'toggle', row), 'on', r.toggle);
      else {
        var v = el('span', 'value', row);
        if (r.value === '›') v.textContent = '›';
        else if (r.opens) { v.appendChild(document.createTextNode(r.value)); el('span', 'chev', v, '›'); }
        else { el('span', 'chev', v, '‹'); v.appendChild(document.createTextNode(r.value)); el('span', 'chev', v, '›'); }
      }
      hover(row, Z.SETTINGS, i);
    }
    el('div', 'hint', box, tr('OK or the arrows change the value.'));
    updateFocus();
  }

  function openSettings() {
    st.zone = Z.SETTINGS;
    st.row = 0;
    renderSettings();
    setClass($('settings'), 'open', true);
    updateFocus();
  }

  // MARK: - Language: a list of every catalogue in its own name, as on Android (moving through it doesn't switch the app)

  var langHint = null;

  function languageChoices() {
    return ['auto'].concat(I18N.languages());
  }

  /** The language in use: "auto", or the catalogue the setting resolved to ("pt-BR" in config.properties is "pt"). */
  function pickedLanguage() {
    return Prefs.language() === 'auto' ? 'auto' : I18N.lang;
  }

  function renderLanguages() {
    var box = $('langpick').firstChild, choices = languageChoices(), current = choices.indexOf(pickedLanguage());
    box.innerHTML = '';
    el('div', 'title', box, tr('Language'));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    list.style.height = Math.min(choices.length, 7) * 78 + 'px';
    for (var i = 0; i < choices.length; i++) {
      var row = el('div', 'row', holder, choices[i] === 'auto' ? tr('Automatic') : I18N.languageName(choices[i]));
      if (i === current) sym('check', el('span', 'value', row));
      hover(row, Z.LANG, i);
    }
    langHint = el('div', 'hint', box);
    updateFocus();
  }

  function openLanguages() {
    setClass($('settings'), 'open', false);
    st.zone = Z.LANG;
    st.lang = Math.max(0, languageChoices().indexOf(pickedLanguage()));
    renderLanguages();
    setClass($('langpick'), 'open', true);
    updateFocus();
  }

  /** OK: the language under the focus; Back: none. Either way, back to Home Settings, on the Language row. */
  function closeLanguages(pick) {
    setClass($('langpick'), 'open', false);
    langHint = null;
    var code = languageChoices()[st.lang];
    if (pick && code !== pickedLanguage()) {
      Prefs.set('language', code);
      I18N.set(Prefs.language());
      renderAll();
      Data.refresh();   // the picks' titles and genres come in the new language
    }
    openSettings();
    for (var i = 0; i < rowsNow.length; i++) if (rowsNow[i].language) st.row = i;
    updateFocus();
  }

  // MARK: - About this TV: what to quote when reporting a problem, without LG's or Samsung's slow menus

  function renderAbout(info) {
    var box = $('about').firstChild;
    box.innerHTML = '';
    el('div', 'title', box, tr('About this TV'));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    var rows = [
      [tr('Model'), info && info.model], [tr('Firmware'), info && info.firmware], [tr('System'), info && info.system],
      [tr('IP address'), info && info.ip], [tr('Time zone'), TV.zone()], ['Free My TV', info && info.version]
    ];
    for (var i = 0; i < rows.length; i++) {
      var row = el('div', 'row', holder), label = el('span', '', row, rows[i][0]);
      fitValue(row, label, el('span', 'value', row, info ? rows[i][1] || tr('Unavailable') : '…'));
    }
    el('div', 'hint', box, tr('Back closes.'));
  }

  /** A long value (Samsung's firmware) gets smaller instead of running over its label, and ends in "…" if even the smallest size is too wide. */
  function fitValue(row, label, value) {
    var room = row.clientWidth - label.offsetWidth - 2 * 24 - 24;   // the row's padding, and a gap after the label
    for (var size = 30; value.offsetWidth > room && size > 18; size -= 2) value.style.fontSize = size - 2 + 'px';
    if (value.offsetWidth > room) { value.style.maxWidth = room + 'px'; setClass(value, 'cut', true); }
  }

  function openAbout() {
    setClass($('settings'), 'open', false);
    st.zone = Z.ABOUT;
    renderAbout(null);
    setClass($('about'), 'open', true);
    updateFocus();
    TV.about(function (info) { if (st.zone === Z.ABOUT) renderAbout(info); });
  }

  function closeAbout() {
    setClass($('about'), 'open', false);
    openSettings();
    for (var i = 0; i < rowsNow.length; i++) if (rowsNow[i].about) st.row = i;
    updateFocus();
  }

  // MARK: - Shelf editor: which apps, in what order

  function editList() {
    var ids = shelf.map(function (a) { return a.id; });
    return {ids: ids, rows: apps.slice().sort(function (a, b) {
      var ia = ids.indexOf(a.id), ib = ids.indexOf(b.id);
      if (ia >= 0 || ib >= 0) return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
      return a.title.toLowerCase() < b.title.toLowerCase() ? -1 : 1;
    })};
  }

  function renderShelfEdit() {
    var box = $('shelfedit').firstChild, e = editList();
    box.innerHTML = '';
    var title = el('div', 'title', box);
    sym('apps', title);
    title.appendChild(document.createTextNode(tr('Shelf')));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    list.style.height = Math.min(e.rows.length, 7) * 78 + 'px';
    for (var i = 0; i < e.rows.length; i++) {
      var a = e.rows[i], at = e.ids.indexOf(a.id), row = el('div', 'row', holder, a.title);
      var place = el('span', 'value', row, at >= 0 ? '#' + (at + 1) : tr('shelf|Off'));
      if (at >= 0) sym('check', place).style.marginLeft = '16px';   // on the shelf: a check, as in the pickers
      hover(row, Z.EDIT, i);
    }
    el('div', 'hint', box, tr('OK adds or removes an app. ← → change the order.'));
    updateFocus();
  }

  function openShelfEdit() {
    setClass($('settings'), 'open', false);
    st.zone = Z.EDIT;
    st.edit = 0;
    renderShelfEdit();
    setClass($('shelfedit'), 'open', true);
    updateFocus();
  }

  function editShelf(dx) {
    var e = editList(), app = e.rows[st.edit];
    if (!app) return;
    var ids = e.ids.slice(), at = ids.indexOf(app.id);
    if (dx === 0) {
      if (at >= 0) ids.splice(at, 1);
      else ids.push(app.id);
    } else {
      if (at < 0) return;
      var to = Math.max(0, Math.min(ids.length - 1, at + dx));
      ids.splice(at, 1);
      ids.splice(to, 0, app.id);
    }
    Prefs.set('shelf', ids);
    pickShelf();
    renderShelf();
    var again = editList();
    for (var i = 0; i < again.rows.length; i++) if (again.rows[i].id === app.id) st.edit = i;
    renderShelfEdit();
  }

  // MARK: - Opening things

  function launcher(id, name) {
    return function () {
      TV.launch(id, null, function (ok) {
        if (!ok) toast(trf("Couldn't open {app}.", {app: name || id}));
      });
    };
  }

  function tvSettings() {
    TV.settings(function (ok) {
      if (!ok) toast(tr("Couldn't open TV Settings"));
    });
  }

  function liveTV() {
    for (var i = 0; i < inputs.length; i++) if (inputs[i].key === 'live') return inputs[i];
    return null;
  }

  function activate() {
    switch (st.zone) {
      case Z.TOP:
        openDrawer();
        return;
      case Z.HERO:
        var p = cards[st.page], app = p && Data.appFor(p.app);
        if (app) launcher(app.id, app.title)();
        return;
      case Z.SHELF:
        if (shelf[st.shelf]) launcher(shelf[st.shelf].id, shelf[st.shelf].title)();
        return;
      case Z.DRAWER:
        if (st.drawer < people().length) {   // a profile: switch to it
          Prefs.set('profile', st.drawer);
          renderPill();
          renderDrawer();
          return;
        }
        if (drawerItems[st.drawer - people().length]) drawerItems[st.drawer - people().length].run();
        return;
      case Z.APPS:
        if (others[st.grid]) launcher(others[st.grid].id, others[st.grid].title)();
        return;
      case Z.SETTINGS:
        if (rowsNow[st.row]) { rowsNow[st.row].step(1); if (st.zone === Z.SETTINGS) renderSettings(); }
        return;
      case Z.EDIT:
        editShelf(0);
        return;
      case Z.LANG:
        closeLanguages(true);
        return;
      case Z.ABOUT:
        closeAbout();
        return;
    }
  }

  function move(dx, dy) {
    if (dx) lean = dx;
    switch (st.zone) {
      case Z.SHELF:
        if (dx) {
          var was = st.shelf;
          st.shelf = Math.max(0, Math.min(shelf.length - 1, st.shelf + dx));
          if (st.shelf !== was) follow(dx);
        } else if (dy > 0) {
          // ↓: the app grid comes up, on the app under this one.
          if (others.length) { openApps(Math.max(0, Math.min(COLS - 1, st.shelf - shelfFirst))); return; }
          openDrawer();
          return;
        } else if (dy < 0) {
          // ↑ on a pick: full screen; on a wallpaper: the pill.
          if (appPicks().length) st.zone = Z.HERO;
          else { st.zone = Z.TOP; st.top = 3; }
        }
        break;
      case Z.HERO:
        if (dx) page(dx);
        else if (dy < 0) { st.zone = Z.TOP; st.top = 3; }
        else if (shelf.length) st.zone = Z.SHELF;
        break;
      case Z.TOP:
        if (dy > 0) st.zone = Z.SHELF;
        break;
      case Z.DRAWER:
        // The profiles' row on top, then the bento's two columns: ← from the left edge closes it, as on tvOS.
        var P = people().length;
        if (st.drawer < P) {
          if (dx < 0 && st.drawer === 0) { closeDrawer(); return; }
          if (dx) st.drawer = Math.max(0, Math.min(P - 1, st.drawer + dx));
          else if (dy > 0 && drawerItems.length) st.drawer = P;
          break;
        }
        var k = st.drawer - P;
        if (dx < 0 && k % 2 === 0) { closeDrawer(); return; }
        if (dx && k + dx >= 0 && k + dx < drawerItems.length && Math.floor((k + dx) / 2) === Math.floor(k / 2)) st.drawer += dx;
        else if (dy < 0 && k < 2) st.drawer = Math.min(P - 1, k % 2 ? P - 1 : 0);
        else if (dy && k + 2 * dy >= 0 && k + 2 * dy < drawerItems.length) st.drawer += 2 * dy;
        break;
      case Z.APPS:
        if (dy < 0 && st.grid < COLS) {
          // ↑ from the first row: back to the Dock, on the app above.
          var col = st.grid % COLS;
          closeSheets();
          if (shelf.length) st.shelf = Math.min(shelf.length - 1, shelfFirst + col);
          break;
        }
        var g = st.grid + dx + dy * COLS;
        if (g >= 0 && g < others.length && (dx === 0 || Math.floor(g / COLS) === Math.floor(st.grid / COLS))) st.grid = g;
        break;
      case Z.SETTINGS:
        if (dy) st.row = Math.max(0, Math.min(rowsNow.length - 1, st.row + dy));
        else if (dx && rowsNow[st.row]) {
          var r = rowsNow[st.row];
          if (r.value !== '›') { r.step(dx); if (st.zone === Z.SETTINGS) renderSettings(); }
        }
        break;
      case Z.EDIT:
        if (dy) st.edit = Math.max(0, Math.min(apps.length - 1, st.edit + dy));
        else if (dx) editShelf(dx);
        break;
      case Z.LANG:
        if (dy) st.lang = Math.max(0, Math.min(languageChoices().length - 1, st.lang + dy));
        break;
    }
    updateFocus();
  }

  function back() {
    if (st.zone === Z.EDIT) {
      setClass($('shelfedit'), 'open', false);
      openSettings();
      for (var i = 0; i < rowsNow.length; i++) if (rowsNow[i].shelf) st.row = i;   // back on the Shelf row, whatever follows it
      updateFocus();
      return;
    }
    if (st.zone === Z.LANG) { closeLanguages(false); return; }
    if (st.zone === Z.ABOUT) { closeAbout(); return; }
    if (sheetOpen()) { closeSheets(); return; }
    if (st.zone === Z.DRAWER) { closeDrawer(); return; }
    if (st.zone !== Z.SHELF && shelf.length) { st.zone = Z.SHELF; updateFocus(); return; }
    TV.back();
  }

  /** The pointer focuses what it's over (the Magic Remote, or a mouse when previewing). */
  function hover(node, zone, index) {
    node.onmouseover = function () {
      if (saverOn || (sheetOpen() !== (zone === Z.APPS || zone === Z.SETTINGS || zone === Z.EDIT || zone === Z.LANG || zone === Z.ABOUT))) return;
      if (zone === Z.DRAWER && st.zone !== Z.DRAWER) return;
      if (zone !== Z.DRAWER && st.zone === Z.DRAWER && zone !== Z.SHELF) return;
      if (zone !== Z.DRAWER && st.zone === Z.DRAWER) return;
      st.zone = zone;
      if (zone === Z.TOP) st.top = index;
      else if (zone === Z.SHELF) st.shelf = index;
      else if (zone === Z.DRAWER) st.drawer = index;
      else if (zone === Z.APPS) st.grid = index;
      else if (zone === Z.SETTINGS) st.row = index;
      else if (zone === Z.EDIT) st.edit = index;
      else if (zone === Z.LANG) st.lang = index;
      updateFocus();
    };
    node.onclick = function () { node.onmouseover(); activate(); };
  }

  // MARK: - Toast

  var toastTimer = 0;
  function toast(text) {
    var t = $('toast');
    t.textContent = text;
    setClass(t, 'on', true);
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { setClass(t, 'on', false); }, 6000);
  }

  // MARK: - Night, clock, screensaver

  var lastNight = null;

  function checkNight() {
    var night = Data.isNight(Date.now());
    setClass(body, 'night', night);
    if (night === lastNight) return;
    lastNight = night;
    document.documentElement.style.background = night ? '#0B0B0D' : '';
    try { localStorage.setItem('fmtv.night', night ? 'true' : 'false'); } catch (e) {}   // for index.html, next start
  }

  var saverOn = false, idleAt = Date.now(), saverIndex = 0, saverTimer = 0, saverPick = null, SAVER_AFTER = 5 * 60000;

  /** The screensaver's photos: picks with an app on the TV. */
  function saverPhotos() {
    return Data.showcase().filter(function (p) { return p.backdrop && Data.appFor(p.app); });
  }

  function showSaver() {
    var photos = saverPhotos();
    saverOn = true;
    saverPick = null;
    var s = $('saver');
    s.innerHTML = '';
    var a = el('div', 'photo', s), b = el('div', 'photo', s);
    el('div', 'shade', s);
    var clock = el('div', 'clock', s);
    el('div', 'time', clock);
    el('div', 'date', clock);
    el('div', 'title', clock);
    var hint = el('div', 'hint', clock);
    var front = a;
    function next() {
      if (!saverOn) return;
      var p = photos.length ? photos[saverIndex++ % photos.length] : null;
      var back = front === a ? b : a;
      if (p) {
        back.style.backgroundImage = 'url(' + Data.image(p.backdrop, 'original') + ')';
        back.style.webkitTransform = back.style.transform = 'scale(1.0)';
        clock.children[2].textContent = p.title;
        saverPick = p;
        // With Watch from the screensaver on, OK opens this title's app (any other key only closes).
        var app = Prefs.saverWatch() && Data.appFor(p.app);
        hint.textContent = app ? trf('Press OK to watch on {app} · any other button to close', {app: app.title}) : '';
        setTimeout(function () {
          setClass(back, 'on', true);
          setClass(front, 'on', false);
          back.style.webkitTransform = back.style.transform = 'scale(1.08) translateX(-1.5%)';
          front = back;
        }, 60);
      }
      tickSaverClock(clock);
      saverTimer = setTimeout(next, 12000);
    }
    setClass(s, 'on', true);
    next();
  }

  function tickSaverClock(clock) {
    var d = new Date();
    clock.firstChild.textContent = formatDate('format|h:mm', d.getTime());
    clock.children[1].textContent = formatDate('format|EEEE, MMMM d', d.getTime());
  }

  function hideSaver() {
    saverOn = false;
    clearTimeout(saverTimer);
    setClass($('saver'), 'on', false);
  }

  function poke(ok) {
    idleAt = Date.now();
    if (!saverOn) return false;
    var app = ok && saverPick && Prefs.saverWatch() && Data.appFor(saverPick.app);
    hideSaver();
    if (app) launcher(app.id, app.title)();
    return true;
  }

  // MARK: - Keys

  document.addEventListener('keydown', function (e) {
    var k = e.keyCode;
    if (poke(k === TV.keys.ok)) { e.preventDefault(); return; }   // the first press only wakes the screen (or OK watches)
    if (k === TV.keys.left) move(-1, 0);
    else if (k === TV.keys.right) move(1, 0);
    else if (k === TV.keys.up) move(0, -1);
    else if (k === TV.keys.down) move(0, 1);
    else if (k === TV.keys.ok) activate();
    else if (k === TV.keys.back || k === 27 || k === 8) back();
    else return;
    e.preventDefault();
  });
  document.addEventListener('mousemove', function () { poke(); });

  // MARK: - Start

  function renderAll() {
    renderTop();
    renderQuotes();
    cards = [];
    renderCards();
    renderShelf();
    renderDrawer();
    renderClock();
    checkNight();
    if (st.zone === Z.SETTINGS) renderSettings();
    updateFocus();
  }

  /** hide_apps in config.properties: left out of the shelf, All Apps and the picks, by id or name (part of it). */
  var HIDE = (CONFIG.hideApps || []).map(function (s) { return s.toLowerCase(); });
  function hidden(app) {
    var id = (app.id || '').toLowerCase(), title = (app.title || '').toLowerCase();
    for (var i = 0; i < HIDE.length; i++) if (id.indexOf(HIDE[i]) >= 0 || title.indexOf(HIDE[i]) >= 0) return true;
    return false;
  }

  function loadApps(done) {
    TV.apps(function (list) {
      apps = list.filter(function (a) { return !hidden(a); });
      Data.setApps(apps);
      pickShelf();
      TV.inputs(function (list2) {
        inputs = list2;
        if (done) done();
      });
    });
  }

  /** Back to the home screen (the app was opened again): the shelf, everything closed, the first tab. */
  function reset() {
    hideSaver();
    closeSheets();
    st.zone = Z.SHELF;
    st.shelf = 0;
    follow();
    updateFocus();
  }

  Data.onChange(function () {
    renderQuotes();
    renderPill();
    renderCards();
    renderPanels();
    checkNight();
  });

  function reopened() {
    // LG: with handlesRelaunch the app has to come to the front itself, or opening it again while it runs shows nothing.
    if (window.PalmSystem && window.PalmSystem.activate) window.PalmSystem.activate();
    reset();
    loadApps(function () { renderShelf(); renderDrawer(); Data.refresh(); });
  }

  window.addEventListener('resize', fit);
  document.addEventListener('webOSRelaunch', reopened);   // LG: opened again while running
  window.addEventListener('appcontrol', reopened);        // Samsung: the same
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) { hideSaver(); return; }
    idleAt = Date.now();
    loadApps(function () { renderShelf(); renderDrawer(); updateFocus(); Data.refresh(); });
  });

  I18N.set(Prefs.language());
  checkNight();   // now, from the last forecast: not after the TV lists its apps, with the day look until then
  // LG's screensaver waits only when ours will show: not under a sheet (ours waits there), and not without photos.
  TV.holdScreenSaver(function () { return !sheetOpen() && saverPhotos().length > 0; });
  fit();
  renderTop();
  tickClock();
  loadApps(function () {
    renderAll();
    Data.refresh();
  });
  // The Top Shelf pages by itself while the focus is elsewhere (and not on an app whose pick it shows).
  setInterval(function () {
    if (!saverOn && st.zone === Z.SHELF) page(1);   // the focused app's picks, when it has several
  }, 8000);
  setInterval(function () {
    checkNight();
    Data.refresh();
    if (!saverOn && !sheetOpen() && Date.now() - idleAt > SAVER_AFTER) showSaver();
    if (saverOn) tickSaverClock($('saver').querySelector('.clock'));
  }, 30000);

  // For the browser preview and tests.
  window.FreeMyTV = {state: st, move: move, activate: activate, back: back, showSaver: showSaver, hideSaver: hideSaver, toast: toast};
})();
