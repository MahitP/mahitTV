'use strict';

/* The web app's keys in a real browser: Home (H) pressed goes to the Dock and held opens Recent Apps, S shows and hides
 * the screensaver, + and − move the volume HUD, [ and ] the brightness, and Control Center has one Inputs pill and no All
 * Apps or Recent Apps; the bubble beside the pill, the weather card, a folder, the welcome setup and the sidebar Settings.
 * Builds the preview into out/webos (as home-background-dom-test does), or serves SITE when it's set. */
var assert = require('assert');
var child = require('child_process');
var fs = require('fs');
var http = require('http');
var path = require('path');
var chromium = require('playwright').chromium;
var root = path.join(__dirname, '..');
function weatherFixture() {
  var t = fs.readFileSync(path.join(root, 'tools', 'weather-fixture.json'), 'utf8');
  var h = 3600000, d = 24 * h, now = Date.now(), thisHour = Math.floor(now / h) * h, today = Math.floor(now / d) * d;
  function hour(ms) { return new Date(ms).toISOString().slice(0, 16); }
  function day(ms) { return new Date(ms).toISOString().slice(0, 10); }
  var hours = [], days = [], rises = [], sets = [];
  for (var i = 0; i < 48; i++) hours.push(hour(thisHour + (i - 1) * h));
  for (var j = 0; j < 8; j++) { days.push(day(today + j * d)); rises.push(hour(today + j * d + 6 * h)); sets.push(hour(today + j * d + 18 * h)); }
  return t.replace('{NOW}', JSON.stringify(hour(thisHour))).replace('{HOURS}', JSON.stringify(hours)).replace('{DAYS}', JSON.stringify(days))
    .replace('{SUNRISES}', JSON.stringify(rises)).replace('{SUNSETS}', JSON.stringify(sets));
}

var site = process.env.SITE ? path.resolve(process.env.SITE) : path.join(root, 'out', 'webos');
var types = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.jpg': 'image/jpeg', '.png': 'image/png', '.svg': 'image/svg+xml', '.ttf': 'font/ttf'};

