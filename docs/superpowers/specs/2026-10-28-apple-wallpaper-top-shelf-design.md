# Apple Wallpaper Engine and App-Driven Top Shelf Design

Date: 2026-10-28

Status: Approved for implementation planning

Roadmap: `TODO.md`, Section 2

## Objective

Make the TV tab rest on a clean, profile-specific Apple-inspired background and show media artwork only when the user is deliberately browsing a media app. The Android TV implementation and ES5 web mirror must expose the same choices, focus behavior, transition timing, fallback rules, and preview states.

This phase must not add controls or visual clutter to the TV or Home tabs. It does not change localization outside the new wallpaper strings, Welly, profiles, the Home diorama, or the existing six-column navigation model.

## Experience Rules

### Default state

The TV tab uses the active profile's selected Apple wallpaper when focus is on:

- the `TV | Home | Welly` top navigation;
- Settings or another utility/non-media Dock tile;
- the app grid; or
- any state without a resolvable media selection.

The default is `Apple Frosted Horizon / Auto Sunset`. In this state, the Top Shelf title logo, metadata, synopsis, buttons, and movie artwork are all hidden. No Apple logo or `tv` wordmark is drawn into the background.

### Media state

Focusing a recognized streaming/media app in the six-column Dock activates that app's Top Shelf context. The controller chooses:

1. the newest entry in the active profile's `WatchLog` belonging to that app;
2. that app's featured recommendation if no usable history entry exists; or
3. the selected Apple wallpaper if neither source yields usable artwork.

The media context remains active when the user presses Up from that Dock tile into its Top Shelf. It ends immediately when focus moves to the top navigation, a utility tile, Settings, or the app grid.

Apple Music uses active `NowPlaying` album artwork when available. Without active playback, it returns to the profile wallpaper rather than inventing a movie recommendation.

### Transitions

Default and media sources cross-dissolve over 700 ms using the existing double-buffered background path. Transitions are interruptible: a new focus target begins from the currently visible composite rather than waiting for the old animation to finish or flashing through an intermediate frame.

With Reduce Motion enabled, the source changes immediately. The setting does not remove essential legibility treatments or the procedural background itself.

## Wallpaper Model

Each profile stores a stable wallpaper identifier instead of a filename:

- `horizon:auto`, `horizon:light`, `horizon:dark`
- `aerial:yosemite`, `aerial:iceland`, `aerial:north-america-aurora`, `aerial:palau-coral`, `aerial:new-york`, `aerial:sonoma-coast`
- `studio:black`, `studio:space-gray`, `studio:midnight-blue`, `studio:deep-forest`, `studio:teal`, `studio:warm-stone`, `studio:silver-mist`
- `live:aerial`

An empty value, a legacy bundled-aerial filename, or an unknown identifier migrates to `horizon:auto`. Wallpaper preferences remain isolated by profile. Guest behavior continues to follow the existing ephemeral-profile rules.

`horizon:auto` uses the existing South Brunswick weather/sun data when available and the local `America/New_York` clock as its offline fallback. It selects the light palette during daylight and the dark palette after sunset. Light and Dark selections remain fixed.

## Frosted Horizon Renderer

Frosted Horizon is generated procedurally on both platforms from the same normalized geometry, color stops, and compositing order. It is not a raster wallpaper and never depends on network access.

The Light palette uses:

- upper sky: `#B8C7D6`
- left horizon glow: `#F5EBD6`
- center diffusion: `#DCE6EF`
- lower-right marine blue: `#7E9BB5`

The Dark palette uses:

- upper sky: `#0D131F`
- twilight horizon: `#1C2638`
- lower-right abyss: `#080B12`

The image is composed as a base vertical atmosphere plus broad, low-frequency radial fields: warm light entering from the lower-left horizon, a diffuse center veil, and a deeper marine/obsidian field weighted toward the lower-right. Gradients extend beyond the viewport so no circular boundaries appear. A restrained final vignette and very low-amplitude deterministic dither prevent visible banding without adding particles or motion.

Android implements the renderer in the existing shader/background pipeline and caches size/theme-dependent shader inputs. The web mirror uses layered ES5-compatible CSS gradients with identical normalized centers and stops. Neither implementation creates per-frame objects for a static Horizon frame.

## Background Controller

A small state controller separates focus policy from rendering. Its logical output is one of:

- `PROFILE_WALLPAPER`
- `MEDIA_ARTWORK`
- `LIVE_AERIAL`

The controller receives the active profile, tab/focus zone, focused app identity, Reduce Motion state, `WatchLog`, recommendations, and `NowPlaying`. It owns cancellation/generation tokens so late artwork or network responses cannot replace a newer focus selection.

Android and web share the same media-app classification rules. A tile is media-capable only when it has an explicit media mapping; arbitrary installed apps do not become media apps merely because they expose a banner.

The WatchLog artwork resolver belongs beside the existing recommendation/TMDB data layer, not inside the view. It first reuses known picks and cached images, then resolves the stored media identifier through the existing detail path. Failed, incomplete, or stale entries fall through to the app's featured pick. Background controllers do not issue duplicate TMDB requests.

## Official Aerial Delivery and Cache

