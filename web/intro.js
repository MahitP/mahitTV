/*
 * The "Anamorphic Horizon & Optical Liquid Glass Ignition" startup (Intro.java on Android), 3.10 s, in four acts:
 *  I   (0.00–0.75 s) from a calm cinema slate, an anamorphic flare ribbon ignites on the horizon line (0.46·H) and the
 *      seam opens like an aperture onto the Frosted Horizon (day or night), which breathes under slow drift waves;
 *  II  (0.45–1.85 s) "mahitTV" condenses glyph by glyph (22 ms apart) out of depth and defocus into Liquid Glass: the
 *      refractive frosted body, a slate elevation shadow with the inner top bevel, the 1.5 px specular rim;
 *  III (1.15–2.35 s) a −28° volumetric beam crosses the screen, splitting the rim into cyan, white and amber, and at
 *      1.45 s a gossamer glass ring spreads from the word over the sky;
 *  IV  (2.35–3.10 s) the wordmark exhales while "Who's Watching?" springs in over the same, uninterrupted Horizon.
 * Three layers of sound go with it (Web Audio): a C2 + G2 sub swell, the Cmaj9 glass bloom in a 2.2 s room, and a
 * shimmer that pans left to right with the beam. OK or Back jumps to the hand-off; I (home.js) or Settings → Preview
 * Startup Animation plays it again. Reduce motion or an old TV (lite): the Horizon and the still wordmark, then the
 * hand-off at once. Startup animation off: no startup at power-on (the lock straight away).
 * Everything is built in play(); a frame only sets transforms, alphas and composite modes (gradients are made once and
 * placed with setTransform).
 */
