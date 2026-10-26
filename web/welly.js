/*
 * The Welly tab: a witty roommate who runs the TV (WELLY-HOME-REMOTE-SPEC.md §3). A greeting, the last exchange, its
 * answer's cards (plain fills), a row of prompt pills and an "Ask Welly…" capsule with the mic. With a Gemini key
 * (gemini_key) or an Ollama on the LAN (ollama_url) it asks them, OpenAI-style with tools; the preview answers the
 * pills from app/assets/welly-preview.json. The web app has fewer tools than the Android app: no Kasa (TVs can't open
 * raw sockets), no media sessions.
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

  var opts, root, els = {}, row = 'pills', index = 0, scrollX = 0, cards = [], pills = [], mood = P.mood || 'Curious';
  var shown = false, focused = false, busy = false, listening = false, recognizer = null, history = [], skipUntil = {};

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

  // MARK: - Layout

  function build() {
    root.innerHTML = '';
    var col = el('div', 'col', root);
    els.who = el('div', 'who', col);
    els.hello = el('div', 'hello', col);
    els.you = el('div', 'you', col);
    els.reply = el('div', 'reply', col);
    els.cards = el('div', 'cards', col);
    els.pillsBox = el('div', 'pills', root);
    els.pills = el('div', 'strip', els.pillsBox);
    els.ask = el('div', 'ask', root);
    el('div', 'edge', els.ask);
    var fill = el('div', 'fill', els.ask);
    els.mic = el('div', 'mic', fill);
    sym('mic', els.mic, 30);
    els.field = el('input', 'field', fill);
    els.field.type = 'text';
    els.field.setAttribute('placeholder', tr('Ask Welly…'));
    els.field.onkeydown = null;
    pills = [];
    for (var i = 0; i < P.pills.length; i++) {
      var p = P.pills[i], b = el('div', 'pill', els.pills, PROMPTS[p.id] || p.prompt);
      pills.push({node: b, data: p});
      pointer(b, 'pills', i);
    }
    pointer(els.mic, 'input', 0);
    pointer(els.field, 'input', 1);
    renderWho();
    greet();
  }

  function pointer(node, r, i) {
    node.onmouseover = function () { if (!focused) return; row = r; index = i; paint(); };
    node.onclick = function () { row = r; index = i; paint(); Welly.ok(); };
  }

  function renderWho() {
    var line = 'Welly  ·  ' + (MOODS[mood] || mood);
    if (preview()) line += '  ·  ' + tr('Preview');
    els.who.textContent = line;
  }

  function greet() {
    els.hello.innerHTML = '';
    el('div', 'big', els.hello, trf('Hey {name}.', {name: opts.name()}));
    el('div', 'sub', els.hello, tr('Ask me anything, or pick one below.'));
    els.hello.style.display = '';
    els.you.textContent = '';
    els.reply.textContent = '';
    setCards([]);
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
    var i, list = els.cards.children;
    for (i = 0; i < list.length; i++) setOn(list[i], focused && row === 'cards' && index === i);
    for (i = 0; i < pills.length; i++) setOn(pills[i].node, focused && row === 'pills' && index === i);
    setOn(els.mic, focused && row === 'input' && index === 0);
    setOn(els.ask, focused && row === 'input' && index === 1);
    // The pills scroll only as far as it takes to show the focused one, as a tvOS row does.
    if (row === 'pills' && pills[index]) {
      var left = pills[index].node.offsetLeft, width = pills[index].node.offsetWidth;
      if (left - scrollX < 0) scrollX = left;
      else if (left + width - scrollX > 1180) scrollX = left + width - 1180;
    }
    els.pills.style.webkitTransform = els.pills.style.transform = 'translateX(' + (-scrollX) + 'px)';
    setClass(els.pillsBox, 'more-l', scrollX > 0);
    setClass(els.pillsBox, 'more-r', els.pills.offsetWidth - scrollX > 1190);
  }

  function setOn(node, on) { setClass(node, 'focus', on); }

  function setClass(node, name, on) {
    var c = ' ' + node.className + ' ', has = c.indexOf(' ' + name + ' ') >= 0;
    if (on && !has) node.className += ' ' + name;
    else if (!on && has) node.className = c.replace(' ' + name + ' ', ' ').replace(/^\s+|\s+$/g, '');
  }

  // MARK: - Cards (plain fills, one focus lift)

  function setCards(list) {
    cards = list;
    els.cards.innerHTML = '';
    for (var i = 0; i < list.length; i++) drawCard(list[i], i);
    if (row === 'cards' && !list.length) { row = 'pills'; index = 0; }
    if (row === 'cards') index = Math.min(index, list.length - 1);
  }

  function pickFor(id) {
    var picks = window.PREVIEW_PICKS || [];
    for (var i = 0; i < picks.length; i++) if (picks[i].backdrop.indexOf('/' + id + '.') >= 0) return picks[i];
    return null;
  }

  function drawCard(c, i) {
    var box = el('div', 'card ' + c.type, els.cards);
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
    els.hello.style.display = 'none';
    els.you.textContent = you;
    els.reply.className = 'reply';
    els.reply.textContent = reply;
    if (newMood) { mood = newMood; renderWho(); }
    setCards(list || []);
    paint();
  }

  function thinking(you) {
    els.hello.style.display = 'none';
    els.you.textContent = you;
    els.reply.className = 'reply thinking';
    els.reply.innerHTML = '<span></span><span></span><span></span>';
    setCards([]);
    paint();
  }

  /** What the preview says to a pill (or to typed words that match one). */
  function previewAnswer(text) {
    var t = text.toLowerCase(), best = null;
    for (var i = 0; i < P.pills.length; i++) {
      var p = P.pills[i];
      if ((PROMPTS[p.id] || '').toLowerCase() === t || p.prompt.toLowerCase() === t) best = p;
    }
    if (!best) {
      var words = {watching: /watch(ing)?\b|playing/, 'movie-mode': /dim|movie|lights?/, scores: /score|game|knicks|jets/,
        weather: /weather|cold|rain|warm/, tonight: /tonight|on tv|schedule/, roast: /roast|watchlist/};
      for (i = 0; i < P.pills.length; i++) if (words[P.pills[i].id] && words[P.pills[i].id].test(t)) best = P.pills[i];
    }
    return best;
  }

  function ask(text) {
    text = (text || '').replace(/^\s+|\s+$/g, '');
    if (!text || busy) return;
    busy = true;
    thinking(text);
    if (preview() || (!CONFIG.geminiKey && !CONFIG.ollamaUrl)) {
      setTimeout(function () {
        busy = false;
        var p = preview() ? previewAnswer(text) : null;
        if (!p) {
          say(text, preview() ? tr('In Preview I can only answer the questions below.')
            : tr('I don\'t have a brain yet. Add a free Gemini key (gemini_key) or your Mac\'s Ollama (ollama_url) to config.properties, then build again.'), 'Embarrassed');
          return;
        }
        // The preview acts out what it says: the lights it names change on the Home tab too.
        for (var i = 0; i < p.cards.length; i++) if (p.cards[i].type === 'home' && window.House) House.set(p.cards[i].fixture, p.cards[i].on);
        say(text, p.reply, p.mood, p.cards.slice());
        rowAfterAnswer();
      }, 650);
      return;
    }
    converse(text, function (reply, list, ok) {
      busy = false;
      say(text, reply, ok ? (list.length ? 'Smug' : 'Curious') : 'Embarrassed', list);
      rowAfterAnswer();
    });
  }

  function rowAfterAnswer() {
    // The focus stays where it was (a pill), so the next question is one press away.
    paint();
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
        if (final) { stopListening(); ask(text); els.field.value = ''; }
      };
      recognizer.onerror = recognizer.onend = function () { stopListening(); };
      recognizer.start();
      listening = true;
      els.ask.className += ' listening';
      els.field.setAttribute('placeholder', tr('Listening…'));
    } catch (e) { stopListening(); }
  }

  function stopListening() {
    listening = false;
    els.ask.className = els.ask.className.replace(/\s*listening/g, '');
    els.field.setAttribute('placeholder', tr('Ask Welly…'));
  }

  // MARK: - API for home.js

  var Welly = window.Welly = {
    /** opts: name() (the profile's name), appNames(), launch(name) → bool, openPick(pick). */
    init: function (layer, o) { root = layer; opts = o; build(); paint(); },
    show: function (on) { shown = on; if (!on && listening) { try { recognizer.stop(); } catch (e) {} } },
    focus: function (on) {
      focused = on;
      if (on) { var r = rows(); if (r.indexOf(row) < 0) row = r[0]; }
      paint();
    },
    /** false when ↑ leaves the top row: the tab bar takes the focus. */
    move: function (dx, dy) {
      var r = rows(), k = r.indexOf(row);
      if (dy) {
        if (k + dy < 0) return false;
        if (k + dy >= r.length) return true;
        var from = row === 'pills' && pills[index] ? pills[index].node : null;
        row = r[k + dy];
        index = row === 'input' ? 1 : 0;
        if (row === 'pills' && from === null) index = 0;
      } else {
        index = Math.max(0, Math.min(size(row) - 1, index + dx));
      }
      paint();
      return true;
    },
    ok: function () {
      if (row === 'pills' && pills[index]) { ask(PROMPTS[pills[index].data.id] || pills[index].data.prompt); return; }
      if (row === 'input' && index === 0) { listen(); return; }
      if (row === 'input') { els.field.focus(); return; }
      var c = cards[index];
      if (!c) return;
      if (c.type === 'media' && c.pickData) opts.openPick(c.pickData);
      else if (c.type === 'home' && window.House) { House.set(c.fixture, !House.state(c.fixture)); setCards(cards); paint(); }
    },
    typing: function () { return document.activeElement === els.field; },
    /** A key while the text field has the keyboard: 'native' leaves it to the field, true when Welly took it. */
    typingKey: function (k, keys) {
      if (k === keys.ok || k === 13) { var t = els.field.value; els.field.value = ''; els.field.blur(); ask(t); return true; }
      if (k === keys.up || k === keys.down || k === keys.back || k === 27) { els.field.blur(); if (k === keys.up) Welly.move(0, -1); return true; }
      return 'native';
    },
    listen: function () { row = 'input'; index = 0; paint(); listen(); },
    ask: ask,
    reset: function () { history = []; mood = P.mood || 'Curious'; renderWho(); greet(); row = 'pills'; index = 0; paint(); }
  };
})();
