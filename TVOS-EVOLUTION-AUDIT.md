# tvOS evolution audit: 9.2 → 27.2, and what mahitTV does with it

mahitTV keeps its own layout (TV · Home · Welly, the clay house, Welly, the five profiles) and takes tvOS's look and
behaviour wherever there's an Apple equivalent. This file lists, version by version, what tvOS did, where it came from,
and where it is in mahitTV (Android `app/src/`, web `web/`). The last section is the checklist for a side-by-side with
Xcode's tvOS Simulator.

## How this was researched, and how sure each point is

- **Written sources** (linked at the end): Apple's tvOS 12 notes and support guides, Apple Newsroom (tvOS 18),
  Wikipedia's tvOS history, MacStories' tvOS 12 and 17 reviews, 9to5Mac's tvOS 27.2 coverage, and 2016 articles on
  tvOS 9.2 folders.
- **The six videos** were checked for title and channel through YouTube's oEmbed endpoint (below). Their content
  couldn't be watched from the build machine (YouTube refuses it), so no timestamp detail here comes from them. Every
  claim below is backed by a written source, or marked as coming only from the brief.
- **Tags:** `[documented]` Apple's own pages; `[reported]` press or reviews; `[brief]` only in the request, not found in
  writing (implemented where cheap and harmless, else left out with the reason).

| Video | Title (oEmbed) | Channel |
| --- | --- | --- |
| `6W_OEoYYA_E` | Give me 13 minutes and I'll Make You Love Apple tvOS 27.2 Update | HalfManHalfTech |
| `GdrhL2fHya0` | tvOS 27.2 Is Better Than Apple Admitted | HotshotTek |
| `VbFRZJLGQXs` | Give me 11 minutes and I'll Make You Love Apple tvOS 27 Update | HalfManHalfTech |
| `9fZ9UmF7ya0` | tvOS 17 - Biggest Apple TV Update in YEARS! (Everything New) | Brandon Butch |
| `VBQZs56WxYk` | tvOS 12 RELEASED! What's new? | Nikias Molina |
| `xD6BhgYcfQ8` | What's new with Apple TV on tvOS 9.2 | 9to5Channel |

## Version by version

### tvOS 9.2 (2016): folders and the card switcher

- **Folders** `[reported]`: hold an app until it wiggles, then drag it onto another, or press Play/Pause for a menu
  (New Folder, Move to…, Move to Home Screen, Delete, Cancel). A folder shows nine apps at a time and scrolls for more.
- **App switcher** `[reported]`: double press Home; previews stacked like cards, swiped between.
- **In mahitTV:**
  - Folders open as a frosted card with a white title badge, springing out of the folder's tile. They now show 3
    columns and 9 apps, and the apps slide up a row at a time past that (`FOLDER_COLUMNS = 3, FOLDER_ROWS = 3` in
    `HomeView.java`, `FOLDER_COLS` / `FOLDER_ROWS` and `folderScroll()` in `web/home.js`).
  - The switcher is Recent Apps (hold Home), with the focused card 845 × 475.
  - **Not done:** folders you make yourself, and the Play/Pause menu. mahitTV has one automatic folder (TV &
    Utilities). Making your own folders needs a rearrange mode and saving per profile. It's the largest item left (see
    "Still open").

### tvOS 10 to 11 (2016–17): dark mode, automatic switching

- Dark mode `[reported]` (10), then Automatic, by time of day (11).
- **In mahitTV:** Theme is Automatic / Light / Dark (Settings → Appearance), and Control Center's Dark Mode tile flips
  between Light and Dark.

### tvOS 12 (2018): Aerials' location

