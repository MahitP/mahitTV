'use strict';

/* Contract checks for the Home Top Shelf controller.  Keep these close to the
 * DOM implementation: the TV browser is ES5 and this is deliberately runnable
 * without a browser harness. */
var assert = require('assert');
var fs = require('fs');
var source = fs.readFileSync('web/home.js', 'utf8');
var css = fs.readFileSync('web/home.css', 'utf8');

assert.ok(/backgroundGeneration/.test(source), 'background controller has a generation token');
assert.ok(/Wallpaper\.media\(st\.zone,/.test(source), 'only Shelf and Hero may request media art');
assert.ok(/Data\.resolveWatch\(app, generation/.test(source), 'watch resolver receives generation');
assert.ok(/generation !== backgroundGeneration/.test(source), 'late watch results are rejected');
assert.ok(/backgroundMode/.test(source), 'profile and media states are explicit');
assert.ok(/mediaPage/.test(source), 'resolved media state is independent of shelf picks');
assert.ok(/media-background/.test(source) && /profile-background/.test(source), 'background state classes are set');
assert.ok(/cubic-bezier\(\.2,\.8,\.2,1\)/.test(source), 'photo fade has the specified curve');
assert.ok(/0\.7s/.test(source), 'photo fade is 700ms');
assert.ok(/\.media-background/.test(css) && /\.profile-background/.test(css), 'CSS gates shelf chrome by background mode');
assert.ok(/\.profile-background #hero/.test(css), 'profile wallpaper clears Top Shelf hero');
assert.ok(/\.profile-background #details/.test(css), 'profile wallpaper clears details');
assert.ok(/\.profile-background #dots/.test(css), 'profile wallpaper clears dots');
assert.ok(/\.profile-background #watch/.test(css), 'profile wallpaper clears Play');

console.log('home-background-test: passed');
