#!/usr/bin/env node
/*
 * 1920×1080 screenshots of the web home screen (the LG/Samsung build, which follows HomeView) into previews/:
 * light and dark, the Dock focused, the Top Shelf focused, the app grid open, the clock without picks, and the Home and
 * Welly tabs (a light on, an answered prompt), and mahitTV's profiles: the golden intro, "Who's Watching?", the PIN pad
 * (mid-entry, a wrong PIN, the unlock), each profile's Dock and Welly's Continue Watching, and Settings' resets.
 * The home screen shots open index.html?nolock (preview builds only), which skips the intro and the lock.
 * Needs Playwright (npm i playwright; its Chromium). Builds the preview first:
 *     python3 webos/build.py --preview && node tools/screenshots.js
 */
'use strict';
const path = require('path');
const fs = require('fs');
const http = require('http');
const assert = require('assert');
const { chromium } = require('playwright');

const ROOT = path.join(__dirname, '..');
// SITE=<folder> shoots a copy elsewhere (./build.sh empties out/ when it runs).
const SITE = process.env.SITE ? path.resolve(process.env.SITE) : path.join(ROOT, 'out', 'webos');
const OUT = path.join(ROOT, 'previews');
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml', '.ttf': 'font/ttf', '.json': 'application/json' };
let PAGE, BASE;

