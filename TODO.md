# mahitTV Roadmap

### 0. Strict Apple Minimalism Guardrail (Applies to All Future Tasks)
- **Zero Extra Chrome on Main Views**: Never add extra button bars, floating scene pills, or noisy weather particles to the `TV` or `Home` tabs.
- **Strictly Official Apple Backgrounds Only**: Never use generic third-party stock wallpapers or unrequested movie posters as the default OS background. Only use the official Apple TV wallpaper/screensaver categories detailed in Section 2 below.
- **Rejected Ideas (Documented as Out-of-Scope)**:
  - NO visible "Home Scenes" button bar above the 3D house (scenes are triggered via Welly voice/chat only).
  - NO falling rain/snow particle overlays on the 3D house diorama (use subtle directional sunlight/sky color temperature shifts only).
  - NO noisy/frequent expansions of the top-right `9:41 AM + Memoji` status pill (only expand for user-initiated active states like Sleep Timer, active Apple Music playback, or a brief 3s Bluetooth device toast).

### 1. Immediate Pending User Inputs & Design Tweaks
- [ ] **6 Main Dock Apps (`default_dock_apps`)**: Wire in Mahit's 6 primary streaming/TV apps once provided after school.
- [ ] **Visual & Layout Tweaks**: Apply Mahit's upcoming design review tweaks across the `TV`, `Home`, `Welly`, Intro, and Lock screens.
- [ ] **On-Device 85" TCL Google TV Validation**: Sideload `FreeMyTV.apk` via ADB, test remote D-pad focus, `ACTION_SCREEN_ON` intro/lock behavior, Kasa TCP/9999 switches, and `HomeMode` Original-TV restore safety net.

### 2. Phase 1 — Authentic Apple TV Wallpaper Engine & Top Shelf "Continue Watching" Behavior
- [ ] **Replace Default Netflix/Movie Poster Background with Clean Apple TV Horizon Mesh**:
  - The default resting background of the `TV` tab (when focus is on the top menu bar `TV | Home | Welly`, on a utility app like Settings, or before selecting a streaming app) **must NOT be a Netflix/movie backdrop**.
  - Set the default background to an exact recreation of **Apple's signature tvOS Frosted Horizon Wallpaper** (matching the uploaded Apple TV reference image—soft diffused slate-blue `#B8C7D6` upper sky, warm cream/gold left horizon glow `#F5EBD6`, and muted coastal blue `#7E9BB5` lower right—with **the center `tv` apple icon and `tv` text completely removed** so it is a pure, seamless atmospheric canvas, plus its matching dark-mode twilight variant after sunset).
- [ ] **App-Driven Top Shelf ("Continue Watching" Artwork Only When Hovering an App)**:
  - Keep the clean default Apple background visible until the user focuses a specific streaming/media app in the Dock.
  - When a user focuses a streaming app in the Dock, smoothly cross-dissolve the Top Shelf backdrop to show **that profile's "Continue Watching" title for that app** (from `WatchLog`, or that app's featured media pick if nothing is in `WatchLog` for that app yet).
  - When focus leaves the media app (e.g., returning to the top menu bar, focusing Settings, or scrolling into the app grid), smoothly cross-dissolve back to the user's selected **Apple Default Background**.
