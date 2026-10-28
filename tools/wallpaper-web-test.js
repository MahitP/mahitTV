'use strict';

var assert = require('assert');
var fs = require('fs');
var vm = require('vm');
var source = fs.readFileSync('web/wallpaper.js', 'utf8');
var css = fs.readFileSync('web/home.css', 'utf8');
var html = fs.readFileSync('web/index.html', 'utf8');
var shaders = fs.readFileSync('app/src/io/freemytv/Shaders.java', 'utf8');
var context = {};
vm.runInNewContext(source, context, {filename: 'web/wallpaper.js'});
var Wallpaper = context.Wallpaper;

assert.ok(Wallpaper, 'Wallpaper is exported');

var valid = [
  'horizon:auto', 'horizon:light', 'horizon:dark',
  'aerial:yosemite', 'aerial:iceland', 'aerial:north-america-aurora',
  'aerial:palau-coral', 'aerial:new-york', 'aerial:sonoma-coast',
  'studio:black', 'studio:space-gray', 'studio:midnight-blue', 'studio:deep-forest',
  'studio:teal', 'studio:warm-stone', 'studio:silver-mist', 'live:aerial'
];
valid.forEach(function (id) { assert.strictEqual(Wallpaper.normalize(id), id); });
assert.strictEqual(Wallpaper.normalize(null), 'horizon:auto');
assert.strictEqual(Wallpaper.normalize(''), 'horizon:auto');
assert.strictEqual(Wallpaper.normalize('missing'), 'horizon:auto');
assert.strictEqual(Wallpaper.normalize('HORIZON:AUTO'), 'horizon:auto');
assert.strictEqual(Wallpaper.normalize('valley.jpg'), 'horizon:auto');
assert.strictEqual(Wallpaper.normalize('coast.jpg'), 'horizon:auto');
assert.strictEqual(Wallpaper.normalize('aurora.jpg'), 'horizon:auto');
assert.strictEqual(Wallpaper.normalize('earth.jpg'), 'horizon:auto');

assert.strictEqual(Wallpaper.legacyAsset('aerial:yosemite'), 'valley.jpg');
assert.strictEqual(Wallpaper.legacyAsset('aerial:iceland'), 'coast.jpg');
assert.strictEqual(Wallpaper.legacyAsset('aerial:north-america-aurora'), 'aurora.jpg');
assert.strictEqual(Wallpaper.legacyAsset('aerial:new-york'), 'earth.jpg');
['horizon:auto', 'horizon:light', 'horizon:dark', 'studio:teal', 'live:aerial',
  'aerial:palau-coral', 'aerial:sonoma-coast', 'missing', null].forEach(function (id) {
  assert.strictEqual(Wallpaper.legacyAsset(id), '');
});

assert.strictEqual(Wallpaper.dark('horizon:light', true), false);
assert.strictEqual(Wallpaper.dark('horizon:dark', false), true);
assert.strictEqual(Wallpaper.dark('horizon:auto', false), false);
assert.strictEqual(Wallpaper.dark('horizon:auto', true), true);
assert.strictEqual(Wallpaper.dark('studio:teal', false), false);
assert.strictEqual(Wallpaper.dark('studio:teal', true), true);
assert.strictEqual(Wallpaper.dark('valley.jpg', false), false);
assert.strictEqual(Wallpaper.dark('valley.jpg', true), true);

