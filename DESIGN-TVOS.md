# Free My TV, tvOS edition: design spec

The home screen restyled after Apple TV's (tvOS 26, Liquid Glass): a full-bleed **Top Shelf** of what to watch, one
row of apps on a glass **Dock**, the **app grid** below it, and a quiet glass pill with the time and weather in the
corner. Everything below is in the 1920×1080 design space (`k = width / 1920` on Android; the `#stage` on the web).
Each section names where it lives in the code: Android first, the LG/Samsung web copy after.

## 1. Layout and hierarchy

```
 y=0  ┌──────────────────────────────────────────────────────────────────────┐
      │                                     ( 9:41  ☀ 21° )  (⏻) (⧉) (⚙)   │  status pill + round buttons
      │                                                                      │
      │   TONIGHT                                                            │  Top Shelf: the pick's photo,
      │   Neon Meridian                                                      │  edge to edge, behind everything
      │   [★ 8.1]  2026 · Sci-Fi · 2h 4m · Apple TV                          │
      │   A courier crosses a drowned city to deliver one last message…      │
      │   ● ○ ○                                                              │  page dots
 y=734│  ╭──────────────────────────── glass dock ─────────────────────────╮ │
      │  │ [ NETFLIX ] [ YouTube ] [ prime ] [ Disney+ ] [ max ]            │ │  5 apps, 300×180 (5:3)
      │  ╰──────────────────────────────────────────────────────────────────╯ │
      │               Netflix   ← label under the focused app only            │
 y=1080└──────────────────────────────────────────────────────────────────────┘
          ↓ from the Dock: everything rises 600 px, the photo dims and the app grid comes up
```

| Element | Spec | Android | Web |
|---|---|---|---|
| Safe area | 90 px sides, 60 top/bottom | `TV_MARGIN` | `#stage` paddings |
| **Top Shelf backdrop** | The pick's photo at full UI size; scrims: left (black 55%→0 over 70% of the width), bottom (black 60%→0 over the lower 55%), top (25%→0 over 22%) | `Backdrop.make(…, forLight=false)`, `drawTvSky` | `#hero .photo`, `.scrim` |
| Period label | "TONIGHT", 22 px semibold, tracking +12%, white 70% | `drawHero` | `#hero .label` |
| Title | 84 px Inter Display bold, tracking −1.5%, up to 2 lines, 1000 px wide, baseline 548 | `tvTitle`, `Hero.titleLayout` | `#hero h1` |
| Metadata | Rating badge (glass pill, ★ + score), then year · genre · runtime · app, 26 px medium, white 85% | `drawHeroMeta` | `#hero .meta` |
| Overview | 26 px regular, white 72%, 2 lines max, 900 px wide | `drawHero` | `#hero .overview` |
| Page dots | 8 px circles, 16 apart, white 100% (current) / 40% | `drawHero` | `#hero .dots` |
| **Dock** | One Liquid Glass platter: 5 tiles of 300×180 (5:3, tvOS's own icon shape; `TV_TILE_H` changes it), 40 apart, 26 padding, glass radius 56 (concentric with the tiles' 30); more apps scroll | `layoutShelf`, `drawPlatter` | `#platter`, `#tiles` |
| **App grid** | The other apps, 5 columns on the Dock's grid, rows 250 apart; ↓ from the Dock lifts the page 600 px (spring), the photo dims to 35% (white veil by day), Up from the first row comes back | `drawSheet` (inline), `updateMotion` | `#apps.grid`, `body.grid-open` |
| **Status pill** | Glass capsule 64 tall: time (30 px semibold, tabular), weather symbol, temperature; right-aligned next to the round buttons | `drawStatusPill` | `#pill` |
| Round buttons | 64 px glass circles (Live TV, HDMI Inputs = Control Center, Settings); focused: white disc, dark glyph, name below | `drawLightButtons` | `button.round` |
| Control Center | The bottom drawer (inputs, shortcuts, weather), opened by *HDMI Inputs* | `drawDrawer` | `#drawer` |

