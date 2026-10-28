# Apple Wallpaper Engine and App-Driven Top Shelf Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each profile a clean Apple Frosted Horizon or official Apple wallpaper and reveal Continue Watching artwork only while that profile deliberately browses a media Dock app.

**Architecture:** Add small pure Android and ES5 wallpaper catalog/policy modules, then make the existing Home views consume their decisions. Keep media resolution in the existing recommendation data layers, render Horizon procedurally, and treat still/video Aerial delivery as a separate fail-soft source with a bounded Android cache.

**Tech Stack:** Java 8/Android Canvas + AGSL fallback, Android `MediaPlayer`/`TextureView`, ES5 JavaScript, CSS gradients, HTML5 muted video, JSON i18n catalogues, Playwright screenshots, zero-Gradle `javac`/`d8` build.

---

## File Structure

**Create**

- `app/src/io/freemytv/Wallpaper.java` — pure IDs, catalogue records, migration, palette selection, media-focus policy
- `app/src/io/freemytv/AerialCache.java` — allowlisted Apple downloads, atomic files, two-entry/1 GB LRU
- `app/src/io/freemytv/WallpaperVideoView.java` — muted lifecycle-aware `TextureView` playback surface
- `web/wallpaper.js` — ES5 mirror of the pure catalogue, migration, palette and focus policy
- `tools/WallpaperTest.java` — Android pure-policy contract
- `tools/wallpaper-web-test.js` — ES5 policy contract and Android/web fixture parity

**Modify**

- `app/src/io/freemytv/Prefs.java` — per-profile stable wallpaper ID and legacy migration
- `app/src/io/freemytv/Recs.java` — resolve newest matching WatchLog entry through the existing details/cache path
- `app/src/io/freemytv/Shaders.java` — exact Light/Dark Horizon AGSL
- `app/src/io/freemytv/Scene.java` — switch aurora/Horizon and cross-fade background sources
- `app/src/io/freemytv/HomeActivity.java` — place decorative video behind `HomeView`
- `app/src/io/freemytv/HomeView.java` — focus gate, generation tokens, hero suppression, wallpaper/video requests
- `app/src/io/freemytv/SettingsActivity.java` — dedicated two-pane wallpaper picker
- `web/index.html` — load `wallpaper.js` and add the non-interactive video layer/picker container
- `web/data.js` — ID migration and WatchLog-to-pick resolver
- `web/home.js` — focus gate, generation tokens, picker, cross-fade/video lifetime
- `web/home.css` — exact Horizon mesh, 700 ms transition, picker layout
- `preview/preview.js` — deterministic history and wallpaper fixtures
- `tools/check.sh` — run the two pure wallpaper contracts before packaging
- `tools/screenshots.js` — three deterministic Section 2 captures
- `app/assets/i18n/*.json` — key-parity translations in all 26 catalogues
- `TODO.md` — mark Section 2 complete only after every verification succeeds

## Fixed Catalogue Records

Use these stable IDs and exact colors everywhere:

```text
horizon:auto  horizon:light  horizon:dark
studio:black=#000000
studio:space-gray=#1C1C1E
studio:midnight-blue=#121A2A
studio:deep-forest=#11221C
studio:teal=#1B3B3A
studio:warm-stone=#8E8E93
studio:silver-mist=#D1D5DB
live:aerial
```

Use these six Apple catalogue choices. `video` is the Apple 1080p SDR H.264 stream; `preview` is the Apple-hosted representative frame. Sonoma is an Apple macOS Aerial excluded from tvOS and has no legacy H.264 stream, so its still is valid but `video` is empty.

```text
aerial:yosemite
  asset=4109D42A-D717-46A7-A9A2-FE53A82B25C0
  title=Yosemite Valley
  video=https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/Y011_C001_SDR_2K_AVC.mov
  preview=https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/Y011_C001_900x580.png

aerial:iceland
  asset=8590D0C5-E344-4FAC-A39A-FD7BC652AEDA
  title=Iceland Coast
  video=https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/I003_C004_SDR_2K_AVC.mov
  preview=https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/I003_C004_900x580.png

aerial:north-america-aurora
  asset=737E9E24-49BE-4104-9B72-F352DE1AD2BF
  title=North America Aurora
  video=https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_GMT314_139M_170NC_NORTH_AMERICA_AURORA__COMP_v22_SDR_20181206_v12CC_SDR_2K_AVC.mov
  preview=https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/GMT314_139M_900x580.png

aerial:palau-coral
  asset=F07CC61B-30FC-4614-BDAD-3240B61F6793
  title=Palau Coral Colors
  video=https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/PA_A004_C003_SDR_20190719_SDR_2K_AVC.mov
  preview=https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/PA_A004_C003_900x580.png

aerial:new-york
  asset=3BA0CFC7-E460-4B59-A817-B97F9EBB9B89
  title=New York from Above
  video=https://sylvan.apple.com/itunes-assets/Aerials116/v4/97/e9/06/97e90616-1227-c670-6118-bd5ae991b43b/comp_N008_C009_PS_v01_SDR_PS_20180925_SDR_2K_AVC.mov
  preview=https://sylvan.apple.com/itunes-assets/Aerials221/v4/86/53/43/8653438c-a104-77a0-ce9a-8a370d39795f/N008_C009_900x580.png

aerial:sonoma-coast
  asset=FE876489-CBD5-479B-A8F0-1B67F0741CEA
  title=Sonoma Horizon
  video=
  preview=https://sylvan.apple.com/itunes-assets/Aerials126/v4/51/ff/08/51ff0824-8da5-78f0-e218-9e61264965bb/W010_C003_F01@2x.png
```

