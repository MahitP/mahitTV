'use strict';

/* Actual preview browser exercise for Home.testBackground.  The seam exists
 * only when PREVIEW_PICKS is bundled and calls updateFocus/refreshBackground. */
var assert = require('assert');
var child = require('child_process');
var fs = require('fs');
var http = require('http');
var path = require('path');
var chromium = require('playwright').chromium;
var root = path.join(__dirname, '..');
var site = path.join(root, 'out', 'webos');
var types = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.jpg': 'image/jpeg', '.png': 'image/png', '.svg': 'image/svg+xml', '.ttf': 'font/ttf'};

child.execFileSync('python3', ['webos/build.py', '--preview'], {cwd: root, stdio: 'inherit'});
var server = http.createServer(function (req, res) {
  var file = path.join(site, decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html');
  if (!file.startsWith(site) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, {'Content-Type': types[path.extname(file)] || 'application/octet-stream'});
  fs.createReadStream(file).pipe(res);
});

function profile(page, zone, app) {
  return page.evaluate(function (x) { return Home.testBackground({zone: x.zone, app: x.app, tab: 0}); }, {zone: zone, app: app});
}

(async function () {
  await new Promise(function (done) { server.listen(0, '127.0.0.1', done); });
  var browser = await chromium.launch({headless: true});
  var page = await browser.newPage({viewport: {width: 1920, height: 1080}});
  await page.addInitScript(function () { localStorage.clear(); });
  await page.goto('http://127.0.0.1:' + server.address().port + '/index.html?nolock');
  await page.waitForTimeout(900);
  var netflix = {id: 'netflix', title: 'Netflix'};
  var plain = {id: 'com.example.notes', title: 'Notes'};
  var apple = {id: 'com.apple.music', title: 'Apple Music'};
  var spotify = {id: 'com.spotify.music', title: 'Spotify'};
  var result;
  await page.evaluate(function () { Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'History only', backdrop: 'preview/long-dunes.jpg', app: 'netflix'}); }; });
  result = await profile(page, 'shelf', netflix);
  assert.ok(result.media && result.title === 'History only', 'media Shelf renders actual resolved history hero');
  result = await profile(page, 'hero', netflix);
  assert.ok(result.media && result.zone === 'hero' && result.title === 'History only', 'history-only Shelf remains Hero');
  for (var i = 0; i < 3; i++) {
    result = await profile(page, ['tabs', 'apps', 'settings'][i], netflix);
    assert.ok(result.profile && !result.title, ['tabs', 'grid', 'settings'][i] + ' clears actual hero');
  }
  result = await profile(page, 'shelf', plain);
  assert.ok(result.profile && !result.title, 'non-media Shelf tile is profile-only');
  await page.evaluate(function () { Data.resolveWatch = function (app, generation, done) { done(generation, null); }; });
  result = await profile(page, 'shelf', netflix);
  assert.ok(result.media, 'actual controller falls back to featured art');
  result = await profile(page, 'shelf', apple);
  assert.ok(result.profile, 'inactive Apple Music has no featured art');
  result = await profile(page, 'shelf', spotify);
  assert.ok(result.profile, 'inactive Spotify has no featured art');
  await page.evaluate(function () { window.FMTV_NOW_PLAYING = {pkg: 'com.spotify.music', title: 'Now', artwork: 'preview/long-dunes.jpg', active: true, backdrop: 'preview/long-dunes.jpg'}; });
  await page.evaluate(function () { Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'Now', backdrop: 'preview/long-dunes.jpg'}); }; });
  result = await profile(page, 'shelf', spotify);
  assert.ok(result.media && result.title === 'Now', 'active Spotify Now Playing is media art');
  var stale = await page.evaluate(function () {
    var callbacks = [];
    Data.resolveWatch = function (app, generation, done) { callbacks.push({generation: generation, done: done}); };
    Home.testBackground({zone: 'shelf', app: {id: 'netflix', title: 'Netflix'}, tab: 0});
    Home.testBackground({zone: 'tabs', app: {id: 'netflix', title: 'Netflix'}, tab: 0});
    callbacks[0].done(callbacks[0].generation, {title: 'Late', backdrop: 'preview/long-dunes.jpg'});
    return Home.testBackground({zone: 'tabs', app: {id: 'netflix', title: 'Netflix'}, tab: 0});
  });
  assert.ok(stale.profile && !stale.title, 'late actual resolve callback cannot revive media hero');
  await page.evaluate(function () {
    Motion.instant = false;
    Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'Broken', backdrop: 'preview/missing-art.jpg', app: 'netflix'}); };
    Home.testBackground({zone: 'shelf', app: {id: 'netflix', title: 'Netflix'}, tab: 0});
  });
  await page.waitForTimeout(120);
  result = await page.evaluate(function () { return {profile: document.body.className.indexOf('profile-background') >= 0, title: document.querySelector('#hero h1').textContent}; });
  assert.ok(result.profile && !result.title, 'actual image error falls back to Horizon profile state');
  var transitions = await page.evaluate(function () {
    Motion.instant = false;
    Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'Fade', backdrop: 'preview/long-dunes.jpg', app: 'netflix'}); };
    return Home.testBackground({zone: 'shelf', app: {id: 'netflix', title: 'Netflix'}, tab: 0});
  });
  assert.ok(/0\.7s cubic-bezier/.test(transitions.transition) && /0\.7s cubic-bezier/.test(transitions.backdropTransition),
    'normal photo and backdrop use 700ms cubic fades: ' + JSON.stringify(transitions));
  await page.evaluate(function () {
    Motion.instant = true;
    Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'Instant', backdrop: 'preview/neon-meridian.jpg', app: 'netflix'}); };
    Home.testBackground({zone: 'shelf', app: {id: 'netflix', title: 'Netflix'}, tab: 0});
  });
  await page.waitForTimeout(100);
  var instant = await page.evaluate(function () { return Home.testBackground({zone: 'shelf', app: {id: 'netflix', title: 'Netflix'}, tab: 0}); });
  assert.strictEqual(instant.transition, 'none', 'instant clears photo transition');
  assert.strictEqual(instant.backdropTransition, 'none', 'instant clears backdrop transition');
  await browser.close(); server.close();
  console.log('home-background-dom-test: passed');
})().catch(function (err) { server.close(); console.error(err.stack || err); process.exitCode = 1; });
