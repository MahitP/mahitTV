/*
 * The golden mahitTV intro (MAHITTV-PROFILES-SPEC.md §2): the Frosted Horizon, the wordmark in brushed gold with one
 * specular sweep, gold sparks rising with depth blur, and a quiet C–E–G chime. It ends by dissolving into the lock.
 * OK or Back skips it. Startup animation off, reduce motion or an old TV (lite): the wordmark alone, a quick fade.
 */
var Intro = (function () {
  'use strict';

  /** Every parameter in one place (times in ms, sizes in px on the 1920×1080 stage). */
  var INTRO = {
    duration: 2400,      // to the end of the dissolve
    fadeIn: 520,         // the wordmark comes in, settling from 0.96
    sweepAt: 620,        // the specular band crosses the word once
    sweepFor: 900,
    sweepWidth: 0.22,    // of the word's width
    endAt: 1900,         // the dissolve into the lock starts
    fade: 500,
    skipFade: 240,       // OK or Back
    staticHold: 700,     // no animation: the wordmark alone, then a quick fade
    staticFade: 260,
    font: 150,
    weight: 700,
    word: 'mahitTV',
    gold: ['#D4AF37', '#F5D061', '#FFF3B0', '#D4AF37'],         // over the night Horizon
    goldDay: ['#9C6F17', '#C9962E', '#E0B54E', '#9C6F17'],      // deeper, over the bright day Horizon
    sweep: [0.85, 0.5],          // the specular band's strength, by night and by day
    shadowDay: 'rgba(70, 50, 12, 0.32)',                        // under the word by day
    brush: 220,          // faint horizontal strokes in the gold
    sparks: 48,
    rise: [70, 190],     // px/s, far to near
    sway: 14,            // px
    size: [3, 9],        // far to near
    bokeh: 0.35,         // the share of sparks out of focus (soft, larger, dimmer)
    chime: {notes: [523.25, 659.25, 783.99], gap: 0.11, length: 1.2, volume: 0.06}
  };

  var layer = null, canvas = null, ctx = null, word = null, wctx = null, goldTex = null, sharp = null, soft = null, shade = null;
  var sparks = [], start = 0, endStart = 0, endFor = 0, done = null, running = false, still = false, frame = 0;

  function make(w, h) {
    var c = document.createElement('canvas');
    c.width = w;
    c.height = h;
    return c;
  }

  /** The brushed gold: the four-stop gradient with faint horizontal strokes, made once. */
  function makeGold(w, h, gold) {
    var c = make(w, h), g = c.getContext('2d'), grad = g.createLinearGradient(0, 0, w, h * 0.6);
    for (var i = 0; i < gold.length; i++) grad.addColorStop(i / (gold.length - 1), gold[i]);
    g.fillStyle = grad;
    g.fillRect(0, 0, w, h);
    for (var s = 0; s < INTRO.brush; s++) {
      g.globalAlpha = 0.04 + Math.random() * 0.07;
      g.fillStyle = Math.random() < 0.5 ? '#FFFFFF' : '#7A5A12';
      g.fillRect(Math.random() * w * 0.4 - w * 0.1, Math.random() * h, w * (0.3 + Math.random() * 0.8), 1);
    }
    g.globalAlpha = 1;
    return c;
  }

  /** The word's soft shadow by day, made once: only the shadow of text drawn off the canvas. */
  function makeShade(w, h) {
    var c = make(w, h), g = c.getContext('2d');
    g.font = INTRO.weight + ' ' + INTRO.font + 'px Inter, "Helvetica Neue", Arial, sans-serif';
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.shadowColor = INTRO.shadowDay;
    g.shadowBlur = 28;
    g.shadowOffsetX = 4000;
    g.shadowOffsetY = 10;
    g.fillText(INTRO.word, w / 2 - 4000, h / 2);
    return c;
  }

  /** A spark: a bright core (in focus) or a soft disc (out of focus); amber by day, so it shows on the bright sky. */
  function makeSpark(blurred, day) {
    var c = make(64, 64), g = c.getContext('2d'), grad = g.createRadialGradient(32, 32, 0, 32, 32, 32);
    if (day && blurred) {
      grad.addColorStop(0, 'rgba(214, 160, 52, 0.5)');
      grad.addColorStop(0.55, 'rgba(196, 140, 34, 0.28)');
      grad.addColorStop(1, 'rgba(176, 122, 22, 0)');
    } else if (day) {
      grad.addColorStop(0, 'rgba(255, 244, 214, 1)');
      grad.addColorStop(0.2, 'rgba(222, 165, 48, 0.95)');
      grad.addColorStop(0.5, 'rgba(176, 122, 22, 0.4)');
      grad.addColorStop(1, 'rgba(176, 122, 22, 0)');
    } else if (blurred) {
      grad.addColorStop(0, 'rgba(255, 228, 140, 0.55)');
      grad.addColorStop(0.55, 'rgba(245, 208, 97, 0.32)');
      grad.addColorStop(1, 'rgba(212, 175, 55, 0)');
    } else {
      grad.addColorStop(0, 'rgba(255, 250, 225, 1)');
      grad.addColorStop(0.18, 'rgba(255, 228, 140, 0.95)');
      grad.addColorStop(0.45, 'rgba(212, 175, 55, 0.35)');
      grad.addColorStop(1, 'rgba(212, 175, 55, 0)');
    }
    g.fillStyle = grad;
    g.fillRect(0, 0, 64, 64);
    return c;
  }

  function spark(fresh) {
    var z = Math.random(), out = Math.random() < INTRO.bokeh;
    return {
      x: 160 + Math.random() * 1600,
      y: fresh ? 300 + Math.random() * 900 : 1120,
      z: z,
      out: out,
      size: (INTRO.size[0] + (INTRO.size[1] - INTRO.size[0]) * z) * (out ? 3.2 : 1),
      v: INTRO.rise[0] + (INTRO.rise[1] - INTRO.rise[0]) * z,
      phase: Math.random() * 6.28,
      a: (out ? 0.35 : 0.55 + 0.45 * z) * (0.6 + Math.random() * 0.4)
    };
  }

  /** The chime, synthesized once: three soft bell tones, rising, quiet. Silent where Web Audio isn't. */
  var audio = null;
  function context() {
    if (audio) return audio;
    var A = window.AudioContext || window.webkitAudioContext;
    if (!A) return null;
    try { audio = new A(); } catch (e) { audio = null; }
    return audio;
  }

  function tone(ac, at, freq, length, volume, type) {
    var o = ac.createOscillator(), g = ac.createGain();
    o.type = type || 'sine';
    o.frequency.value = freq;
    g.gain.setValueAtTime(0.0001, at);
    g.gain.linearRampToValueAtTime(volume, at + 0.012);
    g.gain.exponentialRampToValueAtTime(0.0001, at + length);
    o.connect(g);
    g.connect(ac.destination);
    o.start(at);
    o.stop(at + length + 0.05);
  }

  function chime() {
    var ac = context(), c = INTRO.chime;
    if (!ac) return;
    try {
      var t = ac.currentTime + 0.05;
      for (var i = 0; i < c.notes.length; i++) {
        tone(ac, t + i * c.gap, c.notes[i], c.length - i * c.gap, c.volume, 'sine');
        tone(ac, t + i * c.gap, c.notes[i] * 2, (c.length - i * c.gap) * 0.5, c.volume * 0.18, 'triangle');   // the bell's shimmer
      }
    } catch (e) {}
  }

  /** A short low knock (a wrong PIN). */
  function knock() {
    var ac = context();
    if (!ac) return;
    try {
      var t = ac.currentTime + 0.01;
      tone(ac, t, 140, 0.16, 0.08, 'sine');
      tone(ac, t + 0.07, 104, 0.18, 0.06, 'sine');
    } catch (e) {}
  }

  function ease(t) { t = Math.max(0, Math.min(1, t)); return 1 - Math.pow(1 - t, 3); }

  /** The wordmark into its own canvas: white text, the gold in it, then the specular band over the gold only. */
  function drawWord(sweep) {
    var w = word.width, h = word.height;
    wctx.globalCompositeOperation = 'source-over';
    wctx.clearRect(0, 0, w, h);
    wctx.font = INTRO.weight + ' ' + INTRO.font + 'px Inter, "Helvetica Neue", Arial, sans-serif';
    wctx.textAlign = 'center';
    wctx.textBaseline = 'middle';
    wctx.fillStyle = '#FFF';
    wctx.fillText(INTRO.word, w / 2, h / 2);
    wctx.globalCompositeOperation = 'source-in';
    wctx.drawImage(goldTex, 0, 0);
    if (sweep > 0 && sweep < 1) {
      var band = w * INTRO.sweepWidth, x = -band + (w + 2 * band) * sweep;
      var grad = wctx.createLinearGradient(x - band / 2, 0, x + band / 2, h * 0.3);
      grad.addColorStop(0, 'rgba(255, 255, 255, 0)');
      grad.addColorStop(0.5, 'rgba(255, 255, 255, ' + (shade ? INTRO.sweep[1] : INTRO.sweep[0]) + ')');
      grad.addColorStop(1, 'rgba(255, 255, 255, 0)');
      wctx.globalCompositeOperation = 'source-atop';
      wctx.fillStyle = grad;
      wctx.fillRect(0, 0, w, h);
    }
    wctx.globalCompositeOperation = 'source-over';
  }

  var lastAt = 0;
  function draw(now) {
    frame = 0;
    if (!running) return;
    var t = now - start, dt = lastAt ? Math.min(0.05, (now - lastAt) / 1000) : 1 / 60;
    lastAt = now;
    ctx.clearRect(0, 0, 1920, 1080);   // the Horizon shows through
    // A warm glow behind the word.
    var glow = ctx.createRadialGradient(960, 540, 0, 960, 540, 760);
    glow.addColorStop(0, 'rgba(212, 175, 55, ' + (0.10 * ease(t / INTRO.fadeIn)) + ')');
    glow.addColorStop(1, 'rgba(212, 175, 55, 0)');
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, 1920, 1080);
    // The sparks, far ones first.
    if (!still) {
      var show = ease(t / 600);
      for (var i = 0; i < sparks.length; i++) {
        var s = sparks[i];
        s.y -= s.v * dt;
        if (s.y < -40) sparks[i] = s = spark(false);
        var sx = s.x + Math.sin(s.phase + t / 900) * INTRO.sway * (0.4 + s.z), fadeTop = Math.max(0, Math.min(1, s.y / 260));
        ctx.globalAlpha = s.a * show * fadeTop;
        ctx.drawImage(s.out ? soft : sharp, sx - s.size, s.y - s.size, s.size * 2, s.size * 2);
      }
      ctx.globalAlpha = 1;
    }
    // The wordmark: in over fadeIn, from 0.96; the sweep once.
    var a = still ? 1 : ease(t / INTRO.fadeIn), scale = still ? 1 : 0.96 + 0.04 * a;
    drawWord(still ? 0 : (t - INTRO.sweepAt) / INTRO.sweepFor);
    ctx.globalAlpha = a;
    var w = word.width * scale, h = word.height * scale;
    if (shade) ctx.drawImage(shade, 960 - w / 2, 540 - h / 2, w, h);
    ctx.drawImage(word, 960 - w / 2, 540 - h / 2, w, h);
    ctx.globalAlpha = 1;
    if (!endStart && t >= (still ? INTRO.staticHold : INTRO.endAt)) end(still ? INTRO.staticFade : INTRO.fade);
    if (endStart && now - endStart >= endFor) { finish(); return; }
    frame = requestAnimationFrame(draw);
  }

  /** The dissolve: the layer fades (a CSS opacity transition), and the lock comes in under it at the same moment. */
  function end(ms) {
    if (endStart) return;
    endStart = lastAt || start || 1;   // frame time (rAF's clock, whatever it counts from)
    endFor = ms;
    layer.style.webkitTransition = layer.style.transition = 'opacity ' + ms + 'ms ease-out';
    layer.style.opacity = '0';
    var d = done;
    done = null;
    if (d) d();
  }

  function finish() {
    running = false;
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    layer.style.visibility = 'hidden';
    layer.innerHTML = '';
    canvas = ctx = null;
  }

  return {
    INTRO: INTRO,
    chime: chime,
    knock: knock,
    showing: function () { return running && !endStart; },

    /** Plays it in `node`; then(done) runs as the dissolve starts (show the lock then). */
    play: function (node, then) {
      layer = node;
      done = then;
      still = !Prefs.intro() || Motion.instant;
      layer.innerHTML = '';
      layer.style.webkitTransition = layer.style.transition = '';
      layer.style.opacity = '1';
      layer.style.visibility = 'visible';
      var day = (' ' + document.body.className + ' ').indexOf(' night ') < 0;
      var hz = document.createElement('div');
      hz.className = 'hz hz-day';
      layer.appendChild(hz);
      hz = document.createElement('div');
      hz.className = 'hz hz-night';
      layer.appendChild(hz);
      canvas = make(1920, 1080);
      layer.appendChild(canvas);
      ctx = canvas.getContext('2d');
      word = make(1300, 280);
      wctx = word.getContext('2d');
      goldTex = makeGold(word.width, word.height, day ? INTRO.goldDay : INTRO.gold);
      shade = day ? makeShade(word.width, word.height) : null;
      sharp = makeSpark(false, day);
      soft = makeSpark(true, day);
      sparks = [];
      var lite = (' ' + document.documentElement.className + ' ').indexOf(' lite ') >= 0;
      if (!still && !lite) for (var i = 0; i < INTRO.sparks; i++) sparks.push(spark(true));
      sparks.sort(function (x, y) { return x.z - y.z; });
      endStart = 0;
      lastAt = 0;
      running = true;
      start = 0;
      if (Prefs.intro()) setTimeout(chime, 280);
      frame = requestAnimationFrame(function (now) { start = now; draw(now); });
    },

    /** OK or Back: straight to the dissolve. Every other key waits. */
    key: function (k) {
      if (k === TV.keys.ok || k === TV.keys.back || k === 27 || k === 8 || k === 13) end(INTRO.skipFade);
    },

    /** Off at once (no intro wanted): the layer goes without a frame drawn. */
    skip: function (node) {
      node.style.visibility = 'hidden';
      node.style.opacity = '0';
    }
  };
})();