Only `https`, host `sylvan.apple.com`, and the paths recorded above are accepted by the cache/player.

### Task 1: Build the Pure Cross-Platform Wallpaper Contract

**Files:**
- Create: `app/src/io/freemytv/Wallpaper.java`
- Create: `web/wallpaper.js`
- Create: `tools/WallpaperTest.java`
- Create: `tools/wallpaper-web-test.js`
- Modify: `web/index.html`
- Modify: `tools/check.sh`

- [ ] **Step 1: Write the failing Java contract test**

Create `tools/WallpaperTest.java` with assertions for every valid ID, all legacy migrations, exact palette values, media-focus zones, the six Aerial records, and URL rejection:

```java
package io.freemytv;

public final class WallpaperTest {
    private static void ok(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void eq(Object actual, Object expected, String message) {
        ok(expected.equals(actual), message + ": " + actual + " != " + expected);
    }
    public static void main(String[] args) {
        eq(Wallpaper.normalize(null), "horizon:auto", "null migration");
        eq(Wallpaper.normalize(""), "horizon:auto", "empty migration");
        eq(Wallpaper.normalize("coast.jpg"), "horizon:auto", "legacy migration");
        eq(Wallpaper.normalize("aerial:yosemite"), "aerial:yosemite", "known ID");
        eq(Wallpaper.normalize("studio:teal"), "studio:teal", "solid ID");
        eq(Wallpaper.normalize("bogus"), "horizon:auto", "unknown migration");
        ok(Wallpaper.AERIALS.length == 6, "six aerials");
        eq(Wallpaper.AERIALS[0].asset, "4109D42A-D717-46A7-A9A2-FE53A82B25C0", "Yosemite asset");
        eq(Wallpaper.AERIALS[5].video, "", "Sonoma still-only");
        ok(Wallpaper.allowed(Wallpaper.AERIALS[0].video), "Apple URL accepted");
        ok(!Wallpaper.allowed("http://sylvan.apple.com/file.mov"), "HTTP rejected");
        ok(!Wallpaper.allowed("https://example.com/file.mov"), "foreign host rejected");
        ok(Wallpaper.palette(false)[0] == 0xffb8c7d6, "light sky");
        ok(Wallpaper.palette(false)[1] == 0xfff5ebd6, "light glow");
        ok(Wallpaper.palette(false)[2] == 0xffdce6ef, "light center");
        ok(Wallpaper.palette(false)[3] == 0xff7e9bb5, "light marine");
        ok(Wallpaper.palette(true)[0] == 0xff0d131f, "dark sky");
        ok(Wallpaper.palette(true)[1] == 0xff1c2638, "dark horizon");
        ok(Wallpaper.palette(true)[3] == 0xff080b12, "dark abyss");
        ok(Wallpaper.media("shelf", true), "media Dock tile");
        ok(Wallpaper.media("hero", true), "media Top Shelf");
        ok(!Wallpaper.media("tabs", true), "tabs default");
        ok(!Wallpaper.media("apps", true), "grid default");
        ok(!Wallpaper.media("shelf", false), "utility default");
        System.out.println("Wallpaper Android contract: OK");
    }
}
```

- [ ] **Step 2: Run the Java contract and verify it fails**

Run:

```bash
rm_dir="$(mktemp -d)"
javac -d "$rm_dir" app/src/io/freemytv/Wallpaper.java tools/WallpaperTest.java
```

Expected: FAIL because `Wallpaper.java` does not exist.

- [ ] **Step 3: Implement the Java contract**

Create `Wallpaper.java` as a dependency-free package-private class. Define `Aerial`, `AERIALS`, the three Horizon IDs, seven solid IDs, `normalize(String)`, `find(String)`, `allowed(String)`, `palette(boolean)`, and `media(String, boolean)`. Use the fixed records above verbatim. `allowed` must parse with `java.net.URI`, require `https`, require exact host equality, and require the URL to equal one of the twelve non-empty fixed `video`/`preview` fields.

Use this exact policy body:

```java
static boolean media(String zone, boolean mediaApp) {
    return mediaApp && ("shelf".equals(zone) || "hero".equals(zone));
}

static int[] palette(boolean dark) {
    return dark
            ? new int[] {0xff0d131f, 0xff1c2638, 0xff151e2d, 0xff080b12}
            : new int[] {0xffb8c7d6, 0xfff5ebd6, 0xffdce6ef, 0xff7e9bb5};
}
```

- [ ] **Step 4: Run the Java contract and verify it passes**

Run the compile command from Step 2, then:

```bash
java -cp "$rm_dir" io.freemytv.WallpaperTest
```

