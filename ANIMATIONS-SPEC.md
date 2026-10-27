# Free My TV — motion specification

Every interaction across the three tabs (TV, Home, Welly) is driven by a spring: focus, press, dead ends, tabs,
sheets, the Home camera, light toggles, Welly's replies. Nothing snaps, jumps or eases on a fixed curve, except short
exit fades, which Apple keeps brief on purpose. The Android app (`HomeView`, `HouseView`, `WellyView`, Canvas + AGSL)
and the LG/Samsung web app (`web/`) share one engine and one table of presets, so a tile hop or a tab swap feels the
same on both. 60 fps, no allocation per frame, no frames at all when nothing moves.

Sources: the apple-design-motion and apple-design-interaction skills (spring parameterisation, the asymmetry rule,
stagger budgets, lead/follower choreography, the frequency penalty, reduce motion), UIScrollView's rubber band
(measured against iOS 26), tvOS layered-image focus (back/middle/front layers moving at different rates, a live edge
highlight). Claims about what Apple does are tagged in the skills; where Apple documents nothing (a tvOS press or
dead-end animation), this spec follows its scroll and press behaviour elsewhere.

Decisions (2026-10-26): focus stays **1.23×** (measured from the Apple TV reference photo; press squish 1.23 → 1.15);
pacing **as in the preset table**; the Welly orb **centre stage**; Home effects **as specified**; the five extra ideas
in §5 approved.

---

## 1. The engine

### 1.1 The spring

Apple's parameterisation (SwiftUI `spring(response:dampingFraction:)`, CASpringAnimation), unit mass:

```
ω  = 2π / response
k  = ω²                      stiffness
c  = 2 ζ ω = 4π ζ / response damping, ζ = dampingFraction
```

**Solver: closed form.** Each frame advances the exact solution of `x'' = −k (x − x*) − c x'` over `dt`, from the
current position and velocity, so it's exact for any frame time and needs no sub-steps:

- ζ < 1 (underdamped): `ωd = ω√(1−ζ²)`; with `x0 = x − x*`, `v0 = v`:
  `x(t) = x* + e^(−ζωt) (x0 cos ωd t + (v0 + ζω x0)/ωd · sin ωd t)`, `v(t)` its derivative.
- ζ = 1 (critical): `x(t) = x* + e^(−ωt) (x0 + (v0 + ω x0) t)`.
- ζ > 1 (overdamped): the two real roots `r1,2 = −ω (ζ ∓ √(ζ²−1))`.

**Retargeting** only changes `x*`: position and velocity carry over, so a focus that changes direction mid-flight
bends instead of restarting (100% interruptible). `impulse(Δv)` adds velocity (dead-end bumps, arrival tilt).

**Time step.** `dt = clamp(frameΔ, 0, 1/30 s)`. A longer gap (the app was in the background, the screensaver was up)
settles springs at their targets rather than jumping.

**Rest.** At rest when `|x − x*| < εx` and `|v| < εv`, with ε in the spring's own unit: 0.0005 / 0.005 for 0–1
values, 0.25 px / 2 px/s for pixels, 0.02° / 0.2°/s for angles. At rest the value equals the target and velocity is 0.

