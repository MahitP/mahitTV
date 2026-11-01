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
var site = process.env.SITE ? path.resolve(process.env.SITE) : path.join(root, 'out', 'webos');
var types = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.jpg': 'image/jpeg', '.png': 'image/png', '.svg': 'image/svg+xml', '.ttf': 'font/ttf'};

if (!process.env.SITE) child.execFileSync('python3', ['webos/build.py', '--preview'], {cwd: root, stdio: 'inherit'});
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

var browser;
(async function () {
  await new Promise(function (done) { server.listen(0, '127.0.0.1', done); });
  browser = await chromium.launch({headless: true});
  var page = await browser.newPage({viewport: {width: 1920, height: 1080}});
  var errors = [];
  page.on('pageerror', function (e) { errors.push(e.message); });
  await page.addInitScript(function () { localStorage.clear(); });
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

  // The top right: Control Center's bubble, then the pill. The bubble opens Control Center (the brightness and volume
  // sliders take ↑ ↓ there, the Appearance pill switches the theme); the pill opens the profile panel.
  await page.evaluate(function () { FreeMyTV.home(); FreeMyTV.tab(2); });
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
  var controls = await page.evaluate(function () { return FreeMyTV.cc().map(function (it) { return it.label; }); });
  assert.ok(controls.indexOf('Wi-Fi') >= 0 && controls.indexOf('Appearance') >= 0 && controls.indexOf('Inputs') < 0 && controls.indexOf('Lock') < 0,
    'Control Center holds the controls, not Inputs or Lock: ' + JSON.stringify(controls));
  var sliders = await page.evaluate(function () {
    return [].map.call(document.querySelectorAll('#drawer .ccslider'), function (e) { var r = e.getBoundingClientRect(); return {w: r.width, h: r.height}; });
  });
  assert.ok(sliders.length === 2 && sliders.every(function (r) { return r.h > r.w * 1.5; }), 'two vertical sliders: ' + JSON.stringify(sliders));
  var themes = await page.evaluate(function () {
    var cc = FreeMyTV.cc(), out = [], i, k;
    Prefs.set('theme', 'auto');
    for (i = 0; i < cc.length; i++) if (cc[i].label === 'Appearance') { FreeMyTV.state.drawer = i; for (k = 0; k < 3; k++) { FreeMyTV.activate(); out.push(Prefs.theme()); } }
    return out;
  });
  assert.deepStrictEqual(themes, ['light', 'dark', 'auto'], 'Appearance cycles Light, Dark, Automatic');
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
  await page.evaluate(function () { FreeMyTV.home(); FreeMyTV.forecast(); });
  await page.waitForTimeout(800);
  var card = await page.evaluate(function () {
    var p = document.querySelector('#forecast .panel').getBoundingClientRect();
    return {left: p.left, top: p.top, width: p.width, hours: [].map.call(document.querySelectorAll('#forecast .hour .t'), function (e) { return e.textContent; }),
      days: document.querySelectorAll('#forecast .day .fill').length};
  });
  var unique = card.hours.filter(function (h, i) { return card.hours.indexOf(h) === i; });
  assert.ok(card.hours.length >= 6 - 1 && unique.length === card.hours.length && card.days > 0, 'the weather card: unique hours and day bars: ' + JSON.stringify(card));
  assert.ok(Math.abs(card.left - 140) < 30 && Math.abs(card.width - 720) < 50, 'the weather card floats at x 140, 720 wide: ' + JSON.stringify(card));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(600);

  // A folder: OK opens it over All Apps, Back closes it onto the folder.
  var opened = await page.evaluate(function () { return FreeMyTV.folder(); });
  assert.ok(opened, 'All Apps has a folder (TV & Utilities)');
  await page.waitForTimeout(700);
  s = await state(page);
  var folder = await page.evaluate(function () { return {cells: document.querySelectorAll('#folder .cell').length, badge: document.querySelector('#folder .badge').textContent}; });
  assert.ok(s.zone === 'folder' && folder.cells >= 2 && folder.badge, 'the folder opens: ' + JSON.stringify(folder));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);
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

  assert.deepStrictEqual(errors, [], 'no page errors');
  await browser.close(); server.close();
  console.log('web-keys-test: passed');
})().catch(function (err) {
  // Close the browser too, or a failed check leaves Chromium running and tools/check.sh never ends.
  console.error(err.stack || err); process.exitCode = 1; server.close();
  if (browser) browser.close().catch(function () {});
});