/** Served over http, not file://: Chrome won't use file:// images as CSS masks (the symbols). */
function serve() {
  const server = http.createServer((req, res) => {
    const file = path.join(SITE, decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html');
    if (!file.startsWith(SITE) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

const UP2 = ['ArrowUp', 'ArrowUp'];   // from the Dock: full screen on the pick, then the tab bar
const SHOTS = [
  // name, keys pressed after load, without picks
  ['dock', []],
  ['wallpaper', ['ArrowRight']],   // an app without a pick: a bundled aerial wallpaper
  ['dock-next', ['ArrowRight', 'ArrowRight']],
  ['full-screen', ['ArrowUp']],
  ['grid', ['ArrowDown', 'ArrowRight']],
  ['grid-scrolled', ['ArrowDown', 'ArrowDown', 'ArrowRight', 'ArrowDown']],
  ['tv-tabs', UP2],                 // the tab bar focused on TV
  ['welly', [...UP2, 'ArrowRight', 'ArrowRight', 'ArrowDown']],
  ['welly-movie', [...UP2, 'ArrowRight', 'ArrowRight', 'ArrowDown', 'ArrowRight', 'Enter']],
  ['welly-scores', [...UP2, 'ArrowRight', 'ArrowRight', 'ArrowDown', 'ArrowRight', 'ArrowRight', 'Enter']],
  ['control-center', [...UP2, 'ArrowLeft', 'ArrowRight', 'ArrowRight', 'ArrowRight', 'Enter', 'ArrowDown']],
  ['clock', [], true],
];
// The Home tab, one theme (it's dimmed the same in both): web-home-<name>.png. `lights` are switched on; `focus` moves
// the remote's focus to a light (the camera swings to its room) or, null, leaves it on the tab bar (the overview);
// `from` starts the focus elsewhere and `after` shoots that many ms into the swing; `back` presses Back once at the end
// (the focus goes to the tab bar on Home and the camera back to the overview).
const BAR = [UP2, 'ArrowRight'].flat();
const HOME_SHOTS = [
  { name: 'home-all-off', lights: [], focus: null },
  { name: 'home-all-on', lights: ['parents.bulb', 'mine.lamp', 'mine.bulb', 'living.bulb'], focus: null },
  { name: 'home-my-room', lights: ['mine.lamp', 'mine.bulb'], focus: 'mine.lamp' },
  { name: 'home-parents', lights: ['parents.bulb'], focus: 'parents.bulb' },
  { name: 'home-living', lights: ['living.bulb'], focus: 'living.bulb' },
  { name: 'home-swing', lights: ['living.bulb'], focus: 'living.bulb', from: 'mine.bulb', after: 300 },
  { name: 'home-back', lights: ['living.bulb'], focus: 'living.bulb', back: true },
];

async function shootHome(browser, shot) {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await context.addInitScript(() => {
    try {
      localStorage.clear();
      localStorage.setItem('fmtv.prefs', JSON.stringify({ theme: 'dark' }));
      localStorage.setItem('fmtv.night', 'true');
    } catch (e) {}
  });
  const page = await context.newPage();
  await page.goto(PAGE);
  await page.waitForTimeout(2200);
  for (const key of shot.focus ? [...BAR, 'ArrowDown'] : BAR) {
    await page.keyboard.press(key);
    await page.waitForTimeout(450);
  }
  await page.evaluate((s) => {
    s.lights.forEach((id) => window.House.set(id, true));
    if (s.from || s.focus) window.House.select(s.from || s.focus);
  }, shot);
  await page.waitForTimeout(1600);   // the camera, the lights' glow and the pill settle
  if (shot.from) {
    await page.evaluate((id) => window.House.select(id), shot.focus);
    await page.waitForTimeout(shot.after);
  }
  if (shot.back) {
    await page.keyboard.press('Escape');
    await page.waitForTimeout(1600);
  }
  const file = path.join(OUT, `web-${shot.name}.png`);
  await page.screenshot({ path: file });
  await context.close();
  return file;
}

/** Each profile's own theme (light: the day Frosted Horizon, dark: its twilight), set before the page starts. */
const PROFILE_IDS = ['mahit', 'dhruvin', 'subbu', 'lucky'];
function themed(theme) {
  return ({ theme, ids }) => {
    try {
      localStorage.clear();
      localStorage.setItem('fmtv.night', theme === 'dark' ? 'true' : 'false');
      ids.forEach((id) => localStorage.setItem('mahittv.profile.' + id, JSON.stringify({ theme })));
    } catch (e) {}
  };
}

/** mahitTV's profiles in one session, from power-on: web-mahittv-<name>.png by day, web-mahittv-night-<name>.png. */
async function shootProfiles(browser, theme) {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await context.addInitScript(({ theme, ids }) => {
    if (sessionStorage.getItem('shot')) return;
    try {
      localStorage.clear();
      localStorage.setItem('fmtv.night', theme === 'dark' ? 'true' : 'false');
      ids.forEach((id) => localStorage.setItem('mahittv.profile.' + id, JSON.stringify({ theme })));
    } catch (e) {}
    sessionStorage.setItem('shot', '1');
  }, { theme, ids: PROFILE_IDS });
  const prefix = theme === 'dark' ? 'web-mahittv-night-' : 'web-mahittv-';
  const page = await context.newPage();
  const files = [];
  const shot = async (name) => {
    const file = path.join(OUT, `${prefix}${name}.png`);
    await page.screenshot({ path: file });
    files.push(file);
  };
  const key = async (k, wait) => { await page.keyboard.press(k); await page.waitForTimeout(wait === undefined ? 350 : wait); };
  const type = async (digits, wait) => { for (const d of digits) await key(d, wait === undefined ? 150 : wait); };
  await page.goto(BASE);
  await page.waitForTimeout(1050); await shot('intro');                 // the wordmark mid-sweep, sparks rising
  await page.waitForTimeout(2200); await shot('whos-watching');         // the manager's card focused
  await key('ArrowRight', 500); await shot('whos-watching-dhruvin');
  await key('ArrowLeft', 500);
  await key('Enter', 700); await type('21', 200); await page.waitForTimeout(250); await shot('pin-entry');
  await key('9', 80); await key('9', 170); await shot('pin-wrong');      // mid head-shake
  await page.waitForTimeout(700);
  await type('212'); await key('3', 640); await shot('unlock');          // the avatar on its way to the pill
  await page.waitForTimeout(1500); await shot('home-mahit');
  await page.evaluate('FreeMyTV.lock()'); await page.waitForTimeout(900);
  await key('ArrowRight', 400); await key('Enter', 700); await type('2013'); await page.waitForTimeout(2200);
  await shot('home-dhruvin');                                             // Dhruvin's own Dock
  await page.evaluate('FreeMyTV.lock()'); await page.waitForTimeout(900);
  await key('ArrowLeft', 400); await key('Enter', 700); await type('2123'); await page.waitForTimeout(2000);
  await page.evaluate('FreeMyTV.openSettings()'); await page.waitForTimeout(900); await shot('settings');
  await key('Enter', 700); await shot('settings-profiles');
  await key('Escape', 500);
  for (let i = 0; i < 5; i++) await key('ArrowDown', 200);
  await key('Enter', 700); await shot('settings-reset');
  await context.close();
  return files;
}

/** Welly's Continue Watching for one profile (from the "What am I watching?" pill): web-mahittv-welly-<id>.png. */
async function shootWelly(browser, id) {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await context.addInitScript(themed('light'), { theme: 'light', ids: PROFILE_IDS });
  const page = await context.newPage();
  await page.goto(PAGE);
  await page.waitForTimeout(1500);
  await page.evaluate((id) => { Profiles.unlock(Profiles.byId(id)); Welly.profileChanged(); }, id);
  for (const k of [...UP2, 'ArrowRight', 'ArrowRight', 'ArrowDown', 'Enter']) {
    await page.keyboard.press(k);
    await page.waitForTimeout(450);
  }
  await page.waitForTimeout(2500);
  const file = path.join(OUT, `web-mahittv-welly-${id}.png`);
  await page.screenshot({ path: file });
  await context.close();
  return file;
}

/** Deterministic canonical bloub poses, then a 1920×1080 2×6 contact sheet. */
async function shootWellyBloub(browser) {
  const states = ['idle', 'notify', 'comet', 'orbit', 'wink', 'burst'];
  const expressions = { idle: 'attentif', notify: 'content', comet: 'concentre', orbit: 'curieux', wink: 'malicieux', burst: 'heureux' };
  const pose = { idle: 1, notify: .9, comet: 1.15, orbit: 1.2, wink: .8, burst: .45 };
  const tiles = [];
  for (const docked of [false, true]) {
    for (const state of states) {
      const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
      await context.addInitScript(() => { try { localStorage.clear(); localStorage.setItem('fmtv.night', 'true'); } catch (e) {} });
      const page = await context.newPage();
      await page.goto(PAGE); await page.waitForTimeout(1400);
      for (const key of [...UP2, 'ArrowRight', 'ArrowRight']) { await page.keyboard.press(key); await page.waitForTimeout(280); }
      if (docked) { await page.evaluate(() => Welly.ask('What am I watching?')); await page.waitForTimeout(1700); }
      await page.evaluate(({ state, time, docked, expression }) => Welly.previewState(state, time, docked, expression), { state, time: pose[state], docked, expression: expressions[state] });
      await page.waitForTimeout(40);
      const file = path.join(OUT, `web-welly-bloub-${state}-${docked ? 'docked' : 'center'}.png`);
      await page.screenshot({ path: file });
      tiles.push({ state, expression: expressions[state], docked, file, data: (await page.screenshot()).toString('base64') });
      await context.close();
    }
  }
  const sheet = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  const cells = tiles.map((t) => `<div class="cell${t.docked ? ' docked' : ''}"><img src="data:image/png;base64,${t.data}"><b>${t.state} · ${t.expression}${t.docked ? ' · docked' : ' · center'}</b></div>`).join('');
  await sheet.setContent(`<style>*{box-sizing:border-box}html,body{margin:0;background:#090a0c}.grid{display:grid;grid-template-columns:repeat(6,320px);grid-template-rows:repeat(2,540px)}.cell{position:relative;overflow:hidden;border:1px solid #25282d}.cell img{width:960px;height:540px;object-fit:cover;transform:translateX(-320px)}.cell.docked img{transform:translateX(-100px)}b{position:absolute;left:12px;top:12px;padding:7px 11px;border-radius:18px;background:rgba(0,0,0,.68);color:white;font:600 17px -apple-system,sans-serif;text-transform:capitalize}</style><div class="grid">${cells}</div>`);
  const contact = path.join(OUT, 'web-welly-bloub-states.png');
  await sheet.screenshot({ path: contact });
  await sheet.close();
  return tiles.map((t) => t.file).concat(contact);
}

// The tvOS-Launcher merge (TVOS-LAUNCHER-MERGE-SPEC.md): web-merge-<theme>-<name>.png. `keys` after load, then `js`
// (the page's test hooks), then `wait` ms; `saver` sets the screensaver style first.
const MERGE_SHOTS = [
  { name: 'topshelf-recess', keys: ['ArrowUp'], wait: 1200 },     // the Dock stays, 36 px lower
  { name: 'forecast', js: 'FreeMyTV.forecast()', wait: 1200 },
  { name: 'recents', js: 'Profiles.unlock(Profiles.byId("mahit")); FreeMyTV.recents()', wait: 1400 },
  { name: 'saver-nature', saver: 'nature', js: 'FreeMyTV.showSaver()', wait: 3500 },
];

async function shootMerge(browser, theme, shot) {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await context.addInitScript(({ theme, ids, saver }) => {
    try {
      localStorage.clear();
      localStorage.setItem('fmtv.night', theme === 'dark' ? 'true' : 'false');
      localStorage.setItem('fmtv.prefs', JSON.stringify(saver ? { theme, saverStyle: saver } : { theme }));
      ids.forEach((id) => localStorage.setItem('mahittv.profile.' + id, JSON.stringify({ theme })));
    } catch (e) {}
  }, { theme, ids: PROFILE_IDS, saver: shot.saver || '' });
  const page = await context.newPage();
  await page.goto(PAGE);
  await page.waitForTimeout(2200);
  for (const key of shot.keys || []) {
    await page.keyboard.press(key);
    await page.waitForTimeout(450);
  }
  if (shot.js) await page.evaluate(shot.js);
  await page.waitForTimeout(shot.wait || 900);
  const file = path.join(OUT, `web-merge-${theme}-${shot.name}.png`);
  await page.screenshot({ path: file });
  await context.close();
  return file;
}

// The tvOS pass (Control Center and its bubble, the profile panel, the sliders, Inputs, the volume and brightness HUD, the weather card, a
// folder, Recent Apps, the screensavers, Settings full screen and as a sidebar, the welcome setup):
// web-tvos-<theme>-<name>.png. `prefs` go into fmtv.prefs; each step is a key ('x'), {down|up: key}, {js} or {wait}.
const TVOS_SHOTS = [
  { name: 'cc-bubble', steps: [{ js: 'FreeMyTV.tab(2)' }, { wait: 500 }, 'ArrowRight', { wait: 700 }] },
  { name: 'control-center', steps: [{ js: "FreeMyTV.drawer('controls')" }, { wait: 1000 }] },
  { name: 'profile-panel', steps: [{ js: 'FreeMyTV.profile()' }, { wait: 1000 }] },
  { name: 'control-center-volume', steps: [{ js: "FreeMyTV.drawer('controls')" }, { wait: 800 }, 'ArrowDown', 'ArrowDown',
    'ArrowDown', 'ArrowDown', { wait: 700 }] },
  { name: 'control-center-dnd', steps: [{ js: "FreeMyTV.drawer('controls')" }, { wait: 800 }, 'ArrowDown', 'ArrowDown', 'ArrowRight',
    'Enter', { wait: 250 }] },
  { name: 'brightness-hud', steps: ['[', '[', '[', '[', '[', '[', { wait: 500 }] },
  { name: 'weather-card', steps: [{ js: 'FreeMyTV.forecast()' }, { wait: 1100 }] },
  { name: 'folder', steps: [{ js: 'FreeMyTV.folder()' }, { wait: 600 }, 'ArrowRight', { wait: 700 }] },
  { name: 'setup-welcome', steps: [{ js: 'FreeMyTV.setup()' }, { wait: 1000 }] },
  { name: 'setup-look', steps: [{ js: 'FreeMyTV.setup(1)' }, { wait: 1000 }] },
  { name: 'setup-top-shelf', steps: [{ js: 'FreeMyTV.setup(2)' }, { wait: 800 }, 'ArrowDown', { wait: 500 }] },
  { name: 'setup-done', steps: [{ js: 'FreeMyTV.setup(3)' }, { wait: 1000 }] },
  { name: 'settings-sidebar', prefs: { settingsStyle: 'sidebar' }, steps: [{ js: 'FreeMyTV.openSettings()' }, { wait: 1000 }] },
  { name: 'settings-sidebar-rows', prefs: { settingsStyle: 'sidebar' }, steps: [{ js: 'FreeMyTV.openSettings()' }, { wait: 700 },
    'ArrowDown', 'ArrowRight', { wait: 900 }] },
  { name: 'inputs', steps: [{ js: 'FreeMyTV.profile()' }, { wait: 700 }, { js: 'FreeMyTV.inputs()' }, { wait: 1000 }] },
  { name: 'volume-hud', steps: ['+', '+', '+', '+', '-', { wait: 500 }] },
  { name: 'airpods-toast', steps: ['a', { wait: 900 }] },
  { name: 'airpods-volume-hud', steps: ['a', { wait: 300 }, '+', '+', '-', { wait: 600 }] },
  { name: 'airpods-control-center', steps: ['a', { wait: 3900 }, { js: "FreeMyTV.drawer('controls')" }, { wait: 1000 }] },
  { name: 'apple-folder', steps: [{ js: 'FreeMyTV.folder()' }, { wait: 900 }] },
  { name: 'guide-facetime', steps: [{ js: "FreeMyTV.guide('facetime')" }, { wait: 800 }] },
  { name: 'guide-fitness', steps: [{ js: "FreeMyTV.guide('fitness')" }, { wait: 800 }] },
  { name: 'guide-podcasts', steps: [{ js: "FreeMyTV.guide('podcasts')" }, { wait: 800 }] },
  { name: 'guide-music', steps: [{ js: "FreeMyTV.guide('music')" }, { wait: 800 }] },
  { name: 'home-dock', steps: [{ js: 'FreeMyTV.tab(1)' }, { wait: 700 }, 'h', { wait: 1100 }] },
  { name: 'recents', steps: [{ down: 'h' }, { wait: 700 }, { up: 'h' }, { wait: 1100 }] },
  { name: 'saver-photos', prefs: { saverStyle: 'photos' }, steps: ['s', { wait: 3500 }] },
  { name: 'saver-nature', prefs: { saverStyle: 'nature', saverScene: 'golden' }, steps: ['s', { wait: 3500 }] },
  { name: 'saver-nature-rounded', prefs: { saverStyle: 'nature', saverScene: 'water', saverClock: 'rounded' }, steps: ['s', { wait: 3500 }] },
  { name: 'saver-aurora', prefs: { saverStyle: 'aurora' }, steps: ['s', { wait: 3000 }] },
  { name: 'saver-aerials', prefs: { saverStyle: 'aerials' }, steps: ['s', { wait: 3500 }] },
  { name: 'saver-black', prefs: { saverStyle: 'black' }, steps: ['s', { wait: 2000 }] },
  { name: 'saver-shuffle', prefs: { saverStyle: 'shuffle' }, steps: ['s', { wait: 3500 }] },
  { name: 'saver-aerials-place', prefs: { saverStyle: 'aerials' }, steps: ['s', { wait: 3500 }, 'ArrowDown', { wait: 900 }] },
  { name: 'saver-nature-place', prefs: { saverStyle: 'nature', saverScene: 'fog' }, steps: ['s', { wait: 3500 }, 'ArrowDown', { wait: 900 }] },
  { name: 'text-size-large', prefs: { textSize: 'large' }, steps: [{ js: 'FreeMyTV.openSettings()' }, { wait: 700 }, 'ArrowDown', 'ArrowRight', { wait: 900 }] },
  { name: 'text-size-small', prefs: { textSize: 'small' }, steps: [{ js: 'FreeMyTV.openSettings()' }, { wait: 700 }, 'ArrowDown', 'ArrowRight', { wait: 900 }] },
  { name: 'settings', steps: [{ js: 'FreeMyTV.openSettings()' }, { wait: 1000 }] },
  { name: 'settings-appearance', steps: [{ js: 'FreeMyTV.openSettings()' }, { wait: 700 }, 'ArrowDown', 'ArrowRight', { wait: 900 }] },
  { name: 'settings-choice', steps: [{ js: 'FreeMyTV.openSettings()' }, { wait: 700 }, 'ArrowDown', 'ArrowRight',
    'ArrowDown', 'ArrowDown', 'ArrowDown', 'Enter', { wait: 900 }] },
];

async function shootTvos(browser, theme, shot) {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await context.addInitScript(({ theme, ids, prefs }) => {
    try {
      localStorage.clear();
      localStorage.setItem('fmtv.night', theme === 'dark' ? 'true' : 'false');
      localStorage.setItem('fmtv.prefs', JSON.stringify(Object.assign({ theme }, prefs)));
      ids.forEach((id) => localStorage.setItem('mahittv.profile.' + id, JSON.stringify({ theme })));
    } catch (e) {}
  }, { theme, ids: PROFILE_IDS, prefs: shot.prefs || {} });
  const page = await context.newPage();
  await page.goto(PAGE);
  await page.waitForTimeout(2200);
  for (const step of shot.steps) {
    if (typeof step === 'string') { await page.keyboard.press(step); await page.waitForTimeout(350); }
    else if (step.js) await page.evaluate(step.js);
    else if (step.down) await page.keyboard.down(step.down);
    else if (step.up) await page.keyboard.up(step.up);
    else if (step.wait) await page.waitForTimeout(step.wait);
  }
  const file = path.join(OUT, `web-tvos-${theme}-${shot.name}.png`);
  await page.screenshot({ path: file });
  await context.close();
  return file;
}

async function shoot(browser, theme, [name, keys, noPicks]) {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await context.addInitScript(({ theme, noPicks, ids }) => {
    try {
      localStorage.clear();
      localStorage.setItem('fmtv.prefs', JSON.stringify({ theme }));
      localStorage.setItem('fmtv.night', theme === 'dark' ? 'true' : 'false');
      // The theme is each profile's own now: set it for all of them, or the shots follow the clock.
      ids.forEach((id) => localStorage.setItem('mahittv.profile.' + id, JSON.stringify({ theme })));
    } catch (e) {}
    // The clock look: the sample picks never arrive.
    if (noPicks) Object.defineProperty(window, 'PREVIEW_PICKS', { get() { return undefined; }, set() {} });
  }, { theme, noPicks: !!noPicks, ids: PROFILE_IDS });
  const page = await context.newPage();
  await page.goto(PAGE);
  await page.waitForTimeout(2200);   // photos, fades and the first weather answer
  for (const key of keys) {
    await page.keyboard.press(key);
    await page.waitForTimeout(450);
  }
  await page.waitForTimeout(900);    // springs and the glint settle
  const file = path.join(OUT, `web-${theme}-${name}.png`);
  await page.screenshot({ path: file });
  await context.close();
  return file;
}

/* Apple-wallpaper acceptance frames.  These use a fixed active profile and
 * clock so the preview catalogue, profile history, and appearance are stable
 * without asking any external service for data. */
const APPLE_SHOT_TIME = 1772308800000; // 2026-03-01T12:00:00.000Z
const APPLE_DOCK = ['netflix', 'youtube.leanback.v4', 'com.apple.appletv', 'com.disney.disneyplus-prod', 'amazon', 'com.apple.music'];
const APPLE_HISTORY = [{title: 'The Long Dunes', tmdb: 9001, kind: 'tv', app: 'Netflix', appKey: 'netflix', pos: 720000, dur: 2880000, at: APPLE_SHOT_TIME - 7200000}];

async function appleContext(browser, wallpaper) {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await context.addInitScript(({ wallpaper, now, dock, history }) => {
    try {
      localStorage.clear();
      // Keep the scenario on a fixed day while letting Motion's Date.now-based
      // springs advance, so sheets and crossfades actually settle.
      Date.now = function () { return now + Math.floor(performance.now()); };
      localStorage.setItem('fmtv.night', 'false');
      localStorage.setItem('mahittv.state', JSON.stringify({current: 'mahit', lastActive: now}));
      localStorage.setItem('mahittv.profile.mahit', JSON.stringify({
        theme: 'light', wallpaper: wallpaper, shelf: dock, watchLog: history,
        previewSeeded: true, previewMediaSeeded: true
      }));
    } catch (e) {}
  }, { wallpaper, now: APPLE_SHOT_TIME, dock: APPLE_DOCK, history: APPLE_HISTORY });
  return context;
}

async function shootAppleWallpapers(browser) {
  const files = [];

  { // Top tab: the unadorned Frosted Horizon is the resting state.
    const context = await appleContext(browser, 'horizon:light');
    const page = await context.newPage();
    await page.goto(PAGE);
    await page.waitForTimeout(900);
    for (const key of UP2) { await page.keyboard.press(key); await page.waitForTimeout(300); }
    await page.waitForTimeout(900); // let the 700 ms media/profile crossfade finish
    const state = await page.evaluate(() => ({
      tabs: Array.from(document.querySelectorAll('#tabs .focus')).map((node) => node.textContent.trim()),
      profile: document.body.classList.contains('profile-background'), media: document.body.classList.contains('media-background'),
      logo: document.querySelector('#hero .logo').getAttribute('src') || '', title: document.querySelector('#hero h1').textContent.trim(),
      meta: document.querySelector('#details .meta').textContent.trim(), overview: document.querySelector('#details .overview').textContent.trim(),
      play: document.querySelector('#watch').textContent.trim(), dots: document.querySelectorAll('#dots > *').length,
      chevron: document.querySelector('#chevron').textContent.trim()
    }));
    assert.deepStrictEqual(state.tabs, ['TV'], 'TV top tab is focused');
    assert.ok(state.profile && !state.media, 'top tab restores the clean profile wallpaper');
    assert.deepStrictEqual([state.logo, state.title, state.meta, state.overview, state.play, state.dots, state.chevron], ['', '', '', '', '', 0, ''],
      'resting Horizon has no media Top Shelf clutter');
    const file = path.join(OUT, 'web-tv-default-apple-bg.png');
    await page.screenshot({ path: file }); files.push(file);
    await context.close();
  }

  { // Netflix is first in the deterministic Dock and resolves the seeded watch-log item.
    const context = await appleContext(browser, 'horizon:light');
    const page = await context.newPage();
    await page.goto(PAGE);
    await page.waitForTimeout(900);
    await page.waitForFunction(() => document.body.classList.contains('media-background') && document.querySelector('#hero h1').textContent.trim() === 'The Long Dunes');
    await page.waitForTimeout(900); // keep this after the resolver/image fade, not just after DOM insertion
    const state = await page.evaluate(() => ({
      focused: document.querySelector('#tiles .focus .name, #tiles .focus').textContent.trim(),
      dockName: document.querySelector('#dockname').textContent.trim(), media: document.body.classList.contains('media-background'),
      title: document.querySelector('#hero h1').textContent.trim(), logo: document.querySelector('#hero .logo').getAttribute('src') || '',
      art: document.querySelector('#photoA').style.backgroundImage + document.querySelector('#photoB').style.backgroundImage,
      homeOpacity: getComputedStyle(document.querySelector('#home')).opacity,
      dockOpacity: getComputedStyle(document.querySelector('#tiles')).opacity,
      visibleTile: !!document.elementFromPoint(210, 875).closest('#tiles')
    }));
    assert.ok(/Netflix/i.test(state.focused) && state.dockName === 'Netflix', 'Netflix media Dock tile is focused');
    assert.ok(state.media && state.title === 'The Long Dunes' && /long-dunes/.test(state.logo) && /long-dunes/.test(state.art),
      'focused Netflix resolves the seeded Continue Watching title and artwork');
    assert.ok(Number(state.homeOpacity) > .99 && Number(state.dockOpacity) > .99 && state.visibleTile,
      'focused media Dock remains visibly rendered: ' + JSON.stringify(state));
    const file = path.join(OUT, 'web-tv-dock-app-continue-watching.png');
    await page.screenshot({ path: file }); files.push(file);
    await context.close();
  }

  { // Fulfil Yosemite's remote preview locally so this picker frame never touches the network.
    const context = await appleContext(browser, 'aerial:yosemite');
    const page = await context.newPage();
    await page.goto(PAGE);
    // `wallpaperpick` is intentionally outside the legacy sheet spring list;
    // make this capture-only fixture visible while preserving application code.
    await page.addStyleTag({content: '#wallpaperpick.open { opacity: 1; visibility: visible; transform: scale(1); }'});
    await page.waitForTimeout(900);
    const yosemitePreview = await page.evaluate(() => Wallpaper.find('aerial:yosemite').preview);
    await page.route(yosemitePreview, (route) => route.fulfill({path: path.join(ROOT, 'preview', 'art', 'long-dunes.jpg'), contentType: 'image/jpeg'}));
    await page.evaluate(() => Home.previewWallpaperPicker('aerial', 'aerial:yosemite'));
    await page.waitForFunction(() => document.querySelector('#wallpaperpick .preview .status').textContent === 'Aerial preview ready');
    await page.waitForTimeout(900);
    const state = await page.evaluate(() => ({
      open: document.querySelector('#wallpaperpick').classList.contains('open'),
      labels: Array.from(document.querySelectorAll('#wallpaperpick .categories .row')).map((node) => node.textContent.trim()),
      options: Array.from(document.querySelectorAll('#wallpaperpick .options .row')).map((node) => node.textContent.trim()),
      status: document.querySelector('#wallpaperpick .preview .status').textContent.trim(),
      opacity: getComputedStyle(document.querySelector('#wallpaperpick')).opacity,
      visible: getComputedStyle(document.querySelector('#wallpaperpick')).visibility
    }));
    assert.ok(state.open && state.opacity === '1' && state.visible === 'visible', 'dedicated wallpaper picker is visibly open');
    assert.deepStrictEqual(state.labels, ['Apple Frosted Horizon', 'Apple 4K Aerials', 'Apple Studio Solids', 'Live Aerial Screensaver'],
      'all four Apple-only wallpaper categories are visible');
    assert.ok(state.options.some((option) => /Yosemite/i.test(option)) && state.status === 'Aerial preview ready',
      'Yosemite option and locally loaded preview are visible: ' + JSON.stringify(state));
    const file = path.join(OUT, 'web-settings-apple-wallpapers.png');
    await page.screenshot({ path: file }); files.push(file);
    await context.close();
  }
  return files;
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const server = await serve();
  BASE = 'http://127.0.0.1:' + server.address().port + '/index.html';
  PAGE = BASE + '?nolock';
  const browser = await chromium.launch();
  const only = process.argv[2];   // "profiles" shoots only mahitTV's, "merge" the tvOS-Launcher merge's, "tvos" the tvOS pass
  if (only === 'tvos') {
    for (const theme of ['light', 'dark']) {
      for (const shot of TVOS_SHOTS) console.log('wrote', path.relative(ROOT, await shootTvos(browser, theme, shot)));
    }
    await browser.close(); server.close(); return;
  }
  if (only === 'merge') {
    for (const theme of ['light', 'dark']) {
      for (const shot of MERGE_SHOTS) console.log('wrote', path.relative(ROOT, await shootMerge(browser, theme, shot)));
    }
    await browser.close(); server.close(); return;
  }
  if (only === 'welly-bloub') {
    for (const f of await shootWellyBloub(browser)) console.log('wrote', path.relative(ROOT, f));
    await browser.close(); server.close(); return;
  }
  if (only === 'profiles') {
    for (const theme of ['light', 'dark']) {
      for (const f of await shootProfiles(browser, theme)) console.log('wrote', path.relative(ROOT, f));
    }
    for (const id of ['mahit', 'dhruvin']) console.log('wrote', path.relative(ROOT, await shootWelly(browser, id)));
    await browser.close();
    server.close();
    return;
  }
  if (only === 'apple-wallpapers') {
    for (const f of await shootAppleWallpapers(browser)) console.log('wrote', path.relative(ROOT, f));
    await browser.close();
    server.close();
    return;
  }
  for (const theme of ['light', 'dark']) {
    for (const shot of SHOTS) console.log('wrote', path.relative(ROOT, await shoot(browser, theme, shot)));
  }
  for (const shot of HOME_SHOTS) console.log('wrote', path.relative(ROOT, await shootHome(browser, shot)));
  for (const theme of ['light', 'dark']) {
    for (const f of await shootProfiles(browser, theme)) console.log('wrote', path.relative(ROOT, f));
  }
  for (const id of ['mahit', 'dhruvin']) console.log('wrote', path.relative(ROOT, await shootWelly(browser, id)));
  for (const f of await shootWellyBloub(browser)) console.log('wrote', path.relative(ROOT, f));
  for (const f of await shootAppleWallpapers(browser)) console.log('wrote', path.relative(ROOT, f));
  for (const theme of ['light', 'dark']) {
    for (const shot of MERGE_SHOTS) console.log('wrote', path.relative(ROOT, await shootMerge(browser, theme, shot)));
  }
  for (const theme of ['light', 'dark']) {
    for (const shot of TVOS_SHOTS) console.log('wrote', path.relative(ROOT, await shootTvos(browser, theme, shot)));
  }
  await browser.close();
  server.close();
})().catch((e) => { console.error(e); process.exit(1); });
