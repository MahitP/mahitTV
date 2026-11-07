'use strict';

/* web/layout.js, as AppLayoutTest and FolderNamesTest check the Android app's: the cursor's line, outline, line…, what
 * OK does there, the saved layout over what the TV has, and a new folder's names. */
var assert = require('assert');
var L = require('../web/layout.js');

function board() {
  return new L.Board(['A', 'B', 'C', 'D', 'E', 'F'],
    ['g0', 'folder:apple', 'g2', 'g3', 'g4', 'g5', 'g6', 'g7', 'g8', 'g9', 'g10', 'g11', 'g12'],
    {'folder:apple': ['music', 'guide:facetime']},
    {A: 1, B: 1, C: 1, D: 1, E: 1, F: 1, g0: 1, g2: 1, g3: 1, g4: 1, g5: 1, music: 1}, 6, Infinity);
}
function at(b, row, pos, msg) { assert.deepStrictEqual([b.row, b.pos], [row, pos], msg); }

// The cursor
var b = board();
assert.ok(b.lift(L.FROM_DOCK, 2, null));
assert.deepStrictEqual(b.dock, ['A', 'B', 'D', 'E', 'F'], 'the Dock closes up');
at(b, L.DOCK, 4, 'on the line where it was');
assert.strictEqual(b.action(), L.NONE);
b.move(1, 0);
assert.strictEqual(b.target(), 'D', '→ outlines the next app');
b.move(1, 0);
assert.deepStrictEqual([b.before(), b.after()], ['D', 'E'], 'then the next line');
for (var i = 0; i < 4; i++) b.move(1, 0);
assert.ok(!b.move(1, 0), 'the Dock\'s end');
b.move(0, 1);
at(b, 0, 10, '↓ the same line in the grid');
b.move(1, 0);
b.move(1, 0);
at(b, 0, 12, 'the end of the first row');
b.move(1, 0);
at(b, 1, 1, '→ the next row\'s first app, no second line');
b.move(-1, 0);
at(b, 1, 0, '← the line before it');

// OK
b = board();
b.lift(L.FROM_GRID, 3, null);
b.move(1, 0);
assert.strictEqual(b.action(), L.NEW_FOLDER);
b.drop('folder:u9');
assert.deepStrictEqual(b.folders['folder:u9'], ['g4', 'g3'], 'a new folder: the outlined app, then the moved one');
assert.strictEqual(b.grid[3], 'folder:u9');
b = board();
b.lift(L.FROM_GRID, 2, null);
b.move(0, -1);
b.move(1, 0);
assert.strictEqual(b.action(), L.SWAP);
b.drop('x');
assert.deepStrictEqual(b.dock, ['A', 'B', 'g2', 'D', 'E', 'F'], 'swapped into the Dock');
assert.strictEqual(b.grid[2], 'C', 'and C where g2 was');
b = board();
b.lift(L.FROM_FOLDER, 0, 'folder:apple');
b.move(0, -1);
b.move(-1, 0);
b.drop('x');
assert.strictEqual(b.grid[1], 'guide:facetime', 'a folder down to one app is that app');
assert.ok(b.dissolved['folder:apple']);
b = board();
b.lift(L.FROM_GRID, 1, null);
b.move(1, 0);
at(b, 0, 4, 'a folder skips outlines');
assert.ok(!b.move(0, -1), 'and stays out of the Dock');

// The saved layout
var natural = ['netflix', 'folder:apple', 'hulu', 'spotify', 'folder:tcl'];
var auto = {'folder:apple': ['music', 'guide:facetime', 'guide:fitness'], 'folder:tcl': ['t1', 't2', 't3']};
var l = L.read({grid: [], folders: [{key: 'folder:u1', name: 'Watch', apps: ['hulu', 't1', 'gone']}]});
var out = {};
assert.deepStrictEqual(L.arrange(l, natural, auto, [], out), ['netflix', 'folder:apple', 'spotify', 'folder:tcl', 'folder:u1']);
assert.deepStrictEqual(out['folder:u1'], ['hulu', 't1']);
assert.deepStrictEqual(out['folder:tcl'], ['t2', 't3']);
assert.deepStrictEqual(L.order(['a', 'b', 'n', 'c'], ['c', 'a', 'b']), ['c', 'a', 'b', 'n'], 'a new app after its neighbour');

// Names
function first(labels, ids, name) { assert.strictEqual(L.suggest(labels, ids)[0], name, labels.join(' + ')); }
first(['Netflix', 'Hulu'], ['com.netflix.ninja', 'com.hulu.plus'], 'Streaming');
first(['YouTube', 'YouTube Music'], ['youtube.leanback.v4', 'youtube.music'], 'YouTube');
first(['Spotify', 'Apple Music'], ['spotify-beehive', 'com.apple.music'], 'Music');
first(['Netflix', 'Spotify'], ['netflix', 'spotify-beehive'], 'Entertainment');
first(['YouTube', 'Spotify'], ['youtube.leanback.v4', 'spotify-beehive'], 'Entertainment');   // not 'Sports' (lea-nba-ck)
assert.ok(L.suggest(['Aaa', 'Bbb'], ['x.aaa', 'y.bbb']).length >= 3, 'always a few');
console.log('web-layout-test: ok');
