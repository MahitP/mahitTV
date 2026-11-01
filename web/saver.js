/*
 * The screensaver (the approved plan, Step 2D): Photos (the picks, the default), Nature Clock (the depth-layered scenes),
 * Aurora, Apple Aerials and Black, chosen in Settings → Appearance & Startup → Screensaver. home.js decides when it
 * shows (idle, Control Center, the S key) and what a key does to it; this file only draws it.
 *
 *   Saver.show(node, {photos, pick(p), watch}): starts the chosen mode in `node` (#saver)
 *   Saver.hide(node): stops it; once faded, lets go of its images and canvases
 *
 * The nature scenes are adapted from tvOS-Launcher (Apache-2.0), Scenes.java: the photo, then a giant clock filled with
 * the scene's own blurred glass texture (a soft shadow under it, a light rim on it), then the foreground cutout over the
 * clock, so the clock sits inside the landscape; the date and weather in Inter above. No shade over the photo. The water
 * scene has no cutout: under its waterline the clock is drawn again, wetter. Clock styles: Tall Glass (Antonio) and
 * Rounded (Varela Round, drawn a little taller, as iOS's rounded clock). Fonts other than Inter are only used here.
 */
var Saver = (function () {
  'use strict';

  var on = false, timer = 0, natureTimer = 0, cleanup = 0;

  function el(tag, cls, parent, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    if (parent) parent.appendChild(e);
    return e;
  }

  function has(e, c) { return (' ' + e.className + ' ').indexOf(' ' + c + ' ') >= 0; }

  function setClass(e, c, yes) {
    if (yes && !has(e, c)) e.className = (e.className + ' ' + c).replace(/^\s+/, '');
    else if (!yes && has(e, c)) e.className = (' ' + e.className + ' ').replace(' ' + c + ' ', ' ').replace(/^\s+|\s+$/g, '');
  }

  function T(e, t) { e.style.webkitTransform = e.style.transform = t; }

  /** A slow drift from rest: the reset applies first, so it runs every time. */
  function kenBurns(e, fade, seconds, to) {
    e.style.webkitTransition = e.style.transition = fade;
    T(e, 'none');
    void e.offsetWidth;
    if (Motion.instant) return;
    var curve = ' ' + seconds + 's cubic-bezier(0.37, 0, 0.63, 1)';
    e.style.webkitTransition = fade + ', -webkit-transform' + curve;
    e.style.transition = fade + ', transform' + curve;
    T(e, to);
  }

  function lite() { return has(document.documentElement, 'lite'); }

  // MARK: - The clock over photos, the aurora, the aerials and black

  function domClock(node) {
    var clock = el('div', 'clock', node);
    el('div', 'time', clock);
    el('div', 'date', clock);
    el('div', 'title', clock);
    el('div', 'hint', clock);
    return clock;
  }

  function tickClock(clock) {
    var now = Date.now();
    clock.firstChild.textContent = formatDate('format|h:mm', now);
    clock.children[1].textContent = formatDate('format|EEEE, MMMM d', now);
  }

  function zoomIn(node, cls) {
    var zoom = el('div', 'zoom' + (cls ? ' ' + cls : ''), node);
    T(zoom, Motion.instant ? '' : 'scale(1.06)');
    setTimeout(function () { T(zoom, ''); }, 30);
    // Under everything, the Frosted Horizon: never a black screen while an image loads, or if one doesn't.
    el('div', 'hz hz-day', zoom);
    el('div', 'hz hz-night', zoom);
    return zoom;
  }

  /** Photos: the picks drifting, 12 s each, with the title and, with Watch on, "Press OK to watch". */
  function photos(node, o) {
    var list = o.photos || [], zoom = zoomIn(node), a = el('div', 'photo', zoom), b = el('div', 'photo', zoom), front = a, index = 0;
    setClass(node, 'bare', !list.length);
    if (list.length) el('div', 'shade', node);   // a light shade under the clock, only over photos
    var clock = domClock(node), hint = clock.children[3];
    function next() {
      if (!on) return;
      var p = list.length ? list[index++ % list.length] : null, back = front === a ? b : a;
      if (p) {
        back.style.backgroundImage = 'url(' + Data.image(p.backdrop, 'original') + ')';
        T(back, 'none');
        clock.children[2].textContent = p.title;
        if (o.pick) o.pick(p);
        var app = o.watch && Data.appFor(p.app);
        hint.textContent = app ? trf('Press OK to watch on {app} · any other button to close', {app: app.title}) : '';
        setTimeout(function () {
          kenBurns(back, 'opacity 2.4s', 24, 'scale(1.08) translateX(-1.5%)');
          setClass(back, 'on', true);
          setClass(front, 'on', false);
          front = back;
        }, 60);
      }
      tickClock(clock);
      timer = setTimeout(next, 12000);
    }
    next();
  }

  /** Aurora: soft bands of light drifting over a night sky (transform only), the glass clock over them. */
  function aurora(node) {
    var zoom = zoomIn(node), sky = el('div', 'aurora', zoom);
    el('div', 'band b1', sky);
    el('div', 'band b2', sky);
    el('div', 'band b3', sky);
    setClass(node, 'aurora-on', true);
    var clock = domClock(node);
    (function minute() {
      if (!on) return;
      tickClock(clock);
      timer = setTimeout(minute, 15000);
    })();
  }

  var AERIALS = ['aerials/coast.jpg', 'aerials/valley.jpg', 'aerials/earth.jpg', 'aerials/aurora.jpg'];

  /** Apple Aerials: the four aerial stills, 30 s each, a slow Ken Burns and a 3 s cross-fade. */
  function aerials(node) {
    var zoom = zoomIn(node), a = el('div', 'photo', zoom), b = el('div', 'photo', zoom), front = a, index = Math.floor(Math.random() * AERIALS.length);
    setClass(node, 'aerials-on', true);
    el('div', 'shade', node);
    var clock = domClock(node);
    function next() {
      if (!on) return;
      var back = front === a ? b : a;
      back.style.backgroundImage = 'url(' + AERIALS[index++ % AERIALS.length] + ')';
      T(back, 'none');
      setTimeout(function () {
        kenBurns(back, 'opacity 3s', 34, 'scale(1.1) translateX(1.5%)');
        setClass(back, 'on', true);
        setClass(front, 'on', false);
        front = back;
      }, 60);
      tickClock(clock);
      timer = setTimeout(next, 30000);
    }
    next();
    (function minute() {
      if (!on) return;
      tickClock(clock);
      natureTimer = setTimeout(minute, 15000);
    })();
  }

  /** Black: a dim clock that moves a little every minute, so nothing stays lit in one place. */
  function black(node) {
    setClass(node, 'black', true);
    var clock = domClock(node);
    var moves = 0;
    (function tickBlack() {
      if (!on) return;
      tickClock(clock);
      if (moves++ % 4 === 0) {   // every minute (the clock itself every 15 s)
        // Right and up only: it rests at the bottom-left corner, so a move left or down would push it off screen.
        var dx = Math.round(Math.random() * 360), dy = -Math.round(Math.random() * 220);
        T(clock, 'translate(' + dx + 'px, ' + dy + 'px)');
      }
      timer = setTimeout(tickBlack, 15000);
    })();
  }

  // MARK: - Nature Clock: the depth-layered scenes

  /*
   * Each spec is in tvOS-Launcher's units of 1/540 of the height: clock top, clock size, left (0: centred), date top,
   * date left, then [water line, dark clock, dark date].
   */
  var SCENES = [
    {id: 'night', clockTop: 62, size: 178, left: 0, dateTop: 42, dateLeft: 0},
    {id: 'golden', clockTop: 76, size: 195, left: 0, dateTop: 53, dateLeft: 0},
    {id: 'water', clockTop: 134, size: 210, left: 0, dateTop: 108, dateLeft: 0, water: true},
    {id: 'fog', clockTop: 86, size: 235, left: 48, dateTop: 58, dateLeft: 52, darkClock: true, darkDate: true},
    {id: 'dawn', clockTop: 115, size: 200, left: 0, dateTop: 88, dateLeft: 0}
  ];
  var images = {};
  // The clock's glass, made once and reused every minute (the water scene's wet copy has its own): no canvas per draw.
  var glassCanvas = null, wetCanvas = null, wetSource = null;

  /** The clock's font: Tall Glass (Antonio Light) or Rounded (Varela Round, 0.78 of the size, 1.24 taller). */
  function face(px) {
    return Prefs.saverClock() === 'rounded' ? {font: '400 ' + Math.round(px * 0.78) + 'px "Varela Round", Inter, sans-serif', tall: 1.24, family: 'Varela Round'}
      : {font: '300 ' + px + 'px Antonio, Inter, sans-serif', tall: 1, family: 'Antonio'};
  }

  /** Text drawn `tall` times its height, standing on its baseline. */
  function tallText(g, text, x, base, tall, stroke) {
    if (tall !== 1) g.setTransform(1, 0, 0, tall, 0, base * (1 - tall));
    if (stroke) g.strokeText(text, x, base); else g.fillText(text, x, base);
    if (tall !== 1) g.setTransform(1, 0, 0, 1, 0, 0);
  }

  /** A scene's three images (the cutout may be missing: the water scene has none, and old TVs may not read WebP). */
  function sceneImages(sc, then) {
    var got = images[sc.id];
    if (got) { if (got.ready) then(got); else got.waiting.push(then); return; }
    got = images[sc.id] = {bg: new Image(), glass: new Image(), fg: sc.water ? null : new Image(), ready: false, waiting: [then]};
    var left = got.fg ? 3 : 2;
    function one() {
      if (--left > 0) return;
      got.ready = true;
      for (var i = 0; i < got.waiting.length; i++) got.waiting[i](got);
      got.waiting = [];
    }
    got.bg.onload = got.bg.onerror = one;
    got.glass.onload = got.glass.onerror = one;
    got.bg.src = 'scenes/scene-' + sc.id + '.jpg';
    got.glass.src = 'scenes/scene-' + sc.id + '-glass.jpg';
    if (got.fg) { got.fg.onload = one; got.fg.onerror = function () { got.fg = null; one(); }; got.fg.src = 'scenes/scene-' + sc.id + '-fg.webp'; }
  }

  function loaded(img) { return img && img.complete && img.naturalWidth > 0; }

  /** The clock's glass: the scene's glass texture screen-sized, a diagonal sheen over it, cut to the digits. */
  function glassDigits(img, text, x, base, f, cap, wet, dark) {
    var c = wet ? wetCanvas : glassCanvas;
    if (!c) {
      c = document.createElement('canvas');
      c.width = 1920;
      c.height = 1080;
      if (wet) wetCanvas = c; else glassCanvas = c;
    }
    var g = c.getContext('2d');
    g.globalCompositeOperation = 'source-over';
    g.clearRect(0, 0, 1920, 1080);
    g.font = f.font;
    g.fillStyle = '#FFF';
    tallText(g, text, x, base, f.tall, false);
    g.globalCompositeOperation = 'source-in';
    if (loaded(img)) {
      var src = img;
      if (wet) {   // blurrier under the water: the texture shrunk to 120 × 68 and stretched back
        if (!wetSource) {
          wetSource = document.createElement('canvas');
          wetSource.width = 120;
          wetSource.height = 68;
        }
        src = wetSource;
        src.getContext('2d').drawImage(img, 0, 0, 120, 68);
      }
      g.drawImage(src, 0, 0, 1920, 1080);
    }
    var sheen = g.createLinearGradient(0, 0, 864, 1080);
    sheen.addColorStop(0, 'rgba(255, 255, 255, 0.27)');
    sheen.addColorStop(0.35, 'rgba(255, 255, 255, 0.11)');
    sheen.addColorStop(0.65, 'rgba(255, 255, 255, 0.04)');
    sheen.addColorStop(1, 'rgba(255, 255, 255, 0.1)');
    g.fillStyle = sheen;
    g.fillRect(0, 0, 1920, 1080);
    // The frost: airy at the top of the digits, milkier at the bottom, so the glass reads over any sky.
    var frost = g.createLinearGradient(0, base - cap, 0, base);
    frost.addColorStop(0, dark ? 'rgba(255, 255, 255, 0.18)' : 'rgba(218, 224, 255, 0.28)');
    frost.addColorStop(0.52, dark ? 'rgba(255, 255, 255, 0.32)' : 'rgba(236, 232, 255, 0.44)');
    frost.addColorStop(1, dark ? 'rgba(255, 255, 255, 0.5)' : 'rgba(255, 242, 252, 0.64)');
    g.fillStyle = frost;
    g.fillRect(0, base - cap, 1920, cap);
    g.globalCompositeOperation = 'source-over';
    return c;
  }

  /** One scene, drawn whole into `canvas` (once a minute: the clock changes; nothing moves in between). */
  function drawScene(canvas, sc, im) {
    var g = canvas.getContext('2d'), u = 2, now = Date.now(), w = Data.weather;
    g.clearRect(0, 0, 1920, 1080);
    if (loaded(im.bg)) g.drawImage(im.bg, 0, 0, 1920, 1080);
    var text = formatDate('format|h:mm', now).replace(/\s?[AaPp]\.?\s?[Mm]\.?$/, ''), f = face(sc.size * u);
    g.font = f.font;
    // Antonio's digits are tall: its own measure where the browser has one, else 0.86 of the size.
    var metrics = g.measureText(text), tw = metrics.width, px = parseInt(f.font.split(' ')[1], 10);
    var cap = (metrics.actualBoundingBoxAscent || px * (f.tall === 1 ? 0.86 : 0.72)) * f.tall;
    var dateBase = Math.max((46 + 19) * u, (sc.dateTop + 19) * u);
    var top = Math.max((sc.clockTop + 19) * u, dateBase + 14 * u), base = top + cap;
    var x = sc.left ? sc.left * u : (1920 - tw) / 2;
    var waterY = sc.water ? 1080 * 0.55 : 1080;
    // The clock: its shadow, the glass, the rim (above the waterline); under it, the wet copy.
    g.save();
    g.beginPath();
    g.rect(0, 0, 1920, waterY);
    g.clip();
    g.fillStyle = 'rgba(0, 0, 0, 0.2)';
    tallText(g, text, x, base + 4 * u, f.tall, false);
    g.drawImage(glassDigits(im.glass, text, x, base, f, cap, false, sc.darkClock), 0, 0);
    var rim = g.createLinearGradient(0, base - cap, 0, base);
    rim.addColorStop(0, sc.darkClock ? 'rgba(255, 255, 255, 0.82)' : 'rgba(244, 248, 255, 0.9)');
    rim.addColorStop(0.5, sc.darkClock ? 'rgba(255, 255, 255, 0.63)' : 'rgba(232, 236, 255, 0.73)');
    rim.addColorStop(1, sc.darkClock ? 'rgba(255, 255, 255, 0.9)' : 'rgba(255, 255, 255, 0.98)');
    g.globalAlpha = 0.85;
    g.lineWidth = 2.6;
    g.strokeStyle = rim;
    tallText(g, text, x, base, f.tall, true);
    g.globalAlpha = 1;
    g.restore();
    if (sc.water) {
      g.save();
      g.beginPath();
      g.rect(0, waterY, 1920, 1080 - waterY);
      g.clip();
      g.globalAlpha = 0.62;
      g.drawImage(glassDigits(im.glass, text, x, base, f, cap, true, sc.darkClock), 0, 0);
      g.globalAlpha = 1;
      g.restore();
    }
    if (im.fg && loaded(im.fg)) g.drawImage(im.fg, 0, 0, 1920, 1080);
    // The date and the weather, above the clock.
    var line = formatDate('format|EEEE, MMMM d', now);
    if (w) line = trf('{date} · {temp} {sky}', {date: line, temp: Data.degrees(w.temp), sky: Data.condition(w.code, w.isDay)});
    g.font = '600 ' + Math.round(13.5 * u) + 'px Inter, sans-serif';
    g.textAlign = sc.dateLeft ? 'left' : 'center';
    g.fillStyle = sc.darkDate ? 'rgba(22, 32, 29, 0.78)' : 'rgba(240, 242, 248, 0.92)';
    if (!sc.darkDate) { g.shadowColor = 'rgba(0, 0, 0, 0.45)'; g.shadowBlur = 14; g.shadowOffsetY = 3; }
    g.fillText(line, sc.dateLeft ? sc.dateLeft * u : 960, dateBase);
    g.shadowColor = 'rgba(0, 0, 0, 0)';
    g.textAlign = 'left';
  }

  function nature(node) {
    var zoom = zoomIn(node, 'nature');
    var a = el('canvas', 'scene', zoom), b = el('canvas', 'scene', zoom), front = a;
    a.width = b.width = 1920;
    a.height = b.height = 1080;
    // One chosen scene, or Shuffle: a new scene every minute (a 2.4 s cross-fade) from a random start.
    var chosen = Prefs.saverScene(), fixed = -1, startAt = Math.floor(Math.random() * SCENES.length);
    for (var i = 0; i < SCENES.length; i++) if (SCENES[i].id === chosen) fixed = i;
    var shown = -1, drawnAt = '';
    function frame() {
      if (!on) return;
      var minute = formatDate('format|h:mm', Date.now());
      var want = fixed >= 0 ? fixed : (startAt + Math.floor(Date.now() / 60000)) % SCENES.length;
      if (want !== shown) {
        var sc = SCENES[want], next = shown < 0 ? front : front === a ? b : a;
        shown = want;
        sceneImages(sc, function (im) {
          if (!on) return;
          drawScene(next, sc, im);
          drawnAt = minute;
          next.sc = sc;
          next.im = im;
          setClass(next, 'on', true);
          if (next !== front) setClass(front, 'on', false);
          front = next;
        });
      } else if (minute !== drawnAt && front.sc) {
        drawScene(front, front.sc, front.im);
        drawnAt = minute;
      }
      natureTimer = setTimeout(frame, 1000);
    }
    frame();
    // The clock's font may arrive after the first frame: draw again once it's in. Old TVs have no document.fonts and
    // load a web font only for text on the page: a hidden line in it asks for it, then the clock is drawn again.
    function redraw() { if (on && front.sc) drawScene(front, front.sc, front.im); }
    var f = face(100);
    if (document.fonts && document.fonts.load) {
      document.fonts.load(f.font).then(redraw, function () {});
    } else {
      var probe = el('span', 'font-probe', node, '0123456789:');
      probe.style.fontFamily = f.family;
      setTimeout(redraw, 1500);
      setTimeout(redraw, 4000);
    }
  }

  // MARK: - Showing and hiding

  function show(node, o) {
    o = o || {};
    clearTimeout(cleanup);
    clearTimeout(timer);
    clearTimeout(natureTimer);
    on = true;
    node.innerHTML = '';
    node.className = node.className.replace(/\s*(bare|black|aurora-on|aerials-on)\b/g, '');
    node.style.webkitTransition = node.style.transition = '';
    var style = Prefs.saverStyle();
    // Tizen 2.4 (lite) can't hold the scenes' full-screen canvases: it keeps the Photos screensaver.
    if (style === 'nature' && lite()) style = 'photos';
    if (style === 'nature') nature(node);
    else if (style === 'aurora') aurora(node);
    else if (style === 'aerials') aerials(node);
    else if (style === 'black') black(node);
    else photos(node, o);
    setClass(node, 'on', true);
  }

  function hide(node) {
    on = false;
    clearTimeout(timer);
    clearTimeout(natureTimer);
    setClass(node, 'on', false);
    // Once it has faded, the screensaver lets go of its photos, scenes and canvases (old TVs have little memory).
    cleanup = setTimeout(function () {
      if (on) return;
      var canvases = node.getElementsByTagName('canvas');
      for (var i = 0; i < canvases.length; i++) canvases[i].width = canvases[i].height = 0;
      node.innerHTML = '';
      node.className = node.className.replace(/\s*(bare|black|aurora-on|aerials-on)\b/g, '');
      images = {};
      glassCanvas = wetCanvas = wetSource = null;
    }, 1400);
  }

  return {show: show, hide: hide, showing: function () { return on; }, SCENES: SCENES};
})();