// The example settings, as CI has: with a TMDB key of your own, the preview has no sample picks and no test seam.
// The preview's seeded Docks name apps its sample list has, or a slot quietly goes empty. Mahit's is the TV's: six, StbEmu last.
(function () {
  var tv = fs.readFileSync(path.join(root, 'web', 'tv.js'), 'utf8'), profiles = fs.readFileSync(path.join(root, 'web', 'profiles.js'), 'utf8');
  var sample = tv.slice(tv.indexOf('var SAMPLE = ['), tv.indexOf('];', tv.indexOf('var SAMPLE = ['))), seeds = profiles.match(/\[('[^']+'(?:, )?){6}\]\)/g) || [];
  assert.strictEqual(seeds.length, 2, 'two seeded Docks');
  seeds.forEach(function (dock) {
    dock.match(/'[^']+'/g).forEach(function (id) { assert.ok(sample.indexOf('[' + id + ',') >= 0, 'seeded Dock names an app the sample has: ' + id); });
  });
  assert.ok(/'com\.mvas\.stb\.emu\.[a-z]+'\]\)$/.test(seeds[0]), 'StbEmu is in slot 6 of Mahit\'s Dock: ' + seeds[0]);
})();
if (!process.env.SITE) child.execFileSync('python3', ['webos/build.py', '--preview', '--config', 'config.example.properties'], {cwd: root, stdio: 'inherit'});
var server = http.createServer(function (req, res) {
  var file = path.join(site, decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html');
  if (!file.startsWith(site) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, {'Content-Type': types[path.extname(file)] || 'application/octet-stream'});
  fs.createReadStream(file).pipe(res);
});

function state(page) {
  return page.evaluate(function () {
    var recents = document.querySelector('#recents');
    return {zone: FreeMyTV.state.zone, tab: FreeMyTV.state.tab, recents: recents.className.indexOf('open') >= 0,
      saver: FreeMyTV.saverOn(), volume: FreeMyTV.volume()};
  });
}

var browser = null;

(async function () {
  await new Promise(function (done) { server.listen(0, '127.0.0.1', done); });
  browser = await chromium.launch({headless: true});
  var page = await browser.newPage({viewport: {width: 1920, height: 1080}});
  var errors = [];
  page.on('pageerror', function (e) { errors.push(e.message); });
  await page.addInitScript(function () { localStorage.clear(); });
  // The forecast from tools/weather-fixture.json with times around now (as WeatherParseTest fills it), not the live
  // Open-Meteo: the weather card's check doesn't hang on the network.
  await page.route(/api\.open-meteo\.com/, function (route) { route.fulfill({contentType: 'application/json', body: weatherFixture()}); });
  await page.goto('http://127.0.0.1:' + server.address().port + '/index.html?nolock');
  await page.waitForTimeout(900);
  var s;

  // The profile panel (the pill): one Inputs pill (its sheet lists the ports), no All Apps, no Recent Apps, no port per tile.
  await page.evaluate(function () { FreeMyTV.profile(); });
  await page.waitForTimeout(300);
  var cc = await page.evaluate(function () { return FreeMyTV.cc(); });
  var labels = cc.map(function (it) { return it.label; });
  assert.strictEqual(labels.filter(function (l) { return l === 'Inputs'; }).length, 1, 'one Inputs tile: ' + JSON.stringify(labels));
  assert.ok(labels.indexOf('All Apps') < 0 && labels.indexOf('Recent Apps') < 0, 'no All Apps or Recent Apps tile: ' + JSON.stringify(labels));
  assert.ok(!labels.some(function (l) { return /^HDMI|^Live TV$/.test(l); }), 'no tile per input: ' + JSON.stringify(labels));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(400);
  await page.evaluate(function () { FreeMyTV.drawer(); });
  await page.waitForTimeout(1600);   // SHEET_OPEN's spring is still a few px out at 800 ms
  var panel = await page.evaluate(function () {
    var r = document.querySelector('#drawer').getBoundingClientRect();
    return {zone: FreeMyTV.state.zone, left: r.left, top: r.top, width: r.width, bottom: r.bottom, opacity: parseFloat(getComputedStyle(document.querySelector('#drawer')).opacity)};
  });
  assert.ok(panel.zone === 'drawer' && panel.opacity > 0.9, 'Control Center opens: ' + JSON.stringify(panel));
  assert.ok(Math.abs(panel.left - 1300) < 2 && Math.abs(panel.width - 560) < 2 && panel.top >= 120 && panel.bottom <= 1080,
    'Control Center floats at the right and fits the screen: ' + JSON.stringify(panel));
  await page.evaluate(function () { FreeMyTV.inputs(); });
  await page.waitForTimeout(700);
  var sheet = await page.evaluate(function () {
    var rows = document.querySelectorAll('#drawer .ccinputs .inrow'), checks = 0;
    for (var i = 0; i < rows.length; i++) if (rows[i].querySelector('.check')) checks++;
    return {zone: FreeMyTV.state.zone, rows: rows.length, checks: checks, opacity: parseFloat(getComputedStyle(document.querySelector('#drawer .ccinputs')).opacity)};
  });
  assert.ok(sheet.zone === 'drawer' && sheet.rows > 0 && sheet.checks === 1 && sheet.opacity > 0.9,
    'Inputs opens its sheet with one ✓ on the current input: ' + JSON.stringify(sheet));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);
  s = await state(page);
  assert.strictEqual(s.zone, 'drawer', 'Back leaves the Inputs sheet for Control Center');
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);
  s = await state(page);
  assert.notStrictEqual(s.zone, 'drawer', 'Back again closes Control Center');

  // Home pressed: the TV tab, the Dock focused, from anywhere.
  await page.evaluate(function () { FreeMyTV.tab(1); });
  await page.keyboard.down('h');
  await page.waitForTimeout(120);
  await page.keyboard.up('h');
  await page.waitForTimeout(300);
  s = await state(page);
  assert.ok(s.zone === 'shelf' && s.tab === 0 && !s.recents, 'H pressed goes to the Dock on the TV tab: ' + JSON.stringify(s));

  // Home held (450 ms): Recent Apps, the focused card 845 × 475 in the middle, its app's name above it.
  await page.keyboard.down('h');
  await page.waitForTimeout(650);
  s = await state(page);
  assert.ok(s.recents && s.zone === 'recents', 'H held opens Recent Apps: ' + JSON.stringify(s));
  await page.keyboard.up('h');
  await page.waitForTimeout(500);
  s = await state(page);
  assert.ok(s.recents && s.zone === 'recents', 'releasing a held H leaves Recent Apps open: ' + JSON.stringify(s));
  var deck = await page.evaluate(function () {
    var card = document.querySelector('#recents .card.focus') || document.querySelector('#recents .card');
    if (!card) return {empty: !!document.querySelector('#recents .empty')};
    var f = card.querySelector('.face').getBoundingClientRect(), n = card.querySelector('.name');
    return {left: f.left, top: f.top, width: f.width, height: f.height, name: n.textContent, nameBottom: n.getBoundingClientRect().bottom};
  });
  if (deck.empty === undefined) {
    assert.ok(Math.abs(deck.width - 845) < 30 && Math.abs(deck.height - 475) < 20 && Math.abs(deck.left + deck.width / 2 - 960) < 20,
      'the focused card is 845 × 475, centred: ' + JSON.stringify(deck));
    assert.ok(deck.name && deck.nameBottom <= deck.top, 'the app\'s name sits above its card: ' + JSON.stringify(deck));
  } else {
    assert.ok(deck.empty, 'an empty Recent Apps says so');
  }
  // H again takes it away, and so does Back.
  await page.keyboard.press('h');
  await page.waitForTimeout(400);
  s = await state(page);
  assert.ok(!s.recents && s.zone === 'shelf', 'H closes Recent Apps: ' + JSON.stringify(s));
  await page.keyboard.down('h');
  await page.waitForTimeout(650);
  await page.keyboard.up('h');
  await page.keyboard.press('Escape');
  await page.waitForTimeout(400);
  s = await state(page);
  assert.ok(!s.recents && s.zone !== 'recents', 'Back closes Recent Apps: ' + JSON.stringify(s));

  // S: the screensaver, and S again takes it away.
  await page.keyboard.press('s');
  await page.waitForTimeout(600);
  s = await state(page);
  assert.ok(s.saver, 'S shows the screensaver');
  await page.keyboard.press('s');
  await page.waitForTimeout(600);
  s = await state(page);
  assert.ok(!s.saver, 'S again hides the screensaver');

  // tvOS 12's Aerials: ↓ shows the place's name bottom left (the screensaver stays), → the next place; others wake.
  await page.evaluate(function () { Prefs.set('saverStyle', 'aerials'); });
  await page.keyboard.press('s');
  await page.waitForTimeout(700);
  var place = async function () {
    return page.evaluate(function () {
      var n = document.querySelector('#saver .place'), r = n ? n.getBoundingClientRect() : null;
      return {saver: FreeMyTV.saverOn(), text: n ? n.textContent : '', on: !!n && n.className.indexOf('on') >= 0, left: r && r.left, bottom: r && r.bottom};
    });
  };
  await page.keyboard.press('ArrowDown');
  await page.waitForTimeout(600);
  var pl = await place();
  assert.ok(pl.saver && pl.on && pl.text && Math.abs(pl.left - 80) < 2 && pl.bottom <= 1080 - 92 + 1 && pl.bottom > 900,
    '↓ shows the place bottom left, 32 px above the safe area: ' + JSON.stringify(pl));
  await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(300);
  var pl2 = await place();
  assert.ok(pl2.saver && pl2.on && pl2.text !== pl.text, '→ goes to the next place and names it: ' + JSON.stringify([pl, pl2]));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);
  assert.ok(!(await place()).saver, 'another key wakes the TV');
  // Shuffle: a real choice, first in the list, and the screensaver shows one of its looks.
  var shuffle = await page.evaluate(function () { Prefs.set('saverStyle', 'shuffle'); return Prefs.saverStyle(); });
  assert.strictEqual(shuffle, 'shuffle', 'Shuffle is kept as the screensaver');
  await page.keyboard.press('s');
  await page.waitForTimeout(500);
  assert.ok((await state(page)).saver, 'Shuffle shows a screensaver');
  await page.keyboard.press('s');
  await page.waitForTimeout(500);
  await page.evaluate(function () { Prefs.set('saverStyle', 'photos'); });

  // Control Center's Sleep Timer: tvOS 17's presets, Off → 15 min → 30 min → 1 h → 2 h → Off.
  var sleeps = await page.evaluate(function () {
    FreeMyTV.drawer();
    var cc = FreeMyTV.cc(), out = [], i, k;
    for (i = 0; i < cc.length; i++) if (cc[i].label === 'Sleep Timer') {
      FreeMyTV.state.drawer = i;
      var tiles = document.querySelectorAll('#drawer .cctile'), tile = null;
      for (k = 0; k < tiles.length; k++) if (tiles[k].textContent.indexOf('Sleep Timer') === 0) tile = tiles[k];
      for (k = 0; k < 5; k++) { FreeMyTV.activate(); out.push(tile.querySelector('.detail').textContent); }
    }
    return out;
  });
  assert.deepStrictEqual(sleeps, ['15 min', '30 min', '1 h', '2 h', 'Off'], 'the Sleep Timer steps through tvOS\'s presets');
  await page.keyboard.press('Escape');
  await page.waitForTimeout(400);

  // Text Size (tvOS 27): Large makes Settings' rows 115 %, Small 90 %.
  var sizes = await page.evaluate(function () {
    var out = {};
    ['small', 'default', 'large'].forEach(function (v) {
      Prefs.set('textSize', v);
      FreeMyTV.openSettings();
      var row = document.querySelector('#settings .cat');
      out[v] = row ? parseFloat(getComputedStyle(row).fontSize) : 0;
      FreeMyTV.home();
    });
    Prefs.set('textSize', 'default');
    return out;
  });
  assert.ok(Math.abs(sizes.small - sizes['default'] * 0.9) < 1.1 && Math.abs(sizes.large - sizes['default'] * 1.15) < 1.1,
    'Text Size scales Settings\' text: ' + JSON.stringify(sizes));

  // + and −: the volume (the preview starts at 30, in steps of 5) on the left HUD, which hides itself after 2.2 s.
  s = await state(page);
  assert.strictEqual(s.volume.level, 30, 'the preview volume starts at 30');
  await page.keyboard.press('=');
  await page.waitForTimeout(450);
  s = await state(page);
  var hud = await page.evaluate(function () {
    var h = document.querySelector('#volhud'), r = h.getBoundingClientRect();
    return {left: r.left, width: r.width, height: r.height, opacity: parseFloat(getComputedStyle(h).opacity)};
  });
  assert.ok(s.volume.level === 35 && s.volume.shown, '+ raises the volume and shows the HUD: ' + JSON.stringify(s.volume));
  assert.ok(hud.opacity > 0.8 && Math.abs(hud.width - 72) < 2 && Math.abs(hud.height - 320) < 2 && hud.left > 10 && hud.left < 80,
    'the HUD is 72 × 320 on the left edge: ' + JSON.stringify(hud));
  await page.keyboard.press('-');
  await page.keyboard.press('-');
  await page.waitForTimeout(100);
  s = await state(page);
  assert.strictEqual(s.volume.level, 25, '− lowers the volume');
  await page.waitForTimeout(2600);
  s = await state(page);
  assert.ok(!s.volume.shown, 'the HUD hides itself after 2.2 s');
  for (var i = 0; i < 16; i++) await page.keyboard.press('=');
  s = await state(page);
  assert.strictEqual(s.volume.level, 100, 'the volume stops at 100');

  // [ and ]: the brightness in steps of 5 (from 100), the same HUD with the sun, and a dimmer over everything.
  await page.waitForTimeout(2600);
  var b0 = await page.evaluate(function () { return FreeMyTV.brightness(); });
  assert.strictEqual(b0, 100, 'the brightness starts at 100');
  await page.keyboard.press('[');
  await page.keyboard.press('[');
  await page.waitForTimeout(450);
  var bright = await page.evaluate(function () {
    var d = document.querySelector('#bright'), h = document.querySelector('#volhud');
    return {level: FreeMyTV.brightness(), dim: parseFloat(getComputedStyle(d).opacity), mode: h.className, hud: FreeMyTV.volume().shown};
  });
  assert.ok(bright.level === 90 && Math.abs(bright.dim - 0.065) < 0.01 && /bright/.test(bright.mode) && bright.hud,
    '[ dims the screen and shows the brightness HUD: ' + JSON.stringify(bright));
  await page.keyboard.press(']');
  await page.keyboard.press(']');
  await page.keyboard.press(']');
  await page.waitForTimeout(300);
  bright = await page.evaluate(function () { return {level: FreeMyTV.brightness(), dim: parseFloat(getComputedStyle(document.querySelector('#bright')).opacity)}; });
  assert.ok(bright.level === 100 && bright.dim < 0.001, '] brings it back up and stops at 100: ' + JSON.stringify(bright));

  // The owner's TV: its Dock (Netflix, Prime Video, YouTube, Apple TV, Disney+, StbEmu) and All Apps in their order.
  var lists = await page.evaluate(function () { localStorage.removeItem('mahittv.profile.' + Profiles.current().id); return FreeMyTV.apps(); });
  var wantGrid = ['Apple', 'YouTube Music', 'Spotify', 'Disney+', 'Tubi', 'YouTube TV', 'Twitch', 'Hulu', 'HBO Max',
    'Peacock TV', 'Paramount+', 'Instagram', 'Roblox', 'TCL', 'TV & Utilities'];
  assert.deepStrictEqual(lists.grid, wantGrid, 'All Apps follows the TV\'s order, the folders at its end: ' + JSON.stringify(lists.grid));
  assert.ok(lists.grid.length <= 18, 'All Apps fits one screen (3 rows of 6 under the Dock): ' + lists.grid.length);
  assert.deepStrictEqual(lists.tcl, ['Works with Alexa', 'Media Player', 'User Guide', 'Quick Panel', 'T-Solo', 'TCL Home', 'T-Exhibition'],
    'the TCL folder holds TCL\'s 7 utilities in order: ' + JSON.stringify(lists.tcl));
  assert.deepStrictEqual(lists.utilities.slice(0, 4), ['Live TV', 'TCL TV+', 'Google TV Freeplay', 'BrowseHere'],
    'TV & Utilities starts with what\'s for watching: ' + JSON.stringify(lists.utilities));

  // Arrow keys: ↓ onto All Apps' shorter last row lands on its last app; ↑ from the tabs reaches Control Center's
  // bubble without switching tabs, and ← goes back to that tab.
  var nav = await page.evaluate(function () {
    FreeMyTV.home();
    var out = {}, s = FreeMyTV.state, n = FreeMyTV.apps().grid.length;
    s.zone = 'apps'; s.grid = Math.min(n - 1, 6 + 5);   // row 2, column 6 (or the last one)
    FreeMyTV.move(0, 1);
    out.down = s.grid; out.last = n - 1;
    FreeMyTV.home();
    s.zone = 'tabs'; s.tab = 0;
    FreeMyTV.move(0, -1);
    out.up = s.zone + ' ' + s.top + ' ' + s.tab;
    FreeMyTV.move(-1, 0);   // the preview's sample song plays: the dynamic dot left of the bubble first
    out.dot = s.zone + ' ' + s.top;
    FreeMyTV.move(-1, 0);
    out.left = s.zone + ' ' + s.tab;
    FreeMyTV.home();
    return out;
  });
  assert.ok(nav.down === nav.last || nav.last < 12, '↓ onto a shorter row: its last app: ' + JSON.stringify(nav));
  assert.ok(nav.up === 'top 3 0' && nav.dot === 'top 2' && nav.left === 'tabs 0',
    '↑ from the TV tab: the bubble, tab kept; ← the dynamic dot, ← back: ' + JSON.stringify(nav));
  assert.deepStrictEqual(lists.apple, ['Apple Music', 'FaceTime (guide)', 'Fitness+ (guide)', 'Podcasts (guide)'],
    'the Apple folder, where Apple Music was: Apple Music, then the FaceTime, Fitness+ and Podcasts guides: ' + JSON.stringify(lists.apple));
  assert.deepStrictEqual(lists.dock, ['Netflix', 'Prime Video', 'YouTube', 'Apple TV', 'Crunchyroll', 'StbEmu'],
    'the Dock: Netflix, Prime Video, YouTube, Apple TV, Crunchyroll, StbEmu: ' + JSON.stringify(lists.dock));
  // The top right: the dynamic dot (the sample song plays), Control Center's bubble, then the pill. The bubble opens
  // Control Center (its pills in a fixed order, the volume under them); the pill opens the profile panel.
  await page.evaluate(function () { FreeMyTV.home(); FreeMyTV.tab(2); });
  await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(300);
  assert.strictEqual(await page.evaluate(function () { return FreeMyTV.state.top; }), 2, '→ from Welly reaches the dynamic dot first');
  await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(300);
  var top = await page.evaluate(function () {
    var b = document.querySelector('#ccbtn').getBoundingClientRect(), p = document.querySelector('#pill').getBoundingClientRect();
    return {zone: FreeMyTV.state.zone, top: FreeMyTV.state.top, bubble: document.querySelector('#ccbtn').className, left: b.right <= p.left};
  });
  assert.ok(top.zone === 'top' && top.top === 3 && /focus/.test(top.bubble) && top.left, '→ from Welly reaches the bubble, left of the pill: ' + JSON.stringify(top));
  await page.keyboard.press('Enter');
  await page.waitForTimeout(800);
  s = await state(page);
  assert.strictEqual(s.zone, 'drawer', 'OK on the bubble opens Control Center');
  var controls = await page.evaluate(function () { return FreeMyTV.cc().filter(function (it) { return it.kind === 'tile'; }).map(function (it) { return it.label; }); });
  assert.deepStrictEqual(controls, ['Power', 'Audio Output', 'Wi-Fi', 'Bluetooth', 'Dark Mode', 'Do Not Disturb', 'Sleep Timer', 'Brightness', 'Speaker Mode', 'Listen Alone'],
    'Control Center: its pills in order, two a row (Speaker Mode | Listen Alone last)');
  // The playback card, first: OK plays and pauses, ← → skip, and it never leaves the panel.
  var media = await page.evaluate(function () {
    FreeMyTV.state.drawer = 0;
    var card = document.querySelector('#drawer .ccmedia'), play = card.querySelector('.mb.play'), out = {top: card.getBoundingClientRect().top < document.querySelector('#drawer .cctile').getBoundingClientRect().top};
    out.before = card.querySelector('.line').textContent;
    out.pausedBefore = play.className.indexOf('paused') >= 0;
    FreeMyTV.activate();
    out.pausedAfter = play.className.indexOf('paused') >= 0;
    FreeMyTV.activate();
    return out;
  });
  assert.ok(media.top && media.pausedBefore === false && media.pausedAfter === true, 'the card is first, and OK pauses it: ' + JSON.stringify(media));
  await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(150);
  var skipped = await page.evaluate(function () { return {song: document.querySelector('#drawer .ccmedia .line').textContent, zone: FreeMyTV.state.zone}; });
  assert.ok(skipped.song !== media.before && skipped.zone === 'drawer', '→ skips, in place: ' + JSON.stringify(skipped));
  await page.keyboard.press('ArrowLeft');
  await page.waitForTimeout(150);
  skipped = await page.evaluate(function () { return {song: document.querySelector('#drawer .ccmedia .line').textContent, zone: FreeMyTV.state.zone}; });
  assert.ok(skipped.song === media.before && skipped.zone === 'drawer', '← skips back, and doesn\'t close the panel: ' + JSON.stringify(skipped));
  // As TimeCheckTest, on the web's copy: New Jersey in GMT is wrong; the sun's day is the same whatever the zone.
  var zone = await page.evaluate(function () {
    var h = 3600000, rise = 1793000000000, set = rise + 10 * h;
    return [Data.zoneLooksWrong(0, -74.5), Data.zoneLooksWrong(-300, -74.5), Data.zoneLooksWrong(-240, '-74.5'), Data.zoneLooksWrong(780, -171.8), Data.zoneLooksWrong(0, ''),
      Data.sunriseOfDay(set + 2 * h, rise, set) === rise, Data.sunriseOfDay(rise - h, rise, set) === rise, Data.sunriseOfDay(rise + 72 * h + h, rise, set) === rise + 72 * h];
  });
  assert.deepStrictEqual(zone, [true, false, false, false, false, true, true, true], 'the time zone check and the sun\'s day');
  var grid = await page.evaluate(function () {
    var t = document.querySelectorAll('#drawer .cctile'), out = [];
    for (var i = 0; i < t.length; i++) { var r = t[i].getBoundingClientRect(); out.push([Math.round(r.left), Math.round(r.top)]); }
    var v = document.querySelector('#drawer .ccvolume').getBoundingClientRect();
    return {tiles: out, volumeTop: v.top, lastBottom: t[t.length - 1].getBoundingClientRect().bottom};
  });
  assert.ok(grid.tiles.every(function (p, i) { return i % 2 === 0 || (Math.abs(p[1] - grid.tiles[i - 1][1]) < 8 && p[0] > grid.tiles[i - 1][0]); }) && grid.volumeTop > grid.lastBottom,
    'two pills a row, the volume under them: ' + JSON.stringify(grid));
  var dark = await page.evaluate(function () {
    var cc = FreeMyTV.cc(), out = [document.body.className.indexOf('night') >= 0], i;
    for (i = 0; i < cc.length; i++) if (cc[i].label === 'Dark Mode') {
      FreeMyTV.state.drawer = i;
      FreeMyTV.activate(); out.push(Prefs.theme(), document.body.className.indexOf('night') >= 0);
      FreeMyTV.activate(); out.push(Prefs.theme(), document.body.className.indexOf('night') >= 0);
    }
    return out;
  });
  // From whatever shows (the preview may start at night): the other look, then back, each set as this profile's theme.
  assert.deepStrictEqual(dark.slice(1), dark[0] ? ['light', false, 'dark', true] : ['dark', true, 'light', false],
    'Dark Mode switches the look and back: ' + JSON.stringify(dark));
  var bright = await page.evaluate(function () {
    var cc = FreeMyTV.cc(), out = [], i, k;
    for (i = 0; i < cc.length; i++) if (cc[i].label === 'Brightness') { FreeMyTV.state.drawer = i; for (k = 0; k < 4; k++) { FreeMyTV.activate(); out.push(FreeMyTV.brightness()); } }
    return out;
  });
  assert.deepStrictEqual(bright, [75, 50, 25, 100], 'Brightness steps 75, 50, 25, then back to 100');
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);
  await page.evaluate(function () { FreeMyTV.home(); FreeMyTV.tab(2); FreeMyTV.state.zone = 'top'; FreeMyTV.state.top = 4; });
  await page.keyboard.press('Enter');
  await page.waitForTimeout(800);
  var profile = await page.evaluate(function () {
    return {zone: FreeMyTV.state.zone, labels: FreeMyTV.cc().map(function (it) { return it.label; }), faces: document.querySelectorAll('#drawer .who').length};
  });
  assert.ok(profile.zone === 'drawer' && profile.faces >= 2 && profile.labels.indexOf('Lock') >= 0 && profile.labels.indexOf('Wi-Fi') < 0,
    'OK on the pill opens the profile panel (profiles, Lock; no Wi-Fi): ' + JSON.stringify(profile));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(600);

  // The weather card: the next 6 hours, each label once (the night the clocks go back has 1 AM twice: data.js).
  // The card is built when it opens: first the weather itself (the sample can arrive late on a busy machine).
  await page.waitForFunction(function () { return !!Data.weather; }, null, {timeout: 15000}).catch(function () {});
  await page.evaluate(function () { FreeMyTV.home(); FreeMyTV.forecast(); });
  // Until its hours are in (the sample forecast can arrive late on a busy machine), then its spring settles.
  await page.waitForFunction(function () { return document.querySelectorAll('#forecast .hour .t').length > 0; }, null, {timeout: 5000})
    .catch(function () {});
  await page.waitForTimeout(800);
  var card = await page.evaluate(function () {
    var p = document.querySelector('#forecast .panel').getBoundingClientRect();
    return {left: p.left, top: p.top, width: p.width, hours: [].map.call(document.querySelectorAll('#forecast .hour .t'), function (e) { return e.textContent; }),
      days: document.querySelectorAll('#forecast .day .fill').length,
      data: Data.weather ? (Data.weather.hours24 || []).length : -1, text: document.querySelector('#forecast .panel').textContent.slice(0, 80)};
  });
  var unique = card.hours.filter(function (h, i) { return card.hours.indexOf(h) === i; });
  assert.ok(card.hours.length >= 6 - 1 && unique.length === card.hours.length && card.days > 0, 'the weather card: unique hours and day bars: ' + JSON.stringify(card));
  assert.ok(Math.abs(card.left - 140) < 30 && Math.abs(card.width - 720) < 50, 'the weather card floats at x 140, 720 wide: ' + JSON.stringify(card));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(600);

  // A folder: OK opens it over All Apps, Back closes it onto the folder.
  var opened = await page.evaluate(function () { return FreeMyTV.folder(); });
  assert.ok(opened, 'All Apps has a folder (Apple, first)');
  await page.waitForTimeout(700);
  s = await state(page);
  var folder = await page.evaluate(function () { return {cells: document.querySelectorAll('#folder .cell').length, badge: document.querySelector('#folder .badge').textContent}; });
  assert.ok(s.zone === 'folder' && folder.cells >= 2 && folder.badge, 'the folder opens: ' + JSON.stringify(folder));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);

  // The Apple folder's FaceTime: OK opens its AirPlay guide (steps and a QR code that loads), OK closes it.
  await page.evaluate(function () { FreeMyTV.folder(); });
  await page.waitForTimeout(700);
  await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(150);
  await page.keyboard.press('Enter');
  await page.waitForTimeout(500);
  var guide = await page.evaluate(function () {
    var g = document.querySelector('#guide'), img = g && g.querySelector('.qr img');
    return g && {open: g.className.indexOf('open') >= 0, title: g.querySelector('.title').textContent,
      steps: g.querySelectorAll('.steps li').length, qr: img.complete && img.naturalWidth > 0};
  });
  assert.ok(guide && guide.open && guide.title === 'FaceTime' && guide.steps === 3 && guide.qr, 'FaceTime opens its guide: ' + JSON.stringify(guide));
  await page.keyboard.press('Enter');
  await page.waitForTimeout(500);
  assert.ok(await page.evaluate(function () { return !document.querySelector('#guide'); }), 'OK closes the guide');
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);

  // AirPods (A in the preview): the card slides in at the top right, the glyph shows by the bubble, then the card goes.
  await page.keyboard.press('a');
  await page.waitForTimeout(900);
  var air = await page.evaluate(function () {
    var t = document.querySelector('#airtoast'), r = t.getBoundingClientRect();
    return {a: parseFloat(getComputedStyle(t).opacity), title: t.querySelector('.title').textContent, top: r.top, right: r.right,
      glyph: document.querySelector('#airglyph').className.indexOf('on') >= 0};
  });
  assert.ok(air.a > 0.9 && air.title && air.glyph && Math.abs(air.top - 108) < 4 && air.right > 1800, 'A: the AirPods card and glyph: ' + JSON.stringify(air));
  await page.waitForTimeout(3600);
  var gone = await page.evaluate(function () { return parseFloat(getComputedStyle(document.querySelector('#airtoast')).opacity); });
  assert.ok(gone < 0.05, 'the AirPods card leaves after 3.5 s: ' + gone);
  await page.keyboard.press('a');
  await page.waitForTimeout(300);
  assert.ok(await page.evaluate(function () { return document.querySelector('#airglyph').className.indexOf('on') < 0; }), 'A again: the AirPods leave');
  s = await state(page);
  assert.strictEqual(s.zone, 'apps', 'Back closes the folder onto All Apps');
  await page.evaluate(function () { FreeMyTV.home(); });

  // The welcome setup: four steps, the theme shown as it's picked, then home (and it doesn't come back).
  await page.evaluate(function () { Prefs.set('theme', 'light'); FreeMyTV.setup(); });
  await page.waitForTimeout(600);
  await page.keyboard.press('Enter');   // Get Started
  await page.waitForTimeout(300);
  await page.keyboard.press('ArrowRight');   // Theme: Light to Dark
  await page.waitForTimeout(300);
  var dark = await page.evaluate(function () { return document.body.className.indexOf('night') >= 0 && Prefs.theme() === 'dark'; });
  assert.ok(dark, 'the setup shows the theme as it is picked');
  for (var e = 0; e < 3; e++) { await page.keyboard.press('ArrowDown'); await page.waitForTimeout(80); }
  await page.keyboard.press('Enter');   // Continue
  await page.waitForTimeout(300);
  await page.keyboard.press('ArrowDown');   // Top Shelf: Recommendations & News
  await page.keyboard.press('Enter');
  for (e = 0; e < 4; e++) { await page.keyboard.press('ArrowDown'); await page.waitForTimeout(80); }
  await page.keyboard.press('Enter');   // Continue
  await page.waitForTimeout(300);
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');   // Go to Home Screen
  await page.waitForTimeout(700);
  var done = await page.evaluate(function () { return {done: Prefs.setupDone(), shelf: Prefs.topShelf(), showing: parseFloat(getComputedStyle(document.querySelector('#setup')).opacity)}; });
  assert.ok(done.done && done.shelf === 'picks' && done.showing < 0.2, 'the setup ends on the home screen with its choices: ' + JSON.stringify(done));

  // Settings as a floating sidebar (Appearance & Startup → Settings Style).
  await page.evaluate(function () { Prefs.set('settingsStyle', 'sidebar'); FreeMyTV.openSettings(); });
  await page.waitForTimeout(600);
  var side = await page.evaluate(function () { var r = document.querySelector('#settings .side').getBoundingClientRect(); return {left: r.left, width: r.width, cls: document.querySelector('#settings').className}; });
  assert.ok(/sidebar/.test(side.cls) && Math.abs(side.left - 1080) < 20 && Math.abs(side.width - 760) < 20, 'Settings floats as a sidebar at x 1080: ' + JSON.stringify(side));

  // Not while typing: H and S belong to Welly's text field (the handler checks before it acts).
  var source = fs.readFileSync(path.join(root, 'web', 'home.js'), 'utf8');
  assert.ok(/if \(!typing && \(k === 72 \|\| k === 83/.test(source), 'H and S stay with Welly\'s text field while it types');

  // The city and TMDB key sheets: keys type into the field (H, S and A are home screen keys elsewhere), Back leaves the
  // field, then the sheet.
  await page.reload();
  await page.waitForTimeout(1500);
  await page.evaluate(function () { FreeMyTV.openSettings(); });
  await page.waitForTimeout(300);
  await page.keyboard.press('ArrowDown');
  await page.waitForTimeout(150);
  await page.keyboard.press('ArrowDown');
  await page.waitForTimeout(150);
  await page.keyboard.press('Enter');   // Location & Media: its rows, City first
  await page.waitForTimeout(300);
  async function typeInSheet(id, focusText) {
    for (var tries = 0; tries < 30; tries++) {
      var text = await page.evaluate(function () { var f = document.querySelector('#settings .srow.focus'); return f ? f.textContent : ''; });
      if (text.indexOf(focusText) >= 0) break;
      await page.keyboard.press('ArrowDown');
      await page.waitForTimeout(40);
    }
    await page.keyboard.press('Enter');
    await page.waitForTimeout(300);
    var open = await page.evaluate(function (id) {
      var f = document.querySelector('#settings .srow.focus');
      return {sheet: document.querySelector(id).className, tag: document.activeElement.tagName, zone: FreeMyTV.state.zone,
        row: f ? f.textContent : '', title: (document.querySelector('#settings .rtitle') || {}).textContent};
    }, id);
    assert.ok(/\bopen\b/.test(open.sheet), id + ' opens from ' + focusText + ': ' + JSON.stringify(open));
    assert.strictEqual(open.tag, 'INPUT', id + ': the field takes the keys');
    await page.keyboard.type('has');
    assert.strictEqual(await page.evaluate(function (id) { return document.querySelector(id + ' input').value; }, id), 'has', id + ': typed');
    await page.keyboard.press('Escape');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(200);
    assert.ok(!/\bopen\b/.test(await page.evaluate(function (id) { return document.querySelector(id).className; }, id)), id + ': Back closes it');
  }
  await typeInSheet('#citypick', 'City');
  await typeInSheet('#keypick', 'TMDB key');

  assert.deepStrictEqual(errors, [], 'no page errors');
  await browser.close(); server.close();
  console.log('web-keys-test: passed');
})().catch(async function (err) {
  // A failed check must not leave the browser running: the process would never exit and CI would hang.
  console.error(err.stack || err);
  try { if (browser) await browser.close(); } catch (e) { /* exiting anyway */ }
  server.close();
  process.exit(1);
});
