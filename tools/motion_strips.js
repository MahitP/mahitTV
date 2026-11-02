#!/usr/bin/env node
/*
 * Contact strips of the web app's animations (ANIMATIONS-SPEC.md), and their frame times.
 *
 * Each scenario gets the page to a starting point, does one thing (a focus hop, a dead end, a tab swap…) and captures
 * frames at fixed steps of animation time: the page runs on Playwright's fake clock, so every frame is at exactly
 * +40 ms (or the scenario's step) whatever the screenshot costs. The frames are tiled into previews/motion-<name>.png.
 * A second pass replays each action on the real clock, in a browser without vsync or a frame-rate limit (headless
 * Chromium otherwise throttles some pages to 30 fps), and records the time each animation frame costs.
 *
 *     python3 tools/preview_site.py /tmp/site && SITE=/tmp/site node tools/motion_strips.js [name …]
 * Needs Playwright (npm i playwright) and Python 3 with Pillow (to tile the frames).
 */
'use strict';
const path = require('path');
const fs = require('fs');
const os = require('os');
const http = require('http');
const { execFileSync } = require('child_process');
const { chromium } = require('playwright');

const ROOT = path.join(__dirname, '..');
const SITE = process.env.SITE ? path.resolve(process.env.SITE) : path.join(ROOT, 'out', 'webos');
const OUT = path.join(ROOT, 'previews');
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml', '.ttf': 'font/ttf', '.json': 'application/json' };

const BAR = ['ArrowUp', 'ArrowUp'];          // from the Dock: full screen on the pick, then the tab bar
// name: { setup keys, action (keys or {down|up|js}), frames, step ms, crop [x, y, w, h] or null }
// The startup (TVOS-LAUNCHER-MERGE-SPEC.md Part 2 §1): frames at fixed moments (ms after the page loads), from power-on.
const STARTUP_TIMES = [250, 650, 1150, 1450, 2000, 2650];
const SCENARIOS = {
  'startup-day': { startup: true, theme: 'light', times: STARTUP_TIMES, crop: null },
  'startup-night': { startup: true, theme: 'dark', times: STARTUP_TIMES, crop: null },
  'focus-hop': { setup: [], action: ['ArrowRight'], frames: 10, step: 33, crop: [0, 640, 1920, 440] },
  'dead-end': { setup: [], action: ['ArrowLeft'], frames: 10, step: 33, crop: [0, 640, 1920, 440] },
  'press': { setup: ['ArrowRight'], action: [{ down: 'Enter' }], frames: 6, step: 33, crop: [0, 640, 1920, 440] },
  'grid-lift': { setup: [], action: ['ArrowDown'], frames: 12, step: 50, crop: null },
  'top-shelf': { setup: [], action: ['ArrowUp'], frames: 12, step: 50, crop: null },
  'tab-swap': { setup: BAR, action: ['ArrowRight'], frames: 12, step: 40, crop: null },
  'tab-indicator': { setup: BAR, action: ['ArrowRight', 'ArrowRight'], frames: 10, step: 33, crop: [700, 30, 520, 100] },
  'control-center': { setup: [...BAR, 'ArrowRight', 'ArrowRight', 'ArrowRight'], action: ['Enter'], frames: 12, step: 40, crop: null },
  'home-orbit': { setup: [...BAR, 'ArrowRight', 'ArrowDown', { js: 'House.select("mine.lamp")' }, { wait: 1500 }],
    action: [{ js: 'House.select("living.bulb")' }], frames: 14, step: 60, crop: null },
  'light-on': { setup: [...BAR, 'ArrowRight', 'ArrowDown', { js: 'House.select("mine.lamp")' }, { wait: 1500 }],
    action: ['Enter'], frames: 10, step: 50, crop: [560, 220, 900, 620] },
  'welly-reply': { setup: [...BAR, 'ArrowRight', 'ArrowRight', 'ArrowDown', 'ArrowRight'], action: ['Enter'], frames: 16, step: 120, crop: null },
};

