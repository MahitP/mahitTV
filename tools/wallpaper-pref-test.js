'use strict';

var assert = require('assert');
var fs = require('fs');
var vm = require('vm');
var wallpaperSource = fs.readFileSync('web/wallpaper.js', 'utf8');
var dataSource = fs.readFileSync('web/data.js', 'utf8');

function load(initial, profiles) {
  var values = {}, writes = [], saved = [];
  Object.keys(initial || {}).forEach(function (key) { values[key] = initial[key]; });
  var store = {
    get: function (name, fallback) { return values[name] === undefined ? fallback : values[name]; },
    set: function (name, value) { values[name] = value; writes.push([name, value]); }
  };
  var context = {
    console: console,
    Date: Date,
    Math: Math,
    JSON: JSON,
    navigator: {language: 'en-US'},
    TV: {zone: function () { return ''; }},
    ZONES: {},
    I18N: {},   // data.js adds its date names to it as it loads, after strings.js on the TV
    CONFIG: {latitude: '', longitude: ''},
    localStorage: {
      getItem: function (key) { return key === 'fmtv.prefs' ? JSON.stringify(initial || {}) : null; },
      setItem: function (key, value) { saved.push([key, value]); }
    },
    XMLHttpRequest: function () {},
    setTimeout: function () { return 0; },
    clearTimeout: function () {}
  };
  context.window = context;
  if (profiles) context.Profiles = {store: function () { return store; }};
  vm.runInNewContext(wallpaperSource, context, {filename: 'web/wallpaper.js'});
  vm.runInNewContext(dataSource, context, {filename: 'web/data.js'});
  return {Prefs: context.Prefs, Wallpaper: context.Wallpaper, values: values, writes: writes, saved: saved};
}

['', 'valley.jpg'].forEach(function (legacy) {
  var env = load({wallpaper: legacy}, true);
  assert.strictEqual(env.Prefs.wallpaper(), 'horizon:auto');
  assert.deepStrictEqual(env.writes, [['wallpaper', 'horizon:auto']]);
  assert.strictEqual(env.Prefs.version, 0);
  assert.strictEqual(env.Prefs.wallpaper(), 'horizon:auto');
  assert.strictEqual(env.writes.length, 1);
  assert.strictEqual(env.Prefs.version, 0);
});

var early = load({wallpaper: 'valley.jpg'}, false);
assert.strictEqual(early.Prefs.wallpaper(), 'horizon:auto');
assert.deepStrictEqual(early.writes, []);
assert.deepStrictEqual(early.saved, []);

var valid = load({}, true);
valid.Prefs.set('wallpaper', 'aerial:yosemite');
assert.strictEqual(valid.values.wallpaper, 'aerial:yosemite');
assert.strictEqual(valid.Prefs.wallpaper(), 'aerial:yosemite');
assert.strictEqual(valid.writes.length, 1);
assert.strictEqual(valid.Prefs.version, 1);
valid.Prefs.set('wallpaper', 'old-file.jpg');
assert.strictEqual(valid.values.wallpaper, 'horizon:auto');
assert.strictEqual(valid.Prefs.version, 2);

function consumerUrl(id) {
  var asset = valid.Wallpaper.legacyAsset(id);
  return asset ? 'aerials/' + asset : '';
}
assert.strictEqual(consumerUrl('horizon:auto'), '');
assert.strictEqual(consumerUrl('studio:teal'), '');
assert.strictEqual(consumerUrl('aerial:yosemite'), 'aerials/valley.jpg');
assert.notStrictEqual(consumerUrl('horizon:auto'), 'aerials/horizon:auto');

console.log('wallpaper-pref-test: passed');
