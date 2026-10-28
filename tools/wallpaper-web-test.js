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

var artAt = html.indexOf('id="art"');
var dayAt = html.indexOf('id="horizonDay"');
var nightAt = html.indexOf('id="horizonNight"');
assert.ok(artAt >= 0 && dayAt >= 0 && dayAt < artAt, 'day Horizon sits below photo art');
assert.ok(artAt >= 0 && nightAt >= 0 && nightAt < artAt, 'night Horizon sits below photo art');

function cssBlock(text, selector) {
  var start = text.indexOf(selector);
  assert.notStrictEqual(start, -1, 'CSS selector exists: ' + selector);
  var open = text.indexOf('{', start);
  assert.notStrictEqual(open, -1, 'CSS block opens: ' + selector);
  var depth = 0;
  for (var at = open; at < text.length; at++) {
    if (text.charAt(at) === '{') depth++;
    if (text.charAt(at) === '}') {
      depth--;
      if (depth === 0) return text.substring(open + 1, at);
    }
  }
  assert.fail('CSS block closes: ' + selector);
}

function javaAssignment(text, marker) {
  var start = text.indexOf(marker);
  assert.notStrictEqual(start, -1, 'Java assignment exists: ' + marker);
  var inString = false;
  var escaped = false;
  for (var at = start + marker.length; at < text.length; at++) {
    var ch = text.charAt(at);
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === '"') inString = false;
    } else if (ch === '"') {
      inString = true;
    } else if (ch === ';') {
      return text.substring(start + marker.length, at);
    }
  }
  assert.fail('Java assignment terminates: ' + marker);
}

function javaStrings(expression) {
  var strings = expression.match(/"(?:\\.|[^"\\])*"/g);
  assert.ok(strings && strings.length, 'Java assignment contains strings');
  return strings.map(function (literal) { return JSON.parse(literal); }).join('');
}

function normalized(value) {
  return value.replace(/\s+/g, ' ').replace(/\s*,\s*/g, ',').trim();
}

function backgrounds(block) {
  var values = [];
  var pattern = /background\s*:\s*([^;]+);/g;
  var match;
  while ((match = pattern.exec(block)) !== null) values.push(normalized(match[1]));
  return values;
}

function assertOrdered(text, pieces, message) {
  var after = -1;
  pieces.forEach(function (piece) {
    var foundAt = text.indexOf(piece, after + 1);
    assert.ok(foundAt > after, message + ': ' + piece);
    after = foundAt;
  });
}