Expected: `Wallpaper Android contract: OK`.

- [ ] **Step 5: Write the failing ES5 parity contract**

Create `tools/wallpaper-web-test.js`. Load `web/wallpaper.js` with `vm.runInNewContext`, expose `Wallpaper`, assert the same IDs/colors/policy, and emit a fixture line consumed by parity checking:

```javascript
'use strict';
var fs = require('fs'), vm = require('vm');
var box = {};
vm.runInNewContext(fs.readFileSync('web/wallpaper.js', 'utf8') + '\nthis.out = Wallpaper;', box);
var W = box.out;
function ok(v, m) { if (!v) throw new Error(m); }
ok(W.normalize('') === 'horizon:auto', 'empty migration');
ok(W.normalize('coast.jpg') === 'horizon:auto', 'legacy migration');
ok(W.aerials.length === 6, 'six aerials');
ok(W.aerials[0].asset === '4109D42A-D717-46A7-A9A2-FE53A82B25C0', 'Yosemite');
ok(W.aerials[5].video === '', 'Sonoma still-only');
ok(W.allowed(W.aerials[0].video), 'Apple URL');
ok(!W.allowed('https://example.com/file.mov'), 'foreign URL');
ok(W.palette(false).join(',') === '#B8C7D6,#F5EBD6,#DCE6EF,#7E9BB5', 'light palette');
ok(W.palette(true).join(',') === '#0D131F,#1C2638,#151E2D,#080B12', 'dark palette');
ok(W.media('shelf', true) && W.media('hero', true), 'media zones');
ok(!W.media('tabs', true) && !W.media('apps', true), 'default zones');
console.log('Wallpaper web contract: OK');
```

- [ ] **Step 6: Implement the ES5 mirror and script order**

Create `web/wallpaper.js` as an IIFE returning `{aerials, solids, normalize, find, allowed, palette, media}`. Use `var`, plain functions, loops, and `indexOf`; do not use `const`, `let`, arrow functions, template literals, `URL`, `Promise`, `Map`, or `Array.from`.

In `web/index.html`, load it after `profiles.js` and before `data.js`:

```html
<script src="profiles.js"></script>
<script src="wallpaper.js"></script>
<script src="data.js"></script>
```

- [ ] **Step 7: Run ES5 parity and syntax checks**

Run:

```bash
node tools/wallpaper-web-test.js
npx --yes acorn@8.14.0 --ecma5 --silent web/wallpaper.js
```

Expected: `Wallpaper web contract: OK`, then exit code 0.

- [ ] **Step 8: Wire the contracts into `tools/check.sh`**

Before the Android build, compile `Wallpaper.java` plus `WallpaperTest.java` in a `mktemp -d` directory and run it. Before web packaging, run `node tools/wallpaper-web-test.js`. Extend the existing EXIT trap to remove the temporary test class directory without touching project files.

- [ ] **Step 9: Commit the pure contract**

```bash
git add app/src/io/freemytv/Wallpaper.java web/wallpaper.js web/index.html tools/WallpaperTest.java tools/wallpaper-web-test.js tools/check.sh
git commit -m "feat: add Apple wallpaper catalog and policy"
```

### Task 2: Migrate Per-Profile Preferences and Select Horizon Mode

**Files:**
- Modify: `app/src/io/freemytv/Prefs.java`
- Modify: `web/data.js`
- Modify: `tools/WallpaperTest.java`
- Modify: `tools/wallpaper-web-test.js`

- [ ] **Step 1: Extend tests for write-back migration and sunset selection**

Add pure assertions that `Wallpaper.dark("horizon:light", true)` is false, `Wallpaper.dark("horizon:dark", false)` is true, and `Wallpaper.dark("horizon:auto", weatherNight)` equals `weatherNight`. Mirror them in JavaScript.

- [ ] **Step 2: Run contracts and verify the new methods fail**

Run the two Task 1 contract commands.

Expected: compile/runtime failure because `dark` is absent.

- [ ] **Step 3: Implement mode selection and preference migration**

Add to both pure modules:

```java
static boolean dark(String id, boolean weatherNight) {
    id = normalize(id);
    return "horizon:dark".equals(id) || ("horizon:auto".equals(id) && weatherNight);
}
```

Use the equivalent ES5 function.

Replace `Prefs.wallpaper()` with normalization plus one-time write-back to the active profile store:

```java
static String wallpaper() {
    String raw = pp().getString("wallpaper", "horizon:auto");
    String clean = Wallpaper.normalize(raw);
    if (!clean.equals(raw)) putOwn("wallpaper", clean);
    return clean;
}

static void setWallpaper(String id) {
    putOwn("wallpaper", Wallpaper.normalize(id));
}
```

Change the web `Prefs.wallpaper()` to normalize the selected profile's raw value. If migration changes it, call `Profiles.store().set('wallpaper', clean)` once and return `clean`.

- [ ] **Step 4: Run both contracts and `translate.py --check`**

Expected: both contracts pass; catalogues remain unchanged and translation parity still passes.

- [ ] **Step 5: Commit preference migration**

