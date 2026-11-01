/*
 * The weather card (Control Center's weather platter → OK): Apple Weather's card, floating in Liquid Glass over the
 * home screen (x 140–860, y 120–960, radius 40), the home dimmed behind it. The city, the temperature now (64 px light),
 * the sky and the day's range; the next 6 hours (Now and 5 more, rain in sky blue); then 7 days, each with its range as
 * a yellow-to-orange bar on the week's scale, a white dot at the temperature now on Today. Back or OK closes it, ← →
 * move along the hours. Adapted from tvOS-Launcher's WeatherView (Apache-2.0). Only transform and opacity animate;
 * everything is built when it opens, nothing while it moves.
 */
var Forecast = (function () {
  'use strict';

  var HOUR_W = 104, HOURS_SHOWN = 6, BAR_W = 196;
  var layer = null, panel = null, strip = null, cols = [], focus = 0, first = 0, open = false;
  var show = null, scroll = null, onClose = null;

  function el(tag, cls, parent, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
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

  function percent(n) { return n === null || n === undefined || isNaN(n) ? '' : trf('{n}%', {n: Math.round(n)}); }

  // MARK: - Building it

  function build() {
    layer.innerHTML = '';
    el('div', 'veil', layer);
    panel = el('div', 'panel', layer);
    var w = Data.weather;
    if (!w) {
      el('div', 'empty', panel, tr('No weather data right now.'));
      cols = [];
      return;
    }
    // Now: the city, the temperature, the sky and the day's range; humidity, wind, sunrise and sunset to the right.
    var now = el('div', 'now', panel);
    el('div', 'city', now, Data.city || tr('Weather'));
    var big = el('div', 'big', now);
    sym(Data.symbol(w.code, w.isDay), big);
    el('span', 'temp', big, Data.degrees(w.temp));
    el('div', 'cond', now, Data.condition(w.code, w.isDay));
    el('div', 'range', now, trf('H:{max}  L:{min}', {max: Data.degrees(w.max), min: Data.degrees(w.min)}));
    var stats = el('div', 'stats', panel);
    stat(stats, tr('Humidity'), percent(w.humidity) || '—');
    stat(stats, tr('Wind'), Data.wind(w.wind));
    stat(stats, tr('Sunrise'), w.sunrise ? Data.time(w.sunrise) : '—');
    stat(stats, tr('Sunset'), w.sunset ? Data.time(w.sunset) : '—');
    // The next 6 hours: Now and the 5 after it (one label each, also the night the clocks go back: data.js).
    var hours = el('div', 'hours', panel);
    el('div', 'head', hours, tr('Hourly Forecast'));
    var view = el('div', 'view', hours);
    strip = el('div', 'strip', view);
    cols = [];
    var list = (w.hours24 || []).slice(0, HOURS_SHOWN), i;
    for (i = 0; i < list.length; i++) {
      var h = list[i], col = el('div', 'hour', strip);
      col.style.left = (i * HOUR_W) + 'px';
      el('div', 't', col, h.now ? tr('Now') : Data.hour(h.at));
      sym(Data.symbol(h.code, h.day), col);
      el('div', 'r', col, h.rain >= 20 ? percent(h.rain) : '');
      el('div', 'd', col, Data.degrees(h.temp));
      cols.push(col);
    }
    // 7 days: each its range as a bar on the week's scale, yellow to orange (#FACC15 → #FB923C), as Apple's Weather.
    var week = el('div', 'week', panel);
    el('div', 'head', week, tr('7-Day Forecast'));
    var days = w.days7 || [], lo = Infinity, hi = -Infinity;
    for (i = 0; i < days.length; i++) { lo = Math.min(lo, days[i].min); hi = Math.max(hi, days[i].max); }
    var span = Math.max(1, hi - lo);
    for (i = 0; i < days.length; i++) {
      var d = days[i], row = el('div', 'day', week);
      el('span', 'name', row, d.today ? tr('Today') : Data.weekday(d.at));
      var icon = el('span', 'icon', row);
      sym(Data.symbol(d.code, true), icon);
      el('span', 'rain', row, d.rain >= 20 ? percent(d.rain) : '');
      el('span', 'min', row, Data.degrees(d.min));
      var track = el('span', 'track', row), fill = el('span', 'fill', track);
      var left = (d.min - lo) / span * BAR_W, width = Math.max(12, (d.max - d.min) / span * BAR_W);
      fill.style.left = Math.round(left) + 'px';
      fill.style.width = Math.round(width) + 'px';
      if (d.today && w.temp !== undefined) {
        var dot = el('span', 'dot', track);
        dot.style.left = Math.round(Math.max(0, Math.min(BAR_W, (w.temp - lo) / span * BAR_W)) - 6) + 'px';
      }
      el('span', 'max', row, Data.degrees(d.max));
    }
  }

  function stat(parent, label, value) {
    var s = el('div', 'stat', parent);
    el('div', 'label', s, label);
    el('div', 'value', s, value);
  }

  function highlight() {
    for (var i = 0; i < cols.length; i++) setClass(cols[i], 'focus', i === focus);
    if (focus < first) first = focus;
    else if (focus >= first + HOURS_SHOWN) first = focus - HOURS_SHOWN + 1;
    if (scroll) scroll.set(-first * HOUR_W);
  }

  // MARK: - Motion

  function draw() {
    var a = show.x;
    layer.style.opacity = a;
    layer.style.visibility = a > 0.002 ? 'visible' : 'hidden';
    if (panel) Motion.transform(panel, 'translateX(' + (60 * (1 - a)).toFixed(1) + 'px) scale(' + (0.94 + 0.06 * a).toFixed(4) + ')');
    if (strip) Motion.transform(strip, 'translateX(' + scroll.x.toFixed(1) + 'px)');
  }

  function tick(dt) {
    if (!show) return false;
    var moving = show.step(dt);
    if (scroll.step(dt)) moving = true;
    draw();
    return moving;
  }

  // MARK: - API

  return {
    /** Opens in `node` (the #forecast layer); `closed` runs when Back closes it. */
    open: function (node, closed) {
      layer = node;
      onClose = closed || null;
      if (!show) {
        show = Motion.spring(Motion.P.SHEET_OPEN, 0);
        scroll = Motion.spring(Motion.P.SPATIAL_SCROLL, 0).px();
        Motion.tick(tick);
      }
      build();
      focus = first = 0;
      scroll.snap(0);
      highlight();
      open = true;
      show.tune(Motion.P.SHEET_OPEN).set(1);
      draw();
      Motion.kick();
    },
    close: function () {
      if (!open) return;
      open = false;
      show.tune(Motion.P.SHEET_CLOSE).set(0);
      Motion.kick();
      if (onClose) onClose();
    },
    showing: function () { return open; },
    /** A key while it shows: true when it took it (it takes them all). */
    key: function (k, keys) {
      if (k === keys.left || k === keys.right) {
        var next = focus + (k === keys.right ? 1 : -1);
        if (next >= 0 && next < cols.length) { focus = next; highlight(); }
        else if (cols.length) scroll.impulse((k === keys.right ? -1 : 1) * Motion.BUMP_VELOCITY * 0.4);
      } else if (k === keys.back || k === 27 || k === 8 || k === keys.ok) {
        Forecast.close();
      }
      return true;
    }
  };
})();