- [ ] **Official Apple TV Wallpaper & Background Selector in Settings (Strictly Apple Options Only)**:
  - Restrict all background and wallpaper choices in Settings (per profile) strictly to authentic Apple TV categories—remove any non-Apple filler backgrounds:
    1. **Apple TV Signature Frosted Horizon** (Default — matches the uploaded Apple TV image without the `tv` logo, in Light, Dark, or Auto Sunset).
    2. **Apple 4K Aerial Backdrops** (Curated from Apple's 5 official Aerial categories: *Landscape* [Yosemite, Iceland, Patagonia], *Earth/Space* [North America Aurora, Sahara], *Underwater* [Palau Coral, Alaskan Jellies], *Cityscape* [New York, San Francisco, London], and *Coastal Horizon*).
    3. **Apple Studio Solid Colors & Minimal Gradients** (Official Apple system palette: *Studio Black* `#000000`, *Space Gray* `#1C1C1E`, *Midnight Blue* `#121A2A`, *Deep Forest Green* `#11221C`, *Teal* `#1B3B3A`, *Warm Stone* `#8E8E93`, and *Silver Mist* `#D1D5DB`).
    4. **Active Screensaver as Live Background** (Option to let the active Apple Aerial / Aurora screensaver run softly behind the UI).

### 3. Phase 2 — Subtle 3D Home Atmospheric Realism (`HouseView.java` & `web/rooms.js`)
- [ ] **Solar-Synced Ambient Lighting**: Modulate exterior directional light warmth and angle on the 3D clay diorama based on real-time South Brunswick, NJ time-of-day (cool morning, bright midday, warm golden-hour sunset through the South entrance, calm moonlight at night)—zero particles or clutter.
- [ ] **Living Room 3D Mini-TV Screen Sync**: Tint the black glass screen of the miniature non-interactive 3D TV inside the Living Room with the dominant color / artwork of the current Top Shelf pick or `NowPlaying` media session.

### 4. Phase 3 — Apple "StandBy" & Official tvOS Screensaver Modes (`Screensaver.java` & `web/`)
- [ ] **Left/Right Mode Switching in Screensaver**: Allow pressing Left/Right during idle screensaver to cycle between official Apple TV-inspired idle modes:
  1. **Apple 4K Aerials Showcase** (Landscape, Earth, Underwater, Cityscape with tap-to-show location captions).
  2. **StandBy / Portraits Giant Typographic Clock** (massive SF/Inter Display depth clock + minimal South Brunswick weather).
  3. **Apple Music "Sound Glow" & Live Synced Lyrics** (liquid mesh backdrop derived from `NowPlaying` album art + free no-key `LRCLIB` time-synced lyrics).
  4. **Snoozing Welly Portrait** (`WellyBot` `#2fbfa0` pebble in `sleep`/`endormi` state with white eyes, gently breathing in the corner—our custom take on Apple TV's Snoopy screensaver).

### 5. Phase 4 — Global "Peek-A-Boo" Welly & Deep Media Intelligence (`WellyView`, `WellyTools`)
- [ ] **Global Peek-A-Boo Voice Summon**: Holding the remote Mic button (or shortcut key) while on the `TV` or `Home` tab makes `WellyBot` peek up from the bottom edge of the screen with `curieux` white eyes, listen, run the tool (e.g., toggle Kasa lights or launch a title), deliver a 1-line quip, and duck back down without leaving the current tab.
- [ ] **"InSight" Cast & Soundtrack Tool**: Query live cast, character names, and soundtrack info for whatever is currently in `NowPlaying` / `WatchLog`.
- [ ] **"Catch Me Up" Spoiler-Free Recaps**: Summarize the last watched episode/season of a show in the active profile's `WatchLog` before resuming.
- [ ] **"Family Pick" Multi-Profile Matcher**: Compare `WatchLog` and genre tastes across two or more selected family profiles (e.g., `Mahit + Dhruvin` or `All Family`) to recommend 3 movies nobody has watched yet.

### 6. Phase 5 — Built-In QR-Code Web Companion Remote (`RemoteServer.java`)
- [ ] **Zero-Dependency Local HTTP/WebSocket Server on the TV**: Host a mobile-optimized Liquid Glass web remote directly from the Android TV APK on the local LAN (`http://<TV-IP>:8080`), paired via a QR code in Settings / Control Center.
- [ ] **Phone Remote Capabilities**: 60fps glass haptic touchpad, instant phone keyboard input for Welly/search, live 3D house Kasa light toggles, and silent mobile Welly chat.

### 7. Phase 6 — Pro OS Audio & Media Polish
- [ ] **Top Shelf Muted Trailer Preview**: Smoothly crossfade from static hero backdrop to trailer preview after holding `OK` or dwelling on the focused Top Shelf.
- [ ] **SmartTube Ad-Free Routing**: Automatically route YouTube trailers and video deep links to SmartTube (`com.liskovsoft.smarttubetv.beta` / `.tv`) if installed on the TCL TV, falling back to stock YouTube.
- [ ] **Pitched Harmonic UI Micro-Ticks**: Subtle ascending harmonic pitch variation (`SoundPool` / Web Audio) across the 6 Dock columns and Bluetooth controller connection toast.