```bash
git add app/src/io/freemytv/Prefs.java web/data.js app/src/io/freemytv/Wallpaper.java web/wallpaper.js tools/WallpaperTest.java tools/wallpaper-web-test.js
git commit -m "feat: migrate profile wallpaper preferences"
```

### Task 3: Render the Exact Procedural Frosted Horizon

**Files:**
- Modify: `app/src/io/freemytv/Shaders.java`
- Modify: `app/src/io/freemytv/Scene.java`
- Modify: `web/home.css`
- Modify: `web/index.html`
- Modify: `tools/WallpaperTest.java`
- Modify: `tools/wallpaper-web-test.js`

- [ ] **Step 1: Add normalized geometry fixtures**

Define and test these shared centers/stops:

```text
base vertical: y 0.00 -> 1.00
warm horizon: center (-0.08, 0.69), radius 0.83
center veil: center (0.53, 0.48), radius 0.78
lower-right field: center (1.06, 1.04), radius 1.04
vignette edge strength: 0.12 light, 0.18 dark
```

Java exports the eight floats from `Wallpaper.HORIZON_GEOMETRY`; web exports the same values from `Wallpaper.geometry`. Assert exact array equality in both contract tests.

- [ ] **Step 2: Run both contracts and verify geometry is missing**

Expected: FAIL for `HORIZON_GEOMETRY`/`geometry`.

- [ ] **Step 3: Add `Shaders.HORIZON`**

Implement an AGSL shader with uniforms `res`, `layer`, `c0`, `c1`, `c2`, `c3`, and `dark`. Compute normalized `uv`, three radial weights using the fixed geometry, mix the four colors in the stated order, apply the fixed vignette, and finish with `(hash(xy) - 0.5) / 255.0` deterministic dither. The shader has no time uniform and no animated noise.

- [ ] **Step 4: Add Scene background mode without per-frame allocation**

Create the Horizon `RuntimeShader`, `RenderNode`, and `Paint` once in `Scene` construction. Add:

```java
void setHorizon(boolean dark)
void setAurora()
boolean isHorizon()
```

`setHorizon` updates only the four color uniforms and `dark`; `setSize` updates `res`/`layer`. `drawBackground` selects the pre-existing aurora node or Horizon node. Keep all arrays, paints, shaders, and nodes as fields.

- [ ] **Step 5: Implement the identical CSS mesh**

Add `#horizonDay` and `#horizonNight` inside `#sky`, before `#art`. Use four layered gradients with percentages derived directly from the normalized fixtures:

```css
#horizonDay {
  background:
    radial-gradient(83% 83% at -8% 69%, #F5EBD6 0%, rgba(245,235,214,0) 72%),
    radial-gradient(78% 78% at 53% 48%, #DCE6EF 0%, rgba(220,230,239,0) 74%),
    radial-gradient(104% 104% at 106% 104%, #7E9BB5 0%, rgba(126,155,181,0) 72%),
    linear-gradient(180deg, #B8C7D6 0%, #DCE6EF 56%, #7E9BB5 100%);
}
#horizonNight {
  background:
    radial-gradient(83% 83% at -8% 69%, #1C2638 0%, rgba(28,38,56,0) 72%),
    radial-gradient(78% 78% at 53% 48%, #151E2D 0%, rgba(21,30,45,0) 74%),
    radial-gradient(104% 104% at 106% 104%, #080B12 0%, rgba(8,11,18,0) 72%),
    linear-gradient(180deg, #0D131F 0%, #1C2638 56%, #080B12 100%);
}
```

Add a deterministic 1px dither overlay using the existing permitted CSS/asset mechanism; do not add animation or particles.

- [ ] **Step 6: Verify contracts and legacy CSS rules**

Run both contracts, Acorn, and the banned-CSS grep from `tools/check.sh`.

Expected: contracts pass and no CSS custom properties, Grid, or backdrop-filter are found.

- [ ] **Step 7: Commit Horizon rendering**

```bash
git add app/src/io/freemytv/Shaders.java app/src/io/freemytv/Scene.java web/home.css web/index.html app/src/io/freemytv/Wallpaper.java web/wallpaper.js tools/WallpaperTest.java tools/wallpaper-web-test.js
git commit -m "feat: render Apple Frosted Horizon"
```

### Task 4: Resolve Per-App Continue Watching Artwork

**Files:**
- Modify: `app/src/io/freemytv/Recs.java`
- Modify: `app/src/io/freemytv/WatchLog.java`
- Modify: `web/data.js`
- Modify: `preview/preview.js`
- Create: `tools/wallpaper-policy-test.js`

- [ ] **Step 1: Write failing resolver policy tests**

Create `tools/wallpaper-policy-test.js` with plain objects representing newest-first history and picks. Assert that the resolver chooses the newest entry matching the focused package/app key, skips entries without an ID, uses the first focused-app pick next, treats Apple Music as Now Playing-only, and returns null for Settings.

Use this fixture:

```javascript
var history = [
  {kind: 'movie', tmdb: 22, pkg: 'com.netflix.ninja', at: 300},
  {kind: 'tv', tmdb: 11, pkg: 'com.disney.disneyplus', at: 200}
];
var picks = [
  {kind: 'movie', id: 33, app: 'netflix', pkg: 'com.netflix.ninja'},
  {kind: 'tv', id: 44, app: 'disney', pkg: 'com.disney.disneyplus'}
];
```

