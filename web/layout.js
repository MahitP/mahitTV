/*
 * Moving apps (hold OK on one), as the Android app's AppLayout and FolderNames: the board (the Dock and the grid while
 * something is out of its place, with a cursor that goes line, outline, line…), the layout each profile keeps (the
 * grid's order and its own folders), and names for a new folder. Plain ES5: no DOM here, so it can be tested in Node.
 */
var Layout = (function () {
  'use strict';

  function has(list, x) { return list.indexOf(x) >= 0; }

  /**
   * `natural` in the saved order: the keys `saved` names keep its order (the others it names are gone); the ones it
   * doesn't name (apps just installed) go at the end, as on Apple TV, in `natural`'s order.
   */
  function order(natural, saved) {
    var out = [], placed = {}, i;
    for (i = 0; i < saved.length; i++) {
      if (has(natural, saved[i]) && !placed[saved[i]]) { placed[saved[i]] = true; out.push(saved[i]); }
    }
    for (i = 0; i < natural.length; i++) {
      if (!placed[natural[i]]) { placed[natural[i]] = true; out.push(natural[i]); }
    }
    return out;
  }

  /** A saved layout: {grid: [keys], folders: [{key, name, apps: [keys]}]} (as Prefs keeps it), never null. */
  function read(saved) {
    var l = {grid: [], folders: []};
    if (!saved || typeof saved !== 'object') return l;
    if (saved.grid && saved.grid.length) l.grid = saved.grid.slice();
    for (var i = 0; saved.folders && i < saved.folders.length; i++) {
      var f = saved.folders[i];
      if (f && f.key) l.folders.push({key: f.key, name: String(f.name || ''), apps: (f.apps || []).slice()});
    }
    return l;
  }

  function folderOf(l, key) {
    for (var i = 0; i < l.folders.length; i++) if (l.folders[i].key === key) return l.folders[i];
    return null;
  }

  /**
   * The grid with layout `l`: `natural` is All Apps as made (apps and the TV's own folders, whose apps are in `auto`),
   * `dock` what the Dock shows (never in the grid too). The person's folders take their apps; a TV folder they changed
   * is theirs; a folder down to one app is that app, in its place. Returns the keys in order; `out` gets each folder's apps.
   */
  function arrange(l, natural, auto, dock, out) {
    var all = {}, i, j, k;
    for (i = 0; i < natural.length; i++) {
      if (auto[natural[i]]) for (j = 0; j < auto[natural[i]].length; j++) all[auto[natural[i]][j]] = true;
      else all[natural[i]] = true;
    }
    for (i = 0; i < dock.length; i++) delete all[dock[i]];
    var claimed = {}, mine = {}, mineKeys = [];
    for (i = 0; i < l.folders.length; i++) {
      var f = l.folders[i], kids = [];
      for (j = 0; j < f.apps.length; j++) {
        if (all[f.apps[j]] && !claimed[f.apps[j]]) { claimed[f.apps[j]] = true; kids.push(f.apps[j]); }
      }
      mine[f.key] = kids;
      mineKeys.push(f.key);
    }
    var entries = [], single = {};
    for (i = 0; i < natural.length; i++) {
      k = natural[i];
      var a = auto[k];
      if (!a) {
        if (!claimed[k] && !has(dock, k)) entries.push(k);
        continue;
      }
      var left = [];
      for (j = 0; j < a.length; j++) if (!claimed[a[j]] && !has(dock, a[j])) left.push(a[j]);
      if (mine[k]) {
        entries.push(k);
        for (j = 0; j < left.length; j++) entries.push(left[j]);
      } else if (left.length >= 2) {
        entries.push(k);
        out[k] = left;
      } else if (left.length === 1) {
        entries.push(left[0]);
        single[k] = left[0];
      }
    }
    for (i = 0; i < mineKeys.length; i++) if (!has(entries, mineKeys[i])) entries.push(mineKeys[i]);
    var grid = [];
    for (i = 0; i < entries.length; i++) {
      k = entries[i];
      var m = mine[k];
      if (!m) grid.push(k);
      else if (m.length >= 2) { grid.push(k); out[k] = m; }
      else if (m.length === 1) { grid.push(m[0]); single[k] = m[0]; }
    }
    var saved = l.grid.slice();
    for (i = 0; i < saved.length; i++) if (single[saved[i]] && !has(l.grid, single[saved[i]])) saved[i] = single[saved[i]];
    return order(grid, saved);
  }

  // MARK: - The board: what's moving is out of its place; the cursor's pos is even on a line (gap pos / 2), odd on an
  // app ((pos - 1) / 2); row -1 is the Dock, then the grid's rows

  var DOCK = -1, FROM_DOCK = 1, FROM_GRID = 2, FROM_FOLDER = 3;
  var NONE = 0, PLACE = 1, NEW_FOLDER = 2, ADD = 3, SWAP = 4;

  function Board(dock, grid, folders, dockable, columns, dockMax) {
    this.dock = dock;
    this.grid = grid;
    this.folders = folders;      // folder key → its apps' keys
    this.dockable = dockable;    // key → true for what may go in the Dock
    this.columns = columns;
    this.dockMax = dockMax;
    this.touched = {};
    this.dissolved = {};
    this.lifted = null;
    this.row = 0;
    this.pos = 0;
  }

  Board.prototype.isFolder = function (key) { return key !== null && key !== undefined && !!this.folders[key]; };

  Board.prototype.lift = function (zone, index, folder) {
    if (this.lifted !== null) return false;
    var list = zone === FROM_DOCK ? this.dock : zone === FROM_GRID ? this.grid : this.folders[folder];
    if (!list || index < 0 || index >= list.length) return false;
    this.lifted = list.splice(index, 1)[0];
    this.from = zone;
    this.fromIndex = index;
    this.fromFolder = zone === FROM_FOLDER ? folder : null;
    if (zone === FROM_FOLDER) this.touched[folder] = true;
    if (zone === FROM_DOCK) {
      this.row = DOCK;
      this.pos = 2 * index;
    } else {
      var at = zone === FROM_GRID ? index : this.grid.indexOf(folder) + 1;
      this.row = Math.floor(at / this.columns);
      this.pos = 2 * (at % this.columns);
      if (this.row >= this.rows()) { this.row = this.rows() - 1; this.pos = 2 * this.count(this.row); }
    }
    return true;
  };

  Board.prototype.rows = function () { return Math.max(1, Math.ceil(this.grid.length / this.columns)); };

  Board.prototype.count = function (r) {
    if (r === DOCK) return this.dock.length;
    return Math.max(0, Math.min(this.columns, this.grid.length - r * this.columns));
  };

  Board.prototype.at = function (r, i) { return r === DOCK ? this.dock[i] : this.grid[r * this.columns + i]; };
  Board.prototype.insertion = function () { return this.row === DOCK ? this.pos / 2 : this.row * this.columns + this.pos / 2; };
  Board.prototype.target = function () { return this.pos % 2 === 1 ? this.at(this.row, (this.pos - 1) / 2) : null; };
  Board.prototype.before = function () { return this.pos % 2 === 0 && this.pos > 0 ? this.at(this.row, this.pos / 2 - 1) : null; };
  Board.prototype.after = function () {
    return this.pos % 2 === 0 && this.pos / 2 < this.count(this.row) ? this.at(this.row, this.pos / 2) : null;
  };

  Board.prototype.valid = function (r, p) {
    if (p < 0 || p > 2 * this.count(r)) return false;
    if (r === DOCK) {
      if (!this.dockable[this.lifted]) return false;   // a folder, a guide: not in the Dock
      return p % 2 === 1 || this.dock.length < this.dockMax || this.from === FROM_DOCK;   // a full Dock only swaps
    }
    if (this.from === FROM_DOCK && !this.dock.length) return false;   // the Dock keeps one app
    return p % 2 === 0 || !this.isFolder(this.lifted);   // a folder goes only between apps
  };

  Board.prototype.move = function (dx, dy) {
    if (this.lifted === null) return false;
    var r = this.row, p = this.pos;
    if (dx > 0) {
      for (;;) {
        p++;
        if (p > 2 * this.count(r)) {
          if (r === DOCK || r + 1 >= this.rows()) return false;
          r++;
          p = 1;   // a row's end line is the next row's start: from it, straight to that row's first app
        }
        if (this.valid(r, p)) return this.set(r, p);
      }
    }
    if (dx < 0) {
      for (;;) {
        p--;
        if (p < 0) {
          if (r === DOCK || r === 0) return false;
          r--;
          p = 2 * this.count(r) - 1;
        }
        if (this.valid(r, p)) return this.set(r, p);
      }
    }
    if (!dy) return false;
    if (dy < 0) {
      if (r === DOCK) return false;
      r = r === 0 ? DOCK : r - 1;
    } else {
      if (r !== DOCK && r + 1 >= this.rows()) return false;
      r = r === DOCK ? 0 : r + 1;
    }
    var c = this.count(r);
    p = this.pos % 2 === 1 ? (c === 0 ? 0 : 2 * Math.min((this.pos - 1) / 2, c - 1) + 1) : 2 * Math.min(this.pos / 2, c);
    for (var d = 0; d <= 2 * c; d++) {
      if (this.valid(r, p - d)) return this.set(r, p - d);
      if (this.valid(r, p + d)) return this.set(r, p + d);
    }
    return false;
  };

  Board.prototype.set = function (r, p) { this.row = r; this.pos = p; return true; };

  Board.prototype.action = function () {
    if (this.lifted === null) return NONE;
    if (this.row === DOCK) {
      if (this.pos % 2 === 1) return SWAP;
      return this.from === FROM_DOCK && this.pos / 2 === this.fromIndex ? NONE : PLACE;
    }
    if (this.pos % 2 === 1) return this.isFolder(this.target()) ? ADD : NEW_FOLDER;
    return this.from === FROM_GRID && this.insertion() === this.fromIndex ? NONE : PLACE;
  };

  Board.prototype.drop = function (folderKey) {
    var kind = this.action(), t;
    if (kind === PLACE) {
      (this.row === DOCK ? this.dock : this.grid).splice(this.insertion(), 0, this.lifted);
    } else if (kind === NEW_FOLDER) {
      t = this.row * this.columns + (this.pos - 1) / 2;
      this.folders[folderKey] = [this.grid[t], this.lifted];
      this.grid[t] = folderKey;
      this.touched[folderKey] = true;
      this.newFolder = folderKey;
    } else if (kind === ADD) {
      t = this.target();
      this.folders[t].push(this.lifted);
      this.touched[t] = true;
    } else if (kind === SWAP) {
      var i = (this.pos - 1) / 2, out = this.dock[i];
      this.dock[i] = this.lifted;
      if (this.from === FROM_DOCK) this.dock.splice(this.fromIndex, 0, out);
      else if (this.from === FROM_GRID) this.grid.splice(this.fromIndex, 0, out);
      else this.grid.splice(this.grid.indexOf(this.fromFolder) + 1, 0, out);   // out of a folder: beside it, not in it
    } else {
      var list = this.from === FROM_DOCK ? this.dock : this.from === FROM_GRID ? this.grid : this.folders[this.fromFolder];
      list.splice(Math.min(this.fromIndex, list.length), 0, this.lifted);
    }
    this.lifted = null;
    // A folder down to one app is that app, in its place; an empty one goes.
    for (var g = this.grid.length - 1; g >= 0; g--) {
      var apps = this.folders[this.grid[g]];
      if (!apps || apps.length >= 2) continue;
      var key = this.grid[g];
      delete this.folders[key];
      delete this.touched[key];
      this.dissolved[key] = true;
      if (apps.length) this.grid[g] = apps[0];
      else this.grid.splice(g, 1);
    }
    return kind;
  };

  // MARK: - Names for a new folder (FolderNames): the brand the apps share, else what they're for

  var KINDS = [
    ['Kids', 'Family', /kids|disney ?jr|pbs ?kids|nick ?jr|nickelodeon|cartoon|noggin|sesame|cocomelon|boomerang|toon/],
    ['Live TV', 'Channels', /live ?tv|youtube ?tv|tvunplugged|\bsling|pluto|freeplay|channels|tcl ?tv|tclplus|xumo|local ?now|antenna|tvcenter|wwtv|\bphilo\b|directv|stb ?emu|stbemu|iptv|tivimate|smarters|^com\.tcl\.tv /],
    ['Sports', /espn|\bnfl\b|\bnba\b|\bmlb\b|\bnhl\b|\bmls\b|dazn|sports|f1 ?tv|formula ?1|\bufc\b|\bwwe\b|golf|tennis|fubo|\bbein|fanduel/],
    ['News', /news|cnn|bbc|msnbc|bloomberg|haystack|reuters|weather/],
    ['Music', 'Listen', /music|spotify|pandora|tidal|deezer|soundcloud|iheart|tunein|siriusxm|shazam|vevo|stingray|karaoke|qobuz|napster|audiomack|radio|podcast|audible/],
    ['Games', 'Play', /game|roblox|steam|xbox|geforce|luna|stadia|minecraft|asphalt|retroarch|emulat|playstation|chess|arcade|solitaire|twitch/],
    ['Fitness', 'Health', /fitness|peloton|workout|yoga|nike ?training|les ?mills|beachbody|strava|zwift|calm|headspace|meditat/],
    ['Learning', 'Explore', /\bted\b|khan|coursera|masterclass|udemy|duolingo|curiosity|nebula|great ?courses|wondrium|nat ?geo|nasa|learn/],
    ['Photos', 'Memories', /photo|gallery|immich|flickr|picture|lightroom/],
    ['Social', /instagram|tiktok|musically|facebook|twitter|snapchat|discord|reddit|whatsapp|telegram|threads|pinterest|zoom|messenger|skype/],
    ['Shopping', /shop|ebay|\bqvc\b|\bhsn\b|walmart|etsy/],
    ['Utilities', 'Tools', /settings|file|documentsui|browser|browse|download|clean|vpn|speed ?test|airplay|media ?player|mediacenter|manual|user ?guide|instructions|store|alexa|remote|keyboard|launcher|quick ?panel|t-?solo|exhibit|dashboard|tcl ?home|freemytv|mahittv|tools|system|update|bluetooth|backup|terminal/],
    ['Streaming', 'Movies & TV', 'Watch', /netflix|prime ?video|amazonvideo|\bavod|firebat|hulu|disney|\bhbo|\bmax\b|wbd\.stream|peacock|paramount|cbs\.|tubi|crunchyroll|funimation|hidive|apple ?tv|appletv|atve|plex|jellyfin|emby|kodi|vudu|fandango|starz|showtime|britbox|mubi|acorn|discovery|\bamc\b|shudder|criterion|roku|freevee|kanopy|hoopla|rakuten|viki|youtube|smarttube|video|movie|cinema|film|stream|\btv\b/]
  ];
  var BOTH = [['Streaming', 'Live TV', 'TV'], ['Streaming', 'Kids', 'Family'], ['Streaming', 'Music', 'Entertainment'],
    ['Streaming', 'Games', 'Entertainment'], ['Streaming', 'Sports', 'Watch'], ['Sports', 'Live TV', 'Sports'],
    ['News', 'Live TV', 'News'], ['Games', 'Kids', 'Fun'], ['Music', 'Fitness', 'Fitness'], ['Music', 'Games', 'Fun']];
  var MAKERS = {google: 'Google', amazon: 'Amazon', apple: 'Apple', tcl: 'TCL', disney: 'Disney', microsoft: 'Microsoft',
    nvidia: 'NVIDIA', sony: 'Sony', samsung: 'Samsung', lge: 'LG', guide: 'Apple'};
  var COMMON = ['the', 'my', 'live', 'tv', 'smart', 'media', 'video', 'music', 'quick', 'user', 'works', 'home', 'new', 'free',
    'play', 'app', 'apps', 'android', 'web', 'watch'];
  var FALLBACK = ['Favorites', 'Entertainment', 'My Apps'];

  function kindOf(label, id) {
    var text = ((id || '') + ' ' + (label || '')).toLowerCase();
    for (var i = 0; i < KINDS.length; i++) if (KINDS[i][KINDS[i].length - 1].test(text)) return i;
    return -1;
  }

  function maker(id) {
    if (!id) return '';
    if (id.indexOf('guide:') === 0) return 'guide';
    var parts = id.split('.');
    return parts.length >= 2 ? parts[1] : '';
  }

  function brand(labels, ids) {
    if (labels.length < 2) return null;
    var word = null, i;
    for (i = 0; i < labels.length; i++) {
      var w = String(labels[i] || '').replace(/^\s+/, '').split(/\s+/)[0];
      if (word === null) word = w;
      else if (word.toLowerCase() !== w.toLowerCase()) { word = null; break; }
    }
    if (word && word.length >= 2 && !has(COMMON, word.toLowerCase())) return word;
    var found = null;
    for (i = 0; i < ids.length; i++) {
      var m = MAKERS[maker(ids[i])];
      if (!m || (found && found !== m)) return null;
      found = m;
    }
    return found;
  }

  /** Names for a folder of these apps (their names and ids, in the same order), best first, in English. */
  function suggest(labels, ids) {
    var out = [];
    function add(n) { if (n && !has(out, n)) out.push(n); }
    add(brand(labels, ids));
    var counts = {}, kinds = [], i;
    for (i = 0; i < labels.length; i++) {
      var k = kindOf(labels[i], ids[i]);
      if (k < 0) continue;
      if (!counts[k]) { counts[k] = 0; kinds.push(k); }
      counts[k]++;
    }
    kinds.sort(function (a, b) { return counts[b] - counts[a]; });
    if (kinds.length >= 2) {
      var x = KINDS[kinds[0]][0], y = KINDS[kinds[1]][0];
      for (i = 0; i < BOTH.length; i++) if ((BOTH[i][0] === x && BOTH[i][1] === y) || (BOTH[i][0] === y && BOTH[i][1] === x)) add(BOTH[i][2]);
      for (i = 0; i < kinds.length; i++) add(KINDS[kinds[i]][0]);
    } else if (kinds.length === 1) {
      for (i = 0; i < KINDS[kinds[0]].length - 1; i++) add(KINDS[kinds[0]][i]);
    }
    for (i = 0; i < FALLBACK.length; i++) add(FALLBACK[i]);
    return out;
  }

  return {order: order, read: read, folderOf: folderOf, arrange: arrange, Board: Board, suggest: suggest,
    DOCK: DOCK, FROM_DOCK: FROM_DOCK, FROM_GRID: FROM_GRID, FROM_FOLDER: FROM_FOLDER,
    NONE: NONE, PLACE: PLACE, NEW_FOLDER: NEW_FOLDER, ADD: ADD, SWAP: SWAP};
})();
if (typeof module !== 'undefined') module.exports = Layout;
