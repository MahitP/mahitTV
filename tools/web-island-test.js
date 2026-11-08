'use strict';

/* The dynamic dot in the web preview (DESIGN-DYNAMIC-DOT.md): one Liquid Glass dot beside the tab bar for every live
 * activity, showing the most important one's white symbol (what plays, then Focus, then AirPods, then the sleep timer);
 * → from Welly opens it on that one, ↓ ↑ step through the rest and knock past either end; AirPods joining opens it by
 * itself; nothing open overlaps; the last activity stopping takes the dot away and the bar centres again.
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
    var n = document.getElementById('island'), st = getComputedStyle(n), faces = {};
    Array.prototype.forEach.call(n.querySelectorAll('.face'), function (f) {
      faces[f.id.slice(5)] = {a: Number(f.style.opacity || 0), title: f.querySelector('.t').textContent, sub: f.querySelector('.s').textContent,
        text: Number(f.querySelector('.txt').style.opacity || 0), symbol: getComputedStyle(f.querySelector('.sym')).backgroundColor};
    });
    return {state: Home.testIsland(), top: FreeMyTV.state.top, zone: FreeMyTV.state.zone, tab: FreeMyTV.state.tab, live: FreeMyTV.state.live,
      shape: union(union(box(n.querySelector('.cap.l')), box(n.querySelector('.mid'))), box(n.querySelector('.cap.r'))),
      shown: st.visibility === 'visible' && Number(st.opacity) > 0.9, gone: st.visibility === 'hidden', faces: faces,
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

  // The song alone: one glass dot as tall as the bar, 14 px beside it, the pair centred; the white music symbol.
  var s = await look(page);
  assert.ok(s.shown && s.state.shows === 'music', 'the sample song plays: the dot shows music');
  assert.ok(Math.abs(s.shape.l - s.tabs.r - 14) < 1.5 && s.shape.r < s.bubble.l && s.bubble.r <= s.pill.l, 'tab bar, dot, bubble, pill');
  assert.ok(Math.abs(s.shape.r - s.shape.l - 57) < 2 && Math.abs(s.shape.b - s.shape.t - 57) < 2, 'a dot as tall as the bar');
  assert.ok(Math.abs((s.tabs.l + s.shape.r) / 2 - 960) < 2, 'the bar and the dot centred together');
  assert.ok(s.faces.music.a > 0.95 && s.faces.music.symbol === 'rgb(255, 255, 255)', 'its white symbol, as the round buttons');

  // Focus, AirPods and the sleep timer too: still one dot, still showing the song (the most important).
  await page.evaluate(function () { Home.testIsland({dnd: true, sleep: 15}); });
  await page.keyboard.press('a');   // AirPods join: the dot opens on them by itself (the alert)
  await wait(page, 1200);
  var alert = await look(page);
  assert.ok(alert.state.shows === 'airpods' && alert.state.open === 1 && alert.faces.airpods.text > 0.95 && alert.faces.airpods.title,
    'AirPods joining: the dot opens on them by itself: ' + JSON.stringify(alert.state));
  apart(alert.shape, alert.bubble, 'the alert and the bubble');
  await wait(page, 3600);
  var four = await look(page);
  assert.ok(four.state.lives.every(function (l) { return l.on; }) && four.state.shows === 'music' && four.state.open === 0,
    'after the alert, four running and one dot, showing music again: ' + JSON.stringify(four.state));
  assert.ok(Math.abs(four.shape.r - four.shape.l - 57) < 2, 'still one dot');

  // → from Welly: open on the song; ↓ Focus, AirPods, the sleep timer, then the knock; ↑ back up, then the knock.
  while ((await look(page)).zone !== 'tabs') await tap(page, 'ArrowUp');
  while ((await look(page)).tab !== 2) await tap(page, 'ArrowRight');
  await tap(page, 'ArrowRight');
  await wait(page, 1300);
  var open = await look(page);
  assert.ok(open.top === 2 && open.live === 0 && open.state.shows === 'music' && open.faces.music.title === 'Sample Song', '→ from Welly: open on the song');
  assert.ok(open.shape.r - open.shape.l > 250 && open.shape.b - open.shape.t > 66, 'open: a wide, 72 px capsule');
  apart(open.shape, open.tabs, 'the open dot and the tab bar');
  assert.ok(open.bubble.l - open.shape.r >= 31, 'at least 32 px from the bubble');
  var order = [];
  for (var i = 0; i < 3; i++) {
    await tap(page, 'ArrowDown');
    await wait(page, 700);
    var step = await look(page);
    order.push(step.state.shows);
    apart(step.shape, step.bubble, 'step ' + i + ': the dot and the bubble');
    assert.ok(step.faces[step.state.shows].text > 0.95, 'step ' + i + ': its words show');
  }
  assert.deepStrictEqual(order, ['moon', 'airpods', 'bedtime'], '↓ steps through Focus, AirPods, the sleep timer');
  var bumps = await page.evaluate(function () {
    window.__nudges = [];
    var island = document.getElementById('island'), seen = window.__nudges;
    var o = new MutationObserver(function () { seen.push(island.style.transform); });
    o.observe(island, {attributes: true, attributeFilter: ['style']});
    return true;
  });
  await tap(page, 'ArrowDown');
  await wait(page, 400);
  var end = await page.evaluate(function () { return {live: FreeMyTV.state.live, moved: window.__nudges.some(function (t) { return /,\s*-?[1-9]/.test(t); })}; });
  assert.ok(bumps && end.live === 3 && end.moved, '↓ past the last: it stays, and knocks: ' + JSON.stringify(end));
  await tap(page, 'ArrowUp', 3);
  await wait(page, 700);
  assert.strictEqual((await look(page)).state.shows, 'music', '↑ back to the song');
  await page.evaluate(function () { window.__nudges.length = 0; });
  await tap(page, 'ArrowUp');
  await wait(page, 400);
  end = await page.evaluate(function () { return {live: FreeMyTV.state.live, top: FreeMyTV.state.top, moved: window.__nudges.some(function (t) { return /,\s*-?[1-9]/.test(t); })}; });
  assert.ok(end.live === 0 && end.top === 2 && end.moved, '↑ past the first: it stays, and knocks: ' + JSON.stringify(end));

  // OK on Focus turns Do Not Disturb off: that activity goes, the focus moves on to the next.
  await tap(page, 'ArrowDown');
  await tap(page, 'Enter');
  await wait(page, 900);
  var dnd = await look(page);
  assert.ok(!dnd.state.lives[1].on && dnd.state.shows === 'airpods', 'OK on Focus: Do Not Disturb off, on to AirPods: ' + JSON.stringify(dnd.state));
  await page.keyboard.press('a');   // AirPods leave
  await page.evaluate(function () { Home.testIsland({sleep: 0}); });
  await wait(page, 900);
  assert.strictEqual((await look(page)).state.shows, 'music', 'only the song left');

  // Paused and left: the song goes after its grace, the dot with it, and the bar centres again.
  await tap(page, 'ArrowRight');
  await page.evaluate(function () { Home.testIsland({grace: 400, playing: false}); });
  await wait(page, 1600);
  var gone = await look(page);
  assert.ok(gone.gone && !gone.state.live, 'nothing running: no dot');
  assert.ok(Math.abs(mid(gone.tabs) - 960) < 2, 'the bar centred again');
  await page.evaluate(function () { Home.testIsland({playing: true}); });
  await wait(page, 1200);
  var back = await look(page);
  assert.ok(back.shown && back.state.shows === 'music' && Math.abs(back.shape.r - back.shape.l - 57) < 2, 'playing again: the dot is back');
  assert.deepStrictEqual(errors, [], 'no page errors');
  console.log('web-island-test: passed');
  await browser.close();
  server.close();
})().catch(function (e) { console.error(e); process.exit(1); });