- [ ] **Step 2: Run the test and verify failure**

Run `node tools/wallpaper-policy-test.js`.

Expected: FAIL because `Wallpaper.selectMedia` is absent.

- [ ] **Step 3: Add a pure selection method on both platforms**

Add `selectMedia(history, picks, pkg, appKey, nowPlaying)` to the web module and an equivalent package-private `Recs.MediaChoice` selection helper on Android. Selection order is newest matching valid history ID, active Apple Music Now Playing, first matching featured pick, then null. Package match wins; app-key match is the fallback for web platforms.

- [ ] **Step 4: Add Android asynchronous history resolution**

Expose:

```java
void resolveWatch(String pkg, long generation, WatchResolver callback)
```

in `Recs`. It reads `WatchLog.list(context)` newest-first, reuses a matching in-memory `Pick` by `kind:id`, otherwise creates a `Pick` from `kind`, `tmdbId`, `title`, `pkg`, calls the existing `fetchDetails`/backdrop pipeline on the existing executor, and returns on the main handler. The callback includes the generation so `HomeView` can reject stale results. A resolution error calls back with null; it never clears the recommendation cache.

- [ ] **Step 5: Add the ES5 asynchronous resolver**

Expose `Data.resolveWatch(app, generation, done)`. It reads `Profiles.log()` newest-first, reuses `Data.shelf()` where kind/id match, otherwise routes through the existing `details` function and calls `done(generation, pickOrNull)`. Keep XHR cancellation logical with generation tokens; do not introduce Promises.

- [ ] **Step 6: Seed deterministic preview history**

Ensure `preview/preview.js` has a Netflix WatchLog item whose TMDB ID exactly matches a preview pick and is newer than its other entries. Add deterministic active Apple Music artwork data without network calls.

- [ ] **Step 7: Run resolver and ES5 checks**

Run:

```bash
node tools/wallpaper-policy-test.js
npx --yes acorn@8.14.0 --ecma5 --silent web/data.js web/wallpaper.js preview/preview.js
```

Expected: `Wallpaper media policy: OK` and zero syntax failures.

- [ ] **Step 8: Commit media resolution**

```bash
git add app/src/io/freemytv/Recs.java app/src/io/freemytv/WatchLog.java web/data.js web/wallpaper.js preview/preview.js tools/wallpaper-policy-test.js
git commit -m "feat: resolve per-app Continue Watching art"
```

### Task 5: Apply the Focus Gate and 700 ms Cross-Fade on Web

**Files:**
- Modify: `web/index.html`
- Modify: `web/home.js`
- Modify: `web/home.css`
- Modify: `tools/wallpaper-policy-test.js`

- [ ] **Step 1: Extend policy tests for stale generations and hero visibility**

Assert that a response tagged generation 4 is ignored after the controller advances to 5, that hero visibility is false for `tabs`, `apps`, `settings`, and utility `shelf`, and true only for a resolved media `shelf`/`hero` state.

- [ ] **Step 2: Run the policy test and verify failure**

Expected: FAIL because controller generation helpers are absent.

- [ ] **Step 3: Replace implicit `appPicks` wallpaper behavior with explicit source state**

In `web/home.js`, add `backgroundGeneration`, `backgroundMode`, and `mediaPage`. Implement:

```javascript
function wantsMedia() {
  return Wallpaper.media(st.zone, mediaApp(shelf[st.shelf]));
}

function refreshBackground() {
  var generation = ++backgroundGeneration;
  if (!wantsMedia()) { showProfileWallpaper(); showMedia(null); return; }
  Data.resolveWatch(shelf[st.shelf], generation, function (g, pick) {
    if (g !== backgroundGeneration || !wantsMedia()) return;
    showMedia(pick || firstFeaturedForFocusedApp());
  });
}
```

Call `refreshBackground()` from focus changes, profile changes, recommendation updates, and settings changes. `showMedia(null)` clears logo, details, dots, and watch button before revealing the profile wallpaper. Preserve the resolved media context when `st.zone` changes from Shelf to Hero.

- [ ] **Step 4: Make image transitions exactly 700 ms and interruptible**

Keep two photo/canvas layers. On interruption, preserve the visually dominant layer as the new `from`, increment the source token, and load only into the inactive layer. Set opacity transitions to `700ms cubic-bezier(.2,.8,.2,1)`. If `Motion.instant`, disable the transition and swap in the same frame.

- [ ] **Step 5: Implement profile wallpaper sources**

`showProfileWallpaper()` selects:

- Horizon light/dark from `Wallpaper.dark(Prefs.wallpaper(), Data.isNight(Date.now()))`;
- a studio solid by exact catalogue color;
- an Aerial representative frame through `photo()`/`backdrop()`;
- live Aerial through the video controller in Task 6.

Any image error calls `showHorizon()` with the current Auto Sunset decision and does not display a toast.

- [ ] **Step 6: Verify focus behavior in Chromium**

