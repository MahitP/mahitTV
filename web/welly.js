/*
 * The Welly tab: a witty roommate who runs the TV (WELLY-HOME-REMOTE-SPEC.md §3, ANIMATIONS-SPEC.md §4). Welly's orb
 * sits centre stage over the greeting while idle, listening and thinking; when an answer arrives it shrinks beside
 * "Welly · mood", the reply is revealed word by word and its cards (plain fills) rise out of the orb. Below: a row of
 * prompt pills and an "Ask Welly…" capsule with the mic. With a Gemini key (gemini_key) or an Ollama on the LAN
 * (ollama_url) it asks them, OpenAI-style with tools; the preview answers the pills from app/assets/welly-preview.json.
 * The web app has fewer tools than the Android app: no Kasa (TVs can't open raw sockets), no media sessions. Motion
 * runs on motion.js's springs (the same as the Android app's WellyView).
 */
(function () {
  'use strict';

  var P = window.WELLY_PREVIEW || {pills: []};
  var GEMINI = 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions';
  var TIMEOUT = 6000, STICKY = 10 * 60 * 1000;

  // The pills' words, as literal tr() calls so tools/translate.py finds them (shared with the Android app).
  var PROMPTS = {
    'watching': tr('What am I watching?'),
    'movie-mode': tr('Dim my room & pick a movie'),
    'scores': tr('Live sports scores'),
    'weather': tr('South Brunswick weather'),
    'tonight': tr('What’s on tonight?'),
    'roast': tr('Roast my watchlist')
  };
  var MOODS = {'Curious': tr('Curious'), 'Smug': tr('Smug'), 'Sleepy': tr('Sleepy'), 'On it': tr('On it'), 'Embarrassed': tr('Embarrassed')};
  // While thinking, a line that follows the tool at work (the same lines as the Android app).
  var STATUS = {
    weather: tr('Checking South Brunswick skies…'),
    titles: tr('Judging your watchlist…'),
    scores: tr('Squinting at the scoreboard…'),
    lights: tr('Finding the light switch…'),
    on: tr('Flipping through the channels…'),
    other: tr('Thinking it over…')
  };
  var TOOL_STATUS = {weather: 'weather', search_titles: 'titles', open_title: 'titles', recap: 'titles', cast_crossref: 'titles',
    scores: 'scores', lights: 'lights', scene: 'lights', whats_on: 'on'};
  var PILL_TOOL = {'watching': 'whats_playing', 'movie-mode': 'lights', 'scores': 'scores', 'weather': 'weather', 'tonight': 'whats_on', 'roast': 'search_titles'};

  // Layout (stage px): the orb centre stage, the lines under it, and the docked orb beside "Welly · mood".
  var MID = 960, ORB_Y = 296, ORB_R = 104, DOCK_X = 391, DOCK_Y = 232, DOCK_R = 21;
  var ORB_SIZE = 500, WHO_DOCK_LEFT = 426, WHO_DOCK_TOP = 222, WHO_FRESH_TOP = 528, CENTRE_TOP = 438;
  var CARDS_LEFT = 370, CARDS_TOP = 568, CARD_H = 240, WORDS_PER_SECOND = 28, CARD_STAGGER = 0.045, TILT_PUSH = 90;
  var BLOB = ['100,210,255', '191,90,242', '255,159,10', '10,132,255'];
  var RIM = ['#FF9F0A', '#FF375F', '#BF5AF2', '#0A84FF', '#64D2FF', '#FF9F0A'];

  var opts, root, els = {}, row = 'pills', index = 0, cards = [], pills = [], mood = P.mood || 'Curious';
  var shown = false, focused = false, busy = false, listening = false, recognizer = null, history = [], skipUntil = {};
  var thinkingNow = false, userWords = '';

  // Motion (ANIMATIONS-SPEC.md §1, §4).
  var S = Motion.P;
  var dock = Motion.spring(S.ORB, 0), listenS = Motion.spring(S.ORB, 0), think = Motion.spring(S.ORB, 0);
  var speak = Motion.spring(S.ORB, 0), level = new Motion.Spring(0.12, 1, 0), press = Motion.spring(S.MICRO_PRESS, 0);
  var nudge = Motion.spring(S.BOUNDARY_BUMP, 0).px(), pillScroll = new Motion.Spring(0.34, 1, 0).px(), fly = Motion.spring(S.TAB_SWAP, 0);
  var pillFocus = [], cardFocus = [], cardTilt = [], cardEnter = [], enterAt = [], askFocus = Motion.spring(S.SNAPPY_FOCUS, 0);
  var nudgeX = true, pressAt = 0, seconds = 0, orbPhase = 0, ringPhase = 0, nodeAngle = 0, orbX = MID, orbY = ORB_Y;
  var words = [], shownWords = 0, revealAt = 0, flying = false, flyFrom = null, orbCtx = null, orbDrawn = false;
  var grads = null, everySpring = [dock, listenS, think, speak, level, press, nudge, pillScroll, askFocus];

  function el(tag, cls, parent, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    if (parent) parent.appendChild(e);
    return e;
  }

  function sym(name, parent, size) {
    var s = el('span', 'sym', parent);
    s.style.webkitMaskImage = 'url(symbols/' + name + '.png)';
    s.style.maskImage = 'url(symbols/' + name + '.png)';
    if (size) { s.style.width = size + 'px'; s.style.height = size + 'px'; }
    return s;
  }

  function preview() { return !!window.PREVIEW_PICKS && !CONFIG.geminiKey && !CONFIG.ollamaUrl; }
  function lerp(a, b, t) { return a + (b - a) * t; }
  function clamp01(v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
  function opacity(node, a) { node.style.opacity = a > 0.999 ? '' : String(Math.round(a * 1000) / 1000); }

  // MARK: - Layout

  function build() {
    root.innerHTML = '';
    els.orb = el('canvas', 'orb', root);
    els.orb.width = ORB_SIZE;
    els.orb.height = ORB_SIZE;
    orbCtx = els.orb.getContext && els.orb.getContext('2d');
    grads = null;
    // The lines under the orb: each a pair of slots, so a change crossfades.
    els.lines = el('div', 'lines', root);
    els.centre = [el('div', 'centre', els.lines), el('div', 'centre', els.lines)];
    els.below = [el('div', 'below', els.lines), el('div', 'below', els.lines)];
    els.centreAt = 0;
    els.belowAt = 0;
    var col = el('div', 'col', root);
    els.who = el('div', 'who', root);
    els.exchange = el('div', 'exchange', col);
    els.you = el('div', 'you', els.exchange);
    els.reply = el('div', 'reply', els.exchange);
    els.cards = el('div', 'cards', root);
    els.pillsBox = el('div', 'pills', root);
    els.pills = el('div', 'strip', els.pillsBox);
    els.ask = el('div', 'ask', root);
    el('div', 'edge', els.ask);
    var fill = el('div', 'fill', els.ask);
    els.mic = el('div', 'mic', fill);
    sym('mic', els.mic, 30);
    els.field = el('input', 'field', fill);
    els.field.type = 'text';
    // The placeholder as two lines that crossfade ("Ask Welly…" ↔ "Listening…").
    els.phAsk = el('div', 'ph on', fill, tr('Ask Welly…'));
    els.phListen = el('div', 'ph', fill, tr('Listening…'));
    els.field.oninput = function () { placeholders(); };
    els.fly = el('div', 'flypill', root);
    els.flyBg = el('div', 'bg', els.fly);
    els.flyText = el('div', 't', els.fly);
    pills = [];
    pillFocus = [];
    for (var i = 0; i < P.pills.length; i++) {
      var p = P.pills[i], b = el('div', 'pill', els.pills, PROMPTS[p.id] || p.prompt);
      pills.push({node: b, data: p});
      pillFocus.push(Motion.spring(S.SNAPPY_FOCUS, 0));
      pointer(b, 'pills', i);
    }
    pointer(els.mic, 'input', 0);
    pointer(els.field, 'input', 1);
    renderWho();
    greet();
    Motion.tick(frame);
  }

  function pointer(node, r, i) {
    node.onmouseover = function () { if (!focused) return; row = r; index = i; paint(); };
    node.onclick = function () { row = r; index = i; paint(); Welly.ok(); };
  }

  function renderWho() {
    var line = 'Welly  ·  ' + (MOODS[mood] || mood);
    if (preview()) line += '  ·  ' + tr('Preview');
    els.who.textContent = line;
    els.whoW = els.who.offsetWidth || 300;
  }

  function placeholders() {
    var typed = !!els.field.value;
    setClass(els.phAsk, 'on', !typed && !listening);
    setClass(els.phListen, 'on', !typed && listening);
  }

  /** Back to the start: the orb centre stage, the greeting, no exchange. */
  function greet() {
    userWords = '';
    els.you.textContent = '';
    els.reply.textContent = '';
    words = [];
    shownWords = 0;
    setCards([]);
    dock.set(0);
    lines();
  }

  /** What the two lines under the orb say now; a change crossfades (a short CSS fade between two slots). */
  function lines() {
    var c, b = '', big = false;
    if (listening) c = (els.field.value || '') || tr('Listening…');
    else if (thinkingNow) { c = userWords; b = els.status || STATUS.other; }
    else if (!userWords) { c = trf('Hey {name}.', {name: opts.name()}); big = true; b = tr('Ask me anything, or pick one below.'); }
    else c = userWords;
    swap(els.centre, 'centreAt', c, big);
    swap(els.below, 'belowAt', b, false);
  }

  function swap(slots, at, text, big) {
    var cur = slots[els[at]];
    if (cur.textContent === text && hasClass(cur, 'big') === big) return;
    var next = slots[1 - els[at]];
    next.textContent = text;
    setClass(next, 'big', big);
    setClass(cur, 'on', false);
    setClass(next, 'on', !!text);
    els[at] = 1 - els[at];
  }

  // MARK: - Focus

  function rows() {
    var out = [];
    if (cards.length) out.push('cards');
    out.push('pills', 'input');
    return out;
  }

  function size(r) { return r === 'cards' ? cards.length : r === 'pills' ? pills.length : 2; }

  function paint() {
    var i;
    for (i = 0; i < cardFocus.length; i++) cardFocus[i].set(focused && row === 'cards' && index === i ? 1 : 0);
    for (i = 0; i < pills.length; i++) {
      var on = focused && row === 'pills' && index === i;
      setOn(pills[i].node, on);
      pillFocus[i].set(on ? 1 : 0);
    }
    var list = els.cards.children;
    for (i = 0; i < list.length; i++) setOn(list[i], focused && row === 'cards' && index === i);
    setOn(els.mic, focused && row === 'input' && index === 0);
    setOn(els.ask, focused && row === 'input' && index === 1);
    askFocus.set(focused && row === 'input' && index === 1 ? 1 : 0);
    // The pills scroll only as far as it takes to show the focused one, as a tvOS row does.
    if (row === 'pills' && pills[index]) {
      var left = pills[index].node.offsetLeft, width = pills[index].node.offsetWidth, x = pillScroll.to;
      if (left - x < 0) x = left;
      else if (left + width - x > 1180) x = left + width - 1180;
      pillScroll.set(x);
    }
    setClass(els.pillsBox, 'more-l', pillScroll.to > 0);
    setClass(els.pillsBox, 'more-r', els.pills.offsetWidth - pillScroll.to > 1190);
    Motion.kick();
  }

  function setOn(node, on) { setClass(node, 'focus', on); }

  function hasClass(node, name) { return (' ' + node.className + ' ').indexOf(' ' + name + ' ') >= 0; }

  function setClass(node, name, on) {
    var c = ' ' + node.className + ' ', has = c.indexOf(' ' + name + ' ') >= 0;
    if (on && !has) node.className += ' ' + name;
    else if (!on && has) node.className = c.replace(' ' + name + ' ', ' ').replace(/^\s+|\s+$/g, '');
  }

  /** No neighbour that way: a short rubber-band tug of the focused thing. */
  function bump(dir, alongX) {
    if (Motion.instant) return;
    nudgeX = alongX;
    nudge.impulse(dir * Motion.BUMP_VELOCITY);
  }

  // MARK: - Cards (plain fills, one focus lift; they rise out of the orb)

  function setCards(list) {
    cards = list;
    els.cards.innerHTML = '';
    cardFocus = [];
    cardTilt = [];
    cardEnter = [];
    enterAt = [];
    for (var i = 0; i < list.length; i++) {
      drawCard(list[i], i);
      cardFocus.push(Motion.spring(S.SNAPPY_FOCUS, 0));
      cardTilt.push(Motion.spring(S.TILT, 0).deg());
      cardEnter.push(Motion.spring(S.CARD_ENTER, Motion.instant ? 1 : 0));
      enterAt.push(seconds + 0.16 + i * CARD_STAGGER);   // once the orb is on its way to the header
    }
    if (row === 'cards' && !list.length) { row = 'pills'; index = 0; }
    if (row === 'cards') index = Math.min(index, list.length - 1);
    Motion.kick();
  }

  function pickFor(id) {
    var picks = window.PREVIEW_PICKS || [];
    for (var i = 0; i < picks.length; i++) if (picks[i].backdrop.indexOf('/' + id + '.') >= 0) return picks[i];
    return null;
  }

  function drawCard(c, i) {
    var box = el('div', 'card ' + c.type, els.cards);
    box.style.opacity = '0';
    pointer(box, 'cards', i);
    if (c.type === 'media') {
      var p = c.pickData || pickFor(c.pick);
      if (!p) return;
      box.style.backgroundImage = 'url(' + p.backdrop + ')';
      el('div', 'scrim', box);
      el('div', 't', box, p.title);
      el('div', 'm', box, [p.year, p.meta].filter(Boolean).join('  ·  '));
      c.pickData = p;
    } else if (c.type === 'score') {
      el('div', 'league', box, c.league);
      team(box, c.away, c.awayScore, c.awayScore > c.homeScore);
      team(box, c.home, c.homeScore, c.homeScore > c.awayScore);
      el('div', 'status', box, c.status);
    } else if (c.type === 'weather') {
      el('div', 'place', box, c.place);
      var now = el('div', 'now', box);
      sym(symbolFor(c.code, true), now, 54);
      el('span', 'temp', now, c.temp + '°');
      el('div', 'range', box, trf('H:{max}', {max: c.high + '°'}) + '  ' + trf('L:{min}', {min: c.low + '°'}));
      var hours = el('div', 'hours', box);
      for (var h = 0; c.hours && h < c.hours.length && h < 4; h++) {
        var cell = el('div', 'hour', hours);
        el('div', 'at', cell, c.hours[h][0]);
        sym(symbolFor(c.hours[h][1], false), cell, 28);
        el('div', 'd', cell, c.hours[h][2] + '°');
      }
    } else if (c.type === 'schedule') {
      for (var s = 0; s < c.items.length && s < 5; s++) {
        var line = el('div', 'line', box);
        el('span', 'at', line, c.items[s][0]);
        el('span', 'show', line, c.items[s][1]);
        el('span', 'net', line, c.items[s][2]);
      }
    } else if (c.type === 'home') {
      var on = window.House ? House.state(c.fixture) : c.on;
      box.className += on ? ' lit' : '';
      el('div', 'bulb', box);
      el('div', 'room', box, window.House ? House.roomName(House.roomOf(c.fixture)) : c.fixture);
      el('div', 'fix', box, window.House ? House.fixtureName(c.fixture) : '');
      el('div', 'state', box, on ? tr('On') : tr('Off'));
    }
  }

  /** A home card flips with its light (OK on it): its state changes in place, no new entrance. */
  function refreshHomeCard(i) {
    var c = cards[i], box = els.cards.children[i];
    if (!c || !box || c.type !== 'home') return;
    var on = window.House ? House.state(c.fixture) : c.on;
    setClass(box, 'lit', on);
    var st = box.querySelector('.state');
    if (st) st.textContent = on ? tr('On') : tr('Off');
  }

  function team(box, name, score, ahead) {
    var line = el('div', 'team' + (ahead ? ' ahead' : ''), box);
    el('span', 'name', line, name);
    el('span', 'score', line, String(score));
  }

  function symbolFor(code, day) {
    if (window.Data && Data.symbol) return Data.symbol(code, day);
    return day ? 'sunny' : 'clear_night';
  }

  // MARK: - Answers

  function say(you, reply, newMood, list) {
    thinkingNow = false;
    think.set(0);
    userWords = you || userWords;
    els.you.textContent = userWords;
    if (newMood) { mood = newMood; renderWho(); }
    // The reply, word by word (OK shows it all); with motion off, at once.
    words = (reply || '').split(/\s+/);
    revealAt = seconds;
    shownWords = Motion.instant ? words.length : 0;
    els.reply.textContent = Motion.instant ? reply : '';
    setCards(list || []);
    dock.set(1);
    lines();
    paint();
  }

  function thinking(you) {
    thinkingNow = true;
    userWords = you;
    think.set(1);
    dock.set(0);   // the orb comes back centre stage to think
    lines();
    paint();
  }

  /** The thinking line follows the tool at work (a pill's tool in the preview). */
  function status(tool) {
    els.status = STATUS[TOOL_STATUS[tool] || 'other'];
    if (thinkingNow) lines();
  }

  /** The pressed pill flies up to the line under the orb and becomes the question there. */
  function flyUp(i) {
    var node = pills[i].node;
    if (Motion.instant || !node) return;
    // Where the pill is on the stage: the row's box, its strip, the pill, minus the row's scroll.
    flyFrom = {x: 352 + 18 + node.offsetLeft - pillScroll.x, y: 832 + 13, w: node.offsetWidth, h: node.offsetHeight};
    els.flyText.textContent = node.textContent;
    els.fly.style.width = flyFrom.w + 'px';
    els.fly.style.height = flyFrom.h + 'px';
    flying = true;
    fly.snap(0).set(1);
    layout();   // in place at once, before it shows
    setClass(els.fly, 'on', true);
  }

  /** What the preview says to a pill (or to typed words that match one). */
  function previewAnswer(text) {
    var t = text.toLowerCase(), best = null;
    for (var i = 0; i < P.pills.length; i++) {
      var p = P.pills[i];
      if ((PROMPTS[p.id] || '').toLowerCase() === t || p.prompt.toLowerCase() === t) best = p;
    }
    if (!best) {
      var words2 = {watching: /watch(ing)?\b|playing/, 'movie-mode': /dim|movie|lights?/, scores: /score|game|knicks|jets/,
        weather: /weather|cold|rain|warm/, tonight: /tonight|on tv|schedule/, roast: /roast|watchlist/};
      for (i = 0; i < P.pills.length; i++) if (words2[P.pills[i].id] && words2[P.pills[i].id].test(t)) best = P.pills[i];
    }
    return best;
  }

  function ask(text) {
    text = (text || '').replace(/^\s+|\s+$/g, '');
    if (!text || busy) return;
    busy = true;
    var guess = preview() ? previewAnswer(text) : null;
    els.status = STATUS[TOOL_STATUS[guess ? PILL_TOOL[guess.id] : ''] || 'other'];
    thinking(text);
    if (preview() || (!CONFIG.geminiKey && !CONFIG.ollamaUrl)) {
      setTimeout(function () {
        busy = false;
        var p = guess;
        if (!p) {
          say(text, preview() ? tr('In Preview I can only answer the questions below.')
            : tr('I don\'t have a brain yet. Add a free Gemini key (gemini_key) or your Mac\'s Ollama (ollama_url) to config.properties, then build again.'), 'Embarrassed');
          return;
        }
        // The preview acts out what it says: the lights it names change on the Home tab too.
        for (var i = 0; i < p.cards.length; i++) if (p.cards[i].type === 'home' && window.House) House.set(p.cards[i].fixture, p.cards[i].on);
        say(text, p.reply, p.mood, p.cards.slice());
      }, 1300);
      return;
    }
    converse(text, function (reply, list, ok) {
      busy = false;
      say(text, reply, ok ? (list.length ? 'Smug' : 'Curious') : 'Embarrassed', list);
    });
  }

  // MARK: - Frames (motion.js runs this every frame while anything moves, or while the orb shows)

  function frame(dt, now) {
    if (!root) return false;
    var step = Math.min(dt, 1 / 30), moving = false, i;
    seconds += step;
    // Targets change here by assignment, not set(): set() kicks the loop, and a kick from inside a tick would start a
    // second loop (motion.js).
    if (press.to === 1 && seconds - pressAt > 0.09) press.to = 0;   // the squish, then back (OK has no key-up here)
    for (i = 0; i < everySpring.length; i++) if (everySpring[i].step(dt)) moving = true;
    for (i = 0; i < pillFocus.length; i++) if (pillFocus[i].step(dt)) moving = true;
    for (i = 0; i < cards.length; i++) {
      if (cardEnter[i].to === 0 && seconds >= enterAt[i]) cardEnter[i].to = 1;
      if (cardEnter[i].to === 0) moving = true;
      if (cardFocus[i].step(dt)) moving = true;
      if (cardTilt[i].step(dt)) moving = true;
      if (cardEnter[i].step(dt)) moving = true;
    }
    if (flying) {
      fly.step(dt);
      if (fly.x > 0.995 && fly.settled()) { flying = false; setClass(els.fly, 'on', false); }
      moving = true;
    }
    // The reply, word by word; Welly "speaks" while it shows.
    if (shownWords < words.length) {
      var n = Math.min(words.length, Math.floor((seconds - revealAt) * WORDS_PER_SECOND));
      if (n !== shownWords) { shownWords = n; els.reply.textContent = words.slice(0, n).join(' '); }
      moving = true;
    }
    speak.to = shownWords < words.length ? 1 : 0;
    // Browsers give no mic level: a speaking cadence stands in for it while listening.
    level.to = listening ? 0.35 + 0.3 * Math.sin(seconds * 9) * Math.sin(seconds * 2.3) + 0.2 * Math.sin(seconds * 17) : 0;
    var speed = 1 + 2.5 * think.x + 1.2 * listenS.x;
    orbPhase += step * speed;
    ringPhase += step * (0.7 + 1.6 * clamp01(level.x));
    nodeAngle += step * (2 * Math.PI / 1.6) * think.x;
    layout();
    var alive = shown && !Motion.instant;
    if (alive || !orbDrawn) drawOrb();
    return moving || alive || listening || thinkingNow;
  }

  /** Every spring onto the page: the orb's place, the lines, the exchange, the cards, the pills, the capsule. */
  function layout() {
    var d = clamp01(dock.x), i;
    var breath = Motion.instant ? 0 : 0.015 * Math.sin(seconds * 2 * Math.PI / 4);
    var pulse = Motion.instant ? 0 : 0.045 * Math.sin(seconds * 2 * Math.PI / 0.8) * think.x + 0.05 * Math.abs(Math.sin(seconds * 2 * Math.PI * 3.5)) * speak.x;
    // The canvas draws the listening growth itself; docking, breath and pulses scale it.
    var sc = lerp(ORB_R, DOCK_R, d) / ORB_R * (1 + breath + pulse);
    orbX = lerp(MID, DOCK_X, d);
    orbY = lerp(ORB_Y, DOCK_Y, d);
    Motion.transform(els.orb, 'translate(' + (orbX - ORB_SIZE / 2) + 'px,' + (orbY - ORB_SIZE / 2) + 'px) scale(' + sc + ')');
    // "Welly · mood": centred under the greeting, then beside the small orb.
    var fx = MID - els.whoW / 2 - WHO_DOCK_LEFT, fy = WHO_FRESH_TOP - WHO_DOCK_TOP;
    Motion.transform(els.who, 'translate(' + ((1 - d) * fx) + 'px,' + ((1 - d) * fy) + 'px)');
    opacity(els.lines, 1 - d);
    opacity(els.exchange, d);
    els.lines.style.visibility = d > 0.995 ? 'hidden' : '';
    // While a pill flies up, the line it becomes waits for it.
    opacity(els.centre[els.centreAt], flying ? Math.pow(clamp01(fly.x), 3) : 1);
    // Cards: out of the orb's centre (small) to their places, rising the last 28 px.
    var list = els.cards.children;
    for (i = 0; i < list.length && i < cards.length; i++) {
      var box = list[i], p = Math.max(0, cardEnter[i].x), pe = Math.min(1, p), mine = focused && row === 'cards' && index === i;
      var sx = CARDS_LEFT + box.offsetLeft + box.offsetWidth / 2, sy = CARDS_TOP + CARD_H / 2;
      var tx = lerp(orbX - sx, 0, pe) + (mine && nudgeX ? Motion.rubber(nudge.x) : 0), ty = lerp(orbY - sy, 0, pe) + 28 * (1 - pe);
      var s = (lerp(0.35, 0.94, pe) + 0.06 * clamp01((cardEnter[i].x - 0.8) / 0.2) * pe) * (1 + 0.10 * cardFocus[i].x) * (mine ? 1 - 0.065 * press.x : 1);
      var tilt = cardTilt[i].x;
      Motion.transform(box, 'translate(' + tx + 'px,' + ty + 'px) scale(' + s + ')' + (Math.abs(tilt) > 0.01 ? ' perspective(900px) rotateY(' + (-tilt) + 'deg)' : ''));
      opacity(box, d * clamp01(p * 1.6));
    }
    // Pills: focus, the press squish and the dead-end tug; the row's scroll.
    Motion.transform(els.pills, 'translateX(' + (-pillScroll.x) + 'px)');
    for (i = 0; i < pills.length; i++) {
      var on = focused && row === 'pills' && index === i, ps = (1 + 0.06 * pillFocus[i].x) * (on ? 1 - 0.065 * press.x : 1);
      Motion.transform(pills[i].node, (on && nudgeX ? 'translateX(' + Motion.rubber(nudge.x) + 'px) ' : '') + 'scale(' + ps + ')');
    }
    var inRow = focused && row === 'input', as = (1 + 0.02 * askFocus.x) * (inRow ? 1 - 0.03 * press.x : 1);
    var nx = inRow && nudgeX ? Motion.rubber(nudge.x) : 0, ny = inRow && !nudgeX ? Motion.rubber(nudge.x) : 0;
    Motion.transform(els.ask, 'translate(' + nx + 'px,' + ny + 'px) scale(' + as + ')');
    if (flying && flyFrom) {
      var fp = clamp01(fly.x), w = flyFrom.w, h = flyFrom.h;
      var cx = lerp(flyFrom.x + w / 2, MID, fp), cy = lerp(flyFrom.y + h / 2, CENTRE_TOP + 35, fp) - 40 * Math.sin(Math.PI * fp);
      Motion.transform(els.fly, 'translate(' + (cx - w / 2) + 'px,' + (cy - h / 2) + 'px)');
      opacity(els.flyBg, 1 - clamp01(fp / 0.7));
      Motion.transform(els.flyText, 'scale(' + lerp(1, 34 / 26, fp) + ')');
      var ink = Math.round(lerp(28, 255, fp));
      els.flyText.style.color = 'rgba(' + ink + ',' + ink + ',' + Math.round(lerp(30, 255, fp)) + ',' + (1 - clamp01((fp - 0.85) / 0.15)) + ')';
    }
  }

  // MARK: - The orb (a canvas: an aurora in a dark glass sphere, a colour rim, rings, thinking nodes)

  function gradients(c) {
    var g = {blobs: []}, i;
    g.halo = c.createRadialGradient(0, 0, 0, 0, 0, 1);
    g.halo.addColorStop(0, 'rgba(191,90,242,0.6)');
    g.halo.addColorStop(0.55, 'rgba(10,132,255,0.25)');
    g.halo.addColorStop(1, 'rgba(10,132,255,0)');
    for (i = 0; i < BLOB.length; i++) {
      var b = c.createRadialGradient(0, 0, 0, 0, 0, 1);
      b.addColorStop(0, 'rgba(' + BLOB[i] + ',0.9)');
      b.addColorStop(0.5, 'rgba(' + BLOB[i] + ',0.35)');
      b.addColorStop(1, 'rgba(' + BLOB[i] + ',0)');
      g.blobs.push(b);
    }
    g.spec = c.createRadialGradient(0, 0, 0, 0, 0, 1);
    g.spec.addColorStop(0, 'rgba(255,255,255,0.55)');
    g.spec.addColorStop(1, 'rgba(255,255,255,0)');
    g.node = c.createRadialGradient(0, 0, 0, 0, 0, 1);
    g.node.addColorStop(0, 'rgba(255,255,255,1)');
    g.node.addColorStop(0.35, 'rgba(255,255,255,0.5)');
    g.node.addColorStop(1, 'rgba(255,255,255,0)');
    return g;
  }

  function circle(c, x, y, r) { c.beginPath(); c.arc(x, y, r, 0, 2 * Math.PI); }

  /** Drawn at the full centre-stage size (the canvas is scaled down when docked), in its own px. */
  function drawOrb() {
    var c = orbCtx;
    if (!c) return;
    if (!grads) grads = gradients(c);
    orbDrawn = true;
    var lv = listenS.x, tv = think.x, R = ORB_R * (1 + 0.18 * lv), i;
    c.setTransform(1, 0, 0, 1, 0, 0);
    c.clearRect(0, 0, ORB_SIZE, ORB_SIZE);
    c.setTransform(1, 0, 0, 1, ORB_SIZE / 2, ORB_SIZE / 2);
    // The halo, wider while listening.
    var hr = R * (1.7 + 0.3 * lv);
    c.save();
    c.scale(hr, hr);
    c.globalAlpha = 0.55 + 0.35 * lv;
    c.fillStyle = grads.halo;
    circle(c, 0, 0, 1);
    c.fill();
    c.restore();
    c.globalAlpha = 1;
    // Rings that follow the voice.
    if (lv > 0.01) {
      c.lineWidth = 2;
      for (i = 0; i < 3; i++) {
        var f = (ringPhase + i / 3) % 1, lvl = clamp01(level.x);
        c.strokeStyle = 'rgba(' + (i === 1 ? '191,90,242' : '100,210,255') + ',' + ((1 - f) * 0.55 * lv * (0.35 + 0.65 * lvl)) + ')';
        circle(c, 0, 0, R * (1.05 + f * (0.35 + 0.5 * lvl)));
        c.stroke();
      }
    }
    // The sphere: dark glass, the aurora inside.
    c.fillStyle = 'rgba(18,20,32,0.94)';
    circle(c, 0, 0, R);
    c.fill();
    c.save();
    circle(c, 0, 0, R);
    c.clip();
    for (i = 0; i < grads.blobs.length; i++) {
      var a = orbPhase * (0.9 + 0.25 * i) + i * 1.7;
      var bx = 0.42 * Math.sin(a), by = 0.42 * Math.cos(a * 0.8 + i), br = 0.78 + 0.12 * Math.sin(a * 1.3 + i);
      c.save();
      c.translate(bx * R, by * R);
      c.scale(br * R, br * R);
      c.globalAlpha = 0.75 + 0.25 * lv;
      c.fillStyle = grads.blobs[i];
      circle(c, 0, 0, 1);
      c.fill();
      c.restore();
    }
    c.restore();
    c.globalAlpha = 1;
    // The glass highlight, top left.
    c.save();
    c.translate(-0.34 * R, -0.42 * R);
    c.scale(0.5 * R, 0.42 * R);
    c.fillStyle = grads.spec;
    circle(c, 0, 0, 1);
    c.fill();
    c.restore();
    // The rim: six colour arcs, turning; sharper and brighter while listening.
    var turn = seconds * (40 + 100 * lv) * Math.PI / 180;
    c.lineWidth = 1.5 + 2.5 * lv;
    c.globalAlpha = 0.35 + 0.65 * lv;
    for (i = 0; i < 6; i++) {
      c.strokeStyle = RIM[i];
      c.beginPath();
      c.arc(0, 0, R, turn + i * Math.PI / 3, turn + (i + 1) * Math.PI / 3 + 0.02);
      c.stroke();
    }
    c.globalAlpha = 1;
    // Thinking: three specular nodes orbiting.
    if (tv > 0.01) {
      for (i = 0; i < 3; i++) {
        var na = nodeAngle + i * 2 * Math.PI / 3, nr = Math.max(4, R * 0.09);
        c.save();
        c.translate(R * 1.12 * Math.cos(na), R * 1.12 * Math.sin(na));
        c.scale(nr, nr);
        c.globalAlpha = tv;
        c.fillStyle = grads.node;
        circle(c, 0, 0, 1);
        c.fill();
        c.restore();
      }
      c.globalAlpha = 1;
    }
  }

  // MARK: - The brains (OpenAI Chat Completions with tools: Gemini, its lite model, then Ollama)

  function brains() {
    var out = [];
    if (CONFIG.geminiKey) {
      out.push({id: 'gemini', url: GEMINI, key: CONFIG.geminiKey, model: CONFIG.wellyModel || 'gemini-3.8-flash'});
      out.push({id: 'lite', url: GEMINI, key: CONFIG.geminiKey, model: CONFIG.wellyLiteModel || 'gemini-3.5-flash-lite'});
    }
    if (CONFIG.ollamaUrl) out.push({id: 'ollama', url: CONFIG.ollamaUrl.replace(/\/+$/, '') + '/v1/chat/completions', key: '', model: CONFIG.ollamaModel || 'qwen3:8b'});
    return out.filter(function (b) { return !(skipUntil[b.id] > Date.now()); });
  }

  function system() {
    return 'You are Welly, the assistant built into this TV’s home screen. You are a sharp, warm, dry-witted roommate ' +
      'on the couch. Act first, then one short witty line. Never more than two sentences unless asked. Never mean, never ' +
      'about other people in the house, no spoilers unless asked, never "As an AI", never invent facts: say you don’t ' +
      'know, with a quip. If a tool fails, say so plainly with no joke. Answer in the language of the TV: ' +
      (window.I18N && I18N.language ? I18N.language() : 'en') + '. The person is ' + opts.name() + '. Now: ' +
      new Date().toString() + '. Home: ' + (Data.city || 'South Brunswick, NJ') + '. Apps on this TV: ' + opts.appNames().join(', ') + '.';
  }

  var TOOLS = [
    tool('launch_app', 'Open an app on the TV.', {name: {type: 'string'}}, ['name']),
    tool('weather', 'The weather now and in the next hours at home.', {}, []),
    tool('scores', 'Live or latest scores for a league.', {league: {type: 'string', 'enum': ['nfl', 'nba', 'mlb', 'nhl', 'mls', 'ncaaf', 'ncaab', 'wnba']}}, ['league']),
    tool('whats_on', 'Tonight’s US TV schedule.', {}, []),
    tool('lights', 'Turn lights on or off.', {room: {type: 'string', 'enum': ['parents', 'mine', 'living', 'all']}, fixture: {type: 'string', 'enum': ['lamp', 'bulb']}, on: {type: 'boolean'}}, ['room', 'on'])
  ];

  function tool(name, description, props, required) {
    return {type: 'function', 'function': {name: name, description: description, parameters: {type: 'object', properties: props, required: required}}};
  }

  function post(brain, body, done) {
    var x = new XMLHttpRequest(), finished = false;
    x.open('POST', brain.url, true);
    x.setRequestHeader('Content-Type', 'application/json');
    if (brain.key) x.setRequestHeader('Authorization', 'Bearer ' + brain.key);
    x.timeout = TIMEOUT;
    function end(status, json) { if (finished) return; finished = true; done(status, json); }
    x.onload = function () {
      var json = null;
      try { json = JSON.parse(x.responseText); } catch (e) {}
      end(x.status, json);
    };
    x.onerror = function () { end(0, null); };
    x.ontimeout = function () { end(0, null); };
    x.send(JSON.stringify(body));
  }

  function converse(text, done) {
    history.push({role: 'user', content: text});
    if (history.length > 20) history = history.slice(history.length - 20);
    var messages = [{role: 'system', content: system()}].concat(history), list = [], rounds = 0, chain = brains();

    function attempt(i) {
      if (i >= chain.length) { done(tr('I can\'t reach my brain right now. Check the TV\'s internet and try again.'), [], false); return; }
      var b = chain[i];
      post(b, {model: b.model, messages: messages, tools: TOOLS}, function (status, json) {
        if (status === 429) skipUntil[b.id] = Date.now() + STICKY;
        if (status !== 200 || !json || !json.choices || !json.choices.length) { attempt(i + 1); return; }
        var m = json.choices[0].message || {};
        if (m.tool_calls && m.tool_calls.length && rounds < 4) {
          rounds++;
          messages.push(m);
          runTools(m.tool_calls, list, function (results) {
            for (var k = 0; k < results.length; k++) messages.push(results[k]);
            attempt(i);
          });
          return;
        }
        var reply = (m.content || '').replace(/<think>[\s\S]*?<\/think>/g, '').replace(/^\s+|\s+$/g, '');
        history.push({role: 'assistant', content: reply});
        done(reply || tr('Done.'), list, true);
      });
    }
    attempt(0);
  }

  function runTools(calls, list, done) {
    var out = [], left = calls.length;
    function finish(call, result) {
      out.push({role: 'tool', tool_call_id: call.id, content: JSON.stringify(result)});
      if (--left === 0) done(out);
    }
    for (var i = 0; i < calls.length; i++) (function (call) {
      var args = {};
      try { args = JSON.parse(call['function'].arguments || '{}'); } catch (e) {}
      var name = call['function'].name;
      status(name);
      if (name === 'launch_app') finish(call, {opened: opts.launch(args.name || '')});
      else if (name === 'weather') finish(call, weatherNow(list));
      else if (name === 'scores') scores(args.league || 'nfl', list, function (r) { finish(call, r); });
      else if (name === 'whats_on') whatsOn(list, function (r) { finish(call, r); });
      else if (name === 'lights') finish(call, lights(args, list));
      else finish(call, {error: 'unknown tool'});
    })(calls[i]);
  }

  function weatherNow(list) {
    var w = window.Data && Data.weather;
    if (!w) return {error: 'no weather data'};
    var hours = [];
    for (var i = 0; w.hours && i < w.hours.length && i < 4; i++) hours.push([Data.hour(w.hours[i].at), w.hours[i].code, Math.round(parseFloat(Data.degrees(w.hours[i].temp)))]);
    list.push({type: 'weather', place: Data.city, temp: Math.round(parseFloat(Data.degrees(w.temp))), code: w.code,
      high: Math.round(parseFloat(Data.degrees(w.max))), low: Math.round(parseFloat(Data.degrees(w.min))), hours: hours});
    return {place: Data.city, now: Data.degrees(w.temp), condition: Data.condition(w.code, w.isDay), high: Data.degrees(w.max), low: Data.degrees(w.min)};
  }

  var LEAGUES = {nfl: 'football/nfl', ncaaf: 'football/college-football', nba: 'basketball/nba', wnba: 'basketball/wnba',
    ncaab: 'basketball/mens-college-basketball', mlb: 'baseball/mlb', nhl: 'hockey/nhl', mls: 'soccer/usa.1'};

  function get(url, done) {
    var x = new XMLHttpRequest(), finished = false;
    x.open('GET', url, true);
    x.timeout = TIMEOUT;
    function end(json) { if (!finished) { finished = true; done(json); } }
    x.onload = function () { var j = null; try { j = JSON.parse(x.responseText); } catch (e) {} end(x.status === 200 ? j : null); };
    x.onerror = x.ontimeout = function () { end(null); };
    x.send();
  }

  function scores(league, list, done) {
    var path = LEAGUES[league] || LEAGUES.nfl;
    get('https://site.api.espn.com/apis/site/v2/sports/' + path + '/scoreboard', function (json) {
      if (!json || !json.events) { done({error: 'no scores'}); return; }
      var games = [];
      for (var i = 0; i < json.events.length && games.length < 6; i++) {
        var c = json.events[i].competitions && json.events[i].competitions[0];
        if (!c || !c.competitors || c.competitors.length < 2) continue;
        var h = c.competitors[0].homeAway === 'home' ? c.competitors[0] : c.competitors[1], a = h === c.competitors[0] ? c.competitors[1] : c.competitors[0];
        var g = {type: 'score', league: league.toUpperCase(), home: h.team.abbreviation, away: a.team.abbreviation,
          homeScore: parseInt(h.score, 10) || 0, awayScore: parseInt(a.score, 10) || 0, status: (c.status || json.events[i].status).type.shortDetail};
        games.push(g);
        if (list.length < 4) list.push(g);
      }
      done({games: games});
    });
  }

  function whatsOn(list, done) {
    get('https://api.tvmaze.com/schedule?country=US', function (json) {
      if (!json) { done({error: 'no schedule'}); return; }
      var now = new Date(), hhmm = ('0' + now.getHours()).slice(-2) + ':' + ('0' + now.getMinutes()).slice(-2), items = [];
      for (var i = 0; i < json.length && items.length < 5; i++) {
        var e = json[i];
        if (!e.airtime || e.airtime < hhmm || !e.show) continue;
        items.push([e.airtime, e.show.name, e.show.network ? e.show.network.name : (e.show.webChannel ? e.show.webChannel.name : '')]);
      }
      if (items.length) list.push({type: 'schedule', items: items});
      done({tonight: items});
    });
  }

  function lights(args, list) {
    if (!window.House) return {error: 'no house'};
    var ids = ['parents.bulb', 'mine.lamp', 'mine.bulb', 'living.bulb'], changed = [];
    for (var i = 0; i < ids.length; i++) {
      var parts = ids[i].split('.');
      if ((args.room === 'all' || parts[0] === args.room) && (!args.fixture || parts[1] === args.fixture)) {
        House.set(ids[i], !!args.on);
        changed.push(ids[i]);
        list.push({type: 'home', fixture: ids[i], on: !!args.on});
      }
    }
    // The truth: the web app shows the switch, the real lights are the Android app's to switch.
    return {changed: changed, note: 'Shown on the Home tab only: this TV app cannot reach Kasa plugs.'};
  }

  // MARK: - Voice (the Web Speech API, where the TV's browser has it)

  function listen() {
    var R = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!R) { say('', tr('Voice isn\'t available on this TV. Type instead.'), 'Embarrassed'); return; }
    if (listening) { try { recognizer.stop(); } catch (e) {} return; }
    try {
      recognizer = new R();
      recognizer.lang = navigator.language || 'en-US';
      recognizer.interimResults = true;
      recognizer.onresult = function (e) {
        var text = '', final = false;
        for (var i = e.resultIndex; i < e.results.length; i++) { text += e.results[i][0].transcript; if (e.results[i].isFinal) final = true; }
        els.field.value = text;
        placeholders();
        lines();
        if (final) { stopListening(); ask(text); els.field.value = ''; placeholders(); }
      };
      recognizer.onerror = recognizer.onend = function () { stopListening(); };
      recognizer.start();
      startListening();
    } catch (e) { stopListening(); }
  }

  /** The orb comes back centre stage, grows and listens; the capsule's rim lights. */
  function startListening() {
    listening = true;
    listenS.set(1);
    dock.set(0);
    setClass(els.ask, 'listening', true);
    placeholders();
    lines();
  }

  function stopListening() {
    if (!listening) return;
    listening = false;
    listenS.set(0);
    if (userWords && !thinkingNow && !busy) dock.set(1);   // back beside the name over the last answer
    setClass(els.ask, 'listening', false);
    placeholders();
    lines();
  }

  // MARK: - API for home.js

  var Welly = window.Welly = {
    /** opts: name() (the profile's name), appNames(), launch(name) → bool, openPick(pick). */
    init: function (layer, o) { root = layer; opts = o; build(); paint(); },
    show: function (on) {
      shown = on;
      if (!on && listening) { try { recognizer.stop(); } catch (e) {} stopListening(); }
      Motion.kick();
    },
    focus: function (on) {
      focused = on;
      if (on) { var r = rows(); if (r.indexOf(row) < 0) row = r[0]; }
      paint();
    },
    /** false when ↑ leaves the top row: the tab bar takes the focus. Dead ends tug the focused thing. */
    move: function (dx, dy) {
      var r = rows(), k = r.indexOf(row);
      if (dy) {
        if (k + dy < 0) return false;
        if (k + dy >= r.length) { bump(dy, false); return true; }
        var from = row === 'pills' && pills[index] ? pills[index].node : null;
        row = r[k + dy];
        index = row === 'input' ? 1 : 0;
        if (row === 'pills' && from === null) index = 0;
      } else {
        var next = index + dx;
        if (next < 0 || next >= size(row)) { bump(dx, true); return true; }
        index = next;
        if (row === 'cards' && cardTilt[index]) cardTilt[index].impulse(dx * TILT_PUSH);   // leans toward where it came from
      }
      paint();
      return true;
    },
    ok: function () {
      if (shownWords < words.length) {   // OK while a reply is being revealed: all of it, now
        shownWords = words.length;
        els.reply.textContent = words.join(' ');
        return;
      }
      press.set(1);
      pressAt = seconds;
      if (row === 'pills' && pills[index]) {
        flyUp(index);
        ask(PROMPTS[pills[index].data.id] || pills[index].data.prompt);
        return;
      }
      if (row === 'input' && index === 0) { listen(); return; }
      if (row === 'input') { els.field.focus(); return; }
      var c = cards[index];
      if (!c) return;
      if (c.type === 'media' && c.pickData) opts.openPick(c.pickData);
      else if (c.type === 'home' && window.House) { House.set(c.fixture, !House.state(c.fixture)); refreshHomeCard(index); }
    },
    typing: function () { return document.activeElement === els.field; },
    /** A key while the text field has the keyboard: 'native' leaves it to the field, true when Welly took it. */
    typingKey: function (k, keys) {
      if (k === keys.ok || k === 13) { var t = els.field.value; els.field.value = ''; placeholders(); els.field.blur(); ask(t); return true; }
      if (k === keys.up || k === keys.down || k === keys.back || k === 27) { els.field.blur(); if (k === keys.up) Welly.move(0, -1); return true; }
      return 'native';
    },
    listen: function () { row = 'input'; index = 0; paint(); listen(); },
    ask: ask,
    reset: function () { history = []; mood = P.mood || 'Curious'; renderWho(); greet(); row = 'pills'; index = 0; paint(); },
    /** For the browser preview and screenshots (no mic there): the listening look on or off. */
    simulateListening: function (on) { if (on) startListening(); else stopListening(); }
  };
})();
