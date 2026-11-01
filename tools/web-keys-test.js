'use strict';

/* The web app's keys in a real browser: Home (H) pressed goes to the Dock and held opens Recent Apps, S shows and hides
 * the screensaver, + and − move the volume HUD, and Control Center has one Inputs tile and no All Apps or Recent Apps.
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

(async function () {
  await new Promise(function (done) { server.listen(0, '127.0.0.1', done); });
  var browser = await chromium.launch({headless: true});
  var page = await browser.newPage({viewport: {width: 1920, height: 1080}});
  var errors = [];
  page.on('pageerror', function (e) { errors.push(e.message); });
  await page.addInitScript(function () { localStorage.clear(); });
  await page.goto('http://127.0.0.1:' + server.address().port + '/index.html?nolock');
  await page.waitForTimeout(900);
  var s;

  // Control Center: one Inputs tile (its sheet lists the ports), no All Apps, no Recent Apps, no port per tile.
  var cc = await page.evaluate(function () { return FreeMyTV.cc(); });
  var labels = cc.map(function (it) { return it.label; });
  assert.strictEqual(labels.filter(function (l) { return l === 'Inputs'; }).length, 1, 'one Inputs tile: ' + JSON.stringify(labels));
  assert.ok(labels.indexOf('All Apps') < 0 && labels.indexOf('Recent Apps') < 0, 'no All Apps or Recent Apps tile: ' + JSON.stringify(labels));
  assert.ok(!labels.some(function (l) { return /^HDMI|^Live TV$/.test(l); }), 'no tile per input: ' + JSON.stringify(labels));
  await page.evaluate(function () { FreeMyTV.drawer(); });
  await page.waitForTimeout(800);
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

  // Not while typing: H and S belong to Welly's text field (the handler checks before it acts).
  var source = fs.readFileSync(path.join(root, 'web', 'home.js'), 'utf8');
  assert.ok(/if \(!typing && \(k === 72 \|\| k === 83/.test(source), 'H and S stay with Welly\'s text field while it types');

  assert.deepStrictEqual(errors, [], 'no page errors');
  await browser.close(); server.close();
  console.log('web-keys-test: passed');
})().catch(function (err) { server.close(); console.error(err.stack || err); process.exitCode = 1; });
