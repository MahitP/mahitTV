# mahitTV × tvOS-Launcher: merge spec

- **Part 2** (decided 2026-10-31, waiting for the go-ahead): the Liquid Glass & Prismatic Horizon startup, no black
  backgrounds anywhere, and the re-requested utilities.
- **Part 1** (below, implemented in f953dcb and 035afad): the merge itself.

Numbers are 1080p pixels (`k = w / 1920` scales them), seconds from the start of the sequence, and springs from
`Spring.java` / `web/motion.js` as {response s, damping}.

## Part 2.0 — Where we are (nothing here is redone)

| Requested again | State on `tvos-redesign` |
|---|---|
| 9 commits of `fix/android-wallpaper-picker` incl. 38cabe7 | Fast-forwarded (5736517); the branch and `.worktrees/apple-wallpaper` are gone |
| 8 "Free My TV" → "mahitTV" notices, 26 catalogues | f33da37 |
| Frosted Horizon on the intro, lock, PIN pad, Settings, Home, Welly | 6e25bb1 |
| tvOS-Launcher merge (Clean Up, Control Center cards, Recents, forecast, nature clock, double press, logos, glass) | f953dcb, audit fixes 035afad |
| `wip/tvos-launcher-merge` | Deleted (c0dafda, recoverable for a while) |
| Upstream | tvOS-Launcher is at **v2.0.3** (v2.0.2 + `AppUpdater`, `CustomMedia`). Phase 3 clones it to `/tmp` only if a file is needed, then removes it |
| Dock guardrails | In code and in tests: `DockRecessTest` (Android), `home-background-dom-test` (web). Focus never hides the Dock, tabs or pill. Top Shelf recesses the Dock 36 px / 0.75. Control Center keeps the pill and tabs |

## Section 1 — The "Liquid Glass & Prismatic Horizon" startup

It replaces the golden intro: the gold wordmark, gold sparks and C–E–G chord go away. The files are `Intro.java`, `Shaders.java`, `Click.java`, `LockView.java`, `web/intro.js`, `web/lock.js` and `web/lock.css`, plus a new `GlassText.java`. `MAHITTV-PROFILES-SPEC.md` §2 is updated to point here.

### 1.1 Timeline (2.80 s; the Horizon is never interrupted)

