# Free My TV, tvOS edition: design spec (v2, tvOS 26/27)

The home screen after Apple TV's (tvOS 26 introduced Liquid Glass on Apple TV; tvOS 27, released 14 Sep 2026, kept
it and focused on speed). Everything is in the 1920×1080 design space (`k = width / 1920` on Android; the `#stage` on
the web). Each line names where it lives: Android first, the LG/Samsung web copy after.

## 0. Sources and confidence

| Fact | Source | Confidence |
|---|---|---|
| Safe area 80 px sides, 60 px top/bottom | Apple HIG (tvOS Layout), as distilled in tvhome's `docs/HIG-tvOS.md` | high |
| Grid: 5 columns × **320** wide, **40** apart, ≥ **100** between rows (6 col: 260, 7: 217…) | same | high |
| App icon art 400×240 pt (800×480 @2x), 2–5 layers, opaque back layer, system adds light and shadow | Apple HIG Images; akring's tvOS icon guide | high |
| Type: Title1 76/96 Medium, Title2 57/66, Title3 48/56, Headline 38/46 Medium, Callout 31/38 Medium, Body 29/36 Medium, Caption1 25/32 Medium, Caption2 23/30 Medium; SF Text < 40 pt, SF Display ≥ 40 | Apple HIG Typography (tvOS) | high |
| Top Shelf: inset banner 1740×620 focused; static image 1920×720 | Apple HIG Top Shelf | high |
| Control Center: hold the TV button; a glass panel slides in **from the right**, tabs (profile, controls, Home…), the video stays visible | Apple Support "Navigate Control Center"; Apple newsroom tvOS 26 | high |
| Liquid Glass on Apple TV 4K (2nd gen+) in the Dock, Control Center, Top Shelf, scrubber; parallax "almost unnoticeable" | Apple newsroom; AppleInsider tvOS 26 review | high |
| 27-generation glass: less default transparency, more contrast, revamped icons | reported for iOS/iPadOS/macOS 27; **not confirmed for tvOS 27** | medium |
| Focus ≈ 1.15× (400×240 art shown on a 320×192 tile at rest) | derived from the two rows above | medium |
| Squircle corners: superellipse, exponent 4 | Apple's continuous corners are close to n ≈ 4–5; chosen | medium |

## 1. Layout

```
 y=0   ┌───────────────────────────────────────────────────────────────────┐
  60   │                                            9:41  ☾ 44°   (◉)       │  status: plain text + one glass button
       │                                                                   │
       │  [ TITLE LOGO / Display title ]                                   │  Top Shelf: the pick's art edge to edge
       │  Sci-Fi · 2026 · 2h 4m   [PG-13] [4K] [Dolby Vision] [CC]          │
       │  A courier crosses a drowned city to deliver one last message…    │
       │  ( ▶ Play )                                       ● ○ ○ ○ ○        │
 688   │ ╭───────────────────────── Liquid Glass Dock ─────────────────────╮│
 720   │ │ [320×192] [320×192] [320×192] [320×192] [320×192]               ││  5 apps
 912   │ ╰─────────────────────────────────────────────────────────────────╯│
 980   │        Netflix  ← the focused app's name, centred under it          │
1080   └───────────────────────────────────────────────────────────────────┘
          ↓ from the Dock: the whole page glides up 640 px (Dock to y=80), the art blurs and dims, the grid follows
```