var Intro = (function () {
  'use strict';

  /** Every parameter in one place (times in s, sizes in px on the 1920×1080 stage). */
  var INTRO = {
    W: 1920, H: 1080,
    midnight: '#070B12',
    // Act I: the anamorphic ignition.
    horizonY: 0.46,                // the ribbon's line, of the height
    ribbonSpan: 0.85, ribbonThin: 2, ribbonThick: 14, ribbonIgnite: 0.20, ribbonOut: [0.30, 0.75],
    ribbonCore: [255, 247, 237], bloomWarm: [245, 235, 214], bloomCool: [125, 211, 252],
    bloomFrom: 22, bloomTo: 160, bloomPeak: 0.8,
    apertureAt: 0.20, aperture: [0.55, 0.84],
    featherBase: 8, featherGrow: 0.10,
    drift: [0.20, 0.75, 1.85, 2.35], driftLight: 0.07,   // in, in, out, out; the waves' peak light
    // Act II: the Liquid Glass wordmark.
    word: 'mahitTV', font: 124, weight: 700, tracking: -0.03,
    wordAt: 0.45, stagger: 0.022, glyph: [0.48, 0.86],   // each glyph's spring
    glyphFrom: 1.14, glyphRise: 14,
    box: [1200, 300],                                     // the word's canvas
    refract: 1.15,                                        // the sky through the letters, magnified
    bodyDay: 0.24, bodyNight: 0.16,                       // white frost
    shadow: 'rgba(15, 23, 42, 0.22)', shadowY: 18, shadowBlur: 44,
    bevel: 0.82, rim: 1.5, rimLo: 0.25,
    // Act III: the caustic beam and the glass ring.
    sweep: [1.15, 1.75], sweepAngle: -28, sweepWidth: 300, sweepLift: 0.26, beamWidth: 0.09, beamLight: 0.07,
    prism: ['#38BDF8', '#FFFFFF', '#FDE68A'], prismPx: 1.8,
    ripple: [1.45, 2.35], rippleFrom: 80, rippleTo: 980, ripplePeak: 0.28,
    // Act IV: the hand-off.
    handoff: 2.35, wordOut: 0.36, wordTo: 0.95, exhaleBlur: 0.5,   // 14 px of the 28 px defocus
    stillHold: 0.7,
    // The sound (Click.java's, in Web Audio).
    sound: {
      sub: [[65.41, 0.30], [98.00, 0.22]], subAt: 0.05, subAttack: 0.26, subRelease: [1.95, 2.60],
      chord: [261.63, 329.63, 392.00, 493.88, 587.33], chordLevel: [0.11, 0.10, 0.10, 0.085, 0.075],
      chordAt: 0.48, roll: 0.045, bloom: 0.035, tau: 1.1, cents: 1.8, fm: 0.12,
      shimmer: [659.25, 783.99, 987.77, 1174.66], shimmerAt: [1.15, 1.33, 1.51, 1.69], shimmerLevel: 0.055,
      reverb: 2.2, wet: 0.45, fadeAt: 2.90, length: 3.2, master: 0.5
    }
  };

  // Apple's springs (response, damping), evaluated in closed form from rest: the same curve as Motion.Spring, at any time.
  function springAt(pre, t) {
    if (t <= 0) return 0;
    var w = 2 * Math.PI / pre[0], z = pre[1];
    if (z >= 1) return 1 - Math.exp(-w * t) * (1 + w * t);
    var wd = w * Math.sqrt(1 - z * z);
    return 1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + z * w / wd * Math.sin(wd * t));
  }
  function smooth(a, b, t) { var x = Math.max(0, Math.min(1, (t - a) / (b - a))); return x * x * (3 - 2 * x); }
  function easeOut(x) { x = Math.max(0, Math.min(1, x)); return 1 - (1 - x) * (1 - x) * (1 - x); }
  function clamp(x) { return Math.max(0, Math.min(1, x)); }

  var layer = null, canvas = null, ctx = null, hzDay = null, hzNight = null, veil = null, dark = false;
  var sharp = null, blur = null, shade = null, cover = null, prisms = [], sweepC = null, sctx = null;
  var core = null, bloom = null, edgeTop = null, bandGrad = null, beamGrad = null, waves = [];
  var running = false, still = false, frame = 0, start = 0, t = 0, handAt = 0, frozen = 0, handed = false;
  var done = null, audio = null, room = null, wordTimer = 0, wordBox = null;
  var bandDir = [Math.cos(INTRO.sweepAngle * Math.PI / 180), Math.sin(INTRO.sweepAngle * Math.PI / 180)];

  function make(w, h) {
    var c = document.createElement('canvas');
    c.width = w;
    c.height = h;
    return c;
  }
  function night() { return (' ' + document.body.className + ' ').indexOf(' night ') >= 0; }
  function rgba(c, a) { return 'rgba(' + c[0] + ', ' + c[1] + ', ' + c[2] + ', ' + a + ')'; }

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
      var f = fields[i], rg = g.createRadialGradient(0, 0, 0, 0, 0, 1);
      rg.addColorStop(0, rgba(f[4], 1));
      rg.addColorStop(f[3], rgba(f[4], 0));
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
      var ch = INTRO.word.charAt(i), adv = g.measureText(ch).width;
      spots.push([ch, x, adv]);
      x += adv + track;
    }
    return {spots: spots, width: x - track};
  }

  function font(g) {
    g.font = INTRO.weight + ' ' + INTRO.font + 'px Inter, "SF Pro Display", "Helvetica Neue", Arial, sans-serif';
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

  /** A soft copy of `src`: down to 1/f and back, twice (about f·3 px of blur). */
  function soften(src, f) {
    var bw = src.width, bh = src.height, out = make(bw, bh);
    var small = make(Math.ceil(bw / (2 * f)), Math.ceil(bh / (2 * f))), mid = make(Math.ceil(bw / f), Math.ceil(bh / f));
    var mg = mid.getContext('2d'), lg = small.getContext('2d');
    mg.drawImage(src, 0, 0, mid.width, mid.height);
    lg.drawImage(mid, 0, 0, small.width, small.height);
    mg.clearRect(0, 0, mid.width, mid.height);
    mg.drawImage(small, 0, 0, mid.width, mid.height);
    out.getContext('2d').drawImage(mid, 0, 0, bw, bh);
    return out;
  }

  /** The Liquid Glass word and everything the sweep needs, at the word canvas's size. */
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

    // Layer 1, the refractive body: the sky through the letters as a thick lens shows it (magnified and upside down:
    // the deeper coastal blue at the top of the strokes), frosted white (0.24 by day, 0.16 at night).
    var sky = makeHorizon(dark), body = make(bw, bh), bg = body.getContext('2d');
    var sw = bw / INTRO.refract, sh = bh / INTRO.refract * 2.2;
    var sx = INTRO.W / 2 - sw / 2, sy = INTRO.H / 2 - sh / 2;
    bg.setTransform(1, 0, 0, -1, 0, bh);
    bg.drawImage(sky, sx / 2, sy / 2, sw / 2, sh / 2, 0, 0, bw, bh);
    bg.setTransform(1, 0, 0, 1, 0, 0);
    // By day the lens gathers the Horizon's coastal blue (#7E9BB5), so clear glass still reads on a pale sky.
    if (!dark) {
      bg.fillStyle = 'rgba(126, 155, 181, 0.30)';
      bg.fillRect(0, 0, bw, bh);
    }
    bg.fillStyle = 'rgba(255, 255, 255, ' + (dark ? INTRO.bodyNight : INTRO.bodyDay) + ')';
    bg.fillRect(0, 0, bw, bh);
    // A slightly darker far side inside the glass (its depth), lighter toward the top.
    var depth = bg.createLinearGradient(0, y0 - cap, 0, y0);
    depth.addColorStop(0, 'rgba(255, 255, 255, 0.10)');
    depth.addColorStop(1, 'rgba(15, 23, 42, ' + (dark ? 0.10 : 0.06) + ')');
    bg.fillStyle = depth;
    bg.fillRect(0, 0, bw, bh);
    // Layer 2's inner optical shadow: the glass's thickness darkening its lower inner edges.
    var hole = make(bw, bh), og = hole.getContext('2d');
    og.fillRect(0, 0, bw, bh);
    og.globalCompositeOperation = 'destination-out';
    og.drawImage(cover, 0, 0);
    bg.shadowColor = dark ? 'rgba(0, 0, 0, 0.55)' : 'rgba(15, 23, 42, 0.55)';
    bg.shadowBlur = 12;
    bg.shadowOffsetX = 4000;
    bg.shadowOffsetY = -4;
    bg.drawImage(hole, -4000, 0);
    bg.shadowColor = 'rgba(0, 0, 0, 0)';
    bg.shadowBlur = bg.shadowOffsetX = bg.shadowOffsetY = 0;
    bg.globalCompositeOperation = 'destination-in';
    bg.drawImage(cover, 0, 0);
    bg.globalCompositeOperation = 'source-over';

    // The sharp glass: the body, then Layer 2's inner top bevel and Layer 3's 1.5 px specular rim.
    sharp = make(bw, bh);
    sharp.getContext('2d').drawImage(body, 0, 0);
    edge(sharp, '#FFF', INTRO.bevel, INTRO.rim, INTRO.rim);         // the bevel catching the light (top left)
    edge(sharp, '#FFF', INTRO.rimLo, -INTRO.rim, -INTRO.rim);       // its faint echo on the far side
    edge(sharp, '#FFF', INTRO.bevel * 0.4, 0, INTRO.rim * 2);       // the top lip

    // Layer 2's elevation shadow (0 18 px 44 px, slate 0.22), alone: the letters drawn off-canvas cast it in place.
    shade = make(bw, bh);
    var hg = shade.getContext('2d');
    font(hg);
    hg.shadowColor = INTRO.shadow;
    hg.shadowBlur = INTRO.shadowBlur;
    hg.shadowOffsetX = 4000;
    hg.shadowOffsetY = INTRO.shadowY;
    hg.fillStyle = '#000';
    eachGlyph(hg, word, x0 - 4000, y0, function (ch, x, y) { hg.fillText(ch, x, y); });

    // The optical defocus (about 28 px).
    blur = soften(sharp, 9);

    // The prism: the rim in each of the three colours, drawn apart along the beam while it passes.
    prisms = [];
    for (var p = 0; p < INTRO.prism.length; p++) {
      var pc = make(bw, bh);
      edge(pc, INTRO.prism[p], 1, INTRO.rim, INTRO.rim);
      edge(pc, INTRO.prism[p], 0.6, -INTRO.rim, -INTRO.rim);
      prisms.push(pc);
    }
    sweepC = make(bw, bh);
    sctx = sweepC.getContext('2d');
    bandGrad = sctx.createLinearGradient(-0.5, 0, 0.5, 0);
    bandGrad.addColorStop(0, 'rgba(255, 255, 255, 0)');
    bandGrad.addColorStop(0.25, 'rgba(255, 255, 255, 0.32)');
    bandGrad.addColorStop(0.5, 'rgba(255, 255, 255, 1)');
    bandGrad.addColorStop(0.75, 'rgba(255, 255, 255, 0.32)');
    bandGrad.addColorStop(1, 'rgba(255, 255, 255, 0)');
    // The glyph cells (box px): each glyph's slice of the canvases, and its pivot.
    var cells = [];
    for (var i = 0; i < word.spots.length; i++) {
      var left = i === 0 ? 0 : x0 + word.spots[i][1], right = i === word.spots.length - 1 ? bw : x0 + word.spots[i + 1][1];
      cells.push([left, right, x0 + word.spots[i][1] + word.spots[i][2] / 2]);
    }
    return {x0: x0, width: word.width, cells: cells};
  }

  /** The ribbon: a crisp pearl core, and its soft bloom, warm in the middle and cyan toward the ends. */
  function makeRibbon() {
    var w = 512, i, x, y, u, v, a;
    core = make(w, 16);
    var cg = core.getContext('2d'), ci = cg.createImageData(w, 16), cd = ci.data;
    for (y = 0; y < 16; y++) {
      v = ((y + 0.5) / 16 * 2 - 1) / 0.42;
      for (x = 0; x < w; x++) {
        u = Math.abs((x + 0.5) / w - 0.5) * 2;
        a = Math.exp(-v * v) * (0.35 + 0.65 * Math.exp(-u * 2.2)) * (1 - smooth(0.7, 1, u));
        i = (y * w + x) * 4;
        cd[i] = INTRO.ribbonCore[0]; cd[i + 1] = INTRO.ribbonCore[1]; cd[i + 2] = INTRO.ribbonCore[2];
        cd[i + 3] = Math.round(255 * Math.min(1, a));
      }
    }
    cg.putImageData(ci, 0, 0);
    bloom = make(w, 64);
    var bg = bloom.getContext('2d'), bi = bg.createImageData(w, 64), bd = bi.data, warm = INTRO.bloomWarm, cool = INTRO.bloomCool;
    for (y = 0; y < 64; y++) {
      v = ((y + 0.5) / 64 * 2 - 1) / 0.45;
      for (x = 0; x < w; x++) {
        u = Math.abs((x + 0.5) / w - 0.5) * 2;
        a = 0.6 * Math.exp(-v * v) * Math.exp(-u * 1.3) * (1 - smooth(0.6, 1, u));
        var f = smooth(0.05, 0.8, u);
        i = (y * w + x) * 4;
        bd[i] = Math.round(warm[0] + (cool[0] - warm[0]) * f);
        bd[i + 1] = Math.round(warm[1] + (cool[1] - warm[1]) * f);
        bd[i + 2] = Math.round(warm[2] + (cool[2] - warm[2]) * f);
        bd[i + 3] = Math.round(255 * Math.min(1, a));
      }
    }
    bg.putImageData(bi, 0, 0);
  }

  function makeGradients() {
    // The opening's soft edge: slate to clear, over a unit height (placed by setTransform).
    edgeTop = ctx.createLinearGradient(0, 0, 0, 1);
    edgeTop.addColorStop(0, INTRO.midnight);
    edgeTop.addColorStop(1, 'rgba(7, 11, 18, 0)');
    // The volumetric beam over the sky: a soft stripe across x (unit wide).
    beamGrad = ctx.createLinearGradient(-0.5, 0, 0.5, 0);
    beamGrad.addColorStop(0, 'rgba(255, 255, 255, 0)');
    beamGrad.addColorStop(0.3, 'rgba(255, 255, 255, 0.18)');
    beamGrad.addColorStop(0.5, 'rgba(255, 255, 255, 1)');
    beamGrad.addColorStop(0.7, 'rgba(255, 255, 255, 0.18)');
    beamGrad.addColorStop(1, 'rgba(255, 255, 255, 0)');
    // The atmospheric drift: three slow, wide waves of light (cream, mist, coastal), each a unit radial.
    var tones = dark ? [[60, 78, 110], [40, 56, 84], [30, 64, 96]] : [[245, 235, 214], [220, 230, 239], [125, 211, 252]];
    waves = [];
    for (var i = 0; i < 3; i++) {
      var g = ctx.createRadialGradient(0, 0, 0, 0, 0, 1);
      g.addColorStop(0, rgba(tones[i], 1));
      g.addColorStop(1, rgba(tones[i], 0));
      waves.push(g);
    }
  }

  // MARK: - The sound (Web Audio; silent where there's none)

  function context() {
    if (!audio) {
      var A = window.AudioContext || window.webkitAudioContext;
      if (!A) return null;
      try { audio = new A(); } catch (e) { audio = null; }
    }
    // A browser holds audio until a key: I (a key) wakes it.
    try { if (audio && audio.state === 'suspended' && audio.resume) audio.resume(); } catch (e) {}
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

  /** The room: stereo noise ringing down 60 dB in `rt` s after an 18 ms pre-delay (made once per context). */
  function reverb(ac, rt) {
    if (room && room.ac === ac) return room.buffer;
    var rate = ac.sampleRate, n = Math.round(rate * (rt + 0.1)), pre = Math.round(0.018 * rate);
    var b = ac.createBuffer(2, n, rate);
    for (var ch = 0; ch < 2; ch++) {
      var d = b.getChannelData(ch);
      for (var i = pre; i < n; i++) {
        var s = (i - pre) / rate;
        d[i] = (Math.random() * 2 - 1) * Math.exp(-6.9 * s / rt) * Math.min(1, s / 0.01);
      }
    }
    room = {ac: ac, buffer: b};
    return b;
  }

  /** `node` into the left (0) or right (1) input of `merger` at `gain`. */
  function side(ac, node, merger, ch, gain) {
    var g = ac.createGain();
    g.gain.value = gain;
    node.connect(g);
    g.connect(merger, 0, ch);
  }

  /** A glass voice: 88% sine and 12% a 2:1 FM bell whose index dies away; enveloped, into `out`. */
  function bell(ac, out, freq, at, level, s, stop) {
    var env = ac.createGain(), pure = ac.createOscillator(), fm = ac.createOscillator(), mod = ac.createOscillator();
    var pureG = ac.createGain(), fmG = ac.createGain(), depth = ac.createGain();
    pure.frequency.value = fm.frequency.value = freq;
    mod.frequency.value = freq * 2;
    depth.gain.setValueAtTime(freq * 2.6, at);
    depth.gain.setTargetAtTime(freq * 0.4, at, 0.35);
    mod.connect(depth);
    depth.connect(fm.frequency);
    pureG.gain.value = 1 - s.fm;
    fmG.gain.value = s.fm;
    pure.connect(pureG);
    fm.connect(fmG);
    pureG.connect(env);
    fmG.connect(env);
    env.gain.setValueAtTime(0, at);
    env.gain.linearRampToValueAtTime(level, at + s.bloom);
    env.gain.setTargetAtTime(0, at + s.bloom, s.tau);
    env.connect(out);
    pure.start(at); fm.start(at); mod.start(at);
    pure.stop(stop); fm.stop(stop); mod.stop(stop);
  }

  /** The three layers, timed to the acts from now. */
  function chord() {
    var ac = context(), s = INTRO.sound;
    if (!ac) return;
    try {
      var t0 = ac.currentTime + 0.02, end = t0 + s.length, i;
      var master = ac.createGain();
      master.gain.setValueAtTime(s.master, t0);
      master.gain.setValueAtTime(s.master, t0 + s.fadeAt);
      master.gain.linearRampToValueAtTime(0, end);
      master.connect(ac.destination);
      var stereo = ac.createChannelMerger(2), send = ac.createChannelMerger(2);
      stereo.connect(master);
      var conv = ac.createConvolver(), wet = ac.createGain();
      conv.buffer = reverb(ac, s.reverb);
      wet.gain.value = s.wet;
      send.connect(conv);
      conv.connect(wet);
      wet.connect(master);
      // Layer 1: the warm sub swell (dry), low-passed so it never muddies.
      var low = ac.createBiquadFilter();
      low.type = 'lowpass';
      low.frequency.value = 240;
      low.connect(master);
      for (i = 0; i < s.sub.length; i++) {
        var f = s.sub[i][0], g = ac.createGain(), o = ac.createOscillator(), o3 = ac.createOscillator(), g3 = ac.createGain();
        o.frequency.value = f;
        o3.frequency.value = f * 3;
        g3.gain.value = 0.08;
        o.connect(g);
        o3.connect(g3);
        g3.connect(g);
        g.gain.setValueAtTime(0, t0 + s.subAt);
        g.gain.linearRampToValueAtTime(s.sub[i][1], t0 + s.subAt + s.subAttack);
        g.gain.setValueAtTime(s.sub[i][1], t0 + s.subRelease[0]);
        g.gain.linearRampToValueAtTime(0, t0 + s.subRelease[1]);
        g.connect(low);
        o.start(t0 + s.subAt); o3.start(t0 + s.subAt);
        o.stop(t0 + s.subRelease[1] + 0.05); o3.stop(t0 + s.subRelease[1] + 0.05);
      }
      // Layer 2: Cmaj9, rolled; each note a voice detuned down on the left and up on the right.
      var down = Math.pow(2, -s.cents / 1200), up = Math.pow(2, s.cents / 1200);
      for (i = 0; i < s.chord.length; i++) {
        var at = t0 + s.chordAt + i * s.roll;
        var l = ac.createGain(), r = ac.createGain();
        bell(ac, l, s.chord[i] * down, at, s.chordLevel[i], s, end);
        bell(ac, r, s.chord[i] * up, at, s.chordLevel[i], s, end);
        side(ac, l, stereo, 0, 1); side(ac, r, stereo, 1, 1);
        side(ac, l, send, 0, 1); side(ac, r, send, 1, 1);
      }
      // Layer 3: the shimmer, left to right with the beam, 18 dB under the chord.
      for (i = 0; i < s.shimmer.length; i++) {
        var pan = -0.8 + 1.6 * i / (s.shimmer.length - 1), angle = (pan + 1) * Math.PI / 4, p = ac.createGain();
        voice(ac, p, s.shimmer[i], t0 + s.shimmerAt[i], 0.006, 0.30, s.shimmerLevel, end);
        voice(ac, p, s.shimmer[i] * 2, t0 + s.shimmerAt[i], 0.006, 0.30, s.shimmerLevel * 0.15, end);
        side(ac, p, stereo, 0, Math.cos(angle)); side(ac, p, stereo, 1, Math.sin(angle));
        side(ac, p, send, 0, 1.3 * Math.cos(angle)); side(ac, p, send, 1, 1.3 * Math.sin(angle));
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

  /** Where the beam's centre is (stage x): −0.3·W → 1.3·W, slowest over the word, at its centre at 1.45 s. */
  function beamX(tw) {
    var u = clamp((tw - INTRO.sweep[0]) / (INTRO.sweep[1] - INTRO.sweep[0])) * 2 - 1;
    return INTRO.W * (0.5 + 0.8 * (0.35 * u + 0.65 * u * u * u));
  }

  /** The glass word at its state for time tw (frozen at a skip), on the stage. */
  function drawWord(tw) {
    if (!sharp) return;
    var bw = INTRO.box[0], bh = INTRO.box[1], cx = INTRO.W / 2, cy = INTRO.H / 2, bx = cx - bw / 2, by = cy - bh / 2;
    var zoom = 1, fade = 1, haze = 0;
    if (t >= handAt) {
      var out = easeOut((t - handAt) / INTRO.wordOut);
      zoom = 1 - (1 - INTRO.wordTo) * out;
      fade = 1 - out;
      haze = INTRO.exhaleBlur * out;
    }
    if (fade <= 0.002) return;
    var cells = wordBox.cells, n = cells.length, meanS = 0, meanR = 0, meanA = 0, i, g, s, rise, a, soft;
    ctx.globalCompositeOperation = 'source-over';
    // Layer 2's shadow, under the glyphs, on their mean transform.
    for (i = 0; i < n; i++) {
      g = still ? 1 : springAt(INTRO.glyph, tw - INTRO.wordAt - i * INTRO.stagger);
      meanS += (INTRO.glyphFrom + (1 - INTRO.glyphFrom) * g) / n;
      meanR += INTRO.glyphRise * (1 - g) / n;
      meanA += clamp(g) * clamp(g) / n;
    }
    var sz = meanS * zoom;
    ctx.globalAlpha = fade * meanA * (1 - haze);
    ctx.setTransform(sz, 0, 0, sz, cx - cx * sz, cy - cy * sz + meanR * zoom);
    ctx.drawImage(shade, bx, by);
    for (i = 0; i < n; i++) {
      g = still ? 1 : springAt(INTRO.glyph, tw - INTRO.wordAt - i * INTRO.stagger);
      if (g <= 0.001) continue;
      s = INTRO.glyphFrom + (1 - INTRO.glyphFrom) * g;
      rise = INTRO.glyphRise * (1 - g);
      a = clamp(g);
      soft = clamp(g * 1.6) * Math.max(1 - a, haze);
      var c = cells[i], m = bx + c[2], k = s * zoom;
      // Glyph i about its own centre, then the whole word about the stage's.
      ctx.setTransform(k, 0, 0, k, cx + (m - m * s - cx) * zoom, cy - cy * k + rise * zoom);
      if (soft > 0.004) {
        ctx.globalAlpha = fade * soft * 0.8;
        ctx.drawImage(blur, c[0], 0, c[1] - c[0], bh, bx + c[0], by, c[1] - c[0], bh);
      }
      ctx.globalAlpha = fade * a * a * (1 - haze);
      if (ctx.globalAlpha > 0.004) ctx.drawImage(sharp, c[0], 0, c[1] - c[0], bh, bx + c[0], by, c[1] - c[0], bh);
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    // Act III's caustic sweep: brighter glass and the prism fringe (cyan leading, amber trailing) inside the beam.
    var sp = still ? -1 : (tw - INTRO.sweep[0]) / (INTRO.sweep[1] - INTRO.sweep[0]);
    if (sp > 0 && sp < 1) {
      var o = INTRO.prismPx, ox = bandDir[0] * o, oy = bandDir[1] * o;
      sctx.setTransform(1, 0, 0, 1, 0, 0);
      sctx.globalCompositeOperation = 'source-over';
      sctx.globalAlpha = 1;
      sctx.clearRect(0, 0, bw, bh);
      sctx.globalAlpha = INTRO.sweepLift;
      sctx.drawImage(cover, 0, 0);
      sctx.globalAlpha = 1;
      sctx.globalCompositeOperation = 'lighter';
      for (var pass = 0; pass < 2; pass++) {   // twice: the fringe must glint on a bright sky too
        sctx.drawImage(prisms[0], ox, oy);
        sctx.drawImage(prisms[1], 0, 0);
        sctx.drawImage(prisms[2], -ox, -oy);
      }
      sctx.globalAlpha = 1;
      sctx.globalCompositeOperation = 'destination-in';
      var wide = INTRO.sweepWidth, long = bh * 12, at = beamX(tw) - bx;
      sctx.setTransform(bandDir[0] * wide, bandDir[1] * wide, -bandDir[1] * long, bandDir[0] * long, at, bh / 2);
      sctx.fillStyle = bandGrad;
      sctx.fillRect(-40, -0.5, 80, 1);
      sctx.setTransform(1, 0, 0, 1, 0, 0);
      sctx.globalCompositeOperation = 'source-over';
      ctx.globalCompositeOperation = 'lighter';
      ctx.globalAlpha = fade * meanA;
      ctx.setTransform(zoom, 0, 0, zoom, cx - cx * zoom, cy - cy * zoom);
      ctx.drawImage(sweepC, bx, by);
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.globalCompositeOperation = 'source-over';
    }
    ctx.globalAlpha = 1;
  }

  /** Act I: slate outside the opening, its soft edges, the ribbon and its bloom. */
  function drawIgnition() {
    var W = INTRO.W, H = INTRO.H, y0 = INTRO.horizonY * H;
    var r = Math.max(0, Math.min(1, springAt(INTRO.aperture, t - INTRO.apertureAt)));
    var feather = INTRO.featherBase + INTRO.featherGrow * H * r, reach = Math.max(y0, H - y0) + feather;
    var half = 1 + (reach - 1) * r;
    ctx.globalCompositeOperation = 'source-over';
    ctx.globalAlpha = 1;
    if (half < reach - 0.5) {
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
    var ignite = smooth(0, INTRO.ribbonIgnite, t), glow = ignite * (1 - smooth(INTRO.ribbonOut[0], INTRO.ribbonOut[1], t));
    if (glow > 0.004) {
      var span = INTRO.ribbonSpan * W * (0.35 + 0.65 * ignite);
      var bh = INTRO.bloomFrom + (INTRO.bloomTo - INTRO.bloomFrom) * r;
      var ch = (INTRO.ribbonThin + (INTRO.ribbonThick - INTRO.ribbonThin) * ignite) / 2 / 0.42;
      ctx.globalCompositeOperation = 'lighter';
      ctx.globalAlpha = INTRO.bloomPeak * glow;
      ctx.drawImage(bloom, W / 2 - span * 0.62, y0 - bh, span * 1.24, 2 * bh);
      ctx.globalAlpha = glow;
      ctx.drawImage(core, W / 2 - span / 2, y0 - ch, span, 2 * ch);
      ctx.globalAlpha = 1;
      ctx.globalCompositeOperation = 'source-over';
    }
  }

  /** The living sky: slow drift waves of light, then (Act III) the volumetric beam and the glass ring. */
  function drawAtmosphere() {
    var W = INTRO.W, H = INTRO.H, d = INTRO.drift;
    var env = smooth(d[0], d[1], t) * (1 - smooth(d[2], d[3], t));
    ctx.globalCompositeOperation = 'lighter';
    if (env > 0.002) {
      var spots = [[0.22 + 0.06 * Math.sin(t * 0.9), 0.62 + 0.03 * Math.cos(t * 0.7), 0.42, 0.20],
                   [0.60 + 0.08 * Math.sin(t * 0.6 + 1.3), 0.40 + 0.04 * Math.sin(t * 0.8), 0.50, 0.16],
                   [0.86 + 0.05 * Math.cos(t * 0.75), 0.80 + 0.03 * Math.sin(t * 1.1), 0.36, 0.22]];
      for (var i = 0; i < spots.length; i++) {
        var s = spots[i];
        ctx.globalAlpha = INTRO.driftLight * env * (0.75 + 0.25 * Math.sin(t * 1.3 + i * 2.1));
        ctx.setTransform(s[2] * W, 0, 0, s[3] * H, s[0] * W, s[1] * H);
        ctx.fillStyle = waves[i];
        ctx.fillRect(-1, -1, 2, 2);
      }
    }
    var beam = smooth(INTRO.sweep[0], INTRO.sweep[0] + 0.12, t) * (1 - smooth(INTRO.sweep[1] - 0.12, INTRO.sweep[1], t));
    if (beam > 0.002) {
      var wide = INTRO.beamWidth * W * 2.4, long = H * 4;
      ctx.globalAlpha = INTRO.beamLight * beam;
      ctx.setTransform(bandDir[0] * wide, bandDir[1] * wide, -bandDir[1] * long, bandDir[0] * long, beamX(t), H / 2);
      ctx.fillStyle = beamGrad;
      ctx.fillRect(-0.5, -0.5, 1, 1);
    }
    var p = (t - INTRO.ripple[0]) / (INTRO.ripple[1] - INTRO.ripple[0]);
    if (p > 0 && p < 1) {
      // Gossamer: a soft halo 14 px wide under a 1.5 px line of light.
      var R = INTRO.rippleFrom + (INTRO.rippleTo - INTRO.rippleFrom) * easeOut(p), a = INTRO.ripplePeak * (1 - p);
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.strokeStyle = '#FFF';
      ctx.beginPath();
      ctx.arc(W / 2, H / 2, R, 0, 2 * Math.PI);
      ctx.globalAlpha = a * 0.35;
      ctx.lineWidth = 14;
      ctx.stroke();
      ctx.globalAlpha = a;
      ctx.lineWidth = 1.5;
      ctx.stroke();
    }
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
        drawAtmosphere();
        drawIgnition();
      }
      drawWord(t);
    } else {
      if (!handed) handoff();
      drawWord(frozen);
    }
    if (t >= handAt + INTRO.wordOut) { finish(); return; }
    frame = requestAnimationFrame(draw);
  }

  /** Act IV: the lock comes in under the startup with its identical Horizon; the startup goes clear but the word. */
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
    canvas = ctx = sharp = blur = shade = cover = sweepC = sctx = null;
    prisms = [];
    waves = [];
  }

  return {
    INTRO: INTRO,
    chime: chord,
    knock: knock,
    showing: function () { return running && !handed; },

    /**
     * Plays it in `node`; then(shown) runs at the hand-off (show the lock then): shown is false when there was no
     * startup at all (Startup animation off), so the lock just appears. `preview` (I, or Settings → Preview Startup
     * Animation) plays it even with the startup off, and from the start if it's already playing.
     */
    play: function (node, then, preview) {
      if (frame) cancelAnimationFrame(frame);
      frame = 0;
      clearTimeout(wordTimer);
      running = false;
      layer = node;
      if (!preview && !Prefs.intro()) {
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
        if (!core) makeRibbon();
        makeGradients();
      }
      handAt = still ? INTRO.stillHold : INTRO.handoff;
      frozen = handAt;
      handed = false;
      running = true;
      start = 0;
      t = 0;
      // The word is baked before the first frame (still slate) when Inter is there; otherwise it waits for it, at
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
