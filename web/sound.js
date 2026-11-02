/*
 * The home screen's acoustic feedback (Click.java on Android, the same cues synthesized the same way): a focus tick
 * panned to where the focus lands, select, the dead-end knock, a toggle's two blips, the hold's tension and its snap,
 * and an air-release whisper for sheets. Every cue is synthesized once into an AudioBuffer (no files to load); a cue
 * plays from it at once. Successive ticks vary a little in pitch and level, tick lighter when the focus moves fast,
 * and never more than ~28 a second. Settings → Navigation sounds turns them off (Prefs.sounds). A browser holds audio
 * until a key: the first key wakes it (wake()).
 */
var Sound = (function () {
  'use strict';

  /** Each cue's level (0–1, before the system volume), as Click.java's. */
  var LEVEL = {tick: 0.13, select: 0.20, bump: 0.15, toggle: 0.16, tension: 0.16, commit: 0.20, sheet: 0.14, error: 0.22};
  var PAN_WIDTH = 0.6, MIN_GAP = 35, FAST = 90;
  var RATES = [1, 1.012, 0.994, 1.006, 0.988], GAINS = [1, 0.94, 0.97, 0.92, 0.98];

  var ac = null, out = null, buf = null, rate = 48000, variation = 0, lastTick = 0, tension = null;

  function on() { return !window.Prefs || !Prefs.sounds || Prefs.sounds(); }

  function context() {
    if (ac) return ac;
    var A = window.AudioContext || window.webkitAudioContext;
    if (!A) return null;
    try { ac = new A({latencyHint: 'interactive'}); } catch (e) { try { ac = new A(); } catch (e2) { ac = null; } }
    if (!ac) return null;
    out = ac.createGain();
    out.gain.value = 1;
    out.connect(ac.destination);
    return ac;
  }

  // MARK: - The cues, synthesized once

  function raised(x) { return x <= 0 ? 0 : x >= 1 ? 1 : 0.5 - 0.5 * Math.cos(Math.PI * x); }

  function mode(m, start, hz, amp, tau) {
    var from = Math.round(start * rate), w = 2 * Math.PI * hz / rate;
    for (var i = Math.max(0, from); i < m.length; i++) {
      var t = (i - from) / rate, env = raised(t / 0.0003) * Math.exp(-t / tau);
      if (t > 0.01 && env < 1e-4) break;
      m[i] += amp * env * Math.sin(w * (i - from));
    }
  }

  /** A fixed noise (the same on every TV): a 32-bit LCG, as Click.java's. */
  function noise(state) {
    state.s = (Math.imul ? Math.imul(state.s, 1664525) : (state.s * 1664525) | 0) + 1013904223 | 0;
    return (state.s >>> 8) / 16777216 * 2 - 1;
  }

  function grain(m, start, amp, tau, cut) {
    var from = Math.round(start * rate), a = cut > 0 ? Math.exp(-2 * Math.PI * cut / rate) : 0, y = 0, st = {s: 0x2545F491};
    for (var i = Math.max(0, from); i < m.length; i++) {
      var t = (i - from) / rate, env = Math.exp(-t / tau);
      if (env < 1e-4) break;
      var x = noise(st);
      y = cut > 0 ? (1 - a) * x + a * y : x;
      m[i] += amp * env * y;
    }
  }

  var SYNTH = {
    tick: [0.028, function (m) {
      grain(m, 0, 0.30, 0.0005, 0);
      mode(m, 0, 1240, 1.0, 0.007);
      mode(m, 0, 2710, 0.42, 0.0045);
      mode(m, 0, 4380, 0.16, 0.0025);
    }],
    select: [0.15, function (m) {
      grain(m, 0, 0.18, 0.0008, 3000);
      mode(m, 0, 196, 0.55, 0.022);
      mode(m, 0.006, 1568, 0.62, 0.055);
      mode(m, 0.006, 2352, 0.22, 0.032);
      mode(m, 0.006, 3136, 0.08, 0.018);
    }],
    bump: [0.07, function (m) {
      grain(m, 0, 0.35, 0.006, 900);
      mode(m, 0, 220, 0.9, 0.016);
      mode(m, 0, 331, 0.4, 0.010);
    }],
    on: [0.09, function (m) { blips(m, 1046.5, 1568.0); }],
    off: [0.09, function (m) { blips(m, 1568.0, 1046.5); }],
    tension: [0.56, function (m) {
      var phase = 0, a = Math.exp(-2 * Math.PI * 380 / rate), y = 0, y2 = 0, st = {s: 0x1234567};
      for (var i = 0; i < m.length; i++) {
        var t = i / rate, rise = Math.min(1, t / 0.45);
        var env = raised(rise) * (1 - raised((t - 0.46) / 0.10)), hz = 110 * (1 + 0.12 * rise * rise);
        phase += 2 * Math.PI * hz / rate;
        var tone = Math.sin(phase) + 0.35 * Math.sin(2 * phase) + 0.12 * Math.sin(3 * phase);
        y = (1 - a) * noise(st) + a * y;
        y2 = (1 - a) * y + a * y2;
        var ratchet = 0.5 + 0.5 * Math.sin(2 * Math.PI * (14 + 8 * rise) * t);
        m[i] = env * (0.55 * tone + 2.4 * y2 * ratchet * ratchet);
      }
    }],
    commit: [0.12, function (m) {
      grain(m, 0, 0.25, 0.0008, 2500);
      mode(m, 0, 147, 0.7, 0.03);
      mode(m, 0.004, 880, 0.55, 0.04);
      mode(m, 0.004, 1320, 0.3, 0.03);
    }],
    sheetIn: [0.26, function (m) { air(m, 260, 1700, 0.12); }],
    sheetOut: [0.20, function (m) { air(m, 1500, 220, 0.03); }],
    error: [0.18, function (m) {
      grain(m, 0, 0.3, 0.02, 600);
      mode(m, 0, 150, 1.0, 0.045);
      mode(m, 0.07, 120, 0.7, 0.05);
    }]
  };

  function blips(m, first, second) {
    mode(m, 0, first, 0.8, 0.018);
    mode(m, 0, first * 2, 0.12, 0.010);
    mode(m, 0.028, second, 0.8, 0.022);
    mode(m, 0.028, second * 2, 0.12, 0.012);
  }

  function air(m, from, to, peak) {
    var n = m.length, y1 = 0, y2 = 0, st = {s: 0x7F4A7C15}, len = n / rate;
    for (var i = 0; i < n; i++) {
      var t = i / rate, u = i / n, cut = from * Math.pow(to / from, u), a = Math.exp(-2 * Math.PI * cut / rate);
      y1 = (1 - a) * noise(st) + a * y1;
      y2 = (1 - a) * y1 + a * y2;
      var env = t < peak ? raised(t / peak) : Math.exp(-(t - peak) / ((len - peak) * 0.35));
      m[i] = env * y2;
    }
    if (to > from) mode(m, peak, 2093, 0.03, 0.06);
  }

  /** Every cue into its buffer: faded in over 0.15 ms and out over 1 ms, peak at −3 dBFS (as Click.java's WAVs). */
  function build() {
    if (buf) return buf;
    rate = ac.sampleRate;
    buf = {};
    for (var name in SYNTH) {
      if (!SYNTH.hasOwnProperty(name)) continue;
      var n = Math.round(rate * SYNTH[name][0]), m = new Float32Array(n), peak = 1e-6, fin = rate / 6667, fout = rate / 1000, i;
      SYNTH[name][1](m);
      for (i = 0; i < n; i++) {
        m[i] *= Math.min(1, (i + 1) / fin, (n - i) / fout);
        peak = Math.max(peak, Math.abs(m[i]));
      }
      var b = ac.createBuffer(1, n, rate), d = b.getChannelData(0), g = 0.708 / peak;
      for (i = 0; i < n; i++) d[i] = m[i] * g;
      buf[name] = b;
    }
    return buf;
  }

  // MARK: - Playing

  /** Plays a cue at `level`, panned (equal power, PAN_WIDTH wide; the middle at full level), at `speed`. */
  function play(name, level, pan, speed) {
    if (!on() || !context()) return null;
    try {
      if (ac.state === 'suspended') return null;   // until a key wakes it
      build();
      var src = ac.createBufferSource(), g = ac.createGain(), last = g;
      src.buffer = buf[name];
      src.playbackRate.value = speed || 1;
      g.gain.value = level;
      src.connect(g);
      var p = ((pan === undefined ? 0.5 : pan) - 0.5) * PAN_WIDTH;
      if (p && ac.createStereoPanner) {
        var sp = ac.createStereoPanner();
        sp.pan.value = p;
        g.connect(sp);
        last = sp;
      }
      last.connect(out);
      src.start(0);
      return {src: src, gain: g};
    } catch (e) { return null; }
  }

  return {
    /** A key: lets the browser start audio (it holds it until a gesture), and builds the cues off the first sound. */
    wake: function () {
      if (!on() || !context()) return;
      try {
        if (ac.state === 'suspended' && ac.resume) ac.resume();
        build();
      } catch (e) {}
    },

    /** The focus moved and landed at `pan` (0 left … 1 right). */
    tick: function (pan) {
      var now = Date.now(), gap = now - lastTick;
      if (gap < MIN_GAP) return;
      lastTick = now;
      var level = LEVEL.tick * GAINS[variation] * (gap < FAST ? 0.62 : 1);
      play('tick', level, pan, RATES[variation]);
      variation = (variation + 1) % RATES.length;
    },
    select: function () { play('select', LEVEL.select, 0.5, 1); },
    bump: function (pan) { play('bump', LEVEL.bump, pan, 1); },
    toggle: function (state) { play(state ? 'on' : 'off', LEVEL.toggle, 0.5, 1); },
    error: function () { play('error', LEVEL.error, 0.5, 1); },
    sheet: function (open, pan) { play(open ? 'sheetIn' : 'sheetOut', LEVEL.sheet, pan, 1); },

    /** A press whose hold does something: the tension builds toward it. */
    holdStart: function () {
      Sound.holdStop();
      tension = play('tension', LEVEL.tension, 0.5, 1);
    },
    /** Let go first: the tension fades out over 40 ms. */
    holdCancel: function () {
      if (!tension) return;
      try {
        var t = ac.currentTime;
        tension.gain.gain.setValueAtTime(LEVEL.tension, t);
        tension.gain.gain.linearRampToValueAtTime(0, t + 0.04);
        tension.src.stop(t + 0.05);
      } catch (e) {}
      tension = null;
    },
    /** The hold took: the tension stops and the snap answers. */
    holdCommit: function () {
      Sound.holdStop();
      play('commit', LEVEL.commit, 0.5, 1);
    },
    holdStop: function () {
      if (tension) { try { tension.src.stop(0); } catch (e) {} }
      tension = null;
    }
  };
})();