**Focus order:** Dock (start) ↑ Top Shelf (when there are picks: ← → pages, OK opens the title in its app) ↑ round
buttons. Dock ↓ app grid. Back returns to the Dock. The Top Shelf pages by itself every 8 s while the focus is
elsewhere, and focusing a Dock app whose title is among the picks brings that title up (tvOS's per-app Top Shelf).

## 2. Materials: Liquid Glass

| Property | Spec | Android (`Shaders.LIQUID`, `Scene.liquid`) | Web |
|---|---|---|---|
| Body | The photo behind, lightly blurred, saturation ×1.25, brightness ×1.04: the color bleeds through | `bgAt` + saturation in `LIQUID` | pre-blurred `canvas` copy of the photo, clipped to the shape |
| Tint | Day: white 30%; night: smoke `#111113` 14% | `drawPlatter` | `.glass` / `.night .glass` |
| Refraction | A 30 px thick rim bends the image 24 px outward, with 22% color dispersion | `band`, `refraction` | (static highlight gradient; old Chromium has no backdrop-filter) |
| Specular rim | 1 px inner highlight, 70% where it faces the light (top left), 15% opposite; soft 11 px inner halo | `rim`, `halo` in `LIQUID` | `box-shadow: inset 0 1px 0 rgba(255,255,255,.6), inset 0 -1px 0 rgba(255,255,255,.12)` |
| Outer hairline | 1 px `#FFFFFF` 12% | `flatRim`, `LIQUID` edge | `border: 1px solid rgba(255,255,255,.14)` |
| Corners | Continuous-curvature ("squircle") corners: the shape's distance uses a superellipse norm (exponent 2.6) instead of a circle | `sdSquircle` in `LIQUID` / `GLASS` | `border-radius` (the closest CSS has) |
| Concentric radii | glass radius = inner radius + padding (Dock 56 = 30 + 26; pill 32 = height / 2) | `TV_TILE_RADIUS + TV_DOCK_PAD` | same numbers |
| Android 8–12 | `Scene.bitmapGlass`: the soft copy of the photo, the same tint, the 1 px rim and the top sheen | `bitmapGlass` | — |

## 3. Tiles and focus physics

| Property | Spec | Android | Web |
|---|---|---|---|
| Shape | 5:3, radius 30, the brand's color top to bottom (`TILE_STYLE`) with its logo; apps without a style show their TV banner, cropped (never stretched) | `drawShelfTile`, `roundImage` | `.tile` |
| Focus scale | **1.10×**, spring response 0.30 s, damping 0.72 (a slight overshoot) | `TV_FOCUS_GROW`, `Spring(0.30f, 0.72f)` | `transform: scale(1.1)`, `cubic-bezier(.2,.9,.3,1.18)` .32 s |
| Resting shadow | y 6, blur 18, black 18% (night 30%) | `shadowDeep` | `box-shadow: 0 6px 18px` |
| Focused shadow | y 30, blur 44, black 34% by day, 55% at night: it "lifts" toward you | `drawShelfTile` | `.tile.focus` |
| Specular glint | On focus, a diagonal white band (22% opacity, 30% of the width) sweeps once across the tile in 600 ms, clipped to it; one shader and one matrix reused, nothing allocated while drawing | `glintAt[]`, `tileGlint` + `glintMatrix` | `.tile .glint` animated with `transform` |
| Top sheen | Static white 12%→0 over the top half, fading in with the focus | `tileGlow` | `.tile::after` |
| Parallax | The logo slides 5 px toward the last D-pad direction while focused | `drawLogo` offset | `.tile.focus img` translate |
| Label | The app's name, 26 px semibold, white (dark on the light grid), 20 px under the focused tile; fades with the focus spring; resting tiles have none | `dockLabels[]` | `.tile .name` |

## 4. Typography and micro-UI

- **Inter** (bundled, OFL) stands in for SF Pro: Display cut for the 84 px title (700, −1.5%), text cut for
  everything else (500/600), tabular digits for the time. No other fonts.
- Ink: night `#F5F5F7` with 60% secondary; day `#1D1D1F` with `#6E6E73` secondary. Over the photo, text is always
  white with a soft shadow (y 2, blur 22, black 35%).
- Badges are pills: radius = height / 2, the same glass as the Dock.
- Nothing else on screen: no title bar, no tabs, no prices (`show_crypto=false` by default).

## 5. Without picks (no TMDB key, or offline)

The Top Shelf becomes a clock: the date (34 px semibold), the time (200 px light) and the weather line, over your own
photo when you sent one (`Photos`), else the light (day) or dark (night) gradient. The Dock and the grid work the
same.

## 6. Preview builds

`preview=true` in `config.properties` (Android) or `python3 webos/build.py --preview` (web) bundles five generated
1920×1080 "cinematic" photos and sample titles from `preview/` instead of TMDB, so the Top Shelf, the scrims and the
glass's color bleed can be judged before a TMDB key is set. Release builds never carry them.