| Time (s) | What happens | Curve |
|---|---|---|
| 0.00 | The whole frame is studio midnight **#090D16** | — |
| 0.05 | The audio sub-swell starts (§1.6) | — |
| 0.08–0.65 | **Ignition.** A horizontal seam at **y = 0.44·H = 475 px** brightens to full in 0.10 s. An aperture opens up and down from the seam onto the Frosted Horizon (day palette, or twilight after sunset) until it covers the whole frame | Aperture: SPATIAL_SCROLL {0.46, 0.92} 0 → 1 from 0.08. The seam's brightness rises with smoothstep over 0.08–0.18 and falls with smoothstep over 0.30–0.65 |
| 0.40–1.10 | **The wordmark arrives.** "mahitTV" in glass, scale 1.10 → 1.00, defocus 24 px → 0 | TITLE_SETTLE {0.38, 0.88} from 0.40. Defocus = a cross-fade from a pre-blurred copy (§1.3) |
| 0.62–0.80 | The audio's glass chord peaks at 0.75 (§1.6) | — |
| 0.95–1.75 | **The caustic sweep.** A band at −28° crosses the letters left to right, with a 3-colour prism fringe along their edges | Position: smoothstep over 0.95–1.75 |
| 1.10–2.00 | **The ripple.** A soft ring spreads from the wordmark's centre across the Horizon | Radius: ease-out cubic 0 → 0.9·W. Alpha 0.16 → 0 |
| 2.10 | **Handover.** The lock appears under the intro with its Horizon at alpha 1, identical pixels at 1:1 (the lock's 1.04 zoom is dropped for the backdrop). From this moment the intro stops drawing its own Horizon | instant |
| 2.10–2.38 | The wordmark scales 1.00 → 0.94 and fades out | SHEET_CLOSE {0.34, 0.96}, or linear 280 ms |
| 2.10–2.46 | "Who's Watching?" comes up 24 px and fades in. Card *i* (0…4) starts at **2.10 + 0.03·i**: it rises from +48 px, scales 0.92 → 1 and fades in | Header: TITLE_SETTLE. Cards: CARD_ENTER {0.28, 0.82} each |
| ≈ 2.30 | The focused card (the current profile) grows to 1.18× | SNAPPY_FOCUS {0.28, 0.82} |
| 2.38 | The intro view is GONE (`onIntroDone`) | — |
| ≈ 2.80 | Everything has settled | — |

**Skip (OK or Back)** jumps straight to 2.10 on both platforms. Today Android fades over 0.5 s and the web over 0.24 s.

**Reduce motion** (`Spring.instant`, `Motion.instant`, `lite`): no seam, defocus, sweep or ripple. The Horizon and a still glass wordmark show for 0.7 s, then 2.10 runs with instant springs.

**"Startup animation" off:** the lock appears straight away on the Horizon on both platforms. The web stops showing a still wordmark (Q-B).

The `onIntroEnding` and `onIntroDone` listeners keep their names. `onIntroEnding` fires at 2.10 and is still posted off the frame, because the lock's set-up allocates.

### 1.2 Stage 1: the anamorphic ignition (all versions; AGSL not needed)

- **Base:** the midnight rect **#090D16**.
- **Horizon:** `Horizon.get(w, h, dark)`, already cached and shared with the lock, drawn through an aperture: a clip of `y0 − a … y0 + a` around `y0 = 0.44·H`.
  - The aperture half-height is `a = lerp(1 px, max(y0, H − y0) + feather, r)`, where `r` is the aperture spring.
  - **Feathered edges:** two `LinearGradient`s from midnight to transparent, built once at unit size and placed each frame with a reused `Matrix` (no allocation). Feather is `8 px + 0.10·H·r`.
- **Seam:** a pre-baked anamorphic bitmap, `W × 64` px made once in `onSizeChanged`.
  - Horizontal falloff: `exp(−|x − 0.5W| / 0.55W)`.
  - Vertical profile: Gaussian.
  - Colour: **#FFFFFF** core over a **#CFE8FF** halo.
  - Each frame it's drawn stretched vertically to `2·(1.5 px + 38 px·r)`, at alpha `seamIn · (1 − seamOut)`.
  - Peak alpha 0.90; it blends additively (`ADD` on Android, `'lighter'` on the web).
- **Web:** the same three layers in the intro's canvas.
  - Gradients are created once at the origin and placed with `setTransform`.
  - `.hz-day` / `.hz-night` sit under the canvas (`clearRect` keeps them visible).
  - The aperture is a canvas clip; the midnight is two `fillRect`s outside it.

### 1.3 Stage 2: the glass wordmark

**Type**
- Inter Variable, **opsz 32, wght 700**: the existing intro face, `Typeface.Builder` with `'opsz' 32, 'wght' 700`; on the web, `700 116px Inter`.
- **116 px**, tracking **−0.03 em**.
- Centred on (960, 540) by its cap height, with no emblem (Q-A).

**Android 13+: refractive glass through a new `Shaders.GLASS_WORD`**
- It's a copy of `GLASS_TEXT`. The nature clock's shader is not touched.
- The text-to-slope bitmaps move from `DreamView.makeGlass` into a shared `GlassText.java`, built once per size.
- **Uniforms:**

| Uniform | Value / role |
|---|---|
| `shader sky` | The Horizon `BitmapShader`, scaled 2× (it's half size) |
| `shader slope` | Height gradient in RG, height in B (box blurs 6k and 12k, as today) |
| `shader cover` | Sharp text alpha |
| `float2 origin` | Word top-left |
| `float refraction` | 10k px |
| `float fringe` | 1.5k px. Split along the normal: R at `+fringe`, G at 0, B at `−fringe` (the text's own refraction prism) |
| `half4 body` | By day **#4A5D70** at 0.38 (dark-slate inner tint, so the glass reads on the bright sky). At night **#FFFFFF** at 0.22 (frost) |
| `half4 rim` | Specular edge, **#FFFFFF**, 1.5k px wide, strongest toward the key light (−0.447, −0.894), as in `GLASS_TEXT` |
| `float2 sweepDir` | (cos −28°, sin −28°) = (0.883, −0.469) |
| `float sweepPos` | Band centre along `sweepDir`, from −0.3 to 1.3 of the word width |
| `float sweepWidth` | 0.30 of the word width; Gaussian profile |
| `half3 prismA` | **#7DD3FC** (cyan) |
| `half3 prismB` | **#FFFFFF** |
| `half3 prismC` | **#FEF3C7** (pearl) |
| `float prismPx` | 1.5k px |
| `float defocus` | 1 → 0; mixes toward a pre-blurred `cover` / `slope` pair |
| `float alpha` | Word alpha (stage 3) |

- **Sweep:** inside the band, the body brightens by up to +0.55 white. The rim is sampled three times, offset −1.5k / 0 / +1.5k px along `sweepDir`'s normal and tinted prismA/B/C, then added. Outside the band, no fringe.

**Android 8–12 (`Ui.LEGACY`), allocation-free Canvas layers in this order**
1. **Inner tint:** the text in **#4A5D70**, alpha 0.30 by day, with a 6k px shadow offset 0, 3k px. At night, #FFFFFF at 0.10. Both drawn from a pre-rendered bitmap.
2. **Body:** the text filled with a `BitmapShader` of the Horizon at 1.04× around the word's centre (refraction look), plus #FFFFFF at 0.22.
3. **Rim:** a pre-rendered 1.5k px stroke bitmap, white with a 0.90 → 0.25 gradient toward the bottom right.
4. **Sweep:** inside one `saveLayer`, three pre-tinted rim bitmaps (cyan, white, pearl) are drawn at −1.5k / 0 / +1.5k offsets. The −28° band is then applied with `DST_IN`: one unit gradient placed through a reused `Matrix`.
5. **Defocus:** the sharp word and a pre-blurred copy (blurred once when it's built: downscale ×4 and box blur) are cross-faded by `defocus`.

**Web:** the same five layers on the word canvas.
- The Horizon pattern comes from drawing the `.hz` colours into a 1-time offscreen canvas.
- `'source-in'` and `'destination-in'` for the band, `'lighter'` for the fringe.
- The pre-blurred copy is made once (downscale and upscale).
- No `filter` or `backdrop-filter`.

### 1.4 The ripple (1.10–2.00 s)

- **Android 13+:** `Shaders.HORIZON_RIPPLE`.
  - Uniforms: `shader sky`, `float2 center` = (960, 540)·k, `float radius`, `float width` = 90k, `float amp` = 6k px (fades with alpha).
  - It samples the Horizon displaced along the ring's normal, so it shows as a real refraction, plus +0.10 brightness on the ring.
  - It's drawn full screen only during these 0.9 s.
- **Android 8–12 and the web:** a brightness ring only. A pre-baked radial ring bitmap / one radial gradient at unit size is scaled per frame and drawn with `'screen'`, peak alpha 0.16.

### 1.5 Stage 3: the handover

- **Problem today:** at 1.9 s the intro fades out while the lock fades in at 1.04× with its own Horizon copy. About 12% of the home screen shows through at 0.1 s, and the two skies don't line up.
- **New:**
  - `LockView.show(fromIntro = true)` draws its backdrop at alpha 1 from its first frame, at 1:1 (the 1.04 zoom and fade now apply to the header and cards only).
  - Both views use the same `Horizon.get` bitmap, so the frame doesn't change.
  - From 2.10 the intro draws only the fading wordmark on transparent.
- **Lock entrance (new, both platforms):**
  - Header from +24 px.
  - Cards staggered 30 ms, from +48 px, scale 0.92 → 1, alpha 0 → 1, on CARD_ENTER. This is the `WellyView` stagger pattern: `enterAt[i]`, then the target flips to 1.
  - The focused card then grows on SNAPPY_FOCUS. On Android it no longer starts already grown.
- **Web:**
  - `Lock.show({from: 'intro'})` keeps `.bg` at opacity 1; only the content wrapper springs.
  - `#intro`'s `.hz` layers are removed at 2.10, so the identical `.hz` under the lock carries on.

### 1.6 Audio: the Cmaj9 glass resonance chord

It replaces `Click.chord()` C5–E5–G5–C6 and the web's 3-note chime.

| Voice | Notes (Hz) | Start | Envelope | Level |
|---|---|---|---|---|
| Sub-swell (sine) | C2 65.41, G2 98.00 | 0.05 s | 180 ms attack, then `exp(−t/0.55)` | 0.30 each |
| Glass bells | C4 261.63, G4 392.00, B4 493.88, D5 587.33, E5 659.25 | 0.62 + 0.018·n s (n = 0…4), so they peak at about 0.75 | 8 ms attack; 1.8 s exponential decay, τ = 0.45 s (−34 dB at 1.8 s) | 0.10 each |
| Detune chorus | Each bell twice, at ×2^(±1.5/1200) (0.999134 and 1.000867), so they beat slowly | — | — | Split 0.5 / 0.5 |
| Bell partials | 1.00 (1.0), 2.00 (0.22), 3.01 (0.07), 4.20 (0.03), slightly inharmonic, "glass" | — | — | — |

- **Master:** peak-normalised to −3 dBFS; a 60 ms fade at the end; 2.6 s long.
- **Android:**
  - `Click.chord()` writes **`chime-mahittv-glass.wav`**, 48 kHz mono 16-bit PCM. It's a new file name, so the cached old chord isn't reused.
  - It plays at volume 0.32 at t = 0 (the voices carry their own offsets).
  - Gated by `Prefs.sounds()` and `Prefs.intro()`, as today.
- **Web:**
  - Web Audio: 2 sub oscillators plus 5 notes × (2 detuned + 3 partials) = 27 oscillators, each with a gain envelope (`setValueAtTime`, `linearRampToValueAtTime`, `exponentialRampToValueAtTime`).
  - Skipped on `lite` and where `AudioContext` is missing.
  - The web has no "sounds" setting yet, so the chime follows "Startup animation" only.

### 1.7 Budgets and fallbacks

- **Allocation:** everything is built in `onSizeChanged` / `theme()` / `start()` (Android) or `play()` (web). Frames only set uniforms, matrices and alphas: no `new`, no gradient creation, no string work.
- **Android 13+:** at most two full-screen shader passes per frame (`HORIZON_RIPPLE` only during 1.10–2.00; the wordmark covers about 46% × 13% of the frame).
- **Android 8–12:** bitmaps and one `saveLayer` per frame during the sweep.
- **Web:** one 1920×1080 canvas and one word canvas (1100×220), gradients made once. A `lite` TV takes the reduce-motion path.
- **Day / night:** `Wallpaper.dark(Prefs.wallpaper(), isNight)`, as today. At night the body is frost (#FFFFFF 0.22) instead of slate, and the prism fringe stays.

## Section 2 — No black backgrounds: what's left, file by file

Already on the Horizon: the intro, lock and PIN pad, Settings (tint 0x57 by day, 0x26 at night), the Home/Welly tabs (slate 0.34 by day, 0.22 at night), the forecast, Recent Apps, the screensaver fallbacks and Control Center.

The **twilight Horizon** (#0D131F / #1C2638 / #151E2D / #080B12) is dark by design (Q-E).

| # | Where | Today | Change |
|---|---|---|---|
| B1 | `HomeActivity.java:42`, crash notice | Full-screen rgb(7, 11, 30) | Background = `Horizon.get(w, h, dark)` as a `BitmapDrawable`; text ink by day/night, as `LockView.theme()` does |
| B2 | `HomeView.java:4567-4578`, night gradient #1C1C1E → #0B0B0D | Shows while Live Aerial loads, after `aerialFailed` (stays for good) and during Top Shelf fades | Keep the Scene in Horizon mode: drop `scene.setAurora()` at 1463, 1482, 1487 and 1493. `aerialFailed()` calls `scene.setHorizon(Wallpaper.dark(...))`, so a Horizon is under every image |
| B3 | `HomeView.java:4549-4552`, black veil on image wallpapers | Home/Welly 0.45 by day, 0.55 at night; app grid 0.35 at night (My Photos, aerial stills) | `veil(ht, SLATE)`, ht = tone(0.34, 0.22), matching the web; app grid at night 0.25 |
| B4 | `Backdrop.java:104-108`, My Photos at night | Photo blended 76 % → 50 % toward #0B0B0D | ramp 0.35 → 0.20 toward #1C2638 (the twilight mid) |
| B5 | `HomeActivity.java:63` (null window background) and `HomeView.java:4528` | Black between the sky turning off and Live Aerial's first frame | Window background = the Horizon bitmap. `liveVideoVisible` is set only on `MEDIA_INFO_VIDEO_RENDERING_START` |
| B6 | `Screensaver.java:22` | The dream window is black until DreamView's first frame | `getWindow().setBackgroundDrawable(Horizon …)` after `setContentView` |
| B7 | `SettingsActivity.java:2250` (City), `:2270` (Remove photos) | System dialog, about 0.6 black dim, dark card | `setDimAmount(0.15f)`, and `Theme_DeviceDefault_Light_Dialog_Alert` by day |
| B8 | `res/values/styles.xml:4` window background | Flat #EBEBF0 at cold start | `drawable/horizon_window.xml`, a `<gradient>` #B8C7D6 → #DCE6EF → #7E9BB5, so the very first frame is the day Horizon |
| B9 | `index.html:16`, `home.js:2446`, html #0B0B0D at night | First paint and the letterbox around the stage | `#1C2638`, and the inline script adds `profile-background` with `night` |
| B10 | `home.css:184` `.night #sky .veil` rgba(11, 11, 13, 0.78) | Cold start, media page without art | `rgba(13, 19, 31, 0.22)`, and hidden without `.has-photo` |
| B11 | `home.css:71` `#sky .night` #1C1C1E → #0B0B0D | Shows during the 2 s Horizon cross-fades | Twilight base `linear-gradient(#0D131F, #1C2638 56%, #080B12)` |
| B12 | `home.css:642` `#saver {background:#000}` + nature saver | Black until the scene loads, or for good if it fails | Add the `.hz` layers in the nature saver (as Photos does); `#saver` transparent |
| B13 | `home.css:796` Reduce Transparency `.sheet:not(#apps)` 0.9 black | Settings and sheets | `rgba(22, 33, 45, 0.34)`; the boxes are already near-opaque |
| B14 | `home.css:584` `.sheet` slate 0.42 | Every sheet | 0.34 by day, `.night` 0.15 (as Android) |
| B15 | `home.css:633` wallpaper picker box rgba(34, 36, 44, 0.96), 1360 px wide | Picker | The forecast's glass: by day white 0.58 → 0.38 with dark ink; at night rgba(44, 52, 70, 0.58) → rgba(20, 26, 38, 0.48) |
| B16 | `home.css:731` / `home.js:617` black `.shade` branch (unreachable) | — | Removed; slate always |
| B17 | `Horizon.java` field order, `Shaders.HORIZON` night mid | The bitmap stacks its glows in the opposite order to the CSS and AGSL; the AGSL night mid-sky is c2 #151E2D, the others p1 #1C2638 | Paint c3 → c2 → c1 in the bitmap (as CSS/AGSL); the shader uses p1 for night. All three renderers then match |

Z-order is unchanged:
- **Android:** sky < home layers < overlays < lock < intro.
- **Web:** `#sky` 0 < `#home` 1 < `#dim` 2 < `#drawer` 3 < `.sheet` 4 < `#saver` 5 < `#lock` 6 < `#intro` 7.

Nothing new covers the Dock. `DockRecessTest` and the DOM test keep guarding it.

## Section 3 — Visuals: what's merged, what's left

| Technique | tvOS-Launcher | mahitTV now (f953dcb, 035afad) | Left to do |
|---|---|---|---|
| Liquid Glass | Canvas lens band + rim over a 192×108 blur | AGSL `GLASS`/`LIQUID` (13+); their lens + rim on 8–12 (cached by 8 px); adaptive tint | Startup wordmark glass (§1) |
| Refractive text | — | `GLASS_TEXT` for the nature clock | `GLASS_WORD` + `GlassText.java` (§1.3) |
| Dock focus | 1.13 lift, overshoot | 1.23× SNAPPY_FOCUS; background cross-fades 700 ms to the app's art; the Dock never hides | — (guarded by tests) |
| Top Shelf | Classic slides the grid away | Recess 36 px / 0.75; tabs and pill stay | — |
| App icons | Logo → adaptive ×1.24 → edge colours | Same, plus the 18 bundled logos as full-bleed cards | — |
| Launch | Window anim 0.65 → 1, 0.97 → 1 | Same, plus the home's return settle 1.04 → 1 | — |
| Lock entrance | — | One view fade, 1.04 → 1, no stagger | §1.5 stagger on both platforms |
| Horizon renderers | — | 3 renderers that differ slightly | B17 |
| Idle drift / tilt | Wallpaper ±60 px, tile tilt loop | Left out (Q9 NO) | — |

## Section 4 — Utilities: state and the re-requested items

| Item you asked for | State | Proposal |
|---|---|---|
| CleanYourTV, 341 models, manager PIN | Done: `Debloat.java` + `HomeMode` CLEAN/UNCLEAN, recorded so *Original* restores everything | — |
| ADB port scan 5555…5585 | Not done. Our `Adb.java` connects to 127.0.0.1:5555. Wireless debugging uses a random 30000+ port that a 5555–5585 scan wouldn't find anyway | **NO** (no benefit; it was part of the unsafe attempt) |
| Volume slider | Done (AudioManager, like the remote's keys) | — |
| Brightness slider | Declined before (Q5) | **NO**: needs WRITE_SETTINGS; TCL's picture brightness is the TV's own setting; CLAUDE.md "don't change picture settings" |
| Wi-Fi / Bluetooth live state | Done (read-only, off the UI thread) | — |
| 1-tap cache cleaner `clearCache()` | Declined before (Q6: close background apps only) | **NO**: `pm trim-caches` empties every app's cache, system apps' included |
| Recents: double-tap Home | Not done | **YES**: two Home presses within 400 ms (`HomeActivity.onNewIntent`) open Recent Apps; harmless |
| Recents: swipe-up `am force-stop` | Declined before (Q7); **now YES (you, Q-C)** | Android only (the LG/Samsung web app can't close apps). ↑ on a focused card lifts it 40 px with a "Close" label (`Ui.tr("Close")`); a second ↑ or OK within 3 s runs `am force-stop <pkg>` through a new fixed `HomeMode.forceStop(c, pkg)` on our ADB (RUN lock, mahitTV mode only). Never for system apps that weren't updated, Live TV, the setup app, any HOME app or our own package (the `Debloat.why` / `mayTurnOff` rules). The card animates away (SHEET_CLOSE); a failure shows a toast. It loses the app's state, as the label's hint says |
| Recents: live RAM indicator | Not done | **NO**: tvOS shows no RAM, and it would invite force-stopping |
| Forecast | Done (24 h + 7 days, from the weather card) | — |
| Nature clock | Done (5 scenes, Antonio, decoded at screen size) | — |
| Button mapper single / double | Done (double = 350 ms) | **YES**: 350 → 320 ms as you specify |
| Button mapper long press 550 ms | Not done | **YES** (Q-C): mapped brand buttons only. On down, post a 550 ms runnable; up before it = single/double. Third app choice in `remote-keys.txt`; `onKeyEvent` stays light |
| AirPlayGuard | Declined before (S12) | **NO**: only acts if a specific AirPlay app is installed, and it weakened the boot check |
| QR phone setup / `TmdbSetupServer` | Declined before (S9) | **NO**: the TMDB key stays only in `config.properties` (CLAUDE.md); a LAN server is exposure |

## Section 5 — Decisions (made 2026-10-31)

| # | Question | Decision |
|---|---|---|
| Q-A | Startup mark: pure "mahitTV" wordmark, or a squircle glass emblem before it | **Pure wordmark** (you) |
| Q-B | "Startup animation" off on the web: skip straight to the lock, as Android does | **YES** (you) |
| Q-C | Re-requested utilities to add: double-tap Home → Recents, long press 550 ms, force-stop from Recents, brightness slider | **Double-tap Home: YES. Long press: YES (+ double press 320 ms). Force-stop from Recents: YES, Android only, as Section 4 specifies. Brightness: NO** (you). Cache cleaner, RAM indicator, port scan, AirPlayGuard and QR server stay NO |
| Q-D | Twilight Horizon: keep it as it is (dark navy after sunset), or a lighter night variant | **Keep** (you) |

## Phase 3 work plan (after the go-ahead)

| Worker | Files | Work |
|---|---|---|
| A · Startup (Android) | `Intro.java`, `Shaders.java` (`GLASS_WORD`, `HORIZON_RIPPLE`), new `GlassText.java` (shared slope builder, moved from `DreamView.makeGlass`; DreamView then calls it), `Click.java` (Cmaj9, `chime-mahittv-glass.wav`), `LockView.java` (1:1 backdrop on handover, staggered entrance) | Section 1 |
| B · Startup (web) | `web/intro.js`, `web/lock.js`, `web/lock.css`, `web/home.js` (only the intro/lock hand-off and the "Startup animation" off path) | Section 1 on the web, Q-B |
| C · No-black (Android) + utilities | `HomeActivity.java` (B1, B5, double-tap Home), `HomeView.java` (B2, B3), `Backdrop.java` (B4), `Screensaver.java` (B6), `SettingsActivity.java` (B7, long-press rows), `res/values/styles.xml` + `res/drawable/horizon_window.xml` (B8), `Horizon.java` + `Shaders.HORIZON` night mid (B17, shared with A: A owns Shaders, so C sends the one-line change), `RecentsOverlay.java` + `HomeMode.java` (force-stop), `RemoteKeys.java` + `Prefs.java` (long press, 320 ms) | Sections 2 and 4 |
| D · No-black (web) | `index.html` (B9), `home.css` (B10–B16), `home.js` (B12 nature saver `.hz`, B16) | Section 2 |
| Me | Translations for every new string (25 languages), `MAHITTV-PROFILES-SPEC.md` §2, `tools/screenshots.js` + `tools/motion_strips.js` (the startup at 0.10, 0.40, 0.80, 1.20, 1.60, 2.20, 2.60 s; the lock entrance; Recents force-stop), tests (`DockRecessTest` stays; a `HorizonParityTest` for B17), `./build.sh`, `tools/check.sh` | Verification |

Verification: `./build.sh` and `tools/check.sh` green; the startup motion strip and frame timings on the web; screenshots by day and night of every Section 2 screen; the Android startup and Recents force-stop are build- and code-checked here and tried on your TV with you.

---

# Part 1 — the merge (decided and implemented 2026-10-30, f953dcb and 035afad)

What we take from github.com/techgenius1/tvOS-Launcher (audited at **v2.0.3**, released 2026-10-30; it supersedes
v2.0.2 and only adds `AppUpdater` and `CustomMedia`), what we restyle, and what we leave out, file by file. Sources
audited read-only (GitHub API, no clone): the upstream repo, the earlier attempt saved on `wip/tvos-launcher-merge`
(c0dafda), and our `tvos-redesign` (6e25bb1).

## 0. Where we start (already done, not redone)

| Item from the request | State |
|---|---|
| 9 commits of `fix/android-wallpaper-picker` (incl. 38cabe7 Dock layering) | On `tvos-redesign` (fast-forward to 5736517) |
| 8 "Free My TV" → "mahitTV" notices + 26 catalogues | Committed, f33da37 |
| Frosted Horizon behind the intro, "Who's Watching?", PIN pad, Settings, Home, Welly (web + Android) | Committed, 6e25bb1 |
| `.worktrees/apple-wallpaper` | Removed (its key and config backed up in `~/.freemytv/apple-wallpaper-backup/`) |
| `/tmp/tvos-launcher` | Never cloned (audited through GitHub's API); Phase 3 clones it with `--depth 1` (≈ 10 MB) only to copy approved files, then deletes it |

## 1. What the audits found (the facts the tables rest on)

- **Upstream has no AGSL.** Its "Liquid Glass" is a Canvas `Drawable`: a 192×108 box-blurred, saturated copy of the
  backdrop as a `BitmapShader`, a 1.04× "lens" band along the edge, a tint, a top glow and a gradient rim. Ours is a
  real AGSL refraction shader on Android 13+ with cached-bitmap fallbacks below. Theirs is a good *fallback*, not a
  replacement.
- **Upstream's Classic mode also goes full screen on Up from the first row** (grid slides away). Its Scenes mode never
  hides the dock.
- **The Dock-hiding you saw was ours, not the merge's.** In `HomeView.updateMotion` and `web/home.js drawPage`, ↑ into
  the Top Shelf (`zone == Z_HERO`, the `full` spring) drops the Dock 300 px and fades it out. Focusing a Dock app never
  hid it. The earlier attempt didn't add or fix this.
- **The three fonts** only reached the screensaver clock in the earlier attempt (default "duo" = Bahnschrift).
  **Bahnschrift is a Microsoft font we can't redistribute.** Antonio and Varela Round are OFL (license files must ship).
- **The dark screens** in the earlier attempt were all new: the screensaver scenes (50% black shade), the app switcher
  (≈88% near-black scrim), the weather screen (opaque navy).
- **Safety problems in upstream / the earlier attempt** (none of these are carried over as written):
  - The debloater runs its own `pm disable-user` batch around `HomeMode`: no `PROTECTED`, no `wifiOwners`, no record,
    so *Original* and the watchdog can't bring those apps back. Failed disables are counted as done; its core regex has
    stray spaces so some guards never match.
  - An AdGuard toggle writes the TV's Private DNS (a network setting).
  - Permission re-granting on every boot / every 60 s ("Keep Alive"), rewriting `enabled_accessibility_services`
    (removes other vendors' services), device admin, silent APK self-updates.
  - The earlier attempt's TMDB server: plain HTTP on the LAN, no auth, echoes the key back. Upstream's exported
    Settings activity takes the token from any app.
  - Presentation mode: an HTTP admin server on `0.0.0.0:8080` with a 6-digit PIN and no lockout.
  - The earlier attempt grabbed the remote's Search key in every app and skipped the boot safety check when it thought
    a cast receiver was running.
  - Made-up data on screen in the earlier attempt: a hard-coded "Now Playing" card, a fake Wi-Fi name, a random "MB freed".
- **Licences:** upstream Apache-2.0 (keep its NOTICE), CleanYourTV profiles MIT (Gathin23/cleanyourtv). Scene photos
  have no stated source; `app-logos/` are third-party trademarks (we already ship 14 such logos in `web/icons/`).

## Table 1 — Visual, shader, layout, wallpaper and animation

| # | Technique | Upstream | Ours now | Plan |
|---|---|---|---|---|
| V1 | Top Shelf on ↑ | Classic: grid slides away | Dock drops 300 px and fades (`full`) | **Recess, never hide:** Dock +36 px, 0.75 opacity, tab bar and status pill stay; details + Play over the art. Android `updateMotion`, web `drawPage`; `home-background-dom-test` gains a "Dock visible in hero" check |
| V2 | Dock focus | Lift 1.13, overshoot | 1.23× SNAPPY_FOCUS, specular, background cross-dissolves to the app's Continue Watching art | Keep ours (already never hides) |
| V3 | Liquid Glass on Android 8–12 | Lens band + rim + tint over a baked blur | Cached shadow bitmaps, flat glass | **Adopt their lens band + rim gradient** in `Scene.glass` for `Ui.LEGACY` only; AGSL stays on 13+ |
| V4 | Adaptive glass tint | Tint from backdrop luminance (4×4 sample) | Fixed tint | **Adopt:** Dock/Control Center glass picks its tint from the backdrop's luminance, so it stays legible over the bright Horizon *without* scrims (Android `Scene.glass`, web platter class) |
| V5 | Overlay backdrops | ¼-scale snapshot, box blur | n/a | **Adopt** for the new overlays (weather, recents): blurred home + light frost, never a dark scrim |
| V6 | Frosted Horizon on every default screen | n/a | Done (6e25bb1) | New overlays and the screensaver fallback use it too (the screensaver's no-photos aurora → Horizon) |
| V7 | App icons | Custom → bundled logo → adaptive layers ×1.24 → trimmed legacy icon with edge-colour gradient → banner crop | Bundled logo (folder empty) → logo lifted from the banner → compose | **Adopt the adaptive-layer expansion and edge-colour sampling** for apps with no banner; bundled HD logos per Q2 |
| V8 | App launch | Enter alpha 0.65→1, scale 0.97→1 (200 ms) | Home scales 0.95 and fades | **Adopt the window transition** for the app coming in; keep our spring on the home side; return zoom 1.04→1.0 on SPATIAL_SCROLL |
| V9 | Idle motion | Wallpaper drifts ±60 px, tiles tilt on a 5.2 s loop | Ken Burns on art only | **Leave out** (constant motion isn't tvOS; Q9) |
| V10 | Depth-layered scene clock | Photo → glass-textured giant clock → foreground cutout | n/a | Screensaver option only (Q3), Inter/Antonio, no shade |
| V11 | Fonts | Antonio, Bahnschrift, Varela Round for the clock | Inter Variable everywhere | UI stays Inter. Bahnschrift never ships |

## Table 2 — System features and utilities

| # | Feature | How upstream does it | How it lands in mahitTV |
|---|---|---|---|
| S1 | CleanYourTV (341 models) | Matches `Build.*` against `index.txt`, checklist, own ADB batch, AdGuard DNS | **Data + matcher adopted, execution ours:** `Debloat.java` matches the TV and builds the list; every package goes through `HomeMode` (`mayTurnOff`, `PROTECTED`, `neededByTv`, `wifiOwners`, `CORE`/`FACTORY`) and is added to the `disabled` record, so *Original* and the watchdog restore it. Settings → System & Original TV → "Clean Up This TV" (manager PIN, a checklist showing what's skipped and why). **No DNS toggle.** Fix their regex spaces. Assets 2.2 MB |
| S2 | Control Panel | Wi-Fi/BT/Audio open system pages; Memory (kill background, force-stop by accessibility clicks); DND; Ambient; Power | **Into our Control Center (no new panel):** Wi-Fi and Bluetooth cards (open the system page, show the real state, nothing written), "Screensaver now", plus Q4/Q6 items. No accessibility clicking, no DND |
| S3 | Recent Apps | Accessibility window events + usage stats (appops via ADB) | **From our own launch history** (`HomeApps.open` already logs every launch), no new permission: a carousel of glass cards over the blurred home, OK opens. Control Center card + Q8 key. Closing apps per Q7 |
| S4 | Weather overlay | Open-Meteo (+fallbacks), next hours + 5-day bars | **Adopt the layout:** 24 hours + 7 days with range bars, over the Horizon. Our fetch already asks for 48 h / 6 days but keeps 6 h / 5 days: keep 24 h, `forecast_days=8`, add precipitation, wind, humidity. Opens from the Control Center weather card and the home widget. Their `WeatherIcons` (vector, no assets) ported |
| S5 | Nature clock screensaver | 5 scenes (photo, glass clock, foreground cutout), 3 fonts | Q3. If yes: a screensaver style beside Photos/Picks, not the default; Antonio/Varela Round with OFL files, no Bahnschrift, no dark shade |
| S6 | Button mapper | 1x/2x/3x taps (300 ms) on colour/Menu/Guide keys, no long press | **Double press** added to `RemoteKeys` for the keys we already map (brand app buttons), choices in `remote-keys.txt`; `onKeyEvent` stays a few comparisons and one posted runnable. No Search-key hijack. Which keys: Q8 |
| S7 | Self-ADB | `LocalAdb`, keep-alive, auto-grant | **Not adopted:** our `Adb.java` already does it with fixed commands only |
| S8 | Media watch | Notification listener | Ours: `NowPlaying` (same API, already per profile) |
| S9 | TMDB key from a phone/QR | Exported activity extra (upstream), LAN server (earlier attempt) | **Not adopted:** the key stays only in `config.properties` (project rule) |
| S10 | Presentation/kiosk, AdminServer | HTTP server :8080 | **Not adopted** (LAN exposure) |
| S11 | AppUpdater | Silent self-install from GitHub | **Not adopted** (different app, different key) |
| S12 | AirPlayGuard | Restarts an AirPlay receiver app | **Not adopted** (that app isn't on the TV; it also weakened the boot check) |
| S13 | Setup wizard, Search/JustWatch, India-centric news/recs | | **Not adopted** (profiles come from config; Welly covers search) |

## Table 3 — Apple HIG audit and decisions

Decided by you (2026-10-30): Q1, Q2, Q3, Q4, Q6, Q8, Q11. The rest keep the recommendation.

| # | Question | Why it's questionable | Decision |
|---|---|---|---|
| Q1 | ↑ into the Top Shelf recesses the Dock (+36 px, 0.75) instead of hiding it | Your rule; changes today's behaviour | **YES** (you) |
| Q2 | Bundle 18 HD app logos (Netflix, Disney+, Prime Video, Apple TV, Spotify, Max, YouTube, …) for the Dock | Trademarks; we already ship 14 on the web | **YES** (you) |
| Q3 | Nature clock screensaver as an optional style | Busy next to tvOS's Aerials; unknown photo sources | **YES, optional**, not default (you) |
| Q4 | Volume slider in Control Center | Rule "don't change the TV's sound settings"; the level is what the remote's keys change anyway | **YES, volume only** (you) |
| Q5 | Brightness slider | Needs WRITE_SETTINGS; on TCL picture brightness is the TV's own setting | **NO** |
| Q6 | "Close Background Apps" (Android's `killBackgroundProcesses`); no cache trimming, no force-stop | Cache trimming touches system apps' storage | **YES, close only** (you) |
| Q7 | Closing an app from Recents (`am force-stop` over our ADB) | A system-level action from the home screen | **NO** (Recents opens apps only); superseded 2026-10-31 by Part 2 Q-C: YES, Android only |
| Q8 | Double press on the remote's app buttons | Must stay light in the `:keys` service | **YES** (you) |
| Q9 | Idle wallpaper drift and tile tilt | Constant motion, un-Apple | **NO** |
| Q10 | AdGuard Private DNS in CleanYourTV | Changes a network setting | **NO** |
| Q11 | South Brunswick coordinates: config has 40.3807, −74.5321; you gave 40.3818, −74.5404 | | **Yours** (you; local `config.properties` only) |
| Q12 | After the merge: delete `wip/tvos-launcher-merge` | It holds the unsafe attempt | **YES** once Phase 3 is committed |

## Table 4 — Unified architecture, file by file

Parallel workers own disjoint files (as in the profiles phase), each building into its own `out-xx/`.

| Worker | Files | Work |
|---|---|---|
| A · Android home | `HomeView.java`, `HomeApps.java` (new kinds `WEATHER`, `RECENTS`, `WIFI`, `BLUETOOTH`, `SAVER`, `VOLUME`, `CLOSE_BG`), `HomeActivity.java`, `Scene.java` | V1 recess, Control Center cards, V3/V4 glass, V5 overlay host, V7 icons, V8 launch transition |
| B · Android screens | new `WeatherOverlay.java`, `WeatherIcons.java` (ported), `RecentsOverlay.java`, `DreamView.java` (+ nature style if Q3), `HomeData.java` (24 h / 7 days, extra fields), `res/anim/` (V8) | S3, S4, S5; Horizon + glass, no scrims, no allocation in `onDraw` |
| C · Android system | new `Debloat.java`, `HomeMode.java` (`applyProfile` through the existing filters and record), `SettingsActivity.java` (Clean Up, Screensaver style, double press), `RemoteKeys.java`, `Prefs.java`, `assets/cleanyourtv/` | S1, S6; manager PIN; tests `DebloatTest` (PROTECTED, Wi-Fi owners, record, Amazon refusal) |
| D · Web | `web/home.js`, `home.css`, `data.js` (24 h / 7 days), new `web/weather.js` + CSS, saver style if Q3 | V1, S4, Control Center cards that exist on LG/Samsung (weather, recents from the profile's log), tests |
| Me · integration | `NOTICE` (Apache-2.0 + MIT attributions), font licences, `config.properties` (Q11), catalogues for every new string in all 25 languages, `tools/screenshots.js` (recess, weather, recents, Control Center), `CLAUDE.md` notes | Build, `tools/check.sh`, screenshots, review |

Unchanged and protected: the `TV | Home | Welly` tabs, HouseView/Kasa/`house.json`, Welly (WellyBot, brain, tools,
memory), the intro, the lock and profiles, WatchLog, South Brunswick weather, Reset & Restore, the Original mode and
the watchdog.

## Verification (Phase 3)

- `./build.sh` and `tools/check.sh` green (WellyBot contracts, wallpaper tests, 26 catalogues, ES5/CSS).
- New tests: Dock visible and above the art in the Top Shelf (web DOM test + `WallpaperTest`), `DebloatTest`, weather
  parse (24 h / 7 days).
- Screenshots 1920×1080: Dock focused, Top Shelf recessed, Control Center with the new cards, the weather overlay,
  Recents, Clean Up This TV, the screensaver styles; opened on the Mac.
- Android: build-checked here; on the TV (when connected) you do the taps. Clean Up This TV and Original are tried
  there with you before anything is switched off.