| Element | Spec | Android | Web |
|---|---|---|---|
| Safe area | `TV_MARGIN` 80, top 60 | `HomeView` | `#stage` |
| Tile | **320×192**, radius **28** (squircle-looking via the glass rim; the tile itself `drawRoundRect`) | `TV_TILE_W/H/RADIUS` | `.tile` |
| Dock | 5 tiles + 4×40 = 1760 wide, padding **32** (so a 1.15× end tile stays inside), glass radius **60** (28 + 32), platter x 48–1872, y 688–944, tiles' top at **720**; > 5 apps scroll | `layoutShelf` | `#platter`, `#tiles` |
| Focus | **1.15×**, `Spring(0.32, 0.70)` (≈ 6% overshoot), lifted 6 px | `FOCUS_GROW` | `scale(1.15)`, `cubic-bezier(.2,.9,.25,1.22)` .34 s |
| Grid | the other apps on the Dock's 5 columns, rows **292** apart (192 + 100); ↓ lifts the page **640** so the Dock rests at y = 80 and row 1 at y = 372 | `GRID_*`, `TV_GRID_LIFT` | `#apps`, `GRID_*` |
| Top Shelf title | the title's **logo** (TMDB `logos`, transparent PNG) max 760×240 bottom-left; without one, the title in **Title1 76** semibold, −1% tracking, ≤ 2 lines | `drawShelfText` | `#hero .logo`, `h1` |
| Metadata | Callout 31 → **29 Medium**, white 80%: genre · year · runtime/seasons · app | | `.meta` |
| Badges | after the metadata, 6 apart: rounded-rect **outline** badges, height 30, radius 7, Caption2 **21 semibold**, white 85%, 1.5 px white 55% border: `PG-13`/`TV-MA` (TMDB certification), `★ 8.1`; preview samples also `4K`, `Dolby Vision`, `Dolby Atmos`, `CC` | `drawBadges` | `.badge` |
| Synopsis | Body 29 → **26 regular**, white 72%, 2 lines, 860 wide | | `.overview` |
| Play | capsule 60 tall, padding 34, Headline **28 semibold**: glass at rest, **white with ink text** focused (1.08×); "▶ Play" | `heroButton` | `#watch` |
| Page dots | 8 px, 14 apart, right-aligned to x = 1840 on the button's centre line | | `#dots` |
| Status (top right) | the time **Body 29 semibold** white (ink on the plain light gradient) with a soft shadow, the weather glyph + temperature, then **one** 60 px glass circle: Control Center. Nothing else (Live TV, inputs, settings live in Control Center); the Top Buttons setting can still add Live TV / Settings | `drawStatusText`, `drawLightButtons` | `#status`, `.round` |
| Art scrims | left 0.55 → 0 over 62% of the width, bottom 0.65 → 0 over the lower 55%, top 0.22 → 0 over 18% | `Backdrop` | `.scrim` |

## 2. Liquid Glass (27-generation)

Shader math (`Shaders.LIQUID`, mirrored in `Scene.bitmapGlass` and CSS where possible):

- **Shape:** superellipse corners, `corner = (|qx|^4 + |qy|^4)^(1/4)`, `sd = corner + min(max(qx,qy),0) − r` (`SQUIRCLE_N = 4`).
- **Lensing:** a rim band `band = 34 px`; `bend = (1 − e)^2.2` with `e = clamp(inner / band)`; sample offset
  `−n · bend · 26 px`; chromatic dispersion `±0.28 · offset` on R and B.
- **Vibrancy:** `bg = mix(luma, bg, 1.35) · 1.02`; then tint `mix(bg, tint, a)` with a day 0.24 / night 0.18 (27's
  "less transparent" default sits between the old 0.30 and 0.14), (a contrast floor for text-bearing glass was planned for the pill, which was
  dropped: the status is plain text with a shadow).
- **Light:** key light from the top left `L = normalize(−0.45, −0.89)`. Specular rim: `rim = 1 − smoothstep(0, 2.2, inner)`;
  `spec = rim · (0.18 + 0.82 · facing^1.5 + 0.35 · back)` + inner halo `(1 − smoothstep(0, 14, inner)) · (0.14 · facing + 0.06 · back)`,
  i.e. a bright catchlight at the top-left corner fading along the top and left edges, a faint bounce at the bottom
  right. A top sheen `+0.05` over the upper 40%, a bottom shade `−10%`.
- **Fallback (Android 8–12):** the soft copy baked with saturation 1.35, the cached squircle path (exponent 4), tint floor
  0.24, a diagonal rim: top-left stroke gradient (`0xC0` → `0x18` along the top-left → bottom-right diagonal) plus the
  bottom echo.
