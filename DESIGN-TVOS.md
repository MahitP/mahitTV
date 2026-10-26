# Free My TV, tvOS edition: design spec (v3, tvOS 26/27, matched to Apple's photo)

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
| **Home layout measured off Apple's tvOS 26 press photo** (Apple TV 4K with "Prehistoric Planet 2" on the Top Shelf; screen cropped and scaled to 1920×1080): Dock of **6** tiles **251×150** (5:3), **49** apart, **85** px margins; glass x 35–1885, y 782–1010 (≈ 40–50 px padding); focus **1.23×** centred (308×185), no lift; chevron at y ≈ 718; next row's tops peeking at y = 1052; title logo ≈ 313×183 at (65, 45); status pill x 1585–1875, y 48–105, time + glyphs + a ≈ 50 px avatar | measured here (`tools`-free: PIL on the photo) | high (±3 px) |
| tvOS 26 profiles: an avatar in the top-right pill; switching in Control Center | the photo; Apple newsroom tvOS 26 (profiles on wake, enhanced switching) | high |

## 1. Layout (v3: Apple's photo, 1:1)

```
 y=0   ┌───────────────────────────────────────────────────────────────────┐
  44   │ ┌──────────┐                               ╭──────────────────╮     │
       │ │  TITLE   │  logo ≤ 380×200 at (64, 44)   │ 9:41 AM     (◕‿◕) │     │  pill x 1585–1875, y 48–105
       │ │  LOGO    │                               ╰──────────────────╯     │
       │ └──────────┘                                                       │
       │                     (the art, clean: no text over it)              │
 718   │                                   ⌃                                │  chevron (only with a pick)
 782   │╭──────────────────────────── Liquid Glass Dock ──────────────────╮ │
 822   ││ [251×150] [251×150] [251×150] [251×150] [251×150] [251×150]      │ │  6 apps, 49 apart, x 85–1835
 972   ││                                                                   │ │
1010   │╰───────────────────────────────────────────────────────────────────╯ │
1052   │  ▔▔▔▔▔▔▔   ▔▔▔▔▔▔▔   ▔▔▔▔▔▔▔   ▔▔▔▔▔▔▔   ▔▔▔▔▔▔▔   ▔▔▔▔▔▔▔         │  the grid's first row peeks (28 px)
1080   └───────────────────────────────────────────────────────────────────┘
   ↑ from the Dock (on a pick): full screen — the Dock slides away; details appear at the bottom left.
   ↓ from the Dock: the page glides up 722 (Dock to y = 60), the art frosts and dims, 6-column grid below.
```

| Element | Spec | Android | Web |
|---|---|---|---|
| Tile | **251×150** (5:3), radius **24** | `TV_TILE_W/H/RADIUS` | `.tile` |
| Dock | 6 tiles, 49 apart, x 85–1835, top 822; glass x 37–1883, y 782–1010 (padding 48 × 40), radius **64**; > 6 apps scroll | `layoutShelf` | `#platter` |
| Focus | **1.23×** centred, no lift, `Spring(0.32, 0.72)`; no name under Dock tiles (as in the photo); names under focused grid tiles only | `FOCUS_GROW` | `scale(1.23)` |
| Chevron | a thin ⌃ 30×12, white 75%, centred, y 718; only when the focused app's pick is showing | `drawChevron` | `#chevron` |
| Grid | 6 columns on the Dock's columns; rows **250** apart; at rest the first row's tops peek at y = **1052**; ↓ lifts **722**: Dock at y 60, row 1 at y 330 | `GRID_*`, `TV_GRID_LIFT` | `#apps` (always shown) |
| Title logo | top left, fitted in **380×200** at (64, 44); text fallback Title1 76 semibold, ≤ 2 lines, 700 wide | `drawShelfText` | `#hero .logo` |
| Full screen (↑) | the Dock and the grid peek slide down 300 and fade; at the bottom left (bottom at y 1000): metadata + badges, synopsis (2 lines, 860), ▶ Play; dots bottom right; ↓ or Back returns | `Z_HERO` | `body.full` |
| Status pill | x right 1875, y 48, 57 tall, radius 28.5, **smoke glass** (tint 0.42) with the rim; time **28 semibold** white, 24 from the left; avatar **49** round, inset 4 at the right end; focusable (↑ from the Dock or the Top Shelf): white, ink, 1.06×; OK opens Control Center | `drawPill` | `#pill` |

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