assert.deepStrictEqual(Array.prototype.slice.call(Wallpaper.palette(true)), ['#0D131F', '#1C2638', '#151E2D', '#080B12']);
assert.deepStrictEqual(Array.prototype.slice.call(Wallpaper.palette(false)), ['#B8C7D6', '#F5EBD6', '#DCE6EF', '#7E9BB5']);
var changedPalette = Wallpaper.palette(true);
changedPalette[0] = '#000000';
assert.strictEqual(Wallpaper.palette(true)[0], '#0D131F');
assert.deepStrictEqual(JSON.parse(JSON.stringify(Wallpaper.horizonGeometry())), [
  [-0.08, 0.69, 0.83],
  [0.53, 0.48, 0.78],
  [1.06, 1.04, 1.04]
]);
assert.strictEqual(Wallpaper.horizonVignette(false), 0.12);
assert.strictEqual(Wallpaper.horizonVignette(true), 0.18);
var changedGeometry = Wallpaper.horizonGeometry();
changedGeometry[0][0] = 42;
changedGeometry[1] = [42, 42, 42];
assert.deepStrictEqual(JSON.parse(JSON.stringify(Wallpaper.horizonGeometry())), [
  [-0.08, 0.69, 0.83],
  [0.53, 0.48, 0.78],
  [1.06, 1.04, 1.04]
]);
assert.strictEqual(Wallpaper.media('shelf', true), true);
assert.strictEqual(Wallpaper.media('hero', true), true);
assert.strictEqual(Wallpaper.media('shelf', false), false);
assert.strictEqual(Wallpaper.media('hero', false), false);
assert.strictEqual(Wallpaper.media('dock', true), false);
assert.strictEqual(Wallpaper.media(null, true), false);

var expected = [
  ['aerial:yosemite', '4109D42A-D717-46A7-A9A2-FE53A82B25C0', 'Yosemite Valley', 'https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/Y011_C001_SDR_2K_AVC.mov', 'https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/Y011_C001_900x580.png'],
  ['aerial:iceland', '8590D0C5-E344-4FAC-A39A-FD7BC652AEDA', 'Iceland Coast', 'https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/I003_C004_SDR_2K_AVC.mov', 'https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/I003_C004_900x580.png'],
  ['aerial:north-america-aurora', '737E9E24-49BE-4104-9B72-F352DE1AD2BF', 'North America Aurora', 'https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_GMT314_139M_170NC_NORTH_AMERICA_AURORA__COMP_v22_SDR_20181206_v12CC_SDR_2K_AVC.mov', 'https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/GMT314_139M_900x580.png'],
  ['aerial:palau-coral', 'F07CC61B-30FC-4614-BDAD-3240B61F6793', 'Palau Coral Colors', 'https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/PA_A004_C003_SDR_20190719_SDR_2K_AVC.mov', 'https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/PA_A004_C003_900x580.png'],
  ['aerial:new-york', '3BA0CFC7-E460-4B59-A817-B97F9EBB9B89', 'New York from Above', 'https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_N008_C009_PS_v01_SDR_PS_20180925_SDR_2K_AVC.mov', 'https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/N008_C009_900x580.png'],
  ['aerial:sonoma-coast', 'FE876489-CBD5-479B-A8F0-1B67F0741CEA', 'Sonoma Horizon', '', 'https://sylvan.apple.com/itunes-assets/Aerials126/v4/51/ff/08/51ff0824-8da5-78f0-e218-9e61264965bb/W010_C003_F01@2x.png']
];
assert.strictEqual(Wallpaper.aerials.length, expected.length);
expected.forEach(function (record, i) {
  var aerial = Wallpaper.aerials[i];
  assert.deepStrictEqual([aerial.id, aerial.asset, aerial.title, aerial.video, aerial.preview], record);
  assert.deepStrictEqual(JSON.parse(JSON.stringify(Wallpaper.find(aerial.id))), JSON.parse(JSON.stringify(aerial)));
  assert.notStrictEqual(Wallpaper.find(aerial.id), aerial);
});
assert.strictEqual(Wallpaper.find('horizon:auto'), null);
assert.strictEqual(Wallpaper.find('missing'), null);
assert.strictEqual(Wallpaper.find(null), null);

Wallpaper.aerials.forEach(function (aerial) {
  if (aerial.video) assert.strictEqual(Wallpaper.allowed(aerial.video), true);
  assert.strictEqual(Wallpaper.allowed(aerial.preview), true);
});
var good = Wallpaper.aerials[0].preview;
assert.strictEqual(Wallpaper.allowed(null), false);
assert.strictEqual(Wallpaper.allowed(''), false);
assert.strictEqual(Wallpaper.allowed('not a url'), false);
assert.strictEqual(Wallpaper.allowed(good.replace('https://', 'http://')), false);
assert.strictEqual(Wallpaper.allowed(good.replace('sylvan.apple.com', 'example.com')), false);
assert.strictEqual(Wallpaper.allowed(good.replace('sylvan.apple.com', 'sylvan.apple.com.example.com')), false);
assert.strictEqual(Wallpaper.allowed('https://example.com/https://sylvan.apple.com/'), false);
assert.strictEqual(Wallpaper.allowed(good + '?download=1'), false);
assert.strictEqual(Wallpaper.allowed(good.substring(0, good.length - 1)), false);
assert.strictEqual(Wallpaper.allowed('https://sylvan.apple.com/'), false);

