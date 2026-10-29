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
  await page.evaluate(function () { window.__resolveWatch = Data.resolveWatch; });
  var netflix = {id: 'netflix', title: 'Netflix'};
  var plain = {id: 'com.example.notes', title: 'Notes'};
  var apple = {id: 'com.apple.music', title: 'Apple Music'};
  var spotify = {id: 'com.spotify.music', title: 'Spotify'};
  var result;
  await page.evaluate(function () { Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'History only', backdrop: 'preview/long-dunes.jpg', app: 'netflix'}); }; });
  result = await profile(page, 'shelf', netflix);
  assert.ok(result.media && result.title === 'History only', 'media Shelf renders actual resolved history hero');
  await page.evaluate(function () {
    Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'Track A', backdrop: 'preview/long-dunes.jpg', app: 'netflix'}); };
    Home.testBackground({zone: 'shelf', app: {id: 'netflix', title: 'Netflix'}, tab: 0});
    Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'Track B', backdrop: 'preview/neon-meridian.jpg', app: 'netflix'}); };
    Home.testBackground({zone: 'shelf', app: {id: 'netflix', title: 'Netflix'}, tab: 0, keepKey: true});
  });
  await page.waitForTimeout(100);
  var changedMedia = await page.evaluate(function () { return {title: document.querySelector('#hero h1').textContent, art: document.querySelector('#photoA').style.backgroundImage + document.querySelector('#photoB').style.backgroundImage}; });
  assert.ok(changedMedia.title === 'Track B' && /neon-meridian/.test(changedMedia.art), 'same focused app refreshes changed media artwork without card-count changes');
  await page.evaluate(function () { Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'History only', backdrop: 'preview/long-dunes.jpg', app: 'netflix'}); }; });
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
  await page.evaluate(function () { Data.resolveWatch = window.__resolveWatch; });
  result = await profile(page, 'shelf', apple);
  await page.waitForTimeout(100);
  var appleArt = await page.evaluate(function () { return {media: document.body.className.indexOf('media-background') >= 0, title: document.querySelector('#hero h1').textContent, art: document.querySelector('#photoA').style.backgroundImage + document.querySelector('#photoB').style.backgroundImage}; });
  assert.ok(result.media && appleArt.title && /long-dunes/.test(appleArt.art), 'active Apple Music renders its Now Playing artwork');
  var previewInput = await page.evaluate(function () { return {backdrop: window.PREVIEW_NOW_PLAYING.backdrop, mediaSource: window.PREVIEW_NOW_PLAYING.mediaSource}; });
  assert.strictEqual(previewInput.backdrop, undefined, 'preview Now Playing input is not mutated with backdrop');
  assert.strictEqual(previewInput.mediaSource, undefined, 'preview Now Playing input is not mutated with media metadata');
  await page.evaluate(function () {
    window.PREVIEW_NOW_PLAYING = {pkg: 'com.spotify.music', title: 'Spotify Now', artwork: 'preview/neon-meridian.jpg', active: true};
  });
  result = await profile(page, 'shelf', spotify);
  await page.waitForTimeout(100);
  var spotifyArt = await page.evaluate(function () { return {media: document.body.className.indexOf('media-background') >= 0, title: document.querySelector('#hero h1').textContent, art: document.querySelector('#photoA').style.backgroundImage + document.querySelector('#photoB').style.backgroundImage}; });
  assert.ok(result.media && spotifyArt.title === 'Spotify Now' && /neon-meridian/.test(spotifyArt.art), 'active Spotify renders its Now Playing artwork');
  var allowedArtwork = await page.evaluate(function () { return Wallpaper.aerials[0].preview; });
  await page.route(allowedArtwork, function (route) { route.fulfill({path: path.join(root, 'preview', 'art', 'long-dunes.jpg'), contentType: 'image/jpeg'}); });
  await page.evaluate(function (art) { window.FMTV_NOW_PLAYING = {pkg: 'com.spotify.music', title: 'Allowed Spotify', artwork: art, active: true}; }, allowedArtwork);
  result = await profile(page, 'shelf', spotify);
  await page.waitForTimeout(100);
  var allowedArt = await page.evaluate(function () {
    var input = window.FMTV_NOW_PLAYING;
    return {media: document.body.className.indexOf('media-background') >= 0, title: document.querySelector('#hero h1').textContent,
      art: document.querySelector('#photoA').style.backgroundImage + document.querySelector('#photoB').style.backgroundImage,
      backdrop: input.backdrop, mediaSource: input.mediaSource};
  });
  assert.ok(result.media && allowedArt.title === 'Allowed Spotify' && allowedArt.art.indexOf(allowedArtwork) >= 0,
    'exact allowlisted catalogue artwork renders through the actual music controller');
  assert.strictEqual(allowedArt.backdrop, undefined, 'allowlisted Now Playing input is not mutated with backdrop');
  assert.strictEqual(allowedArt.mediaSource, undefined, 'allowlisted Now Playing input is not mutated with media metadata');
  var invalidMusic = [
    {pkg: 'com.spotify.music', title: 'Foreign', artwork: 'https://example.invalid/art.jpg', active: true},
    {pkg: 'com.spotify.music', title: 'Forged', artwork: 'https://example.invalid/art.jpg', backdrop: 'preview/long-dunes.jpg', active: true},
    {pkg: 'com.spotify.music', title: 'Missing', active: true}
  ];
  for (i = 0; i < invalidMusic.length; i++) {
    await page.evaluate(function (playing) { window.FMTV_NOW_PLAYING = playing; Data.resolveWatch = window.__resolveWatch; }, invalidMusic[i]);
    result = await profile(page, 'shelf', spotify);
    assert.ok(result.profile && !result.title, 'untrusted Now Playing artwork falls back to profile: ' + invalidMusic[i].title);
  }
  await page.evaluate(function () { window.FMTV_NOW_PLAYING = null; window.PREVIEW_NOW_PLAYING = {pkg: 'com.spotify.music', title: 'Spotify Now', artwork: 'preview/neon-meridian.jpg', active: true}; });
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
  await page.evaluate(function () {
    Motion.instant = false;
    Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'Fade', backdrop: 'preview/orbital.jpg', app: 'netflix'}); };
    Home.testBackground({zone: 'shelf', app: {id: 'netflix', title: 'Netflix'}, tab: 0});
  });
  await page.waitForTimeout(100);
  var transitions = await page.evaluate(function () { return {photo: document.querySelector('#photoA').style.transition || document.querySelector('#photoB').style.transition, backdrop: document.querySelector('#bgA').style.transition, art: document.querySelector('#photoA').style.backgroundImage + document.querySelector('#photoB').style.backgroundImage}; });
  assert.ok(/orbital/.test(transitions.art) && /0\.7s cubic-bezier/.test(transitions.photo) && /0\.7s cubic-bezier/.test(transitions.backdrop),
    'normal photo and backdrop use 700ms cubic fades: ' + JSON.stringify(transitions));
  await page.evaluate(function () {
    Motion.instant = true;
    Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'Instant', backdrop: 'preview/neon-meridian.jpg', app: 'netflix'}); };
    Home.testBackground({zone: 'shelf', app: {id: 'netflix', title: 'Netflix'}, tab: 0});
  });
  await page.waitForTimeout(100);
  var instant = await page.evaluate(function () { return {photo: document.querySelector('#photoA').style.transition || document.querySelector('#photoB').style.transition, backdrop: document.querySelector('#bgA').style.transition, art: document.querySelector('#photoA').style.backgroundImage + document.querySelector('#photoB').style.backgroundImage}; });
  assert.ok(/neon-meridian/.test(instant.art), 'instant applies the distinct target art');
  assert.strictEqual(instant.photo, 'none', 'instant clears photo transition');
  assert.strictEqual(instant.backdrop, 'none', 'instant clears backdrop transition');
  await browser.close(); server.close();
  console.log('home-background-dom-test: passed');
})().catch(function (err) { server.close(); console.error(err.stack || err); process.exitCode = 1; });