Build the preview, serve it, and use Playwright assertions to check body mode and hero visibility after Top Tabs, Netflix Dock, Settings Dock, and app-grid focus.

Expected: only Netflix Dock/Top Shelf has `.media-background`; all other states have `.profile-background` and an empty hero.

- [ ] **Step 7: Commit web focus choreography**

```bash
git add web/index.html web/home.js web/home.css tools/wallpaper-policy-test.js
git commit -m "feat: gate web Top Shelf art by media focus"
```

### Task 6: Add Fail-Soft Aerial Still and Live Playback

**Files:**
- Create: `app/src/io/freemytv/AerialCache.java`
- Create: `app/src/io/freemytv/WallpaperVideoView.java`
- Modify: `app/src/io/freemytv/HomeActivity.java`
- Modify: `app/src/io/freemytv/HomeView.java`
- Modify: `web/index.html`
- Modify: `web/home.js`
- Modify: `web/home.css`
- Create: `tools/AerialCacheTest.java`

- [ ] **Step 1: Write the failing cache contract**

Test URL allowlisting, `.part` atomic naming, maximum two complete files, oldest-first eviction, and total byte eviction at `1_073_741_824L`. Use a temporary directory populated with small files and pass a test byte ceiling of 100 to avoid downloads.

- [ ] **Step 2: Run the cache test and verify failure**

Compile `Wallpaper.java`, missing `AerialCache.java`, and `AerialCacheTest.java` against `android.jar`.

Expected: FAIL because `AerialCache` is absent.

- [ ] **Step 3: Implement `AerialCache`**

Use a single-thread executor, `HttpURLConnection`, connect/read timeouts of 10/30 seconds, redirects disabled unless the redirected URI is also allowlisted, and a 16 KiB reusable worker buffer. Write `<asset>.part`, sync/close, validate nonzero size and content type beginning `video/` or `image/`, then rename atomically. Touch successful reads. After success, sort completed files by `lastModified`, preserve the requested asset, and delete oldest entries until count <= 2 and bytes <= 1 GB.

- [ ] **Step 4: Implement Android decorative video**

`WallpaperVideoView` owns one `MediaPlayer` and its `Surface`. Public methods are:

```java
void show(File source, Runnable failed)
void hide()
void release()
```

Always set volume to 0, looping true, screen-on false, and use center-crop transform. `hide` pauses and makes the surface invisible; `release` is called from activity destruction. No native controls or focusability are enabled.

- [ ] **Step 5: Layer video behind `HomeView`**

In `HomeActivity`, use a `FrameLayout` root containing `WallpaperVideoView` first and `HomeView` second. Pass a narrow `WallpaperVideo` interface into `HomeView`; do not let `HomeView` own activity lifecycle. A video failure callback returns the scene to Horizon.

- [ ] **Step 6: Implement web live playback**

Add `<video id="wallpaperVideo" muted loop playsinline tabindex="-1"></video>` before `#art`. The ES5 controller sets `muted`, `loop`, and `webkit-playsinline` properties, assigns only allowlisted URLs, listens for `error`, and calls `play()` inside `try`; if the return value has a `catch` function, attach a rejection fallback without referencing `Promise`. Pause, clear `src`, and call `load()` when hidden.

For `aerial:sonoma-coast`, use only its preview frame. For `live:aerial`, choose the first cached/available H.264 entry starting at a deterministic profile/day index; skip Sonoma. Any failure calls `showHorizon()`.

- [ ] **Step 7: Suspend decoding outside its visible state**

Verify Android pauses on tab/activity invisibility and web pauses when media art, Home, Welly, Settings, lock, intro, or screensaver covers the TV tab.

- [ ] **Step 8: Run cache, ES5, and build checks**

Expected: cache contract passes, Acorn passes, `./build.sh` succeeds.

- [ ] **Step 9: Commit Aerial delivery**

```bash
git add app/src/io/freemytv/AerialCache.java app/src/io/freemytv/WallpaperVideoView.java app/src/io/freemytv/HomeActivity.java app/src/io/freemytv/HomeView.java web/index.html web/home.js web/home.css tools/AerialCacheTest.java
git commit -m "feat: add official Apple Aerial backgrounds"
```

### Task 7: Apply Android Focus Policy and Hero Suppression

**Files:**
- Modify: `app/src/io/freemytv/HomeView.java`
- Modify: `app/src/io/freemytv/Scene.java`
- Modify: `tools/WallpaperTest.java`

- [ ] **Step 1: Add Android media-app and generation tests**

Test that Netflix, Disney+, Max, Prime Video, YouTube, and Apple TV packages are media; Settings is not; Apple Music is Now Playing-only; and a callback with an old generation cannot update selected media.

- [ ] **Step 2: Run the contract and verify failure**

Expected: missing media classification/generation API.

- [ ] **Step 3: Replace `aerial()` fallback with profile wallpaper requests**

Delete hourly bundled-aerial selection from `HomeView`. Add `backgroundGeneration`, `resolvedPick`, and `backgroundMode`. `updateBackground()` must first evaluate `Wallpaper.media(zoneName(), mediaApp(focusedApp))`; default state calls `showProfileWallpaper()` and clears the hero, media state resolves WatchLog then falls back to the focused app's first pick.

