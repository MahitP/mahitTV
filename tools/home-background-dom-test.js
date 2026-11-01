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

/** Waits until an element's computed opacity has held still for 300 ms (springs overshoot, and slow runners lag). */
async function settledOpacity(page, selector) {
  await page.waitForFunction(function (sel) {
    var o = getComputedStyle(document.querySelector(sel)).opacity, seen = window.__settled || (window.__settled = {}), now = Date.now();
    if (!seen[sel] || seen[sel].o !== o) { seen[sel] = {o: o, at: now}; return false; }
    return now - seen[sel].at >= 300;
  }, selector, {polling: 50, timeout: 10000});
  return page.evaluate(function (sel) { return parseFloat(getComputedStyle(document.querySelector(sel)).opacity); }, selector);
}

function profile(page, zone, app) {
  return page.evaluate(function (x) { return Home.testBackground({zone: x.zone, app: x.app, tab: 0}); }, {zone: zone, app: app});
}

var browser = null;

(async function () {
  await new Promise(function (done) { server.listen(0, '127.0.0.1', done); });
  browser = await chromium.launch({headless: true});
  var page = await browser.newPage({viewport: {width: 1920, height: 1080}});
  await page.addInitScript(function () { localStorage.clear(); });
  await page.goto('http://127.0.0.1:' + server.address().port + '/index.html?nolock');
  await page.waitForTimeout(900);
  var pickerStatus = async function (category, option) {
    return page.evaluate(function (selection) {
      Home.previewWallpaperPicker(selection.category, selection.option);
      var preview = document.querySelector('#wallpaperpick .preview');
      var status = preview.querySelector('.status');
      return {status: status ? status.textContent : '', background: preview.style.backgroundImage, horizon: preview.className.indexOf('horizon') >= 0};
    }, {category: category, option: option});
  };
  var pickerUrls = await page.evaluate(function () { return {
    aerial: Wallpaper.find('aerial:yosemite').preview,
    live: Wallpaper.liveAerial(Math.floor(Date.now() / 86400000)).preview
  }; });
  await page.route(pickerUrls.aerial, function (route) { route.fulfill({path: path.join(root, 'preview', 'art', 'long-dunes.jpg'), contentType: 'image/jpeg', headers: {'Cache-Control': 'no-store'}}); });
  await page.route(pickerUrls.live, function (route) { route.fulfill({path: path.join(root, 'preview', 'art', 'long-dunes.jpg'), contentType: 'image/jpeg', headers: {'Cache-Control': 'no-store'}}); });
  var horizonPicker = await pickerStatus('horizon', 'horizon:auto');
  assert.strictEqual(horizonPicker.status, '', 'actual web Horizon preview has no unavailable fallback status');
  var studioPicker = await pickerStatus('studio', 'studio:teal');
  assert.strictEqual(studioPicker.status, '', 'actual web Studio preview has no unavailable fallback status');
  var aerialPicker = await pickerStatus('aerial', 'aerial:yosemite');
  assert.strictEqual(aerialPicker.status, 'Downloading…', 'actual web aerial preview shows its loading status');
  await page.waitForFunction(function () { return document.querySelector('#wallpaperpick .preview .status').textContent === 'Aerial preview ready'; }, undefined, {timeout: 5000});
  var aerialReady = await page.evaluate(function () { return document.querySelector('#wallpaperpick .preview .status').textContent; });
  assert.strictEqual(aerialReady, 'Aerial preview ready', 'actual web aerial preview reports readiness');
  var livePicker = await pickerStatus('live', 'live:aerial');
  assert.strictEqual(livePicker.status, 'Downloading…', 'actual web live preview shows its loading status when available');
  await page.waitForFunction(function () { return document.querySelector('#wallpaperpick .preview .status').textContent === 'Live Aerial preview ready'; }, undefined, {timeout: 5000});
  var liveReady = await page.evaluate(function () { return document.querySelector('#wallpaperpick .preview .status').textContent; });
  assert.strictEqual(liveReady, 'Live Aerial preview ready', 'actual web live preview reports readiness');
  await page.evaluate(function () {
    window.__PickerImage = window.Image;
    window.Image = function () {
      var image = this;
      Object.defineProperty(image, 'src', {set: function () { setTimeout(function () { image.onerror(); }, 0); }});
    };
  });
  await pickerStatus('aerial', 'aerial:iceland');
  await page.waitForFunction(function () { return document.querySelector('#wallpaperpick .preview .status').textContent === 'Unavailable — showing Frosted Horizon'; }, undefined, {timeout: 5000});
  var aerialError = await page.evaluate(function () { var preview = document.querySelector('#wallpaperpick .preview'); return {horizon: preview.className.indexOf('horizon') >= 0, background: preview.style.backgroundImage}; });
  assert.ok(aerialError.horizon && !aerialError.background, 'actual web aerial error falls back to Horizon');
  await pickerStatus('live', 'live:aerial');
  await page.waitForFunction(function () { return document.querySelector('#wallpaperpick .preview .status').textContent === 'Unavailable — showing Frosted Horizon'; }, undefined, {timeout: 5000});
  var liveError = await page.evaluate(function () { var preview = document.querySelector('#wallpaperpick .preview'); return {horizon: preview.className.indexOf('horizon') >= 0, background: preview.style.backgroundImage}; });
  assert.ok(liveError.horizon && !liveError.background, 'actual web live error falls back to Horizon');
  await page.evaluate(function () { window.Image = window.__PickerImage; delete window.__PickerImage; });
  // The picker is a real sheet over the page now (it was never visible before): Back out of it and out of Settings,
  // as the remote does, before the Dock's hit tests. Settings is two panes, as on tvOS: Back from a section's rows
  // goes to the list of sections first, then closes it.
  await page.evaluate(function () { FreeMyTV.back(); FreeMyTV.back(); FreeMyTV.back(); });
  await page.waitForTimeout(700);
  var pickerClosed = await page.evaluate(function () {
    return !document.querySelector('#wallpaperpick').classList.contains('open') && !document.querySelector('#settings').classList.contains('open');
  });
  assert.ok(pickerClosed, 'Back closes the wallpaper picker and Settings');
  await page.evaluate(function () { window.__resolveWatch = Data.resolveWatch; });
  var netflix = {id: 'netflix', title: 'Netflix'};
  var plain = {id: 'com.example.notes', title: 'Notes'};
  var apple = {id: 'com.apple.music', title: 'Apple Music'};
  var spotify = {id: 'com.spotify.music', title: 'Spotify'};
  var result;
  await page.evaluate(function () { Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'History only', backdrop: 'preview/long-dunes.jpg', app: 'netflix'}); }; });
  result = await profile(page, 'shelf', netflix);
  assert.ok(result.media && result.title === 'History only', 'media Shelf renders actual resolved history hero');
  var mediaLayer = await page.evaluate(function () {
    var tile = document.querySelector('#tiles .tile');
    var point = document.elementFromPoint(210, 875);
    return {tileVisible: tile && getComputedStyle(tile).opacity !== '0', tileOnTop: !!(point && point.closest && point.closest('#tiles'))};
  });
  assert.ok(mediaLayer.tileVisible && mediaLayer.tileOnTop,
    'media art remains behind the focused Dock UI: ' + JSON.stringify(mediaLayer));
  await page.evaluate(function () {
    Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'Track A', backdrop: 'preview/long-dunes.jpg', app: 'netflix'}); };
    Home.testBackground({zone: 'shelf', app: {id: 'netflix', title: 'Netflix'}, tab: 0});
    Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'Track B', backdrop: 'preview/neon-meridian.jpg', app: 'netflix'}); };
    Home.testBackground({zone: 'shelf', app: {id: 'netflix', title: 'Netflix'}, tab: 0, keepKey: true});
  });
  // Until the new title and art are in (a busy machine can take longer than a frame or two), not a fixed 100 ms.
  // The art swaps in once its picture has loaded, which can trail the title: wait for both.
  await page.waitForFunction(function () {
    var art = document.querySelector('#photoA').style.backgroundImage + document.querySelector('#photoB').style.backgroundImage;
    return document.querySelector('#hero h1').textContent === 'Track B' && /neon-meridian/.test(art);
  }, null, {timeout: 3000}).catch(function () {});
  var changedMedia = await page.evaluate(function () { return {title: document.querySelector('#hero h1').textContent, art: document.querySelector('#photoA').style.backgroundImage + document.querySelector('#photoB').style.backgroundImage}; });
  assert.ok(changedMedia.title === 'Track B' && /neon-meridian/.test(changedMedia.art), 'same focused app refreshes changed media artwork without card-count changes: ' + JSON.stringify(changedMedia));
  await page.evaluate(function () { Data.resolveWatch = function (app, generation, done) { done(generation, {title: 'History only', backdrop: 'preview/long-dunes.jpg', app: 'netflix'}); }; });
  result = await profile(page, 'hero', netflix);
  assert.ok(result.media && result.zone === 'hero' && result.title === 'History only', 'history-only Shelf remains Hero');
  // The Top Shelf's full screen recesses the Dock (36 px lower, 0.75 opacity): it never hides it, nor the tab bar.
  await page.waitForTimeout(1200);
  var heroDock = await page.evaluate(function () {
    var tiles = getComputedStyle(document.querySelector('#tiles')), point = document.elementFromPoint(210, 911);
    return {zone: FreeMyTV.state.zone, tiles: parseFloat(tiles.opacity), platter: parseFloat(getComputedStyle(document.querySelector('#platter')).opacity),
      tabs: parseFloat(getComputedStyle(document.querySelector('#tabs')).opacity), onTop: !!(point && point.closest && point.closest('#tiles')),
      detailsBottom: document.querySelector('#details').getBoundingClientRect().bottom, platterTop: document.querySelector('#platter').getBoundingClientRect().top};
  });
  assert.ok(heroDock.zone === 'hero' && heroDock.tiles >= 0.7 && heroDock.platter >= 0.7 && heroDock.onTop,
    'the Dock stays visible in the Top Shelf, recessed: ' + JSON.stringify(heroDock));
  assert.ok(heroDock.tabs > 0.9, 'the tab bar stays in the Top Shelf: ' + JSON.stringify(heroDock));
  assert.ok(heroDock.detailsBottom <= heroDock.platterTop, 'the details sit above the recessed Dock: ' + JSON.stringify(heroDock));
  // Control Center (OK on its bubble, left of the pill) keeps the status pill and the tab bar (tvOS keeps its status
  // over the panel); the panel starts under the pill and the bubble, so they never overlap.
  await page.evaluate(function () { FreeMyTV.state.zone = 'top'; FreeMyTV.state.top = 3; FreeMyTV.activate(); });
  // Wait for the dimmer's spring to come to rest, not a fixed time: mid-flight it overshoots its 0.18 / 0.22 target.
  var dim = await settledOpacity(page, '#dim');
  var drawerOpen = await page.evaluate(function () {
    var pill = document.querySelector('#pill'), drawer = document.querySelector('#drawer');
    return {zone: FreeMyTV.state.zone, pill: parseFloat(getComputedStyle(pill).opacity), tabs: parseFloat(getComputedStyle(document.querySelector('#tabs')).opacity),
      pillBottom: Math.max(pill.getBoundingClientRect().bottom, document.querySelector('#ccbtn').getBoundingClientRect().bottom),
      drawerTop: drawer.getBoundingClientRect().top};
  });
  drawerOpen.dim = dim;
  assert.ok(drawerOpen.zone === 'drawer' && drawerOpen.pill > 0.9 && drawerOpen.tabs > 0.9,
    'the status pill and the tab bar stay with Control Center open: ' + JSON.stringify(drawerOpen));
  assert.ok(drawerOpen.pillBottom <= drawerOpen.drawerTop, 'Control Center starts under the status pill: ' + JSON.stringify(drawerOpen));
  assert.ok(drawerOpen.dim > 0 && drawerOpen.dim <= 0.22, 'Control Center only dims the page a little: ' + JSON.stringify(drawerOpen));
  await page.keyboard.press('Escape');
  await page.waitForTimeout(600);
  for (var i = 0; i < 3; i++) {
    result = await profile(page, ['tabs', 'apps', 'settings'][i], netflix);
    assert.ok(result.profile && !result.title, ['tabs', 'grid', 'settings'][i] + ' clears actual hero');
  }
  await page.evaluate(function () { Prefs.set('wallpaper', 'horizon:light'); Home.testBackground({zone: 'tabs', app: {id: 'netflix', title: 'Netflix'}, tab: 0}); });
  var profileLayer = await page.evaluate(function () {
    return {profile: document.body.className.indexOf('profile-background') >= 0,
      veil: getComputedStyle(document.querySelector('#sky .veil')).opacity};
  });
  assert.ok(profileLayer.profile && profileLayer.veil === '0',
    'selected profile wallpaper is not washed out by the legacy veil: ' + JSON.stringify(profileLayer));
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
  // The artwork comes through a routed request: wait for it to land (up to 5 s) rather than a fixed 100 ms; the assert
  // below still reports what was there if it never does.
  await page.waitForFunction(function (art) {
    return document.querySelector('#hero h1').textContent === 'Allowed Spotify' &&
      (document.querySelector('#photoA').style.backgroundImage + document.querySelector('#photoB').style.backgroundImage).indexOf(art) >= 0;
  }, allowedArtwork, {timeout: 5000}).catch(function () {});
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
  // Until its picture has loaded and swapped in (it can take longer than 100 ms on a busy machine).
  await page.waitForFunction(function () {
    return /orbital/.test(document.querySelector('#photoA').style.backgroundImage + document.querySelector('#photoB').style.backgroundImage);
  }, null, {timeout: 3000}).catch(function () {});
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
  // Upstream's night dim uses the same id as Control Center's dimmer: one #dim, and the city and key pickers are there.
  assert.strictEqual(await page.evaluate(function () { return document.querySelectorAll('#dim').length; }), 1, 'one #dim');
  assert.ok(await page.evaluate(function () { return !!document.querySelector('#citypick') && !!document.querySelector('#keypick'); }), 'city and key pickers');

  await browser.close(); server.close();
  console.log('home-background-dom-test: passed');
})().catch(async function (err) {
  // A failed check must not leave the browser running: the process would never exit and CI would hang.
  console.error(err.stack || err);
  try { if (browser) await browser.close(); } catch (e) { /* exiting anyway */ }
  server.close();
  process.exit(1);
});
