'use strict';

/* Moving apps in the web app, in a real browser: OK held on a Dock app lifts it (the Dock closes up, the cursor and
 * the hint show); ↓ → OK on another app names a new folder, which is saved for the profile and kept after a reload;
 * Back while moving puts everything back. Serves SITE when it's set, else builds the preview into out/webos. */
var assert = require('assert');
var child = require('child_process');
var fs = require('fs');
var http = require('http');
var path = require('path');
var chromium = require('playwright').chromium;
var root = path.join(__dirname, '..');
var site = process.env.SITE ? path.resolve(process.env.SITE) : path.join(root, 'out', 'webos');
var types = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.jpg': 'image/jpeg', '.png': 'image/png', '.svg': 'image/svg+xml', '.ttf': 'font/ttf'};
if (!process.env.SITE) child.execFileSync('python3', ['webos/build.py', '--preview', '--config', 'config.example.properties'], {cwd: root, stdio: 'inherit'});
var server = http.createServer(function (req, res) {
  var file = path.join(site, decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html');
  if (!file.startsWith(site) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, {'Content-Type': types[path.extname(file)] || 'application/octet-stream'});
  fs.createReadStream(file).pipe(res);
});

function wait(page, ms) { return page.waitForTimeout(ms); }
async function tap(page, key, n) { for (var i = 0; i < (n || 1); i++) { await page.keyboard.press(key); await wait(page, 140); } }
async function hold(page) { await page.keyboard.down('Enter'); await wait(page, 750); await page.keyboard.up('Enter'); await wait(page, 300); }
function look(page) {
  return page.evaluate(function () {
    return {dock: document.querySelectorAll('#tiles .tile').length, moving: document.getElementById('move').className.indexOf('on') >= 0,
      hint: document.getElementById('movehint').textContent, cells: Array.prototype.map.call(document.querySelectorAll('#apps .cell .label'), function (l) { return l.textContent; }),
      layout: Prefs.layout(), shelf: Prefs.shelf(), naming: document.getElementById('namecard').style.visibility === 'visible',
      pills: Array.prototype.map.call(document.querySelectorAll('#namecard .pill'), function (p) { return p.textContent; })};
  });
}

(async function () {
  await new Promise(function (done) { server.listen(0, '127.0.0.1', done); });
  var browser = await chromium.launch({headless: true});
  var page = await browser.newPage({viewport: {width: 1920, height: 1080}});
  var errors = [];
  page.on('pageerror', function (e) { errors.push(e.message); });
  await page.addInitScript(function () { if (!sessionStorage.getItem('kept')) { localStorage.clear(); sessionStorage.setItem('kept', '1'); } });
  var url = 'http://127.0.0.1:' + server.address().port + '/index.html?nolock';
  await page.goto(url);
  await wait(page, 900);
  var start = await look(page);
  assert.strictEqual(start.dock, 6, 'six on the Dock');

  // A tap opens; a hold lifts.
  await tap(page, 'ArrowRight', 2);
  await hold(page);
  var s = await look(page);
  assert.ok(s.moving, 'held: moving');
  assert.strictEqual(s.dock, 5, 'the Dock closes up behind it');
  assert.ok(/Where it was/.test(s.hint), 'the hint says OK changes nothing there: ' + s.hint);

  // Back: everything as it was.
  await tap(page, 'ArrowDown');
  await tap(page, 'ArrowRight');
  await page.keyboard.press('Escape');
  await wait(page, 1200);
  s = await look(page);
  assert.ok(!s.moving, 'Back: no longer moving');
  assert.strictEqual(s.dock, 6, 'Back: the Dock as it was');
  assert.deepStrictEqual(s.cells, start.cells, 'Back: All Apps as it was');
  assert.deepStrictEqual(s.layout, start.layout, 'Back: the saved layout as it was (the first load kept the order)');
  assert.ok(start.layout && start.layout.grid.length === start.cells.length, 'the first load keeps the order, so new apps go at the end');

  // ↓ → onto an app, OK: a folder, named from the card.
  await tap(page, 'ArrowRight', 2);
  await hold(page);
  await tap(page, 'ArrowDown');
  await tap(page, 'ArrowRight');
  s = await look(page);
  assert.ok(/New folder with/.test(s.hint), 'on an app: a new folder: ' + s.hint);
  await tap(page, 'Enter');
  await wait(page, 400);
  s = await look(page);
  assert.ok(s.naming && s.pills.length >= 4, 'the naming card, with names to pick: ' + JSON.stringify(s.pills));
  var name = s.pills[0];
  await tap(page, 'Enter');
  await wait(page, 1200);
  s = await look(page);
  assert.ok(!s.moving && !s.naming, 'done');
  assert.strictEqual(s.dock, 5, 'off the Dock');
  assert.ok(s.cells.indexOf(name) >= 0, 'the folder "' + name + '" is in All Apps: ' + JSON.stringify(s.cells));
  assert.strictEqual(s.layout.folders.length, 1, 'saved: one folder');
  assert.strictEqual(s.layout.folders[0].name, name, 'saved with its name');
  assert.strictEqual(s.shelf.length, 5, 'the Dock saved');

  // Kept after a reload.
  await page.reload();
  await wait(page, 900);
  s = await look(page);
  assert.strictEqual(s.dock, 5, 'reloaded: the Dock as moved');
  assert.ok(s.cells.indexOf(name) >= 0, 'reloaded: the folder is there');
  assert.deepStrictEqual(errors, [], 'no page errors');
  console.log('web-move-test: passed');
  await browser.close();
  server.close();
})().catch(function (e) { console.error(e); process.exit(1); });