- [ ] **Step 4: Suppress hero allocation and drawing in default state**

Make `currentPick()` return `resolvedPick` only during a media state. `makeHero()` returns an empty `Hero` without date/clock copy in profile-wallpaper mode. Ensure draw code skips logo, title, metadata, synopsis, badges, dots, and Play. The top-right status pill remains unchanged.

- [ ] **Step 5: Use the existing 700 ms background mixer**

Change the fade duration constant/easing input to 700 ms. Keep the existing dominant-source interruption rule and `Spring.instant` snap. Horizon selection calls `scene.setHorizon(dark)`; media/still art calls the existing `Backdrop` path; live video clears opaque scene art while preserving glass readability.

- [ ] **Step 6: Verify zero-allocation drawing**

Search the changed `onDraw`/draw helpers for `new`, array creation, collection creation, `String.format`, and bitmap creation. Move every new object to initialization, state-change, or loader code. Run `./build.sh`.

- [ ] **Step 7: Commit Android focus choreography**

```bash
git add app/src/io/freemytv/HomeView.java app/src/io/freemytv/Scene.java app/src/io/freemytv/Wallpaper.java tools/WallpaperTest.java
git commit -m "feat: gate Android Top Shelf art by media focus"
```

### Task 8: Build the Dedicated Two-Pane Wallpaper Picker

**Files:**
- Modify: `app/src/io/freemytv/SettingsActivity.java`
- Modify: `web/index.html`
- Modify: `web/home.js`
- Modify: `web/home.css`
- Modify: `preview/preview.js`

- [ ] **Step 1: Add picker state tests to the pure policy contracts**

Assert four categories in order, three Horizon options, six Aerials, seven solids, one Live choice, stable selected-ID lookup, and category restoration when opening an existing selection.

- [ ] **Step 2: Run contracts and verify picker APIs fail**

Expected: missing `categoryOf`/`options` APIs.

- [ ] **Step 3: Implement catalogue picker helpers**

Add pure `categoryOf(id)` and ordered `options(category)` on both platforms. Category IDs are exactly `horizon`, `aerial`, `studio`, `live`.

- [ ] **Step 4: Implement Android picker screen**

Add a distinct `WALLPAPER_PICKER` screen state, `wallpaperCategory`, and `wallpaperOption`. OK on `R_WALLPAPER` opens it. Draw four category rows in the left pane and options plus a 16:9 preview in the right pane using preallocated paints/rects. Left/Right changes pane, Up/Down changes focused row, OK calls `Prefs.setWallpaper(id)` immediately, and Back returns to `S_APPEARANCE` on `R_WALLPAPER` without reverting.

The preview renders Horizon through the same normalized shader/colors, exact studio colors, or asynchronously loaded Apple preview images. Missing/loading Aerial previews render Horizon and the localized availability line.

- [ ] **Step 5: Implement the web picker**

Add a dedicated picker sheet and state zone. Reuse Settings row construction but render the category list and option list separately. Apply the same key behavior and immediate `Prefs.set('wallpaper', id)`, then call `refreshBackground()`. Preview failures show Horizon and the same localized line.

- [ ] **Step 6: Add deterministic preview entry point**

Expose a preview-only `Home.previewWallpaperPicker(category, option)` guarded by `window.PREVIEW_PICKS`. It opens the picker without remote-key timing and selects `aerial`/`yosemite` for the screenshot.

- [ ] **Step 7: Verify picker navigation manually and with Playwright**

Assert category/option focus, immediate persistence under the current profile, Back behavior, profile isolation, and fallback preview with network disabled.

- [ ] **Step 8: Commit picker behavior**

```bash
git add app/src/io/freemytv/SettingsActivity.java app/src/io/freemytv/Wallpaper.java web/index.html web/wallpaper.js web/home.js web/home.css preview/preview.js tools/WallpaperTest.java tools/wallpaper-web-test.js
git commit -m "feat: add Apple wallpaper picker"
```

### Task 9: Add All 26 Catalogue Translations

**Files:**
- Modify: `app/assets/i18n/en.json`
- Modify: `app/assets/i18n/ar.json`
- Modify: `app/assets/i18n/bg.json`
- Modify: `app/assets/i18n/cs.json`
- Modify: `app/assets/i18n/da.json`
- Modify: `app/assets/i18n/de.json`
- Modify: `app/assets/i18n/el.json`
- Modify: `app/assets/i18n/es.json`
- Modify: `app/assets/i18n/fi.json`
- Modify: `app/assets/i18n/fr.json`
- Modify: `app/assets/i18n/he.json`
- Modify: `app/assets/i18n/hi.json`
- Modify: `app/assets/i18n/hu.json`
- Modify: `app/assets/i18n/id.json`
- Modify: `app/assets/i18n/it.json`
- Modify: `app/assets/i18n/ja.json`
- Modify: `app/assets/i18n/ko.json`
- Modify: `app/assets/i18n/ms.json`
- Modify: `app/assets/i18n/nl.json`
- Modify: `app/assets/i18n/no.json`
- Modify: `app/assets/i18n/pl.json`
- Modify: `app/assets/i18n/pt.json`
- Modify: `app/assets/i18n/ro.json`
- Modify: `app/assets/i18n/ru.json`
- Modify: `app/assets/i18n/sv.json`
- Modify: `app/assets/i18n/tr.json`

