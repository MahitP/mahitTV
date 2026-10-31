# mahitTV × tvOS-Launcher: merge spec (decided and implemented 2026-10-30)

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
| Q7 | Closing an app from Recents (`am force-stop` over our ADB) | A system-level action from the home screen | **NO** (Recents opens apps only) |
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
