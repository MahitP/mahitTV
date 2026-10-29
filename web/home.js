/*
 * The home screen for web-app TVs (LG webOS, Samsung Tizen), with the Android app's design and behavior (HomeView,
 * DESIGN-TVOS.md), after Apple TV's:
 *  - the Top Shelf: this hour's pick edge to edge, its title, rating, details and synopsis at the bottom left, page
 *    dots; ← → page through the picks, and it pages by itself;
 *  - a glass pill with the time and the weather and icon-only glass buttons at the top right (Live TV, HDMI Inputs,
 *    which opens the drawer with the inputs and the weather, and Settings);
 *  - the glass Dock with the apps; ↓ brings up the other apps as a grid; ↑ or Back goes back;
 *  - a glass tab bar at the top centre, TV · Home · Welly: Home is the house and its lights (rooms.js), Welly the
 *    assistant (welly.js); both draw over the background, frosted and dimmed, and TV stays the plain Apple TV screen;
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
    ['applemusic', /apple.?music|com\.apple\.music/i, /apple music/i, ['#FA586A', '#FA233B']],
    ['spotify', /spotify/, /spotify/i, ['#202020', '#050505']],
    ['plex', /^cdp-30$|plex/i, /^plex$/i, ['#282A2D', '#121315']],
    ['crunchyroll', /crunchyroll/, /crunchyroll/i, ['#FF8A1F', '#E8590C']]
  ];
  // These apps are media for Dock policy but their album art must only come
  // from active Now Playing; they never borrow a film/TV recommendation.
  var MUSIC_KEYS = {applemusic: true, spotify: true};
  // Apps shown at once, as on Android: 8 (today's smallest tiles, 178 wide); the shelf scrolls for the rest. The
  // built-in list keeps 8 too.
  // The Dock shows 5 at once (tvOS's 5:3 tiles, 300 × 180) and scrolls for the rest.
  // As in Apple's photo (DESIGN-TVOS.md §1): 6 columns of 251 × 150, 49 apart, 85 px margins; the Dock's glass pads them
  // 48 × 40 from y 782; the grid's rows are 250 apart, its first row peeking at y 1052 until ↓ lifts the page 722.
  var SHELF_VISIBLE = 6, FEW_APPS = 8, TILE_W = 251, TILE_H = 150, TILE_GAP = 49, DOCK_PAD_X = 48, DOCK_PAD_Y = 40, DOCK_TOP = 782;
  var COLS = 6, GRID_LIFT = 722, GRID_ROW = 250, GRID_TOP = 330, GRID_LEFT = 85;
  var WALLPAPERS = ['horizon:auto', 'aerial:yosemite', 'aerial:iceland', 'aerial:north-america-aurora', 'aerial:new-york'];

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

  // MARK: - Motion (ANIMATIONS-SPEC.md): springs from motion.js drive every transform that can change mid-flight

  var P = Motion.P, BUMP = Motion.BUMP_VELOCITY, scrubbing = false, lastMove = {dx: 0, dy: 0, at: 0};

  function spring(preset, value) { return new Motion.Spring(preset, value || 0); }
  // Styles are written only when they change: a write that changes nothing still costs a style pass per frame.
  function T(e, t) {
    if (!e || e._t === t) return;
    e._t = t;
    e.style.webkitTransform = e.style.transform = t;
  }
  function A(e, a) {
    if (!e) return;
    var v = String(r4(Motion.clamp(a, 0, 1)));
    if (e._o === v) return;
    e._o = v;
    e.style.opacity = v;
  }
  /** Hidden once fully faded, so a transparent layer doesn't take the pointer or cost the GPU. */
  function V(e, a) {
    if (!e) return;
    var v = a < 0.002 ? 'hidden' : 'visible';
    if (e._v === v) return;
    e._v = v;
    e.style.visibility = v;
  }
  function r2(v) { return Math.round(v * 100) / 100; }
  function r4(v) { return Math.round(v * 10000) / 10000; }

  /** While an arrow is held, focus moves faster and the tilt, sheen and labels wait (ANIMATIONS-SPEC.md §5.1). */
  var scrub = spring([0.2, 1]);

  var fxAll = [];

  /**
   * One focusable thing's motion: focus (scale, lift shadow, label, sheen), the OK press squish, the arrival tilt
   * with its emblem parallax, the dead-end bump (through the rubber band), and a launch zoom. Its owner can add a
   * stagger offset (ex, ey) and opacity (ea).
   */
  function Fx(node, o) {
    o = o || {};
    this.node = node;
    this.grow = o.grow || 0;
    this.squish = o.squish === undefined ? 0.04 : o.squish;
    this.f = spring(scrubbing ? P.FAST_FOCUS : P.SNAPPY_FOCUS);
    this.p = spring(P.MICRO_PRESS);
    this.nx = spring(P.BOUNDARY_BUMP).px();
    this.ny = spring(P.BOUNDARY_BUMP).px();
    this.tilt = !!o.tilt;
    this.ry = spring(P.TILT).deg();
    this.rx = spring(P.TILT).deg();
    this.shadow = o.shadow || null;
    this.img = o.img || null;
    this.sheen = o.sheen || null;
    this.label = o.label || null;
    this.avatar = o.avatar || null;
    this.lab = spring(P.SNAPPY_FOCUS);
    this.launch = spring(P.SHEET_OPEN);
    this.ex = 0;
    this.ey = 0;
    this.ea = -1;
    this.on = false;
    this.alive = true;
    this.dirty = true;
    fxAll.push(this);
    Motion.kick();
  }

  Fx.prototype.focus = function (on) {
    if (on === this.on) return;
    this.on = on;
    this.f.set(on ? 1 : 0);
    // The label comes in with the focus and leaves fast (120 ms), so names never overlap when scrubbing.
    this.lab.tune(on ? P.SNAPPY_FOCUS : [0.12, 1]).set(on ? 1 : 0);
    if (!on) this.p.set(0);
    if (on && Date.now() - lastMove.at < 120) this.arrive(lastMove.dx, lastMove.dy);
  };

  /** Arrival: a 3° lean toward where it was reached from, settling flat (an impulse on the tilt springs). */
  Fx.prototype.arrive = function (dx, dy) {
    if (!this.tilt || scrubbing) return;
    this.ry.impulse(-90 * dx);
    this.rx.impulse(90 * dy);
  };

  /** A dead end: a tug the way it was pushed, through UIScrollView's rubber band, and back. */
  Fx.prototype.bump = function (dx, dy) {
    this.nx.impulse(dx * BUMP);
    this.ny.impulse(dy * BUMP);
  };

  Fx.prototype.step = function (dt) {
    var m = this.f.step(dt);
    m = this.p.step(dt) || m;
    m = this.nx.step(dt) || m;
    m = this.ny.step(dt) || m;
    m = this.ry.step(dt) || m;
    m = this.rx.step(dt) || m;
    m = this.lab.step(dt) || m;
    m = this.launch.step(dt) || m;
    if (m || this.dirty) this.draw();
    this.dirty = false;
    return m;
  };

  Fx.prototype.draw = function () {
    var f = this.f.x, pr = this.p.x, l = this.launch.x;
    var s = (1 + this.grow * f - this.squish * pr) * (1 + 1.4 * l);
    var x = this.ex + Motion.rubber(this.nx.x), y = this.ey + Motion.rubber(this.ny.x), t = '';
    if (x || y) t = 'translate(' + r2(x) + 'px,' + r2(y) + 'px) ';
    var ry = this.ry.x, rx = this.rx.x;
    if (ry || rx) t += 'perspective(900px) rotateY(' + r2(ry) + 'deg) rotateX(' + r2(rx) + 'deg) ';
    T(this.node, t + 'scale(' + r4(s) + ')');
    var a = (this.ea >= 0 ? this.ea : 1) * (1 - l);
    if (this.ea >= 0 || l > 0.0001) A(this.node, a);
    else if (this.node._o !== '') { this.node._o = ''; this.node.style.opacity = ''; }
    if (this.shadow) {
      A(this.shadow, f * (1 - 0.45 * pr) * a);
      T(this.shadow, 'translate(' + r2(x) + 'px,' + r2(y - 16 * pr) + 'px) scale(' + r4(s) + ')');
    }
    // Layers: the emblem drifts with the tilt (6 px at 3°) and grows 3%, the sheen the other way.
    if (this.img) T(this.img, 'translate(-50%, -50%) translateX(' + r2(-2 * ry) + 'px) scale(' + r4(1 + 0.03 * f) + ')');
    if (this.sheen) {
      A(this.sheen, f * (1 - scrub.x));
      T(this.sheen, 'translateX(' + r2(4 * ry) + 'px)');
    }
    if (this.label) {
      A(this.label, this.lab.x * (1 - scrub.x));
      T(this.label, 'translateY(' + r2(6 * (1 - this.lab.x)) + 'px)');
    }
    if (this.avatar) T(this.avatar, 'scale(' + r4(1 + 0.12 * f) + ')');
  };

  function killFx(list) {
    for (var i = 0; i < list.length; i++) list[i].alive = false;
    list.length = 0;
  }

  function setScrub(on) {
    if (on === scrubbing) return;
    scrubbing = on;
    scrub.set(on ? 1 : 0);
    for (var i = 0; i < fxAll.length; i++) fxAll[i].f.tune(on ? P.FAST_FOCUS : P.SNAPPY_FOCUS);
  }

  // The page's springs. lift: how far the page has risen for the grid (px); tab: TV 0, Home 1, Welly 2 (between them
  // mid-swap); drawer: Control Center 0..1; full: the Top Shelf's full screen 0..1; dock: the Dock's scroll (px);
  // par: the art's counter-parallax (px); launch/wake: the page receding for an app, and back from the screensaver.
  var mv = {
    lift: spring(P.SPATIAL_SCROLL).px(), tab: spring(P.TAB_SWAP), drawer: spring(P.SHEET_OPEN), full: spring(P.SPATIAL_SCROLL),
    dock: spring(P.SPATIAL_SCROLL).px(), par: spring(P.SPATIAL_SCROLL).px(), launch: spring(P.SHEET_OPEN), wake: spring(P.TAB_SWAP),
    heroX: spring(P.TITLE_SETTLE).px(), heroA: spring(P.TITLE_SETTLE, 1), heroShow: spring([0.3, 1], 1),
    lensX: spring(P.LIQUID_PILL).px(), lensW: spring(P.LIQUID_PILL, 100).px(), dot: spring(P.SNAPPY_FOCUS).px(),
    rowNudge: spring(P.BOUNDARY_BUMP).px()
  };
  var detail = [spring(P.TITLE_SETTLE), spring(P.TITLE_SETTLE), spring(P.TITLE_SETTLE)];
  var rowLag = [], gridRows = [], drawerRows = [], sheetSprings = {}, holders = {};
  var fxTiles = [], fxCells = [], fxTabs = [], fxKeys = [], fxWho = [], fxPill = null, fxWatch = null, launching = null;
  var pending = [];

  /** Sets a spring's target after `ms` (a stagger); a newer call for the same spring replaces it. */
  function later(s, value, ms, preset) {
    for (var i = pending.length - 1; i >= 0; i--) if (pending[i].s === s) pending.splice(i, 1);
    if (ms <= 0 || Motion.instant) { if (preset) s.tune(preset); s.set(value); return; }
    pending.push({s: s, v: value, at: Date.now() + ms, preset: preset});
    Motion.kick();
  }

  function cancel(s) {
    for (var i = pending.length - 1; i >= 0; i--) if (pending[i].s === s) pending.splice(i, 1);
  }

  function stepAll(list, dt) {
    var m = false;
    for (var i = 0; i < list.length; i++) if (list[i].step(dt)) m = true;
    return m;
  }

  Motion.tick(function (dt) {
    var moving = false, i, k, now = Date.now();
    for (i = pending.length - 1; i >= 0; i--) {
      if (now < pending[i].at) continue;
      var q = pending.splice(i, 1)[0];
      if (q.preset) q.s.tune(q.preset);
      q.s.set(q.v);
    }
    if (pending.length) moving = true;
    for (k in mv) if (mv.hasOwnProperty(k) && mv[k].step(dt)) moving = true;
    if (stepAll(detail, dt)) moving = true;
    if (stepAll(rowLag, dt)) moving = true;
    if (stepAll(drawerRows, dt)) moving = true;
    for (k in sheetSprings) if (sheetSprings.hasOwnProperty(k) && sheetSprings[k].step(dt)) moving = true;
    for (k in holders) if (holders.hasOwnProperty(k) && holders[k].s.step(dt)) moving = true;
    var scrubbed = scrub.step(dt);
    if (scrubbed) moving = true;
    for (i = 0; i < fxAll.length; i++) {
      var fx = fxAll[i];
      if (!fx.alive) { fxAll.splice(i--, 1); continue; }
      if (scrubbed) fx.dirty = true;
      if (fx.step(dt)) moving = true;
    }
    drawPage();
    return moving;
  });

  var home = $('home'), tvLayer = $('tv'), el$ = {};
  /** An element by id or selector, looked up once. */
  function q(sel) { return el$[sel] || (el$[sel] = sel.charAt(0) === '#' && sel.indexOf(' ') < 0 ? $(sel.slice(1)) : document.querySelector(sel)); }

  /** Where a tab's layer is while the tabs swap: d = its index − the tab position, −1..1 (TAB_SWAP: 60 px, 0.96). */
  function tabLayer(e, index, extra, behind) {
    var d = Motion.clamp(index - mv.tab.x, -1, 1), a = Math.max(0, 1 - Math.abs(d) * 1.6);
    T(e, 'translateX(' + r2(60 * d) + 'px)' + (extra || '') + ' scale(' + r4((1 - 0.04 * Math.abs(d)) * behind) + ')');
    return a;
  }

  /** Writes every spring-driven style of the page (runs only while something moves). */
  function drawPage() {
    var y = mv.lift.x, p = Motion.clamp(y / 640, 0, 1), d = mv.drawer.x, full = mv.full.x;
    var night = hasClass(body, 'night'), away = Motion.clamp(mv.tab.x, 0, 1);
    // Behind Control Center, a launching app or the screensaver going, the page recedes.
    var behind = (1 - 0.025 * d) * (1 - 0.02 * mv.wake.x);
    var launch = mv.launch.x;
    T(home, 'translateY(' + r2(-y) + 'px) scale(' + r4(behind * (1 - 0.05 * launch)) + ')');
    A(home, 1 - 0.6 * launch);
    // The tabs' layers.
    var tvA = tabLayer(tvLayer, 0, '', 1);
    A(tvLayer, tvA);
    var apps = q('#apps');
    A(apps, tabLayer(apps, 0, ' translateY(' + r2(722 - y + 300 * full) + 'px)', behind) * (1 - full));
    var house = q('#house'), welly = q('#welly'), ha = tabLayer(house, 1, '', behind), wa = tabLayer(welly, 2, '', behind);
    A(house, ha); V(house, ha);
    A(welly, wa); V(welly, wa);
    // The grid's rows, each a little behind the one above.
    for (var r = 0; r < gridRows.length && r < rowLag.length; r++) {
      var lag = r2(rowLag[r].x - y);
      for (var c = 0; c < gridRows[r].length; c++) T(gridRows[r][c], lag ? 'translateY(' + lag + 'px)' : '');
    }
    // Scrolling down to the grid frosts and darkens the art continuously (p), as does leaving the TV tab.
    A(q('#shelftop'), 1 - p);
    A(q('#top'), 1 - p);
    A(q('#blur'), hasClass(body, 'has-photo') ? Math.max(p, away) : 1);
    var lift = q('#sky .lift');
    A(lift, Math.max(p * (night ? 0.42 : 0.6), d * (night ? 0.42 : 0.32)));
    A(q('#sky .shade'), away * (night ? 0.55 : 0.45));
    A(q('#dim'), d * (night ? 0.3 : 0.22));
    T(q('#art'), 'translateX(' + r2(mv.par.x) + 'px)');
    // The Dock: its scroll, and in full screen it drops away.
    T(q('#tiles'), 'translate(' + r2(-mv.dock.x) + 'px,' + r2(300 * full) + 'px)');
    T(q('#shadows'), 'translate(' + r2(-mv.dock.x) + 'px,' + r2(300 * full) + 'px)');
    T(q('#platter'), full ? 'translateY(' + r2(300 * full) + 'px)' : '');
    A(q('#platter'), 1 - full);
    A(q('#tiles'), 1 - full);
    A(q('#shadows'), 1 - full);
    // The Top Shelf's title: out the way it goes, in from the other side.
    var hero = q('#hero');
    T(hero, 'translateX(' + r2(mv.heroX.x) + 'px)');
    A(hero, mv.heroA.x * mv.heroShow.x);
    // Full screen's details, in order (metadata, synopsis, Play), rising 16 px.
    var det = q('#details');
    for (var i = 0; i < 2; i++) {
      A(det.children[i], detail[i].x);
      T(det.children[i], 'translateY(' + r2(16 * (1 - detail[i].x)) + 'px)');
    }
    if (fxWatch && (fxWatch.ea !== detail[2].x)) {
      fxWatch.ey = 16 * (1 - detail[2].x);
      fxWatch.ea = detail[2].x;
      fxWatch.draw();
    }
    var cur = $('dots').querySelector('.cur');
    if (cur) T(cur, 'translateX(' + r2(mv.dot.x) + 'px)');
    // Control Center: in from the right, its rows 25 ms apart.
    var drawer = q('#drawer'), da = Motion.clamp(d * 2.5, 0, 1);
    T(drawer, 'translateX(' + r2(480 * (1 - d)) + 'px)');
    A(drawer, da);
    V(drawer, da);
    var prof = drawer.querySelector('.profiles');
    if (prof && drawerRows[0]) {
      T(prof, 'translateX(' + r2(28 * (1 - drawerRows[0].x)) + 'px)');
      A(prof, drawerRows[0].x);
    }
    for (var k = 0; k < fxKeys.length; k++) {
      var row = drawerRows[1 + Math.floor(k / 2)], rx = row ? row.x : 1, fk = fxKeys[k];
      var ex = 28 * (1 - rx), ea = rx * (hasClass(fk.node, 'off') && !fk.on ? 0.5 : 1);
      if (ex !== fk.ex || ea !== fk.ea) { fk.ex = ex; fk.ea = ea; fk.draw(); }
    }
    var weather = $('weather'), wr = drawerRows[drawerRows.length - 1];
    if (weather && wr) {
      T(weather, 'translateX(' + r2(28 * (1 - wr.x)) + 'px)');
      A(weather, wr.x);
    }
    // Sheets: fade and settle from 1.04.
    for (var id in sheetSprings) {
      if (!sheetSprings.hasOwnProperty(id)) continue;
      var sh = $(id), sv = sheetSprings[id].x;
      A(sh, sv);
      V(sh, sv);
      T(sh, 'scale(' + r4(1.04 - 0.04 * sv) + ')');
    }
    for (var h in holders) if (holders.hasOwnProperty(h)) T(holders[h].el, 'translateY(' + r2(holders[h].s.x) + 'px)');
    var focusedRow = document.querySelector('.sheet.open .row.focus');
    if (focusedRow) T(focusedRow, 'translateY(' + r2(Motion.rubber(mv.rowNudge.x)) + 'px)');
    drawLens();
  }

  // MARK: - Focus

  var Z = {TOP: 'top', HERO: 'hero', SHELF: 'shelf', DRAWER: 'drawer', APPS: 'apps', SETTINGS: 'settings', EDIT: 'edit', LANG: 'lang', ABOUT: 'about',
    TABS: 'tabs', HOUSE: 'house', WELLY: 'welly'};
  var TAB = {TV: 0, HOME: 1, WELLY: 2};
  var st = {zone: Z.SHELF, tab: TAB.TV, top: 3, page: 0, shelf: 0, drawer: 0, grid: 0, row: 0, edit: 0, lang: 0};
  var cards = [];
  var was = {drawer: false, full: false, row: null};

  function sheetOpen() { return st.zone === Z.APPS || st.zone === Z.SETTINGS || st.zone === Z.EDIT || st.zone === Z.LANG || st.zone === Z.ABOUT; }

  function focusList(list, on, index) {
    for (var i = 0; i < list.length; i++) {
      setClass(list[i].node.parentNode && hasClass(list[i].node, 'face') ? list[i].node.parentNode : list[i].node, 'focus', on && index === i);
      list[i].focus(on && index === i);
    }
  }

  function updateFocus() {
    var i, list;
    setClass($('pill'), 'focus', st.zone === Z.TOP);
    if (fxPill) fxPill.focus(st.zone === Z.TOP);
    setClass($('watch'), 'focus', st.zone === Z.HERO);
    if (fxWatch) fxWatch.focus(st.zone === Z.HERO);
    setClass(body, 'full', st.zone === Z.HERO);
    focusList(fxTiles, st.zone === Z.SHELF, st.shelf);
    revealShelf();
    placeName();
    var P1 = people().length;
    focusList(fxWho, st.zone === Z.DRAWER, st.drawer);
    list = document.querySelectorAll('#drawer .who');
    for (i = 0; i < list.length; i++) setClass(list[i], 'focus', st.zone === Z.DRAWER && st.drawer === i);
    focusList(fxKeys, st.zone === Z.DRAWER, st.drawer - P1);
    focusList(fxCells, st.zone === Z.APPS, st.grid);
    // The grid shows 2 rows under the risen Dock; past them, the page (Dock and all) scrolls on.
    var rows = Math.max(1, Math.ceil(others.length / COLS)), r = Math.floor(st.grid / COLS), first = Math.max(0, Math.min(rows - 2, r - 1));
    var grid = st.zone === Z.APPS, lift = grid ? GRID_LIFT + first * GRID_ROW : 0;
    if (lift !== mv.lift.to) {
      mv.lift.set(lift);
      for (i = 0; i < rowLag.length; i++) later(rowLag[i], lift, 18 * i);
    }
    focusRows($('settings'), st.row, st.zone === Z.SETTINGS);
    focusRows($('shelfedit'), st.edit, st.zone === Z.EDIT);
    focusRows($('langpick'), st.lang, st.zone === Z.LANG);
    if (langHint) langHint.textContent = (st.lang + 1) + ' / ' + languageChoices().length + '  ·  ' + tr('OK picks, Back cancels.');
    var row = document.querySelector('.sheet.open .row.focus');
    if (row !== was.row) {
      if (was.row) T(was.row, '');
      was.row = row;
      mv.rowNudge.snap(0);
    }
    // Control Center: presented a little slower than it's dismissed; its rows follow 25 ms apart.
    var drawerOpen = st.zone === Z.DRAWER;
    setClass(body, 'drawer-open', drawerOpen);
    if (drawerOpen !== was.drawer) {
      was.drawer = drawerOpen;
      mv.drawer.tune(drawerOpen ? P.SHEET_OPEN : P.SHEET_CLOSE).set(drawerOpen ? 1 : 0);
      for (i = 0; i < drawerRows.length; i++) {
        if (drawerOpen) { drawerRows[i].snap(0); later(drawerRows[i], 1, 25 * i, P.SHEET_OPEN); }
        else { cancel(drawerRows[i]); drawerRows[i].snap(1); }
      }
    }
    // Full screen: the details come up in order, 40 ms apart, and leave together in 180 ms.
    var full = st.zone === Z.HERO;
    if (full !== was.full) {
      was.full = full;
      mv.full.set(full ? 1 : 0);
      for (i = 0; i < detail.length; i++) {
        if (full) later(detail[i], 1, 40 * i, P.TITLE_SETTLE);
        else { cancel(detail[i]); detail[i].tune([0.12, 1]).set(0); }
      }
    }
    syncSheets();
    setClass(body, 'grid-open', grid);
    setClass(body, 'dock-focus', st.zone === Z.SHELF && st.tab === TAB.TV);
    // The Top Shelf's art drifts ±8 px against the focus moving along the Dock.
    var n = Math.min(shelf.length, SHELF_VISIBLE);
    mv.par.set(st.zone === Z.SHELF && n > 1 ? -8 * (2 * (st.shelf - shelfFirst) / (n - 1) - 1) : 0);
    mv.heroShow.set(hasClass(body, 'profile-background') ? 0 : 1);
    // The tab bar: the selected tab is a lighter lens that slides; the focused one a white pill.
    list = $('tabs').querySelectorAll('.tab');
    for (i = 0; i < list.length; i++) {
      setClass(list[i], 'sel', st.tab === i);
      setClass(list[i], 'focus', st.zone === Z.TABS && st.tab === i);
    }
    for (i = 0; i < fxTabs.length; i++) fxTabs[i].focus(st.zone === Z.TABS && st.tab === i);
    placeLens();
    mv.tab.set(st.tab);
    setClass(body, 'tab-home', st.tab === TAB.HOME);
    setClass(body, 'tab-welly', st.tab === TAB.WELLY);
    if (window.House) { House.show(st.tab === TAB.HOME); House.focus(st.zone === Z.HOUSE); }
    if (window.Welly) { Welly.show(st.tab === TAB.WELLY); Welly.focus(st.zone === Z.WELLY); }
    refreshBackground();
    syncAerialVideo();
    Motion.kick();
  }

  /** The sheets (Home Settings, Shelf, Language, About) open on SHEET_OPEN and close on SHEET_CLOSE. */
  function syncSheets() {
    var ids = ['settings', 'shelfedit', 'langpick', 'about'];
    for (var i = 0; i < ids.length; i++) {
      var s = sheetSprings[ids[i]] || (sheetSprings[ids[i]] = spring(P.SHEET_OPEN)), open = hasClass($(ids[i]), 'open');
      if (open !== (s.to === 1)) s.tune(open ? P.SHEET_OPEN : P.SHEET_CLOSE).set(open ? 1 : 0);
    }
  }

  /** The thing with the focus, for the press squish and the dead-end bump. */
  function focusedFx() {
    switch (st.zone) {
      case Z.SHELF: return fxTiles[st.shelf] || null;
      case Z.APPS: return fxCells[st.grid] || null;
      case Z.TABS: return fxTabs[st.tab] || null;
      case Z.TOP: return fxPill;
      case Z.HERO: return fxWatch;
      case Z.DRAWER: return st.drawer < people().length ? fxWho[st.drawer] || null : fxKeys[st.drawer - people().length] || null;
    }
    return null;
  }

  function bump(dx, dy) {
    var fx = focusedFx();
    if (fx) fx.bump(dx, dy);
    else if (st.zone === Z.SETTINGS || st.zone === Z.EDIT || st.zone === Z.LANG) mv.rowNudge.impulse(dy * BUMP);
  }

  // MARK: - Tabs: TV · Home · Welly

  var lens = null;

  function renderTabs() {
    var bar = $('tabs'), names = [tr('TV'), tr('Home'), 'Welly'];
    bar.innerHTML = '';
    killFx(fxTabs);
    // The selected tab's lens: two round caps and a stretchable middle, so it can change width and stretch with
    // its speed without its round ends going oval.
    lens = el('div', 'lens', bar);
    lens.box = el('div', 'box', lens);
    lens.left = el('span', 'cap l', lens.box);
    lens.mid = el('span', 'mid', lens.box);
    lens.right = el('span', 'cap r', lens.box);
    lens.shine = el('span', 'shine', lens);
    for (var i = 0; i < names.length; i++) {
      var tab = el('span', 'tab', bar, names[i]);
      if (i === 2 && window.WellyBot) drawWellyMini(tab);
      hover(tab, Z.TABS, i);
      fxTabs.push(new Fx(tab, {grow: 0.06}));
    }
    placeLens(true);
  }

  function drawWellyMini(tab) {
    tab.textContent='';var canvas=el('canvas','welly-mini',tab),c=canvas.getContext&&canvas.getContext('2d'),p=WellyBot.profiles.galet,i,a,r;el('span','welly-label',tab,'Welly');
    canvas.width=40;canvas.height=40;if(!c)return;c.translate(20,20);c.scale(16,16);c.beginPath();
    for(i=0;i<64;i++){a=i*Math.PI*2/64;r=p[i];if(i)c.lineTo(Math.cos(a)*r,Math.sin(a)*r);else c.moveTo(Math.cos(a)*r,Math.sin(a)*r);}c.closePath();c.fillStyle=WellyBot.COLOR;c.fill();
    c.save();c.globalCompositeOperation='destination-out';c.save();c.translate(-.18,-.05);c.rotate(-.25);c.scale(.09,.22);c.beginPath();c.arc(0,0,1,0,Math.PI*2);c.fill();c.restore();c.save();c.translate(.18,-.08);c.rotate(-.25);c.scale(.09,.22);c.beginPath();c.arc(0,0,1,0,Math.PI*2);c.fill();c.restore();c.restore();
    c.fillStyle=WellyBot.NOTIF_BLUE;c.beginPath();c.arc(.76,-.68,.16,0,Math.PI*2);c.fill();c.save();c.globalCompositeOperation='destination-out';c.beginPath();c.arc(.65,-.56,.09,0,Math.PI*2);c.fill();c.restore();
  }

  /** The lens's target: under the selected tab. */
  function placeLens(snap) {
    var tabs = $('tabs').querySelectorAll('.tab'), t = tabs[st.tab];
    if (!t || !lens) return;
    mv.lensX.set(t.offsetLeft);
    mv.lensW.set(t.offsetWidth);
    if (snap) { mv.lensX.snap(t.offsetLeft); mv.lensW.snap(t.offsetWidth); }
  }

  /** The lens stretches with its speed: sx = 1 + clamp(|v|·0.00018, 0, 0.12), sy = 1/√sx; its shine moves against it. */
  function drawLens() {
    if (!lens) return;
    var v = mv.lensX.v, sx = 1 + Motion.clamp(Math.abs(v) * 0.00018, 0, 0.12), sy = 1 / Math.sqrt(sx);
    var w = mv.lensW.x * sx, x = mv.lensX.x - (w - mv.lensW.x) / 2, cap = 25.5, mid = Math.max(0, w - 2 * cap);
    T(lens.box, 'translateX(' + r2(x) + 'px) scaleY(' + r4(sy) + ')');
    T(lens.mid, 'translateX(' + cap + 'px) scaleX(' + r2(mid) + ')');
    T(lens.right, 'translateX(' + r2(cap + mid) + 'px)');
    A(lens.shine, Math.min(0.7, Math.abs(v) / 1400));
    T(lens.shine, 'translateX(' + r2(x + w / 2 - 40 - Motion.clamp(v * 0.02, -30, 30)) + 'px)');
  }

  /** ↓ or OK on the tab bar: into the selected tab's content. */
  function enterTab() {
    if (st.tab === TAB.HOME) st.zone = window.House ? Z.HOUSE : Z.TABS;
    else if (st.tab === TAB.WELLY) st.zone = window.Welly ? Z.WELLY : Z.TABS;
    else st.zone = shelf.length ? Z.SHELF : Z.TABS;
  }

  function onApp(name) {
    var n = (name || '').toLowerCase();
    if (!n) return false;
    for (var i = 0; i < apps.length; i++) {
      if ((apps[i].title || '').toLowerCase().indexOf(n) >= 0 || n.indexOf((apps[i].title || '~').toLowerCase()) >= 0) {
        launcher(apps[i].id, apps[i].title)();
        return true;
      }
    }
    return false;
  }

  function focusRows(sheet, index, on) {
    var rows = sheet.querySelectorAll('.row');
    for (var i = 0; i < rows.length; i++) setClass(rows[i], 'focus', on && i === index);
    var holder = sheet.querySelector('.rows');
    if (!holder) return;
    var visible = 7, first = Math.max(0, Math.min(rows.length - visible, index - 3)), y = -first * 78;
    // The list scrolls on a spring; a list just drawn again (a value changed) starts where it is.
    var h = holders[sheet.id];
    if (!h || h.el !== holder) {
      h = holders[sheet.id] = {el: holder, s: h ? h.s : spring(P.SNAPPY_FOCUS).px()};
      if (h.s.to !== y && !hasClass(sheet, 'open')) h.s.snap(y);
    }
    h.s.set(y);
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
    if (!fxPill) fxPill = new Fx($('pill'), {grow: 0.08, avatar: $('pill').querySelector('.avatar')});
    if (!fxWatch) fxWatch = new Fx($('watch'), {grow: 0.08});
    renderPill();
  }

  /** The profiles (profiles.js): profiles= in config.properties, else a default one and Guest. */
  function people() { return Profiles.all(); }

  function person() { return Profiles.current(); }

  function avatarUrl(p) { return 'url(' + Profiles.avatarUrl(p) + ')'; }

  /** The status pill: the time, then the current profile's avatar; the quotes (or what's playing) left of it. */
  function renderPill() {
    var pill = $('pill'), avatar = pill.querySelector('.avatar'), url = avatarUrl(person());
    pill.querySelector('.time').textContent = formatDate('format|h:mm a', Date.now());
    // Another profile: the new face dissolves in over the old one.
    if (avatar.style.backgroundImage && avatar.getAttribute('data-url') !== url && !Motion.instant) {
      var old = el('span', 'prev', avatar);
      old.style.backgroundImage = avatar.style.backgroundImage;
      setTimeout(function () { A(old, 0); }, 20);
      setTimeout(function () { if (old.parentNode) old.parentNode.removeChild(old); }, 450);
    }
    avatar.setAttribute('data-url', url);
    avatar.style.backgroundImage = url;
    $('quotes').style.right = (45 + pill.offsetWidth + 24) + 'px';
  }

  /** What's playing (or a timer), when the TV tells us: the pill widens to show it with three moving bars. */
  function nowPlaying(text) {
    var pill = $('pill'), np = pill.querySelector('.np');
    if (!text) {
      if (np) { setClass(pill, 'playing', false); setTimeout(function () { if (np.parentNode && !hasClass(pill, 'playing')) pill.removeChild(np); renderPill(); }, 260); }
      return;
    }
    if (!np) {
      np = el('span', 'np');
      pill.insertBefore(np, pill.firstChild);
      for (var i = 0; i < 3; i++) el('span', 'bar', np);
      el('span', 'what', np);
    }
    np.lastChild.textContent = text;
    setClass(pill, 'playing', true);
    renderPill();
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

  var photoFront = 'photoA', photoUrl = null, photoGeneration = 0, heroTimer = 0;
  // Background ownership is independent of the recommendation carousel.  A Dock
  // app may have a Continue Watching title which is not in this hour's cards.
  var backgroundGeneration = 0, backgroundMode = 'profile', mediaPage = null, backgroundKey = '';
  var videoFailed = false;
  var aerialVideo = Wallpaper.videoController($('wallpaperVideo'), function () { videoFailed = true; applyProfileBackground(); });

  function syncAerialVideo() {
    var live = Prefs.wallpaper() === 'live:aerial';
    var visible = live && !videoFailed && backgroundMode === 'profile' && st.tab === TAB.TV && !sheetOpen() &&
      !saverOn && !Lock.showing() && !Intro.showing();
    if (!visible) { aerialVideo.hide(); return; }
    var aerial = Wallpaper.liveAerial(Math.floor(Date.now() / 86400000));
    if (!aerial || !aerialVideo.show(aerial.video)) { videoFailed = true; applyProfileBackground(); }
  }

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

  function mediaDescriptor(app) {
    var k = app && shelfKey(app), key = k ? k[0] : '';
    var playing = window.FMTV_NOW_PLAYING || window.PREVIEW_NOW_PLAYING || null;
    return {id: app && app.id || '', pkg: app && app.id || '', appKey: key, shelfKey: key,
      title: app && app.title || '', service: app && app.title || '', media: !!key,
      music: MUSIC_KEYS[key] === true, nowPlaying: MUSIC_KEYS[key] === true ? playing : null};
  }

  function featuredFor(app) {
    var own = [];
    if (app.music) return null;
    for (var i = 0; i < cards.length; i++) if (Data.appFor(cards[i].app) && cards[i].app === app.appKey) own.push(cards[i]);
    return own.length ? own[0] : null;
  }

  /** Now Playing uses `artwork`; the Top Shelf page renderer uses `backdrop`.
   * Keep the source item intact and accept only preview-local or catalogue URLs. */
  function pageForMedia(pick) {
    var art = pick && pick.artwork;
    // A Now Playing result is intentionally stricter than a TMDB pick: callers
    // cannot smuggle arbitrary artwork through a pre-filled backdrop field.
    if (!pick || !pick.mediaSource || pick.mediaSource !== 'nowPlaying') return pick;
    if (!art || !(art.indexOf('preview/') === 0 || Wallpaper.allowed(art))) return null;
    var page = {}, name;
    for (name in pick) if (pick.hasOwnProperty(name)) page[name] = pick[name];
    page.backdrop = art;
    return page;
  }

  function clearHero() {
    clearTimeout(heroTimer);
    var hero = $('hero'), details = $('details'), logo = hero.children[0];
    setClass(hero, 'has-logo', false);
    logo.onload = null;
    logo.removeAttribute('src');
    hero.children[1].textContent = '';
    details.children[0].innerHTML = '';
    details.children[1].textContent = '';
    $('watch').innerHTML = '';
    $('watch').style.display = 'none';
    $('dots').innerHTML = '';
  }

  function applyProfileBackground() {
    backgroundMode = 'profile';
    mediaPage = null;
    setClass(body, 'media-background', false);
    setClass(body, 'profile-background', true);
    clearHero();
    showWallpaper();
    syncAerialVideo();
  }

  /** Re-evaluate on every focus/profile/recommendation/settings update. */
  function refreshBackground() {
    var app = mediaDescriptor(shelf[st.shelf]);
    var wantsMedia = st.tab === TAB.TV && Wallpaper.media(st.zone, app.media);
    var key = wantsMedia ? (st.zone + ':' + app.id + ':' + cards.length + ':' + Prefs.version) : 'profile:' + Prefs.wallpaper() + ':' + Prefs.version;
    // A media resolver can change Continue Watching/Now Playing without
    // changing the app, zone, recommendation count, or preference revision.
    // Re-resolve media focus; generations still reject older async answers.
    if (key === backgroundKey && !wantsMedia) return;
    backgroundKey = key;
    backgroundGeneration++;
    var generation = backgroundGeneration;
    if (!wantsMedia) { applyProfileBackground(); return; }
    Data.resolveWatch(app, generation, function (resolvedGeneration, pick) {
      if (resolvedGeneration !== backgroundGeneration || generation !== backgroundGeneration ||
          !Wallpaper.media(st.zone, mediaDescriptor(shelf[st.shelf]).media) || st.tab !== TAB.TV) return;
      pick = pageForMedia(pick || featuredFor(app));
      if (!pick) { applyProfileBackground(); return; }
      backgroundMode = 'media';
      mediaPage = pick;
      setClass(body, 'profile-background', false);
      setClass(body, 'media-background', true);
      showPage(st.page, 0);
      aerialVideo.hide();
    });
  }

  /** The background for the focused app: resolved watch first, then featured or profile art. */
  function follow(dir) {
    backgroundKey = '';
    refreshBackground(dir);
  }

  /** A bundled compatibility asset for the profile's stable wallpaper ID, or the base gradient. */
  function wallpaperUrl() {
    var official = Wallpaper.aerial(Prefs.wallpaper());
    if (official) return Wallpaper.allowed(official.preview) ? official.preview : '';
    var asset = Wallpaper.legacyAsset(Prefs.wallpaper());
    return asset ? 'aerials/' + asset : '';
  }

  /** The wallpaper, with nothing over it. */
  function showWallpaper() {
    setClass(body, 'wallpaper', true);
    var url = wallpaperUrl();
    photo(url);
    backdrop(url);
  }

  /** Pick `index` on the Top Shelf: its photo cross-fades in, its logo top left, its details for full screen. */
  function showPage(index, dir) {
    st.page = index;
    setClass(body, 'wallpaper', false);
    var p = mediaPage || cards[index];
    photo(p ? pageImage(p.backdrop, 'original') : '');
    backdrop(p ? pageImage(p.backdrop, 'w300') : '');
    var dots = $('dots'), own = appPicks(), n = mediaPage && own.length ? own.length : 0;
    // The page dots: the white one slides to the page shown.
    if (dots.querySelectorAll('.dot').length !== n) {
      dots.innerHTML = '';
      for (var d = 0; d < n; d++) el('span', 'dot', dots);
      if (n) el('span', 'cur', dots);
      mv.dot.snap(Math.max(0, own.indexOf(index)) * 22);
    }
    mv.dot.set(Math.max(0, own.indexOf(index)) * 22);
    clearTimeout(heroTimer);
    if (!dir || Motion.instant) {
      fillHero(p);
      mv.heroX.snap(0);
      mv.heroA.snap(1);
      Motion.kick();
      return;
    }
    // The title leaves the way the pages go (−14 px, 220 ms); the next one comes in from +22 px, 80 ms later.
    mv.heroX.tune([0.22, 1]).set(-14 * dir);
    mv.heroA.tune([0.16, 1]).set(0);
    heroTimer = setTimeout(function () {
      fillHero(mediaPage || cards[st.page]);
      mv.heroX.tune(P.TITLE_SETTLE).snap(22 * dir);
      mv.heroX.set(0);
      mv.heroA.tune(P.TITLE_SETTLE).set(1);
    }, 300);
  }

  function pageImage(art, size) {
    return art && (art.indexOf('preview/') === 0 || Wallpaper.allowed(art)) ? art : Data.image(art, size);
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
    var generation = ++photoGeneration;
    setClass(body, 'has-photo', !!url);
    Motion.kick();   // the frosted copy and the shades depend on whether there's a photo
    var next = photoFront === 'photoA' ? 'photoB' : 'photoA';
    if (!url) {
      setClass($('photoA'), 'on', false);
      setClass($('photoB'), 'on', false);
      setClass(body, 'has-photo', false);
      return;
    }
    var img = new Image();
    img.onload = function () {
      if (generation !== photoGeneration || photoUrl !== url) return;
      $(next).style.backgroundImage = 'url(' + url + ')';
      kenBurns($(next), Motion.instant ? 'none' : 'opacity 0.7s cubic-bezier(.2,.8,.2,1)', 16, 'scale(1.04) translateX(-12px)');
      setClass($(next), 'on', true);
      $(photoFront).style.webkitTransition = $(photoFront).style.transition = Motion.instant ? 'none' : 'opacity 0.7s cubic-bezier(.2,.8,.2,1)';
      setClass($(photoFront), 'on', false);
      photoFront = next;
    };
    img.onerror = function () {
      if (generation !== photoGeneration || photoUrl !== url) return;
      photoUrl = '';
      setClass($('photoA'), 'on', false);
      setClass($('photoB'), 'on', false);
      setClass(body, 'has-photo', false);
      if (backgroundMode === 'media') applyProfileBackground();
    };
    img.src = url;
  }

  /**
   * A slow drift across a photo (Ken Burns), started again from rest each time the photo is shown: the start is set
   * with no transition, then the long one runs to `to`. `fade` is the element's own opacity transition.
   */
  function kenBurns(e, fade, seconds, to) {
    e.style.webkitTransition = e.style.transition = fade;
    T(e, 'none');
    void e.offsetWidth;   // the start applies now, so the drift runs from it
    if (Motion.instant) return;
    var curve = ' ' + seconds + 's cubic-bezier(0.37, 0, 0.63, 1)';
    e.style.webkitTransition = fade + ', -webkit-transform' + curve;
    e.style.transition = fade + ', transform' + curve;
    T(e, to);
  }

  /** ← → in full screen (or by itself): the focused app's next pick. */
  function page(dir) {
    var own = appPicks(), at = own.indexOf(st.page);
    if (own.length < 2) return false;
    // The resolved Continue Watching title owns the initial shelf context; paging
    // intentionally returns to this app's ordinary featured carousel.
    mediaPage = null;
    showPage(own[(at + dir + own.length) % own.length], dir);
    return true;
  }

  var bgUrl = null, bgFront = 'bgA', bgGeneration = 0;

  /** The pick's photo drawn tiny and stretched (as soft as a heavy blur): behind the grid, and in the Dock's glass. */
  function backdrop(url) {
    if (url === bgUrl) return;
    bgUrl = url;
    var generation = ++bgGeneration;
    var next = bgFront === 'bgA' ? 'bgB' : 'bgA';
    var fade = Motion.instant ? 'none' : 'opacity 0.7s cubic-bezier(.2,.8,.2,1)';
    $('bgA').style.webkitTransition = $('bgA').style.transition = fade;
    $('bgB').style.webkitTransition = $('bgB').style.transition = fade;
    if (!url) {
      setClass($('bgA'), 'on', false);
      setClass($('bgB'), 'on', false);
      return;
    }
    var img = new Image();
    img.onload = function () {
      if (generation !== bgGeneration || bgUrl !== url) return;
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
    img.onerror = function () {
      if (generation !== bgGeneration || bgUrl !== url) return;
      bgUrl = '';
      setClass($('bgA'), 'on', false);
      setClass($('bgB'), 'on', false);
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
    var firstRender = !fxTiles.length;
    // The glass's copy of the photo sits where the photo is on screen.
    var bleed = platter.firstChild;
    bleed.style.left = -(left - pad) + 'px';
    bleed.style.top = -DOCK_TOP + 'px';
    var tiles = $('tiles'), shadows = $('shadows');
    tiles.innerHTML = '';
    shadows.innerHTML = '';
    killFx(fxTiles);
    tileStep = tw + gap;
    tileLeft = left;
    shelfFirst = Math.max(0, Math.min(shelfFirst, shelf.length - n));
    for (var i = 0; i < shelf.length; i++) {
      var t = el('div', 'tile', tiles), sh = el('div', 'lift-shadow', shadows);
      t.style.top = sh.style.top = (DOCK_TOP + DOCK_PAD_Y) + 'px';
      face(shelf[i], t, wordFit(shelf[i].title, tw, 32));
      var sheen = el('span', 'sheen', t);
      hover(t, Z.SHELF, i);
      fxTiles.push(new Fx(t, {grow: 0.23, squish: 0.08, tilt: true, shadow: sh, img: t.querySelector('img'), sheen: sheen}));
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
    if (firstRender) mv.dock.snap(shelfFirst * tileStep);
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

  /** The row slides (on a spring, see drawPage), and the apps it takes past the glass fade out. */
  function placeTiles() {
    var row = $('tiles'), tiles = row.children, shadows = $('shadows').children, n = Math.min(shelf.length, SHELF_VISIBLE);
    for (var i = 0; i < shelf.length; i++) {
      var shown = i >= shelfFirst && i < shelfFirst + n;
      tiles[i].style.left = shadows[i].style.left = (tileLeft + i * tileStep) + 'px';
      setClass(tiles[i], 'gone', !shown);
      tiles[i].style.pointerEvents = shown ? '' : 'none';
    }
    mv.dock.set(shelfFirst * tileStep);
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
    killFx(fxKeys);
    killFx(fxWho);
    drawerItems = [];
    var fixed = [
      {label: tr('All Apps'), symbol: 'apps', run: function () { closeDrawer(); openApps(0); }},
      {label: tr('Home Settings'), symbol: 'settings', run: function () { openSettings(); }},
      {label: tr('Lock'), glyph: 'glyph-lock', run: lockNow}
    ];
    if (TV.canOpenSettings) fixed.push({label: tr('TV Settings'), symbol: 'tv_settings', run: tvSettings});
    for (var i = 0; i < inputs.length && drawerItems.length < 8 - fixed.length; i++) {
      var x = inputs[i];
      drawerItems.push({label: x.label || tr('Live TV'), symbol: x.symbol, off: !x.connected, run: launcher(x.id, x.label, null, true)});
    }
    drawerInputs = drawerItems.length;
    drawerItems = drawerItems.concat(fixed);
    // Profiles first, as on tvOS: the avatars, the current one ringed; OK opens the lock on that profile.
    var row = el('div', 'profiles' + (people().length > 4 ? ' many' : ''), d), list = people(), P = list.length;
    var step = P > 4 ? Math.floor((464 - 88) / (P - 1)) : 116;   // five or more: smaller faces, spread over the width
    for (var w = 0; w < P; w++) {
      var who = el('div', 'who' + (list[w].id === person().id ? ' on' : ''), row);
      who.style.left = (w * step) + 'px';
      var faceEl = el('span', 'face', who);
      faceEl.style.backgroundImage = avatarUrl(list[w]);
      el('span', 'name', who, list[w].name);
      hover(who, Z.DRAWER, w);
      fxWho.push(new Fx(faceEl, {grow: 0.12}));
    }
    var rows = Math.min(4, Math.ceil(drawerItems.length / 2));
    drawerItems = drawerItems.slice(0, rows * 2);
    for (var k = 0; k < drawerItems.length; k++) {
      var item = drawerItems[k], key = el('div', 'key' + (item.off ? ' off' : ''), d);
      key.style.left = (k % 2) * 240 + 'px';
      key.style.top = (148 + Math.floor(k / 2) * 136) + 'px';
      if (item.glyph) el('span', item.glyph, key);
      else sym(item.symbol, key);
      el('div', 'label', key, item.label);
      hover(key, Z.DRAWER, P + k);
      fxKeys.push(new Fx(key, {grow: 0.06}));
    }
    var w = el('div', '', d);
    w.id = 'weather';
    // One spring per row (profiles, each pair of cards, the weather): they come in 25 ms apart.
    var count = 2 + rows;
    while (drawerRows.length < count) drawerRows.push(spring(P.SHEET_OPEN, 1));
    drawerRows.length = count;
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
    st.drawer = Profiles.index();
    renderPill();
    updateFocus();
  }

  /** Back where the focus was (the button that opened it, or the Dock). */
  function closeDrawer() {
    st.zone = drawerFrom === Z.TOP || drawerFrom === Z.HERO || drawerFrom === Z.TABS ? drawerFrom : Z.SHELF;
    updateFocus();
  }

  // MARK: - All Apps

  function renderApps() {
    var sheet = $('apps');
    sheet.firstChild.textContent = tr('All Apps');
    var grid = sheet.lastChild;
    grid.innerHTML = '';
    killFx(fxCells);
    gridRows = [];
    for (var i = 0; i < others.length; i++) {
      var r = Math.floor(i / COLS), c = i % COLS, cell = el('div', 'cell', grid);
      cell.style.left = (GRID_LEFT + c * (TILE_W + TILE_GAP)) + 'px';
      cell.style.top = (GRID_TOP + r * GRID_ROW) + 'px';
      var sh = el('div', 'lift-shadow', cell), f = el('div', 'face', cell);
      face(others[i], f, wordFit(others[i].title, TILE_W, 34));
      var sheen = el('span', 'sheen', f), label = el('div', 'label', cell, others[i].title);
      hover(cell, Z.APPS, i);
      fxCells.push(new Fx(f, {grow: 0.23, squish: 0.08, tilt: true, shadow: sh, img: f.querySelector('img'), sheen: sheen, label: label}));
      (gridRows[r] || (gridRows[r] = [])).push(cell);
    }
    // Each row follows the page 18 ms after the one above.
    while (rowLag.length < gridRows.length) rowLag.push(spring(P.SPATIAL_SCROLL, mv.lift.to).px());
    rowLag.length = gridRows.length;
    Motion.kick();
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

  // MARK: - Home Settings: the sections of MAHITTV-PROFILES-SPEC.md §6, each a list of rows

  var settingsSection = null, sectionRow = 0;

  function sections() {
    return [
      {id: 'profiles', label: tr('Profiles & PINs'), rows: profileRows},
      {id: 'look', label: tr('Appearance & Startup'), rows: lookRows},
      {id: 'place', label: tr('Location & Media'), rows: placeRows},
      {id: 'welly', label: tr('Welly'), rows: wellyRows},
      {id: 'system', label: tr('System'), rows: systemRows},
      {id: 'reset', label: tr('Reset & Restore'), rows: resetRows}
    ];
  }

  function section(id) {
    var list = sections();
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }

  function settingRows() {
    var s = section(settingsSection);
    if (s) return s.rows();
    return sections().map(function (x, i) {
      return {label: x.label, value: '›', step: function (d) { if (d >= 0) enterSection(x.id, i); }};
    });
  }

  function enterSection(id, i) {
    settingsSection = id;
    sectionRow = i;
    st.row = 0;
    renderSettings();
  }

  function leaveSection() {
    settingsSection = null;
    st.row = sectionRow;
    renderSettings();
  }

  function status(on) { return on ? tr('status|Set') : tr('status|Not set'); }

  function profileRows() {
    var me = person(), rows = [];
    rows.push({label: tr('Profile'), value: me.name, opens: true,
      step: function (d) { if (d >= 0) showLock({from: 'switch', cancel: true}); }});
    rows.push({label: tr('Lock'), value: '›', step: function (d) { if (d >= 0) lockNow(); }});
    rows.push({label: tr('Change PIN'), value: '›', dim: me.role === 'guest', step: function (d) { if (d >= 0) changeOwnPin(); }});
    // The manager sets or removes everyone else's PIN (with the manager's PIN): OK or → sets, ← removes.
    if (me.role === 'manager') {
      people().forEach(function (p) {
        if (p.id === me.id || p.role === 'guest') return;
        rows.push({label: trf("{name}'s PIN", {name: p.name}), value: status(Profiles.needsPin(p)),
          step: function (d) { if (d < 0) removePin(p); else setPinFor(p); }});
      });
    }
    return rows;
  }

  function lookRows() {
    var theme = Prefs.theme(), auto = tr('Automatic'), wall = Prefs.wallpaper();
    var names = {
      'horizon:auto': auto, 'horizon:light': tr('Light'), 'horizon:dark': tr('Dark'),
      'aerial:yosemite': tr('Valley'), 'aerial:iceland': tr('Coast'),
      'aerial:north-america-aurora': tr('Aurora'), 'aerial:new-york': tr('Earth')
    };
    return [
      {label: tr('Theme'), value: theme === 'light' ? tr('Light') : theme === 'dark' ? tr('Dark') : auto,
        step: function (d) { cycle('theme', ['auto', 'light', 'dark'], theme, d); checkNight(); }},
      {label: tr('Wallpaper'), value: names[wall] || auto,
        step: function (d) {
          cycle('wallpaper', WALLPAPERS, wall, d);
          follow();
        }},
      {label: tr('Startup animation & chime'), toggle: Prefs.intro(),
        step: function () { Prefs.set('intro', !Prefs.intro()); }}
    ];
  }

  function placeRows() {
    var unit = Prefs.temperature();
    var rows = [
      {label: tr('City'), value: Data.city || tr('Unavailable'), fixed: true, step: function () {}},
      {label: tr('Temperature'), value: unit === 'C' ? '°C' : unit === 'F' ? '°F' : tr('unit|Automatic'),
        step: function (d) { cycle('temperature', ['auto', 'C', 'F'], unit, d); renderPanels(); }},
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
    rows.push({label: tr('TMDB key'), value: status(!!CONFIG.tmdbKey), fixed: true, step: function () {}});
    return rows;
  }

  function wellyRows() {
    var me = person(), host = (CONFIG.ollamaUrl || '').replace(/^https?:\/\//, '').replace(/\/.*$/, '');
    return [
      {label: tr('Gemini key'), value: status(!!CONFIG.geminiKey), fixed: true, step: function () {}},
      {label: tr('Ollama'), value: host || tr('status|Not set'), fixed: true, step: function () {}},
      {label: trf("Clear Welly's memory for {name}", {name: me.name}), value: '›', dim: me.role === 'guest',
        step: function (d) {
          if (d < 0 || me.role === 'guest') return;
          Profiles.clearLog();
          if (window.Welly && Welly.clearMemory) Welly.clearMemory();
          toast(trf("Cleared Welly's memory for {name}.", {name: me.name}));
        }}
    ];
  }

  function systemRows() {
    var picked = pickedLanguage();
    var rows = [
      {label: tr('Language'), value: picked === 'auto' ? tr('Automatic') : I18N.languageName(picked), opens: true, language: true,
        step: function (d) { if (d >= 0) openLanguages(); }},
      {label: tr('Dock'), value: '›', shelf: true, step: function (d) { if (d >= 0) openShelfEdit(); }},
      {label: tr('Watch from the screensaver'), toggle: Prefs.saverWatch(),
        step: function () { Prefs.set('saverWatch', !Prefs.saverWatch()); }},
      {label: tr('About this TV'), value: '›', about: true, step: function (d) { if (d >= 0) openAbout(); }}
    ];
    if (TV.canOpenSettings) rows.push({label: tr('TV Settings'), value: '›', step: function (d) { if (d >= 0) tvSettings(); }});
    return rows;
  }

  function resetRows() {
    return [
      {label: tr('Reset mahitTV Data'), value: '›', step: function (d) { if (d >= 0) resetData(); }},
      {label: tr('Restore Original TV'), value: tr('Google TV only'), fixed: true, dim: true,
        step: function () { toast(tr('Restore Original TV is on Google TV only.')); }}
    ];
  }

  // MARK: - PINs from Settings (the PIN pad of lock.js over the sheet)

  /** The manager's PIN first (when the manager has one), then `then`. */
  function withManager(then) {
    var m = Profiles.manager();
    if (!Profiles.needsPin(m)) { then(); return; }
    Lock.ask({title: trf("Enter {name}'s PIN", {name: m.name}), profile: m,
      verify: function (d) { return Profiles.checkPin(m, d); }, done: function () { then(); }});
  }

  /** A new PIN for p, twice; then `done`. */
  function askNewPin(p, done) {
    Lock.ask({title: trf('New PIN for {name}', {name: p.name}), profile: p, done: function (first) {
      Lock.ask({title: tr('Enter the new PIN again'), profile: p,
        verify: function (again) { return again === first; },
        done: function () { Profiles.setPin(p, first); Lock.close(); done(); }});
    }});
  }

  function changeOwnPin() {
    var me = person();
    if (me.role === 'guest') { toast(tr('Guest has no PIN.')); return; }
    function next() { askNewPin(me, function () { toast(tr('PIN changed.')); renderSettings(); }); }
    if (!Profiles.needsPin(me)) { next(); return; }
    Lock.ask({title: tr('Enter your current PIN'), profile: me, verify: function (d) { return Profiles.checkPin(me, d); }, done: next});
  }

  function setPinFor(p) {
    withManager(function () { askNewPin(p, function () { toast(trf('PIN set for {name}.', {name: p.name})); renderSettings(); }); });
  }

  function removePin(p) {
    if (!Profiles.needsPin(p)) return;
    withManager(function () {
      Profiles.setPin(p, '');
      Lock.close();
      toast(trf('PIN removed for {name}.', {name: p.name}));
      renderSettings();
    });
  }

  /** Reset mahitTV Data (the manager's PIN): every profile's settings, Dock, PIN, watch log and Welly memory, then the lock. */
  function resetData() {
    withManager(function () {
      Lock.close();
      if (window.Welly && Welly.resetAll) Welly.resetAll();
      Profiles.resetAll();
      location.reload();
    });
  }

  function cycle(name, values, current, d) {
    var i = values.indexOf(current);
    Prefs.set(name, values[(i + (d || 1) + values.length) % values.length]);
  }

  var rowsNow = [];

  function renderSettings() {
    var box = $('settings').firstChild, s = section(settingsSection);
    box.innerHTML = '';
    var title = el('div', 'title', box);
    sym('settings', title);
    title.appendChild(document.createTextNode(s ? s.label : tr('Home Settings')));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    rowsNow = settingRows();
    st.row = Math.max(0, Math.min(rowsNow.length - 1, st.row));
    list.style.height = Math.min(rowsNow.length, 7) * 78 + 'px';
    for (var i = 0; i < rowsNow.length; i++) {
      var r = rowsNow[i], row = el('div', 'row' + (r.dim ? ' dim' : ''), holder, r.label);
      if (r.toggle !== undefined) setClass(el('div', 'toggle', row), 'on', r.toggle);
      else {
        var v = el('span', 'value', row);
        if (r.value === '›' || r.fixed) v.textContent = r.value;
        else if (r.opens) { v.appendChild(document.createTextNode(r.value)); el('span', 'chev', v, '›'); }
        else { el('span', 'chev', v, '‹'); v.appendChild(document.createTextNode(r.value)); el('span', 'chev', v, '›'); }
      }
      hover(row, Z.SETTINGS, i);
    }
    var manager = settingsSection === 'profiles' && person().role === 'manager';
    el('div', 'hint', box, manager ? tr('OK sets a PIN. ← removes it.') : s ? tr('OK or the arrows change the value.') : tr('Back closes.'));
    updateFocus();
  }

  /** From Control Center: the sections. keep: back from Language, About or the Dock, in the same section. */
  function openSettings(keep) {
    st.zone = Z.SETTINGS;
    if (!keep) { settingsSection = null; st.row = 0; }
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
    openSettings(true);
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
      [tr('IP address'), info && info.ip], [tr('Time zone'), TV.zone()], ['mahitTV', info && info.version]
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
    openSettings(true);
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
    title.appendChild(document.createTextNode(tr('Dock')));
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

  /**
   * Opens an app; `pick` (a Top Shelf title) goes into the profile's watch log with it, a plain app with no title.
   * `quiet`: not logged (an input).
   */
  function launcher(id, name, pick, quiet) {
    return function () {
      if (!quiet) {
        Profiles.touch();
        var service = shelfKey({id: id, title: name || id});
        Profiles.launched(pick ? pick.title : '', pick && pick.kind ? pick.id : 0, pick ? pick.kind || '' : '',
          name || id, service ? service[0] : '', id);
      }
      TV.launch(id, null, function (ok) {
        if (!ok) toast(trf("Couldn't open {app}.", {app: name || id}));
      });
    };
  }

  /**
   * The app opens at once; meanwhile its tile keeps growing and fades toward full screen and the page recedes to
   * 0.95. Back on the home screen (or if nothing took over), it all returns.
   */
  var launchTimer = 0;
  function launchMotion(fx) {
    if (Motion.instant) return;
    launching = fx;
    if (fx) fx.launch.set(1);
    mv.launch.set(1);
    clearTimeout(launchTimer);
    launchTimer = setTimeout(function () { if (!document.hidden) endLaunch(false); }, 1500);
  }

  function endLaunch(snap) {
    clearTimeout(launchTimer);
    if (launching) { if (snap) launching.launch.snap(0); else launching.launch.set(0); launching.p.snap(0); }
    launching = null;
    if (snap) mv.launch.snap(0); else mv.launch.set(0);
    Motion.kick();
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
      case Z.TABS:
        enterTab();
        updateFocus();
        return;
      case Z.HOUSE:
        House.toggle();
        return;
      case Z.WELLY:
        Welly.ok();
        return;
      case Z.TOP:
        openDrawer();
        return;
      case Z.HERO:
        var p = cards[st.page], app = p && Data.appFor(p.app);
        if (app) { launchMotion(fxWatch); launcher(app.id, app.title, p)(); }
        return;
      case Z.SHELF:
        if (shelf[st.shelf]) { launchMotion(fxTiles[st.shelf]); launcher(shelf[st.shelf].id, shelf[st.shelf].title)(); }
        return;
      case Z.DRAWER:
        if (st.drawer < people().length) {   // a profile: the lock on it (its PIN first, when it has one)
          var who = people()[st.drawer];
          closeDrawer();
          if (who.id !== person().id) showLock({profile: who, pin: true, cancel: true, from: 'switch'});
          return;
        }
        if (drawerItems[st.drawer - people().length]) drawerItems[st.drawer - people().length].run();
        return;
      case Z.APPS:
        if (others[st.grid]) { launchMotion(fxCells[st.grid]); launcher(others[st.grid].id, others[st.grid].title)(); }
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

  function snapshot() {
    return [st.zone, st.tab, st.top, st.page, st.shelf, st.drawer, st.grid, st.row, st.edit, st.lang].join(',');
  }

  function move(dx, dy) {
    lastMove = {dx: dx, dy: dy, at: Date.now()};
    var before = snapshot(), zone = st.zone;
    moveFocus(dx, dy);
    // Nothing that way: the focused thing tugs toward the press and comes back (the house and Welly do their own).
    var changesValue = (zone === Z.SETTINGS && dx) || (zone === Z.EDIT && dx);
    if (snapshot() === before && !changesValue && zone !== Z.HOUSE && zone !== Z.WELLY) bump(dx, dy);
  }

  function moveFocus(dx, dy) {
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
          // ↑ on a pick: full screen; on a wallpaper: the tab bar.
          if (appPicks().length || mediaPage || hasClass(body, 'media-background')) st.zone = Z.HERO;
          else st.zone = Z.TABS;
        }
        break;
      case Z.HERO:
        if (dx) page(dx);
        else if (dy < 0) st.zone = Z.TABS;
        else if (shelf.length) st.zone = Z.SHELF;
        break;
      case Z.TABS:
        // ← → walk TV · Home · Welly · the pill; focusing a tab selects it, as on tvOS.
        if (dx > 0 && st.tab === TAB.WELLY) { st.zone = Z.TOP; st.top = 3; }
        else if (dx) st.tab = Math.max(0, Math.min(TAB.WELLY, st.tab + dx));
        else if (dy > 0) enterTab();
        break;
      case Z.HOUSE:
        if (!House.move(dx, dy)) st.zone = Z.TABS;
        break;
      case Z.WELLY:
        if (!Welly.move(dx, dy)) st.zone = Z.TABS;
        break;
      case Z.TOP:
        if (dx < 0) { st.zone = Z.TABS; st.tab = TAB.WELLY; }
        else if (dy > 0) enterTab();
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
      openSettings(true);
      for (var i = 0; i < rowsNow.length; i++) if (rowsNow[i].shelf) st.row = i;   // back on the Shelf row, whatever follows it
      updateFocus();
      return;
    }
    if (st.zone === Z.LANG) { closeLanguages(false); return; }
    if (st.zone === Z.SETTINGS && settingsSection) { leaveSection(); return; }
    if (st.zone === Z.ABOUT) { closeAbout(); return; }
    if (sheetOpen()) { closeSheets(); return; }
    if (st.zone === Z.DRAWER) { closeDrawer(); return; }
    // Back from a tab's content goes to the tab bar; on the bar, back to TV.
    if (st.zone === Z.HOUSE || st.zone === Z.WELLY || (st.zone === Z.TOP && st.tab !== TAB.TV)) { st.zone = Z.TABS; updateFocus(); return; }
    if (st.zone === Z.TABS && st.tab !== TAB.TV) { st.tab = TAB.TV; updateFocus(); return; }
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
      if (zone === Z.TABS) st.tab = index;
      else if (zone === Z.TOP) st.top = index;
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
    var night = Wallpaper.dark(Prefs.wallpaper(), Data.isNight(Date.now()));
    setClass(body, 'night', night);
    refreshBackground();
    Motion.kick();   // the page's shades are darker at night
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
    aerialVideo.hide();
    var photos = saverPhotos();
    saverOn = true;
    saverPick = null;
    var s = $('saver');
    s.innerHTML = '';
    s.style.webkitTransition = s.style.transition = '';
    // It comes in over 1.2 s, settling from 1.06 to 1.0.
    var zoom = el('div', 'zoom', s);
    T(zoom, Motion.instant ? '' : 'scale(1.06)');
    setTimeout(function () { T(zoom, ''); }, 30);
    var a = el('div', 'photo', zoom), b = el('div', 'photo', zoom);
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
        T(back, 'none');
        clock.children[2].textContent = p.title;
        saverPick = p;
        // With Watch from the screensaver on, OK opens this title's app (any other key only closes).
        var app = Prefs.saverWatch() && Data.appFor(p.app);
        hint.textContent = app ? trf('Press OK to watch on {app} · any other button to close', {app: app.title}) : '';
        setTimeout(function () {
          // Each photo drifts from rest (the reset above has no transition, so the drift runs every time).
          kenBurns(back, 'opacity 2.4s', 24, 'scale(1.08) translateX(-1.5%)');
          setClass(back, 'on', true);
          setClass(front, 'on', false);
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

  /** Woken by a key: it dissolves in 320 ms while the home screen settles from 0.98. */
  function hideSaver(woken) {
    var s = $('saver');
    if (woken && saverOn) {
      s.style.webkitTransition = s.style.transition = 'opacity 0.32s, visibility 0s 0.32s';
      mv.wake.snap(1);
      mv.wake.set(0);
    }
    saverOn = false;
    clearTimeout(saverTimer);
    setClass(s, 'on', false);
    syncAerialVideo();
  }

  function poke(ok) {
    idleAt = Date.now();
    if (!saverOn) return false;
    var app = ok && saverPick && Prefs.saverWatch() && Data.appFor(saverPick.app);
    hideSaver(true);
    if (app) launcher(app.id, app.title, saverPick)();
    return true;
  }

  // MARK: - The lock and the profiles (lock.js, profiles.js)

  /** The lock over everything: o.from ('intro', 'idle', 'lock', 'switch'), o.profile, o.pin, o.cancel (see lock.js). */
  function showLock(o) {
    o = o || {};
    hideSaver();
    aerialVideo.hide();
    if (o.from !== 'switch') {
      closeSheets();
      if (st.zone === Z.DRAWER) closeDrawer();
    }
    o.wallpaper = wallpaperUrl();
    Lock.show(o);
  }

  /** Control Center's Lock card (and Settings → Profiles & PINs → Lock). */
  function lockNow() {
    if (st.zone === Z.DRAWER) closeDrawer();
    Profiles.lock();
    showLock({from: 'lock'});
  }

  /** In, as `p`: the home screen becomes theirs (Dock, theme, wallpaper, Welly) and the TV tab unfolds. */
  function unlocked(p) {
    touchedAt = Date.now();
    idleAt = Date.now();
    hideSaver();
    closeSheets();
    st.zone = Z.SHELF;
    st.tab = TAB.TV;
    st.shelf = 0;
    st.page = 0;
    pickShelf();
    renderShelf();
    renderDrawer();
    renderPill();
    checkNight();
    follow();
    if (window.Welly && Welly.profileChanged) Welly.profileChanged();
    if (!Motion.instant) { mv.wake.snap(1); mv.wake.set(0); }   // the page settles from 0.98 as the lock lifts
    updateFocus();
  }

  /** The last use, for the 10-minute grace (written at most every 5 s). */
  var touchedAt = 0;
  function touch() {
    var now = Date.now();
    if (now - touchedAt < 5000) return;
    touchedAt = now;
    Profiles.touch();
  }

  // MARK: - Keys

  var okDown = false, pressed = null, lastKey = 0, lastKeyAt = 0;

  function isArrow(k) { return k === TV.keys.left || k === TV.keys.right || k === TV.keys.up || k === TV.keys.down; }

  document.addEventListener('keydown', function (e) {
    var k = e.keyCode, now = Date.now();
    // The intro and the lock take every key while they show.
    if (Intro.showing()) { Intro.key(k); e.preventDefault(); return; }
    if (Lock.showing()) { idleAt = now; if (Lock.key(e)) e.preventDefault(); return; }
    // Away longer than the grace: the lock first (the screensaver goes with it).
    if (Profiles.needsLock()) { e.preventDefault(); showLock({from: 'idle'}); return; }
    touch();
    if (poke(k === TV.keys.ok)) { e.preventDefault(); return; }   // the first press only wakes the screen (or OK watches)
    if (launching) endLaunch(true);
    // Welly's text field has the keyboard: the field keeps the letters and the ← →, Welly takes OK and ↑ ↓ Back.
    if (st.zone === Z.WELLY && Welly.typing()) {
      var r = Welly.typingKey(k, TV.keys);
      if (r === 'native') return;
      e.preventDefault();
      updateFocus();
      return;
    }
    if (isArrow(k)) {
      // A held arrow (the remote repeats it): fast scrub until it's released.
      setScrub(!!e.repeat || (k === lastKey && now - lastKeyAt < 150));
      lastKey = k;
      lastKeyAt = now;
    }
    if (k === TV.keys.left) move(-1, 0);
    else if (k === TV.keys.right) move(1, 0);
    else if (k === TV.keys.up) move(0, -1);
    else if (k === TV.keys.down) move(0, 1);
    else if (k === TV.keys.ok) {
      // OK squishes what's focused on the press and acts on the release, as the Android app and tvOS do.
      e.preventDefault();
      if (okDown || e.repeat) return;
      okDown = true;
      // Welly does its own press: OK down presses there, its release lets go (Welly.release, when it has one).
      if (st.zone === Z.WELLY) { okDown = 'welly'; activate(); return; }
      pressed = focusedFx();
      if (pressed) pressed.p.set(1);
      return;
    }
    else if (k === TV.keys.back || k === 27 || k === 8) back();
    else return;
    e.preventDefault();
  });
  document.addEventListener('keyup', function (e) {
    var k = e.keyCode;
    if (isArrow(k)) { setScrub(false); return; }
    if (k !== TV.keys.ok || !okDown) return;
    if (okDown === 'welly') {
      okDown = false;
      if (window.Welly && Welly.release) Welly.release();
      return;
    }
    okDown = false;
    if (pressed) pressed.p.set(0);
    pressed = null;
    e.preventDefault();
    activate();
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
    st.tab = TAB.TV;
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
    if (Profiles.needsLock() && !Intro.showing() && !Lock.showing()) showLock({from: 'idle'});
    loadApps(function () { renderShelf(); renderDrawer(); Data.refresh(); });
  }

  window.addEventListener('resize', fit);
  document.addEventListener('webOSRelaunch', reopened);   // LG: opened again while running
  window.addEventListener('appcontrol', reopened);        // Samsung: the same
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) { aerialVideo.hide(); hideSaver(); return; }
    idleAt = Date.now();
    endLaunch(true);   // back from the app: the page is in place again
    if (Profiles.needsLock() && !Intro.showing() && !Lock.showing()) showLock({from: 'idle'});
    loadApps(function () { renderShelf(); renderDrawer(); updateFocus(); Data.refresh(); });
  });
  window.addEventListener('pagehide', function () { aerialVideo.hide(); });
  window.addEventListener('unload', function () { aerialVideo.hide(); });

  Profiles.seedPreview();   // preview builds: Mahit's and Dhruvin's sample Docks and watch logs, before anything draws
  I18N.set(Prefs.language());
  checkNight();   // now, from the last forecast: not after the TV lists its apps, with the day look until then
  // LG's screensaver waits only when ours will show: not under a sheet (ours waits there), and not without photos.
  TV.holdScreenSaver(function () { return !sheetOpen() && saverPhotos().length > 0; });
  fit();
  renderTop();
  renderTabs();
  if (window.House) House.init($('house'));
  if (window.Welly) {
    Welly.init($('welly'), {
      name: function () { return person().name; },
      appNames: function () { return apps.map(function (a) { return a.title; }); },
      launch: onApp,
      openPick: function (p) { var app = Data.appFor(p.app); if (app) launcher(app.id, app.title, p)(); }
    });
  }
  tickClock();
  loadApps(function () {
    renderAll();
    Data.refresh();
  });
  // Power-on: the golden intro, dissolving into "Who's Watching?" (preview builds skip both with ?nolock).
  Lock.init($('lock'), {onUnlock: unlocked});
  if (window.PREVIEW_PICKS && /[?&]nolock\b/.test(location.search)) {
    Intro.skip($('intro'));
    Profiles.unlock(Profiles.current());
  } else {
    Intro.play($('intro'), function () { showLock({from: 'intro'}); });
  }
  // The Top Shelf pages by itself while the focus is elsewhere (and not on an app whose pick it shows).
  setInterval(function () {
    if (!saverOn && st.zone === Z.SHELF && st.tab === TAB.TV) page(1);   // the focused app's picks, when it has several
  }, 8000);
  setInterval(function () {
    checkNight();
    Data.refresh();
    if (!saverOn && !sheetOpen() && !Lock.showing() && !Intro.showing() && Date.now() - idleAt > SAVER_AFTER) showSaver();
    if (saverOn) tickSaverClock($('saver').querySelector('.clock'));
  }, 30000);

  // For the browser preview and tests.
  window.FreeMyTV = {state: st, move: move, activate: activate, back: back, showSaver: showSaver, hideSaver: hideSaver, toast: toast,
    lock: lockNow, showLock: showLock, openSettings: openSettings,
    tab: function (i) { st.tab = i; st.zone = Z.TABS; updateFocus(); },
    // What's playing or a timer, when a TV can tell (none can yet on webOS/Tizen): the pill widens to show it.
    nowPlaying: nowPlaying};
  // Preview-only controller seam for deterministic browser tests.  It calls the
  // same focus/background path as a remote key; production packages have no
  // PREVIEW_PICKS and never expose it.
  if (window.PREVIEW_PICKS) {
    window.Home = window.Home || {};
    window.Home.testBackground = function (o) {
      o = o || {};
      if (o.app) shelf[st.shelf] = o.app;
      if (o.zone) st.zone = o.zone;
      if (o.tab !== undefined) st.tab = o.tab;
      if (!o.keepKey) backgroundKey = '';
      updateFocus();
      return {mode: backgroundMode, generation: backgroundGeneration, zone: st.zone,
        media: hasClass(body, 'media-background'), profile: hasClass(body, 'profile-background'),
        title: $('hero').children[1].textContent, transition: $('photoA').style.transition || $('photoB').style.transition,
        backdropTransition: $('bgA').style.transition || $('bgB').style.transition};
    };
  }
})();
