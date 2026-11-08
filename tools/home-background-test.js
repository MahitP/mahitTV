'use strict';

/* Contract checks for the Home Top Shelf controller.  Keep these close to the
 * DOM implementation: the TV browser is ES5 and this is deliberately runnable
 * without a browser harness. */
var assert = require('assert');
var fs = require('fs');
var vm = require('vm');
var source = fs.readFileSync('web/home.js', 'utf8');
var css = fs.readFileSync('web/home.css', 'utf8');
var wallpaperSource = fs.readFileSync('web/wallpaper.js', 'utf8');
var context = {};
vm.runInNewContext(wallpaperSource, context, {filename: 'web/wallpaper.js'});
var Wallpaper = context.Wallpaper;

/* Deterministic controller contract: this mirrors the small, externally
 * observable decision boundary used by refreshBackground, without a browser. */
function decide(zone, app, history, featured, nowPlaying) {
  if (!Wallpaper.media(zone, app.media)) return {mode: 'profile', hero: false};
  var choice = Wallpaper.selectMedia(history, [], app, nowPlaying);
  if (!choice && !app.music) choice = Wallpaper.selectMedia([], featured, app, null);
  return choice ? {mode: 'media', hero: true, pick: choice.item} : {mode: 'profile', hero: false};
}
var netflix = {pkg: 'netflix', appKey: 'netflix', media: true};
var apple = {pkg: 'com.apple.music', appKey: 'applemusic', media: true, music: true};
var spotify = {pkg: 'com.spotify.music', appKey: 'spotify', media: true, music: true};
var historyOnly = [{kind: 'movie', tmdb: 91, pkg: 'netflix', appKey: 'netflix', title: 'History'}];
var featured = [{kind: 'movie', id: 12, pkg: 'netflix', app: 'netflix', title: 'Featured'}];
assert.strictEqual(decide('shelf', netflix, historyOnly, featured, null).pick.title, 'History', 'history-only Shelf remains Hero-capable');
assert.strictEqual(decide('hero', netflix, historyOnly, featured, null).pick.title, 'History', 'Hero preserves resolved history art');
assert.strictEqual(decide('tabs', netflix, historyOnly, featured, null).mode, 'profile', 'tabs use profile background');
assert.strictEqual(decide('apps', netflix, historyOnly, featured, null).mode, 'profile', 'grid uses profile background');
assert.strictEqual(decide('settings', netflix, historyOnly, featured, null).mode, 'profile', 'settings use profile background');
assert.strictEqual(decide('shelf', {media: false}, historyOnly, featured, null).mode, 'profile', 'non-media tiles use profile background');
assert.strictEqual(decide('shelf', netflix, [], featured, null).pick.title, 'Featured', 'no history falls back to focused featured art');
assert.strictEqual(decide('shelf', apple, [], featured, null).mode, 'profile', 'inactive Apple Music has no featured art');
assert.strictEqual(decide('shelf', spotify, historyOnly, featured, null).mode, 'profile', 'inactive Spotify has no history or featured art');
assert.strictEqual(decide('shelf', spotify, [], featured, {pkg: 'com.spotify.music', title: 'Track', artwork: 'art.jpg', active: true}).mode,
  'media', 'active Spotify Now Playing is media art');