The curated Aerial choices use Apple-hosted media only. The catalogue contains pinned records for the six approved selections and an HTTPS hostname allowlist. The implementation treats Apple's delivery feed as an undocumented external dependency that may change; it never blocks navigation or startup on that service.

On Android:

- the selected aerial and the most recently used fallback are retained;
- the disk cache uses a 1 GB LRU ceiling;
- downloads are atomic and validated for successful status, expected media type, and sane size before activation;
- partial or invalid files are discarded; and
- any missing, offline, unsupported, or failed source falls back to Frosted Horizon.

On webOS/Tizen/browser preview:

- aerial media streams from the same allowlisted Apple source;
- the platform HTTP cache is used where supported;
- no large IndexedDB video cache is introduced because legacy TV engines cannot provide reliable eviction or playback from chunked storage; and
- startup, autoplay, decode, or network failure falls back to Frosted Horizon.

Live Aerial is always muted, looping, and decorative. It must not steal remote focus, expose native video controls, or continue decoding when obscured by media artwork or another tab. A still Aerial selection uses the curated asset's representative frame; Live Aerial uses its compatible SDR stream.

## Settings Picker

The existing Wallpaper row opens a dedicated two-pane picker rather than cycling filenames inline.

The left pane contains exactly four categories:

1. Apple Frosted Horizon
2. Apple 4K Aerials
3. Apple Studio Solids
4. Live Aerial Screensaver

The right pane shows the options within the selected category and a large visual preview. Horizon exposes Light, Dark, and Auto Sunset. Aerials expose Yosemite, Iceland, North America Aurora, Palau Coral, New York, and Sonoma Coast. Studio exposes the seven specified colors. Live Aerial exposes the single live-background choice plus its availability/fallback status.

Selections preview and persist immediately for the active profile. Back returns to the main Settings list without reverting. Focus remains predictable: Left/Right moves between panes or option controls, Up/Down moves within a pane, OK selects, and Back returns one level. Missing aerial media does not disable the setting; the preview clearly falls back to Horizon while acquisition is pending or unavailable.

The picker uses the existing Settings visual language, focus lift, safe areas, and glass hierarchy. It adds no new controls to the main TV view.

## Localization

All user-visible category, option, mode, availability, and fallback strings are added to the English catalogue and all 25 companion catalogues, preserving exact key parity. Proper place names remain official names where localization is inappropriate. This phase does not perform the deferred general localization sweep.

## Platform Integration

### Android

The planned implementation touches the requested surfaces and narrowly related owners:

- `HomeView.java`: focus policy integration, hero visibility, source requests
- `Shaders.java` and `Scene.java`: procedural Horizon and source cross-fade
- `Prefs.java`: stable wallpaper IDs and migration
- `SettingsActivity.java`: two-pane picker
- recommendation/WatchLog data layer: narrow history-to-artwork resolver
- host/view layer as required for a muted video surface behind `HomeView`

Canvas drawing must preserve the project's zero-allocation `onDraw` rule. Network and bitmap work stays off the UI thread. Video lifetime follows visibility and activity lifecycle.

### Web

The planned implementation touches:

- `web/home.js`: shared focus policy, wallpaper IDs, resolver orchestration, video lifetime
- `web/home.css`: exact procedural Horizon geometry and 700 ms cross-fade
- `preview/preview.js`: deterministic preview data and capture modes
- the existing web data/prefs module where stable IDs and WatchLog resolution belong

JavaScript remains ES5 and avoids banned APIs. The DOM keeps at most the two background sources needed for an interruptible cross-fade. Decorative video is muted, non-interactive, and removed or paused when inactive.

## Failure and Edge Cases

- A late response for an old app focus is ignored.
- A WatchLog entry without usable art falls through to the app pick.
- A focused media app with no history, recommendation, or Now Playing uses the profile wallpaper with no hero clutter.
- Offline mode always retains Frosted Horizon and cached media already validated.
- Profile switching recomputes both wallpaper and WatchLog context before rendering.
- Guest history remains empty and is never used after exit.
- Unsupported video codecs, autoplay rejection, and corrupt cache entries fall back without modal errors.
- Rapid navigation cannot flash the prior profile's art or metadata.
- Default background screenshots are deterministic and do not depend on live weather or network responses.

## Verification

Implementation is complete only after:

- focused tests cover ID migration, focus-zone policy, per-app WatchLog choice, app fallback, stale-response rejection, theme/sunset selection, and Reduce Motion;
- Android and web use the same normalized Horizon fixtures and expected colors;
- `./build.sh` passes;
- `./tools/check.sh` passes, including all 26 localization catalogues;
- generated web output preserves the intended script/style behavior;
- deterministic 1920x1080 captures are produced for:
  - `previews/web-tv-default-apple-bg.png`
  - `previews/web-tv-dock-app-continue-watching.png`
  - `previews/web-settings-apple-wallpapers.png`
- the screenshots are visually inspected and opened on macOS;
- only after all checks pass are the three Section 2 tasks in `TODO.md` marked complete.

## Out of Scope

- generic stock wallpapers or third-party movie backgrounds as defaults
- an Apple logo or `tv` wordmark in Frosted Horizon
- new Home-tab controls, scenes, or weather particles
- proactive Welly behavior
- a general localization sweep
- additional screensaver modes from later roadmap phases
- bundling large Aerial videos in Git or the APK
