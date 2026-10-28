/*
 * The people who use the TV (MAHITTV-PROFILES-SPEC.md §1), the same as the Android app's ProfileManager and WatchLog:
 * accounts from profiles= in config.properties (the build only ever puts sha256(salt + PIN) in CONFIG), who is in, the
 * 10-minute grace period, each profile's own settings in localStorage['mahittv.profile.<id>'] (Guest's in memory
 * only), and each one's private watch log for Welly's "Continue watching".
 */
var Profiles = (function () {
  'use strict';

  var GRACE_MS = 10 * 60 * 1000, TRIES = 5, WAIT_MS = 30000, LOG_MAX = 30;
  var STATE = 'mahittv.state';
  var list = null, unlocked = false, guest = {}, wrong = {}, waitUntil = {};

  function read(key, fallback) {
    try { var v = JSON.parse(localStorage.getItem(key)); return v === null || v === undefined ? fallback : v; } catch (e) { return fallback; }
  }
  function write(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) {} }
  function remove(key) { try { localStorage.removeItem(key); } catch (e) {} }

  // MARK: - SHA-256 (ES5; old TVs have no SubtleCrypto)

  var K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98,
    0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6,
    0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3,
    0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e,
    0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
    0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814,
    0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];

  function utf8(text) {
    var out = [], s = unescape(encodeURIComponent(text));
    for (var i = 0; i < s.length; i++) out.push(s.charCodeAt(i));
    return out;
  }

  function sha256(text) {
    var bytes = utf8(text), n = bytes.length, i, j;
    bytes.push(0x80);
    while (bytes.length % 64 !== 56) bytes.push(0);
    var bits = n * 8;
    for (i = 7; i >= 0; i--) bytes.push(i > 3 ? 0 : (bits >>> (i * 8)) & 0xff);
    var h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19], w = [];
    function rotr(x, r) { return (x >>> r) | (x << (32 - r)); }
    for (var off = 0; off < bytes.length; off += 64) {
      for (i = 0; i < 16; i++) w[i] = (bytes[off + i * 4] << 24) | (bytes[off + i * 4 + 1] << 16) | (bytes[off + i * 4 + 2] << 8) | bytes[off + i * 4 + 3];
      for (i = 16; i < 64; i++) {
        var s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3), s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
      }
      var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
      for (j = 0; j < 64; j++) {
        var t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[j] + w[j]) | 0;
        var t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
        hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
      h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + hh) | 0;
    }
    var hex = '';
    for (i = 0; i < 8; i++) hex += ('00000000' + (h[i] >>> 0).toString(16)).slice(-8);
    return hex;
  }

  function salt() {
    var s = '';
    for (var i = 0; i < 32; i++) s += Math.floor(Math.random() * 16).toString(16);
    return s;
  }

  // MARK: - The accounts

  function all() {
    if (list) return list;
    var src = (window.CONFIG && CONFIG.profiles) || [];
    list = [];
    for (var i = 0; i < src.length; i++) {
      var p = src[i];
      if (!p.id) continue;   // the old Name:avatar form has no id: treat it as a plain profile
      list.push({id: p.id, name: p.name, role: p.role || 'standard', pin: p.pin || '', avatar: p.avatar || ''});
    }
    if (!list.length && src.length) {
      for (i = 0; i < src.length; i++) list.push({id: 'p' + i, name: src[i].name, role: i ? 'standard' : 'manager', pin: '', avatar: src[i].avatar || ''});
    }
    if (!list.length) {
      list.push({id: 'me', name: tr('Me'), role: 'manager', pin: '', avatar: ''});
      list.push({id: 'guest', name: tr('Guest'), role: 'guest', pin: '', avatar: ''});
    }
    return list;
  }

  function byId(id) {
    var l = all();
    for (var i = 0; i < l.length; i++) if (l[i].id === id) return l[i];
    return null;
  }

  function state() { return read(STATE, {}); }
  function saveState(s) { write(STATE, s); }

  function current() {
    return byId(state().current) || all()[0];
  }

  // MARK: - Each profile's own settings

  /** A profile's own store: get(name, fallback), set(name, value), all(). Guest's lives in memory. */
  function store(p) {
    p = p || current();
    if (p.role === 'guest') {
      return {
        get: function (k, d) { return guest[k] === undefined ? d : guest[k]; },
        set: function (k, v) { guest[k] = v; },
        remove: function (k) { delete guest[k]; }
      };
    }
    var key = 'mahittv.profile.' + p.id;
    return {
      get: function (k, d) { var o = read(key, {}); return o[k] === undefined ? d : o[k]; },
      set: function (k, v) { var o = read(key, {}); o[k] = v; write(key, o); },
      remove: function (k) { var o = read(key, {}); delete o[k]; write(key, o); }
    };
  }

  // MARK: - PINs

  function pinSaltOf(p) { var s = store(p); return s.get('pinSet', false) ? s.get('pinSalt', '') : (CONFIG.pinSalt || ''); }
  function pinHashOf(p) { var s = store(p); return s.get('pinSet', false) ? s.get('pinHash', '') : p.pin; }

  // MARK: - The watch log (Welly's "Continue watching"), newest first

  function log() { return current().role === 'guest' ? [] : store().get('watchLog', []); }

  function saveLog(l) { if (current().role !== 'guest') store().set('watchLog', l.slice(0, LOG_MAX)); }

  /** Preview builds: Mahit and Dhruvin get different histories and Docks, once (the sample picks' titles). */
  function seedPreview() {
    if (!window.PREVIEW_PICKS) return;
    var now = Date.now(), h = 3600000;
    function seed(id, rows, dock) {
      var p = byId(id);
      if (!p) return;
      var s = store(p);
      if (s.get('previewSeeded', false)) return;
      var out = [];
      for (var i = 0; i < rows.length; i++) {
        var r = rows[i];
        out.push({title: r[0], tmdb: r[1], kind: r[2], app: r[3], appKey: r[4], at: now - (i * 20 + 2) * h, pos: r[5] * 60000, dur: r[6] * 60000});
      }
      s.set('watchLog', out);
      s.set('shelf', dock);
      s.set('previewSeeded', true);
    }
    seed('mahit', [['Orbital', 9003, 'tv', 'Disney+', 'disney', 41, 52], ['Neon Meridian', 9000, 'movie', 'Apple TV', 'appletv', 63, 124],
      ['The Long Dunes', 9001, 'tv', 'Netflix', 'netflix', 12, 48]],
      ['netflix', 'youtube.leanback.v4', 'com.apple.appletv', 'com.disney.disneyplus-prod', 'amazon', 'com.apple.music']);
    seed('dhruvin', [['Quiet Peak', 9002, 'movie', 'Prime Video', 'prime', 28, 112], ['Tidebreak', 9004, 'movie', 'Max', 'max', 95, 107]],
      ['youtube.leanback.v4', 'amazon', 'com.hbo.hbomax', 'netflix', 'com.crunchyroll.webapp', 'spotify-beehive']);
  }

  var api = {
    GRACE_MS: GRACE_MS,
    sha256: sha256,
    all: all,
    byId: byId,
    current: current,
    index: function () { return Math.max(0, all().indexOf(current())); },
    manager: function () { var l = all(); for (var i = 0; i < l.length; i++) if (l[i].role === 'manager') return l[i]; return l[0]; },
    store: store,
    /** The avatar's URL: profiles/<id>.png (yours), the named avatar, memoji/guest, else a bundled default. */
    avatarUrl: function (p) {
      var files = (window.CONFIG && CONFIG.profileAvatars) || [];
      if (files.indexOf(p.id + '.png') >= 0) return 'profiles/' + p.id + '.png';
      if (p.avatar) return 'avatars/' + p.avatar;
      if (p.role === 'manager' && CONFIG.defaultAvatar === 'memoji.png') return 'avatars/memoji.png';
      if (p.role === 'guest') return 'avatars/guest.png';
      return 'avatars/default-' + (all().indexOf(p) % 4 + 1) + '.png';
    },
    needsLock: function () {
      if (!unlocked) return true;
      var last = state().lastActive || 0;
      return !last || Date.now() - last > GRACE_MS;
    },
    touch: function () { var s = state(); s.lastActive = Date.now(); saveState(s); },
    unlock: function (p) {
      var before = current();
      if (before.role === 'guest' && before.id !== p.id) guest = {};
      var s = state();
      s.current = p.id;
      s.lastActive = Date.now();
      saveState(s);
      unlocked = true;
      if (window.Prefs) Prefs.version++;
    },
    lock: function () {
      unlocked = false;
      if (current().role === 'guest') guest = {};
    },
    unlocked: function () { return unlocked; },
    needsPin: function (p) { return p.role !== 'guest' && !!pinHashOf(p); },
    waitLeft: function (p) { return Math.max(0, (waitUntil[p.id] || 0) - Date.now()); },
    /** Checks four digits; five wrong in a row and the profile waits 30 s. */
    checkPin: function (p, digits) {
      if (!api.needsPin(p)) return true;
      if (api.waitLeft(p) > 0) return false;
      var ok = sha256(pinSaltOf(p) + digits) === pinHashOf(p);
      if (ok) { wrong[p.id] = 0; return true; }
      wrong[p.id] = (wrong[p.id] || 0) + 1;
      if (wrong[p.id] >= TRIES) { wrong[p.id] = 0; waitUntil[p.id] = Date.now() + WAIT_MS; }
      return false;
    },
    setPin: function (p, digits) {
      if (p.role === 'guest') return;
      var s = store(p), sl = digits ? salt() : '';
      s.set('pinSalt', sl);
      s.set('pinHash', digits ? sha256(sl + digits) : '');
      s.set('pinSet', true);
    },
    /** Back to the default profiles: every profile's store, the watch logs and the picks cache (Settings, manager PIN). */
    resetAll: function () {
      var l = all();
      for (var i = 0; i < l.length; i++) remove('mahittv.profile.' + l[i].id);
      remove(STATE);
      remove('fmtv.recs');
      guest = {};
      unlocked = false;
      if (window.Prefs) Prefs.version++;
    },
    log: log,
    /** Opened from mahitTV: a title (a pick) or an app. The same title again moves to the top. */
    launched: function (title, tmdb, kind, app, appKey) {
      if (current().role === 'guest') return;
      var l = log(), e = null;
      for (var i = 0; i < l.length; i++) {
        if ((tmdb && l[i].tmdb === tmdb) || (!tmdb && title && l[i].title === title)) { e = l.splice(i, 1)[0]; break; }
      }
      e = e || {pos: 0, dur: 0};
      e.title = title || '';
      e.tmdb = tmdb || e.tmdb || 0;
      e.kind = kind || '';
      e.app = app || '';
      e.appKey = appKey || '';
      e.at = Date.now();
      l.unshift(e);
      saveLog(l);
    },
    clearLog: function () { if (current().role !== 'guest') store().remove('watchLog'); },
    seedPreview: seedPreview
  };
  return api;
})();
