'use strict';

var assert = require('assert');
var fs = require('fs');
var vm = require('vm');
var source = fs.readFileSync('web/wallpaper.js', 'utf8');
var dataSource = fs.readFileSync('web/data.js', 'utf8');
var previewSource = fs.readFileSync('preview/preview.js', 'utf8');
var context = {};
vm.runInNewContext(source, context, {filename: 'web/wallpaper.js'});
var Wallpaper = context.Wallpaper;

var history = [
  {kind: 'movie', tmdb: 22, pkg: 'com.netflix.ninja', appKey: 'netflix', at: 300},
  {kind: 'tv', tmdb: 11, pkg: 'com.disney.disneyplus', appKey: 'disney', at: 200}
];
var picks = [
  {kind: 'movie', id: 33, app: 'netflix', pkg: 'com.netflix.ninja'},
  {kind: 'tv', id: 44, app: 'disney', pkg: 'com.disney.disneyplus'}
];
var netflix = {pkg: 'com.netflix.ninja', appKey: 'netflix', media: true};
var disney = {pkg: 'com.disney.disneyplus', appKey: 'disney', media: true};
var music = {pkg: 'com.apple.music', appKey: 'applemusic', media: true, music: true};

function choice(log, featured, app, nowPlaying) {
  return Wallpaper.selectMedia(log, featured, app, nowPlaying);
}

assert.strictEqual(choice(history, picks, netflix, null).source, 'history', 'newest matching history wins');
assert.strictEqual(choice(history, picks, netflix, null).item.tmdb, 22);
assert.strictEqual(choice(history, picks, disney, null).item.tmdb, 11, 'history never leaks across packages');

var exact = choice([
  {kind: 'movie', tmdb: 91, pkg: 'another.netflix', appKey: 'netflix', at: 900},
  {kind: 'movie', tmdb: 92, pkg: netflix.pkg, appKey: 'other', at: 800}
], picks, netflix, null);
assert.strictEqual(exact.item.tmdb, 92, 'an exact package match wins over app-key fallback');

var webFallback = choice([{kind: 'tv', tmdb: 77, appKey: 'netflix', at: 1}], picks,
  {pkg: '', appKey: 'netflix', media: true}, null);
assert.strictEqual(webFallback.item.tmdb, 77, 'app-key fallback works when the package is unavailable');

var skipped = choice([
  {kind: 'movie', tmdb: 0, pkg: netflix.pkg, at: 900},
  {kind: 'movie', tmdb: -1, pkg: netflix.pkg, at: 800},
  {kind: 'movie', pkg: netflix.pkg, at: 700},
  {kind: 'book', tmdb: 99, pkg: netflix.pkg, at: 600}
], picks, netflix, null);
assert.strictEqual(skipped.source, 'featured', 'invalid history is skipped');
assert.strictEqual(skipped.item, picks[0], 'first focused-app featured pick is the fallback');

assert.strictEqual(choice(history, picks, {pkg: 'settings', appKey: 'settings', media: false}, null), null,
  'Settings and non-media apps have no media choice');
assert.strictEqual(choice([], picks, music, null), null, 'Apple Music never receives a TMDB featured pick');
var playing = {pkg: 'com.apple.music', title: 'Midnight City', artwork: 'album.jpg', active: true};
assert.strictEqual(choice([], picks, music, playing).source, 'nowPlaying', 'active Apple Music Now Playing wins');
assert.strictEqual(choice([], picks, music, playing).item, playing);
assert.strictEqual(choice([], picks, music, {title: 'No art', active: true}), null, 'Now Playing needs artwork and title');
assert.strictEqual(choice([], picks, music, {title: 'Paused', artwork: 'album.jpg', active: false}), null,
  'Now Playing must be active');

var mismatch = choice([{kind: 'movie', tmdb: 55, pkg: disney.pkg, appKey: 'disney', at: 100}], picks, netflix, null);
assert.strictEqual(mismatch.source, 'featured');
assert.strictEqual(mismatch.item, picks[0]);

var unordered = [
  {kind: 'movie', tmdb: 101, pkg: netflix.pkg, at: 100},
  {kind: 'tv', tmdb: 202, pkg: netflix.pkg, at: 300},
  {kind: 'movie', tmdb: 303, pkg: netflix.pkg, at: 200}
];
assert.strictEqual(choice(unordered, picks, netflix, null).item.tmdb, 202, 'newest is deterministic even if input is unsorted');
assert.strictEqual(choice(unordered, picks, netflix, null).item.tmdb, 202, 'selection is stable across calls');
assert.strictEqual(unordered[0].tmdb, 101, 'selection does not mutate history');

var dataContext = {
  console: console,
  Date: Date,
  Math: Math,
  JSON: JSON,
  navigator: {language: 'en-US'},
  TV: {zone: function () { return ''; }},
  ZONES: {},
  CONFIG: {latitude: '', longitude: '', tmdbKey: ''},
  Profiles: {log: function () { return [{kind: 'movie', tmdb: 22, appKey: 'netflix', at: 300}]; }},
  PREVIEW_PICKS: [{kind: 'movie', id: 22, app: 'disney', service: 'Disney+', title: 'Seeded', detailed: true}],
  localStorage: {getItem: function () { return null; }, setItem: function () {}},
  XMLHttpRequest: function () {},
  setTimeout: function () { return 0; },
  clearTimeout: function () {}
};
dataContext.window = dataContext;
vm.runInNewContext(source, dataContext, {filename: 'web/wallpaper.js'});
vm.runInNewContext(dataSource, dataContext, {filename: 'web/data.js'});
var calls = 0, resolvedGeneration, resolvedPick;
dataContext.Data.resolveWatch({pkg: '', appKey: 'netflix', media: true}, 73, function (generation, pick) {
  calls++;
  resolvedGeneration = generation;
  resolvedPick = pick;
});
assert.strictEqual(calls, 1, 'Data resolves exactly once');
assert.strictEqual(resolvedGeneration, 73, 'Data preserves the caller generation');
assert.notStrictEqual(resolvedPick, dataContext.PREVIEW_PICKS[0], 'Data isolates a reused shelf pick');
assert.strictEqual(resolvedPick.app, 'netflix', 'Data keeps the focused app authoritative when reusing artwork');
assert.strictEqual(dataContext.PREVIEW_PICKS[0].app, 'disney', 'Data does not mutate the shared shelf pick');

var previewValues = {watchLog: [{kind: 'tv', tmdb: 9003, appKey: 'disney', at: 1}]};
var previewStore = {
  get: function (key, fallback) { return previewValues[key] === undefined ? fallback : previewValues[key]; },
  set: function (key, value) { previewValues[key] = value; }
};
var previewContext = {
  CONFIG: {},
  Date: Date,
  Number: Number,
  Profiles: {
    current: function () { return {role: 'manager'}; },
    store: function () { return previewStore; },
    seedPreview: function () {}
  }
};
previewContext.window = previewContext;
vm.runInNewContext(previewSource, previewContext, {filename: 'preview/preview.js'});
previewContext.Profiles.seedPreview();
assert.strictEqual(previewValues.watchLog[0].appKey, 'netflix', 'preview active profile starts with Netflix history');
assert.strictEqual(previewValues.watchLog[0].tmdb, 9001, 'preview history maps exactly to a seeded pick');

console.log('wallpaper-policy-test: passed');