**Focus:** 1.23× (measured) with overshoot; shadows: ambient (blur 44, y 18, black 26%/42% night) + key (blur 16, y 34·f,
black 30%/45%); **tilt**: a 3D sway toward the direction of travel (rotateY ±5°, decaying spring 0.5 s) with the emblem
shifting 7 px and the gloss band 18 px the other way (parallax); the one-time **glint** sweep (600 ms); the name
fades in centred 22 px under the tile (Caption1 25 → **26 semibold**, white with a shadow, ink on light).

Brand backdrops (top → bottom, logo mode): Netflix `#141414→#000` as is; YouTube `#202020→#0B0B0B` as is; Prime Video
`#1FA2FF→#0A62C9` white; Disney+ `#1A3E9A→#0A1854` white; Max `#5B2EEA→#1F1477` white; Apple TV `#2C2C2E→#000` white;
Hulu `#1CE783→#0E9F57` dark; Paramount+ `#0064FF→#00287F` white; Peacock `#1C1C1E→#000` as is; Spotify `#1ED760→#118A3E`
dark; Plex `#282A2D→#121315` as is; Crunchyroll `#FF8A1F→#E8590C` white; Settings: graphite `#8E8E93→#48484A` with a
white gear.

## 4. Control Center (opened from the pill)

- A floating Liquid Glass panel on the right: x 1340–1860, y 60–1020, radius 44, slides in 80 px with a fade; the
  page dims 18% (night 30%).
- Inside (padding 28): **Profiles** — the avatars (84 round) with names (Caption2) in a row, the current one ringed
  (3 px white); OK switches. Then the **bento**, 2 columns of 224×120, 16 apart, radius 26 (Sleep Timer, Live TV, the
  inputs, All Apps, Home Settings, TV Settings…), then the weather card (464×210) at the bottom.
- Cards: glass; focused white with ink, 1.06×. Arrows move in the grid (the profiles are its top row); Back or ← from
  the left column closes it.

## 5. Backgrounds (as tvOS does it)

- **The focused Dock app's Top Shelf:** when that app has a pick, its art fills the screen, sharp behind the glass,
  with its logo top left. More than one pick for it: they page every 8 s.
- **Otherwise, a wallpaper:** your own photo (Photos) when you sent one, else one of the bundled **aerial-style**
  wallpapers (`app/assets/aerials/*.jpg`, generated: a valley at golden hour, a coastline from above, the Earth at
  dawn, an aurora of glass), a new one each hour, cross-faded. No text over it. This replaces the old big clock.
- **The grid open:** the background frosts (its 192×108 copy stretched) and dims (day: white 0.55; night: black 0.6).

## 6. Profiles

- `profiles=` in config.properties: `Name:avatar.png` pairs, comma separated (`profiles=Mahit:memoji.png,Guest:default-3.png`).
  Avatars are files in **`app/assets/avatars/`** (both builds take them from there). Drop **`memoji.png`** there to
  use your own Memoji. Empty: one profile, avatar `memoji.png` when present, else `default-1.png`.
- Avatar backgrounds use **only Apple's 18 Contacts/Memoji gradients** (top → bottom, in Apple's order), measured
  off an iOS screenshot of the picker (Display P3 → sRGB) and kept in `tools/make_wallpapers.py`
  (`APPLE_AVATAR_GRADIENTS`); they match the values published by jack.bio's iOS monogram creator to a few steps,
  except the grey, whose top is lighter on the device. No glow, rim or ring: the Memoji on the plain gradient, as Apple draws it.
- The current profile is remembered on the TV (Prefs); its avatar shows in the pill. Profiles hold the name and the
  avatar for now (per-profile Docks would come later).

## 7. Icons: the automatic Liquid Glass composer

Every app's tile is composed like a tvOS icon (§3 layers), whatever the app ships:

1. **Known brands** (`TILE_STYLE`): the brand backdrop + the logo lifted off the app's banner.
2. **A TV banner with a flat background** (most): the background colour is read off the banner's border; the logo is
   lifted off it; the tile is that colour as a rich gradient (top-left lighter 12%, bottom-right darker 28%, a
   radial lift) with the logo centred and its soft shadow.
3. **A TV banner that is a picture:** cropped (never stretched) to 5:3, with the depth, gloss and rim on top.
4. **Only a launcher icon:** an adaptive icon's background layer becomes the backdrop and its foreground the emblem;
   a legacy icon is centred at 58% of the tile's height on a gradient of its dominant vivid colour (a hue histogram
   weighted by saturation), with a soft shadow.

Then the depth, the gloss band and the 1 px specular rim on all of them.

## 8. Preview builds

`preview=true` (Android) / `--preview` (web): five generated 1920×1080 photos and sample titles with logos and badges
(`preview/`), and on the web the sample apps get their bundled logos (`web/icons`) so the Dock looks as it will on a TV.
Release builds never carry them.
