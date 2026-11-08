'use strict';

/* The dynamic dot in the web preview (DESIGN-DYNAMIC-DOT.md): with the sample song playing it sits beside the tab bar,
 * the pair centred; → from Welly opens it into a capsule that overlaps nothing (the pair slides left if it must);
 * paused and left, it goes after its grace and the bar centres again; playing again brings it back.
 * Serves SITE when it's set, else builds the preview into out/webos. */
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
async function tap(page, key, n) { for (var i = 0; i < (n || 1); i++) { await page.keyboard.press(key); await wait(page, 160); } }
function look(page) {
  return page.evaluate(function () {
    function box(sel) { var e = document.querySelector(sel), r = e.getBoundingClientRect(); return {l: r.left, r: r.right, t: r.top, b: r.bottom, w: r.width}; }
    function union(a, b) { return {l: Math.min(a.l, b.l), r: Math.max(a.r, b.r), t: Math.min(a.t, b.t), b: Math.max(a.b, b.b)}; }
    var shape = union(union(box('#island .cap.l'), box('#island .mid')), box('#island .cap.r'));
    var island = document.getElementById('island');
    return {state: Home.testIsland(), top: FreeMyTV.state.top, zone: FreeMyTV.state.zone, shape: shape,
      shown: getComputedStyle(island).visibility === 'visible' && Number(getComputedStyle(island).opacity) > 0.9,
      gone: getComputedStyle(island).visibility === 'hidden', bubble: box('#ccbtn'), pill: box('#pill'), tabs: box('#tabs'),
      text: document.querySelector('#island .txt').style.opacity, title: document.querySelector('#island .t').textContent};
  });
}
function apart(a, b, what) { assert.ok(a.r <= b.l + 0.5 || b.r <= a.l + 0.5, what + ' overlap: ' + JSON.stringify([a, b])); }

