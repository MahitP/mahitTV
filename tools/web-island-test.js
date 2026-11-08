'use strict';

/* The dynamic dot's live activities in the web preview (DESIGN-DYNAMIC-DOT.md): the sample song is a glass dot with a
 * white music symbol beside the tab bar, the bar and the dots centred together; → from Welly opens it into a capsule
 * that overlaps nothing; the sleep timer adds a second dot (OK steps it, off removes it); AirPods joining opens its own
 * by itself, then it goes; paused and left, the song goes after its grace and the bar centres again.
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
    function box(e) { var r = e.getBoundingClientRect(); return {l: r.left, r: r.right, t: r.top, b: r.bottom, w: r.width}; }
    function union(a, b) { return {l: Math.min(a.l, b.l), r: Math.max(a.r, b.r), t: Math.min(a.t, b.t), b: Math.max(a.b, b.b)}; }
    function live(icon) {
      var n = document.getElementById('live-' + icon), st = getComputedStyle(n);
      return {shape: union(union(box(n.querySelector('.cap.l')), box(n.querySelector('.mid'))), box(n.querySelector('.cap.r'))),
        shown: st.visibility === 'visible' && Number(st.opacity) > 0.9, gone: st.visibility === 'hidden',
        text: Number(n.querySelector('.txt').style.opacity || 0), title: n.querySelector('.t').textContent, sub: n.querySelector('.s').textContent,
        symbol: getComputedStyle(n.querySelector('.sym')).backgroundColor};
    }
    return {state: Home.testIsland(), top: FreeMyTV.state.top, zone: FreeMyTV.state.zone, tab: FreeMyTV.state.tab,
      music: live('music'), sleep: live('bedtime'), air: live('airpods'),
      bubble: box(document.getElementById('ccbtn')), pill: box(document.getElementById('pill')), tabs: box(document.getElementById('tabs'))};
  });
}
function apart(a, b, what) { assert.ok(a.r <= b.l + 0.5 || b.r <= a.l + 0.5, what + ' overlap: ' + JSON.stringify([a, b])); }
function mid(x) { return (x.l + x.r) / 2; }

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

  // The song: a glass dot as tall as the bar, 14 px beside it, the pair centred; a white symbol, no picture.
  var s = await look(page);
  assert.ok(s.state.live && s.music.shown && !s.sleep.shown && !s.air.shown, 'the sample song plays: its dot alone');
  assert.ok(Math.abs(s.music.shape.l - s.tabs.r - 14) < 1.5 && s.music.shape.r < s.bubble.l && s.bubble.r <= s.pill.l,
    'tab bar, dot, bubble, pill in that order: ' + JSON.stringify([s.tabs, s.music.shape, s.bubble, s.pill]));
  assert.ok(Math.abs(s.music.shape.r - s.music.shape.l - 57) < 2 && Math.abs(s.music.shape.b - s.music.shape.t - 57) < 2, 'a dot as tall as the bar');
  assert.ok(Math.abs((s.tabs.l + s.music.shape.r) / 2 - 960) < 2, 'the bar and the dot centred together');
  assert.strictEqual(s.music.symbol, 'rgb(255, 255, 255)', 'a white symbol, as the round buttons');

  // → from Welly: the dot opens rightward with the title; the bubble and the pill stay put.
  while ((await look(page)).zone !== 'top') await tap(page, 'ArrowUp');
  await wait(page, 600);
  var before = await look(page);
  await tap(page, 'ArrowLeft');   // the bubble → the dot
  await wait(page, 1500);
  var open = await look(page);
  assert.ok(open.top === 2 && open.state.at === 0, '← from the bubble: the song\'s dot');
  assert.ok(open.music.shape.r - open.music.shape.l > 250 && open.music.shape.b - open.music.shape.t > 66, 'open: a wide, 72 px capsule');
  assert.ok(Math.abs(open.music.shape.l - open.tabs.r - 14) < 2, 'it grows rightward, 14 px from the bar');
  assert.ok(open.bubble.l - open.music.shape.r >= 31, 'at least 32 px from the bubble');
  assert.ok(Math.abs(mid(open.bubble) - mid(before.bubble)) < 1 && Math.abs(mid(open.pill) - mid(before.pill)) < 1, 'the bubble and the pill stay put');
  assert.ok(open.music.text > 0.95 && open.music.title === 'Sample Song', 'the title shows: ' + open.music.title);
  await tap(page, 'ArrowLeft');
  var w = await look(page);
  assert.ok(w.zone === 'tabs' && w.tab === 2, '← from the dot: Welly, beside it');
  await tap(page, 'ArrowRight');
  assert.ok((await look(page)).top === 2, '→ from Welly: the dot');

  // The sleep timer: a second dot after the song's; → reaches it, OK steps it; off, it goes and the focus comes back.
  await page.evaluate(function () { Home.testIsland({sleep: 15}); });
  await wait(page, 1200);
  var two = await look(page);
  assert.ok(two.sleep.shown && two.music.shown, 'the sleep timer adds its dot');
  apart(two.music.shape, two.sleep.shape, 'the song\'s capsule and the sleep timer\'s dot');
  apart(two.sleep.shape, two.bubble, 'the sleep timer\'s dot and the bubble');
  await tap(page, 'ArrowRight');
  await wait(page, 1200);
  var sl = await look(page);
  assert.ok(sl.state.at === 1 && sl.sleep.text > 0.95 && sl.sleep.title === 'Sleep Timer' && /15/.test(sl.sleep.sub), '→: the sleep timer opens: ' + JSON.stringify([sl.sleep.title, sl.sleep.sub]));
  apart(sl.music.shape, sl.sleep.shape, 'the song\'s dot and the sleep timer\'s capsule');
  await tap(page, 'Enter');
  assert.ok(/30/.test((await look(page)).sleep.sub), 'OK: the next preset, 30 min');
  await page.evaluate(function () { Home.testIsland({sleep: 120}); });
  await tap(page, 'Enter');
  await wait(page, 1200);
  var off = await look(page);
  assert.ok(!off.state.lives[1].on && off.sleep.gone && off.top === 2 && off.state.at === 0, 'OK at 2 h: off, the dot goes, the focus to the song');

  // AirPods joining: its dot opens by itself (an alert), closes, then goes.
  await tap(page, 'ArrowDown');
  await page.keyboard.press('a');
  await wait(page, 1200);
  var air = await look(page);
  assert.ok(air.air.shown && air.state.lives[2].open === 1 && air.air.text > 0.95 && air.air.title, 'A: AirPods\' alert opens by itself: ' + JSON.stringify(air.state.lives));
  apart(air.music.shape, air.air.shape, 'the song\'s dot and the AirPods alert');
  apart(air.air.shape, air.bubble, 'the AirPods alert and the bubble');
  await wait(page, 3800);
  assert.ok((await look(page)).air.gone, 'after its alert, the AirPods dot goes');
  await page.keyboard.press('a');
  await wait(page, 300);

  // Paused and left: the song goes after its grace, and the bar centres again; playing again brings it back.
  await page.evaluate(function () { Home.testIsland({grace: 400, playing: false}); });
  await wait(page, 1600);
  var gone = await look(page);
  assert.ok(!gone.state.live && gone.music.gone, 'paused, not focused: gone after its grace');
  assert.ok(Math.abs(mid(gone.tabs) - 960) < 2, 'no dots: the bar centred again');
  await page.evaluate(function () { Home.testIsland({playing: true}); });
  await wait(page, 1200);
  var back = await look(page);
  assert.ok(back.state.live && back.music.shown && Math.abs(back.music.shape.r - back.music.shape.l - 57) < 2, 'playing again: the dot is back');
  assert.deepStrictEqual(errors, [], 'no page errors');
  console.log('web-island-test: passed');
  await browser.close();
  server.close();
})().catch(function (e) { console.error(e); process.exit(1); });
