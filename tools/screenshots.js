#!/usr/bin/env node
/*
 * 1920×1080 screenshots of the web home screen (the LG/Samsung build, which follows HomeView) into previews/:
 * light and dark, the Dock focused, the Top Shelf focused, the app grid open, the clock without picks, and the Home and
 * Welly tabs (a light on, an answered prompt).
 * Needs Playwright (npm i playwright; its Chromium). Builds the preview first:
 *     python3 webos/build.py --preview && node tools/screenshots.js
 */
'use strict';
const path = require('path');
const fs = require('fs');
const http = require('http');
const { chromium } = require('playwright');

const ROOT = path.join(__dirname, '..');
// SITE=<folder> shoots a copy elsewhere (./build.sh empties out/ when it runs).
const SITE = process.env.SITE ? path.resolve(process.env.SITE) : path.join(ROOT, 'out', 'webos');
const OUT = path.join(ROOT, 'previews');
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml', '.ttf': 'font/ttf', '.json': 'application/json' };
let PAGE;

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

async function shoot(browser, theme, [name, keys, noPicks]) {
  const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await context.addInitScript(({ theme, noPicks }) => {
    try {
      localStorage.clear();
      localStorage.setItem('fmtv.prefs', JSON.stringify({ theme }));
      localStorage.setItem('fmtv.night', theme === 'dark' ? 'true' : 'false');
    } catch (e) {}
    // The clock look: the sample picks never arrive.
    if (noPicks) Object.defineProperty(window, 'PREVIEW_PICKS', { get() { return undefined; }, set() {} });
  }, { theme, noPicks: !!noPicks });
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

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const server = await serve();
  PAGE = 'http://127.0.0.1:' + server.address().port + '/index.html';
  const browser = await chromium.launch();
  for (const theme of ['light', 'dark']) {
    for (const shot of SHOTS) console.log('wrote', path.relative(ROOT, await shoot(browser, theme, shot)));
  }
  for (const shot of HOME_SHOTS) console.log('wrote', path.relative(ROOT, await shootHome(browser, shot)));
  await browser.close();
  server.close();
})().catch((e) => { console.error(e); process.exit(1); });
