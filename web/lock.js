/*
 * "Who's Watching?" (MAHITTV-PROFILES-SPEC.md §3): the profiles' cards over the Frosted Horizon, and the PIN HUD (four
 * dots, a glass 0–9 row, the remote's number keys). Guest and profiles without a PIN go straight in; a right PIN pulses
 * the dots gold and the avatar flies into the status pill. Back does nothing on the cards (it's the gate), unless the
 * lock was opened only to switch profile. Lock.ask() puts the same PIN pad over Settings (change a PIN, the manager's).
 */
var Lock = (function () {
  'use strict';

  var CARD = 240, GAP = 96, CARD_TOP = 380, PIN_TOP = 120;
  // The entrance from the startup (TVOS-LAUNCHER-MERGE-SPEC.md Part 2 §1.5): the header rises 24 px, each card 48 px
  // from 0.92×, 30 ms after the one before; the Horizon behind them is already there.
  var TITLE_RISE = 24, CARD_RISE = 48, CARD_FROM = 0.92, STAGGER = 0.032;
  var P = Motion.P, BUMP = Motion.BUMP_VELOCITY;

  var layer = null, bg = null, title = null, hud = null, prompt = null, dotsBox = null, msg = null, row = null;
  var cards = [], dots = [], keys = [];
  var mode = null;            // 'cards', 'pin', 'fly', or null (hidden)
  var at = 0, pad = 1, digits = '', chosen = null, opts = {}, asking = null, waitTimer = 0, busy = false;
  var entering = -1;          // seconds into the staggered entrance, or -1
  var uncoverTimer = 0;

  /** The home screen's background may paint again (intro.js holds it back while the startup covers it). */
  function uncover() {
    clearTimeout(uncoverTimer);
    var b = document.body;
    if ((' ' + b.className + ' ').indexOf(' covered ') >= 0) b.className = (' ' + b.className + ' ').replace(' covered ', ' ').replace(/^\s+|\s+$/g, '');
  }

  function spring(preset, value) { return new Motion.Spring(preset, value || 0); }
  var sp = {
    veil: spring(P.SHEET_OPEN, 1), title: spring(P.TITLE_SETTLE, 1), titleIn: spring(P.TITLE_SETTLE, 1), hud: spring(P.SHEET_OPEN),
    shake: spring(P.BOUNDARY_BUMP).px(), gold: spring(P.MICRO_PRESS), row: spring(P.BOUNDARY_BUMP).px()
  };

  function el(tag, cls, parent, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    if (parent) parent.appendChild(e);
    return e;
  }
  function setClass(e, c, on) {
    var has = (' ' + e.className + ' ').indexOf(' ' + c + ' ') >= 0;
    if (on && !has) e.className = (e.className ? e.className + ' ' : '') + c;
    else if (!on && has) e.className = (' ' + e.className + ' ').replace(' ' + c + ' ', ' ').replace(/^\s+|\s+$/g, '');
  }
  function r2(v) { return Math.round(v * 100) / 100; }
  function r4(v) { return Math.round(v * 10000) / 10000; }
  function T(e, t) { if (e && e._t !== t) { e._t = t; e.style.webkitTransform = e.style.transform = t; } }
  function A(e, a) {
    if (!e) return;
    var v = String(r4(Motion.clamp(a, 0, 1)));
    if (e._o !== v) { e._o = v; e.style.opacity = v; e.style.visibility = a < 0.002 ? 'hidden' : 'visible'; }
  }

  // MARK: - Building it

  /** Where card i rests in a row of n, centred. */
  function restX(i, n) { var w = n * CARD + (n - 1) * GAP; return (1920 - w) / 2 + i * (CARD + GAP); }

  function build(list, glass) {
    layer.innerHTML = '';
    bg = el('div', 'bg' + (glass ? ' glass' : ''), layer);
    el('div', 'hz hz-day', bg);
    el('div', 'hz hz-night', bg);
    el('div', 'veil', bg);
    title = el('div', 'title', layer, tr("Who's Watching?"));
    cards = [];
    for (var i = 0; i < list.length; i++) {
      var p = list[i], node = el('div', 'card' + (p.role === 'manager' ? ' manager' : ''), layer);
      el('div', 'shadow', node);
      el('div', 'ring', node);
      var face = el('div', 'face', node);
      face.style.backgroundImage = 'url(' + Profiles.avatarUrl(p) + ')';
      roundness(Profiles.avatarUrl(p), face);
      el('div', 'spec', face);
      var label = el('div', 'label', node, p.name);
      if (Profiles.needsPin(p)) el('span', 'glyph-lock', label);
      var c = {p: p, node: node, ring: node.childNodes[1], shadow: node.firstChild, spec: face.lastChild, label: label,
        f: spring(P.SNAPPY_FOCUS), bx: spring(P.BOUNDARY_BUMP).px(), by: spring(P.BOUNDARY_BUMP).px(),
        x: spring(P.SPATIAL_SCROLL, restX(i, list.length)).px(), y: spring(P.SPATIAL_SCROLL, CARD_TOP).px(),
        s: spring(P.SPATIAL_SCROLL, 1), a: spring(P.TITLE_SETTLE, 1), e: spring(P.CARD_ENTER, 1)};
      hoverCard(c, i);
      cards.push(c);
    }
    hud = el('div', 'hud', layer);
    prompt = el('div', 'prompt', hud);
    dotsBox = el('div', 'dots', hud);
    dots = [];
    for (var d = 0; d < 4; d++) dots.push({node: el('span', 'dot', dotsBox), pop: spring(P.MICRO_PRESS)});
    row = el('div', 'pad', hud);
    keys = [];
    for (var k = 0; k < 10; k++) {
      var key = el('div', 'digit', row, String((k + 1) % 10));   // 1 2 3 … 9 0, as on a phone's row
      keys.push({node: key, n: (k + 1) % 10, f: spring(P.SNAPPY_FOCUS)});
      hoverKey(key, k);
    }
    msg = el('div', 'msg', hud);
  }

  function hoverCard(c, i) {
    c.node.onmouseover = function () { if (mode === 'cards') { at = i; focus(); } };
    c.node.onclick = function () { if (mode === 'cards') { at = i; focus(); choose(c); } };
  }

  function hoverKey(node, k) {
    node.onmouseover = function () { if (mode === 'pin') { pad = k; focus(); } };
    node.onclick = function () { if (mode === 'pin') { pad = k; focus(); digit(keys[k].n); } };
  }

  /**
   * A round avatar (a disc on transparent corners) is drawn larger, so its disc fills the squircle instead of floating
   * in it. Where the image can't be read back (file:// on some TVs), it stays as it is.
   */
  var probe = null, probeCtx = null;
  function roundness(url, face) {
    var img = new Image();
    img.onload = function () {
      try {
        // One small canvas kept on the CPU (willReadFrequently): reading back a GPU canvas stalls the page for a frame
        // per avatar, right while the cards come in.
        if (!probe) {
          probe = document.createElement('canvas');
          probe.width = probe.height = 16;
          probeCtx = probe.getContext('2d', {willReadFrequently: true}) || probe.getContext('2d');
        }
        probeCtx.clearRect(0, 0, 16, 16);
        probeCtx.drawImage(img, 0, 0, 16, 16);
        var d = probeCtx.getImageData(0, 0, 16, 16).data;
        if (d[3] < 40 && d[(15 * 16 + 15) * 4 + 3] < 40) setClass(face, 'round', true);
      } catch (e) {}
    };
    img.src = url;
  }

  // MARK: - Focus and motion

  function focus() {
    for (var i = 0; i < cards.length; i++) {
      var on = mode === 'cards' ? i === at : cards[i] === chosen;
      cards[i].f.set(on ? 1 : 0);
      setClass(cards[i].node, 'focus', on);
    }
    for (var k = 0; k < keys.length; k++) {
      keys[k].f.set(mode === 'pin' && k === pad ? 1 : 0);
      setClass(keys[k].node, 'focus', mode === 'pin' && k === pad);
    }
    for (var d = 0; d < dots.length; d++) setClass(dots[d].node, 'on', d < digits.length);
    Motion.kick();
  }

  function stepAll(dt) {
    var m = false, k;
    for (k in sp) if (sp.hasOwnProperty(k) && sp[k].step(dt)) m = true;
    for (var i = 0; i < cards.length; i++) {
      var c = cards[i];
      if (c.f.step(dt)) m = true;
      if (c.bx.step(dt)) m = true;
      if (c.by.step(dt)) m = true;
      if (c.x.step(dt)) m = true;
      if (c.y.step(dt)) m = true;
      if (c.s.step(dt)) m = true;
      if (c.a.step(dt)) m = true;
      if (c.e.step(dt)) m = true;
    }
    // The staggered entrance: card i's spring starts STAGGER·i after the first.
    if (entering >= 0) {
      entering += dt;
      var left = false;
      for (i = 0; i < cards.length; i++) {
        if (cards[i].e.to === 1) continue;
        if (entering >= i * STAGGER) cards[i].e.set(1);
        else left = true;
      }
      if (left) m = true;
      else entering = -1;
    }
    for (i = 0; i < dots.length; i++) if (dots[i].pop.step(dt)) m = true;
    for (i = 0; i < keys.length; i++) if (keys[i].f.step(dt)) m = true;
    return m;
  }

  function draw() {
    // The backdrop (the Horizon) never moves: only what's on it comes in.
    A(bg, sp.veil.x);
    A(title, sp.title.x * sp.titleIn.x);
    T(title, 'translateY(' + r2(-14 * (1 - sp.title.x) + TITLE_RISE * (1 - sp.titleIn.x)) + 'px)');
    for (var i = 0; i < cards.length; i++) {
      var c = cards[i], f = c.f.x, e = c.e.x, shake = c === chosen ? sp.shake.x : 0;
      var x = c.x.x + Motion.rubber(c.bx.x) + shake, y = c.y.x + Motion.rubber(c.by.x) + CARD_RISE * (1 - e);
      var sc = c.s.x * (1 + 0.18 * f) * (CARD_FROM + (1 - CARD_FROM) * e);
      T(c.node, 'translate(' + r2(x) + 'px,' + r2(y) + 'px) scale(' + r4(sc) + ')');
      A(c.node, c.a.x * Motion.clamp(e, 0, 1));
      A(c.ring, f);
      A(c.shadow, f);
      A(c.spec, 0.35 + 0.65 * f);
      A(c.label, mode === 'fly' ? 0 : f);
      T(c.label, 'translateY(' + r2(8 * (1 - f)) + 'px)');
    }
    A(hud, sp.hud.x);
    T(hud, 'translateY(' + r2(24 * (1 - sp.hud.x)) + 'px)');
    T(dotsBox, 'translateX(' + r2(sp.shake.x) + 'px)');
    T(row, 'translateX(' + r2(Motion.rubber(sp.row.x)) + 'px)');
    for (var d = 0; d < dots.length; d++) T(dots[d].node, 'scale(' + r4(1 + dots[d].pop.x + 0.35 * sp.gold.x) + ')');
    for (var k = 0; k < keys.length; k++) T(keys[k].node, 'scale(' + r4(1 + 0.14 * keys[k].f.x) + ')');
  }

  Motion.tick(function (dt) {
    if (!layer || !mode) return false;
    var m = stepAll(dt);
    draw();
    return m;
  });

  // MARK: - Choosing, the PIN

  function choose(c) {
    if (!c) return;
    if (!Profiles.needsPin(c.p)) { success(c); return; }
    chosen = c;
    mode = 'pin';
    digits = '';
    pad = 0;
    // The card glides to the centre and up; the others and the title step back; the HUD comes up.
    for (var i = 0; i < cards.length; i++) {
      if (cards[i] === c) { c.x.set(960 - CARD / 2); c.y.set(PIN_TOP); }
      else cards[i].a.set(0);
    }
    sp.title.set(0);
    prompt.textContent = tr('Enter PIN');
    sp.hud.set(1);
    showWait();
    focus();
  }

  /** Back to the cards from the HUD. */
  function unchoose() {
    mode = 'cards';
    digits = '';
    for (var i = 0; i < cards.length; i++) {
      cards[i].x.set(restX(i, cards.length));
      cards[i].y.set(CARD_TOP);
      cards[i].a.set(1);
    }
    chosen = null;
    sp.title.set(1);
    sp.hud.set(0);
    clearInterval(waitTimer);
    focus();
  }

  function waitProfile() { return asking ? asking.profile : chosen && chosen.p; }

  function showWait() {
    clearInterval(waitTimer);
    function tick() {
      var p = waitProfile(), left = p ? Profiles.waitLeft(p) : 0;
      msg.textContent = left > 0 ? trf('Try again in {n} s', {n: Math.ceil(left / 1000)}) : (asking && asking.hint) || '';
    }
    tick();
    waitTimer = setInterval(tick, 250);
  }

  function digit(n) {
    if (busy || digits.length >= 4) return;
    var p = waitProfile();
    if (p && Profiles.waitLeft(p) > 0) { wrong(); return; }
    digits += n;
    dots[digits.length - 1].pop.impulse(32);
    focus();
    if (digits.length < 4) return;
    busy = true;
    setTimeout(function () {
      busy = false;
      var entered = digits;
      if (asking) {
        if (asking.verify && !asking.verify(entered)) { wrong(); return; }
        var a = asking;
        digits = '';
        focus();
        a.done(entered);   // may ask again (the next step) or close
        return;
      }
      if (Profiles.checkPin(chosen.p, entered)) right();
      else wrong();
    }, 140);
  }

  /** A wrong PIN: a head-shake (impulses on BOUNDARY_BUMP, ±18 px), the knock, the dots clear. */
  function wrong() {
    busy = true;
    var seq = [1, -1.7, 1.5, -0.9], i = 0;
    (function next() {
      sp.shake.impulse(seq[i] * 1080);
      if (++i < seq.length) setTimeout(next, 75);
    })();
    if (window.Intro) Intro.knock();
    setTimeout(function () { busy = false; digits = ''; focus(); showWait(); }, 260);
  }

  /** The right PIN: the dots pulse gold, then the avatar flies to the pill. */
  function right() {
    setClass(dotsBox, 'gold', true);
    sp.gold.impulse(26);
    busy = true;
    setTimeout(function () { busy = false; success(chosen); }, 300);
  }

  /** Where the status pill's avatar is, in stage pixels (the stage may be scaled to the window). */
  function pillTarget() {
    var a = document.querySelector('#pill .avatar'), stage = document.getElementById('stage');
    if (!a || !stage) return {x: 1820, y: 70, size: 49};
    var r = a.getBoundingClientRect(), s = stage.getBoundingClientRect(), k = s.width / 1920 || 1;
    return {x: (r.left - s.left) / k + r.width / k / 2, y: (r.top - s.top) / k + r.height / k / 2, size: r.width / k || 49};
  }

  function success(c) {
    uncover();
    mode = 'fly';
    clearInterval(waitTimer);
    var p = c.p, cb = opts.onUnlock;
    Profiles.unlock(p);
    if (cb) cb(p);   // the home screen re-renders behind, its pill already showing the new face
    var t = pillTarget();
    for (var i = 0; i < cards.length; i++) if (cards[i] !== c) cards[i].a.set(0);
    c.f.set(0);
    c.x.set(t.x - CARD / 2);
    c.y.set(t.y - CARD / 2);
    c.s.set(t.size / CARD);
    sp.title.set(0);
    sp.hud.set(0);
    sp.veil.tune(P.SHEET_CLOSE).set(0);
    Motion.kick();
    setTimeout(function () { c.a.tune([0.16, 1]).set(0); }, 420);
    setTimeout(hide, 700);
  }

  function hide() {
    uncover();
    mode = null;
    asking = null;
    chosen = null;
    clearInterval(waitTimer);
    layer.style.visibility = 'hidden';
    layer.style.opacity = '0';
    layer.innerHTML = '';
    cards = [];
    if (opts.onHide) opts.onHide();
  }

  function bump(dx, dy) {
    if (mode === 'cards' && cards[at]) { cards[at].bx.impulse(dx * BUMP); cards[at].by.impulse(dy * BUMP); }
    else if (mode === 'pin') sp.row.impulse((dx || 0) * BUMP);
  }

  // MARK: - Showing it

  function present(list, glass, from) {
    layer.style.visibility = 'visible';
    layer.style.opacity = '1';
    layer._t = layer._o = null;
    layer.style.webkitTransform = layer.style.transform = '';
    build(list, glass);
    sp.veil.tune(P.SHEET_OPEN).snap(1);
    sp.title.snap(1);
    sp.hud.snap(0);
    sp.shake.snap(0);
    sp.gold.snap(0);
    // From the startup the Horizon is already on screen (the intro's own, identical, goes as this one appears): the
    // header and the cards come in over it, staggered. Otherwise everything is simply there.
    var enter = from === 'intro' && !Motion.instant;
    sp.titleIn.snap(enter ? 0 : 1);
    if (enter) sp.titleIn.set(1);
    for (var i = 0; i < cards.length; i++) { cards[i].f.snap(0); cards[i].a.snap(1); cards[i].e.snap(enter ? 0 : 1); }
    entering = enter ? 0 : -1;
    // Once the cards have settled (about 0.8 s), the home background paints behind the lock, while nothing moves.
    clearTimeout(uncoverTimer);
    uncoverTimer = setTimeout(uncover, enter ? 900 : 0);
    draw();
    Motion.kick();
  }

  var api = {
    /** opts: onUnlock(profile), onHide(). */
    init: function (node, o) { layer = node; opts = o || {}; },
    showing: function () { return !!mode; },

    /**
     * The lock: o.from ('intro', 'idle', 'switch'), o.profile (that card first, and its PIN at once when it has one),
     * o.cancel (Back on the cards returns: the lock was opened only to switch).
     */
    show: function (o) {
      o = o || {};
      asking = null;
      api.cancel = !!o.cancel;
      var list = Profiles.all(), want = o.profile || Profiles.current();
      present(list, false, o.from);
      mode = 'cards';
      at = Math.max(0, list.indexOf(want));
      chosen = null;
      digits = '';
      focus();
      if (o.profile && o.pin && Profiles.needsPin(o.profile)) {
        for (var i = 0; i < cards.length; i++) cards[i].f.snap(i === at ? 1 : 0);
        choose(cards[at]);
      }
    },

    /**
     * A PIN pad over Settings: a.title, a.profile (its avatar, and its wait after five wrong tries), a.verify(digits)
     * (false shakes and clears), a.done(digits), a.cancel(), a.hint. Calling ask again inside done is the next step.
     */
    ask: function (a) {
      var again = mode === 'pin' && asking;
      asking = a;
      if (!again) {
        present(a.profile ? [a.profile] : [], true, null);
        if (cards[0]) { cards[0].x.snap(960 - CARD / 2); cards[0].y.snap(PIN_TOP); cards[0].f.snap(1); chosen = cards[0]; }
        sp.title.snap(0);
        sp.hud.set(1);
      }
      mode = 'pin';
      digits = '';
      pad = 0;
      setClass(dotsBox, 'gold', false);
      prompt.textContent = a.title || tr('Enter PIN');
      showWait();
      focus();
    },

    /** Closes a Settings PIN pad. */
    close: function () { if (asking) hide(); },

    /** Every key goes here while it shows; true when it was used. */
    key: function (e) {
      var k = e.keyCode, K = TV.keys;
      if (mode === 'fly') return true;
      var n = k >= 48 && k <= 57 ? k - 48 : k >= 96 && k <= 105 ? k - 96 : -1;
      if (mode === 'cards') {
        if (k === K.left || k === K.right) {
          var to = at + (k === K.left ? -1 : 1);
          if (to < 0 || to >= cards.length) bump(k === K.left ? -1 : 1, 0);
          else { at = to; focus(); }
        } else if (k === K.up || k === K.down) bump(0, k === K.up ? -1 : 1);
        else if (k === K.ok || k === 13) { if (!e.repeat) choose(cards[at]); }
        else if ((k === K.back || k === 27 || k === 8) && api.cancel) hide();
        return true;
      }
      if (mode === 'pin') {
        if (n >= 0) digit(n);
        else if (k === K.left || k === K.right) {
          var p2 = pad + (k === K.left ? -1 : 1);
          if (p2 < 0 || p2 > 9) bump(k === K.left ? -1 : 1, 0);
          else { pad = p2; focus(); }
        } else if (k === K.ok || k === 13) { if (!e.repeat) digit(keys[pad].n); }
        else if (k === 8 && digits.length) { digits = digits.slice(0, -1); focus(); }
        else if (k === K.back || k === 27 || k === 8) {
          if (asking) { var a = asking; hide(); if (a.cancel) a.cancel(); }
          else unchoose();
        }
        return true;
      }
      return false;
    },
    cancel: false
  };
  return api;
})();
