/*
 * mahitTV Settings, drawn as Apple TV's Settings (the approved plan, Step 2E): full screen over the Frosted Horizon, a
 * left pane with the section's emblem (a glass squircle), its title and a line about it, and the list of sections as
 * capsule pills; a right pane with the section's title and its rows in grouped inset glass, value labels, chevrons on
 * rows that open something, tvOS switches, and a footer under a group. A choice (Theme, Screensaver…) pushes a list
 * with a check on the current one. home.js owns the state and the rows; this file only draws them and moves the focus.
 *
 *   Settings.render(node, model): draws the model (a section, its rows, or a choice list)
 *   Settings.focus(node, model): moves the focus and the scroll only (cheap: no new elements)
 *
 * model: {cats: [{label, symbol}], cat, pane: 'cats' | 'rows' | 'choice', emblem: {symbol, avatar, title, desc},
 *         title, groups: [{header, footer, rows: [{label, value, toggle, chevron, dim, check}]}], row,
 *         hover(kind, index), click(kind, index)}
 * Only transform and opacity animate; the scroll and the switches' knobs move on springs (motion.js).
 */
var Settings = (function () {
  'use strict';

  var ROW = 64, VIEW = 824, KNOB = 26;
  var scroll = null, scrollEl = null, knobs = {}, was = {};
  // The dead end: the focused pill or row tugs toward the press and springs back (BOUNDARY_BUMP, rubber-banded).
  var nudge = null, nudgeEl = null, nudgeX = 0, nudgeY = 0, nudgeScale = 1;

  function el(tag, cls, parent, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    if (parent) parent.appendChild(e);
    return e;
  }

  function has(e, c) { return (' ' + e.className + ' ').indexOf(' ' + c + ' ') >= 0; }

  function setClass(e, c, on) {
    if (!e) return;
    if (on && !has(e, c)) e.className = (e.className + ' ' + c).replace(/^\s+/, '');
    else if (!on && has(e, c)) e.className = (' ' + e.className + ' ').replace(' ' + c + ' ', ' ').replace(/^\s+|\s+$/g, '');
  }

  function sym(name, parent, size) {
    var s = el('span', 'sym', parent);
    s.style.webkitMaskImage = 'url(symbols/' + name + '.png)';
    s.style.maskImage = 'url(symbols/' + name + '.png)';
    if (size) { s.style.width = size + 'px'; s.style.height = size + 'px'; }
    return s;
  }

  // The right pane scrolls on a spring; the switches' knobs slide on SNAPPY_FOCUS.
  function tick() {
    var moving = false;
    if (scroll && scrollEl) Motion.transform(scrollEl, 'translateY(' + (Math.round(-scroll.x * 10) / 10) + 'px)');
    if (nudgeEl) {
      var d = Motion.rubber(nudge.x);
      if (nudge.x === 0 && nudge.v === 0) clearNudge();
      else {
        moving = true;
        Motion.transform(nudgeEl, 'translate(' + (Math.round(nudgeX * d * 10) / 10) + 'px, ' + (Math.round(nudgeY * d * 10) / 10) + 'px) scale(' + nudgeScale + ')');
      }
    }
    for (var k in knobs) {
      if (!knobs.hasOwnProperty(k)) continue;
      var kn = knobs[k];
      if (!kn.el.parentNode) continue;
      if (kn.s.x !== kn.s.to) moving = true;
      Motion.transform(kn.el, 'translateX(' + (Math.round(kn.s.x * KNOB * 10) / 10) + 'px)');
    }
    return moving;
  }
  var ticking = false;
  function start() {
    if (!ticking) {
      ticking = true;
      Motion.tick(function (dt) {
        var moving = false;
        if (scroll && scroll.step(dt)) moving = true;
        if (nudge && nudgeEl && nudge.step(dt)) moving = true;
        for (var k in knobs) if (knobs.hasOwnProperty(k) && knobs[k].s.step(dt)) moving = true;
        return tick() || moving;
      });
    }
    Motion.kick();
  }

  function clearNudge() {
    if (!nudgeEl) return;
    nudgeEl.style.webkitTransform = nudgeEl.style.transform = '';
    nudgeEl.style.webkitTransition = nudgeEl.style.transition = '';
    nudgeEl = null;
  }

  /** Nothing that way: the focused pill or row tugs toward the press (dx, dy; 0, 0 for a row that does nothing). */
  function bump(node, dx, dy) {
    var target = node.querySelector('.cat.focus') || node.querySelector('.srow.focus');
    if (!target || Motion.instant) return;
    if (!nudge) nudge = Motion.spring(Motion.P.BOUNDARY_BUMP, 0).px();
    if (nudgeEl !== target) clearNudge();
    nudgeEl = target;
    nudgeX = dx;
    nudgeY = dy;
    nudgeScale = has(target, 'cat') ? 1.04 : 1.02;
    if (!dx && !dy) { nudgeScale *= 0.985; nudgeY = 0; }
    target.style.webkitTransition = target.style.transition = 'background-color 0.12s, box-shadow 0.2s';
    nudge.snap(0);
    nudge.impulse(Motion.BUMP_VELOCITY);
    start();
  }

  function render(node, m) {
    clearNudge();
    node.innerHTML = '';
    setClass(node, 'tvs', true);
    el('div', 'hz hz-day', node);
    el('div', 'hz hz-night', node);
    el('div', 'veil', node);

    // The left pane: the emblem, the title, a line about the section, then the sections.
    var left = el('div', 'left', node), em = el('div', 'emblem', left);
    if (m.emblem.avatar) {
      var face = el('div', 'face', em);
      face.style.backgroundImage = 'url(' + m.emblem.avatar + ')';
    } else sym(m.emblem.symbol, em, 84);
    el('div', 'etitle', left, m.emblem.title);
    el('div', 'edesc', left, m.emblem.desc);
    var cats = el('div', 'cats', left);
    for (var i = 0; i < m.cats.length; i++) {
      var cat = el('div', 'cat', cats);
      sym(m.cats[i].symbol, cat, 30);
      el('span', 'label', cat, m.cats[i].label);
      wire(cat, m, 'cat', i);
    }

    // The right pane: the title, then the groups (or the choice list), scrolling under a fixed title.
    var right = el('div', 'right', node);
    el('div', 'rtitle', right, m.title);
    var view = el('div', 'view', right);
    scrollEl = el('div', 'scroll', view);
    var flat = 0, seen = {};
    for (var g = 0; g < m.groups.length; g++) {
      var grp = m.groups[g];
      if (grp.header) el('div', 'gh', scrollEl, grp.header);
      var box = el('div', 'gbox', scrollEl);
      for (var r = 0; r < grp.rows.length; r++) {
        var row = grp.rows[r], line = el('div', 'srow' + (row.dim ? ' dim' : ''), box);
        line.flat = flat;
        el('span', 'label', line, row.label);
        var right2 = el('span', 'end', line);
        if (row.toggle !== undefined) {
          var sw = el('span', 'switch' + (row.toggle ? ' on' : ''), right2), knob = el('span', 'knob', sw);
          // A switch that just changed slides from where it was; one drawn for the first time sits still.
          var key = m.title + '|' + row.label, prev = was[key];
          var k = knobs[key] || (knobs[key] = {s: Motion.spring(Motion.P.SNAPPY_FOCUS, row.toggle ? 1 : 0)});
          k.el = knob;
          if (prev === undefined || Motion.instant) k.s.snap(row.toggle ? 1 : 0);
          else k.s.set(row.toggle ? 1 : 0);
          was[key] = row.toggle;
          seen[key] = true;
          Motion.transform(knob, 'translateX(' + (k.s.x * KNOB) + 'px)');
        } else {
          if (row.value !== undefined && row.value !== null && row.value !== '') el('span', 'value', right2, row.value);
          if (row.check) sym('check', right2, 26).className += ' check';
          if (row.chevron) sym('chevron_right', right2, 20);
        }
        wire(line, m, 'row', flat);
        flat++;
      }
      if (grp.footer) el('div', 'gf', scrollEl, grp.footer);
    }
    for (var key2 in knobs) if (knobs.hasOwnProperty(key2) && !seen[key2] && knobs[key2].el && !knobs[key2].el.parentNode) delete knobs[key2];
    var keep = scroll ? scroll.x : 0;
    scroll = Motion.spring(Motion.P.SNAPPY_FOCUS, keep).px();
    focus(node, m);
    if (m.fresh) scroll.snap(scroll.to);
    start();
  }

  function wire(e, m, kind, index) {
    e.onmouseover = function () { if (m.hover) m.hover(kind, index); };
    e.onclick = function () { if (m.click) m.click(kind, index); };
  }

  function focus(node, m) {
    var cats = node.querySelectorAll('.cat');
    for (var i = 0; i < cats.length; i++) {
      setClass(cats[i], 'focus', m.pane === 'cats' && i === m.cat);
      setClass(cats[i], 'current', m.pane !== 'cats' && i === m.cat);
    }
    var rows = node.querySelectorAll('.srow'), focused = null;
    for (i = 0; i < rows.length; i++) {
      var on = m.pane !== 'cats' && rows[i].flat === m.row;
      setClass(rows[i], 'focus', on);
      if (on) focused = rows[i];
    }
    setClass(node, 'on-cats', m.pane === 'cats');
    // The scroll keeps the focused row three rows from the top while there's more below.
    if (scroll && scrollEl) {
      var content = scrollEl.offsetHeight, to = 0;
      if (focused && content > VIEW) {
        var top = focused.offsetTop + focused.parentNode.offsetTop;
        to = Math.max(0, Math.min(content - VIEW, top - 3 * (ROW + 8)));
      }
      if (m.pane === 'cats') to = 0;
      scroll.set(to);
      start();
    }
  }

  return {render: render, focus: focus, bump: bump};
})();
