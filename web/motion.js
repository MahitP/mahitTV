/*
 * The motion engine shared by home.js, rooms.js and welly.js (ANIMATIONS-SPEC.md §1): Apple's springs (response,
 * damping; k = (2π/response)², c = 4π·damping/response), stepped with the exact solution so they're the same at any
 * frame rate, retargeted without losing velocity, and one requestAnimationFrame loop that runs only while something
 * moves. The same solver and presets as the Android app's Spring.java.
 */
(function () {
  'use strict';

  var MAX_DT = 1 / 30, GAP = 0.25;
  var root = document.documentElement;

  /** A spring: x (value), v (velocity), to (target). */
  function Spring(response, damping, value) {
    if (response && response.length === 2) { value = damping; damping = response[1]; response = response[0]; }
    this.r = response; this.d = damping;
    this.x = value || 0; this.v = 0; this.to = this.x;
    this.ex = 0.0005; this.ev = 0.005;
  }

  /** For a spring in pixels / degrees: at rest within a quarter pixel / a fiftieth of a degree. */
  Spring.prototype.px = function () { this.ex = 0.25; this.ev = 2; return this; };
  Spring.prototype.deg = function () { this.ex = 0.02; this.ev = 0.2; return this; };
  Spring.prototype.tune = function (response, damping) {
    if (response && response.length === 2) { damping = response[1]; response = response[0]; }
    this.r = response; this.d = damping;
    return this;
  };
  Spring.prototype.snap = function (value) { this.x = this.to = value; this.v = 0; return this; };
  /** Adds velocity (a dead-end bump, an arrival tilt). */
  Spring.prototype.impulse = function (dv) { if (!Motion.instant) this.v += dv; Motion.kick(); return this; };
  Spring.prototype.set = function (to) { if (to !== this.to) { this.to = to; Motion.kick(); } return this; };
  Spring.prototype.settled = function () { return Math.abs(this.x - this.to) < this.ex && Math.abs(this.v) < this.ev; };

  /** Advances dt seconds; true while still moving. */
  Spring.prototype.step = function (dt) {
    if (Motion.instant || dt > GAP || this.settled()) { this.x = this.to; this.v = 0; return false; }
    if (dt <= 0) return true;
    if (dt > MAX_DT) dt = MAX_DT;
    var w = 2 * Math.PI / this.r, z = this.d, x0 = this.x - this.to, v0 = this.v, x, v, e;
    if (z < 0.9999) {
      var wd = w * Math.sqrt(1 - z * z), c = Math.cos(wd * dt), s = Math.sin(wd * dt), b = (v0 + z * w * x0) / wd;
      e = Math.exp(-z * w * dt);
      x = e * (x0 * c + b * s);
      v = e * (-z * w * (x0 * c + b * s) + wd * (b * c - x0 * s));
    } else if (z <= 1.0001) {
      var b2 = v0 + w * x0;
      e = Math.exp(-w * dt);
      x = e * (x0 + b2 * dt);
      v = e * (v0 - w * b2 * dt);
    } else {
      var q = Math.sqrt(z * z - 1), r1 = -w * (z - q), r2 = -w * (z + q), a = (v0 - r2 * x0) / (r1 - r2), bb = x0 - a;
      var e1 = Math.exp(r1 * dt), e2 = Math.exp(r2 * dt);
      x = a * e1 + bb * e2;
      v = r1 * a * e1 + r2 * bb * e2;
    }
    this.x = this.to + x;
    this.v = v;
    return true;
  };

  var tickers = [], frame = 0, last = 0, ticking = false, again = false;

  function loop(now) {
    frame = 0;
    var dt = last ? (now - last) / 1000 : 1 / 60, moving = false;
    last = now;
    // A ticker that retargets a spring (set, impulse, kick) mustn't start a second loop: it asks for one more frame.
    ticking = true;
    again = false;
    for (var i = 0; i < tickers.length; i++) if (tickers[i](dt, now)) moving = true;
    ticking = false;
    if (moving || again) frame = requestAnimationFrame(loop);
    else last = 0;
  }

  var Motion = window.Motion = {
    Spring: Spring,
    /** The presets (ANIMATIONS-SPEC.md §1.2): [response, damping]. */
    P: {
      MICRO_PRESS: [0.18, 0.92], SNAPPY_FOCUS: [0.28, 0.82], FAST_FOCUS: [0.16, 0.95], BOUNDARY_BUMP: [0.24, 0.76],
      TILT: [0.42, 0.60], LIQUID_PILL: [0.36, 0.84], TAB_SWAP: [0.42, 0.90], SHEET_OPEN: [0.44, 0.88],
      SHEET_CLOSE: [0.34, 0.96], SPATIAL_SCROLL: [0.46, 0.92], TITLE_SETTLE: [0.38, 0.88], CAMERA_ORBIT: [0.68, 0.94],
      WALL_CUT: [0.45, 0.92], ROOM_FADE: [0.40, 1.00], IGNITE: [0.25, 0.75], CARD_ENTER: [0.28, 0.82], ORB: [0.50, 0.80],
      // Control Center's panels: tvOS 27 made them come up faster than a sheet.
      CC_OPEN: [0.32, 0.86]
    },
    BUMP_VELOCITY: 1100,
    /** Animations off: reduce motion, or Tizen 2.4 (`lite` on <html>), whose GPU can't keep up. */
    instant: false,
    reduced: false,
    lite: false,
    spring: function (preset, value) { return new Spring(preset, value); },
    /** UIScrollView's rubber band: f(x, d, c) = x·d·c / (d + c·x), c = 0.55. */
    rubber: function (x, d, c) {
      d = d || 40; c = c || 0.55;
      var a = Math.abs(x), f = a * d * c / (d + c * a);
      return x < 0 ? -f : f;
    },
    /** fn(dt, now) runs every frame while the loop runs; it returns true while it still has something moving. */
    tick: function (fn) { tickers.push(fn); Motion.kick(); },
    /** Something changed: run the loop (if it isn't running). */
    kick: function () {
      if (ticking) { again = true; return; }
      if (!frame) { last = 0; frame = requestAnimationFrame(loop); }
    },
    clamp: function (v, a, b) { return v < a ? a : v > b ? b : v; },
    /** transform for an element, with the prefixed property old WebKit needs. */
    transform: function (el, t) { el.style.webkitTransform = el.style.transform = t; }
  };

  function readSettings() {
    var cls = ' ' + root.className + ' ' + (document.body ? document.body.className : '') + ' ';
    Motion.lite = cls.indexOf(' lite ') >= 0;
    try { Motion.reduced = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches); } catch (e) {}
    Motion.instant = Motion.lite || Motion.reduced;
  }
  readSettings();
  // tv.js marks lite TVs on <html> when it runs (it loads after this script); read again once everything has loaded.
  window.addEventListener('load', readSettings);
  try {
    var mq = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)');
    if (mq && mq.addListener) mq.addListener(readSettings);
  } catch (e) {}
})();
