var assert = require('assert'), fs = require('fs'), vm = require('vm');
var source = fs.readFileSync('web/wallpaper.js', 'utf8');
var context = {}; vm.runInNewContext(source, context, {filename: 'web/wallpaper.js'});
var W = context.Wallpaper, events = {}, calls = [];
var video = {style: {}, muted: false, loop: false, setAttribute: function (k, v) { this[k] = v; },
  addEventListener: function (k, fn) { events[k] = fn; }, removeAttribute: function (k) { calls.push('remove:' + k); },
  play: function () { calls.push('play'); return {catch: function (fn) { calls.push('catch'); }}; }, pause: function () { calls.push('pause'); }, load: function () { calls.push('load'); }};
var fallback = 0, c = W.videoController(video, function () { fallback++; });
assert.strictEqual(c.show('https://evil.example/a.mov'), false, 'rejects a non-allowlisted video');
assert.strictEqual(c.show(W.aerials[0].preview), false, 'does not play preview stills');
assert.strictEqual(c.show(W.aerials[0].video), true, 'plays allowlisted video');
assert.strictEqual(video.muted, true); assert.strictEqual(video.loop, true); assert.strictEqual(video.tabindex, '-1');
events.error(); assert.strictEqual(fallback, 1, 'video error falls back'); assert.ok(calls.indexOf('pause') >= 0 && calls.indexOf('remove:src') >= 0, 'failure clears video');
var sonoma = W.liveAerial(12); assert.ok(sonoma && sonoma.id !== 'aerial:sonoma-coast' && sonoma.video, 'live selection excludes Sonoma');
c.show(W.aerials[1].video); c.hide(); assert.ok(calls.indexOf('load') >= 0, 'hide pauses and unloads');
console.log('aerial-video-web-test: passed');
