# mahitTV — profiles, lock screen, Welly memory, app transfer, Settings

The home screen becomes a family TV: a golden **mahitTV** intro, a "Who's Watching?" lock with PINs, each person's own
Dock, theme, wallpaper and Welly memory (with a private Continue Watching that lives in Welly, never on the TV tab),
every installed app carried over, and one complete on-TV Settings with two protected resets. The tab bar stays
`TV | Home | Welly`; the apple-design rules (DESIGN-TVOS.md §0b) and the motion engine (ANIMATIONS-SPEC.md,
`Spring.java`, `web/motion.js`) apply to everything here. Android and the LG/Samsung web app get the same behaviour,
except what only a Google TV can do (Kasa, Original mode).

Decisions (2026-10-27): names, roles and starting PINs live in `config.properties` (git-ignored); the source ships
generic defaults; PINs are stored hashed. mahitTV is the name everywhere on the TV, but the package (`io.freemytv`) and
file names stay, so updates install over the current app and the Original/watchdog safety net keeps working. Intro +
lock on every power-on and first launch; within 10 minutes of the last use the TV goes straight back to the same
profile, after that the lock appears (no intro). Continue Watching logs every launch and, with Now Playing access on,
the title and position the playing app reports.

---

## 1. Accounts and storage

### 1.1 config.properties
```
profiles=mahit:Mahit:manager:2123,dhruvin:Dhruvin:standard:2013,subbu:Subbu:standard:2212,lucky:Lucky:standard:1063,guest:Guest:guest:
default_dock_apps=com.netflix.ninja,com.google.android.youtube.tv,…   # 6 apps, in order (Android packages / LG-Samsung ids)
```
- `id:Name:role:PIN[:avatar]`; roles `manager` (one), `standard`, `guest` (no PIN). The old form `Name:avatar.png` still
  works (a standard profile without a PIN).