assert.ok(/backgroundGeneration/.test(source), 'background controller has a generation token');
assert.ok(/Wallpaper\.media\(st\.zone,/.test(source), 'only Shelf and Hero may request media art');
assert.ok(/Data\.resolveWatch\(app, generation/.test(source), 'watch resolver receives generation');
assert.ok(/generation !== backgroundGeneration/.test(source), 'late watch results are rejected');
assert.ok(/backgroundMode/.test(source), 'profile and media states are explicit');
assert.ok(/mediaPage/.test(source), 'resolved media state is independent of shelf picks');
assert.ok(/MUSIC_KEYS/.test(source) && /applemusic/.test(source) && /spotify/.test(source), 'Apple Music and Spotify are explicit music services');
assert.ok(/mediaPage \|\| hasClass\(body, 'media-background'\)/.test(source), 'resolved Shelf art can enter Hero without a featured card');
assert.ok(/media-background/.test(source) && /profile-background/.test(source), 'background state classes are set');
assert.ok(/cubic-bezier\(\.2,\.8,\.2,1\)/.test(source), 'photo fade has the specified curve');
assert.ok(/0\.7s/.test(source), 'photo fade is 700ms');
assert.ok(/Motion\.instant \? 'none' : 'opacity 0\.7s cubic-bezier\(\.2,\.8,.2,1\)'/.test(source), 'instant mode clears photo transition');
assert.ok(/\$\('bgA'\)\.style\.webkitTransition/.test(source), 'backdrop transition is explicitly controlled');
assert.ok(/#sky \.photo[\s\S]*transition: none/.test(css), 'CSS does not override the controlled photo transition');
assert.ok(/img\.onerror[\s\S]*applyProfileBackground\(\)/.test(source), 'failed media images fall back silently to Horizon');
assert.ok(/\.media-background/.test(css) && /\.profile-background/.test(css), 'CSS gates shelf chrome by background mode');
assert.ok(/\.profile-background #hero/.test(css), 'profile wallpaper clears Top Shelf hero');
assert.ok(/\.profile-background #details/.test(css), 'profile wallpaper clears details');
assert.ok(/\.profile-background #dots/.test(css), 'profile wallpaper clears dots');
assert.ok(/\.profile-background #watch/.test(css), 'profile wallpaper clears Play');
assert.ok(/\.profile-background #sky \.veil\s*\{\s*opacity:\s*0/.test(css),
  'profile Horizon is not covered by the legacy white veil');

// The Top Shelf's full screen recesses the Dock (36 px, 0.75 opacity) and keeps the tab bar: it never hides them.
assert.ok(/RECESS = 36, RECESS_A = 0\.75/.test(source), 'the Dock recesses 36 px to 0.75 in full screen');
assert.ok(!/300 \* full/.test(source), 'the Dock no longer drops 300 px in full screen');
assert.ok(!/\.full #tabs/.test(css), 'the tab bar stays in full screen');
assert.ok(!/\.drawer-open #pill/.test(css) && !/\.drawer-open #tabs/.test(css), 'the status pill and the tab bar stay with Control Center open');
assert.ok(/#drawer \{[^}]*top: 124px/.test(css), 'Control Center starts under the status pill');
// Control Center floats on the right, 560 wide from x 1300; the Dock's tiles are 244 x 146, 57 apart.
assert.ok(/#drawer \{[^}]*left: 1300px[^}]*width: 560px/.test(css), 'Control Center is a 560 px panel at x 1300');
assert.ok(/TILE_W = 244, TILE_H = 146, TILE_GAP = 57/.test(source), 'the Dock lays out 244 x 146 tiles, 57 apart');
assert.ok(/\.tile, \.cell \.face \{[^}]*width: 244px;[^}]*height: 146px;/.test(css), 'a Dock tile is 244 x 146');
// Control Center (the bubble): its pills in a fixed order, two a row; the profile panel (the pill) has the one Inputs
// pill (its sheet lists the ports). All Apps and Recent Apps live on the Dock and the Home key, in neither.
var controls = source.slice(source.indexOf('function ccControls'), source.indexOf('function ccProfilePills'));
var order = ['Power', 'Audio Output', 'Wi-Fi', 'Bluetooth', 'Dark Mode', 'Do Not Disturb', 'Sleep Timer', 'Brightness']
  .map(function (l) { return controls.indexOf("tr('" + l + "')"); });
assert.ok(order.every(function (at, i) { return at >= 0 && (i === 0 || at > order[i - 1]); }),
  'Control Center: Power, Audio Output, Wi-Fi, Bluetooth, Dark Mode, Do Not Disturb, Sleep Timer, Brightness: ' + order);
var profileMenu = source.slice(source.indexOf('function ccProfilePills'), source.indexOf('function ccRow'));
assert.strictEqual((profileMenu.match(/tr\('Inputs'\)/g) || []).length, 1, 'the profile panel has one Inputs pill');
assert.ok(!/tr\('(Inputs|Screensaver|Lock)'\)/.test(controls), 'Control Center leaves Inputs, the Screensaver and Lock to the profile panel');
assert.ok(!/All Apps|Recent Apps|hdmi/.test(controls + profileMenu), 'no All Apps, Recent Apps or per-port tiles');
assert.ok(/#ccbtn \{[^}]*width: 48px;[^}]*height: 48px;/.test(css), "Control Center's bubble is a 48 px circle");
assert.ok(/#pill \{[^}]*right: 45px/.test(css) && /\$\('ccbtn'\)\.style\.right = \(45 \+ pill\.offsetWidth \+ 14\)/.test(source),
  "the pill ends the cluster, Control Center's bubble 14 px left of it");
assert.ok(/#bright \{[^}]*pointer-events: none/.test(css), 'the brightness dimmer never takes the pointer');
// The volume HUD sits on the left edge, where an iPhone's buttons are, clear of the panel on the right.
assert.ok(/#volhud \{[^}]*left: 22px; top: 415px; width: 52px; height: 250px/.test(css), 'the volume HUD is a 52 x 250 pill, centre left');

console.log('home-background-test: passed');