assert.deepStrictEqual(JSON.parse(JSON.stringify(Wallpaper.solids)), [
  {id: 'studio:black', color: '#000000'},
  {id: 'studio:space-gray', color: '#1C1C1E'},
  {id: 'studio:midnight-blue', color: '#121A2A'},
  {id: 'studio:deep-forest', color: '#11221C'},
  {id: 'studio:teal', color: '#1B3B3A'},
  {id: 'studio:warm-stone', color: '#8E8E93'},
  {id: 'studio:silver-mist', color: '#D1D5DB'}
]);

var originalPreview = Wallpaper.aerials[0].preview;
Wallpaper.aerials[0].id = 'aerial:changed';
Wallpaper.aerials[0].preview = 'https://sylvan.apple.com/changed.png';
Wallpaper.aerials[1] = {id: 'aerial:injected', preview: 'https://sylvan.apple.com/injected.png'};
Wallpaper.solids[0].id = 'studio:changed';
var found = Wallpaper.find('aerial:yosemite');
found.id = 'aerial:changed-again';
found.preview = 'https://sylvan.apple.com/changed-again.png';
assert.strictEqual(Wallpaper.normalize('aerial:yosemite'), 'aerial:yosemite');
assert.strictEqual(Wallpaper.normalize('aerial:injected'), 'horizon:auto');
assert.strictEqual(Wallpaper.normalize('studio:black'), 'studio:black');
assert.strictEqual(Wallpaper.find('aerial:yosemite').id, 'aerial:yosemite');
assert.strictEqual(Wallpaper.find('aerial:yosemite').preview, originalPreview);
assert.strictEqual(Wallpaper.allowed(originalPreview), true);
assert.strictEqual(Wallpaper.allowed('https://sylvan.apple.com/changed.png'), false);
assert.strictEqual(Wallpaper.allowed('https://sylvan.apple.com/injected.png'), false);
assert.strictEqual(Wallpaper.allowed('https://sylvan.apple.com/changed-again.png'), false);

assert.strictEqual(/\b(?:const|let)\b|=>|`|\b(?:Promise|Map|Set|URL)\b|Array\.from/.test(source), false, 'source remains ES5');

assert.ok(html.indexOf('id="horizonDay"') < html.indexOf('id="art"'), 'day Horizon sits below photo art');
assert.ok(html.indexOf('id="horizonNight"') < html.indexOf('id="art"'), 'night Horizon sits below photo art');
[
  '83% 83% at -8% 69%', '#F5EBD6',
  '78% 78% at 53% 48%', '#DCE6EF',
  '104% 104% at 106% 104%', '#7E9BB5',
  '#B8C7D6 0%', '#DCE6EF 56%', '#7E9BB5 100%',
  '#1C2638', '#151E2D', '#080B12', '#0D131F 0%',
  'rgba(0, 0, 0, 0.12)', 'rgba(0, 0, 0, 0.18)'
].forEach(function (token) { assert.ok(css.indexOf(token) >= 0, 'Horizon CSS token ' + token); });
['uniform float2 res;', 'uniform float2 layer;', 'uniform half3 c0;', 'uniform half3 c1;',
  'uniform half3 c2;', 'uniform half3 c3;', 'uniform float dark;',
  '(hash(xy) - 0.5) / 255.0'].forEach(function (token) {
  assert.ok(shaders.indexOf(token) >= 0, 'Horizon AGSL token ' + token);
});
var horizonShader = shaders.substring(shaders.indexOf('static final String HORIZON'), shaders.indexOf('static final String GLASS'));
assert.strictEqual(/uniform float t;|\banimation\b/.test(horizonShader), false, 'Horizon shader has no time or animation');
console.log('wallpaper-web-test: passed');