(async function () {
  await new Promise(function (done) { server.listen(0, '127.0.0.1', done); });
  var browser = await chromium.launch({headless: true});
  var page = await browser.newPage({viewport: {width: 1920, height: 1080}});
  var errors = [];
  page.on('pageerror', function (e) { errors.push(e.message); });
  await page.goto('http://127.0.0.1:' + server.address().port + '/index.html?nolock');
  // The fonts first: one coming in late changes the pill's width, and everything left of it moves with it.
  await page.evaluate(function () { return document.fonts ? document.fonts.ready.then(function () {}) : null; });
  await wait(page, 1500);

  // Playing: the tab bar, the dot 14 px beside it, the pair centred; then the bubble and the pill at the right.
  var s = await look(page);
  assert.ok(s.state.live && s.shown, 'the sample song plays: the dot shows');
  assert.ok(Math.abs(s.shape.l - s.tabs.r - 14) < 1.5 && s.shape.r < s.bubble.l && s.bubble.r <= s.pill.l,
    'tab bar, dot, bubble, pill in that order: ' + JSON.stringify([s.tabs, s.shape, s.bubble, s.pill]));
  assert.ok(Math.abs(s.shape.r - s.shape.l - 57) < 2 && Math.abs(s.shape.b - s.shape.t - 57) < 2, 'a dot as tall as the bar: ' + JSON.stringify(s.shape));
  assert.ok(Math.abs((s.tabs.l + s.shape.r) / 2 - 960) < 2, 'the bar and the dot centred together: ' + JSON.stringify([s.tabs, s.shape]));

  // ↑ to the bubble, ← to the dot: it opens leftward into a capsule with the title, the bubble and pill unmoved.
  while ((await look(page)).zone !== 'top') await tap(page, 'ArrowUp');
  await wait(page, 600);
  var before = await look(page);
  assert.strictEqual(before.top, 3, '↑ lands on the bubble');
  await tap(page, 'ArrowLeft');
  await wait(page, 1500);
  var open = await look(page);
  assert.strictEqual(open.top, 2, '← from the bubble: the dot');
  assert.ok(open.shape.r - open.shape.l > 250 && open.shape.b - open.shape.t > 66, 'open: a wide, 72 px capsule: ' + JSON.stringify(open.shape));
  assert.ok(Math.abs(open.shape.l - open.tabs.r - 14) < 2, 'it grows rightward, 14 px from the bar: ' + JSON.stringify([open.tabs, open.shape]));
  assert.ok(open.bubble.l - open.shape.r >= 31, 'at least 32 px from the bubble: ' + JSON.stringify([open.shape, open.bubble]));
  function mid(x) { return (x.l + x.r) / 2; }   // centres: the focused one is grown
  assert.ok(Math.abs(mid(open.bubble) - mid(before.bubble)) < 1 && Math.abs(mid(open.pill) - mid(before.pill)) < 1,
    'the bubble and the pill stay put: ' + JSON.stringify([before.bubble, open.bubble, before.pill, open.pill]));
  assert.ok(Number(open.text) > 0.95 && open.title === 'Sample Song', 'the title shows: ' + open.title);
  apart(open.shape, open.tabs, 'open capsule and tab bar');
  await tap(page, 'ArrowLeft');
  var welly = await page.evaluate(function () { return FreeMyTV.state.zone + ' ' + FreeMyTV.state.tab; });
  assert.strictEqual(welly, 'tabs 2', '← from the dot: Welly, beside it');
  await tap(page, 'ArrowRight');
  var back = await look(page);
  assert.ok(back.zone === 'top' && back.top === 2, '→ from Welly: the dot');

  // A narrow screen's worth of room: the tab bar slides away rather than sit under it.
  await page.evaluate(function () { document.getElementById('tabs').style.paddingRight = '420px'; });
  await tap(page, 'ArrowRight');
  await tap(page, 'ArrowLeft');
  await wait(page, 1200);
  var crowded = await look(page);
  assert.strictEqual(crowded.top, 2, 'back on the dot');
  apart(crowded.shape, crowded.tabs, 'with a wide tab bar, capsule and tab bar ');
  apart(crowded.shape, crowded.bubble, 'with a wide tab bar, capsule and bubble');
  assert.ok(crowded.tabs.l >= 475, 'the tab bar stops short of the pick\'s logo: ' + crowded.tabs.l);
  await page.evaluate(function () { document.getElementById('tabs').style.paddingRight = ''; });

  // OK pauses; it stays while focused, closes when left, and goes after the pause's grace.
  await page.evaluate(function () { Home.testIsland({grace: 600}); });
  await tap(page, 'Enter');
  assert.strictEqual((await look(page)).state.playing, false, 'OK on the dot: paused');
  await wait(page, 900);
  assert.ok((await look(page)).state.live, 'paused but focused: still there');
  await tap(page, 'ArrowRight');
  await wait(page, 1600);
  var gone = await look(page);
  assert.ok(!gone.state.live && gone.gone, 'paused and left: gone after its grace');
  assert.strictEqual(gone.top, 3, 'the focus stayed on the bubble');
  assert.ok(Math.abs((gone.tabs.l + gone.tabs.r) / 2 - 960) < 2, 'no dot: the bar centred again: ' + JSON.stringify(gone.tabs));
  await tap(page, 'ArrowLeft');
  assert.strictEqual((await look(page)).zone, 'tabs', 'no dot: ← from the bubble goes to the tabs');

  // Playing again: back, as a dot.
  await page.evaluate(function () { Home.testIsland({playing: true}); });
  await wait(page, 1200);
  back = await look(page);
  assert.ok(back.state.live && back.shown && Math.abs(back.shape.r - back.shape.l - 57) < 2, 'playing again: the dot is back');
  assert.deepStrictEqual(errors, [], 'no page errors');
  console.log('web-island-test: passed');
  await browser.close();
  server.close();
})().catch(function (e) { console.error(e); process.exit(1); });