- The build never writes a PIN into the app: `build.sh` and `tools/webapp.py` write `sha256(salt + PIN)` with a random
  salt per build (`Config.PROFILES`, `Config.PIN_SALT`; the web's `CONFIG.profiles[].pin`, `CONFIG.pinSalt`).
- The example file ships one generic manager and a guest; the real family list is only in the user's own
  config.properties (git-ignored), like the TMDB key.

### 1.2 Avatars
`app/assets/profiles/<id>.png` (the web build packs the same files as `profiles/<id>.png`), else the existing avatars
(`avatars/memoji.png` for the manager, `avatars/default-N.png` for the others, a glass person glyph for Guest). Drop a
Memoji in to replace one; `app/assets/profiles/` is git-ignored (personal).

### 1.3 Per-profile state
| Kept per profile | Kept for the whole TV |
|---|---|
| Dock apps and order, theme, wallpaper/photos, top buttons, sounds, Welly memory (turns + facts), Continue Watching, a changed PIN | language, city and units, sleep timer, remote buttons, Home Screen mode, Now Playing access, screensaver, Kasa |

- Android: `ProfileManager.prefs(profile)` → its own SharedPreferences file `profile_<id>`; `Prefs` routes the
  per-profile keys through the current profile. Guest: an in-memory store, wiped on lock, sleep or switch.
- Web: `localStorage['mahittv.profile.<id>']`; `Prefs` routes the same keys; Guest in memory.
- First run after this update: today's single profile's Dock/theme become the manager's.

### 1.4 PINs
- Each check: `sha256(salt + digits)` against the stored hash. A changed PIN gets a new random salt, stored in that
  profile's file. Five wrong tries → 30 s wait (per profile, in memory).
- Anyone changes their own PIN (old PIN first); the manager can set anyone's (with the manager PIN).

## 2. Golden intro
- When: power-on (a `SCREEN_ON` receiver in the home process), first launch after boot or install. Web: first load.
- Obsidian background; **mahitTV** in Inter Display 700, brushed-gold fill (#D4AF37 → #F5D061 → #FFF3B0 → #D4AF37), a
  specular band sweeping once; 48 gold sparks rising with depth blur; a warm C–E–G chime (synthesized once, SoundPool;
  Web Audio on the web where it exists).
- All parameters in one place: `Intro.java` `INTRO_*` constants and `web/intro.js` `INTRO` (duration 2.4 s, sparks,
  rise, bokeh, sweep, colours, chime).
- Ends: cross-dissolve, the lock 1.04 → 1.0 on SPATIAL_SCROLL. OK or Back skips. Off with Settings → Appearance →
  Startup animation, reduce motion (static wordmark, quick fade), Android 8–11 (no sparks).

## 3. "Who's Watching?"
- After the intro, on lock from Control Center / the status pill, and after 10 minutes away.
- Blurred aerial behind; "Who's Watching?" (Inter Display 57); five avatar cards (squircle 240 px, 96 px apart).
- Focus 1.18× (SNAPPY_FOCUS), a specular ring (gold for the manager, white glass for others), name and a lock glyph
  fade in; dead ends bump; Back does nothing (it's the gate).
- Guest: OK → in. Others: the card glides to the centre, the PIN HUD shows four dots, a glass 0–9 row and takes the
  remote's number keys; each digit pops its dot (MICRO_PRESS). Wrong: head-shake ±18 px (impulse on BOUNDARY_BUMP), the
  error knock, dots clear. Right: dots pulse gold, the avatar flies into the status pill, the TV tab unfolds.

## 4. Welly per profile, Continue Watching inside Welly
- **WellyBot (`WellyBot.java` & `web/welly-bot.js`):** Welly's official visual form is the `#2fbfa0` green `galet`
  pebble silhouette with clipped `#FFFFFF` white eyes, 15 kinetic states and 16 expressions, centre-stage on open and
  docked beside `Welly · mood` when answering (plus a mini pebble badge in the `Welly` top tab pill).
- `WatchLog`: every launch through `HomeApps.open` (Top Shelf, Welly, deep links, Dock) → {title, tmdbId, kind, app,
  package, time, position, duration}; NowPlaying's media session (when allowed) updates the current entry's title and
  position. 30 entries per profile; nothing for Guest. Never shown on the TV tab.
- Welly's tool `continue_watching` returns the current profile's list as media cards (OK opens the title or app);
  "What am I watching?", "Continue watching", "Pick up where I left off" route there (preview too).
- The system prompt carries the profile: id, name, role, favourite genres (from the log), recent titles, Dock apps.
  Manager: creator/admin of mahitTV, best-friend roommate tone. Family: their own name, taste and history. Guest:
  says it's Guest Mode and nothing is kept.
- Preview builds seed Mahit and Dhruvin with different sample logs and Docks.

## 5. Apps
- `HomeApps.launchable()` keeps both LEANBACK_LAUNCHER and LAUNCHER (the manifest `<queries>` covers both) with the
  Leanback → normal launch-intent fallback; Settings → System adds "Show system apps" for system apps that only have a
  phone-style entry, and About shows how many apps were found.
- `default_dock_apps` seeds each new profile's Dock; each profile edits its own on the TV (Dock editor, hold-to-move).

## 6. Settings and resets
Sections (the existing two-pane Settings on Android, the Settings sheet on the web):
1. **Profiles & PINs** — switch, lock, this profile's Dock, change PIN; the manager manages everyone.
2. **Appearance & Startup** — theme, wallpaper/photos, startup animation & chime, reduce motion/transparency.
3. **Location & Media** — city, temperature unit, TMDB/Watchmode key present or not, Top Shelf auto-advance.
4. **Smart Home** — the four Kasa lights, their IPs and reachability (reading state only). Android.
5. **Welly** — brain status (Gemini key present, Ollama reachable), clear this profile's memory and history.
6. **System & Original TV** — TV Settings, Home Screen mode, screensaver, Remote Buttons, Sleep Timer, Top Buttons,
   language, Live TV, show system apps.
7. **Reset & Restore** (manager PIN):
   - **Reset mahitTV data** — clears every profile file, Welly memory, logs and caches → the default profiles.
   - **Restore Original TV** (Android) — `HomeMode.request(ORIGINAL)`, the existing revert with its ordering and
     safety rules (setup app, Wi-Fi owners, watchdog). mahitTV stays installed. Choosing Original under Home Screen now
     asks for the manager PIN too.

## 7. Verification
`./build.sh`, `tools/check.sh` (all 25 catalogues for new strings); web screenshots: intro, Who's Watching, PIN entry,
Welly Continue Watching for Mahit and Dhruvin, Settings with Reset & Restore; motion strips: intro, wrong PIN, unlock.
The Android side is build-checked here and tried on the TV when it's connected (the user does the taps).