- **Web:** no backdrop-filter on old TVs: a tiny stretched copy of the art (`canvas`, CSS `saturate` not allowed on old
  Chromium → baked when drawing the canvas), the tint, and the rim as `inset 1.5px 1.5px 0 rgba(255,255,255,.75),
  inset -1px -1px 0 rgba(255,255,255,.16)` with a top-left radial catchlight pseudo-element.

## 3. App icons: layered glass tiles, focus physics

Each tile is drawn as tvOS composes a layered icon (no baked shadows; light added at run time):

1. **Back layer:** the brand's backdrop (gradient, `TILE_STYLE`), or the app's own TV banner, cropped to 5:3.
2. **Depth:** a soft radial lift at 30% / 25% (white 10%) and a vignette toward the bottom edge (black 18%).
3. **Emblem (front layer):** the logo lifted off the banner, with its own soft drop shadow (blur 10, y 3, black 35%)
   prepared once per logo.
4. **Glass edge:** a 1.5 px inner rim, white 45% top-left → 8% bottom-right, plus a 1 px outer hairline white 10%.
5. **Gloss:** a diagonal specular band (white 14% → 0) over the top 45%.

**Focus:** 1.15× with overshoot; shadows: ambient (blur 44, y 18, black 26%/42% night) + key (blur 16, y 34·f,
black 30%/45%); **tilt**: a 3D sway toward the direction of travel (rotateY ±5°, decaying spring 0.5 s) with the emblem
shifting 7 px and the gloss band 18 px the other way (parallax); the one-time **glint** sweep (600 ms); the name
fades in centred 22 px under the tile (Caption1 25 → **26 semibold**, white with a shadow, ink on light).

Brand backdrops (top → bottom, logo mode): Netflix `#141414→#000` as is; YouTube `#202020→#0B0B0B` as is; Prime Video
`#1FA2FF→#0A62C9` white; Disney+ `#1A3E9A→#0A1854` white; Max `#5B2EEA→#1F1477` white; Apple TV `#2C2C2E→#000` white;
Hulu `#1CE783→#0E9F57` dark; Paramount+ `#0064FF→#00287F` white; Peacock `#1C1C1E→#000` as is; Spotify `#1ED760→#118A3E`
dark; Plex `#282A2D→#121315` as is; Crunchyroll `#FF8A1F→#E8590C` white; Settings: graphite `#8E8E93→#48484A` with a
white gear.

## 4. Control Center (replaces the bottom drawer)

- Opened by the Control Center button (top right) or HDMI Inputs; Back, or ← from its left column, closes it.
- A **floating vertical Liquid Glass panel** on the right: x from 1920 − 60 − **520** to 1860, y 60 → 1020, radius
  **44**; slides in 80 px from the right with a fade (spring 0.38 / 0.88); the page behind dims 18% (night 30%) and
  nothing else moves.
- Inside (padding 28): header — the time **Title2 57 light** and the date **Caption1**; then a **bento** grid, 2 columns
  of 224 wide × 120 tall, 16 apart, radius **26**: Sleep Timer, Live TV, each HDMI input, All Apps, Home Settings,
  TV Settings (and the projector shortcuts where they exist); at the bottom a full-width **Weather** card (456 × 210):
  the place, now (temperature 64 light + glyph), condition, H/L, and the next 4 hours.
- Cards: glass (tint white 0.16 / smoke 0.32), symbol 34 + label Caption2 21 semibold; focused → **white**, ink, 1.06×,
  key shadow. Arrow keys move in the 2-column grid.

## 5. Without picks

The Top Shelf becomes the clock (date Caption1, time 200 light, weather line) over your photo or the gradient.

## 6. Preview builds

`preview=true` (Android) / `--preview` (web): five generated 1920×1080 photos and sample titles with logos and badges
(`preview/`), and on the web the sample apps get their bundled logos (`web/icons`) so the Dock looks as it will on a TV.
Release builds never carry them.