**Frame loop.** Android: one `Choreographer.FrameCallback` per view steps all its springs, then invalidates what
moved; it stops posting when everything is at rest (idle = no frames; the clock's 1 Hz tick stays). HouseView and
WellyView step their springs there too, not inside `onDraw`. Web: one `requestAnimationFrame` loop in `motion.js`,
running only while something moves.

**Animations off.** Android `ANIMATOR_DURATION_SCALE == 0` (`Spring.instant`) and web `prefers-reduced-motion`:
springs jump to their targets and the layers they drive update in the same frame; travel becomes a 150 ms crossfade;
tilt, drift, bumps, waves and breathing are off; the focus scale and the press squish stay (they carry meaning).

### 1.2 Presets (`Spring.java` constants, `Motion.P` on the web)

| Preset | response | ζ | Used for |
|---|---|---|---|
| `MICRO_PRESS` | 0.18 | 0.92 | OK press-down squish and its rebound |
| `SNAPPY_FOCUS` | 0.28 | 0.82 | focus scale, lift shadow, dot and card focus (≈ 1.5% overshoot) |
| `FAST_FOCUS` | 0.16 | 0.95 | focus while an arrow is held (fast scrub, §5.1) |
| `BOUNDARY_BUMP` | 0.24 | 0.76 | dead-end nudge and its return |
| `TILT` | 0.42 | 0.60 | arrival tilt (an impulse, settles flat) |
| `LIQUID_PILL` | 0.36 | 0.84 | tab bar indicator x/width, status pill width morph |
| `TAB_SWAP` | 0.42 | 0.90 | TV ↔ Home ↔ Welly content slide |
| `SHEET_OPEN` | 0.44 | 0.88 | Control Center, sheets opening |
| `SHEET_CLOSE` | 0.34 | 0.96 | the same closing (brisker, no bounce) |
| `SPATIAL_SCROLL` | 0.46 | 0.92 | Dock ↔ grid, grid rows, Top Shelf inspection |
| `TITLE_SETTLE` | 0.38 | 0.88 | Top Shelf title/logo entry |
| `CAMERA_ORBIT` | 0.68 | 0.94 | Home camera (yaw, pitch, zoom, centre) |
| `WALL_CUT` | 0.45 | 0.92 | Home outside walls lowering/rising |
| `ROOM_FADE` | 0.40 | 1.00 | Home rooms de-emphasis, light warmth |
| `IGNITE` | 0.25 | 0.75 | filament flash when a light turns on |
| `CARD_ENTER` | 0.28 | 0.82 | Welly cards entering (= SNAPPY_FOCUS) |
| `ORB` | 0.50 | 0.80 | Welly orb state blends and its move to the header |

Exits that only disappear are short fades, 150–220 ms (appear slower than disappear). Staggers: 20–50 ms per item,
never more than 300 ms in total.

### 1.3 Rubber band (dead ends)

A D-pad press with no neighbour that way gives the focused element (tile, tab, pill, card, dot, Control Center tile)
an impulse on its `nudge` spring (BOUNDARY_BUMP, target 0). The drawn offset passes through UIScrollView's rubber band
`f(x, d, c) = x·d·c / (d + c·x)`, `c = 0.55`, `d = 40 px`, so it tugs about 8 px and comes back; repeated presses can't
push it past the band. The impulse is **1100 px/s**: the brief's 260 px/s peaks at ≈ 4 px of raw travel, ≈ 2 px after
the band, too small to see; 1100 px/s gives the 6–10 px tug the brief asks for (computed with the solver above). The boundary sound plays on the same frame (§5.4).

---

## 2. TV tab

### 2.1 Tiles (Dock and grid)
- **Focus** `f` on SNAPPY_FOCUS: scale `1 + 0.23 f`; shadow resting (y 6, blur 18, α .18) → elevated (y 28, blur 46,
  α .48); the label under a grid tile fades in and rises 6 px with `f`, and fades out in 120 ms when focus leaves.
- **Arrival tilt:** an impulse on the tile's TILT spring leans it 3° toward the direction it was reached from, then it
  settles flat; the sheen slides the opposite way.
- **Layer parallax:** base 0 px, brand graphic 3 px, logo 6 px and 1.03× against the tilt.
- **OK:** MICRO_PRESS to 1.15× and shadow y 28 → 12 on press, back on release; the app opens on release.
- **Launch:** the app starts at once (no added delay); the tile keeps growing and fading toward full screen while the
  home behind scales to 0.95 and fades.
- **Dock → grid:** SPATIAL_SCROLL; wallpaper blur, saturation (1 → 1.35) and the dark scrim (0 → .42) follow the scroll
  progress `p = clamp(y/640)`, never a switch; each grid row lags 18 ms behind the one above.
- **Dead ends:** rubber band (§1.3) on the leftmost/rightmost tile, the last grid row, the first grid column.

### 2.2 Top Shelf
- **Pick change** (every 8 s, or another Dock app focused): backdrop crossfade 700 ms with a slow Ken Burns
  (1.00 → 1.04 and 12 px over 16 s, restarted on each swap); the title logo leaves −14 px in 220 ms and the new one
  enters from +22 px 80 ms later on TITLE_SETTLE; the page dots slide.
- **Up into the shelf:** the Dock recedes (+36 px, α .75), the scrim deepens, then badges, synopsis and Play cascade
  40 ms apart (rising 16 px); Down: everything leaves together in 180 ms.
- **Chevron:** breathes ±2.5 px over 3.2 s while the Dock has focus; fades with the Dock.
- **Counter-parallax** (§5.2).

### 2.3 Tab bar and status pill
- **Indicator:** the selected lens slides (x and width on LIQUID_PILL) and stretches with its speed:
  `sx = 1 + clamp(|vx| · 0.00018, 0, 0.12)`, `sy = 1/√sx`. On Android the `LIQUID` shader's light direction turns
  with `vx` (new `light` uniform), so the rim catches the light as it moves; the web shifts the rim gradient.
- **Focused segment:** white capsule, 1.06×, SNAPPY_FOCUS.
- **Content:** TAB_SWAP — the outgoing tab slides 60 px away and scales to 0.96 while fading in 220 ms; the incoming
  one comes from 60 px on the other side, 0.96 → 1. Direction follows the tab order (TV → Home → Welly is "right").
- **Status pill:** focus 1.0 → 1.08 with a brighter rim; the Memoji pops 1.12× inside its circle. When something is
  playing, the pill widens (LIQUID_PILL) to show a three-bar equalizer, and narrows back when it stops.

### 2.4 Control Center and profiles
- **Open:** SHEET_OPEN; the panel slides in from +480 px while the canvas behind scales to 0.975 under a 30% dim;
  header, profiles, the bento rows and the weather card follow 25 ms apart.
- **Close (Back):** SHEET_CLOSE, the canvas back to 1.0.
- **Profile switch:** the avatar in the status pill cross-dissolves.

### 2.5 Screensaver
- Enter: 1.2 s dissolve with a slow zoom 1.06 → 1.0. Wake on any key: 320 ms dissolve, the home 0.98 → 1.0.

---

## 3. Home tab (the diorama)
- **Entry:** the camera settles from a little higher and further (pitch +4°, zoom 0.94) on CAMERA_ORBIT.
- **Overview** whenever the tab bar has the focus; Back from any light returns there in one press.
- **Room orbit:** yaw (the short way round), pitch, zoom and centre on CAMERA_ORBIT. Outside walls lower and rise on
  WALL_CUT from the angle between their outward normal and the view; art on them fades with them. Other rooms fade to
  45% and lose 30% of their colour (ROOM_FADE).
- **Dots:** focused 1.35×, its stem 8 px longer, the fixture 1.12× (SNAPPY_FOCUS); the pill follows the projection,
  crossfades its text between lights, stays in the safe area and is drawn above everything. Dead ends: rubber band.
- **Toggle:** a ring leaves the dot (1 → 2.8×, α .8 → 0, 450 ms); turning on, the filament flashes to 1.25 on IGNITE
  and the warmth spreads outward from the light at 8.5 m/s (closest pieces first); turning off, it pulls back into the
  light over 240 ms.
- **Ambient:** fan blades turn (360° in 4.5 s); TVs and monitors carry a faint standby glint; after 6 s without a key
  the camera drifts in a slow figure eight (±1.5°, 36 s), stopped by any key; a lit dot breathes (§5.5).

## 4. Welly tab
- **Orb**, centre stage above the greeting, four states blended on ORB:
  - **Idle:** a slow aurora morphing with low-frequency sines.
  - **Listening:** grows to 1.18×, a sharper cyan/magenta/amber rim, rings that follow the mic level
    (SpeechRecognizer `onRmsChanged` on Android; a simulated cadence on the web).
  - **Thinking:** specular nodes orbit it, a 0.8 s pulse, and a witty status line per tool ("Checking South Brunswick
    skies…", "Judging your watchlist…").
  - **Speaking:** pulses in step with the reply being revealed.
  When a reply arrives the orb shrinks and glides up beside "Welly · mood".
- **Reply:** its block grows to its height; words appear at a reading pace (OK shows it all).
- **Cards:** rise out of the orb's position (§5.3), 45 ms apart: 28 px rise, 0.94 → 1.0, fade, CARD_ENTER. Focus uses
  the tile physics (1.10×, tilt, lift).
- **Prompt pill:** OK squishes it (MICRO_PRESS), then it flies up into the conversation as your line.
- **Dead ends:** rubber band on the first/last pill and card.

## 5. Extra ideas (approved)
1. **Fast scrub:** while an arrow is held (key repeat), focus moves on FAST_FOCUS and the tilt, sheen and labels are
   held back; they return when the key is released.
2. **Counter-parallax:** the Top Shelf art shifts ±8 px opposite to the focus moving along the Dock (a translate of the
   existing layer, no extra drawing).
3. **Orb → cards:** Welly's cards start at the orb and travel to their places.
4. **Sound with motion:** the click sounds fire on the press frame; dead ends play the soft boundary sound with the
   bump.
5. **Lit-dot breath:** a lit light's amber dot breathes ±4% over 4 s.

## 6. Guardrails
- No allocation per frame: Paints, Paths, shaders, matrices and arrays are made once and changed in place; text is
  measured when it changes, not per frame.
- No frames at rest (§1.1). Android 8–11 (`Ui.LEGACY`) keep the motion and skip only the costliest effects (blur
  animation, orb refraction) with the existing fallbacks.
- Web: only `transform` and `opacity` animate; springs write them from `motion.js`; CSS keeps short one-shot fades.
  ES5 and no CSS variables/grid/backdrop-filter (`tools/check.sh`). Tizen 2.4 (`lite`, set on `<html>`): springs jump,
  keyframe animations off.
- Reduce motion and reduce transparency apply everywhere (§1.1); their rules must win over every other rule.

## 7. Gap analysis (2026-10-26 audit, before this work)
- Android `Spring` was semi-implicit Euler with 4 ms sub-steps and absolute rest thresholds whatever the unit; callers
  clamped dt to 50 ms. The web home had no springs (CSS transitions: 37 cubic-beziers, 7 `linear()` curves), so focus
  hops restarted instead of bending; rooms.js had its own spring.
- With animations off, Control Center, the grid and full screen never moved (`updateMotion` ran only when a spring
  reported motion or `motionDirty` was set, and those paths didn't set it).
- Missing: OK press feedback, dead-end feedback (18 silent `return`s in HomeView alone), Welly reply/card entrances,
  Control Center stagger, mic level, a moving light direction in the glass shader.
- Snaps: the chevron, page dots, the emblem bitmap at mid-grow, the profile avatar, All Apps focus, the house pill
  text, Welly's listening text and mic colour.
- Not springs and not honouring animations-off: backdrop/hero fades, full-screen details, toast, night, House
  ignite/ripple, Welly dots/rim.
- Inconsistent: the tab lens slid on Android, recoloured on the web; tab content 0.45 s vs 0.35 s.
- Web bugs: focus grow snapped to 1.23× after the first ←/→ (the `sway-*` keyframes override the transition); the
  screensaver's Ken Burns stopped after each photo's first use; `lite` on `<html>` but rooms.js checked `<body>`;
  the "Spring A" `linear()` curve had uneven stops and lagged the real spring; reduce motion/transparency lost to
  more specific rules (`.drawer-open #drawer`, `.full #details > *`, `.night`, `.no-picks`).
- Frame budget: HouseView and WellyView stepped springs inside `onDraw`; `Scene.setFrost()` made a `BitmapShader`
  per call during night/background fades; Welly reset a gradient matrix every frame while listening.
