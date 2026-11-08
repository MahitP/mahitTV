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

  /**
   * The 1920 × 1080 stage fills the window (on the TV it already is 1920 × 1080). A TV's window only gets shorter for
   * the keyboard (Samsung's resizes it while the city or key field has focus): there the stage keeps its width and the
   * keyboard covers its bottom, as in other apps. Fitted to the short window, it shrank into the top left beside a white
   * band (#399).
   */
  function fit() {
    var s = TV.name !== 'browser' ? window.innerWidth / 1920 : Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
    stage.style.webkitTransform = stage.style.transform = s === 1 ? '' : 'scale(' + s + ')';
  }

  // MARK: - Apps on the shelf

  /** Shelf apps in this order when installed, like the Android app: key, LG app id, name on the TV, brand gradient. */
  // The first DOCK_DEFAULT found make the built-in Dock: Netflix, Prime Video, YouTube, Apple TV, Crunchyroll, StbEmu.
  var SHELF = [
    ['netflix', /^netflix$/, /netflix/i, ['#141414', '#000000']],
    ['prime', /^amazon$/, /prime video|amazon/i, ['#1FA2FF', '#0A62C9']],
    ['youtube', /youtube\.leanback|^com\.google\.android\.youtube\.tv$/, /^youtube$/i, ['#202020', '#0B0B0B']],
    ['appletv', /appletv|apple\.tv/, /apple tv/i, ['#2C2C2E', '#000000']],
    ['crunchyroll', /crunchyroll/, /crunchyroll/i, ['#FF8A1F', '#E8590C']],
    ['stbemu', /stb\.?emu/i, /^stb ?emu$/i, ['#84CC16', '#4D7C0F']],
    ['disney', /disney/, /disney/i, ['#1A3E9A', '#0A1854']],
    ['max', /(^|\.)hbo|wbd/, /^(hbo )?max$|hbo max/i, ['#5B2EEA', '#1F1477']],   // (^|.)hbo: not com.tcl.dasHBOard
    ['hulu', /^hulu/, /hulu/i, ['#1CE783', '#0E9F57']],
    ['paramount', /paramount|cbs/, /paramount/i, ['#0064FF', '#002A86']],
    ['peacock', /peacock/, /peacock/i, ['#1C1C1E', '#000000']],
    ['nfl', /nfl/, /^nfl/i, ['#013369', '#00163D']],
    ['globoplay', /globoplay/, /globoplay/i, ['#FF8A1F', '#FF2D5A']],
    ['jellyfin', /jellyfin/i, /jellyfin/i, ['#AA5CC3', '#00A4DC']],
    // tvOS 27.2's Music icon: a white note on black glass (it was red), in light and dark alike.
    ['applemusic', /apple.?music|com\.apple\.music/i, /apple music/i, ['#3A3A3C', '#000000']],
    ['spotify', /spotify/, /spotify/i, ['#202020', '#050505']],
    ['plex', /^cdp-30$|plex/i, /^plex$/i, ['#282A2D', '#121315']]
  ];
  // These apps are media for Dock policy but their album art must only come
  // from active Now Playing; they never borrow a film/TV recommendation.
  var MUSIC_KEYS = {applemusic: true, spotify: true};
  // Apps shown at once, as on Android: 8 (today's smallest tiles, 178 wide); the shelf scrolls for the rest. The
  // built-in list keeps 8 too.
  // The Dock shows 5 at once (tvOS's 5:3 tiles, 300 × 180) and scrolls for the rest.
  // As in Apple's photo (DESIGN-TVOS.md §1): 6 columns of 244 × 146, 57 apart, 85 px margins; the Dock's glass pads them
  // 48 × 40 from y 782; the grid's rows are 250 apart, its first row peeking at y 1052 until ↓ lifts the page 722.
  var SHELF_VISIBLE = 6, FEW_APPS = 8, DOCK_DEFAULT = 6, TILE_W = 244, TILE_H = 146, TILE_GAP = 57, DOCK_PAD_X = 48, DOCK_PAD_Y = 40, DOCK_TOP = 782;
  var COLS = 6, GRID_LIFT = 722, GRID_ROW = 250, GRID_TOP = 330, GRID_LEFT = 85;
  // The Top Shelf's full screen (↑ on a pick) recesses the Dock this far and to this opacity; it never hides it.
  var RECESS = 36, RECESS_A = 0.75;

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
    if (app.guide) {   // an Apple guide: its symbol in white on its colours
      var g = GUIDES[app.guide];
      paint(box, g.top, g.bottom);
      sym(g.symbol, box).className += ' big';
      return;
    }
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
      for (var s = 0; s < SHELF.length && out.length < DOCK_DEFAULT; s++) {
        for (var a = 0; a < apps.length; a++) {
          if ((SHELF[s][1].test(apps[a].id) || SHELF[s][2].test(apps[a].title)) && out.indexOf(apps[a]) < 0) { out.push(apps[a]); break; }
        }
      }
      if (!out.length) out = apps.slice(0, FEW_APPS);
    }
    shelf = out;
    others = arrangeGrid(folders(gridOrder(apps.filter(function (x) { return shelf.indexOf(x) < 0; }))));
  }

  /*
   * All Apps' order after the Dock (as HomeApps.GRID_ORDER): by app id, else by the name the TV shows; the rest keep the
   * TV's own order after them. An app here never goes in the utilities folder.
   */
  var GRID_ORDER = [
    [/apple\.?music/i, /^apple music$/i], [/youtube\.?(tv)?music/i, /^youtube music$/i], [/spotify/i, /^spotify$/i],
    [/disney/i, /^disney/i], [/tubi/i, /^tubi$/i], [/youtube\.?(tv)?unplugged|youtubetv/i, /^youtube tv$/i],
    [/twitch/i, /^twitch$/i], [/hulu/i, /^hulu$/i], [/(^|\.)hbo|wbd/i, /^(hbo )?max$/i], [/peacock/i, /^peacock/i],
    [/paramount|cbs/i, /^paramount/i], [/instagram/i, /^instagram$/i], [/roblox/i, /^roblox$/i],
    // From here (TCL_FIRST) TCL's apps, in the TCL folder in this order.
    [/alexa/i, /alexa/i],
    [/media.?(center|player)/i, /^media ?player$/i], [/user.?guide|instructions/i, /^user ?guide$/i],
    [/quick.?panel|tcl\.suspension/i, /^quick ?panel$/i], [/t_?solo/i, /^t-?solo$/i], [/tcl.?home|tcl\.dashboard/i, /^tcl home$/i],
    [/exhibit/i, /^t-?exhibition$/i],
    // From here (TV_FIRST) what's for watching and the TV's general tools, first in TV & Utilities.
    [/^com\.tcl\.tv$/i, /^(live )?tv$/i], [/tclplus|tcl\.tv\.plus|tcl\.vod|waterfall/i, /^tcl ?tv\+?$/i],
    [/freeplay/i, /freeplay/i], [/browse/i, /browse/i]
  ];

  var TCL_FIRST = 13, TV_FIRST = 20;

  /**
   * One of TCL's utilities, for the TCL folder (as HomeApps.tclApp): GRID_ORDER's last ones, or another com.tcl app it
   * doesn't place; TCL's browser goes in TV & Utilities.
   */
  function tclApp(app) {
    var r = gridRank(app);
    if (r < GRID_ORDER.length) return r >= TCL_FIRST && r < TV_FIRST;
    return /^com\.tcl\./i.test(app.id || '') && !/browse/i.test((app.id || '') + ' ' + (app.title || ''));
  }

  function gridRank(app) {
    for (var i = 0; i < GRID_ORDER.length; i++) if (GRID_ORDER[i][0].test(app.id || '') || GRID_ORDER[i][1].test(app.title || '')) return i;
    return GRID_ORDER.length;
  }

  /** The listed apps first, in GRID_ORDER's order; the others after, in the order the TV gave them (a stable sort). */
  function gridOrder(list) {
    var ranked = list.map(function (x, i) { return {app: x, rank: gridRank(x), at: i}; });
    ranked.sort(function (a, b) { return a.rank !== b.rank ? a.rank - b.rank : a.at - b.at; });
    return ranked.map(function (r) { return r.app; });
  }

  // The TV's own utilities (its settings, store, browser, files, inputs…): together in one folder at the end of All Apps.
  var UTILITY = /browser|settings|store|google\.play|play store|file|media ?player|gallery|help|manual|guide|update|account|smartthings|tv-viewer|livetv|channel|input|hdmi|tcl|usb|miracast|freeplay/i;

  /*
   * All Apps' list with two folders at its end, so it fits one screen (as HomeApps.folders): "TCL" (the maker's apps,
   * in GRID_ORDER's order) and "TV & Utilities" (settings, store, files…). A folder of one stays an app.
   */
  function folders(list) {
    var tcl = list.filter(tclApp);
    var util = list.filter(function (x) {
      var rank = gridRank(x);
      return tcl.indexOf(x) < 0 && ((rank >= TV_FIRST && rank < GRID_ORDER.length) || (!shelfKey(x) && gridRank(x) === GRID_ORDER.length && (UTILITY.test(x.id) || UTILITY.test(x.title))));
    });
    var rest = list.filter(function (x) { return tcl.indexOf(x) < 0 && util.indexOf(x) < 0; });
    appleFolder(rest);
    addFolder(rest, tcl, 'folder:tcl', 'TCL');   // the maker's name
    addFolder(rest, util, 'folder:utilities', tr('TV & Utilities'));
    return rest;
  }

  /*
   * FaceTime, Fitness+, Podcasts and Apple Music where the TV lacks them (as AppleGuideActivity): how to AirPlay them
   * from an iPhone, with a QR code to Apple's page. Nothing here pretends to be the app.
   */
  var GUIDES = {
    music: {title: 'Apple Music', symbol: 'music', top: '#2C2C2E', bottom: '#000000', qr: 'applemusic', steps: function () {
      return [tr('Play music in Apple Music on your iPhone or iPad.'), tr('Tap the AirPlay button and choose this TV.'),
        tr("Or install Apple Music for Android TV from Google Play, where it's offered.")];
    }},
    facetime: {title: 'FaceTime', symbol: 'videocam', top: '#65DE6A', bottom: '#1DB33A', qr: 'facetime', steps: function () {
      return [tr('Start the FaceTime call on your iPhone or iPad.'), tr('Open Control Center and choose Screen Mirroring, then this TV.'),
        tr('Keep your iPhone near you: its camera and microphone carry the call.')];
    }},
    fitness: {title: 'Fitness+', symbol: 'fitness', top: '#2C2C2E', bottom: '#000000', qr: 'fitness', steps: function () {
      return [tr('Open the Fitness app on your iPhone or iPad and choose a workout.'), tr('Tap the AirPlay button and choose this TV.'),
        tr('Your Apple Watch keeps the rings and heart rate on your wrist.')];
    }},
    podcasts: {title: 'Podcasts', symbol: 'podcasts', top: '#D56DFB', bottom: '#8C2AE8', qr: 'podcasts', steps: function () {
      return [tr('Play an episode in Podcasts on your iPhone or iPad.'), tr('Tap the AirPlay button and choose this TV.'),
        tr('Scan the code to browse Apple Podcasts on the web.')];
    }}
  };

  function guideApp(key) { return {id: 'guide:' + key, title: GUIDES[key].title, guide: key}; }

  /** "Apple", where Apple Music goes: Apple Music (installed, else its guide), then FaceTime, Fitness+ and Podcasts. */
  function appleFolder(rest) {
    var at = 0, music = null;
    for (var i = 0; i < rest.length; i++) if (!rest[i].folder && gridRank(rest[i]) === 0) { music = rest[i]; at = i; break; }
    var installed = false;   // Apple Music on the Dock: it stays only there, no guide for it in the folder
    for (var j = 0; j < apps.length; j++) if (gridRank(apps[j]) === 0) installed = true;
    if (music) rest.splice(at, 1);
    else if (!installed) music = guideApp('music');
    var list = [guideApp('facetime'), guideApp('fitness'), guideApp('podcasts')];
    if (music) list.unshift(music);
    rest.splice(at, 0, {id: 'folder:apple', title: 'Apple', folder: true, apps: list});
  }

  var guideNode = null;

  // MARK: - AirPods: an AirPods or Beats output joining (on the TV: AirPods.java); in the preview, A or the CC tiles

  var airpods = null, airTimer = 0, airNode = null;

  function toggleAirPods() {
    var bt = CONFIG.previewBluetooth || [], name = null;
    for (var i = 0; i < bt.length; i++) if (/airpods|beats/i.test(bt[i])) { name = bt[i]; break; }
    setAirPods(airpods ? null : (name || 'AirPods Pro'));
  }

  /** Joined (a name) or gone (null): the card slides in for 3.5 s, the glyph by the bubble, the HUD's buds. */
  function setAirPods(name) {
    airpods = name;
    if (hud) setClass(hud.node, 'air', !!name);
    renderPill();
    ccRefresh();
    clearTimeout(airTimer);
    if (!name) { mv.air.set(0); Motion.kick(); return; }
    if (!airNode) {
      airNode = el('div', '', $('stage'));
      airNode.id = 'airtoast';
      el('div', 'glass', airNode);
      sym('airpods', airNode).className += ' icon';
      el('div', 'title', airNode);
      el('div', 'sub', airNode, tr('Connected · Spatial Audio'));
    }
    airNode.querySelector('.title').textContent = name;
    mv.air.set(1);
    airTimer = setTimeout(function () { mv.air.set(0); Motion.kick(); }, 3500);
    Motion.kick();
  }

  function drawAir() {
    if (!airNode) return;
    var v = mv.air.x, a = Motion.clamp(v, 0, 1);
    T(airNode, 'translateY(' + r2(36 + 72 * v) + 'px)');
    A(airNode, a);
    V(airNode, a);
  }

  /** OK on a guide: its card over the home screen; OK or Back closes it. */
  function openGuide(key) {
    var g = GUIDES[key];
    closeGuide(true);
    var layer = guideNode = el('div', 'layer', $('stage'));
    layer.id = 'guide';
    el('div', 'veil', layer);
    var card = el('div', 'card', layer), plate = el('div', 'plate', card);
    paint(plate, g.top, g.bottom);
    sym(g.symbol, plate);
    el('div', 'title', card, g.title);
    el('div', 'sub', card, tr('Use your iPhone with AirPlay'));
    var steps = el('ol', 'steps', card), list = g.steps();
    for (var i = 0; i < list.length; i++) {
      var li = el('li', '', steps);
      el('span', 'n', li, String(i + 1));
      el('span', 't', li, list[i]);
    }
    var qr = el('div', 'qr', card), img = el('img', '', qr);
    img.src = 'qr/' + g.qr + '.png';
    el('div', 'scan', card, tr('Scan with your iPhone'));
    el('div', 'foot', card, tr('AirPlay needs a TV that supports it (Settings › Channels & Inputs › Apple AirPlay & HomeKit).'));
    layer.onclick = function () { closeGuide(); };
    setTimeout(function () { setClass(layer, 'open', true); }, 20);
  }

  function closeGuide(quiet) {
    var layer = guideNode;
    if (!layer) return;
    guideNode = null;
    setClass(layer, 'open', false);
    setTimeout(function () { if (layer.parentNode) layer.parentNode.removeChild(layer); }, quiet ? 0 : 320);
  }

  function addFolder(into, apps, id, title) {
    if (apps.length === 1) into.push(apps[0]);
    if (apps.length > 1) into.push({id: id, title: title, folder: true, apps: apps});
  }

  // MARK: - Motion (ANIMATIONS-SPEC.md): springs from motion.js drive every transform that can change mid-flight

  var P = Motion.P, BUMP = Motion.BUMP_VELOCITY, scrubbing = false, lastMove = {dx: 0, dy: 0, at: 0};
  // The glare's sweep: a smooth, critically damped glide (no bounce: light doesn't overshoot).
  var GLARE = [1.1, 1];

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
    // The glare (tvOS's hardware glint): a narrow band of light, with a 1.5 px bright edge on the bevel, that sweeps
    // across the focused tile the way the focus came, once per arrival. -1.3 … 1.3 tile half-widths; at rest, off it.
    this.glare = null;
    this.gl = spring(GLARE, 1.3);
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
    if (on) this.sweep(Date.now() - lastMove.at < 120 ? lastMove.dx : 0);
  };

  /** The glare crosses the tile: left to right when the focus came from the left (or up/down), else right to left. */
  Fx.prototype.sweep = function (dx) {
    if (!this.sheen || scrubbing || Motion.instant || Motion.lite) return;
    if (!this.glare) this.glare = el('span', 'glare', this.sheen.parentNode);
    var from = dx < 0 ? 1.3 : -1.3;
    this.gl.snap(from);
    this.gl.set(-from);
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
    m = this.gl.step(dt) || m;
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
    if (this.glare) {
      // Brightest crossing the middle, gone at the edges; it rides the tilt like the sheen.
      var g = this.gl.x, through = Motion.clamp(1 - Math.abs(g) / 1.3, 0, 1);
      A(this.glare, f * through * (1 - scrub.x));
      T(this.glare, 'translateX(' + r2(g * 150 + 4 * ry) + 'px) skewX(-18deg)');
    }
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
  var AIRPODS_TOAST = [0.38, 0.82];
  var mv = {
    lift: spring(P.SPATIAL_SCROLL).px(), tab: spring(P.TAB_SWAP), drawer: spring(P.SHEET_OPEN), full: spring(P.SPATIAL_SCROLL),
    dock: spring(P.SPATIAL_SCROLL).px(), par: spring(P.SPATIAL_SCROLL).px(), launch: spring(P.SHEET_OPEN), wake: spring(P.TAB_SWAP),
    heroX: spring(P.TITLE_SETTLE).px(), heroA: spring(P.TITLE_SETTLE, 1), heroShow: spring([0.3, 1], 1),
    lensX: spring(P.LIQUID_PILL).px(), lensW: spring(P.LIQUID_PILL, 100).px(), dot: spring(P.SNAPPY_FOCUS).px(),
    rowNudge: spring(P.BOUNDARY_BUMP).px(), recents: spring(P.SPATIAL_SCROLL),
    ccSub: spring(P.SHEET_OPEN), hud: spring(P.SHEET_OPEN), hudFill: spring(P.SNAPPY_FOCUS), hudBump: spring(P.BOUNDARY_BUMP),
    // brightFill: the brightness (Control Center's slider, the HUD in brightness mode); folder: an open folder 0..1.
    brightFill: spring(P.SNAPPY_FOCUS, Prefs.brightness() / 100), folder: spring(P.SHEET_OPEN), folderScroll: spring(P.SPATIAL_SCROLL).px(),
    // air: the AirPods card at the top right (0 hidden, 1 in place).
    air: spring(AIRPODS_TOAST)
  };
  var detail = [spring(P.TITLE_SETTLE), spring(P.TITLE_SETTLE), spring(P.TITLE_SETTLE)];
  var rowLag = [], gridRows = [], drawerRows = [], sheetSprings = {}, holders = {};
  var fxTiles = [], fxCells = [], fxTabs = [], fxKeys = [], fxWho = [], fxRecents = [], fxPill = null, fxWatch = null, launching = null;
  var fxBubble = null, fxFolder = [];
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
    if (moveTick(dt)) moving = true;
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
    A(apps, tabLayer(apps, 0, ' translateY(' + r2(722 - y + RECESS * full) + 'px)', behind) * (1 - (1 - RECESS_A) * full));
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
    // At night the art darkens only a little (the grid 0.25, Control Center 0.12 here plus #dim 0.22): no heavy scrims.
    A(lift, Math.max(p * (night ? 0.25 : 0.6), d * (night ? 0.12 : 0.32)));
    A(q('#sky .shade'), away * (night ? 0.22 : 0.34));
    A(q('#dim'), d * (night ? 0.22 : 0.18));
    T(q('#art'), 'translateX(' + r2(mv.par.x) + 'px)');
    // The Dock: its scroll; in full screen it only recesses (36 px lower, 0.75 opacity), it never leaves.
    var rec = RECESS * full, recA = 1 - (1 - RECESS_A) * full;
    T(q('#tiles'), 'translate(' + r2(-mv.dock.x) + 'px,' + r2(rec) + 'px)');
    T(q('#shadows'), 'translate(' + r2(-mv.dock.x) + 'px,' + r2(rec) + 'px)');
    T(q('#platter'), full ? 'translateY(' + r2(rec) + 'px)' : '');
    A(q('#platter'), recA);
    A(q('#tiles'), recA);
    A(q('#shadows'), recA);
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
    // Control Center: in from the right, its rows 18 ms apart; Inputs slides in over the tiles, which slide away.
    var drawer = q('#drawer'), da = Motion.clamp(d * 2.5, 0, 1), sub = Motion.clamp(mv.ccSub.x, 0, 1);
    T(drawer, 'translateX(' + r2(480 * (1 - d)) + 'px)');
    A(drawer, da);
    V(drawer, da);
    for (var k = 0; k < ccRows.length; k++) {
      var rx = drawerRows[k] ? drawerRows[k].x : 1, inGroup = ccRows[k].group;
      T(ccRows[k].node, 'translateX(' + r2(28 * (1 - rx) - (inGroup ? 40 * sub : 0)) + 'px)');
      A(ccRows[k].node, rx * (inGroup ? 1 - sub : 1));
    }
    if (ccInputsNode) {
      T(ccInputsNode, 'translateX(' + r2(40 * (1 - sub)) + 'px)');
      A(ccInputsNode, sub);
      V(ccInputsNode, sub);
    }
    // The volume: the HUD slides in from the left edge; its fill (and Control Center's) follow the level.
    var level = Motion.clamp(mv.hudFill.x, 0, 1);
    if (hud) {
      var hs = Motion.clamp(mv.hud.x, 0, 1);
      T(hud.node, 'translateX(' + r2(-40 * (1 - hs)) + 'px) scale(1,' + r4(1 + 0.03 * Motion.clamp(mv.hudBump.x, -1, 1)) + ')');
      A(hud.node, hs);
      V(hud.node, hs);
      T(hud.fill, 'scale(1,' + r4(hudMode === 'brightness' ? Motion.clamp(mv.brightFill.x, 0, 1) : level) + ')');
    }
    if (ccVolume) T(ccVolume.fill, 'scale(' + r4(level) + ',1)');
    drawFolder();
    drawMove();
    drawCard();
    drawAir();
    if (recentRow) placeRecents(mv.recents.x);
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

  var Z = {TOP: 'top', HERO: 'hero', SHELF: 'shelf', DRAWER: 'drawer', APPS: 'apps', SETTINGS: 'settings', EDIT: 'edit', LANG: 'lang', CITY: 'city', KEY: 'key', WALLPAPER: 'wallpaper', ABOUT: 'about',
    TABS: 'tabs', HOUSE: 'house', WELLY: 'welly', RECENTS: 'recents', FOLDER: 'folder'};
  var TAB = {TV: 0, HOME: 1, WELLY: 2};
  var st = {zone: Z.SHELF, tab: TAB.TV, top: 3, page: 0, shelf: 0, drawer: 0, grid: 0, row: 0, edit: 0, lang: 0, city: 0, key: 0, wallpaperCategory: 0, wallpaperOption: 0, wallpaperPane: 0,
    recent: 0, ccInput: 0, folder: 0};
  var cards = [];
  var topFromTab = null;   // the tab ↑ left for the bubble (← goes back to it)
  // The pointer only moves the focus once it has really moved since the last key: a mouse resting on the screen
  // doesn't steal it while the arrows slide the grid or the Dock under it.
  var keyAt = 0, mouseAt = 0, mouseX = -1, mouseY = -1;
  document.addEventListener('mousemove', function (e) {
    if (e.clientX === mouseX && e.clientY === mouseY) return;
    mouseX = e.clientX; mouseY = e.clientY; mouseAt = Date.now();
  });
  var was = {drawer: false, full: false, row: null};

  function sheetOpen() { return st.zone === Z.APPS || st.zone === Z.SETTINGS || st.zone === Z.EDIT || st.zone === Z.LANG || st.zone === Z.CITY || st.zone === Z.KEY || st.zone === Z.WALLPAPER || st.zone === Z.ABOUT || st.zone === Z.RECENTS || st.zone === Z.FOLDER; }

  function focusList(list, on, index) {
    for (var i = 0; i < list.length; i++) {
      setClass(list[i].node.parentNode && hasClass(list[i].node, 'face') ? list[i].node.parentNode : list[i].node, 'focus', on && index === i);
      list[i].focus(on && index === i);
    }
  }

  function updateFocus() {
    var i, list;
    // The top right: Control Center's bubble is st.top 3, the pill (the time and you: the profile panel) right of it 4.
    var onBubble = st.zone === Z.TOP && st.top === 3, onPill = st.zone === Z.TOP && st.top === 4;
    setClass($('pill'), 'focus', onPill);
    if (fxPill) fxPill.focus(onPill);
    setClass($('ccbtn'), 'focus', onBubble);
    if (fxBubble) fxBubble.focus(onBubble);
    focusList(fxFolder, st.zone === Z.FOLDER, st.folder);
    setClass($('watch'), 'focus', st.zone === Z.HERO);
    if (fxWatch) fxWatch.focus(st.zone === Z.HERO);
    setClass(body, 'full', st.zone === Z.HERO);
    focusList(fxTiles, st.zone === Z.SHELF, st.shelf);
    revealShelf();
    placeName();
    ccFocus();
    focusList(fxRecents, st.zone === Z.RECENTS, st.recent);
    // Recent Apps: the deck follows the focus (the cards before it stack up behind it).
    mv.recents.set(st.recent);
    focusList(fxCells, st.zone === Z.APPS, st.grid);
    // The grid shows 2 rows under the risen Dock; past them, the page (Dock and all) scrolls on.
    var rows = Math.max(1, Math.ceil(others.length / COLS)), r = Math.floor(st.grid / COLS), first = Math.max(0, Math.min(rows - 2, r - 1));
    var grid = st.zone === Z.APPS || st.zone === Z.FOLDER, lift = grid ? GRID_LIFT + first * GRID_ROW : 0;
    if (lift !== mv.lift.to) {
      mv.lift.set(lift);
      for (i = 0; i < rowLag.length; i++) later(rowLag[i], lift, 18 * i);
    }
    if (st.zone === Z.SETTINGS) focusSettings();
    focusRows($('shelfedit'), st.edit, st.zone === Z.EDIT);
    focusRows($('langpick'), st.lang, st.zone === Z.LANG);
    focusRows($('citypick'), st.city, st.zone === Z.CITY);
    focusRows($('keypick'), st.key, st.zone === Z.KEY);
    focusRows($('wallpaperpick'), st.wallpaperPane ? st.wallpaperOption : st.wallpaperCategory, st.zone === Z.WALLPAPER);
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
    setClass(body, 'drawer-profile', ccMode === 'profile');   // which button opened it lights up
    if (drawerOpen !== was.drawer) {
      was.drawer = drawerOpen;
      mv.drawer.tune(drawerOpen ? P.CC_OPEN : P.SHEET_CLOSE).set(drawerOpen ? 1 : 0);
      for (i = 0; i < drawerRows.length; i++) {
        if (drawerOpen) { drawerRows[i].snap(0); later(drawerRows[i], 1, 18 * i, P.CC_OPEN); }
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

  /** The sheets (Home Settings, Shelf, Language, Wallpaper, About) open on SHEET_OPEN and close on SHEET_CLOSE. */
  function syncSheets() {
    var ids = ['settings', 'shelfedit', 'langpick', 'citypick', 'keypick', 'wallpaperpick', 'about', 'recents'];
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
      case Z.TOP: return st.top === 3 ? fxBubble : fxPill;
      case Z.FOLDER: return fxFolder[st.folder] || null;
      case Z.HERO: return fxWatch;
      case Z.DRAWER: return ccFocusedFx();
      case Z.RECENTS: return fxRecents[st.recent] || null;
    }
    return null;
  }

  /** Where the focus is across the screen (0 left … 1 right): the tick is panned there. */
  function focusPan() {
    var fx = focusedFx(), r = fx && fx.node && fx.node.getBoundingClientRect && fx.node.getBoundingClientRect();
    var w = window.innerWidth || 1920;
    return r && r.width ? Math.max(0, Math.min(1, (r.left + r.width / 2) / w)) : 0.5;
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
    hover($('ccbtn'), Z.TOP, 3);
    hover($('pill'), Z.TOP, 4);
    hover($('watch'), Z.HERO, 0);
    if (!fxPill) fxPill = new Fx($('pill'), {grow: 0.08, avatar: $('pill').querySelector('.avatar')});
    if (!fxBubble) { sym('controls', $('ccbtn')); fxBubble = new Fx($('ccbtn'), {grow: 0.12}); }
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
    Profiles.fitAvatar(person(), avatar);
    // Right to left: the pill, Control Center's bubble 14 px from it, then the quotes.
    $('ccbtn').style.right = (45 + pill.offsetWidth + 14) + 'px';
    var air = $('airglyph'), right = 45 + pill.offsetWidth + 14 + 48 + 24;
    if (air) {
      air.style.right = (right - 8) + 'px';
      setClass(air, 'on', !!airpods);
      if (airpods) right += 40 + 14;
    }
    $('quotes').style.right = right + 'px';
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

  /** The first streaming app in the Dock (All Continue Watching shows its title on any Dock app). */
  function firstMedia() {
    for (var i = 0; i < shelf.length; i++) {
      var d = mediaDescriptor(shelf[i]);
      if (d.media && !d.music) return d;
    }
    return null;
  }

  /**
   * Re-evaluate on every focus/profile/recommendation/settings update. The Top Shelf (Settings or the welcome setup):
   * 'app' the focused streaming app's Continue Watching (the Frosted Horizon elsewhere), 'all' a Continue Watching title
   * whatever app is focused, 'picks' the recommendations whatever app is focused, 'off' only the wallpaper.
   */
  function refreshBackground() {
    var mode = Prefs.topShelf(), app = mediaDescriptor(shelf[st.shelf]);
    if (mode === 'all' && !app.media) app = firstMedia() || app;
    var wantsMedia = mode !== 'off' && st.tab === TAB.TV && Wallpaper.media(st.zone, mode === 'picks' ? cards.length > 0 : app.media);
    var key = wantsMedia ? (mode + ':' + st.zone + ':' + app.id + ':' + cards.length + ':' + Prefs.version) : 'profile:' + Prefs.wallpaper() + ':' + Prefs.version;
    // A media resolver can change Continue Watching/Now Playing without
    // changing the app, zone, recommendation count, or preference revision.
    // Re-resolve media focus; generations still reject older async answers.
    if (key === backgroundKey && !wantsMedia) return;
    backgroundKey = key;
    backgroundGeneration++;
    var generation = backgroundGeneration;
    if (!wantsMedia) { applyProfileBackground(); return; }
    if (mode === 'picks') {
      var featured = pageForMedia(cards[Math.min(st.page, cards.length - 1)]);
      if (!featured) { applyProfileBackground(); return; }
      backgroundMode = 'media';
      mediaPage = null;
      setClass(body, 'profile-background', false);
      setClass(body, 'media-background', true);
      showPage(Math.min(st.page, cards.length - 1), 0);
      aerialVideo.hide();
      return;
    }
    Data.resolveWatch(app, generation, function (resolvedGeneration, pick) {
      if (resolvedGeneration !== backgroundGeneration || generation !== backgroundGeneration ||
          !Wallpaper.media(st.zone, mode === 'all' || mediaDescriptor(shelf[st.shelf]).media) || st.tab !== TAB.TV) return;
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

  /*
   * The adaptive glass (TVOS-LAUNCHER-MERGE-SPEC.md V4): how bright a photo is behind the Dock and Control Center,
   * measured once per photo on a 32 × 18 copy of its bottom band. Bright art gets a light frost with dark ink instead
   * of the smoky glass, so the glass keeps its contrast without any dark scrim. A TV that won't let the page read the
   * image (another server, no CORS) keeps the smoky glass.
   */
  var artBright = {};
  function measureArt(url, then) {
    if (artBright[url] !== undefined) { then(artBright[url]); return; }
    var probe = new Image();
    probe.crossOrigin = 'anonymous';
    probe.onload = function () {
      var bright = false;
      try {
        var c = document.createElement('canvas');
        c.width = 32;
        c.height = 18;
        var g = c.getContext('2d'), w = probe.naturalWidth, h = probe.naturalHeight;
        g.drawImage(probe, 0, h * 0.62, w, h * 0.38, 0, 0, 32, 18);
        var px = g.getImageData(0, 0, 32, 18).data, sum = 0;
        for (var i = 0; i < px.length; i += 4) sum += luminance(px[i], px[i + 1], px[i + 2]);
        bright = sum / (px.length / 4) > 0.36;
      } catch (e) {}
      artBright[url] = bright;
      then(bright);
    };
    probe.onerror = function () { artBright[url] = false; then(false); };
    probe.src = url;
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
      setClass(body, 'art-bright', false);
      return;
    }
    var img = new Image();
    measureArt(url, function (bright) {
      if (generation === photoGeneration && photoUrl === url) setClass(body, 'art-bright', bright);
    });
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
    var tilesWidth = n * tw + Math.max(0, n - 1) * gap;
    // While an app moves the glass keeps its width (dockSlots), the tiles centred in it.
    n = Math.min(Math.max(n, dockSlots), SHELF_VISIBLE);
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
    tileLeft = (1920 - tilesWidth) / 2;
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

  // MARK: - Control Center: a right-hand Liquid Glass panel, as tvOS's

  // Two panels under the top-right cluster, as tvOS's. Control Center (its bubble) is a 2-column grid of pills in a
  // fixed order (Power | Audio Output, Wi-Fi | Bluetooth, Dark Mode | Do Not Disturb, Sleep Timer | Brightness) with the
  // volume slider under it. The profile panel (the pill) holds the profiles, the weather, Inputs | Screensaver and a
  // row of Lock, Home Settings and TV Settings cells; Inputs' list slides over what's under the weather (ccSub) and the
  // panel grows to fit it. Everything focusable is one entry of ccItems (its row, node, spring, what OK does): ← → walk
  // the row (on the volume they change it), ↑ ↓ go to the nearest entry of the next row. The tiles inside are flat (the
  // panel is the glass: never glass on glass).
  var CC = {left: 1300, top: 124, pad: 28, body: 504, gap: 16, col: 260, tileW: 244, tileH: 72, tileGap: 12,
    whoH: 104, platterH: 152, volumeH: 72, mediaH: 96, cellH: 92, cellGap: 12, inputH: 60, inputGap: 6, headH: 52};
  /** Which panel is drawn: 'controls' (the bubble) or 'profile' (the pill); how tall it is closed and with Inputs open. */
  var ccMode = 'controls', ccHeights = {closed: 0, open: 0};

  /** The panel as tall as what shows: its content, or the Inputs list while that's open. */
  function fitDrawer() {
    $('drawer').style.height = ((ccSub ? ccHeights.open : ccHeights.closed) + 2 * CC.pad) + 'px';
  }
  var PREVIEW = !!window.PREVIEW_BUILD;
  // The preview stands in for the Google TV app (what LG and Samsung web apps can't do shows a note); these two only
  // keep a state there so the tiles can be tried.
  var previewCC = {dnd: false, sleep: 0};
  /** Android's playback card follows a real media session; LG and Samsung have none, and the preview shows a sample. */
  var previewMedia = {at: 0, playing: true, songs: [['Sample Song', 'Sample Artist · Spotify'], ['Second Song', 'Sample Artist · Spotify'], ['Third Song', 'Sample Artist · Spotify']]};
  var ccItems = [], ccRows = [], ccGroup = null, ccInputsNode = null, ccInputItems = [], ccSub = false, ccVolume = null, ccBright = null;
  var ccLastRow = 0, drawerWeather = false, recentList = [], recentRow = null;

  function lastInput() {
    try { return localStorage.getItem('fmtv.lastInput') || ''; } catch (e) { return ''; }
  }

  function setLastInput(id) {
    try { localStorage.setItem('fmtv.lastInput', id); } catch (e) {}
  }

  /** The input last opened from here (no TV says which one shows): the first, Live TV, until one is picked. */
  function currentInput() {
    var id = lastInput();
    for (var i = 0; i < inputs.length; i++) if (inputs[i].id === id) return id;
    return inputs.length ? inputs[0].id : '';
  }

  function inputName(x) { return x.label || tr('Live TV'); }

  function googleOnly() { toast(tr('Only on Google TV')); }

  function darkOn() { return hasClass(body, 'night'); }

  /** Dark Mode: this profile's theme, Light or Dark (Settings → Appearance still offers Automatic). */
  function toggleDark() {
    Prefs.set('theme', darkOn() ? 'light' : 'dark');
    checkNight();
  }

  /** Brightness from Control Center: OK steps it down a quarter, and from 25 back to full (the HUD shows the level). */
  var BRIGHT_STEPS = [100, 75, 50, 25];
  function cycleBrightness() {
    var b = Prefs.brightness(), next = 100;
    for (var i = 0; i < BRIGHT_STEPS.length; i++) if (BRIGHT_STEPS[i] < b) { next = BRIGHT_STEPS[i]; break; }
    Prefs.set('brightness', next);
    mv.brightFill.set(next / 100);
    applyBrightness();
    showHud('brightness');
  }

  function onOff(on) { return on ? tr('state|On') : tr('state|Off'); }

  var SLEEP_PRESETS = [0, 15, 30, 60, 120];

  /** 15 min, 1 h, 2 h (as Android's SleepTimer.duration). */
  function sleepName(m) { return m < 60 ? trf('{n} min', {n: m}) : trf('{n} h', {n: m / 60}); }

  /**
   * Control Center's pills, in this order (2 columns): Power | Audio Output, Wi-Fi | Bluetooth, Dark Mode | Do Not
   * Disturb, Sleep Timer | Brightness. On LG and Samsung what a web app can do (Dark Mode, Brightness); the preview shows
   * the Google TV app's whole set.
   */
  function ccControls() {
    var t = [];
    if (PREVIEW) {
      t.push({label: tr('Power'), symbol: 'power', run: googleOnly});
      // preview_wifi / preview_bluetooth (config.properties) stand in for what the TV reports, as Android shows it.
      var bt = CONFIG.previewBluetooth || [], wifi = CONFIG.previewWifi;
      // Audio Output and Bluetooth: in the preview they connect the AirPods (or let them go), as A does.
      t.push({label: tr('Audio Output'), symbol: function () { return airpods ? 'airpods' : 'speaker'; }, run: toggleAirPods,
        detail: function () { return airpods || bt[0] || tr('TV Speakers'); }});
      t.push({label: tr('Wi-Fi'), symbol: 'wifi', run: googleOnly, active: function () { return true; }, detail: function () { return wifi || tr('Connected'); }});
      t.push({label: tr('Bluetooth'), symbol: 'bluetooth', run: toggleAirPods, active: function () { return !!airpods || bt.length > 0; },
        detail: function () { return airpods || bt[0] || onOff(false); }});
    }
    t.push({label: tr('Dark Mode'), symbol: 'appearance', run: toggleDark, active: darkOn, detail: function () { return onOff(darkOn()); }});
    if (PREVIEW) {
      t.push({label: tr('Do Not Disturb'), symbol: 'moon', pop: true, run: function () { previewCC.dnd = !previewCC.dnd; },
        active: function () { return previewCC.dnd; }, detail: function () { return onOff(previewCC.dnd); }});
      // tvOS 17's presets: Off, 15 min, 30 min, 1 h, 2 h.
      t.push({label: tr('Sleep Timer'), glyph: 'glyph-timer', run: function () { previewCC.sleep = SLEEP_PRESETS[(SLEEP_PRESETS.indexOf(previewCC.sleep) + 1) % SLEEP_PRESETS.length]; },
        active: function () { return !!previewCC.sleep; },
        detail: function () { return previewCC.sleep ? sleepName(previewCC.sleep) : onOff(false); }});
    }
    t.push({label: tr('Brightness'), symbol: 'sunny', run: cycleBrightness, detail: function () { return Prefs.brightness() + '%'; }});
    if (PREVIEW) {
      // As on a TCL Google TV: Speaker Mode (T-Solo: the TV's sound on a phone), and beside it Listen Alone (AirPods on
      // the TV, through its pairing screen); here, A's stand-in AirPods.
      t.push({label: tr('Speaker Mode'), symbol: 'speaker_phone', run: googleOnly});
      t.push({label: tr('Listen Alone'), symbol: 'airpods', run: toggleAirPods, active: function () { return !!airpods; },
        detail: function () { return airpods || tr('Pair AirPods'); }});
    }
    return t;
  }

  /** The profile panel's pills: Inputs (the input in use under its name) and the Screensaver. */
  function ccProfilePills() {
    var t = [];
    if (inputs.length) {
      t.push({label: tr('Inputs'), symbol: 'input', run: openInputsSheet, detail: function () {
        var id = currentInput();
        for (var i = 0; i < inputs.length; i++) if (inputs[i].id === id) return inputName(inputs[i]);
        return '';
      }});
    }
    t.push({label: tr('Screensaver'), symbol: 'screensaver', run: function () { closeDrawer(); showSaver(); }});
    return t;
  }

  /** The profile panel's last row: Lock, Home Settings and the TV's settings, cells as wide as they can share. */
  function ccCells() {
    var b = [{label: tr('Lock'), glyph: 'glyph-lock', run: lockNow},
      {label: tr('Home Settings'), symbol: 'settings', run: function () { openSettings(); }}];
    if (TV.canOpenSettings || PREVIEW) b.push({label: tr('TV Settings'), symbol: 'tv_settings', run: TV.canOpenSettings ? tvSettings : googleOnly});
    return b;
  }

  /** A row of the panel, at `top` in `parent` (the panel's body, or the group that the inputs slide over). */
  function ccRow(parent, top, height, group) {
    var row = el('div', 'ccrow', parent);
    row.style.top = top + 'px';
    row.style.height = height + 'px';
    ccRows.push({node: row, group: !!group});
    return row;
  }

  /** One focusable entry: `cx` is its centre across the panel, for ↑ ↓ to find the nearest one in the next row. */
  function ccAdd(node, fx, run, kind, cx) {
    var item = {node: node, fx: fx, run: run, kind: kind, row: ccRows.length - 1, cx: cx};
    ccItems.push(item);
    if (fx) (kind === 'who' ? fxWho : fxKeys).push(fx);
    hover(node, Z.DRAWER, ccItems.length - 1);
    return item;
  }

  function ccGlyph(spec, parent) {
    if (spec.glyph) return el('span', spec.glyph, parent);
    return sym(typeof spec.symbol === 'function' ? spec.symbol() : spec.symbol, parent);
  }

  /** The panel ccMode says: Control Center's controls, or the profile panel. */
  function renderDrawer() {
    var d = $('drawer').lastChild;
    d.innerHTML = '';
    killFx(fxKeys);
    killFx(fxWho);
    ccItems = [];
    ccRows = [];
    ccVolume = ccBright = ccInputsNode = ccGroup = null;
    ccSub = false;
    setClass($('drawer'), 'profile-panel', ccMode === 'profile');
    ccHeights = ccMode === 'profile' ? renderProfilePanel(d) : renderControls(d);
    fitDrawer();
    while (drawerRows.length < ccRows.length) drawerRows.push(spring(P.SHEET_OPEN, 1));
    drawerRows.length = ccRows.length;
    renderPanels();
    showVolumeLevel();
    showBrightLevel();
    ccRefresh();
    updateFocus();
  }

  /** Two pills a row in the order given, from `y` in `parent`; returns the y under the last row. */
  function ccPillRows(parent, y, pills, group) {
    for (var k = 0; k < pills.length; k += 2) {
      var row = ccRow(parent, y, CC.tileH, group);
      for (var c = 0; c < 2 && k + c < pills.length; c++) ccTile(row, pills[k + c], c, pills.length === 1);
      y += CC.tileH + CC.tileGap;
    }
    return pills.length ? y - CC.tileGap : y;
  }

  /** The playback card (as HomeView's): what's playing and its three buttons. OK plays or pauses, ← → skip. */
  function ccMediaRow(row) {
    var v = el('div', 'ccmedia', row), song = el('div', 'song', v), line = el('div', 'line', song), sub = el('div', 'sub', song);
    var buttons = el('div', 'mediabtns', v);
    el('span', 'mb prev', buttons);
    var play = el('span', 'mb play', buttons);
    el('span', 'mb next', buttons);
    function show() {
      var s = previewMedia.songs[previewMedia.at];
      line.textContent = s[0];
      sub.textContent = s[1];
      setClass(play, 'paused', !previewMedia.playing);
    }
    var item = ccAdd(v, new Fx(v, {grow: 0.02}), function () { previewMedia.playing = !previewMedia.playing; show(); }, 'hslider', CC.body / 2);
    item.step = function (dx) {
      var n = previewMedia.songs.length;
      previewMedia.at = (previewMedia.at + (dx > 0 ? 1 : n - 1)) % n;
      show();
    };
    show();
  }

  /** Control Center: the playback card (in the preview), the grid of pills, then the volume. Returns how tall it is. */
  function renderControls(d) {
    var y = 0;
    if (PREVIEW) {
      ccMediaRow(ccRow(d, 0, CC.mediaH));
      y = CC.mediaH + CC.tileGap;
    }
    y = ccPillRows(d, y, ccControls(), false);
    if (volumeLevel !== null) {
      y += CC.gap;
      ccVolume = ccVolumeRow(ccRow(d, y, CC.volumeH));
      y += CC.volumeH;
    }
    return {closed: y, open: y};
  }

  /** The profile panel: the profiles, the weather, Inputs | Screensaver, the cells. Returns how tall it is (and with Inputs open). */
  function renderProfilePanel(d) {
    var y = 0, row, list = people(), np = list.length, step = Math.min(116, Math.floor(CC.body / Math.max(1, np)));
    // The profiles first, the current one ringed; OK opens the lock on that profile.
    row = ccRow(d, y, CC.whoH);
    row.className += ' profiles';
    var x0 = Math.round((CC.body - np * step) / 2);
    for (var w = 0; w < np; w++) {
      var who = el('div', 'who' + (list[w].id === person().id ? ' on' : ''), row);
      who.style.left = (x0 + w * step) + 'px';
      who.style.width = step + 'px';
      var faceEl = el('span', 'face', who);
      faceEl.style.backgroundImage = avatarUrl(list[w]);
      Profiles.fitAvatar(list[w], faceEl);
      el('span', 'name', who, list[w].name);
      ccAdd(who, new Fx(faceEl, {grow: 0.12}), profileRun(list[w]), 'who', x0 + w * step + step / 2);
    }
    y += CC.whoH + CC.gap;
    // The rest is the group the Inputs list slides over: the weather (OK opens the floating weather card), the pills
    // and the cells, each row filling the panel's width.
    var sheetTop = y, grp = ccGroup = el('div', 'ccgroup', d), gy = 0;
    grp.style.top = y + 'px';
    drawerWeather = Prefs.weather();
    if (drawerWeather) {
      row = ccRow(grp, gy, CC.platterH, true);
      var wc = el('div', '', row);
      wc.id = 'weather';
      ccAdd(wc, null, function () { if (Data.weather) openForecast(); }, 'weather', CC.body / 2);
      gy += CC.platterH + CC.tileGap;
    }
    gy = ccPillRows(grp, gy, ccProfilePills(), true) + CC.tileGap;
    var cells = ccCells(), cw = (CC.body - (cells.length - 1) * CC.cellGap) / cells.length;
    row = ccRow(grp, gy, CC.cellH, true);
    for (var b = 0; b < cells.length; b++) ccCell(row, cells[b], b * (cw + CC.cellGap), cw);
    gy += CC.cellH;
    renderInputsSheet(d, sheetTop);
    var sheetH = CC.headH + inputs.length * (CC.inputH + CC.inputGap) - CC.inputGap;
    return {closed: y + gy, open: y + Math.max(gy, sheetH)};
  }

  function profileRun(who) {
    return function () {
      closeDrawer();
      if (who.id !== person().id) showLock({profile: who, pin: true, cancel: true, from: 'switch'});
    };
  }

  /** A wide pill: a round glyph (blue when the thing is on), the name and its state under it. */
  function ccTile(row, spec, col, wide) {
    var tile = el('div', 'cctile' + (spec.detail ? '' : ' solo') + (wide ? ' wide' : ''), row);
    tile.style.left = (col * CC.col) + 'px';
    var dot = el('span', 'dot', tile), glyph = ccGlyph(spec, dot);
    el('div', 'label', tile, spec.label);
    var detail = spec.detail ? el('div', 'detail', tile) : null;
    var item = ccAdd(tile, new Fx(tile, {grow: wide ? 0.03 : 0.05}), function () { spec.run(); ccRefresh(); if (spec.pop) popGlyph(tile, dot); }, 'tile', wide ? CC.body / 2 : col * CC.col + CC.tileW / 2);
    item.spec = spec;
    item.detail = detail;
    item.glyph = glyph;
  }

  /** Do Not Disturb turned on: its moon swings in (tvOS's micro-animation); off, it only fades back to grey. */
  function popGlyph(tile, dot) {
    if (Motion.instant || !hasClass(tile, 'on')) return;
    setClass(dot, 'pop', false);
    void dot.offsetWidth;
    setClass(dot, 'pop', true);
    setTimeout(function () { setClass(dot, 'pop', false); }, 700);
  }

  /** A cell of the profile panel's last row: its glyph over its name, `w` wide at `x`. */
  function ccCell(row, spec, x, w) {
    var cell = el('div', 'cccell', row);
    cell.style.left = Math.round(x) + 'px';
    cell.style.width = Math.round(w) + 'px';
    var g = el('span', 'icon', cell);
    ccGlyph(spec, g);
    el('div', 'label', cell, spec.label);
    ccAdd(cell, new Fx(cell, {grow: 0.05}), spec.run, 'cell', x + w / 2);
  }

  /** The volume: the speaker, the level's track and its number in one capsule; ← → change it, OK mutes. */
  function ccVolumeRow(row) {
    var v = el('div', 'ccvolume', row);
    sym('volume', v);
    var track = el('div', 'track', v), fill = el('div', 'fill', track), num = el('div', 'num', v);
    var item = ccAdd(v, new Fx(v, {grow: 0.03}), function () { stepVolume('mute'); }, 'hslider', CC.body / 2);
    item.step = stepVolume;
    return {node: v, fill: fill, num: num, h: true};
  }

  /** The panel's states (a toggle, a name, Inputs' last choice, the theme), without building it again. */
  function ccRefresh() {
    for (var i = 0; i < ccItems.length; i++) {
      var it = ccItems[i];
      if (!it.spec) continue;
      setClass(it.node, 'on', !!(it.spec.active && it.spec.active()));
      if (it.name && it.spec.name) it.name.textContent = it.spec.name();
      if (it.glyph && typeof it.spec.symbol === 'function') {   // Audio Output: the speaker, or the AirPods
        var mask = 'url(symbols/' + it.spec.symbol() + '.png)';
        it.glyph.style.webkitMaskImage = it.glyph.style.maskImage = mask;
      }
      if (it.detail) {
        var text = it.spec.detail();
        it.detail.textContent = text;
        setClass(it.node, 'solo', !text);
      }
    }
  }

  /** The volume's number. */
  function sliderLevel(sl, n) {
    if (sl) sl.num.textContent = String(n);
  }

  /** Inputs: the antenna and the HDMI ports, ✓ on the last one opened from here; Back or ← returns to the tiles. */
  function renderInputsSheet(d, top) {
    var s = ccInputsNode = el('div', 'ccinputs', d);
    s.style.top = top + 'px';
    var head = el('div', 'cchead', s);
    sym('chevron_right', head).className += ' back';
    el('span', 'title', head, tr('Inputs'));
    ccInputItems = [];
    for (var i = 0; i < inputs.length; i++) {
      var x = inputs[i], r = el('div', 'inrow' + (x.connected ? '' : ' off'), s);
      r.style.top = (CC.headH + i * (CC.inputH + CC.inputGap)) + 'px';
      sym(x.symbol || 'hdmi', el('span', 'dot', r));
      el('div', 'name', r, inputName(x));
      if (!x.connected) el('div', 'status', r, tr('No signal'));
      else setClass(r, 'solo', true);
      if (x.id === currentInput()) sym('check', r).className += ' check';
      ccInputItems.push({node: r, fx: new Fx(r, {grow: 0.04}), run: inputRun(x)});
      fxKeys.push(ccInputItems[i].fx);
      inputHover(r, i);
    }
  }

  function inputRun(x) {
    return function () {
      setLastInput(x.id);
      closeInputsSheet();
      closeDrawer();
      launcher(x.id, x.label, null, true)();
      renderDrawer();
    };
  }

  function inputHover(node, i) {
    node.onmouseover = function () {
      if (st.zone !== Z.DRAWER || !ccSub) return;
      st.ccInput = i;
      updateFocus();
    };
    node.onclick = function () { mouseAt = Date.now() + 1; node.onmouseover(); activate(); };
  }

  function openInputsSheet() {
    if (!ccInputItems.length) return;
    if (!ccSub) Sound.sheet(true, 0.78);
    ccSub = true;
    st.ccInput = 0;
    var id = currentInput();
    for (var i = 0; i < inputs.length; i++) if (inputs[i].id === id) st.ccInput = i;
    mv.ccSub.tune(P.CC_OPEN).set(1);
    fitDrawer();
    updateFocus();
  }

  /** Back to the tiles, on Inputs. */
  function closeInputsSheet(quiet) {
    if (!ccSub) return;
    if (!quiet) Sound.sheet(false, 0.78);
    ccSub = false;
    mv.ccSub.tune(P.SHEET_CLOSE).set(0);
    fitDrawer();
    for (var i = 0; i < ccItems.length; i++) if (ccItems[i].spec && ccItems[i].spec.run === openInputsSheet) st.drawer = i;
    if (!quiet) updateFocus();
  }

  /** Whether entry `c` is in row `r` (the sliders span two). */
  function ccIn(c, r) { return r >= c.row && r <= (c.rowEnd === undefined ? c.row : c.rowEnd); }

  /** The entry next to `it` in its row (dx ±1), or −1; on a slider, the row the focus came from. */
  function ccNeighbour(it, dx) {
    var r = it.rowEnd === undefined ? it.row : Math.max(it.row, Math.min(it.rowEnd, ccLastRow)), row = [], i;
    for (i = 0; i < ccItems.length; i++) if (ccIn(ccItems[i], r)) row.push(i);
    row.sort(function (a, b) { return ccItems[a].cx - ccItems[b].cx; });
    var at = row.indexOf(ccItems.indexOf(it)) + dx;
    if (at >= 0 && at < row.length) {
      if (ccItems[row[at]].rowEnd === undefined) ccLastRow = ccItems[row[at]].row;
      return row[at];
    }
    return -1;
  }

  /** The entry in the next row that way (dy ±1) nearest under or over `it`, or −1; the weather without data is skipped. */
  function ccVertical(it, dy) {
    var from = dy > 0 && it.rowEnd !== undefined ? it.rowEnd : it.row;
    for (var r = from + dy; r >= 0 && r < ccRows.length; r += dy) {
      var best = -1, dist = 1e9;
      for (var i = 0; i < ccItems.length; i++) {
        var c = ccItems[i];
        if (c === it || !ccIn(c, r) || (c.kind === 'weather' && !Data.weather)) continue;
        var dd = Math.abs(c.cx - it.cx);
        if (dd < dist) { dist = dd; best = i; }
      }
      if (best >= 0) { ccLastRow = r; return best; }
    }
    return -1;
  }

  function ccFocusedFx() {
    if (ccSub) return ccInputItems[st.ccInput] ? ccInputItems[st.ccInput].fx : null;
    var it = ccItems[st.drawer];
    return it ? it.fx : null;
  }

  /** Focus classes and springs for the panel (updateFocus). */
  function ccFocus() {
    var open = st.zone === Z.DRAWER, i;
    for (i = 0; i < ccItems.length; i++) {
      var on = open && !ccSub && st.drawer === i;
      setClass(ccItems[i].node, 'focus', on);
      if (ccItems[i].fx) ccItems[i].fx.focus(on);
    }
    for (i = 0; i < ccInputItems.length; i++) {
      var o = open && ccSub && st.ccInput === i;
      setClass(ccInputItems[i].node, 'focus', o);
      ccInputItems[i].fx.focus(o);
    }
  }

  function renderPanels() {
    var w = $('weather');
    if (!w) return;
    w.innerHTML = '';
    if (Prefs.weather()) renderWeather(w);
  }

  /** The weather platter: the city, the temperature and sky on the left (220 px), 4 hours (or days) to the right of it. */
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
    el('div', 'range', card, trf('H:{max}  L:{min}', {max: Data.degrees(w.max), min: Data.degrees(w.min)}));
    // The next 4 hours (or days, with Home Settings → Forecast), in their own columns: nothing runs into the temperature.
    var days = Prefs.forecast() === 'days' && w.days && w.days.length > 0, list = days ? w.days : w.hours, n = 4;
    var hours = el('div', 'hours', card), hw = 252 / n;
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

  /** Opens a panel: 'controls' (Control Center, the default) or 'profile'; the focus on its first control, or on you. */
  function openDrawer(mode) {
    mode = mode === 'profile' ? 'profile' : 'controls';
    if (mode !== ccMode || !ccItems.length) {
      ccMode = mode;
      renderDrawer();
    }
    if (!ccItems.length) return;
    if (st.zone !== Z.DRAWER) Sound.sheet(true, 0.82);   // Control Center comes from the top right
    drawerFrom = st.zone === Z.DRAWER ? drawerFrom : st.zone;
    st.zone = Z.DRAWER;
    st.drawer = ccMode === 'profile' ? Profiles.index() : 0;
    ccLastRow = 0;
    if (ccSub) closeInputsSheet(true);
    ccRefresh();
    renderPill();
    updateFocus();
  }

  /** Back where the focus was (the button that opened it, or the Dock). */
  function closeDrawer() {
    if (st.zone === Z.DRAWER) Sound.sheet(false, 0.82);
    if (ccSub) { ccSub = false; mv.ccSub.snap(0); fitDrawer(); }
    st.zone = drawerFrom === Z.TOP || drawerFrom === Z.HERO || drawerFrom === Z.TABS ? drawerFrom : Z.SHELF;
    updateFocus();
  }

  // MARK: - The volume: the TV's own level, and a Liquid Glass HUD on the left (tvOS's volume, on the leading edge)

  var volumeLevel = null, volumeMuted = false, hud = null, hudTimer = 0, hudPinned = false, HUD_HOLD = 2200;

  /** Reads the TV's volume once at start: where it can't be read (most TVs keep it to themselves), no volume row or HUD. */
  function readVolume() {
    TV.volume(0, function (level, muted) {
      if (level === null) return;
      var had = volumeLevel !== null;
      setVolume(level, muted);
      mv.hudFill.snap(volumeMuted ? 0 : level / 100);
      if (!had) renderDrawer();
    });
  }

  /** + / − (or the TV's volume keys): a step, as the remote's keys; 'mute' toggles mute. The HUD shows the result. */
  function stepVolume(dir) {
    var was = volumeLevel;
    TV.volume(dir, function (level, muted) {
      if (level === null) return;   // this TV keeps its volume to itself: its own volume bar shows instead
      var had = was !== null;
      setVolume(level, muted);
      if (!had) renderDrawer();
      // Past either end: a rubber stretch, and it springs back (BOUNDARY_BUMP).
      if (dir !== 'mute' && level === was && (level === 0 || level === 100)) mv.hudBump.impulse(Motion.BUMP_VELOCITY * 0.04);
      showHud();
    });
  }

  function setVolume(level, muted) {
    volumeLevel = level;
    volumeMuted = !!muted;
    mv.hudFill.set(volumeMuted ? 0 : level / 100);
    showVolumeLevel();
    Motion.kick();
  }

  /** The HUD's and Control Center's number and glyph for the level (mute, then 1, 2 or 3 arcs). */
  function showVolumeLevel() {
    var level = volumeMuted ? 0 : volumeLevel || 0, arcs = level === 0 ? 0 : level < 34 ? 1 : level < 67 ? 2 : 3;
    if (hud && hudMode === 'volume') {
      hud.num.textContent = volumeMuted ? '' : String(level);
      hud.node.className = 'arcs' + arcs + (volumeMuted || level === 0 ? ' muted' : '') +
        (level >= 19 ? ' lit' : '') + (level >= 31 ? ' numlit' : '') + (hudPinned ? ' pinned' : '') + (airpods ? ' air' : '');
    }
    if (ccVolume) { sliderLevel(ccVolume, level); if (volumeMuted) ccVolume.num.textContent = tr('Muted'); }
  }

  // MARK: - Brightness: a dimmer over everything, up to 0.65 black at 0 (as brightness(0.35)); Control Center, [ and ]

  var hudMode = 'volume';

  function applyBrightness() {
    var b = Prefs.brightness(), a = (100 - b) / 100 * 0.65;
    A($('bright'), a);
    V($('bright'), a);
  }

  function showBrightLevel() {
    var b = Prefs.brightness();
    sliderLevel(ccBright, b);
    if (hud && hudMode === 'brightness') {
      hud.num.textContent = String(b);
      hud.node.className = 'bright' + (b >= 19 ? ' lit' : '') + (b >= 31 ? ' numlit' : '') + (hudPinned ? ' pinned' : '');
    }
  }

  /** [ and ] (and Control Center's slider): a step of 5; past either end the HUD stretches and springs back. */
  function stepBrightness(dir) {
    var was = Prefs.brightness(), b = Math.max(0, Math.min(100, was + 5 * dir));
    if (b !== was) Prefs.set('brightness', b);
    else mv.hudBump.impulse(Motion.BUMP_VELOCITY * 0.04);
    mv.brightFill.set(b / 100);
    applyBrightness();
    showHud('brightness');
  }

  function buildHud() {
    if (hud) return;
    var node = el('div', '', $('stage'));
    node.id = 'volhud';
    el('div', 'glass', node);
    var fill = el('div', 'fill', node), num = el('div', 'num', node), spk = el('div', 'spk', node);
    sym('sunny', node).className += ' sunglyph';
    sym('airpods', node).className += ' airglyph';
    el('span', 'cone', spk);
    el('span', 'arc a1', spk);
    el('span', 'arc a2', spk);
    el('span', 'arc a3', spk);
    el('span', 'slash', spk);
    hud = {node: node, fill: fill, num: num};
    setClass(node, 'air', !!airpods);
    showVolumeLevel();
  }

  /** The HUD on the left, for the volume (the default) or the brightness. */
  function showHud(mode) {
    hudMode = mode || 'volume';
    buildHud();
    if (hudMode === 'brightness') showBrightLevel();
    else showVolumeLevel();
    mv.hud.tune(P.SHEET_OPEN).set(1);
    clearTimeout(hudTimer);
    if (!hudPinned) hudTimer = setTimeout(hideHud, HUD_HOLD);
    Motion.kick();
  }

  function hideHud() {
    mv.hud.tune(P.SHEET_CLOSE).set(0);
    Motion.kick();
  }

  /** V in the preview: the HUD stays (to look at it), V again lets it go. */
  function pinHud() {
    if (volumeLevel === null) return;
    hudPinned = !hudPinned;
    if (hudPinned) showHud();
    else { showVolumeLevel(); hideHud(); }
  }

  /** The volume keys: the TV's own (when it sends them to the app), + and − on a keyboard. */
  function volumeKey(e, typing) {
    var k = e.keyCode, key = e.key, dir = 0;
    if (k === TV.keys.volumeUp || k === 175) dir = 1;
    else if (k === TV.keys.volumeDown || k === 174) dir = -1;
    else if (k === TV.keys.mute || k === 173) dir = 'mute';
    else if (!typing && (k === 187 || k === 107 || key === '+' || key === '=')) dir = 1;
    else if (!typing && (k === 189 || k === 109 || key === '-' || key === '_')) dir = -1;
    if (!dir) return false;
    stepVolume(dir);
    return true;
  }

  // MARK: - The Home button (H in a browser): a press goes to the Dock, a hold (450 ms) shows Recent Apps

  var homeTimer = 0, HOME_HOLD = 450;

  function homeDown() {
    if (homeTimer) return;
    if (st.zone === Z.RECENTS) { goHome(); return; }   // Home over the switcher takes it away at once
    Sound.holdStart();   // the tension builds toward Recent Apps from the frame H goes down
    homeTimer = setTimeout(function () {
      homeTimer = 0;
      Sound.holdCommit();
      if (st.zone !== Z.RECENTS) openRecents();
    }, HOME_HOLD);
  }

  function homeUp() {
    if (homeTimer) { clearTimeout(homeTimer); homeTimer = 0; Sound.holdCancel(); goHome(); }
  }

  var homing = false;

  /** Home: the TV tab with the Dock focused, everything else closed (the screensaver, sheets, Recent Apps, the forecast). */
  function goHome() {
    if (mm || naming) cancelMove(true);
    if (saverOn) hideSaver();
    if (Forecast.showing()) { homing = true; Forecast.close(); homing = false; }
    if (ccSub) closeInputsSheet(true);
    st.tab = TAB.TV;
    closeSheets();
    st.zone = shelf.length ? Z.SHELF : Z.TABS;
    updateFocus();
  }

  /** S in a browser: the screensaver now, and S again takes it away. */
  function toggleSaver() {
    if (saverOn) { hideSaver(); return; }
    if (st.zone === Z.DRAWER) closeDrawer();
    showSaver();
  }

  // MARK: - The forecast and Recent Apps (TVOS-LAUNCHER-MERGE-SPEC.md S3, S4)

  /** OK on Control Center's weather card: the 24-hour / 7-day forecast over the Horizon; Back comes back to the card. */
  function openForecast() {
    var at = st.drawer;
    Forecast.open($('forecast'), function () {
      if (!saverOn && !homing) { openDrawer('profile'); st.drawer = at; updateFocus(); }
    });
    closeDrawer();
  }

  /** An installed app by its id (the TV's app list). */
  function appById(id) {
    for (var i = 0; i < apps.length; i++) if (apps[i].id === id) return apps[i];
    return null;
  }

  /**
   * The installed app a log entry was opened in: its app id (`pkg`, as Android's RecentsOverlay), else, for entries
   * logged before that was kept, the app whose service key (`appKey`: 'netflix', 'disney'…) matches.
   */
  function appForEntry(e) {
    var app = e.pkg ? appById(e.pkg) : null;
    if (app || !e.appKey) return app;
    app = appById(e.appKey);
    for (var i = 0; !app && i < apps.length; i++) {
      var key = shelfKey(apps[i]);
      if (key && key[0] === e.appKey) app = apps[i];
    }
    return app;
  }

  /** The profile's last apps, newest first: from mahitTV's own launch log, each once, still installed (12 at most). */
  function recentApps() {
    var log = Profiles.log(), out = [], seen = {};
    for (var i = 0; i < log.length && out.length < 12; i++) {
      var app = appForEntry(log[i]);
      if (!app || seen[app.id]) continue;
      seen[app.id] = true;
      out.push(app);
    }
    return out;
  }

  // Recent Apps as tvOS's switcher: big 16:9 cards (845 × 475), the focused one centred; the ones after it to its right,
  // 794 apart (6 % overlap); the ones before it stacked up behind its left edge (tvOS-Launcher's deck: the first 313 px
  // out, then 101 px each). The deck follows a spring on the focus (mv.recents), so it glides between cards.
  var RECENT_W = 845, RECENT_H = 475, RECENT_STEP = 794, RECENT_X = 538, RECENT_Y = 302;

  /** Where card i is with the focus at f (fractional while it moves): x, scale, opacity, stacking. */
  function placeRecents(f) {
    var cards = recentRow.children;
    for (var i = 0; i < cards.length; i++) {
      var d = i - f, a = Math.abs(d), x;
      if (d >= 0) x = RECENT_X + d * RECENT_STEP;
      else x = RECENT_X - (a <= 1 ? a * 313 : 313 + (a - 1) * 101);
      var s = 1 - 0.06 * Math.min(1, a), alpha = d < 0 ? Motion.clamp(4 - a, 0, 1) : Motion.clamp(2.2 - d, 0, 1);
      // Depth: the cards beside the focused one turn up to 9° toward it, their outer edges receding (a deck, not a strip).
      var turn = 9 * Motion.clamp(d, -1, 1);
      T(cards[i], 'translate(' + r2(x) + 'px,0) perspective(2000px) rotateY(' + r2(turn) + 'deg) scale(' + r4(s) + ')');
      A(cards[i], alpha);
      cards[i].style.zIndex = String(40 - Math.round(a * 4));
      A(cards[i].firstChild, Motion.clamp(1 - a * 1.6, 0, 1));   // the app's name above the focused card only
    }
  }

  /** Recent Apps (Home held, or H held in a browser): the deck over the Frosted Horizon; OK opens one (logged). */
  function openRecents() {
    Sound.sheet(true, 0.5);
    var layer = $('recents');
    layer.innerHTML = '';
    killFx(fxRecents);
    el('div', 'hz hz-day', layer);
    el('div', 'hz hz-night', layer);
    recentList = recentApps();
    var row = recentRow = el('div', 'row', layer);   // drawPage places its cards (rebuilt on every open)
    for (var i = 0; i < recentList.length; i++) {
      var app = recentList[i], card = el('div', 'card', row);
      var hdr = el('div', 'hdr', card), badge = el('span', 'badge', hdr);
      face(app, badge, 12);
      el('span', 'name', hdr, app.title);
      var f = el('div', 'face', card);
      face(app, f, wordFit(app.title, RECENT_W, 64));
      hover(card, Z.RECENTS, i);
      fxRecents.push(new Fx(f, {grow: 0.02, squish: 0.04}));
    }
    if (!recentList.length) el('div', 'empty', layer, tr('No recent apps yet.'));
    st.recent = 0;
    mv.recents.snap(0);
    if (st.zone === Z.DRAWER) closeDrawer();
    st.zone = Z.RECENTS;
    setClass(layer, 'open', true);
    placeRecents(0);
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
      if (others[i].folder) folderFace(others[i], f);
      else face(others[i], f, wordFit(others[i].title, TILE_W, 34));
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

  // MARK: - Folders: a frosted squircle with four of its apps in All Apps; OK zooms it open over the home screen

  // As tvOS 9.2's folders: 3 rows in view, the rest a scroll away (the grid slides; the card stays put).
  var FOLDER_COLS = 3, FOLDER_ROWS = 3, FOLDER_GAP = 40, FOLDER_PAD = 60, FOLDER_ROW = TILE_H + 84;
  var folderOpen = null, folderCard = null, folderGrid = null, folderTop = 0, folderFrom = {x: 0, y: 0};

  /** As many across as keep a folder's rows even (AppGrid.folderColumns): 2×2, 3×2, 4×2, 3×3, 4×3; past 12, 3 and a scroll. */
  function folderColumns(n) {
    if (n === 3 || n === 5 || n === 6 || n === 9) return 3;
    if (n <= 4) return 2;
    return n <= 12 ? 4 : 3;
  }

  /** The folder's tile: frosted glass, its first four apps as a 2×2 of little tiles. */
  function folderFace(folder, box) {
    setClass(box, 'folderface', true);
    for (var i = 0; i < Math.min(4, folder.apps.length); i++) {
      var mini = el('div', 'mini', box);
      mini.style.left = (18 + (i % 2) * 110) + 'px';
      mini.style.top = (14 + Math.floor(i / 2) * 64) + 'px';
      face(folder.apps[i], mini, 12);
    }
  }

  /** OK on a folder: its apps in a floating frosted card, springing out of the folder's tile. */
  function openFolder(folder, fromFx) {
    Sound.sheet(true, 0.5);
    folderOpen = folder;
    FOLDER_COLS = folderColumns(folder.apps.length);
    var layer = $('folder'), n = folder.apps.length, cols = Math.min(FOLDER_COLS, n), rows = Math.min(FOLDER_ROWS, Math.ceil(n / FOLDER_COLS));
    layer.innerHTML = '';
    killFx(fxFolder);
    el('div', 'veil', layer);
    var w = 2 * FOLDER_PAD + cols * TILE_W + (cols - 1) * FOLDER_GAP, h = 156 + rows * FOLDER_ROW + 24;
    var card = folderCard = el('div', 'card', layer);
    card.style.width = w + 'px';
    card.style.height = h + 'px';
    card.style.left = Math.round((1920 - w) / 2) + 'px';
    card.style.top = Math.round((1080 - h) / 2) + 'px';
    el('div', 'badge', card, folder.title);
    // The apps scroll inside a window as tall as the rows in view (with room for a focused tile's lift and name).
    var view = el('div', 'view', card);
    view.style.height = (h - 92) + 'px';
    folderGrid = el('div', 'grid', view);
    folderTop = 0;
    for (var i = 0; i < n; i++) {
      var cell = el('div', 'cell', folderGrid);
      cell.style.left = (FOLDER_PAD + (i % FOLDER_COLS) * (TILE_W + FOLDER_GAP)) + 'px';
      cell.style.top = (40 + Math.floor(i / FOLDER_COLS) * FOLDER_ROW) + 'px';
      var sh = el('div', 'lift-shadow', cell), f = el('div', 'face', cell);
      face(folder.apps[i], f, wordFit(folder.apps[i].title, TILE_W, 34));
      var sheen = el('span', 'sheen', f), label = el('div', 'label', cell, folder.apps[i].title);
      folderHover(cell, i);
      fxFolder.push(new Fx(f, {grow: 0.2, squish: 0.08, tilt: true, shadow: sh, img: f.querySelector('img'), sheen: sheen, label: label}));
    }
    // From the folder's tile: the card starts there, small, and springs to the middle.
    var from = fromFx && fromFx.node ? fromFx.node.getBoundingClientRect() : null;
    folderFrom = from ? {x: (from.left + from.right) / 2 - 960, y: (from.top + from.bottom) / 2 - 540} : {x: 0, y: 0};
    mv.folderScroll.snap(0);
    st.zone = Z.FOLDER;
    st.folder = 0;
    fitLogos(layer);
    mv.folder.tune(P.SHEET_OPEN).set(1);
    updateFocus();
  }

  /** Back (or an app opening): the card zooms back into its tile, the focus on the folder. */
  function closeFolder(quiet) {
    if (!folderOpen) return;
    if (!quiet) Sound.sheet(false, 0.5);
    folderOpen = null;
    mv.folder.tune(P.SHEET_CLOSE).set(0);
    if (st.zone === Z.FOLDER) st.zone = Z.APPS;
    if (!quiet) updateFocus();
  }

  function folderHover(node, i) {
    node.onmouseover = function () { if (keyAt > mouseAt || st.zone !== Z.FOLDER) return; st.folder = i; updateFocus(); };
    node.onclick = function () { mouseAt = Date.now() + 1; node.onmouseover(); activate(); };
  }

  /** Keeps the focused app's row in view: the grid slides a row at a time (SPATIAL_SCROLL, through mv.folderScroll). */
  function folderScroll() {
    if (!folderGrid) return;
    var row = Math.floor(st.folder / FOLDER_COLS);
    if (row < folderTop) folderTop = row;
    if (row > folderTop + FOLDER_ROWS - 1) folderTop = row - FOLDER_ROWS + 1;
    mv.folderScroll.set(-folderTop * FOLDER_ROW);
  }

  function drawFolder() {
    if (folderGrid) T(folderGrid, 'translateY(' + r2(mv.folderScroll.x) + 'px)');
    var a = Motion.clamp(mv.folder.x, 0, 1), layer = $('folder');
    A(layer, a);
    V(layer, a);
    if (folderCard) T(folderCard, 'translate(' + r2(folderFrom.x * (1 - a)) + 'px, ' + r2(folderFrom.y * (1 - a)) + 'px) scale(' + r4(0.3 + 0.7 * a) + ')');
  }

  // MARK: - Moving apps (hold OK on one in the Dock, All Apps or an open folder), as the Android app: it rises out of
  // its place and rides a cursor that goes line, outline, line… (white, with a dark edge, on the light grid as on the
  // dark one). A line puts it between two apps; an outline makes a folder of the two (on a folder: into it; on a Dock
  // app: they swap). OK does it, Back puts everything back. The board itself is in layout.js.

  var HOLD_MS = 500, PART = 12, RING_W = TILE_W + 34, RING_H = TILE_H + 34, LINE_H = TILE_H + 32;
  var mm = null, naming = null, holdTimer = 0, dockSlots = 0, wiggle = 0;
  mv.curX = spring([0.26, 0.86]).px();
  mv.curY = spring([0.26, 0.86]).px();
  mv.lineA = spring([0.22, 1]);
  mv.ringA = spring([0.22, 1]);
  mv.moveA = spring([0.3, 1]);
  mv.carryX = spring([0.36, 0.8]).px();
  mv.carryY = spring([0.36, 0.8]).px();
  mv.carryS = spring([0.36, 0.86], 1);
  mv.carryA = spring([0.24, 1], 1);
  mv.nameA = spring(P.SHEET_OPEN);

  /** Something that can move has the focus: a Dock app, anything in All Apps, an app in an open folder. */
  function canMove() {
    if (mm || naming || st.tab !== TAB.TV) return false;
    if (st.zone === Z.FOLDER) return !!(folderOpen && folderOpen.apps[st.folder]);
    if (st.zone === Z.APPS) return !!others[st.grid];
    return st.zone === Z.SHELF && !!shelf[st.shelf];
  }

  function dockable(x) { return !x.folder && !x.guide; }

  /** Where a Dock tile and a grid cell are, on the page (the grid sits GRID_LIFT under the Dock and scrolls with it). */
  function dockAt(i) { return {x: tileLeft + i * tileStep - mv.dock.x, y: DOCK_TOP + DOCK_PAD_Y}; }
  function cellAt(i) { return {x: GRID_LEFT + (i % COLS) * (TILE_W + TILE_GAP), y: GRID_TOP + GRID_LIFT + Math.floor(i / COLS) * GRID_ROW}; }

  function slideOf(fx) {
    if (!fx.sx) { fx.sx = spring([0.32, 0.86]).px(); fx.sy = spring([0.32, 0.86]).px(); }
    return fx;
  }

  /** Before the lists change: where each tile is now (with its slide), by its id. */
  function positions() {
    var at = {}, i, p;
    for (i = 0; i < shelf.length; i++) {
      p = dockAt(i);
      at[shelf[i].id] = {x: p.x + (fxTiles[i] && fxTiles[i].sx ? fxTiles[i].sx.x : 0), y: p.y};
    }
    for (i = 0; i < others.length; i++) {
      p = cellAt(i);
      var fx = fxCells[i];
      at[others[i].id] = {x: p.x + (fx && fx.sx ? fx.sx.x : 0), y: p.y + (fx && fx.sy ? fx.sy.x : 0)};
    }
    return at;
  }

  /** New lists drawn, each tile sliding from where it was (`before`); a new folder from its first app's place. */
  function showLists(dock, grid, before) {
    shelf = dock;
    others = grid;
    renderShelf();
    if (st.zone === Z.APPS) renderApps();
    fitLogos($('tiles'));
    fitLogos($('apps'));
    var i, from, p;
    for (i = 0; i < fxTiles.length; i++) {
      from = before[shelf[i].id];
      p = dockAt(i);
      if (from && Math.abs(from.y - p.y) < 1) slideOf(fxTiles[i]).sx.snap(from.x - p.x);
      slideOf(fxTiles[i]).sx.set(0);
    }
    for (i = 0; i < fxCells.length; i++) {
      from = before[others[i].id] || (others[i].folder && others[i].apps.length ? before[others[i].apps[0].id] : null);
      p = cellAt(i);
      slideOf(fxCells[i]);
      if (from) { fxCells[i].sx.snap(from.x - p.x); fxCells[i].sy.snap(from.y - p.y); }
      fxCells[i].sx.set(0);
      fxCells[i].sy.set(0);
    }
    Motion.kick();
  }

  /** A key's app or folder; a folder whose apps changed is a new one (the one from before stays, for Back). */
  function boardItem(key) {
    var kids = mm.board.folders[key], it = mm.items[key];
    if (!kids) return it || mm.made[key];
    if (it && it.apps && sameIds(it.apps, kids)) return it;
    var made = mm.made[key];
    if (made && sameIds(made.apps, kids)) return made;
    var apps = [];
    for (var i = 0; i < kids.length; i++) if (mm.items[kids[i]]) apps.push(mm.items[kids[i]]);
    made = mm.made[key] = {id: key, title: it ? it.title : made ? made.title : tr('Folder'), folder: true, apps: apps};
    return made;
  }

  function sameIds(apps, ids) {
    if (apps.length !== ids.length) return false;
    for (var i = 0; i < ids.length; i++) if (apps[i].id !== ids[i]) return false;
    return true;
  }

  function showBoard(before) {
    var dock = [], grid = [], i, it;
    for (i = 0; i < mm.board.dock.length; i++) if ((it = mm.items[mm.board.dock[i]])) dock.push(it);
    for (i = 0; i < mm.board.grid.length; i++) if ((it = boardItem(mm.board.grid[i]))) grid.push(it);
    showLists(dock, grid, before);
  }

  /** It rises out of its place (the others close up behind it), and the cursor shows where it was. */
  function startMove() {
    if (!canMove()) return;
    var dock = [], grid = [], folders = {}, can = {}, items = {}, i, j, x;
    for (i = 0; i < shelf.length; i++) {
      dock.push(shelf[i].id);
      items[shelf[i].id] = shelf[i];
      if (dockable(shelf[i])) can[shelf[i].id] = true;
    }
    for (i = 0; i < others.length; i++) {
      x = others[i];
      grid.push(x.id);
      items[x.id] = x;
      if (dockable(x)) can[x.id] = true;
      if (!x.folder) continue;
      folders[x.id] = [];
      for (j = 0; j < x.apps.length; j++) {
        folders[x.id].push(x.apps[j].id);
        items[x.apps[j].id] = x.apps[j];
        if (dockable(x.apps[j])) can[x.apps[j].id] = true;
      }
    }
    var board = new Layout.Board(dock, grid, folders, can, COLS, Infinity), from;
    mm = {board: board, items: items, made: {}, shelf0: shelf.slice(), others0: others.slice(), grid0: st.zone !== Z.SHELF,
      shelfAt: st.shelf, gridAt: st.grid, carried: null, landing: null, at: Date.now()};
    if (st.zone === Z.FOLDER) {
      var card = folderCard, cell = folderGrid.children[st.folder], fk = folderOpen.id, fi = st.folder;
      from = {x: parseFloat(card.style.left) + parseFloat(cell.style.left), y: parseFloat(card.style.top) + 92 + parseFloat(cell.style.top) + mv.folderScroll.x + mv.lift.x};
      closeFolder(true);
      st.zone = Z.APPS;
      board.lift(Layout.FROM_FOLDER, fi, fk);
    } else if (st.zone === Z.APPS) {
      from = cellAt(st.grid);
      board.lift(Layout.FROM_GRID, st.grid, null);
    } else {
      from = dockAt(st.shelf);
      board.lift(Layout.FROM_DOCK, st.shelf, null);
    }
    mm.carried = items[board.lifted];
    dockSlots = Math.min(shelf.length, SHELF_VISIBLE);
    var layer = $('move');
    layer.innerHTML = '';
    mm.line = el('div', 'line', layer);
    mm.ring = el('div', 'ring', layer);
    var carry = mm.carry = el('div', 'cell carry', layer), f = el('div', 'face', carry);
    if (mm.carried.folder) folderFace(mm.carried, f);
    else face(mm.carried, f, wordFit(mm.carried.title, TILE_W, 34));
    fitLogos(layer);
    mv.carryX.snap(from.x + TILE_W / 2);
    mv.carryY.snap(from.y + TILE_H / 2);
    mv.carryS.snap(1.23);
    mv.carryA.snap(1);
    mv.moveA.set(1);
    mv.lineA.snap(1);
    mv.ringA.snap(0);
    var before = positions();
    before[board.lifted] = null;
    showBoard(before);
    aimCursor(true);
    setClass(layer, 'on', true);
  }

  /** The page at `lift` (the Dock's row: the top; a grid row: with the one above it), rows trailing as they do. */
  function setLift(lift) {
    if (lift === mv.lift.to) return;
    mv.lift.set(lift);
    for (var i = 0; i < rowLag.length; i++) later(rowLag[i], lift, 18 * i);
  }

  /** All Apps comes up under the Dock once the cursor goes below it. */
  function enterGrid() {
    if (st.zone === Z.APPS) return;
    Sound.sheet(true, 0.5);
    st.zone = Z.APPS;
    setClass($('apps'), 'open', true);
    setClass(body, 'grid-open', true);
    setClass(body, 'dock-focus', false);
    renderApps();
    fitLogos($('apps'));
  }

  /**
   * The cursor to the board's place, and around it: the apps beside a line part a little, an outlined app shrinks
   * onto the folder-to-be (an outlined folder grows to take it, a Dock app dips to swap), the row comes into view.
   */
  function aimCursor(snap) {
    var b = mm.board, r = b.row, p = b.pos, c = b.count(r), ring = p % 2 === 1, inDock = r === Layout.DOCK;
    var item = ring ? (p - 1) / 2 : -1, gap = ring ? -1 : p / 2, half = TILE_GAP / 2, x, y, i, j;
    if (!inDock) enterGrid();
    if (inDock) {
      var show = ring ? item : Math.min(gap, c - 1);
      if (show >= 0) { st.shelf = show; revealShelf(); }
      var off = tileLeft - mv.dock.to;
      y = DOCK_TOP + DOCK_PAD_Y + TILE_H / 2;
      if (!c) x = 960;
      else if (ring) x = off + item * tileStep + TILE_W / 2;
      else if (gap === 0) x = off - half;
      else if (gap === c) x = off + (c - 1) * tileStep + TILE_W + half;
      else x = off + gap * tileStep - half;
      setLift(st.zone === Z.APPS ? GRID_LIFT : 0);
    } else {
      var base = r * COLS;
      y = cellAt(base).y + TILE_H / 2;
      if (!c) x = GRID_LEFT - half;
      else if (ring) x = cellAt(base + item).x + TILE_W / 2;
      else if (gap === c) x = cellAt(base + c - 1).x + TILE_W + half;
      else x = cellAt(base + gap).x - half;
      setLift(GRID_LIFT + Math.max(0, Math.min(b.rows() - 2, r - 1)) * GRID_ROW);
    }
    for (i = 0; i < fxTiles.length; i++) {
      fxTiles[i].f.set(inDock && i === item ? -0.3 : 0);
      slideOf(fxTiles[i]).sx.set(inDock && gap >= 0 ? (i === gap - 1 ? -PART : i === gap ? PART : 0) : 0);
    }
    var cells = $('apps').lastChild.children;
    for (i = 0; i < fxCells.length; i++) {
      j = i - (inDock ? 0 : r * COLS);
      var here = !inDock && j >= 0 && j < c, on = here && j === item;
      fxCells[i].f.set(on ? (others[i].folder ? 0.25 : -0.3) : 0);
      slideOf(fxCells[i]).sx.set(here && gap >= 0 ? (j === gap - 1 ? -PART : j === gap ? PART : 0) : 0);
      if (cells[i]) setClass(cells[i], 'forming', on && !others[i].folder);
    }
    mv.curX.set(x);
    mv.curY.set(y);
    mv.lineA.set(ring ? 0 : 1);
    mv.ringA.set(ring ? 1 : 0);
    if (snap) { mv.curX.snap(x); mv.curY.snap(y); }
    updateHint();
    Motion.kick();
  }

  function moveCursor(dx, dy) {
    lastMove = {dx: dx, dy: dy, at: Date.now()};
    if (mm.board.move(dx, dy)) { aimCursor(false); Sound.tick(0.5); return; }
    mv.curX.impulse(dx * BUMP * 0.5);
    mv.curY.impulse(dy * BUMP * 0.5);
    Sound.bump(0.5 + 0.2 * dx);
  }

  /** Keys while moving; true when taken. */
  function moveKey(e) {
    var k = e.keyCode;
    if (isArrow(k)) {
      moveCursor(k === TV.keys.left ? -1 : k === TV.keys.right ? 1 : 0, k === TV.keys.up ? -1 : k === TV.keys.down ? 1 : 0);
      return true;
    }
    if (k === TV.keys.ok) {
      // The press that lifted it is still down (its repeats and release do nothing); a new press does it.
      if (!e.repeat && !okDown) commitMove();
      return true;
    }
    if (k === TV.keys.back || k === 27 || k === 8) { cancelMove(false); return true; }
    return true;
  }

  function commitMove() {
    if (!mm || mm.landing) return;
    if (mm.board.action() === Layout.NEW_FOLDER) { startNaming(null); return; }
    drop(null);
  }

  /** It goes where the cursor is (a new folder named `name`), everything slides into place, and it's saved. */
  function drop(name) {
    var b = mm.board, key = 'folder:u' + Date.now().toString(36), lifted = b.lifted, before = positions();
    var kind = b.drop(key);
    if (kind === Layout.NEW_FOLDER) {
      var kids = [];
      for (var i = 0; i < b.folders[key].length; i++) kids.push(mm.items[b.folders[key][i]]);
      mm.made[key] = {id: key, title: name || tr('Folder'), folder: true, apps: kids};
    }
    dockSlots = 0;
    showBoard(before);
    saveMove(kind);
    Sound.select();
    land(mm.items[lifted]);
  }

  function folderHolding(it) {
    for (var i = 0; i < others.length; i++) if (others[i].folder && others[i].apps.indexOf(it) >= 0) return others[i];
    return null;
  }

  /** After OK or Back: the carried tile flies to its place (into its folder, if it's in one); the focus follows it. */
  function land(it) {
    var di = shelf.indexOf(it), gi = others.indexOf(it), fade = di < 0 && gi < 0, dest = it, i;
    if (fade) { dest = folderHolding(it); gi = dest ? others.indexOf(dest) : -1; }
    for (i = 0; i < fxTiles.length; i++) { fxTiles[i].f.set(0); slideOf(fxTiles[i]).sx.set(0); }
    for (i = 0; i < fxCells.length; i++) { fxCells[i].f.set(0); slideOf(fxCells[i]).sx.set(0); }
    var cells = $('apps').lastChild.children;
    for (i = 0; i < cells.length; i++) setClass(cells[i], 'forming', false);
    if (di >= 0) {
      if (st.zone === Z.APPS) { setClass($('apps'), 'open', false); Sound.sheet(false, 0.5); }
      st.zone = Z.SHELF;
      st.shelf = di;
    } else if (gi >= 0) {
      enterGrid();
      st.grid = gi;
    }
    mm.landing = {item: dest, fade: fade, at: Date.now()};
    var node = landingNode();
    if (node && !fade) setClass(node, 'landing', true);
    mv.moveA.set(0);
    setClass($('movehint'), 'on', false);
    updateFocus();
  }

  function landingNode() {
    if (!mm || !mm.landing || !mm.landing.item) return null;
    var di = shelf.indexOf(mm.landing.item), gi = others.indexOf(mm.landing.item);
    if (di >= 0) return $('tiles').children[di];
    if (gi >= 0) return $('apps').lastChild.children[gi];
    return null;
  }

  /** Landed (or a key came first): the move is over and the tile it landed on shows again. */
  function endLanding() {
    if (!mm) return;
    var node = landingNode();
    if (node) setClass(node, 'landing', false);
    mm = null;
    setClass($('move'), 'on', false);
    $('move').innerHTML = '';
    setClass($('movehint'), 'on', false);
    updateFocus();
  }

  /** Back (or Home): everything as it was, and it flies back to its place (at once with `snap`). */
  function cancelMove(snap) {
    if (naming) closeNaming();
    if (!mm) return;
    if (mm.landing) { if (snap) endLanding(); return; }
    var it = mm.items[mm.board.lifted], before = positions();
    before[mm.board.lifted] = null;
    dockSlots = 0;
    if (mm.grid0) enterGrid();
    showLists(mm.shelf0, mm.others0, before);
    if (!mm.grid0 && st.zone === Z.APPS) { setClass($('apps'), 'open', false); st.zone = Z.SHELF; }
    st.shelf = mm.shelfAt;
    st.grid = mm.gridAt;
    Sound.sheet(false, 0.5);
    land(it);
    if (snap) endLanding();
  }

  /** Saves what OK did: the Dock when it changed, and All Apps' order and the person's folders (Layout.read's shape). */
  function saveMove(kind) {
    if (kind === Layout.NONE) return;
    var b = mm.board, ids = [], i;
    for (i = 0; i < mm.shelf0.length; i++) ids.push(mm.shelf0[i].id);
    if (ids.join('\n') !== b.dock.join('\n')) Prefs.set('shelf', b.dock.slice());
    var l = Layout.read(Prefs.layout()), keep = [];
    l.grid = b.grid.slice();
    for (i = 0; i < l.folders.length; i++) if (!b.dissolved[l.folders[i].key]) keep.push(l.folders[i]);
    l.folders = keep;
    var mine = [];
    for (i = 0; i < keep.length; i++) mine.push(keep[i].key);
    for (var t in b.touched) if (b.touched.hasOwnProperty(t) && mine.indexOf(t) < 0) mine.push(t);
    for (i = 0; i < mine.length; i++) {
      if (!b.folders[mine[i]]) continue;   // not in All Apps now (its apps on the Dock): kept as it was
      var f = Layout.folderOf(l, mine[i]), item = boardItem(mine[i]);
      if (!f) l.folders.push(f = {key: mine[i], name: '', apps: []});
      f.name = item ? item.title : f.name;
      f.apps = b.folders[mine[i]].slice();
    }
    Prefs.set('layout', l);
  }

  /** All Apps as this profile arranged it (layout.js): its order and its folders. */
  function arrangeGrid(list) {
    var l = Layout.read(Prefs.layout());
    if (!l.grid.length && !l.folders.length) return list;
    var byId = {}, natural = [], auto = {}, dock = [], out = {}, res = [], i, j;
    for (i = 0; i < shelf.length; i++) dock.push(shelf[i].id);
    for (i = 0; i < list.length; i++) {
      natural.push(list[i].id);
      byId[list[i].id] = list[i];
      if (!list[i].folder) continue;
      auto[list[i].id] = [];
      for (j = 0; j < list[i].apps.length; j++) { auto[list[i].id].push(list[i].apps[j].id); byId[list[i].apps[j].id] = list[i].apps[j]; }
    }
    var keys = Layout.arrange(l, natural, auto, dock, out);
    for (i = 0; i < keys.length; i++) {
      if (!out[keys[i]]) { if (byId[keys[i]]) res.push(byId[keys[i]]); continue; }
      var apps = [], mine = Layout.folderOf(l, keys[i]);
      for (j = 0; j < out[keys[i]].length; j++) if (byId[out[keys[i]][j]]) apps.push(byId[out[keys[i]][j]]);
      res.push({id: keys[i], title: mine && mine.name ? mine.name : byId[keys[i]] ? byId[keys[i]].title : tr('Folder'), folder: true, apps: apps});
    }
    return res;
  }

  /** One frame: the carried tile rides above the cursor and wiggles (landing: flies to its place), the tiles slide. */
  function moveTick(dt) {
    var moving = false, i, fx;
    for (i = 0; i < fxTiles.length; i++) {
      fx = fxTiles[i];
      if (fx.sx && fx.sx.step(dt)) moving = true;
      if (fx.sx && fx.ex !== fx.sx.x) { fx.ex = fx.sx.x; fx.dirty = true; }
    }
    for (i = 0; i < fxCells.length; i++) {
      fx = fxCells[i];
      if (!fx.sx) continue;
      if (fx.sx.step(dt) | fx.sy.step(dt)) moving = true;
      if (fx.ex !== fx.sx.x || fx.ey !== fx.sy.x) { fx.ex = fx.sx.x; fx.ey = fx.sy.x; fx.dirty = true; }
    }
    if (!mm) return moving;
    var now = Date.now();
    if (mm.landing) {
      var it = mm.landing.item, di = shelf.indexOf(it), gi = others.indexOf(it), p = null;
      if (di >= 0) p = {x: dockAt(di).x + (fxTiles[di] && fxTiles[di].sx ? fxTiles[di].sx.x : 0), y: dockAt(di).y};
      else if (gi >= 0) p = {x: cellAt(gi).x + fxCells[gi].sx.x, y: cellAt(gi).y + fxCells[gi].sy.x};
      if (p) { mv.carryX.set(p.x + TILE_W / 2); mv.carryY.set(p.y + TILE_H / 2); }
      mv.carryS.set(mm.landing.fade ? 0.3 : 1.23);
      mv.carryA.set(mm.landing.fade || !p ? 0 : 1);
      var settled = Math.abs(mv.carryX.x - mv.carryX.to) < 1 && Math.abs(mv.carryY.x - mv.carryY.to) < 1 && Math.abs(mv.carryS.x - mv.carryS.to) < 0.01;
      if (settled || now - mm.landing.at > 700) { endLanding(); return true; }
    } else {
      var ringOn = mm.board.pos % 2 === 1;
      mv.carryX.set(mv.curX.to);
      mv.carryY.set(mv.curY.to - (ringOn ? RING_H : LINE_H) / 2 - TILE_H * 0.54 / 2 + (ringOn ? 26 : 18));
      mv.carryS.set(0.54);
      mv.carryA.set(1);
    }
    var amp = mm.landing ? Math.max(0, 1 - (now - mm.landing.at) / 180) : 1;
    wiggle = 1.6 * amp * Math.sin((now - mm.at) * 2 * Math.PI / 300);
    return true;
  }

  function drawMove() {
    var hint = $('movehint');
    // Every frame (A and V only write what changed), so the card's last frame is always its resting one.
    var na = Motion.clamp(mv.nameA.x, 0, 1), card = $('namecard');
    A(card, na);
    V(card, na);
    if (naming && naming.card) T(naming.card, 'scale(' + r4(0.94 + 0.06 * na) + ')');
    if (!mm) return;
    T($('move'), 'translateY(' + r2(-mv.lift.x) + 'px)');
    setClass(hint, 'low', mv.lift.to > 300);
    var a = Motion.clamp(mv.moveA.x, 0, 1), x = mv.curX.x, y = mv.curY.x, la = Motion.clamp(mv.lineA.x, 0, 1), ra = Motion.clamp(mv.ringA.x, 0, 1);
    T(mm.line, 'translate(' + r2(x - 4) + 'px,' + r2(y - LINE_H / 2) + 'px) scaleY(' + r4(0.4 + 0.6 * la) + ')');
    A(mm.line, a * la);
    T(mm.ring, 'translate(' + r2(x - RING_W / 2) + 'px,' + r2(y - RING_H / 2) + 'px) scale(' + r4(0.86 + 0.14 * ra) + ')');
    A(mm.ring, a * ra);
    T(mm.carry, 'translate(' + r2(mv.carryX.x - TILE_W / 2) + 'px,' + r2(mv.carryY.x - TILE_H / 2) + 'px) rotate(' + r2(wiggle) + 'deg) scale(' + r4(mv.carryS.x) + ')');
    A(mm.carry, mv.carryA.x);
  }

  /** The hint: what OK does at the cursor, in words and on the keys. Over the home screen at the top, over All Apps at the bottom. */
  function updateHint() {
    var b = mm.board, t = b.target(), a = b.before(), c = b.after(), dock = b.row === Layout.DOCK, where, verb;
    function name(key) { var it = boardItem(key); return it ? it.title : ''; }
    switch (b.action()) {
      case Layout.NONE: where = tr('Where it was'); verb = tr('Done'); break;
      case Layout.NEW_FOLDER: where = trf('New folder with {app}', {app: name(t)}); verb = tr('Make Folder'); break;
      case Layout.ADD: where = trf('Add to {folder}', {folder: name(t)}); verb = tr('Add'); break;
      case Layout.SWAP: where = trf('Swap with {app}', {app: name(t)}); verb = tr('Swap'); break;
      default:
        verb = tr('Place');
        if (a && c) where = dock ? trf('In the Dock, between {a} and {b}', {a: name(a), b: name(c)}) : trf('Between {a} and {b}', {a: name(a), b: name(c)});
        else if (c) where = dock ? tr('First in the Dock') : trf('Before {app}', {app: name(c)});
        else if (a) where = dock ? tr('Last in the Dock') : trf('After {app}', {app: name(a)});
        else where = dock ? tr('In the Dock') : tr('Here');
    }
    var h = $('movehint');
    h.innerHTML = '';
    var mini = el('div', 'mini', h);
    if (mm.carried.folder) sym('folder', mini, 24);
    else face(mm.carried, mini, 11);
    el('b', 'name', h, mm.carried.title);
    el('i', 'sep', h);
    el('span', 'where', h, where);
    el('span', 'key', h, tr('OK'));
    el('b', 'verb', h, verb);
    el('span', 'key', h, tr('Back'));
    el('span', 'cancel', h, tr('Cancel'));
    fitLogos(h);
    setClass(h, 'on', true);
  }

  // MARK: - Naming a folder: a new one (OK on an outlined app) or the open one (its name, ↑ from its first row)

  /** A suggested folder name (layout.js, in English) in the person's language: literal calls, so they're packed and translated. */
  function folderName(n) {
    var names = {
      'Streaming': tr('folder|Streaming'),
      'Movies & TV': tr('folder|Movies & TV'),
      'Watch': tr('folder|Watch'),
      'Kids': tr('folder|Kids'),
      'Family': tr('folder|Family'),
      'Live TV': tr('folder|Live TV'),
      'Channels': tr('folder|Channels'),
      'Sports': tr('folder|Sports'),
      'News': tr('folder|News'),
      'Music': tr('folder|Music'),
      'Listen': tr('folder|Listen'),
      'Games': tr('folder|Games'),
      'Play': tr('folder|Play'),
      'Fitness': tr('folder|Fitness'),
      'Health': tr('folder|Health'),
      'Learning': tr('folder|Learning'),
      'Explore': tr('folder|Explore'),
      'Photos': tr('folder|Photos'),
      'Memories': tr('folder|Memories'),
      'Social': tr('folder|Social'),
      'Shopping': tr('folder|Shopping'),
      'Utilities': tr('folder|Utilities'),
      'Tools': tr('folder|Tools'),
      'TV': tr('folder|TV'),
      'Entertainment': tr('folder|Entertainment'),
      'Fun': tr('folder|Fun'),
      'Favorites': tr('folder|Favorites'),
      'My Apps': tr('folder|My Apps')
    };
    return names.hasOwnProperty(n) ? names[n] : n;   // a brand (YouTube, TCL): as it is
  }

  function startNaming(folder) {
    var kids = folder ? folder.apps.slice() : [boardItem(mm.board.target()), mm.carried], labels = [], ids = [], taken = {}, i;
    for (i = 0; i < kids.length; i++) { labels.push(kids[i].title); ids.push(kids[i].id); }
    for (i = 0; i < others.length; i++) if (others[i].folder && others[i] !== folder) taken[others[i].title.toLowerCase()] = true;
    var names = folder ? [folder.title] : [], sug = Layout.suggest(labels, ids);
    for (i = 0; i < sug.length && names.length < (folder ? 5 : 4); i++) {
      var n = folderName(sug[i]);
      if (!taken[n.toLowerCase()] && names.indexOf(n) < 0) names.push(n);
    }
    var layer = $('namecard');
    layer.innerHTML = '';
    el('div', 'veil', layer);
    var card = el('div', 'card', layer), tile = el('div', 'cell preview', card), f = el('div', 'face', tile);
    folderFace({apps: kids}, f);
    el('div', 'title', card, folder ? tr('Rename Folder') : tr('New Folder'));
    var big = el('div', 'big', card, names[0]);
    el('div', 'caption', card, kids.length === 2 ? trf('{a} and {b}', {a: labels[0], b: labels[1]}) : plural('plural|one:{n} app|other:{n} apps', kids.length));
    var pills = el('div', 'pills', card), nodes = [];
    for (i = 0; i <= names.length; i++) nodes.push(el('div', 'pill', pills, i < names.length ? names[i] : tr('Type a Name…')));
    var input = el('input', 'field', card);
    input.type = 'text';
    input.placeholder = tr('Folder Name');
    input.maxLength = 40;
    fitLogos(layer);
    naming = {folder: folder || null, names: names, index: 0, pills: nodes, big: big, input: input, card: card};
    setClass($('movehint'), 'on', false);
    focusName();
    mv.nameA.tune(P.SHEET_OPEN).set(1);
    Sound.sheet(true, 0.5);
    Motion.kick();
  }

  function focusName() {
    for (var i = 0; i < naming.pills.length; i++) setClass(naming.pills[i], 'focus', i === naming.index);
    naming.big.textContent = naming.names[Math.min(naming.index, naming.names.length - 1)];
  }

  /** Keys on the naming card: ← → the names, ↑ ↓ the row above or below, OK picks, Back goes back. */
  function nameKey(e) {
    var k = e.keyCode, typing = document.activeElement === naming.input;
    if (typing) {
      if (k === 13 || k === TV.keys.ok || k === 65376) { finishNaming(naming.input.value.replace(/^\s+|\s+$/g, '')); return true; }
      if (k === 27 || k === TV.keys.back || k === 65385) { naming.input.blur(); setClass(naming.card, 'typing', false); return true; }
      return false;   // the letters go in the field
    }
    if (isArrow(k)) {
      var n = naming.pills.length, next = naming.index;
      if (k === TV.keys.left || k === TV.keys.right) {
        next += k === TV.keys.left ? -1 : 1;
      } else {
        var from = naming.pills[naming.index], best = Infinity, up = k === TV.keys.up;
        next = -1;
        for (var i = 0; i < n; i++) {
          var dy = naming.pills[i].offsetTop - from.offsetTop;
          if (up ? dy > -1 : dy < 1) continue;
          var d = Math.abs(dy) * 4 + Math.abs(naming.pills[i].offsetLeft - from.offsetLeft);
          if (d < best) { best = d; next = i; }
        }
      }
      if (next < 0 || next >= n) { Sound.bump(0.5); return true; }
      naming.index = next;
      focusName();
      Sound.tick(0.5);
      return true;
    }
    if (k === TV.keys.ok) {
      if (e.repeat || okDown) return true;
      if (naming.index < naming.names.length) { finishNaming(naming.names[naming.index]); return true; }
      setClass(naming.card, 'typing', true);
      naming.input.value = naming.names[0];
      naming.input.focus();
      naming.input.select();
      return true;
    }
    if (k === TV.keys.back || k === 27 || k === 8) { closeNaming(); Sound.sheet(false, 0.5); return true; }
    return true;
  }

  function finishNaming(name) {
    if (!naming || !name) return;
    var folder = naming.folder;
    closeNaming();
    if (folder) renameFolder(folder, name);
    else drop(name);
  }

  function closeNaming() {
    if (!naming) return;
    if (document.activeElement === naming.input) naming.input.blur();
    naming = null;
    mv.nameA.tune(P.SHEET_CLOSE).set(0);
    if (mm && !mm.landing) updateHint();
    Motion.kick();
  }

  /** The open folder's new name, at once and kept with the profile's layout (the folder is theirs now). */
  function renameFolder(folder, name) {
    folder.title = name;
    var badge = folderCard && folderCard.querySelector('.badge');
    if (badge) badge.textContent = name;
    var l = Layout.read(Prefs.layout()), f = Layout.folderOf(l, folder.id), i;
    if (!l.grid.length) for (i = 0; i < others.length; i++) l.grid.push(others[i].id);
    if (!f) l.folders.push(f = {key: folder.id, name: '', apps: []});
    f.name = name;
    f.apps = [];
    for (i = 0; i < folder.apps.length; i++) f.apps.push(folder.apps[i].id);
    Prefs.set('layout', l);
    renderApps();
    fitLogos($('apps'));
    Sound.select();
    updateFocus();
  }

  function openApps(index) {
    if (st.zone !== Z.APPS) Sound.sheet(true, 0.5);
    renderApps();
    st.zone = Z.APPS;
    st.grid = Math.max(0, Math.min(others.length - 1, index || 0));
    setClass($('apps'), 'open', true);
    fitLogos($('apps'));
    updateFocus();
  }

  function closeSheets() {
    var z = st.zone;
    if (z === Z.APPS || z === Z.SETTINGS || z === Z.RECENTS || z === Z.EDIT || z === Z.LANG || z === Z.WALLPAPER || z === Z.ABOUT) {
      Sound.sheet(false, 0.5);
    }
    setClass($('apps'), 'open', false);
    setClass($('settings'), 'open', false);
    setClass($('shelfedit'), 'open', false);
    setClass($('langpick'), 'open', false);
    setClass($('wallpaperpick'), 'open', false);
    setClass($('about'), 'open', false);
    closeField('citypick');
    closeField('keypick');
    setClass($('citypick'), 'open', false);
    setClass($('keypick'), 'open', false);
    setClass($('recents'), 'open', false);
    langHint = null;
    st.zone = Z.SHELF;
    updateFocus();
  }

  // MARK: - Settings: the sections of MAHITTV-PROFILES-SPEC.md §6, drawn as Apple TV's Settings (settings.js)

  /*
   * Two panes: the sections on the left (settingsPane 'cats'), the focused section's rows on the right ('rows'); a
   * choice (Theme, Screensaver…) pushes a list with a check on the current one (settingsChoice). A row: {label, value,
   * toggle (on or off), choice ({title, options: [[value, label]], current, set(value)}), step() (OK: what it does),
   * opens (it opens something: a chevron), fixed (it only shows a value), dim}; a section's rows come in groups
   * {header, rows, footer}.
   */
  var settingsSection = null, sectionRow = 0, settingsPane = 'cats', settingsChoice = null, choiceRow = 0, groupsNow = [];

  function sections() {
    return [
      {id: 'profiles', label: tr('Profiles & PINs'), symbol: 'lock', rows: profileRows,
        desc: tr("Who's watching, their PINs and their Dock.")},
      {id: 'look', label: tr('Appearance & Startup'), symbol: 'screensaver', rows: lookRows,
        desc: tr('Theme, wallpaper, screensaver and the startup.')},
      {id: 'place', label: tr('Location & Media'), symbol: 'sunny', rows: placeRows,
        desc: tr('Your city, weather and the Top Shelf\'s picks.')},
      {id: 'welly', label: tr('Welly'), symbol: 'sparkles', rows: wellyRows,
        desc: tr("Welly's brain and what it remembers.")},
      {id: 'system', label: tr('System'), symbol: 'tv', rows: systemRows,
        desc: tr('Language, the Dock and this TV.')},
      {id: 'reset', label: tr('Reset & Restore'), symbol: 'sync', rows: resetRows,
        desc: tr("Start over, or bring back the TV's own home screen.")}
    ];
  }

  function section(id) {
    var list = sections();
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }

  /** The focused section's rows as the focus moves through them (flat), keeping its groups for drawing. */
  function settingRows() {
    var s = section(settingsSection) || sections()[0], flat = [];
    groupsNow = s.rows();
    for (var g = 0; g < groupsNow.length; g++) for (var r = 0; r < groupsNow[g].rows.length; r++) flat.push(groupsNow[g].rows[r]);
    return flat;
  }

  function status(on) { return on ? tr('status|Set') : tr('status|Not set'); }

  function profileRows() {
    var me = person(), groups = [{rows: [
      {label: tr('Profile'), value: me.name, opens: true, step: function () { showLock({from: 'switch', cancel: true}); }},
      {label: tr('Lock'), step: function () { lockNow(); }},
      {label: tr('Change PIN'), opens: true, dim: me.role === 'guest', step: function () { changeOwnPin(); }}
    ]}];
    // The manager sets or removes everyone else's PIN (each asks for the manager's PIN first).
    if (me.role === 'manager') {
      var pins = [];
      people().forEach(function (p) {
        if (p.id === me.id || p.role === 'guest') return;
        var set = Profiles.needsPin(p), options = [['set', tr('Set PIN')]];
        if (set) options.push(['remove', tr('Remove PIN')]);
        pins.push({label: trf("{name}'s PIN", {name: p.name}), value: status(set),
          choice: {title: trf("{name}'s PIN", {name: p.name}), options: options, current: null,
            set: function (v) { if (v === 'remove') removePin(p); else setPinFor(p); }}});
      });
      groups.push({header: tr('Family'), rows: pins, footer: tr("Changing someone else's PIN asks for the manager's PIN.")});
    }
    return groups;
  }

  function lookRows() {
    var theme = Prefs.theme(), auto = tr('Automatic'), wall = Prefs.wallpaper(), style = Prefs.saverStyle();
    var names = {
      'horizon:auto': auto, 'horizon:light': tr('Light'), 'horizon:dark': tr('Dark'),
      'aerial:yosemite': tr('Valley'), 'aerial:iceland': tr('Coast'),
      'aerial:north-america-aurora': tr('Aurora'), 'aerial:new-york': tr('Earth')
    };
    var size = Prefs.textSize();
    var sizes = [['small', tr('size|Small')], ['default', tr('size|Default')], ['large', tr('size|Large')]];
    var saver = [
      {label: tr('Screensaver'), choice: {title: tr('Screensaver'), current: style, set: function (v) { Prefs.set('saverStyle', v); },
        options: [['shuffle', tr('saver|Shuffle')], ['photos', tr('saver|Photos')], ['nature', tr('Nature Clock')], ['aurora', tr('Aurora')],
          ['aerials', tr('Apple Aerials')], ['black', tr('saver|Black')]]}}
    ];
    if (style === 'nature') {
      saver.push({label: tr('Scene'), choice: {title: tr('Scene'), current: Prefs.saverScene(), set: function (v) { Prefs.set('saverScene', v); },
        options: [['shuffle', tr('Change every minute')], ['night', tr('saver|Night')], ['golden', tr('Golden Hour')], ['water', tr('Waterline')],
          ['fog', tr('Fog')], ['dawn', tr('Rainforest Dawn')]]}});
      saver.push({label: tr('Clock Style'), choice: {title: tr('Clock Style'), current: Prefs.saverClock(), set: function (v) { Prefs.set('saverClock', v); },
        options: [['tall', tr('Tall Glass')], ['rounded', tr('Rounded')]]}});
    }
    if (style === 'photos') {
      saver.push({label: tr('Watch from the screensaver'), toggle: Prefs.saverWatch(),
        step: function () { Prefs.set('saverWatch', !Prefs.saverWatch()); }});
    }
    saver.push({label: tr('Preview'), opens: true, step: function () { showSaver(); }});
    return [
      {rows: [
        {label: tr('Theme'), choice: {title: tr('Theme'), current: theme, options: [['auto', auto], ['light', tr('Light')], ['dark', tr('Dark')]],
          set: function (v) { Prefs.set('theme', v); checkNight(); }}},
        {label: tr('Text Size'), choice: {title: tr('Text Size'), current: size, options: sizes,
          set: function (v) { Prefs.set('textSize', v); checkNight(); }}},
        {label: tr('Wallpaper'), value: names[wall] || wallpaperName(wall), valueId: 'wallpaper', opens: true,
          step: function () { openWallpaperPicker(); }},
        {label: tr('Glass'), choice: {title: tr('Glass'), current: Prefs.glass(), options: [['liquid', tr('Liquid Glass')], ['frosted', tr('Frosted Glass')]],
          set: function (v) { Prefs.set('glass', v); checkNight(); }}},
        {label: tr('Settings Style'), choice: {title: tr('Settings Style'), current: Prefs.settingsStyle(),
          options: [['full', tr('Full Screen (tvOS)')], ['sidebar', tr('Floating Sidebar')]],
          set: function (v) { Prefs.set('settingsStyle', v); }}},
        {label: tr('Startup animation & chime'), toggle: Prefs.intro(), step: function () { Prefs.set('intro', !Prefs.intro()); }},
        {label: tr('Preview Startup Animation'), opens: true, step: function () { replayIntro(); }},
        {label: tr('Navigation sounds'), toggle: Prefs.sounds(), step: function () {
          Prefs.set('sounds', !Prefs.sounds());
          if (Prefs.sounds()) Sound.toggle(true);   // off is heard as it goes (settingsOk); on, once it's on
        }}
      ]},
      {header: tr('Screensaver'), rows: saver, footer: tr('It starts after 5 minutes without a button press.')}
    ];
  }

  function wallpaperName(id) {
    if (id === 'horizon:auto') return tr('Auto Sunset');
    if (id === 'horizon:light') return tr('Light');
    if (id === 'horizon:dark') return tr('Dark');
    if (id === 'aerial:yosemite') return tr('Yosemite');
    if (id === 'aerial:iceland') return tr('Iceland');
    if (id === 'aerial:north-america-aurora') return tr('North America Aurora');
    if (id === 'aerial:palau-coral') return tr('Palau Coral');
    if (id === 'aerial:new-york') return tr('New York');
    if (id === 'aerial:sonoma-coast') return tr('Sonoma Coast');
    if (id === 'studio:black') return tr('Studio Black');
    if (id === 'studio:space-gray') return tr('Space Gray');
    if (id === 'studio:midnight-blue') return tr('Midnight Blue');
    if (id === 'studio:deep-forest') return tr('Deep Forest Green');
    if (id === 'studio:teal') return tr('Teal');
    if (id === 'studio:warm-stone') return tr('Warm Stone');
    if (id === 'studio:silver-mist') return tr('Silver Mist');
    if (id === 'live:aerial') return tr('Live Aerial Screensaver');
    var aerial = Wallpaper['find'](id);
    if (aerial) return aerial.title;
    if (id.indexOf('studio:') === 0) return id.substring(7).replace(/-/g, ' ');
    return tr('Automatic');
  }

  function wallpaperCategoryName(id) {
    return id === 'horizon' ? tr('Apple Frosted Horizon') : id === 'aerial' ? tr('Apple 4K Aerials') : id === 'studio' ? tr('Apple Studio Solids') : tr('Live Aerial Screensaver');
  }

  var wallpaperPreviewGeneration = 0;

  function renderWallpaperPicker() {
    var box = $('wallpaperpick').firstChild, categories = Wallpaper.categories(), category = categories[st.wallpaperCategory], options = Wallpaper.options(category);
    box.innerHTML = '';
    el('div', 'title', box, tr('Wallpaper'));
    var panes = el('div', 'panes', box), left = el('div', 'categories', panes), right = el('div', 'options', panes);
    for (var i = 0; i < categories.length; i++) el('div', 'row', left, wallpaperCategoryName(categories[i]));
    for (i = 0; i < options.length; i++) el('div', 'row', right, wallpaperName(options[i]));
    var preview = el('div', 'preview', panes), chosen = options[st.wallpaperOption], aerial = chosen === 'live:aerial' ? Wallpaper.liveAerial(Math.floor(Date.now() / 86400000)) : Wallpaper['find'](chosen), solid, generation = ++wallpaperPreviewGeneration;
    if (aerial) {
      var live = chosen === 'live:aerial', status;
      preview.style.backgroundImage = 'url(' + aerial.preview + ')'; status = el('div', 'status', preview, tr('Downloading…'));
      var probe = new Image();
      probe.onload = function () {
        if (generation !== wallpaperPreviewGeneration) return;
        status.textContent = live ? tr('Live Aerial preview ready') : tr('Aerial preview ready');
      };
      probe.onerror = function () { if (generation === wallpaperPreviewGeneration) { preview.style.backgroundImage = ''; preview.className += ' horizon'; status.textContent = tr('Unavailable — showing Frosted Horizon'); } };
      probe.src = aerial.preview;
    }
    else if (chosen.indexOf('studio:') === 0) { for (i = 0; i < Wallpaper.solids.length; i++) if (Wallpaper.solids[i].id === chosen) solid = Wallpaper.solids[i]; preview.style.background = solid ? solid.color : '#B8C7D6'; }
    else { preview.className += ' horizon'; }
    el('div', 'hint', box, tr('OK selects immediately. Back keeps your choice.'));
  }

  function openWallpaperPicker() {
    var categories = Wallpaper.categories(), current = Prefs.wallpaper(), category = Wallpaper.categoryOf(current), options;
    st.wallpaperCategory = Math.max(0, categories.indexOf(category));
    options = Wallpaper.options(categories[st.wallpaperCategory]);
    st.wallpaperOption = Math.max(0, options.indexOf(current)); st.wallpaperPane = 0;
    setClass($('settings'), 'open', false); st.zone = Z.WALLPAPER; renderWallpaperPicker(); setClass($('wallpaperpick'), 'open', true); updateFocus();
  }

  function closeWallpaperPicker() {
    setClass($('wallpaperpick'), 'open', false); openSettings(true);
    for (var i = 0; i < rowsNow.length; i++) if (rowsNow[i].valueId === 'wallpaper') st.row = i;
    updateFocus();
  }

  function placeRows() {
    var unit = Prefs.temperature();
    var widgets = [
      {label: tr('Top Shelf'), choice: {title: tr('Top Shelf'), current: Prefs.topShelf(), options: topShelfOptions(),
        set: function (v) { Prefs.set('topShelf', v); follow(); }}},
      {label: tr('Weather widget'), toggle: Prefs.weather(), step: function () { Prefs.set('weather', !Prefs.weather()); renderPanels(); }},
      {label: tr('Forecast'), choice: {title: tr('Forecast'), current: Prefs.forecast(), options: [['hours', tr('Next hours')], ['days', tr('Next days')]],
        set: function (v) { Prefs.set('forecast', v); renderPanels(); }}},
      {label: tr('Quotes widget'), toggle: Prefs.quotes(), step: function () { Prefs.set('quotes', !Prefs.quotes()); Data.settingsChanged(); }}
    ];
    var names = ['Bitcoin', 'Ethereum', 'Solana', tr('Dollar')];
    var count = Data.currency === 'BRL' ? 4 : 3;
    for (var i = 0; i < count; i++) {
      (function (i) {
        widgets.push({label: names[i], toggle: Prefs.quotes() && Prefs.coin(i), dim: !Prefs.quotes(),
          step: function () { if (Prefs.quotes()) { Prefs.setCoin(i, !Prefs.coin(i)); Data.settingsChanged(); } }});
      })(i);
    }
    return [
      {rows: [
        person().role === 'guest' ? {label: tr('City'), value: Prefs.city() || tr('Unavailable'), fixed: true}
          : {label: tr('City'), value: Prefs.city() || tr('Unavailable'), opens: true, city: true,
            step: function () { openCities(); }},
        dateTimeRow(),
        {label: tr('Temperature'), choice: {title: tr('Temperature'), current: unit, options: [['auto', tr('unit|Automatic')], ['C', '°C'], ['F', '°F']],
          set: function (v) { Prefs.set('temperature', v); renderPanels(); }}}
      ]},
      {header: tr('Home Screen'), rows: widgets},
      {header: tr('Picks'), rows: pickRows()}
    ];
  }

  /** The picks' key and sources: only the manager changes them (upstream's rows come without profiles). */
  function pickRows() {
    if (person().role !== 'manager') return [{label: tr('TMDB key'), value: status(!!Prefs.tmdbKey()), fixed: true}];
    var rows = [{label: tr('TMDB key'), value: Prefs.tmdbKey().length >= 4 ? '•••• ' + Prefs.tmdbKey().slice(-4) : status(false),
      opens: true, tmdb: true, step: function () { openKey(); }}];
    var sv = Data.services();
    for (var j = 0; j < sv.length; j++) {
      (function (key, name) {
        rows.push({label: name, toggle: !Prefs.serviceOff(key),
          step: function () { Prefs.setServiceOff(key, !Prefs.serviceOff(key)); Data.refresh(); Data.settingsChanged(); }});
      })(sv[j].key, sv[j].name);
    }
    rows.push({label: tr('Cartoons and anime'), toggle: !Prefs.hideAnimation(),
      step: function () { Prefs.set('hideAnimation', !Prefs.hideAnimation()); Data.refresh(); }});
    if (nuvio()) {
      rows.push({label: tr('Open picks in'), choice: {title: tr('Open picks in'), current: Prefs.openPicks(),
        options: [['apps', tr('Streaming apps')], ['nuvio', 'Nuvio']], set: function (v) { Prefs.set('openPicks', v); }}});
    }
    return rows;
  }

  function wellyRows() {
    var me = person(), host = (CONFIG.ollamaUrl || '').replace(/^https?:\/\//, '').replace(/\/.*$/, '');
    return [
      {rows: [
        {label: tr('Gemini key'), value: status(!!CONFIG.geminiKey), fixed: true},
        {label: tr('Ollama'), value: host || tr('status|Not set'), fixed: true}
      ], footer: tr('Set in config.properties: gemini_key, welly_model and ollama_url.')},
      {rows: [
        {label: trf("Clear Welly's memory for {name}", {name: me.name}), dim: me.role === 'guest',
          step: function () {
            if (me.role === 'guest') return;
            Profiles.clearLog();
            if (window.Welly && Welly.clearMemory) Welly.clearMemory();
            toast(trf("Cleared Welly's memory for {name}.", {name: me.name}));
          }}
      ]}
    ];
  }

  function systemRows() {
    var picked = pickedLanguage();
    var rows = [
      {label: tr('Language'), value: picked === 'auto' ? tr('Automatic') : I18N.languageName(picked), opens: true, language: true,
        step: function () { openLanguages(); }},
      {label: tr('Dock'), opens: true, shelf: true, step: function () { openShelfEdit(); }},
      {label: tr('About this TV'), opens: true, about: true, step: function () { openAbout(); }}
    ];
    if (TV.canOpenSettings) rows.push({label: tr('TV Settings'), opens: true, step: function () { tvSettings(); }});
    var groups = [{rows: rows}, {rows: [{label: tr('Run Setup Again'), opens: true, step: function () { runSetup(); }}],
      footer: tr('The welcome setup: the look, the glass, the Top Shelf and the weather.')}];
    var tv = tvMenuRows();
    if (tv.length) groups.unshift({header: tr('Google TV Settings'), rows: tv});
    return groups;
  }

  /*
   * The TV's own Settings, as a TCL Google TV lists them (as SettingsActivity's Google TV Settings): on LG and Samsung
   * each opens the TV's settings; the preview shows what the Google TV app shows (preview_wifi, preview_bluetooth).
   */
  function tvMenuRows() {
    if (!TV.canOpenSettings && !PREVIEW) return [];
    var open = TV.canOpenSettings ? tvSettings : googleOnly, bt = CONFIG.previewBluetooth || [];
    function row(label, value) { return {label: label, value: value || '', opens: true, step: function () { open(); }}; }
    return [row(tr('Channels & Inputs')), row(tr('Display & Sound')), row(tr('Network & Internet'), PREVIEW ? CONFIG.previewWifi : ''),
      row(tr('Accounts & Profiles')), row(tr('Privacy')), row(tr('Apps')), row(tr('tv|System')), row(tr('Accessibility')),
      row(tr('Remotes & Accessories'), PREVIEW ? bt[0] : '')];
  }

  /** The time zone, and where to change it: only the TV can (TimeCheck). */
  function zoneWrong() {
    return Data.zoneLooksWrong(-new Date().getTimezoneOffset(), Prefs.longitude());
  }

  function dateTimeRow() {
    var name = (TV.zone() || '').replace(/_/g, ' '), value = zoneWrong() ? tr('Check the time zone') : name;
    if (!TV.canOpenSettings && !PREVIEW) return {label: tr('Date & Time'), value: value, fixed: true};
    return {label: tr('Date & Time'), value: value, opens: true, step: function () { (TV.canOpenSettings ? tvSettings : googleOnly)(); }};
  }

  /** The Top Shelf's modes (Settings and the welcome setup). */
  function topShelfOptions() {
    return [['app', tr('App-Specific Continue Watching')], ['picks', tr('Recommendations & News')],
      ['all', tr('All Continue Watching')], ['off', tr('Off (Wallpaper Only)')]];
  }

  function resetRows() {
    return [
      {rows: [{label: tr('Reset mahitTV Data'), step: function () { resetData(); }}],
        footer: tr("Clears every profile's Dock, theme, PIN changes, Welly memory and history. Language, city and remote buttons stay. Asks for the manager's PIN.")},
      {rows: [{label: tr('Restore Original TV'), value: tr('Google TV only'), fixed: true, dim: true}],
        footer: tr('Restore Original TV is on Google TV only.')}
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

  var rowsNow = [];

  /** What settings.js draws: the sections, the emblem, and on the right the section's rows or a choice list. */
  function settingsModel(fresh) {
    var list = sections(), s = section(settingsSection) || list[0], title, groups, row;
    if (settingsChoice) {
      title = settingsChoice.title;
      groups = [{rows: settingsChoice.options.map(function (o) { return {label: o[1], check: o[0] === settingsChoice.current}; })}];
      row = choiceRow;
    } else {
      title = s.label;
      groups = groupsNow.map(function (g) {
        return {header: g.header, footer: g.footer, rows: g.rows.map(function (r) {
          var value = r.value;
          if (value === undefined && r.choice) {
            for (var i = 0; i < r.choice.options.length; i++) if (r.choice.options[i][0] === r.choice.current) value = r.choice.options[i][1];
          }
          return {label: r.label, value: value, toggle: r.toggle, chevron: !!(r.opens || r.choice), dim: r.dim};
        })};
      });
      row = st.row;
    }
    return {
      cats: list.map(function (x) { return {label: x.label, symbol: x.symbol}; }), cat: sectionRow,
      pane: settingsChoice ? 'choice' : settingsPane, title: title, groups: groups, row: row, fresh: fresh,
      emblem: {symbol: s.symbol, avatar: s.id === 'profiles' ? Profiles.avatarUrl(person()) : null, title: s.label, desc: s.desc},
      hover: settingsHover, click: settingsClick
    };
  }

  /** Draws Settings again (a value changed, another section, a choice). fresh: the list starts at its top. */
  function renderSettings(fresh) {
    if (!section(settingsSection)) { sectionRow = 0; settingsSection = sections()[0].id; }
    rowsNow = settingRows();
    st.row = Math.max(0, Math.min(rowsNow.length - 1, st.row));
    var m = settingsModel(fresh);
    m.sidebar = Prefs.settingsStyle() === 'sidebar';
    Settings.render($('settings'), m);
  }

  /** The focus moved (updateFocus): only the highlight and the scroll. */
  function focusSettings() {
    if ($('settings').querySelector('.left')) { var m = settingsModel(); m.sidebar = Prefs.settingsStyle() === 'sidebar'; Settings.focus($('settings'), m); }
  }

  function openChoice(c) {
    settingsChoice = c;
    choiceRow = 0;
    for (var i = 0; i < c.options.length; i++) if (c.options[i][0] === c.current) choiceRow = i;
    renderSettings(true);
    updateFocus();
  }

  function closeChoice() {
    settingsChoice = null;
    renderSettings();
    updateFocus();
  }

  /** The arrows in Settings: ↑ ↓ within a pane, → into the rows, ← back to the sections (or out of a choice). */
  function settingsMove(dx, dy) {
    var n, bumped = false;
    if (settingsChoice) {
      if (dy) { n = Math.max(0, Math.min(settingsChoice.options.length - 1, choiceRow + dy)); bumped = n === choiceRow; choiceRow = n; }
      else if (dx < 0) { closeChoice(); return; }
      else bumped = true;
    } else if (settingsPane === 'cats') {
      if (dy) {
        n = Math.max(0, Math.min(sections().length - 1, sectionRow + dy));
        if (n === sectionRow) bumped = true;
        else { sectionRow = n; settingsSection = sections()[n].id; st.row = 0; renderSettings(true); }
      } else if (dx > 0 && rowsNow.length) { settingsPane = 'rows'; st.row = 0; }
      else bumped = true;
    } else if (dy) {
      n = Math.max(0, Math.min(rowsNow.length - 1, st.row + dy));
      bumped = n === st.row;
      st.row = n;
    } else if (dx < 0) settingsPane = 'cats';
    else bumped = true;
    if (bumped) Settings.bump($('settings'), dx, dy);
  }

  /** OK in Settings: into the rows; a switch flips; a choice opens its list (or picks); a row does what it does. */
  function settingsOk() {
    if (settingsChoice) {
      var c = settingsChoice, o = c.options[choiceRow];
      settingsChoice = null;
      if (o) c.set(o[0]);
      if (st.zone === Z.SETTINGS) { renderSettings(); updateFocus(); }
      return;
    }
    if (settingsPane === 'cats') {
      if (rowsNow.length) { settingsPane = 'rows'; st.row = 0; updateFocus(); }
      return;
    }
    var r = rowsNow[st.row];
    if (!r || r.fixed) { Sound.bump(0.5); Settings.bump($('settings'), 0, 0); return; }
    if (r.choice) { Sound.select(); openChoice(r.choice); return; }
    if (r.toggle !== undefined) Sound.toggle(!r.toggle);   // rising on, falling off
    else Sound.select();
    if (r.step) r.step();
    if (st.zone === Z.SETTINGS) { renderSettings(); updateFocus(); }
  }

  /** Back in Settings: out of a choice, then out of the rows; on the sections, Settings closes (false). */
  function settingsBack() {
    if (settingsChoice) { closeChoice(); return true; }
    if (settingsPane !== 'cats') { settingsPane = 'cats'; updateFocus(); return true; }
    return false;
  }

  /** The pointer (the Magic Remote, or a mouse when previewing). */
  function settingsHover(kind, index) {
    if (st.zone !== Z.SETTINGS) return;
    if (kind === 'cat') {
      if (settingsChoice) return;
      if (index !== sectionRow) { sectionRow = index; settingsSection = sections()[index].id; st.row = 0; settingsPane = 'cats'; renderSettings(true); }
      else settingsPane = 'cats';
    } else if (settingsChoice) choiceRow = index;
    else { settingsPane = 'rows'; st.row = index; }
    updateFocus();
  }

  function settingsClick(kind, index) {
    settingsHover(kind, index);
    settingsOk();
  }

  /** From Control Center: the sections. keep: back from Language, About, the Dock or the wallpapers, in the rows. */
  function openSettings(keep) {
    if (st.zone !== Z.SETTINGS) Sound.sheet(true, 0.5);
    applyTextSize();
    st.zone = Z.SETTINGS;
    settingsChoice = null;
    if (!keep) { sectionRow = 0; settingsSection = sections()[0].id; settingsPane = 'cats'; st.row = 0; }
    else settingsPane = 'rows';
    renderSettings(!keep);
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

  // MARK: - Text fields: typed with the TV's keyboard (the city, the TMDB key)

  /** A row with a text field; on the TV, the focus in it brings up the keyboard. */
  function textField(holder, zone, value, placeholder, label) {
    var field = el('input', '', el('div', 'row field', holder));
    field.type = 'text';
    field.value = value;
    field.placeholder = placeholder;
    field.setAttribute('aria-label', label);
    field.setAttribute('autocomplete', 'off');
    field.setAttribute('spellcheck', 'false');
    hover(field.parentNode, zone, 0);
    return field;
  }

  function fieldOf(sheet) { return $(sheet).querySelector('input'); }

  function typingIn(sheet) { var f = fieldOf(sheet); return !!f && document.activeElement === f; }

  /** Into the field: the TV shows its keyboard. */
  function openField(sheet) {
    var f = fieldOf(sheet);
    if (!f) return;
    $(sheet).style.visibility = 'visible';   // its fade starts a frame later, and a hidden field can't take the focus
    f.focus();
    try { f.setSelectionRange(f.value.length, f.value.length); } catch (e) {}
  }

  function closeField(sheet) {
    if (typingIn(sheet)) fieldOf(sheet).blur();
  }

  // MARK: - City: typed with the TV's keyboard, found by Open-Meteo, as on Android (the weather is for its coordinates)

  var cityFound = [], cityStatus = '', cityTimer = 0, citySeq = 0;

  /** The field to type in, the Search row, then the places found. */
  function cityRows() { return 2 + cityFound.length; }

  function renderCities() {
    var box = $('citypick').firstChild, old = fieldOf('citypick'), typed = old ? old.value : '', inField = typingIn('citypick');
    box.innerHTML = '';
    el('div', 'title', box, tr('City'));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    list.style.height = Math.min(cityRows(), 7) * 78 + 'px';
    textField(holder, Z.CITY, typed, tr("Type the city's name"), tr('City'));
    var search = el('div', 'row', holder, tr('Search'));
    sym('chevron_right', el('span', 'value', search));
    hover(search, Z.CITY, 1);
    for (var i = 0; i < cityFound.length; i++) {
      var row = el('div', 'row', holder, cityFound[i].label);
      hover(row, Z.CITY, 2 + i);
    }
    el('div', 'hint', box, cityStatus || tr('OK picks, Back cancels.'));
    if (inField) openField('citypick');   // a message that went away while typing: the keyboard stays
    updateFocus();
  }

  function openCities() {
    setClass($('settings'), 'open', false);
    st.zone = Z.CITY;
    st.city = 0;
    cityFound = [];
    cityStatus = '';
    $('citypick').firstChild.innerHTML = '';
    renderCities();
    setClass($('citypick'), 'open', true);
    openField('citypick');
  }

  function setCityStatus(text) {
    cityStatus = text;
    clearTimeout(cityTimer);
    // Clears only the hint: a new render would rebuild the field and interrupt the TV's keyboard while someone types.
    if (text && text !== tr('Searching…')) cityTimer = setTimeout(function () {
      cityStatus = '';
      var hint = st.zone === Z.CITY && $('citypick').querySelector('.hint');
      if (hint) hint.textContent = tr('OK picks, Back cancels.');
    }, 4000);
    if (st.zone === Z.CITY) renderCities();
  }

  function searchCity() {
    var f = fieldOf('citypick'), name = f ? f.value : '';
    closeField('citypick');
    if (!name.trim()) { st.city = 0; openField('citypick'); updateFocus(); return; }
    if (cityStatus === tr('Searching…')) return;
    setCityStatus(tr('Searching…'));
    var mine = ++citySeq;   // an answer for a sheet already closed (and opened again) is dropped
    Data.findCity(name, function (err, found) {
      if (mine !== citySeq) return;
      if (st.zone !== Z.CITY) { cityStatus = ''; return; }
      cityFound = found || [];
      if (cityFound.length) st.city = 2;   // on the first place found
      setCityStatus(err ? trf("Couldn't search now: {error}", {error: err.message}) : cityFound.length ? '' : tr('No city with that name.'));
    });
  }

  /** OK on a place: the weather moves there. Back (pick false): nothing changes. Either way, back on the City row. */
  function closeCities(pick) {
    var place = pick && cityFound[st.city - 2];
    closeField('citypick');
    setClass($('citypick'), 'open', false);
    cityFound = [];
    citySeq++;
    clearTimeout(cityTimer);
    cityStatus = '';
    if (place) {
      Prefs.setCity(place.city, place.lat, place.lon);
      Data.cityChanged();
    }
    openSettings(true);
    for (var i = 0; i < rowsNow.length; i++) if (rowsNow[i].city) st.row = i;
    updateFocus();
  }

  function activateCity() {
    if (st.city === 0) openField('citypick');
    else if (st.city === 1) searchCity();
    else closeCities(true);
  }

  // MARK: - TMDB key: typed on the TV, so builds without one (the releases) get the picks too; TMDB checks it first

  var keyStatus = '', keyWarn = false, keyTimer = 0, keySeq = 0, keyAsking = false;

  function keyRows() { return keyAsking ? 2 : Prefs.tmdbKeyOnTV() ? 3 : 2; }

  function keySteps() {
    return tr("The key brings the picks: what's trending on your streaming apps, with their photos on the screensaver.") + '\n'
      + tr('Free, and it takes two minutes:') + '\n1. ' + tr('Create an account at themoviedb.org.')
      + '\n2. ' + tr('Open Settings → API and request a developer key.');
  }

  function renderKey() {
    var box = $('keypick').firstChild, old = fieldOf('keypick'), typed = old ? old.value : '', inField = typingIn('keypick');
    box.innerHTML = '';
    el('div', 'title', box, keyAsking ? tr('Remove the TMDB key?') : tr('TMDB key'));
    var list = el('div', 'list', box), holder = el('div', 'rows', list);
    list.style.height = keyRows() * 78 + 'px';
    if (keyAsking) {
      hover(el('div', 'row', holder, tr('Cancel')), Z.KEY, 0);
      hover(el('div', 'row', holder, tr('Remove')), Z.KEY, 1);
      updateFocus();
      return;
    }
    var keyField = textField(holder, Z.KEY, typed, tr('API Read Access Token or API Key'), tr('TMDB key'));
    // A v3 key is lowercase hex: the TV's keyboard mustn't capitalize it (the city's field wants capitals).
    keyField.setAttribute('autocapitalize', 'none');
    keyField.setAttribute('autocorrect', 'off');
    var save = el('div', 'row', holder, tr('Save'));
    sym('chevron_right', el('span', 'value', save));
    hover(save, Z.KEY, 1);
    if (Prefs.tmdbKeyOnTV()) hover(el('div', 'row', holder, tr('Remove the key')), Z.KEY, 2);
    if (keyStatus) setClass(el('div', 'hint', box, keyStatus), 'warn', keyWarn);
    el('div', 'hint steps', box, keySteps());
    if (inField) openField('keypick');
    updateFocus();
  }

  function openKey() {
    setClass($('settings'), 'open', false);
    st.zone = Z.KEY;
    st.key = 0;
    keyStatus = '';
    keyAsking = false;
    $('keypick').firstChild.innerHTML = '';
    renderKey();
    setClass($('keypick'), 'open', true);
    openField('keypick');
  }

  function setKeyStatus(text, warn) {
    keyStatus = text;
    keyWarn = !!warn;
    clearTimeout(keyTimer);
    if (text && text !== tr('Checking the key with TMDB…')) keyTimer = setTimeout(function () {
      keyStatus = '';
      var line = st.zone === Z.KEY && $('keypick').querySelector('.hint:not(.steps)');
      if (line) line.parentNode.removeChild(line);
    }, 6000);
    if (st.zone === Z.KEY) renderKey();
  }

  function cleanKey(typed) {
    var key = typed.replace(/\s+/g, '');
    return /^[A-Za-z0-9._-]{16,1024}$/.test(key) ? key : '';
  }

  function saveKey() {
    var f = fieldOf('keypick'), typed = f ? f.value : '', key = cleanKey(typed);
    closeField('keypick');
    if (!typed.replace(/\s+/g, '')) { st.key = 0; openField('keypick'); updateFocus(); return; }
    if (keyStatus === tr('Checking the key with TMDB…')) return;
    if (!key) { setKeyStatus(tr("That doesn't look like a TMDB key."), true); return; }
    setKeyStatus(tr('Checking the key with TMDB…'));
    var mine = ++keySeq;   // an answer for a sheet already closed is dropped
    Data.checkKey(key, function (err) {
      if (mine !== keySeq || st.zone !== Z.KEY) return;
      if (err) { setKeyStatus(err.rejected ? tr('TMDB refused this key.') : trf("Couldn't reach TMDB: {error}", {error: err.message}), true); return; }
      Prefs.set('tmdbKey', key);
      if (fieldOf('keypick')) fieldOf('keypick').value = '';
      Data.keyChanged();
      setKeyStatus(tr('Saved. The picks arrive in a moment.'));
    });
  }

  /** Back to config.properties' key (none in the releases): the picks go. */
  function removeKey() {
    Prefs.set('tmdbKey', undefined);   // left out of the saved settings
    closeKey();
    Data.keyChanged();
  }

  /** Back on the TMDB key row of Home Settings. */
  function closeKey() {
    closeField('keypick');
    setClass($('keypick'), 'open', false);
    keySeq++;
    clearTimeout(keyTimer);
    keyStatus = '';
    keyAsking = false;
    var f = fieldOf('keypick');
    if (f) f.value = '';
    openSettings(true);
    for (var i = 0; i < rowsNow.length; i++) if (rowsNow[i].tmdb) st.row = i;
    updateFocus();
  }

  function askRemoveKey(on) {
    keyAsking = on;
    keyStatus = '';
    clearTimeout(keyTimer);
    st.key = on ? 0 : 2;
    renderKey();
  }

  function activateKey() {
    if (keyAsking) { if (st.key === 1) removeKey(); else askRemoveKey(false); }
    else if (st.key === 0) openField('keypick');
    else if (st.key === 1) saveKey();
    else askRemoveKey(true);
  }

  /** Nuvio, when the TV has it: picks can open on its page there (Home Settings, Open picks in). LG only for now:
   *  Samsung's launch passes no data, so Nuvio would open on its Home. */
  function nuvio() {
    if (TV.name !== 'webos') return null;
    for (var i = 0; i < apps.length; i++) if (/nuvio/i.test(apps[i].id) || /^nuvio\b/i.test(apps[i].title)) return apps[i];
    return null;
  }

  function pickTarget(p) {
    if (!p) return null;
    var via = Prefs.openPicks() === 'nuvio' ? nuvio() : null;
    var app = via || Data.appFor(p.app);
    if (!app) return null;
    // Nuvio's own link for the title, as on Android (nuvio://tmdb/movie/603); webOS apps get links as contentTarget.
    return {app: app, params: via ? {contentTarget: 'nuvio://tmdb/' + (p.kind === 'tv' ? 'series' : 'movie') + '/' + p.id} : null};
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
  function launcher(id, name, pick, quiet, params) {
    return function () {
      if (!quiet) {
        Profiles.touch();
        var service = shelfKey({id: id, title: name || id});
        Profiles.launched(pick ? pick.title : '', pick && pick.kind ? pick.id : 0, pick ? pick.kind || '' : '',
          name || id, service ? service[0] : '', id);
      }
      TV.launch(id, params || null, function (ok) {
        if (!ok) toast(trf("Couldn't open {app}.", {app: name || id}));
      });
    };
  }

  /**
   * The app opens at once; meanwhile its tile keeps growing and fades toward full screen and the page recedes to
   * 0.95. Back on the home screen (or if nothing took over), it all returns.
   */
  var launchTimer = 0, LAUNCH_GIVE_UP = 12000, LAUNCH_SPIN = 900;
  // The launch screen, as the Android app (HomeView.startLaunch): the tile grows into a full-screen card in its own
  // colours with its logo in the middle (zoom: 0 the tile, 1 the screen), and holds until the app takes over the TV;
  // back on the home screen it shrinks into the tile again. card: {bg, mark, spin, from: {x, y, w, h}, back, at}.
  var card = null;
  mv.zoom = spring([0.42, 0.9]);
  mv.cardA = spring([0.2, 1]);
  mv.spin = spring([0.3, 1]);

  function launchMotion(fx) {
    if (Motion.instant) return;
    launching = fx;
    mv.launch.set(1);   // the page recedes behind it
    showCard(fx);
    clearTimeout(launchTimer);
    // It holds however long the app takes; only if nothing ever takes over does it go back. The preview has no app:
    // it plays the way back as if one had opened and closed.
    launchTimer = setTimeout(function () { if (!document.hidden) endLaunch(false); }, PREVIEW ? 1400 : LAUNCH_GIVE_UP);
  }

  /** The card, made from the tile's own face: its background, its logo (or its name), at the tile's place. */
  function showCard(fx) {
    var node = fx && fx.node, layer = $('launchcard');
    if (!node || !layer) return;
    var sr = stage.getBoundingClientRect(), r = node.getBoundingClientRect(), k = sr.width / 1920;
    var from = {x: (r.left - sr.left) / k, y: (r.top - sr.top) / k, w: r.width / k, h: r.height / k};
    layer.innerHTML = '';
    var bg = el('div', 'bg', layer), cs = window.getComputedStyle(node);
    bg.style.backgroundImage = cs.backgroundImage;
    bg.style.backgroundColor = cs.backgroundColor === 'rgba(0, 0, 0, 0)' ? '#1C1C1E' : cs.backgroundColor;
    var markSrc = node.querySelector('img'), word = node.querySelector('.word');
    var mark = el('div', 'mark', layer);
    mark.style.width = from.w + 'px';
    mark.style.height = from.h + 'px';
    if (markSrc) mark.appendChild(markSrc.cloneNode(true));
    else if (word) mark.appendChild(word.cloneNode(true));
    var spin = el('div', 'spin', layer);
    for (var i = 0; i < 8; i++) {
      var spoke = el('i', '', spin);
      spoke.style.webkitTransform = spoke.style.transform = 'rotate(' + (i * 45) + 'deg)';
      spoke.style.opacity = String(1 - ((8 - i) % 8) * 0.11);
    }
    card = {bg: bg, mark: mark, spin: spin, from: from, back: false, at: Date.now()};
    mv.zoom.tune([0.42, 0.9]).snap(0);
    mv.zoom.set(1);
    mv.cardA.snap(1);
    mv.spin.snap(0);
    setClass(layer, 'on', true);
    Motion.kick();
  }

  /** Back from the app (or Back while it opens): the card shrinks into its tile and fades into it. */
  function cardBack(full) {
    if (!card || card.back) return;
    card.back = true;
    mv.zoom.tune([0.38, 0.92]);
    if (full) mv.zoom.snap(1);
    mv.zoom.set(0);
    mv.spin.set(0);
    Motion.kick();
  }

  function endLaunch(snap) {
    clearTimeout(launchTimer);
    if (launching) { if (snap) launching.launch.snap(0); else launching.launch.set(0); launching.p.snap(0); }
    launching = null;
    if (snap) mv.launch.snap(0); else mv.launch.set(0);
    if (card && snap) { card = null; setClass($('launchcard'), 'on', false); }
    else cardBack(false);
    Motion.kick();
  }

  /** The card's frame (from drawPage): the rect from the tile to the screen, corners squaring off; the logo growing. */
  function drawCard() {
    if (!card) return;
    var z = mv.zoom.x, f = card.from, x = f.x * (1 - z), y = f.y * (1 - z);
    var w = f.w + (1920 - f.w) * z, h = f.h + (1080 - f.h) * z, sx = Math.max(0.001, w / 1920), sy = Math.max(0.001, h / 1080);
    var radius = 30 * Math.max(0, 1 - z);
    T(card.bg, 'translate(' + r2(x) + 'px,' + r2(y) + 'px) scale(' + r4(sx) + ',' + r4(sy) + ')');
    var br = r2(radius / sx) + 'px / ' + r2(radius / sy) + 'px';
    if (card.bg._br !== br) { card.bg._br = br; card.bg.style.borderRadius = br; }
    var g = 1 + 0.6 * Motion.clamp(z, 0, 1);
    T(card.mark, 'translate(' + r2(x + w / 2 - f.w / 2) + 'px,' + r2(y + h / 2 - f.h / 2) + 'px) scale(' + r4(g) + ')');
    var a = Motion.clamp(mv.cardA.x, 0, 1);
    A($('launchcard'), a);
    if (!card.back && Date.now() - card.at > LAUNCH_SPIN) mv.spin.set(1);
    A(card.spin, Motion.clamp(mv.spin.x, 0, 1) * Motion.clamp(z, 0, 1));
    if (card.back && z < 0.18) mv.cardA.set(0);
    if (card.back && mv.cardA.to === 0 && mv.cardA.x < 0.01) {
      card = null;
      setClass($('launchcard'), 'on', false);
    }
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
    // Select, on the frame the press lets go and the opening motion starts (Settings plays its own, by row).
    if (st.zone !== Z.SETTINGS && st.zone !== Z.WELLY) Sound.select();
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
        openDrawer(st.top === 3 ? 'controls' : 'profile');   // the bubble: Control Center; the pill: the profile panel
        return;
      case Z.HERO:
        var p = cards[st.page], target = pickTarget(p);
        if (target) { launchMotion(fxWatch); launcher(target.app.id, target.app.title, p, false, target.params)(); }
        return;
      case Z.SHELF:
        if (shelf[st.shelf]) { launchMotion(fxTiles[st.shelf]); launcher(shelf[st.shelf].id, shelf[st.shelf].title)(); }
        return;
      case Z.DRAWER:
        // A profile (the lock on it, its PIN first), a tile, the weather, a round button; in Inputs, an input.
        if (ccSub) { if (ccInputItems[st.ccInput]) ccInputItems[st.ccInput].run(); return; }
        if (ccItems[st.drawer]) ccItems[st.drawer].run();
        return;
      case Z.RECENTS:
        var recent = recentList[st.recent];
        if (recent) {
          launchMotion(fxRecents[st.recent]);
          launcher(recent.id, recent.title)();
          setTimeout(closeSheets, 260);   // after the press, so the card is seen going in
        }
        return;
      case Z.APPS:
        if (others[st.grid] && others[st.grid].folder) { openFolder(others[st.grid], fxCells[st.grid]); return; }
        if (others[st.grid] && others[st.grid].guide) { openGuide(others[st.grid].guide); return; }
        if (others[st.grid]) { launchMotion(fxCells[st.grid]); launcher(others[st.grid].id, others[st.grid].title)(); }
        return;
      case Z.FOLDER:
        if (st.folder < 0 && folderOpen) { startNaming(folderOpen); return; }   // the folder's name: rename it
        var fa = folderOpen && folderOpen.apps[st.folder];
        if (fa && fa.guide) { openGuide(fa.guide); return; }
        if (fa) { launchMotion(fxFolder[st.folder]); closeFolder(true); launcher(fa.id, fa.title)(); }
        return;
      case Z.SETTINGS:
        settingsOk();
        return;
      case Z.EDIT:
        editShelf(0);
        return;
      case Z.LANG:
        closeLanguages(true);
        return;
      case Z.CITY:
        activateCity();
        return;
      case Z.KEY:
        activateKey();
        return;
      case Z.WALLPAPER:
        if (!st.wallpaperPane) { st.wallpaperPane = 1; updateFocus(); return; }
        var wallpaperOptions = Wallpaper.options(Wallpaper.categories()[st.wallpaperCategory]);
        Prefs.set('wallpaper', wallpaperOptions[st.wallpaperOption]);
        refreshBackground();
        renderWallpaperPicker();
        updateFocus();
        return;
      case Z.ABOUT:
        closeAbout();
        return;
    }
  }

  function snapshot() {
    return [st.zone, st.tab, st.top, st.page, st.shelf, st.drawer, st.grid, st.row, st.edit, st.lang, st.recent].join(',');
  }

  function move(dx, dy) {
    lastMove = {dx: dx, dy: dy, at: Date.now()};
    var before = snapshot(), zone = st.zone;
    moveFocus(dx, dy);
    // Nothing that way: the focused thing tugs toward the press and comes back (the house and Welly do their own),
    // with the knock; otherwise the tick, where the focus landed, as its spring starts.
    var changesValue = (zone === Z.SETTINGS && dx) || (zone === Z.EDIT && dx);
    if (snapshot() !== before || changesValue) Sound.tick(focusPan());
    else if (zone !== Z.HOUSE && zone !== Z.WELLY) { Sound.bump(0.5 + 0.2 * dx); bump(dx, dy); }
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
        if (dx > 0 && st.tab === TAB.WELLY) { st.zone = Z.TOP; st.top = 3; topFromTab = null; }
        else if (dx) st.tab = Math.max(0, Math.min(TAB.WELLY, st.tab + dx));
        else if (dy > 0) enterTab();
        // ↑ from the tabs: Control Center's bubble, without walking across (and switching) the other tabs.
        else if (dy < 0) { st.zone = Z.TOP; st.top = 3; topFromTab = st.tab; }
        break;
      case Z.HOUSE:
        if (!House.move(dx, dy)) st.zone = Z.TABS;
        break;
      case Z.WELLY:
        if (!Welly.move(dx, dy)) st.zone = Z.TABS;
        break;
      case Z.TOP:
        if (dx > 0 && st.top === 3) st.top = 4;
        else if (dx < 0 && st.top === 4) st.top = 3;
        // ← from the bubble: the tab it was reached from with ↑, else the nearest one (Welly).
        else if (dx < 0) { st.zone = Z.TABS; st.tab = topFromTab !== null ? topFromTab : TAB.WELLY; topFromTab = null; }
        else if (dy > 0) { topFromTab = null; enterTab(); }
        break;
      case Z.DRAWER:
        // Inputs: ↑ ↓ through the inputs, ← back to the tiles.
        if (ccSub) {
          if (dy) st.ccInput = Math.max(0, Math.min(ccInputItems.length - 1, st.ccInput + dy));
          else if (dx < 0) closeInputsSheet(true);
          break;
        }
        var it = ccItems[st.drawer];
        if (!it) break;
        if (it.kind === 'hslider' && dx) { it.step(dx); return; }   // ← → change the volume, as the remote's keys
        if (dx) {
          var nx = ccNeighbour(it, dx);
          if (nx >= 0) st.drawer = nx;
          else if (dx < 0) { closeDrawer(); return; }   // ← past the left edge closes it, as on tvOS
        } else if (dy) {
          var ny = ccVertical(it, dy);
          if (ny >= 0) st.drawer = ny;
        }
        break;
      case Z.RECENTS:
        if (dx) st.recent = Math.max(0, Math.min(recentList.length - 1, st.recent + dx));
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
        // ↓ onto a shorter last row: its last app (as Android's moveGrid), not a dead end.
        if (dy > 0 && g >= others.length && Math.floor(st.grid / COLS) < Math.floor((others.length - 1) / COLS)) g = others.length - 1;
        if (g >= 0 && g < others.length && (dx === 0 || Math.floor(g / COLS) === Math.floor(st.grid / COLS))) st.grid = g;
        break;
      case Z.SETTINGS:
        settingsMove(dx, dy);
        break;
      case Z.FOLDER:
        if (st.folder < 0) { if (dy > 0) st.folder = 0; setClass(folderCard.querySelector('.badge'), 'focus', st.folder < 0); break; }
        if (dy < 0 && st.folder < FOLDER_COLS) { st.folder = -1; setClass(folderCard.querySelector('.badge'), 'focus', true); break; }
        var fg = st.folder + dx + dy * FOLDER_COLS, fn = folderOpen ? folderOpen.apps.length : 0;
        // ↓ from a row whose next row is shorter: the last app.
        if (dy > 0 && fg >= fn && Math.floor(st.folder / FOLDER_COLS) < Math.floor((fn - 1) / FOLDER_COLS)) fg = fn - 1;
        if (fg >= 0 && fg < fn && (dx === 0 || Math.floor(fg / FOLDER_COLS) === Math.floor(st.folder / FOLDER_COLS))) st.folder = fg;
        folderScroll();
        break;
      case Z.EDIT:
        if (dy) st.edit = Math.max(0, Math.min(apps.length - 1, st.edit + dy));
        else if (dx) editShelf(dx);
        break;
      case Z.LANG:
        if (dy) st.lang = Math.max(0, Math.min(languageChoices().length - 1, st.lang + dy));
        break;
      case Z.CITY:
        if (dy) st.city = Math.max(0, Math.min(cityRows() - 1, st.city + dy));
        break;
      case Z.KEY:
        if (dy) st.key = Math.max(0, Math.min(keyRows() - 1, st.key + dy));
        break;
      case Z.WALLPAPER:
        if (dx) st.wallpaperPane = dx > 0 ? 1 : 0;
        else if (dy && st.wallpaperPane) {
          var options = Wallpaper.options(Wallpaper.categories()[st.wallpaperCategory]);
          st.wallpaperOption = Math.max(0, Math.min(options.length - 1, st.wallpaperOption + dy));
        } else if (dy) {
          st.wallpaperCategory = Math.max(0, Math.min(3, st.wallpaperCategory + dy)); st.wallpaperOption = 0;
        }
        renderWallpaperPicker();
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
    if (st.zone === Z.FOLDER) { closeFolder(); return; }
    if (st.zone === Z.LANG) { closeLanguages(false); return; }
    if (st.zone === Z.CITY) { closeCities(false); return; }
    if (st.zone === Z.KEY) { if (keyAsking) askRemoveKey(false); else closeKey(); return; }
    if (st.zone === Z.WALLPAPER) { closeWallpaperPicker(); return; }
    if (st.zone === Z.SETTINGS && settingsBack()) return;
    if (st.zone === Z.ABOUT) { closeAbout(); return; }
    if (sheetOpen()) { closeSheets(); return; }
    if (st.zone === Z.DRAWER && ccSub) { closeInputsSheet(); return; }
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
      if (keyAt > mouseAt || mm || naming) return;
      if (saverOn || (sheetOpen() !== (zone === Z.APPS || zone === Z.SETTINGS || zone === Z.EDIT || zone === Z.LANG || zone === Z.CITY || zone === Z.KEY || zone === Z.ABOUT ||
          zone === Z.RECENTS))) return;
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
      else if (zone === Z.CITY) st.city = index;
      else if (zone === Z.KEY) st.key = index;
      else if (zone === Z.RECENTS) st.recent = index;
      updateFocus();
    };
    node.onclick = function () { mouseAt = Date.now() + 1; node.onmouseover(); activate(); };
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

  /** Text Size (tvOS 27): the text styles in home.css and settings.css at 90 % or 115 %. */
  function applyTextSize() {
    setClass(body, 'text-small', Prefs.textSize() === 'small');
    setClass(body, 'text-large', Prefs.textSize() === 'large');
  }

  function checkNight() {
    applyTextSize();
    var night = Wallpaper.dark(Prefs.wallpaper(), Data.isNight(Date.now()));
    setClass(body, 'night', night);
    setClass(body, 'frosted', Prefs.glass() === 'frosted');
    refreshBackground();
    Motion.kick();   // the page's shades are darker at night
    if (night === lastNight) return;
    lastNight = night;
    document.documentElement.style.background = night ? '#1C2638' : '';   // the twilight Horizon's mid colour
    try { localStorage.setItem('fmtv.night', night ? 'true' : 'false'); } catch (e) {}   // for index.html, next start
  }

  var saverOn = false, idleAt = Date.now(), saverPick = null, SAVER_AFTER = 5 * 60000;

  /** The screensaver's photos: picks with an app on the TV. */
  function saverPhotos() {
    return Data.showcase().filter(function (p) { return p.backdrop && Data.appFor(p.app); });
  }

  /**
   * The screensaver (saver.js): Photos, Nature Clock, Aurora, Apple Aerials or Black, as Settings has it. Here only what
   * the home screen does around it: the live aerial pauses, OK on a photo can watch it, a key wakes the page from 0.98.
   */
  function showSaver() {
    aerialVideo.hide();
    saverOn = true;
    saverPick = null;
    Saver.show($('saver'), {photos: saverPhotos(), watch: Prefs.saverWatch(), pick: function (p) { saverPick = p; }});
  }

  /** Woken by a key: it dissolves in 320 ms while the home screen settles from 0.98. */
  function hideSaver(woken) {
    var s = $('saver');
    if (woken && saverOn) {
      s.style.webkitTransition = s.style.transition = 'opacity 0.32s cubic-bezier(0.22, 1, 0.36, 1), visibility 0s 0.32s';
      mv.wake.snap(1);
      mv.wake.set(0);
    }
    saverOn = false;
    Saver.hide(s);
    syncAerialVideo();
  }

  function poke(ok) {
    idleAt = Date.now();
    if (!saverOn) return false;
    var target = ok && Prefs.saverWatch() ? pickTarget(saverPick) : null, watched = saverPick;
    hideSaver(true);
    if (target) launcher(target.app.id, target.app.title, watched, false, target.params)();
    return true;
  }

  /**
   * In a text field, the keys type (← → move in the text, Delete erases). OK or the keyboard's Done searches (or saves); Back,
   * its Cancel or ↑ ↓ leave the field. Samsung's keyboard sends Done as 65376 and Cancel as 65385. True: handled here.
   */
  function fieldKey(k) {
    var sheet = st.zone === Z.CITY ? 'citypick' : st.zone === Z.KEY ? 'keypick' : '';
    if (!sheet || !typingIn(sheet)) return false;
    if (k === TV.keys.ok || k === 65376) { if (st.zone === Z.CITY) searchCity(); else saveKey(); }
    else if (k === TV.keys.back || k === 27 || k === 65385) { closeField(sheet); updateFocus(); }
    else if (k === TV.keys.up || k === TV.keys.down) { closeField(sheet); move(0, k === TV.keys.up ? -1 : 1); }
    return true;   // anything else types into the field
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
    Lock.show(o);
  }

  // MARK: - The welcome setup (setup.js): on a new TV, and Settings → System → Run Setup Again

  /** What the setup applies as it's picked, and `done` after its last step. */
  function setupOptions(done) {
    return {
      theme: function (v) { Prefs.set('theme', v); checkNight(); },
      glass: function (v) { Prefs.set('glass', v); checkNight(); },
      topShelf: function (v) { Prefs.set('topShelf', v); follow(); },
      temperature: function (v) { Prefs.set('temperature', v); renderPanels(); },
      people: function () { return people().map(function (p) { return {name: p.name, avatar: avatarUrl(p), profile: p}; }); },
      city: function () { return Data.city; },
      done: function () { Prefs.set('setupDone', true); done(); }
    };
  }

  /**
   * I (in a browser), or Settings → Preview Startup Animation: the whole startup and its sound again, as at power-on,
   * into "Who's Watching?" (it plays even with Startup animation off; I again starts it over).
   */
  function replayIntro() {
    if (Setup.showing()) return;
    hideSaver();
    aerialVideo.hide();
    closeSheets();
    if (st.zone === Z.DRAWER) closeDrawer();
    Profiles.lock();
    Intro.play($('intro'), function () { showLock({from: 'intro'}); }, true);
  }

  function runSetup() {
    closeSheets();
    if (st.zone === Z.DRAWER) closeDrawer();
    Setup.show($('setup'), setupOptions(function () { goHome(); }));
  }

  /** Control Center's Lock card (and Settings → Profiles & PINs → Lock). */
  function lockNow() {
    if (st.zone === Z.DRAWER) closeDrawer();
    Profiles.lock();
    showLock({from: 'lock'});
  }

  /** In, as `p`: the home screen becomes theirs (Dock, theme, wallpaper, Welly) and the TV tab unfolds. */
  function unlocked(p) {
    cancelMove(true);
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

  function inField(n) {
    return !!n && (n.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(n.tagName || ''));
  }

  function isArrow(k) { return k === TV.keys.left || k === TV.keys.right || k === TV.keys.up || k === TV.keys.down; }

  document.addEventListener('keydown', function (e) {
    var k = e.keyCode, now = Date.now();
    keyAt = now;
    Sound.wake();   // a browser holds audio until a key
    if (fieldKey(k)) return;
    var typing = st.zone === Z.WELLY && window.Welly && Welly.typing();
    // The volume works over everything (the intro, the lock, the screensaver): the TV's keys, + and − on a keyboard.
    if (volumeKey(e, typing)) { e.preventDefault(); return; }
    // [ and ] in a browser: the brightness down and up (as − and + step the volume), over everything too.
    if (!typing && (k === 219 || k === 221)) { e.preventDefault(); stepBrightness(k === 221 ? 1 : -1); return; }
    // I: the startup again, with its sound. Not while typing in a field.
    if (k === 73 && !typing && !e.repeat && !inField(e.target) && !Setup.showing()) { e.preventDefault(); replayIntro(); return; }
    // The intro and the lock take every other key while they show.
    if (Intro.showing()) { Intro.key(k); e.preventDefault(); return; }
    if (Setup.showing()) { Setup.key(k, TV.keys); e.preventDefault(); return; }
    if (Lock.showing()) { idleAt = now; if (Lock.key(e)) e.preventDefault(); return; }
    // Away longer than the grace: the lock first (the screensaver goes with it).
    if (Profiles.needsLock()) { e.preventDefault(); showLock({from: 'idle'}); return; }
    touch();
    // A guide is up: OK or Back closes it, the rest wait.
    if (guideNode) {
      if (k === TV.keys.ok || k === TV.keys.back || k === 27 || k === 8) closeGuide();
      e.preventDefault();
      return;
    }
    // A in the preview: AirPods connect (or leave), as the TV shows when they join over Bluetooth.
    if (!typing && k === 65 && PREVIEW) { e.preventDefault(); if (!e.repeat) toggleAirPods(); return; }
    // A browser's stand-ins for the remote: H is Home (a press: the Dock; held: Recent Apps), S the screensaver (S
    // again: back), V keeps the volume HUD up in the preview. Not while Welly's text field has the keyboard.
    if (!typing && (k === 72 || k === 83 || (k === 86 && PREVIEW))) {
      e.preventDefault();
      if (e.repeat) return;
      if (k === 72) homeDown();
      else if (k === 83) toggleSaver();
      else pinHud();
      return;
    }
    // Over Aerials or Nature Clock, as tvOS 12: ← → the previous or next place, ↓ its name; the others wake the TV.
    if (saverOn && Saver.key(k === TV.keys.left ? 'left' : k === TV.keys.right ? 'right' : k === TV.keys.down ? 'down' : '')) {
      idleAt = now;
      e.preventDefault();
      return;
    }
    if (poke(k === TV.keys.ok)) { e.preventDefault(); return; }   // the first press only wakes the screen (or OK watches)
    if (Forecast.showing()) { if (Forecast.key(k, TV.keys)) e.preventDefault(); return; }
    if (launching) endLaunch(false);   // a key while an app opens: its card shrinks back
    // Naming a folder, or moving an app: those take the keys (the volume and Home were handled above).
    if (naming) { if (nameKey(e)) e.preventDefault(); return; }
    if (mm && mm.landing) endLanding();
    if (mm) { e.preventDefault(); moveKey(e); return; }
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
      // Held (~0.5 s) on something that can move: it lifts to be moved; the release after a hold opens nothing.
      if (canMove()) {
        Sound.holdStart();
        holdTimer = setTimeout(function () {
          holdTimer = 0;
          if (!okDown) return;
          okDown = 'held';
          if (pressed) pressed.p.set(0);
          pressed = null;
          Sound.holdCommit();
          startMove();
        }, HOLD_MS);
      }
      return;
    }
    else if (k === TV.keys.back || k === 27 || k === 8) back();
    else return;
    e.preventDefault();
  });
  document.addEventListener('keyup', function (e) {
    var k = e.keyCode;
    if (k === 72) { homeUp(); return; }
    if (isArrow(k)) { setScrub(false); return; }
    if (k !== TV.keys.ok || !okDown) return;
    if (holdTimer) { clearTimeout(holdTimer); holdTimer = 0; Sound.holdCancel(); }
    if (okDown === 'held') { okDown = false; e.preventDefault(); return; }
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
    cancelMove(true);
    reset();
    if (Profiles.needsLock() && !Intro.showing() && !Lock.showing()) showLock({from: 'idle'});
    loadApps(function () { renderShelf(); renderDrawer(); Data.refresh(); });
  }

  window.addEventListener('resize', fit);
  document.addEventListener('webOSRelaunch', reopened);   // LG: opened again while running
  window.addEventListener('appcontrol', reopened);        // Samsung: the same
  document.addEventListener('visibilitychange', function () {
    cancelMove(true);   // leaving (or coming back) mid-move: everything as it was, before the lists load again
    if (document.hidden) { aerialVideo.hide(); hideSaver(); return; }
    idleAt = Date.now();
    // Back from the app: its card, whole again, shrinks into the tile as the page comes forward.
    if (card) { clearTimeout(launchTimer); cardBack(true); launching = null; mv.launch.snap(1); mv.launch.set(0); }
    else endLaunch(true);
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
  applyBrightness();
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
    readVolume();   // where the TV lets the app read its volume: Control Center's volume row and the HUD
  });
  // Power-on: the Liquid Glass startup handing off to "Who's Watching?"; with Startup animation off, the lock at once
  // (preview builds skip both with ?nolock).
  Lock.init($('lock'), {onUnlock: unlocked});
  if (window.PREVIEW_BUILD && /[?&]nolock\b/.test(location.search)) {
    Intro.skip($('intro'));
    Profiles.unlock(Profiles.current());
  } else {
    Intro.play($('intro'), function (shown) {
      if (Prefs.setupDone()) showLock({from: shown ? 'intro' : 'power'});
      else Setup.show($('setup'), setupOptions(function () { showLock({from: 'intro'}); }));   // a new TV: the welcome setup first
    });
  }
  if (!PREVIEW && zoneWrong()) setTimeout(function () { toast(tr("The TV's clock looks set to the wrong time zone. Fix it in Home Settings › Location & Media.")); }, 8000);
  // The Top Shelf pages by itself while the focus is elsewhere (and not on an app whose pick it shows).
  setInterval(function () {
    if (!saverOn && st.zone === Z.SHELF && st.tab === TAB.TV) page(1);   // the focused app's picks, when it has several
  }, 8000);
  setInterval(function () {
    checkNight();
    Data.refresh();
    if (!saverOn && !sheetOpen() && !Forecast.showing() && !Lock.showing() && !Intro.showing() && !Setup.showing() && Date.now() - idleAt > SAVER_AFTER) showSaver();
  }, 30000);

  // For the browser preview and tests.
  window.FreeMyTV = {state: st, replayIntro: replayIntro, move: move, activate: activate, back: back, showSaver: showSaver, hideSaver: hideSaver, toast: toast,
    lock: lockNow, showLock: showLock, openSettings: openSettings, recents: openRecents, forecast: openForecast,
    tab: function (i) { st.tab = i; st.zone = Z.TABS; updateFocus(); },
    // Control Center, Home and the volume, for the tests and the screenshots.
    drawer: function (mode) { openDrawer(mode); }, profile: function () { openDrawer('profile'); },
    inputs: function () { if (ccMode !== 'profile' || st.zone !== Z.DRAWER) openDrawer('profile'); openInputsSheet(); },
    home: goHome, toggleSaver: toggleSaver,
    // All Apps' folder open (the screenshots), and the brightness ([ and ]) for the tests.
    // The Dock's and All Apps' titles in order (folders as their names), for the tests.
    apps: function () {
      var tcl = others.filter(function (a) { return a.id === 'folder:tcl'; })[0];
      var apple = others.filter(function (a) { return a.id === 'folder:apple'; })[0];
      return {dock: shelf.map(function (a) { return a.title; }), grid: others.map(function (a) { return a.title; }),
        tcl: tcl ? tcl.apps.map(function (a) { return a.title; }) : [],
        utilities: (others.filter(function (a) { return a.id === 'folder:utilities'; })[0] || {apps: []}).apps.map(function (a) { return a.title; }),
        apple: apple ? apple.apps.map(function (a) { return a.title + (a.guide ? ' (guide)' : ''); }) : []};
    },
    folder: function () {
      for (var i = 0; i < others.length; i++) if (others[i].folder) { openApps(i); openFolder(others[i], fxCells[i]); return true; }
      return false;
    },
    // AirPods (as A does) and an Apple guide, for the tests and the screenshots.
    airpods: function (name) { setAirPods(name === undefined ? 'AirPods Pro' : name); },
    guide: function (key) { if (key) openGuide(key); else closeGuide(); },
    brightness: function () { return Prefs.brightness(); },
    setup: function (n) { runSetup(); if (n) Setup.go(n); },
    cc: function () { return ccItems.map(function (it) { return {kind: it.kind, label: it.spec ? it.spec.label : it.node.textContent, row: it.row}; }); },
    saverOn: function () { return saverOn; },
    volume: function () { return {level: volumeLevel, muted: volumeMuted, shown: !!hud && mv.hud.to === 1}; },
    // What's playing or a timer, when a TV can tell (none can yet on webOS/Tizen): the pill widens to show it.
    nowPlaying: nowPlaying};
  // Preview-only controller seam for deterministic browser tests.  It calls the
  // same focus/background path as a remote key; production packages have no
  // PREVIEW_BUILD and never expose it.
  if (window.PREVIEW_BUILD) {
    window.Home = window.Home || {};
    window.Home.previewWallpaperPicker = function (category, option) {
      openWallpaperPicker();
      var categories = Wallpaper.categories(), options;
      st.wallpaperCategory = Math.max(0, categories.indexOf(category || 'aerial'));
      options = Wallpaper.options(categories[st.wallpaperCategory]);
      st.wallpaperOption = Math.max(0, options.indexOf(option || 'aerial:yosemite'));
      st.wallpaperPane = 1;
      renderWallpaperPicker(); updateFocus();
      return {category: categories[st.wallpaperCategory], option: options[st.wallpaperOption]};
    };
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