- [ ] **Step 1: Add the English keys**

Add category/mode/color/availability keys used by the picker, including:

```text
Apple Frosted Horizon
Apple 4K Aerials
Apple Studio Solids
Live Aerial Screensaver
Auto Sunset
Yosemite
Iceland
North America Aurora
Palau Coral
New York
Sonoma Coast
Studio Black
Space Gray
Midnight Blue
Deep Forest Green
Teal
Warm Stone
Silver Mist
Downloading…
Unavailable — showing Frosted Horizon
OK selects immediately. Back keeps your choice.
```

- [ ] **Step 2: Run translation parity and verify failure**

Run `python3 tools/translate.py --check`.

Expected: FAIL listing the missing keys in the other 25 catalogues.

- [ ] **Step 3: Translate only the new keys in all companion catalogues**

Use native translations for category, mode, color, action, and status text. Preserve official place names and Apple product/category names. Keep placeholder and punctuation parity. Do not alter any existing key as part of the deferred localization sweep.

- [ ] **Step 4: Validate JSON and key parity**

Run:

```bash
python3 -m json.tool app/assets/i18n/en.json >/dev/null
python3 tools/translate.py --check
```

Expected: translation check passes for all 26 files.

- [ ] **Step 5: Commit catalogues**

```bash
git add app/assets/i18n/*.json
git commit -m "i18n: translate Apple wallpaper settings"
```

### Task 10: Capture Deterministic Previews and Finish the Roadmap Item

**Files:**
- Modify: `tools/screenshots.js`
- Modify: `preview/preview.js`
- Modify: `TODO.md`

- [ ] **Step 1: Add exact screenshot cases**

Add three Playwright captures at 1920x1080:

```javascript
{name: 'tv-default-apple-bg', setup: 'top-tabs'},
{name: 'tv-dock-app-continue-watching', setup: 'netflix-dock'},
{name: 'settings-apple-wallpapers', setup: 'wallpaper-picker'}
```

For the default shot, set `horizon:light`, focus the TV tab, wait for the 700 ms transition, and assert the hero/logo/details are hidden. For Continue Watching, focus the seeded Netflix Dock tile and assert the seeded WatchLog title/logo are visible. For Settings, call the preview-only picker entry point and assert all four category labels are rendered.

- [ ] **Step 2: Build and run all independent checks**

Run:

```bash
./build.sh
./tools/check.sh
```

Expected: APK builds and the final line is `All checks passed.`

- [ ] **Step 3: Build the preview and capture screenshots**

Run:

```bash
python3 webos/build.py --preview
node tools/screenshots.js
```

Expected files:

```text
previews/web-tv-default-apple-bg.png
previews/web-tv-dock-app-continue-watching.png
previews/web-settings-apple-wallpapers.png
```

- [ ] **Step 4: Inspect the three captures**

Open them with the local image viewer and verify:

- default: no Apple logo, movie artwork, title logo, metadata, synopsis, or Play button;
- Dock media: seeded profile/app Continue Watching artwork and title logo, with no stale prior art;
- Settings: exactly four Apple categories, correct selected state, and readable preview/fallback status.

On macOS run:

```bash
open previews/web-tv-default-apple-bg.png previews/web-tv-dock-app-continue-watching.png previews/web-settings-apple-wallpapers.png
```

- [ ] **Step 5: Mark Section 2 complete only after Steps 2–4 succeed**

Change the three top-level checkboxes in TODO Section 2 from `[ ]` to `[x]`. Do not check any later phase.

- [ ] **Step 6: Run final repository verification**

Run:

```bash
git diff --check
git status --short
git diff -- TODO.md
```

Confirm only intentional source, catalogue, test, and roadmap files are tracked; APK/build/preview artifacts remain ignored.

- [ ] **Step 7: Commit completion**

```bash
git add TODO.md tools/screenshots.js preview/preview.js
git commit -m "test: verify Apple wallpaper experience"
```

- [ ] **Step 8: Report evidence**

Report the two verification commands and their success, the final commit range, the three absolute preview paths, and any Aerial availability caveat observed during offline testing.

## Self-Review Record

- Spec coverage: default/hero rules, 700 ms interruption, Reduce Motion, per-profile IDs, Light/Dark/Auto, exact colors/geometry, six curated Aerials, seven solids, live playback, Apple-only validation, Android LRU, web fallback, Settings navigation, WatchLog resolution, stale responses, all 26 catalogues, builds, screenshots, and TODO completion each map to a task.
- Placeholder scan: no implementation step contains TBD, deferred implementation, or an unspecified error-handling instruction.
- Type consistency: `Wallpaper.normalize`, `Wallpaper.dark`, `Wallpaper.media`, `Wallpaper.allowed`, catalogue field names, generation tokens, `Data.resolveWatch`, and picker category IDs are consistent across tasks.