- **Location name** `[documented]` (Apple's tvOS 12 notes): during an Aerial, tap the remote for the place's name, and
  swipe left or right for another place. MacStories: the name is "a line of text in the lower-left corner", fading after
  a moment. On today's remotes, a tap on the clickpad, or the Down button `[reported]`.
- **In mahitTV:** over Apple Aerials and Nature Clock, **↓** shows the name and **← / →** go to the previous or next
  place. The name sits bottom left, 80 px in and 32 px above the 60 px safe area, at 30 px semibold white with a soft
  shadow. It fades in over 0.4–0.5 s, stays 4 s, and fades out. Any other key still wakes the TV.
  - Web: `Saver.key()` and `caption()` in `web/saver.js`, `#saver .place` in `web/home.css`, and the key path in
    `web/home.js`.
  - Android: `DreamView.key()` and `drawPlace()`. `Screensaver` is now interactive, and lets the volume keys through.
  - Names: the scenes' (Night, Golden Hour, Waterline, Fog, Rainforest Dawn), and the stills' places (Iceland Coast,
    Yosemite Valley, New York from Above, North America Aurora).
  - **Android difference:** ← goes on to the next still too. The Aerials there come from a prepared-next queue, so
    "previous" would need the last one loaded again.

### tvOS 13 to 16 (2019–22)

- **Control Center** `[reported]` arrives in 13, redesigned in 14. Also full-screen previews on the home screen,
  rounder icons, and scrolling lyrics.
- **In mahitTV:** the Top Shelf is full screen behind the Dock, the tiles are 5:3 rounded squircles, and Control Center
  is described under 17 and 27.

### tvOS 17 (2023): Control Center top right, Sleep Timer, profiles

- **Control Center** `[reported]` lives top right on the home screen, with pages.
- **Sleep Timer** `[documented]` (Apple's tvOS 17 guide): 15 Minutes, 30 Minutes, 1 Hour, 2 Hours, and Cancel.
- **Do Not Disturb** `[documented]` turns off notifications while you watch. Profiles switch from Control Center.
- **In mahitTV:**
  - Control Center has its own round button left of the time-and-avatar pill. The pill opens the profile menu
    (profiles, weather, Inputs, Screensaver, Lock, Home Settings, TV Settings).
  - Control Center is the fixed grid you chose: Power | Audio Output, Wi-Fi | Bluetooth, Dark Mode | Do Not Disturb,
    Sleep Timer | Brightness, with the volume bar under it.
  - **Sleep Timer** now steps through tvOS's presets in place: Off → 15 min → 30 min → 1 h → 2 h → Off.
    `SLEEP_PRESETS` in `web/home.js` and `HomeView.java` (it sets the real timer through `SleepTimer.set`).
    Settings → Sleep Timer keeps its longer list.
  - **Do Not Disturb** `[brief]`: turning it on swings the moon in from −40° and grows it to size, with an overshooting
    ease (cubic-bezier 0.34, 1.56, 0.64, 1), over 0.6–0.7 s. Web: `.dot.pop` with the `moonin` keyframes. Android:
    `dndAt` in `drawDrawerTile`.

### tvOS 18 (2024): Portraits, Snoopy, InSight

- **Screensavers** `[documented]` (Apple Newsroom): Portraits (depth-layered photos and clock), and Snoopy later in
  the year.
- **InSight and Enhance Dialogue** belong to the TV app and audio, with no home screen equivalent.
- **In mahitTV:** Nature Clock plays Portraits' part: a giant clock between the scene and its cut-out foreground. There
  is no Snoopy, because Peanuts characters are licensed, and drawing a Welly screensaver is a design job of its own.

### tvOS 26 (2025): Liquid Glass

- **Liquid Glass** `[reported]` (Wikipedia; the version numbers jump to the year).
- **In mahitTV:** the Dock, the panels, the pill, the tab bar and the Settings sidebar are glass. On Android it's an
  AGSL shader from Android 13 (`Shaders`, `Scene.liquid`), with blurred stand-ins before that. On the web it's a blurred
  copy of the backdrop under a tint, with no `backdrop-filter` (see 27). Focused tiles carry a moving sheen with their
  tilt.

### tvOS 27 (September 2026): speed, text size

- **A faster, more responsive Control Center** `[reported]` (several outlets; Apple's own story was speed across its
  operating systems).
- **System-wide text size** `[reported]`, a slider in Accessibility. Its reach (every app, or only Dynamic Type ones) is
  reported differently.
- **Hi-Res Lossless and a new Podcasts app** `[reported]`: apps, not home screen.
- **"Liquid Glass refinement", "specular wiggle", "hues bleeding through"** `[brief]`: not found in writing. Several
  sites call Liquid Glass new in 27, which contradicts 26.
- **In mahitTV:**
  - **Control Center opens faster:** its own spring, `CC_OPEN` (response 0.32, damping 0.86), instead of a sheet's
    0.44 / 0.88, with its rows 18 ms apart instead of 25. `Spring.CC_OPEN`, `P.CC_OPEN`.
  - **Text Size** (Settings → Appearance & Startup, under Theme): Small 90 %, Default, Large 115 %, for the whole TV.
    - Android: one factor in `Ui.text()`, so every text the app draws follows (the home screen picks it up when it
      comes back; Settings at once).
    - Web: `body.text-small` / `text-large` rules for Settings, Control Center, the profile menu, the Inputs list, the
      tab bar and All Apps' names. Old TVs have no CSS variables, so it's those styles, not literally every one.
  - **`backdrop-filter: blur(44px) saturate(195%)` is not used.** LG's webOS 3–4 and Samsung's Tizen 3 run Chromium
    from 2015–17, which can't do it, and `tools/check.sh` fails the build on it. The web's glass already samples the
    picture behind it (a blurred copy, saturation boosted), which is what lets the Top Shelf's colours through.

### tvOS 27.2 (October 26, 2026): Shuffle, the new Music icon

- **Screen saver Shuffle** `[reported]` (9to5Mac): a new option rotates through every screensaver category during the
  day (Aerials, Memories & Slideshows, Portraits, Snoopy), where you used to pick one.
- **Photos picker for screensavers** `[reported]`: choose which photos Portraits and Memories use.
- **Apple Music's rebrand** `[reported]`: a white note on black replaces the red icon (Geeky Gadgets; MacDailyNews says
  black and white, with the accent moving from red to blue).
- **New profile characters** `[reported]` (Snoopy, Frog & Toad, Stillwater), and sign language in the TV app.
- **"AutoMix pill" and an album-coloured Music background** `[brief]`: AutoMix is Apple Music's own player.
  `[brief]` also: the avatar-tinted profile glow.
- **In mahitTV:**
  - **Shuffle** is the first choice under Screensaver. It shows Aerials, Nature Clock, Photos and Aurora in turn,
    20 minutes each through the day, and changes over while it shows. Web: `styleNow()` and `SHUFFLE` in `saver.js`.
    Android: `shuffleMode()` in `DreamView`, which restarts the look when its 20 minutes are up.
  - **The Music tile** is a white note on black glass (#3A3A3C → #000000) in light and dark. `BRANDS` in
    `web/home.js`, `TILE_STYLE` in `HomeView.java`, and the colours in `HomeApps` and `web/tv.js`. The note is
    mahitTV's own drawing, not Apple's artwork.
  - **Not done:** an album-tinted Music screen and an AutoMix pill (mahitTV has no music player; Apple Music runs as its
    own app), and the avatar-coloured glow (no source found).

## Still open

1. **Folders you make yourself**, tvOS 9.2's way: wiggle mode, drop an app on another, and the Play/Pause menu (New
   Folder, Move to…, Move to Home Screen, Cancel), saved per profile.
2. **A Snoopy-like character screensaver**: a sleepy Welly.
3. **Profile characters** like 27.2's, as avatar choices.
4. **Android ← in Aerials**, back to the previous still.
5. **Text Size on the web** for the remaining styles (the Top Shelf's title, the screensaver clock, toasts).

## Xcode tvOS Simulator side-by-side calibration checklist

**Set-up (on the Mac).** Xcode → Settings → Platforms → tvOS. Open the Simulator with Apple TV 4K (3rd generation) on
the newest tvOS, in light appearance first (Settings → General → Appearance).

```bash
xcrun simctl list devices tvOS                 # the device's id
xcrun simctl boot <id>; open -a Simulator
xcrun simctl ui booted appearance light        # then dark for the second pass
xcrun simctl io booted screenshot sim-home.png # 3840 x 2160; the compare script scales it to 1920 x 1080
python3 webos/build.py --preview && node tools/screenshots.js
python3 tools/simulator_compare.py sim-home.png previews/web-tvos-light-home-dock.png out-home.png
```

`simulator_compare.py` puts the two side by side, with a heat map of their difference and its mean. `--box x,y,w,h`
compares one region. The Simulator's own apps differ from yours, so compare **layout, type, materials and motion**, not
the icons.

**Frames to capture**, with the mahitTV screenshot each one goes against:

| # | Simulator | mahitTV (`previews/web-tvos-<theme>-…`) | Check |
| --- | --- | --- | --- |
| 1 | Home screen, Dock row focused | `home-dock` | tile size and corner radius; gap (57 px); focused scale (1.23×) and shadow; name under the focused tile |
| 2 | Home, ↑ to the Top Shelf | `topshelf-recess` | how far the Dock recesses; Top Shelf title position and type |
| 3 | Control Center open | `control-center` | panel x / width / top; corner radius; tile height and spacing; fill opacity; type sizes |
| 4 | Control Center, a toggle focused | `control-center-volume`, `-dnd` | focused tile: white, ink colour, lift; the "on" blue (#007AFF / #0A84FF) |
| 5 | Control Center → Sleep Timer | `control-center` (Sleep detail) | preset names and order |
| 6 | Profile switcher (top right) | `profile-panel` | avatar size, ring, name type, spacing |
| 7 | A folder open | `folder` | card size and radius; title badge; 3 columns; the 9-app window; scroll step |
| 8 | Recent Apps (double press TV) | `recents` | focused card 845 × 475; neighbours' scale and offset; the app name above |
| 9 | Settings root and a section | `settings`, `settings-appearance` | the two panes' widths; row height (64); group inset; value colour; switch (#34C759) |
| 10 | Settings → Accessibility → Text Size, at each step | `text-size-small`, `text-size-large` | the scale per step (we use 90 / 100 / 115 %) |
| 11 | Screen saver, Aerial, tap for its place | `saver-aerials-place` | caption x / bottom / size / weight / fade time (we use 80, 92, 30 px semibold, 4 s) |
| 12 | Screen saver → Shuffle | `saver-shuffle` | Shuffle's position in the list; how often it changes (we use 20 min) |
| 13 | Volume and brightness overlays | `volume-hud`, `brightness-hud` | position, size (72 × 320), fill, glyph |

**Motion**, recorded with `xcrun simctl io booted recordVideo sim.mov` and stepped frame by frame:

- **Control Center opening:** time to settle, overshoot (we use 0.32 / 0.86, rows 18 ms apart).
- **Focus moving between Dock tiles:** scale-up curve and settle (`SNAPPY_FOCUS` 0.28 / 0.82).
- **A folder opening from its tile:** start scale (0.3), curve (`SHEET_OPEN`).
- **The screensaver arriving** (1.06 → 1, 1.2 s) and leaving (0.32 s dissolve).

After each pass, put the numbers that changed into `DESIGN-TVOS.md` and the constants named above, then render the
previews again and compare.

## Part 3: motion audit and readiness

### Springs (`web/motion.js`, `Spring.java`, `ANIMATIONS-SPEC.md` §1.2)

Everything that moves runs on the same damped spring, on both platforms. A spring is described by its response (the
time it takes to settle) and its damping (1 = no bounce, lower = some overshoot).

| What | Preset | Response | Damping |
| --- | --- | --- | --- |
| Dock and grid focus (1.23×), Settings and panel focus | `SNAPPY_FOCUS` | 0.30 s | 0.80 |
| Control Center and the profile menu, and their Inputs list | `CC_OPEN` | 0.42 s | 0.85 |
| Sheets (All Apps, Settings) | `SHEET_OPEN` / `SHEET_CLOSE` | 0.44 / 0.34 s | 0.88 / 0.96 |
| Volume and brightness overlay, bounce past 0 % and 100 % (3 % stretch) | `BOUNDARY_BUMP` | 0.24 s | 0.76 |
| Recent Apps deck scroll | `SPATIAL_SCROLL` | 0.46 s | 0.92 |
| The glare's sweep | (its own) | 1.1 s | 1 (no bounce) |

- **Control Center trade-off:** `CC_OPEN` was 0.32 / 0.86, after tvOS 27's faster Control Center. It's now 0.42 / 0.85,
  as you asked for this pass.
- **CSS transitions:** none use plain `ease` any more. The 146 transitions (fades, colors, shadows) settle on
  `cubic-bezier(0.22, 1, 0.36, 1)`, a curve close to a critically damped spring. The settings switch knob overshoots a
  little (`cubic-bezier(0.3, 1.35, 0.5, 1)`, 0.32 s).
- **Left on `ease-in-out`, on purpose:** the always-running loops (the breathing chevron, the playing-music bars, the
  aurora drift). They move back and forth forever, so a sine-like curve suits them.

### Light and depth

- **The glare (new):** when focus lands on a tile in the Dock, All Apps or a folder, a narrow band of light sweeps across
  it in the direction you moved.
  - It has a 1.5 px bright edge along the tile's bevel, rides the tile's tilt, and is brightest crossing the middle.
  - It's skipped while you hold an arrow to scroll, when animations are off, and on Android 8–11 and Tizen 2.4 (their
    GPUs).
  - Web: `Fx.sweep()` and `.glare`. Android: `glare` in `drawAppTile`, one gradient per tile size, moved by the canvas.
- **The sheen:** the soft specular highlight that slides against the tilt (DESIGN-TVOS.md §0b) is unchanged.
- **Recent Apps deck (new depth):** the cards beside the focused one turn up to 9° toward it, their outer edges
  receding. Web: `rotateY` in perspective. Android: the card mapped to a matching trapezoid.

### Readiness checklist

| Check | Status | How it was checked |
| --- | --- | --- |
| The Dock never hides when an app or a pick is focused | Yes | `DockRecessTest` (14 checks) and the web DOM test: in full screen it recesses 36 px to 0.75 opacity |
| Every screen sits on the Frosted Horizon, Light / Dark / Auto | Yes (preview) | Light and dark screenshots of every screen; Settings → Theme and Control Center's Dark Mode |
| The TV's apps in order, StbEmu in slot 6, the TCL folder | Yes (code, preview) | `web-keys-test` checks the Dock, the 18-tile grid and the TCL folder; Android uses the same order and folder rules |
| Apps launch | Code paths only | TV-style and phone-style launch intents, with fallback packages; **not run on the TV from here** |
| App tiles | Partly | On the TV, each tile uses the app's own artwork in a glass tile; the web preview uses each brand's color and name, plus the bundled logos |
| Wi-Fi name, Bluetooth headphones, remote | Partly | Android shows the Wi-Fi name where Android allows it, and the playing headphones' name; the remote's name isn't read (Android hides paired-device names without Bluetooth permission); the preview reads `preview_wifi` / `preview_bluetooth` |
| The TV's Settings pages | Code paths only | Each Google TV Settings row tries the public Android action, then the TV's Settings |
| Build and checks | Yes | `./build.sh` → Built FreeMyTV.apk; `tools/check.sh` → All checks passed |

**Ready for the TV, after one check on the TV itself.** Everything above builds and passes its tests. This build machine
can't run an Android emulator, so before calling it done, check these on the TCL:

1. A few launches, especially StbEmu, Instagram and Roblox.
2. Each Google TV Settings row.
3. The glare and the deck's depth.
4. Holding Home for Recent Apps.

## Part 4: Apple apps and AirPods on a Google TV

What's real here and what isn't. Nothing in mahitTV pretends to be an Apple app.

### Built

- **AirPods and Beats.** When a Bluetooth output whose name contains "AirPods" or "Beats" joins
  (`AudioManager.registerAudioDeviceCallback`, no permission needed; `AirPods.java`):
  - A Liquid Glass card slides in at the top right (y 36 → 108, spring 0.38 / 0.82) for 3.5 s. It shows the device's
    name and "Connected".
  - "Spatial Audio" is added only when Android's Spatializer (Android 12L and newer) says it's available and on.
  - A small AirPods glyph sits left of Control Center's bubble while they're connected.
  - The Audio Output pill and the volume HUD show the buds instead of the speaker.
  - In the web preview, `A` or the Audio Output / Bluetooth pills connect a stand-in pair.
- **The Apple folder** (All Apps, where Apple Music goes). It holds:
  - Apple Music. If the app is installed (`com.apple.android.music`) it opens it; otherwise it opens a guide.
  - FaceTime, Fitness+ and Podcasts guides. Each guide (`AppleGuideActivity`, `GUIDES` in `web/home.js`) gives three
    steps for playing it from an iPhone or iPad over AirPlay, and a QR code to Apple's page (`app/assets/qr/`).

### Not possible, and why

| Asked for | Why not |
|---|---|
| FaceTime on the TV | FaceTime on the web needs Chrome or Edge with a camera, and someone with an Apple device has to start the call. The TV has no camera, and Android TV's WebView isn't Chrome. |
| Continuity Camera (iPhone as webcam) | This only works between an iPhone and an Apple TV or Mac. A web page on the iPhone can't use its camera over plain HTTP: browsers require HTTPS for that, and a TV on the home network has no certificate. |
| Fitness+ rings and heart rate | Your Activity data stays on your iPhone and Apple Watch, and Android has no way to read it. Showing rings would mean showing made-up numbers. |
| An Apple Music player inside mahitTV | There's no Apple Music API for Android TV apps. Use the Apple Music app itself, or AirPlay from an iPhone. |
| Starting AirPlay from the home screen | Apps can't control the TV's AirPlay receiver. It's part of the TV's firmware, and only some TCL models have it. |

### Installing Apple Music yourself

- **From Google Play:** where it's offered for the TV, install it there. The Apple folder opens it once it's installed.
- **Sideloading:** sideload only an APK you trust (from Google Play, or one you pulled from your own phone), with
  `adb install -r <file>.apk`. The phone build runs but isn't made for a remote. mahitTV finds it by its package,
  `com.apple.android.music`.
- **Cider and similar:** these are desktop apps, with no Android TV build.
- **AirPlay on TCL:** where the model has it, turn it on in the TV's own settings (*Settings › Channels & Inputs ›
  Apple AirPlay & HomeKit*). mahitTV doesn't change it, per the rule against changing the TV's own settings.

## Sources

- [Wikipedia: tvOS](https://en.wikipedia.org/wiki/TvOS)
- [Apple: What's new in tvOS 12](https://support.apple.com/en-au/guide/tv/atvb457e13ae/12.0)
- [MacStories: tvOS 12 review](https://www.macstories.net/reviews/tvos-12-the-macstories-review/)
- [Apple: Adjust settings in Control Center (tvOS 17)](https://support.apple.com/en-kz/guide/tv/atvb18c04fc0/17.0/tvos)
- [MacStories: tvOS 17 review, Control Center](https://www.macstories.net/reviews/tvos-17-the-macstories-review/3/)
- [Apple Newsroom: tvOS 18 is now available](https://www.apple.com/newsroom/2024/09/tvos-18-is-now-available/)
- [9to5Mac: tvOS 27.2 just launched](https://9to5mac.com/2026/10/26/tvos-27-2-just-launched-for-apple-tv-4k-heres-whats-new/)
- [9to5Mac: tvOS 27.2 adds three new features](https://9to5mac.com/2026/10/20/tvos-27-2-adds-three-new-features-to-your-apple-tv-4k/)
- [Geeky Gadgets: tvOS 27.2 RC](https://www.geeky-gadgets.com/tvos-27-2-rc-features/)
- [MacDailyNews: What's new in the 27.2 releases](https://macdailynews.com/2026/10/28/whats-new-in-apples-macos-27-2-ios-27-2-ipados-27-2-and-tvos-27-2-visionos-27-2-and-watchos-27-2/)
- [BGR: tvOS 27 features](https://www.bgr.com/2269260/best-apple-tv-tvos-27-features/)
- [TidBITS: tvOS 9.2](https://tidbits.com/2016/03/23/with-tvos-9-2-the-fourth-generation-apple-tv-hits-1-0/)
- [iDownloadBlog: tvOS 9.2 released](https://www.idownloadblog.com/2016/03/21/tvos-9-2-released/)
- [Apple: FaceTime links on the web](https://support.apple.com/HT204289)
- [MDN: getUserMedia needs a secure context](https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia)
- [Android: Spatializer](https://developer.android.com/reference/android/media/Spatializer)
