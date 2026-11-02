'use strict';

/* The web app's interface sounds (web/sound.js) in a real browser: every cue builds (a mono buffer, normalized to
 * −3 dBFS), and the home screen plays the right one at the right moment: a tick panned toward where the focus lands, the
 * knock at a dead end, select on OK, a whisper in and out for Control Center and Settings, the tension on holding H, its
 * snap when Recent Apps opens and its fade on a tap; and nothing once Navigation sounds is off.
 * Builds the preview into out/webos (as web-keys-test does), or serves SITE when it's set. */
var assert = require('assert');
var child = require('child_process');
var fs = require('fs');
var http = require('http');
var path = require('path');
var chromium = require('playwright').chromium;
var root = path.join(__dirname, '..');
var site = process.env.SITE ? path.resolve(process.env.SITE) : path.join(root, 'out', 'webos');
var types = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.jpg': 'image/jpeg', '.png': 'image/png', '.svg': 'image/svg+xml', '.ttf': 'font/ttf'};

// The example settings, as CI has: with a TMDB key of your own, the preview has no sample picks and no test seam.
if (!process.env.SITE) child.execFileSync('python3', ['webos/build.py', '--preview', '--config', 'config.example.properties'], {cwd: root, stdio: 'inherit'});
var server = http.createServer(function (req, res) {
  var file = path.join(site, decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html');
  if (!file.startsWith(site) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, {'Content-Type': types[path.extname(file)] || 'application/octet-stream'});
  fs.createReadStream(file).pipe(res);
});

var browser = null;

(async function () {
  await new Promise(function (done) { server.listen(0, '127.0.0.1', done); });
  browser = await chromium.launch({headless: true, args: ['--autoplay-policy=no-user-gesture-required']});
  var page = await browser.newPage({viewport: {width: 1920, height: 1080}});
  var errors = [];
  page.on('pageerror', function (e) { errors.push(e.message); });
  await page.addInitScript(function () { localStorage.clear(); });
  await page.route(/api\.open-meteo\.com/, function (route) { route.abort(); });
  await page.goto('http://127.0.0.1:' + server.address().port + '/index.html?nolock');
  await page.waitForTimeout(900);
  // Every call to the engine, with its arguments, is logged (the real one still plays).
  await page.evaluate(function () {
    window.cues = [];
    ['tick', 'select', 'bump', 'toggle', 'sheet', 'holdStart', 'holdCancel', 'holdCommit', 'error'].forEach(function (name) {
      var real = Sound[name];
      Sound[name] = function () { cues.push([name].concat([].slice.call(arguments))); return real.apply(Sound, arguments); };
    });
  });
  function take() { return page.evaluate(function () { var c = cues; window.cues = []; return c; }); }
  function names(c) { return c.map(function (x) { return x[0]; }); }

  // The cues themselves: each builds, has sound in it and peaks at −3 dBFS.
  var built = await page.evaluate(function () {
    var A = window.AudioContext || window.webkitAudioContext, real = A.prototype.createBuffer, made = [];
    A.prototype.createBuffer = function () { var b = real.apply(this, arguments); made.push(b); return b; };
    Sound.wake();
    A.prototype.createBuffer = real;
    return made.map(function (b) {
      var d = b.getChannelData(0), peak = 0;
      for (var i = 0; i < d.length; i++) peak = Math.max(peak, Math.abs(d[i]));
      return {ms: Math.round(b.duration * 1000), peak: peak};
    });
  });
  assert.strictEqual(built.length, 10, 'ten cues: ' + JSON.stringify(built));
  built.forEach(function (b) {
    assert.ok(b.ms >= 28 && b.ms <= 560 && Math.abs(b.peak - 0.708) < 0.01, 'a cue at −3 dBFS: ' + JSON.stringify(b));
  });

  // ← at the start of the Dock: the knock; → along it: ticks, panned further right each time.
  await page.keyboard.press('ArrowLeft'); await page.waitForTimeout(120);
  var c = await take();
  assert.deepStrictEqual(names(c), ['bump'], 'a dead end knocks: ' + JSON.stringify(c));
  var pans = [];
  for (var i = 0; i < 3; i++) {
    await page.keyboard.press('ArrowRight'); await page.waitForTimeout(160);
    c = await take();
    assert.deepStrictEqual(names(c), ['tick'], 'a focus move ticks: ' + JSON.stringify(c));
    pans.push(c[0][1]);
  }
  assert.ok(pans[0] < pans[1] && pans[1] < pans[2] && pans[2] <= 1 && pans[0] >= 0, 'the tick follows the focus to the right: ' + pans);

  // Control Center: a whisper in from the top right, and out.
  await page.evaluate(function () { FreeMyTV.drawer(); });
  await page.waitForTimeout(300);
  c = await take();
  assert.ok(c.some(function (x) { return x[0] === 'sheet' && x[1] === true && x[2] > 0.7; }), 'Control Center whispers in from the right: ' + JSON.stringify(c));
  await page.keyboard.press('Escape'); await page.waitForTimeout(300);
  c = await take();
  assert.ok(c.some(function (x) { return x[0] === 'sheet' && x[1] === false; }), 'and out: ' + JSON.stringify(c));

  // H held: the tension, then its snap as Recent Apps opens; H tapped: the tension fades and it goes home.
  await page.keyboard.down('h'); await page.waitForTimeout(650); await page.keyboard.up('h'); await page.waitForTimeout(200);
  c = await take();
  assert.deepStrictEqual(names(c).filter(function (n) { return /^hold|^sheet$/.test(n); }).slice(0, 3), ['holdStart', 'holdCommit', 'sheet'],
    'a held H: tension, snap, Recent Apps whispers in: ' + JSON.stringify(c));
  await page.keyboard.press('Escape'); await page.waitForTimeout(400); await take();
  await page.keyboard.down('h'); await page.waitForTimeout(120); await page.keyboard.up('h'); await page.waitForTimeout(200);
  c = await take();
  assert.deepStrictEqual(names(c).filter(function (n) { return /^hold/.test(n); }), ['holdStart', 'holdCancel'], 'a tapped H: the tension fades: ' + JSON.stringify(c));

  // Navigation sounds off: the engine is still called, but nothing plays.
  var silent = await page.evaluate(function () {
    Prefs.set('sounds', false);
    var made = 0, A = window.AudioContext || window.webkitAudioContext, real = A.prototype.createBufferSource;
    A.prototype.createBufferSource = function () { made++; return real.apply(this, arguments); };
    Sound.tick(0.5); Sound.select(); Sound.sheet(true, 0.5);
    A.prototype.createBufferSource = real;
    Prefs.set('sounds', true);
    return made;
  });
  assert.strictEqual(silent, 0, 'Navigation sounds off: no sound plays');

  assert.deepStrictEqual(errors, [], 'no page errors');
  console.log('web-sound-test: passed');
  await browser.close();
  server.close();
})().catch(async function (e) {
  console.error(e);
  if (browser) await browser.close();
  server.close();
  process.exit(1);
});