function serve() {
  const server = http.createServer((req, res) => {
    const file = path.join(SITE, decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html');
    if (!file.startsWith(SITE) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

async function context(browser, theme) {
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await ctx.addInitScript((theme) => {
    try {
      localStorage.clear();
      localStorage.setItem('fmtv.prefs', JSON.stringify({ theme }));
      localStorage.setItem('fmtv.night', theme === 'dark' ? 'true' : 'false');
      // The theme is each profile's own: set it for every profile, or the frames follow the clock.
      ['mahit', 'dhruvin', 'subbu', 'lucky'].forEach((id) => localStorage.setItem('mahittv.profile.' + id, JSON.stringify({ theme })));
    } catch (e) {}
  }, theme || 'dark');
  return ctx;
}

/** The startup from the first frame: a screenshot at each of `sc.times` (ms) on the page's fake clock. */
async function startupStrip(browser, url, name, sc, dir) {
  const ctx = await context(browser, sc.theme);
  const page = await ctx.newPage();
  await page.clock.install();
  await page.goto(url);
  await page.clock.pauseAt((await page.evaluate(() => Date.now())) + 1);
  const files = [];
  let at = 0;
  for (let i = 0; i < sc.times.length; i++) {
    await page.clock.runFor(sc.times[i] - at);
    at = sc.times[i];
    const file = path.join(dir, `${name}-${String(i).padStart(2, '0')}.png`);
    await page.screenshot({ path: file });
    files.push(file);
  }
  await ctx.close();
  return files;
}

async function step(page, s, fake) {
  const wait = (ms) => (fake ? page.clock.runFor(ms) : page.waitForTimeout(ms));
  if (typeof s === 'string') { await page.keyboard.press(s); await wait(450); }
  else if (s.js) { await page.evaluate(s.js); }
  else if (s.wait) { await wait(s.wait); }
  else if (s.down) { await page.keyboard.down(s.down); }
  else if (s.up) { await page.keyboard.up(s.up); }
}

async function act(page, s) {
  if (typeof s === 'string') await page.keyboard.press(s);
  else if (s.js) await page.evaluate(s.js);
  else if (s.down) await page.keyboard.down(s.down);
  else if (s.up) await page.keyboard.up(s.up);
}

async function strip(browser, url, name, sc, dir) {
  if (sc.startup) return startupStrip(browser, url, name, sc, dir);
  const ctx = await context(browser);
  const page = await ctx.newPage();
  await page.clock.install();
  await page.goto(url);
  await page.clock.runFor(2500);
  for (const s of sc.setup) await step(page, s, true);
  await page.clock.runFor(1200);
  // From here time only moves when we say so (screenshots take real time; the page mustn't see it).
  await page.clock.pauseAt((await page.evaluate(() => Date.now())) + 20);
  const files = [];
  for (const s of sc.action) await act(page, s);
  for (let i = 0; i < sc.frames; i++) {
    const file = path.join(dir, `${name}-${String(i).padStart(2, '0')}.png`);
    const clip = sc.crop ? { x: sc.crop[0], y: sc.crop[1], width: sc.crop[2], height: sc.crop[3] } : undefined;
    await page.screenshot({ path: file, clip });
    files.push(file);
    await page.clock.runFor(sc.step);
  }
  await ctx.close();
  return files;
}

async function timing(browser, url, sc) {
  const ctx = await context(browser, sc.theme);
  const page = await ctx.newPage();
  if (sc.startup) {
    // The whole startup, from load: every animation frame's cost for 3 s.
    await page.addInitScript(() => {
      window.__frames = [];
      let last = 0;
      (function tick(now) {
        if (last) window.__frames.push(now - last);
        last = now;
        if (window.__frames.length < 400) requestAnimationFrame(tick);
      })(0);
    });
    await page.goto(url);
    await page.waitForTimeout(3200);
    const frames = (await page.evaluate(() => window.__frames)).slice(2).sort((a, b) => a - b);
    await ctx.close();
    const p = (q) => frames[Math.min(frames.length - 1, Math.floor(q * frames.length))];
    return { n: frames.length, median: p(0.5), p95: p(0.95), max: frames[frames.length - 1], over: frames.filter((f) => f > 16.7).length };
  }
  await page.goto(url);
  await page.waitForTimeout(2500);
  for (const s of sc.setup) await step(page, s, false);
  await page.waitForTimeout(1200);
  await page.evaluate(() => {
    window.__frames = [];
    let last = performance.now();
    const until = last + 1500;
    (function tick(now) {
      window.__frames.push(now - last);
      last = now;
      if (now < until) requestAnimationFrame(tick);
    })(last);
  });
  for (const s of sc.action) await act(page, s);
  await page.waitForTimeout(1700);
  const frames = (await page.evaluate(() => window.__frames)).slice(1);
  await ctx.close();
  frames.sort((a, b) => a - b);
  const p = (q) => frames[Math.min(frames.length - 1, Math.floor(q * frames.length))];
  // Without vsync the interval between frames is what a frame costs (script, style, layout, paint).
  return { n: frames.length, median: p(0.5), p95: p(0.95), max: frames[frames.length - 1],
    over: frames.filter((f) => f > 16.7).length };
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const names = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(SCENARIOS);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fmtv-frames-'));
  const server = await serve();
  const base = 'http://127.0.0.1:' + server.address().port + '/index.html';
  const url = base + '?nolock';   // the home screen (preview builds skip the startup and the lock with ?nolock)
  const browser = await chromium.launch();
  // GPU rendering, as on a TV (headless Chromium otherwise rasterises in software, SwiftShader, ~7x slower here).
  const unthrottled = await chromium.launch({ args: ['--disable-frame-rate-limit', '--disable-gpu-vsync',
    '--use-angle=metal', '--enable-gpu-rasterization', '--ignore-gpu-blocklist'] });
  const report = {};
  for (const name of names) {
    const sc = SCENARIOS[name];
    if (!sc) { console.error('no scenario', name); continue; }
    const at = sc.startup ? base : url;
    const files = await strip(browser, at, name, sc, dir);
    const out = path.join(OUT, `motion-${name}.png`);
    execFileSync('python3', ['-c', TILE, out, sc.times ? sc.times.join(',') : String(sc.step), ...files]);
    report[name] = await timing(unthrottled, at, sc);
    const t = report[name];
    console.log(`wrote ${path.relative(ROOT, out)}  frames ${t.n}, median ${t.median.toFixed(1)} ms, ` +
      `p95 ${t.p95.toFixed(1)} ms, max ${t.max.toFixed(1)} ms, over 16.7 ms: ${t.over}`);
  }
  fs.writeFileSync(path.join(OUT, 'motion-timing.json'), JSON.stringify(report, null, 1));
  await browser.close();
  await unthrottled.close();
  server.close();
})().catch((e) => { console.error(e); process.exit(1); });

// Tiles the frames into a contact sheet: up to 4 per row, each labelled with its time.
const TILE = `
import sys
from PIL import Image, ImageDraw, ImageFont
out, step, files = sys.argv[1], sys.argv[2], sys.argv[3:]
labels = ['+%s ms' % t for t in step.split(',')] if ',' in step else ['+%d ms' % (i * int(step)) for i in range(len(files))]
frames = [Image.open(f).convert('RGB') for f in files]
w, h = frames[0].size
scale = min(1.0, 640 / w)
fw, fh = int(w * scale), int(h * scale)
cols = min(4, len(frames))
rows = (len(frames) + cols - 1) // cols
pad, label = 12, 30
sheet = Image.new('RGB', (cols * (fw + pad) + pad, rows * (fh + label + pad) + pad), (18, 18, 20))
draw = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype('/System/Library/Fonts/SFNS.ttf', 20)
except Exception:
    font = ImageFont.load_default()
for i, f in enumerate(frames):
    x, y = pad + (i % cols) * (fw + pad), pad + (i // cols) * (fh + label + pad)
    sheet.paste(f.resize((fw, fh), Image.LANCZOS), (x, y + label))
    draw.text((x + 2, y + 4), labels[i], fill=(200, 200, 205), font=font)
sheet.save(out)
`;