function assertHorizonParity(cssText, shaderText) {
  var day = cssBlock(cssText, '#horizonDay {');
  var night = cssBlock(cssText, '#horizonNight {');
  var dayVignette = cssBlock(cssText, '#horizonDay::after {');
  var nightVignette = cssBlock(cssText, '#horizonNight::after {');
  var dither = cssBlock(cssText, '#sky .horizon::before {');

  assert.deepStrictEqual(backgrounds(day), [
    normalized('-webkit-radial-gradient(-8% 69%, 83% 83%, #F5EBD6 0%, transparent 72%), ' +
      '-webkit-radial-gradient(53% 48%, 78% 78%, #DCE6EF 0%, transparent 74%), ' +
      '-webkit-radial-gradient(106% 104%, 104% 104%, #7E9BB5 0%, transparent 72%), ' +
      '-webkit-linear-gradient(top, #B8C7D6 0%, #DCE6EF 56%, #7E9BB5 100%)'),
    normalized('radial-gradient(83% 83% at -8% 69%, #F5EBD6 0%, transparent 72%), ' +
      'radial-gradient(78% 78% at 53% 48%, #DCE6EF 0%, transparent 74%), ' +
      'radial-gradient(104% 104% at 106% 104%, #7E9BB5 0%, transparent 72%), ' +
      'linear-gradient(180deg, #B8C7D6 0%, #DCE6EF 56%, #7E9BB5 100%)')
  ], 'day Horizon has the exact four ordered layers');
  assert.deepStrictEqual(backgrounds(night), [
    normalized('-webkit-radial-gradient(-8% 69%, 83% 83%, #1C2638 0%, transparent 72%), ' +
      '-webkit-radial-gradient(53% 48%, 78% 78%, #151E2D 0%, transparent 74%), ' +
      '-webkit-radial-gradient(106% 104%, 104% 104%, #080B12 0%, transparent 72%), ' +
      '-webkit-linear-gradient(top, #0D131F 0%, #1C2638 56%, #080B12 100%)'),
    normalized('radial-gradient(83% 83% at -8% 69%, #1C2638 0%, transparent 72%), ' +
      'radial-gradient(78% 78% at 53% 48%, #151E2D 0%, transparent 74%), ' +
      'radial-gradient(104% 104% at 106% 104%, #080B12 0%, transparent 72%), ' +
      'linear-gradient(180deg, #0D131F 0%, #1C2638 56%, #080B12 100%)')
  ], 'night Horizon has the exact four ordered layers');

  assert.deepStrictEqual(backgrounds(dayVignette), [
    normalized('-webkit-radial-gradient(center, ellipse, rgba(0, 0, 0, 0) 45%, rgba(0, 0, 0, 0.12) 100%)'),
    normalized('radial-gradient(ellipse at center, rgba(0, 0, 0, 0) 45%, rgba(0, 0, 0, 0.12) 100%)')
  ], 'day vignette is scoped and exact');
  assert.deepStrictEqual(backgrounds(nightVignette), [
    normalized('-webkit-radial-gradient(center, ellipse, rgba(0, 0, 0, 0) 45%, rgba(0, 0, 0, 0.18) 100%)'),
    normalized('radial-gradient(ellipse at center, rgba(0, 0, 0, 0) 45%, rgba(0, 0, 0, 0.18) 100%)')
  ], 'night vignette is scoped and exact');
  assert.deepStrictEqual(backgrounds(dither), [
    normalized('-webkit-repeating-linear-gradient(135deg, rgba(255, 255, 255, 0.004) 0, rgba(255, 255, 255, 0.004) 1px, rgba(0, 0, 0, 0.004) 1px, rgba(0, 0, 0, 0.004) 2px)'),
    normalized('repeating-linear-gradient(135deg, rgba(255, 255, 255, 0.004) 0, rgba(255, 255, 255, 0.004) 1px, rgba(0, 0, 0, 0.004) 1px, rgba(0, 0, 0, 0.004) 2px)')
  ], 'Horizon dither is deterministic and exact');
  assert.strictEqual(/animation/i.test(dither), false, 'Horizon dither is nonanimated');

  var expression = javaAssignment(shaderText, 'static final String HORIZON =');
  var horizon = javaStrings(expression);
  var uniforms = [];
  var uniformPattern = /uniform\s+(float2|half3|float)\s+([a-zA-Z0-9_]+)\s*;/g;
  var uniform;
  while ((uniform = uniformPattern.exec(horizon)) !== null) uniforms.push(uniform[1] + ' ' + uniform[2]);
  assert.deepStrictEqual(uniforms,
    ['float2 res', 'float2 layer', 'half3 c0', 'half3 c1', 'half3 c2', 'half3 c3', 'float dark'],
    'Horizon has only the required uniforms in order');
  assertOrdered(horizon, [
    'mix(c0, c2, half(clamp(uv.y / 0.56, 0.0, 1.0)))',
    'mix(col, c3, half(clamp((uv.y - 0.56) / 0.44, 0.0, 1.0)))',
    'mix(col, c3, half(field(uv, float2(1.06, 1.04), 1.04, 0.72)))',
    'mix(col, c2, half(field(uv, float2(0.53, 0.48), 0.78, 0.74)))',
    'mix(col, c1, half(field(uv, float2(-0.08, 0.69), 0.83, 0.72)))'
  ], 'Horizon palette roles and layers stay ordered');
  assert.ok(horizon.indexOf('mix(0.12, 0.18, dark)') >= 0, 'Horizon vignette strengths are exact');
  assert.ok(horizon.indexOf('(hash(xy) - 0.5) / 255.0') >= 0, 'Horizon dither is exact');
  assert.strictEqual(/uniform\s+(?:float|half)(?:[234])?\s+(?:t|time)\b/.test(horizon), false,
    'Horizon has no time uniform');
  assert.strictEqual(/\b(?:t|time|speed|noise|fbm|sin|cos)\b/.test(horizon), false,
    'Horizon has no animated noise or time expression');
}

function parityMustReject(label, changedCss, changedShaders) {
  assert.throws(function () { assertHorizonParity(changedCss, changedShaders); }, label);
}

assertHorizonParity(css, shaders);
parityMustReject('rejects a changed center', css.replace('83% 83% at -8% 69%', '83% 83% at -7% 69%'), shaders);
parityMustReject('rejects a changed color', css.replace('#F5EBD6 0%', '#F5EAD6 0%'), shaders);
parityMustReject('rejects a changed fade', css.replace('transparent 74%', 'transparent 73%'), shaders);
parityMustReject('rejects a missing uniform', css, shaders.replace('uniform float dark;', 'uniform float darkMode;'));
parityMustReject('rejects changed shader geometry', css,
  shaders.replace('float2(0.53, 0.48), 0.78, 0.74', 'float2(0.54, 0.48), 0.78, 0.74'));
console.log('wallpaper-web-test: passed');
