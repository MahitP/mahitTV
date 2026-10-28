/* Host-side contract test for the clock-free ES5 Welly/bloub port. */
'use strict';
var fs = require('fs');
var vm = require('vm');
var path = require('path');
var root = path.resolve(__dirname, '..');
var source = fs.readFileSync(path.join(root, 'web', 'welly-bot.js'), 'utf8');
var box = {window: {}, Math: Math};
vm.runInNewContext(source, box, {filename: 'welly-bot.js'});
var B = box.window.WellyBot;
var fixture = JSON.parse(fs.readFileSync(path.join(root, 'tools', 'fixtures', 'welly-bloub-b4bb3c1b.json'), 'utf8'));
function ok(value, message) { if (!value) throw new Error(message); }
function near(a, b, e, message) { ok(Math.abs(a - b) <= e, message + ': ' + a + ' != ' + b); }

ok(B && B.Engine, 'WellyBot.Engine exported');
ok(B.PROFILE_SAMPLES === 64, '64 angular samples');
ok(B.COLOR === '#2fbfa0', 'second green swatch');
ok(B.SHAPE === 'galet' && B.EXPRESSION === 'attentif', 'fixed identity');
ok(B.STATES.length === 15 && B.STATES.indexOf('swirl') >= 0, 'all 15 states');
var wantedExpressions = ['attentif','content','heureux','malicieux','surpris','curieux','sceptique','endormi','fache','triste','fier','concentre','clin','love','blase','panique'];
ok(B.EXPRESSIONS.length === 16, 'all 16 expressions');
ok(B.EXPRESSIONS.join(',') === wantedExpressions.join(','), 'Welly expression vocabulary');
ok(B.profiles.galet.length === 64, 'galet profile length');
for (var sample = 0; sample < 64; sample++) near(B.profiles.galet[sample], fixture.radii[sample], 1e-12, 'upstream radial sample ' + sample);
near(Math.max.apply(Math, B.profiles.galet), 1.02, 1e-12, 'galet normalization');
near(B.NOTIF_POP, 1.14, 0, 'notification pop');
near(B.BLINK_DURATION, 0.18, 0, 'scheduled blink');
near(B.FORCED_BLINK_DURATION, 0.2, 0, 'forced blink');

var engine = new B.Engine();
var frame = new B.Frame();
ok(engine.sample(0, frame) === frame, 'sample writes caller frame');
ok(frame.count === 64, 'sample writes 64 radii');
for (var si = 0; si < B.STATES.length; si++) {
  var state = B.STATES[si];
  engine.setState(state, 10 + si * 2);
  engine.sample(10 + si * 2 + (B.POSES[state] || 0.5), frame);
  for (var ri = 0; ri < 64; ri++) near(frame.radii[ri], fixture.radii[ri], 1e-12, state + ' keeps pebble sample ' + ri);
  ok(frame.eyeAlpha > 0, state + ' keeps the face visible');
}
var signatures = {};
for (var ei = 0; ei < wantedExpressions.length; ei++) {
  engine.setExpression(wantedExpressions[ei], 80 + ei);
  engine.sample(80 + ei + .5, frame);
  signatures[[frame.eyeW0,frame.eyeH0,frame.eyeTilt0,frame.eyeOpen0,frame.eyeW1,frame.eyeH1,frame.eyeTilt1,frame.eyeOpen1,frame.gazeYaw,frame.gazePitch,frame.gazeRoll,frame.eyeSplit].join(',')] = true;
}
ok(Object.keys(signatures).length === 16, 'all expressions have distinct measured faces');
engine.setState('orbit', 100); engine.setExpression('love', 100); engine.sample(100.8, frame);
ok(frame.expression === 'love' && frame.state === 'orbit', 'expression and kinetic state are independent');
engine.setState('orbit', 50); engine.sample(50.12, frame); var before = frame.radii[7];
engine.setState('wink', 50.12); engine.sample(50.12, frame);
near(frame.radii[7], before, 1e-12, 'interrupted transition freezes exact departure');
engine.setLook(12, -8, 1, 0, 0);
engine.sample(0.24, frame);
var yaw = frame.gazeYaw;
engine.setLook(NaN, 2, 1, 0, 0);
engine.sample(0.24, frame);
near(frame.gazeYaw, yaw, 1e-12, 'invalid gaze target rejected');
engine.setState('notify', 0);
engine.sample(0.9, frame);
near(frame.notifScale, 1, 1e-9, 'notify canonical settled size');
engine.setState('comet', 1);
engine.sample(2.15, frame);
near(frame.centerX, 0, 1e-12, 'comet center stationary');
ok(Math.abs(frame.centerY) <= 0.0350001, 'comet dot only has measured vertical wobble');
engine.setReducedMotion(true);
engine.setState('burst', 3);
engine.sample(3.45, frame);
ok(frame.dotCount === 0 && frame.arcCount === 0, 'reduce motion suppresses particles/rings');
ok(frame.eyeAlpha > 0, 'reduce motion preserves white eyes');

var ui = fs.readFileSync(path.join(root, 'web', 'welly.js'), 'utf8');
ok(ui.indexOf("fillStyle='#FFFFFF'") >= 0 || ui.indexOf("fillStyle = '#FFFFFF'") >= 0, 'web eyes render solid white');
ok(ui.indexOf("globalCompositeOperation='destination-out';c.globalAlpha=f.eyeAlpha") < 0, 'web eyes are not mask holes');

var html = fs.readFileSync(path.join(root, 'web', 'index.html'), 'utf8');
ok(html.indexOf('welly-bot.js') >= 0, 'engine script is loaded');
ok(html.indexOf('welly-bot.js') < html.indexOf('welly.js'), 'engine loads before Welly UI');
console.log('WellyBot web contract: OK');
