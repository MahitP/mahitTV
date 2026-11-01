/*
 * The welcome setup ("Welcome to mahitTV"): on a new TV after the startup, and from Settings → System → Run Setup Again.
 * Four steps in one Liquid Glass card over the Frosted Horizon (never a flat black): Welcome; the look (Light, Dark or
 * Automatic, and Liquid or Frosted glass, both shown at once as they're picked); the Top Shelf; the profiles and the
 * weather (°F or °C), then "You're All Set". ← → pick in a row, ↑ ↓ move between rows, OK goes on, Back goes back.
 * home.js applies each choice (opts) and owns what happens after; this file draws and moves the focus.
 * Only transform and opacity animate.
 */
var Setup = (function () {
  'use strict';

  var layer = null, card = null, content = null, opts = null, open = false, step = 0, row = 0, col = 0, rows = [];
  var show = null, slide = null, STEPS = 4;

  function el(tag, cls, parent, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    if (parent) parent.appendChild(e);
    return e;
  }

  function sym(name, parent) {
    var s = el('span', 'sym', parent);
    s.style.webkitMaskImage = s.style.maskImage = 'url(symbols/' + name + '.png)';
    return s;
  }

  function setClass(e, c, on) {
    var has = (' ' + e.className + ' ').indexOf(' ' + c + ' ') >= 0;
    if (on && !has) e.className = (e.className + ' ' + c).replace(/^\s+/, '');
    else if (!on && has) e.className = (' ' + e.className + ' ').replace(' ' + c + ' ', ' ').replace(/^\s+|\s+$/g, '');
  }

  // MARK: - The steps: each a title, a line under it, and rows of choices (options) or a button

  function steps() {
    return [
      {title: tr('Welcome to mahitTV'), line: tr('A home screen for your TV, made like Apple TV.'), hero: true,
        rows: [{button: tr('Get Started')}]},
      {title: tr('Choose Your Look'), line: tr('You can change these anytime in Settings.'),
        rows: [
          {head: tr('Theme'), value: Prefs.theme(), set: opts.theme, options: [['light', tr('Light'), 'swatch-day'], ['dark', tr('Dark'), 'swatch-night'],
            ['auto', tr('Automatic'), 'swatch-auto']]},
          {head: tr('Glass'), value: Prefs.glass(), set: opts.glass, options: [['liquid', tr('Liquid Glass'), 'swatch-liquid'], ['frosted', tr('Frosted Glass'), 'swatch-frosted']]},
          {button: tr('Continue')}
        ]},
      {title: tr('Top Shelf'), line: tr('What fills the top of the TV tab, above the Dock.'),
        rows: [
          {list: true, value: Prefs.topShelf(), set: opts.topShelf, options: [
            ['app', tr('App-Specific Continue Watching'), tr('The Frosted Horizon until you focus a streaming app, then what you were watching in it.')],
            ['picks', tr('Recommendations & News'), tr('Picks and news from your apps, always on top.')],
            ['all', tr('All Continue Watching'), tr('What you were watching, from any app.')],
            ['off', tr('Off (Wallpaper Only)'), tr('Only your wallpaper.')]]},
          {button: tr('Continue')}
        ]},
      {title: tr("You're All Set"), line: tr('Your profiles and your weather are ready.'), people: true,
        rows: [
          {head: tr('Temperature'), value: Prefs.temperature() === 'C' ? 'C' : 'F', set: opts.temperature, options: [['F', '°F'], ['C', '°C']]},
          {button: tr('Go to Home Screen')}
        ]}
    ];
  }

  // MARK: - Drawing

  function build() {
    content.innerHTML = '';
    var s = steps()[step];
    rows = [];
    var dots = el('div', 'dots', content);
    for (var d = 0; d < STEPS; d++) setClass(el('span', 'dot', dots), 'on', d === step);
    if (s.hero) {
      var mark = el('div', 'mark', content);
      el('span', 'm', mark, 'mahit');
      el('span', 't', mark, 'TV');
    }
    el('div', 'title' + (s.hero ? ' big' : ''), content, s.title);
    el('div', 'line', content, s.line);
    if (s.people) people(content);
    for (var r = 0; r < s.rows.length; r++) rows.push(buildRow(s.rows[r]));
    row = 0;
    col = current(rows[0]);
    focus();
  }

  function people(parent) {
    var list = opts.people(), box = el('div', 'people', parent);
    for (var i = 0; i < list.length; i++) {
      var p = el('div', 'who', box), face = el('span', 'face', p);
      face.style.backgroundImage = list[i].avatar;
      el('span', 'name', p, list[i].name);
    }
    var city = el('div', 'city', parent);
    sym('sunny', city);
    el('span', '', city, opts.city() || tr('Weather'));
  }

  function buildRow(spec) {
    var r = {spec: spec, nodes: []};
    if (spec.button) {
      var b = el('div', 'button', content, spec.button);
      r.nodes.push(b);
      return r;
    }
    if (spec.head) el('div', 'head', content, spec.head);
    var box = el('div', spec.list ? 'list' : 'options', content);
    for (var i = 0; i < spec.options.length; i++) {
      var o = spec.options[i], n = el('div', spec.list ? 'item' : 'option', box);
      if (spec.list) {
        el('div', 'label', n, o[1]);
        el('div', 'desc', n, o[2]);
        sym('check', n).className += ' check';
      } else {
        if (o[2]) el('span', 'swatch ' + o[2], n);
        el('span', 'label', n, o[1]);
      }
      setClass(n, 'picked', o[0] === spec.value);
      r.nodes.push(n);
    }
    return r;
  }

  /** The picked option's index in an options row (the focus starts there), else 0. */
  function current(r) {
    if (!r || !r.spec.options) return 0;
    for (var i = 0; i < r.spec.options.length; i++) if (r.spec.options[i][0] === r.spec.value) return i;
    return 0;
  }

  function focus() {
    for (var r = 0; r < rows.length; r++) {
      for (var i = 0; i < rows[r].nodes.length; i++) {
        var list = rows[r].spec.list;
        setClass(rows[r].nodes[i], 'focus', r === row && (list ? i === col : (rows[r].spec.button ? true : i === col)));
      }
    }
  }

  /** Picks option `i` of row `r`: shown at once (home.js applies it), the check moves. */
  function pick(r, i) {
    var spec = rows[r].spec, o = spec.options[i];
    if (!o || spec.value === o[0]) return;
    spec.value = o[0];
    for (var k = 0; k < rows[r].nodes.length; k++) setClass(rows[r].nodes[k], 'picked', k === i);
    if (spec.set) spec.set(o[0]);
  }

  function go(to) {
    if (to < 0) return;
    if (to >= STEPS) { finish(); return; }
    step = to;
    build();
    if (!Motion.instant) { slide.snap(1); slide.set(0); }
    Motion.kick();
  }

  function finish() {
    open = false;
    show.tune(Motion.P.SHEET_CLOSE).set(0);
    Motion.kick();
    if (opts.done) opts.done();
  }

  // MARK: - Motion

  function draw() {
    var a = show.x;
    layer.style.opacity = a;
    layer.style.visibility = a > 0.002 ? 'visible' : 'hidden';
    if (card) Motion.transform(card, 'scale(' + (1.03 - 0.03 * a).toFixed(4) + ')');
    if (content) {
      Motion.transform(content, 'translateX(' + (48 * slide.x).toFixed(1) + 'px)');
      content.style.opacity = (1 - slide.x).toFixed(3);
    }
  }

  function tick(dt) {
    if (!show) return false;
    var moving = show.step(dt);
    if (slide.step(dt)) moving = true;
    draw();
    return moving;
  }

  // MARK: - API

  return {
    /**
     * Shows the setup in `node` (#setup). o: {theme(v), glass(v), topShelf(v), temperature(v): apply a choice;
     * people(): [{name, avatar}]; city(): the city's name; done(): after "Go to Home Screen"}.
     */
    show: function (node, o) {
      layer = node;
      opts = o;
      layer.innerHTML = '';
      el('div', 'hz hz-day', layer);
      el('div', 'hz hz-night', layer);
      card = el('div', 'card', layer);
      content = el('div', 'content', card);
      if (!show) {
        show = Motion.spring(Motion.P.SHEET_OPEN, 0);
        slide = Motion.spring(Motion.P.TITLE_SETTLE, 0);
        Motion.tick(tick);
      }
      step = 0;
      build();
      open = true;
      show.tune(Motion.P.SHEET_OPEN).set(1);
      slide.snap(0);
      draw();
      Motion.kick();
    },
    showing: function () { return open; },
    /** The step shown (0–3), for the tests and the screenshots; go(n) jumps to one. */
    step: function () { return step; },
    go: function (n) { if (open) go(n); },
    /** A key while it shows: it takes them all. */
    key: function (k, keys) {
      var r = rows[row];
      if (!r) return true;
      if (k === keys.left || k === keys.right) {
        if (r.spec.options && !r.spec.list) {
          var c = Math.max(0, Math.min(r.nodes.length - 1, col + (k === keys.right ? 1 : -1)));
          if (c !== col) { col = c; pick(row, col); }
        }
      } else if (k === keys.up || k === keys.down) {
        var dy = k === keys.down ? 1 : -1;
        if (r.spec.list && col + dy >= 0 && col + dy < r.nodes.length) col += dy;
        else if (row + dy >= 0 && row + dy < rows.length) {
          row += dy;
          col = rows[row].spec.list ? (dy > 0 ? 0 : rows[row].nodes.length - 1) : current(rows[row]);
        }
      } else if (k === keys.ok) {
        if (r.spec.button) go(step + 1);
        else if (r.spec.list) pick(row, col);
        else if (row + 1 < rows.length) { row++; col = current(rows[row]); }
      } else if (k === keys.back || k === 27 || k === 8) {
        go(step - 1);
      }
      focus();
      return true;
    }
  };
})();
