/*
 * The Liquid Glass & Prismatic Horizon startup (TVOS-LAUNCHER-MERGE-SPEC.md Part 2 §1). From studio midnight a seam of
 * light ignites on the horizon line and opens onto the Frosted Horizon (day, or its twilight); "mahitTV" comes into
 * focus as glass, a −28° band of light crosses it with a prism fringe along the letters while a soft ring spreads over
 * the sky; then the wordmark goes and "Who's Watching?" comes in over the same, uninterrupted Horizon. A Cmaj9 glass
 * chord goes with it. OK or Back jumps to the hand-off. Reduce motion or an old TV (lite): the Horizon and the still
 * wordmark, then the hand-off at once. Startup animation off: no startup at all (the lock straight away).
 * Everything is built in play(); a frame only sets transforms, alphas and composite modes (gradients are made once and
 * placed with setTransform).
 */
var Intro = (function () {
  'use strict';

  /** Every parameter in one place (times in s, sizes in px on the 1920×1080 stage). */
  var INTRO = {
    W: 1920, H: 1080,
    midnight: '#090D16',
    // Stage 1: the anamorphic ignition.
    horizonY: 0.44,             // the seam's line, of the height
    apertureAt: 0.08,           // the opening starts (SPATIAL_SCROLL)
    seamIn: [0.08, 0.18],       // the seam brightens (smoothstep)
    seamOut: [0.30, 0.65],      // and fades (smoothstep)
    seamPeak: 0.9,
    seamCore: 1.5, seamGrow: 38, // half-height: core + grow·opening
    featherBase: 8, featherGrow: 0.10,
    // Stage 2: the glass wordmark.
    word: 'mahitTV', font: 116, weight: 700, tracking: -0.03,
    wordAt: 0.40, wordFadeIn: 0.30, wordFrom: 1.10,   // scale 1.10 → 1 and defocus → sharp on TITLE_SETTLE
    box: [1100, 220],                                   // the word's canvas
    refract: 1.04,                                      // the sky through the letters, magnified
    tintDay: [74, 93, 112, 0.38], frostNight: [255, 255, 255, 0.22],   // #4A5D70 by day, white frost at night
    shadowDay: 'rgba(74, 93, 112, 0.30)', shadowNight: 'rgba(255, 255, 255, 0.10)',
    rim: 1.5, rimHi: 0.9, rimLo: 0.25,
    sweep: [0.95, 1.75], sweepAngle: -28, sweepWidth: 0.30, sweepLift: 0.55,
    prism: ['#7DD3FC', '#FFFFFF', '#FEF3C7'], prismPx: 1.5,
    ripple: [1.10, 2.00], rippleReach: 0.9, ripplePeak: 0.16,
    // Stage 3: the hand-off.
    handoff: 2.10, wordOut: 0.28, wordTo: 0.94, end: 2.38,
    stillHold: 0.7,
    // The Cmaj9 glass resonance chord (§1.6).
    chord: {
      sub: [65.41, 98.00], subAt: 0.05, subAttack: 0.18, subTau: 0.55, subLevel: 0.30,
      bells: [261.63, 392.00, 493.88, 587.33, 659.25], bellAt: 0.62, bellRoll: 0.018, bellAttack: 0.008,
      bellTau: 0.45, bellLevel: 0.10, detune: [0.999134, 1.000867],
      partials: [[2.00, 0.22], [3.01, 0.07], [4.20, 0.03]],
      length: 2.6, master: 0.18
    }
  };

  // Apple's springs (response, damping), evaluated in closed form from rest: the same curve as Motion.Spring, at any time.
  var P = Motion.P;
  function springAt(pre, t) {
    if (t <= 0) return 0;
    var w = 2 * Math.PI / pre[0], z = pre[1];
    if (z >= 1) return 1 - Math.exp(-w * t) * (1 + w * t);
    var wd = w * Math.sqrt(1 - z * z);
    return 1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + z * w / wd * Math.sin(wd * t));
  }
  function smooth(a, b, t) { var x = Math.max(0, Math.min(1, (t - a) / (b - a))); return x * x * (3 - 2 * x); }
  function easeOut(x) { x = Math.max(0, Math.min(1, x)); return 1 - (1 - x) * (1 - x) * (1 - x); }

  var layer = null, canvas = null, ctx = null, hzDay = null, hzNight = null, veil = null, dark = false;
  var sharp = null, blur = null, cover = null, prisms = [], sweepC = null, sctx = null, seam = null;
  var edgeTop = null, ringGrad = null, bandGrad = null;
  var running = false, still = false, frame = 0, start = 0, t = 0, handAt = 0, frozen = 0, handed = false;
  var done = null, audio = null, wordTimer = 0;
  var bandDir = [Math.cos(INTRO.sweepAngle * Math.PI / 180), Math.sin(INTRO.sweepAngle * Math.PI / 180)];

  function make(w, h) {
    var c = document.createElement('canvas');
    c.width = w;
    c.height = h;
    return c;
  }
  function night() { return (' ' + document.body.className + ' ').indexOf(' night ') >= 0; }

  // MARK: - Built once

  /** The Frosted Horizon (web/home.css .hz-day/.hz-night) at half size, for the sky seen through the glass letters. */
  function makeHorizon(dark) {
    var w = INTRO.W / 2, h = INTRO.H / 2, c = make(w, h), g = c.getContext('2d');
    var base = dark ? ['#0D131F', '#1C2638', '#080B12'] : ['#B8C7D6', '#DCE6EF', '#7E9BB5'];
    var glow = dark ? [[28, 38, 56], [21, 30, 45], [8, 11, 18]] : [[245, 235, 214], [220, 230, 239], [126, 155, 181]];
    var lin = g.createLinearGradient(0, 0, 0, h);
    lin.addColorStop(0, base[0]);
    lin.addColorStop(0.56, base[1]);
    lin.addColorStop(1, base[2]);
    g.fillStyle = lin;
    g.fillRect(0, 0, w, h);
    // CSS paints its first background on top: the lower-right glow first, the left horizon glow last.
    var fields = [[1.06, 1.04, 1.04, 0.72, glow[2]], [0.53, 0.48, 0.78, 0.74, glow[1]], [-0.08, 0.69, 0.83, 0.72, glow[0]]];
    for (var i = 0; i < fields.length; i++) {
      var f = fields[i], col = f[4].join(', '), rg = g.createRadialGradient(0, 0, 0, 0, 0, 1);
      rg.addColorStop(0, 'rgba(' + col + ', 1)');
      rg.addColorStop(f[3], 'rgba(' + col + ', 0)');
      g.setTransform(f[2] * w, 0, 0, f[2] * h, f[0] * w, f[1] * h);
      g.fillStyle = rg;
      g.fillRect(-4, -4, 8, 8);
    }
    var vg = g.createRadialGradient(0, 0, 0, 0, 0, 1);
    vg.addColorStop(0.45, 'rgba(0, 0, 0, 0)');
    vg.addColorStop(1, 'rgba(0, 0, 0, ' + (dark ? 0.18 : 0.12) + ')');
    g.setTransform(w * 0.7071, 0, 0, h * 0.7071, w / 2, h / 2);
    g.fillStyle = vg;
    g.fillRect(-2, -2, 4, 4);
    g.setTransform(1, 0, 0, 1, 0, 0);
    return c;
  }

  /** The word's glyphs, each placed with the −0.03 em tracking (old TVs have no letterSpacing). */
  function layout(g) {
    var spots = [], x = 0, track = INTRO.tracking * INTRO.font;
    for (var i = 0; i < INTRO.word.length; i++) {
      var ch = INTRO.word.charAt(i);
      spots.push([ch, x]);
      x += g.measureText(ch).width + track;
    }
    return {spots: spots, width: x - track};
  }

  function font(g) {
    g.font = INTRO.weight + ' ' + INTRO.font + 'px Inter, "Helvetica Neue", Arial, sans-serif';
    g.textBaseline = 'alphabetic';
    g.textAlign = 'left';
  }

  function eachGlyph(g, word, x0, y0, fn) {
    for (var i = 0; i < word.spots.length; i++) fn(word.spots[i][0], x0 + word.spots[i][1], y0);
  }

  /**
   * An edge of the whole word (not of each glyph, which overlap with the tracking): the letters minus themselves
   * moved (dx, dy), in `color`. (+d, +d) leaves the top-left edges, (−d, −d) the bottom-right ones.
   */
  function edge(dst, color, alpha, dx, dy) {
    var bw = INTRO.box[0], bh = INTRO.box[1], e = make(bw, bh), g = e.getContext('2d');
    g.drawImage(cover, 0, 0);
    g.globalCompositeOperation = 'source-in';
    g.fillStyle = color;
    g.fillRect(0, 0, bw, bh);
    g.globalCompositeOperation = 'destination-out';
    g.drawImage(cover, dx, dy);
    var dg = dst.getContext('2d');
    dg.globalAlpha = alpha;
    dg.drawImage(e, 0, 0);
    dg.globalAlpha = 1;
  }

  /** The glass word and everything the sweep needs, at the word canvas's size. */
  function makeWord(dark) {
    var bw = INTRO.box[0], bh = INTRO.box[1];
    var probe = make(4, 4).getContext('2d');
    font(probe);
    var word = layout(probe), cap = 0.727 * INTRO.font;   // Inter's cap height
    var x0 = (bw - word.width) / 2, y0 = bh / 2 + cap / 2;

    // The letters' alpha.
    cover = make(bw, bh);
    var cg = cover.getContext('2d');
    font(cg);
    cg.fillStyle = '#FFF';
    eachGlyph(cg, word, x0, y0, function (ch, x, y) { cg.fillText(ch, x, y); });

    // The body: the sky through the letters at 1.04×, then the tint (slate by day, frost at night).
    var sky = makeHorizon(dark), body = make(bw, bh), bg = body.getContext('2d');
    var sw = bw / INTRO.refract, sh = bh / INTRO.refract;
    var sx = INTRO.W / 2 - sw / 2, sy = INTRO.H / 2 - sh / 2;
    bg.drawImage(sky, sx / 2, sy / 2, sw / 2, sh / 2, 0, 0, bw, bh);
    var tint = dark ? INTRO.frostNight : INTRO.tintDay;
    bg.fillStyle = 'rgba(' + tint[0] + ', ' + tint[1] + ', ' + tint[2] + ', ' + tint[3] + ')';
    bg.fillRect(0, 0, bw, bh);
    bg.globalCompositeOperation = 'destination-in';
    bg.drawImage(cover, 0, 0);
    bg.globalCompositeOperation = 'source-over';

    // The sharp glass: the soft shadow (inner tint) under, the body, the specular rim on top.
    sharp = make(bw, bh);
    var sg = sharp.getContext('2d');
    font(sg);
    sg.shadowColor = dark ? INTRO.shadowNight : INTRO.shadowDay;
    sg.shadowBlur = 6;
    sg.shadowOffsetY = 3;
    sg.fillStyle = dark ? 'rgba(255, 255, 255, 0.10)' : 'rgba(74, 93, 112, 0.30)';
    eachGlyph(sg, word, x0, y0, function (ch, x, y) { sg.fillText(ch, x, y); });
    sg.shadowColor = 'rgba(0, 0, 0, 0)';
    sg.shadowBlur = 0;
    sg.shadowOffsetY = 0;
    sg.drawImage(body, 0, 0);
    // The specular rim, 1.5 px: bright where the light comes from (top left), faint on the far side.
    edge(sharp, '#FFF', INTRO.rimHi, INTRO.rim, INTRO.rim);
    edge(sharp, '#FFF', INTRO.rimLo, -INTRO.rim, -INTRO.rim);

    // The defocused copy (24 px): down to an eighth and back, twice.
    blur = make(bw, bh);
    var small = make(Math.ceil(bw / 8), Math.ceil(bh / 8)), smid = make(Math.ceil(bw / 4), Math.ceil(bh / 4));
    var mg = smid.getContext('2d'), lg = small.getContext('2d'), blg = blur.getContext('2d');
    mg.drawImage(sharp, 0, 0, smid.width, smid.height);
    lg.drawImage(smid, 0, 0, small.width, small.height);
    mg.clearRect(0, 0, smid.width, smid.height);
    mg.drawImage(small, 0, 0, smid.width, smid.height);
    blg.drawImage(smid, 0, 0, bw, bh);

    // The prism: the rim in each of the three colours, drawn apart along the band's normal while it passes.
    prisms = [];
    for (var p = 0; p < INTRO.prism.length; p++) {
      var pc = make(bw, bh);
      edge(pc, INTRO.prism[p], 1, INTRO.rim, INTRO.rim);
      edge(pc, INTRO.prism[p], 0.6, -INTRO.rim, -INTRO.rim);
      prisms.push(pc);
    }
    sweepC = make(bw, bh);
    sctx = sweepC.getContext('2d');
    // The band: a soft stripe across x (unit wide), placed and turned with setTransform.
    bandGrad = sctx.createLinearGradient(-0.5, 0, 0.5, 0);
    bandGrad.addColorStop(0, 'rgba(255, 255, 255, 0)');
    bandGrad.addColorStop(0.25, 'rgba(255, 255, 255, 0.32)');
    bandGrad.addColorStop(0.5, 'rgba(255, 255, 255, 1)');
    bandGrad.addColorStop(0.75, 'rgba(255, 255, 255, 0.32)');
    bandGrad.addColorStop(1, 'rgba(255, 255, 255, 0)');
    return {x0: x0, width: word.width};
  }

  /** The seam: an anamorphic streak, wide and thin (stretched vertically as the horizon opens). */
  function makeSeam() {
    var w = INTRO.W, h = 64, c = make(w, h), g = c.getContext('2d'), img = g.createImageData(w, h), d = img.data;
    for (var y = 0; y < h; y++) {
      var v = (y - h / 2 + 0.5) / (h / 2), core = Math.exp(-v * v * 18), halo = Math.exp(-v * v * 3.2);
      for (var x = 0; x < w; x++) {
        var u = Math.exp(-Math.abs(x - w / 2) / (0.55 * w)), i = (y * w + x) * 4;
        var a = Math.min(1, u * (core + 0.55 * halo));
        var mixCore = core / (core + 0.55 * halo + 1e-6);   // white core, #CFE8FF halo
        d[i] = Math.round(207 + 48 * mixCore);
        d[i + 1] = Math.round(232 + 23 * mixCore);
        d[i + 2] = 255;
        d[i + 3] = Math.round(255 * a);
      }
    }
    g.putImageData(img, 0, 0);
    return c;
  }

  function makeGradients() {
    // The opening's soft edge: midnight to clear, over a unit height (placed by setTransform).
    edgeTop = ctx.createLinearGradient(0, 0, 0, 1);
    edgeTop.addColorStop(0, INTRO.midnight);
    edgeTop.addColorStop(1, 'rgba(9, 13, 22, 0)');
    // The ripple: a thin bright ring on a unit circle.
    ringGrad = ctx.createRadialGradient(0, 0, 0, 0, 0, 1);
    ringGrad.addColorStop(0, 'rgba(255, 255, 255, 0)');
    ringGrad.addColorStop(0.86, 'rgba(255, 255, 255, 0)');
    ringGrad.addColorStop(0.93, 'rgba(255, 255, 255, 1)');
    ringGrad.addColorStop(1, 'rgba(255, 255, 255, 0)');
  }

  // MARK: - The chord (Web Audio; silent where there's none)

  function context() {
    if (audio) return audio;
    var A = window.AudioContext || window.webkitAudioContext;
    if (!A) return null;
    try { audio = new A(); } catch (e) { audio = null; }
    return audio;
  }

  function voice(ac, out, freq, at, attack, tau, level, stop) {
    var o = ac.createOscillator(), g = ac.createGain();
    o.type = 'sine';
    o.frequency.value = freq;
    g.gain.setValueAtTime(0, at);
    g.gain.linearRampToValueAtTime(level, at + attack);
    g.gain.setTargetAtTime(0, at + attack, tau);
    o.connect(g);
    g.connect(out);
    o.start(at);
    o.stop(stop);
  }

  /** Cmaj9: a C2 + G2 swell as the horizon ignites, then five glass bells, each a detuned pair with three partials. */
  function chord() {
    var ac = context(), c = INTRO.chord;
    if (!ac) return;
    try {
      var t0 = ac.currentTime + 0.02, end = t0 + c.length, master = ac.createGain();
      master.gain.setValueAtTime(c.master, t0);
      master.gain.setValueAtTime(c.master, end - 0.06);
      master.gain.linearRampToValueAtTime(0, end);
      master.connect(ac.destination);
      for (var s = 0; s < c.sub.length; s++) voice(ac, master, c.sub[s], t0 + c.subAt, c.subAttack, c.subTau, c.subLevel, end);
      for (var n = 0; n < c.bells.length; n++) {
        var at = t0 + c.bellAt + n * c.bellRoll;
        for (var d = 0; d < c.detune.length; d++) {
          voice(ac, master, c.bells[n] * c.detune[d], at, c.bellAttack, c.bellTau, c.bellLevel / 2, end);
        }
        for (var p = 0; p < c.partials.length; p++) {
          voice(ac, master, c.bells[n] * c.partials[p][0], at, c.bellAttack, c.bellTau, c.bellLevel * c.partials[p][1], end);
        }
      }
    } catch (e) {}
  }

  /** A short low knock (a wrong PIN). */
  function knock() {
    var ac = context();
    if (!ac) return;
    try {
      var t0 = ac.currentTime + 0.01;
      voice(ac, ac.destination, 140, t0, 0.012, 0.05, 0.08, t0 + 0.2);
      voice(ac, ac.destination, 104, t0 + 0.07, 0.012, 0.06, 0.06, t0 + 0.3);
    } catch (e) {}
  }

  // MARK: - A frame

  var wordBox = null;

  /** The glass word at its state for time tw (frozen at a skip), alpha a, on the stage. */
  function drawWord(tw, a) {
    if (a <= 0.002 || !sharp) return;
    var bw = INTRO.box[0], bh = INTRO.box[1];
    var k = still ? 1 : springAt(P.TITLE_SETTLE, tw - INTRO.wordAt), sc = INTRO.wordFrom + (1 - INTRO.wordFrom) * k;
    var appear = still ? 1 : smooth(INTRO.wordAt, INTRO.wordAt + INTRO.wordFadeIn, tw);
    if (t >= handAt) {
      var out = easeOut((t - handAt) / INTRO.wordOut);
      sc *= 1 - (1 - INTRO.wordTo) * out;
      a *= 1 - out;
    }
    a *= appear;
    if (a <= 0.002) return;
    var dw = bw * sc, dh = bh * sc, dx = INTRO.W / 2 - dw / 2, dy = INTRO.H / 2 - dh / 2, defocus = 1 - k;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalCompositeOperation = 'source-over';
    if (defocus > 0.01) {
      ctx.globalAlpha = a * defocus;
      ctx.drawImage(blur, dx, dy, dw, dh);
    }
    ctx.globalAlpha = a * (1 - defocus);
    ctx.drawImage(sharp, dx, dy, dw, dh);
    // The caustic sweep: brighter glass and the prism fringe, inside a −28° band crossing left to right.
    var sp = still ? -1 : smooth(INTRO.sweep[0], INTRO.sweep[1], tw);
    if (sp > 0 && sp < 1) {
      var o = INTRO.prismPx, nx = -bandDir[1] * o, ny = bandDir[0] * o;
      sctx.setTransform(1, 0, 0, 1, 0, 0);
      sctx.globalCompositeOperation = 'source-over';
      sctx.globalAlpha = 1;
      sctx.clearRect(0, 0, bw, bh);
      sctx.globalAlpha = INTRO.sweepLift;
      sctx.drawImage(cover, 0, 0);
      sctx.globalAlpha = 0.9;
      sctx.globalCompositeOperation = 'lighter';
      sctx.drawImage(prisms[0], -nx, -ny);
      sctx.drawImage(prisms[1], 0, 0);
      sctx.drawImage(prisms[2], nx, ny);
      sctx.globalAlpha = 1;
      sctx.globalCompositeOperation = 'destination-in';
      // Wide and long enough to cover the whole word canvas wherever the band is.
      var wide = INTRO.sweepWidth * wordBox.width, cx = wordBox.x0 + (-0.3 + 1.6 * sp) * wordBox.width, long = bh * 12;
      sctx.setTransform(bandDir[0] * wide, bandDir[1] * wide, -bandDir[1] * long, bandDir[0] * long, cx, bh / 2);
      sctx.fillStyle = bandGrad;
      sctx.fillRect(-40, -0.5, 80, 1);
      sctx.setTransform(1, 0, 0, 1, 0, 0);
      sctx.globalCompositeOperation = 'source-over';
      ctx.globalCompositeOperation = 'lighter';
      ctx.globalAlpha = a;
      ctx.drawImage(sweepC, dx, dy, dw, dh);
      ctx.globalCompositeOperation = 'source-over';
    }
    ctx.globalAlpha = 1;
  }

  /** Stage 1: midnight outside the opening, its soft edges, the seam. */
  function drawIgnition() {
    var W = INTRO.W, H = INTRO.H, y0 = INTRO.horizonY * H;
    var r = Math.max(0, Math.min(1.02, springAt(P.SPATIAL_SCROLL, t - INTRO.apertureAt)));
    var feather = INTRO.featherBase + INTRO.featherGrow * H * r, reach = Math.max(y0, H - y0) + feather;
    var half = 1 + (reach - 1) * Math.min(1, r);
    ctx.globalCompositeOperation = 'source-over';
    ctx.globalAlpha = 1;
    if (half < reach) {
      var top = y0 - half, bottom = y0 + half;
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.fillStyle = INTRO.midnight;
      if (top - feather > 0) ctx.fillRect(0, 0, W, top - feather);
      if (bottom + feather < H) ctx.fillRect(0, bottom + feather, W, H - bottom - feather);
      ctx.fillStyle = edgeTop;
      ctx.setTransform(1, 0, 0, feather, 0, top - feather);
      ctx.fillRect(0, 0, W, 1);
      ctx.setTransform(1, 0, 0, -feather, 0, bottom + feather);
      ctx.fillRect(0, 0, W, 1);
      ctx.setTransform(1, 0, 0, 1, 0, 0);
    }
    var glow = INTRO.seamPeak * smooth(INTRO.seamIn[0], INTRO.seamIn[1], t) * (1 - smooth(INTRO.seamOut[0], INTRO.seamOut[1], t));
    if (glow > 0.004) {
      var hh = INTRO.seamCore + INTRO.seamGrow * Math.min(1, r);
      ctx.globalCompositeOperation = 'lighter';
      ctx.globalAlpha = glow;
      ctx.drawImage(seam, 0, y0 - hh, W, 2 * hh);
      ctx.globalAlpha = 1;
      ctx.globalCompositeOperation = 'source-over';
    }
  }

  /** The soft ring that spreads over the sky as the light crosses the word. */
  function drawRipple() {
    var p = (t - INTRO.ripple[0]) / (INTRO.ripple[1] - INTRO.ripple[0]);
    if (p <= 0 || p >= 1) return;
    var R = easeOut(p) * INTRO.rippleReach * INTRO.W;
    if (R < 2) return;
    ctx.globalCompositeOperation = 'lighter';
    ctx.globalAlpha = INTRO.ripplePeak * (1 - p);
    ctx.setTransform(R, 0, 0, R, INTRO.W / 2, INTRO.H / 2);
    ctx.fillStyle = ringGrad;
    ctx.fillRect(-1, -1, 2, 2);
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = 'source-over';
  }

  function draw(now) {
    frame = 0;
    if (!running) return;
    if (!start) start = now;
    t = (now - start) / 1000;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, INTRO.W, INTRO.H);   // the .hz layers (the Horizon) show through
    if (t < handAt) {
      if (!still) {
        drawIgnition();
        drawRipple();
      }
      drawWord(t, 1);
    } else {
      if (!handed) handoff();
      drawWord(frozen, 1);
    }
    if (t >= handAt + INTRO.wordOut) { finish(); return; }
    frame = requestAnimationFrame(draw);
  }

  /** Stage 3: the lock comes in under the startup with its identical Horizon; the startup goes clear but the word. */
  function handoff() {
    handed = true;
    if (frozen > handAt) frozen = handAt;
    // The lock first (its Horizon and veil are the same pixels), then this layer's sky goes: nothing shows through.
    var d = done;
    done = null;
    if (d) d(true);
    hzDay.style.display = hzNight.style.display = veil.style.display = 'none';
    layer.className += ' handoff';
  }

  /** The glass word, once Inter is there (baked once: it must not catch the fallback font). */
  function buildWord() {
    clearTimeout(wordTimer);
    if (!running || sharp) return;
    wordBox = makeWord(dark);
  }

  function finish() {
    running = false;
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    if (!handed) handoff();
    layer.style.visibility = 'hidden';
    layer.innerHTML = '';
    layer.className = layer.className.replace(/\s*handoff\b/g, '');
    clearTimeout(wordTimer);
    canvas = ctx = sharp = blur = cover = sweepC = sctx = seam = null;
    prisms = [];
  }

  return {
    INTRO: INTRO,
    chime: chord,
    knock: knock,
    showing: function () { return running && !handed; },

    /**
     * Plays it in `node`; then(shown) runs at the hand-off (show the lock then): shown is false when there was no
     * startup at all (Startup animation off), so the lock just appears.
     */
    play: function (node, then) {
      layer = node;
      if (!Prefs.intro()) {
        node.style.visibility = 'hidden';
        node.style.opacity = '0';
        if (then) then(false);
        return;
      }
      done = then;
      // The home screen's background (art, aerial, blur) first paints in about a second of raster work: while this
      // and the lock cover it, it waits (lock.css), and the lock lets it paint once its cards have settled.
      if ((' ' + document.body.className + ' ').indexOf(' covered ') < 0) document.body.className += ' covered';
      var lite = (' ' + document.documentElement.className + ' ').indexOf(' lite ') >= 0;
      still = lite || Motion.instant;
      layer.innerHTML = '';
      layer.className = layer.className.replace(/\s*handoff\b/g, '');
      layer.style.opacity = '1';
      layer.style.visibility = 'visible';
      hzDay = document.createElement('div');
      hzDay.className = 'hz hz-day';
      layer.appendChild(hzDay);
      hzNight = document.createElement('div');
      hzNight.className = 'hz hz-night';
      layer.appendChild(hzNight);
      veil = document.createElement('div');   // the lock's faint frost (lock.css), so the hand-off changes no pixel
      veil.className = 'veil';
      layer.appendChild(veil);
      canvas = make(INTRO.W, INTRO.H);
      layer.appendChild(canvas);
      ctx = canvas.getContext('2d');
      dark = night();
      sharp = null;
      if (!still) {
        seam = makeSeam();
        makeGradients();
      }
      handAt = still ? INTRO.stillHold : INTRO.handoff;
      frozen = handAt;
      handed = false;
      running = true;
      start = 0;
      t = 0;
      // The word is baked before the first frame (still midnight) when Inter is there; otherwise it waits for it, at
      // most 0.3 s, so the fallback font is never baked in.
      var face = INTRO.weight + ' ' + INTRO.font + 'px Inter', ready = still;
      try { ready = ready || !document.fonts || document.fonts.check(face); } catch (e) { ready = true; }
      if (ready) buildWord();
      else {
        wordTimer = setTimeout(buildWord, 300);
        try { document.fonts.load(face).then(buildWord, buildWord); } catch (e) {}
      }
      if (!lite) chord();
      frame = requestAnimationFrame(draw);
    },

    /** OK or Back: straight to the hand-off. Every other key waits. */
    key: function (k) {
      if (!running || handed) return;
      if (k === TV.keys.ok || k === TV.keys.back || k === 27 || k === 8 || k === 13) {
        frozen = t;
        handAt = t;
      }
    },

    /** Off at once (no startup wanted): the layer goes without a frame drawn. */
    skip: function (node) {
      node.style.visibility = 'hidden';
      node.style.opacity = '0';
    }
  };
})();
