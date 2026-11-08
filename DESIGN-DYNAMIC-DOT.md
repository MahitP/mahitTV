# The dynamic dot

mahitTV's take on Apple's Dynamic Island. While a song plays, a Liquid Glass dot sits beside the tab bar at the top
centre, the way the Dynamic Island's detached circle sits beside the island:

```
                 ( TV · Home · Welly ) (♪)              (⚙)  ( 9:41 (avatar) )
                  the tab bar          the dynamic dot   Control Center   the time and the profile
```

**Why there.**
- **The island analogy.** The tab bar is the screen's centred capsule, the island, and the dot is its detached circle. Apple's minimal presentation is exactly that pair.
- **Reachable.** It's one press → from Welly, and ← from it goes back to Welly. ↑ from the tabs still lands on Control Center.
- **Balanced.** The bar and the dot are centred as one group, so the dot's arrival doesn't tip the top row to one side.
- **Uncrowded.** The right corner keeps only Control Center and the profile pill.

The first version (left of Control Center, black) was harder to reach, and it read as one more button in the corner.

## What the research said

**Apple (Human Interface Guidelines, Live Activities).**
- A Live Activity has three island presentations:
  - **compact**: one activity;
  - **minimal**: a *detached circle* when there are several;
  - **expanded**: touch and hold.
- The minimal circle should show live information, not just a logo. For music, that's the album art.
- The expanded view is "an enlarged version" of the small one. Elements keep their relative places, and the layout grows predictably out of the small one. Existing elements animate to their new positions rather than vanishing and coming back. Overlapping is avoided.
- Background: black and opaque, with a thin key line around it in the dark (tinted to the content). The text is large and medium weight or heavier.
- Margins are even and concentric: round things inside sit on a radius that matches the outer shape.
- Interactivity: at most one direct control, for things you pause and resume, such as music. Tapping it opens the app.
- Animations: springs, at most two seconds. When the activity ends, it leaves the island at once.

**Phones that copied it.**
- **realme Mini Capsule** (2023): charging, data use and steps. The most basic.
- **OnePlus/OPPO Fluid Cloud** (OxygenOS 14): timers and music; tap to open the app.
- **Honor Magic Capsule**: the most interactive. It changes songs, stops music and timers, and answers calls from the capsule.
- **Samsung Now Bar** (One UI 7): several live activities (timers, scores, navigation, music), swiped between. It can be hidden per app.
- **Xiaomi HyperIsland** (HyperOS 3): third-party apps (Spotify, YouTube, WhatsApp) and two islands at once.
- **Android 16 Live Updates**:
  - a status-bar chip with a short text;
  - other icons hide while it shows;
  - tapping it opens the full card.

**What carries over to a TV.**
- A TV has no camera to hide and no touch, so the island becomes a focusable element in the top row.
- Focus plays the role of touch and hold: when focused, it's expanded; when not, it's the minimal circle.
- OK is the one direct control. For music, that's play or pause, as on Honor's capsule; on a TV you don't want to leave the home screen for that.
- Glass, not black. Apple's island is black so it can hide the camera, and a TV has none. Here it wears the tab bar's Liquid Glass, so it reads as part of the same system.

## The behaviour

- **Nothing playing:** there is no dot, and the tab bar is centred alone.
- **A song starts:** this means a media session is playing and the *Now Playing widget* is on.
  - The dot slides out from under the bar's right end. It grows from 0.35× on a liquid spring that carries it a little past its place.
  - The bar slides left by half the dot's width, so the pair stays centred.
- **The dot at rest:**
  - It's the bar's own Liquid Glass (smoke glass, its rim and its light), as tall as the bar: 57 px.
  - The album art sits inside it as a circle on an even 6 px margin. Without art, the app's icon is used.
- **Focused (→ from Welly):**
  - It opens rightward into a 72 px capsule on a bouncy spring. The rim brightens and the shadow deepens.
  - The art stays at the left end and grows to 56 px on a concentric 8 px margin. The title and artist fade in as the shape arrives, and play or pause sits at the right end.
  - The pair re-centres as it grows.
- **OK:** play or pause.
- **Paused:** it stays 30 s, and while it has the focus. Then it slides back under the bar, and the bar centres again.
- **Stopped:** the session ends or Now Playing is off. The dot leaves at once, and a focus on it moves to the bubble.
- **The title row:** the song's title no longer shows in the tab row's right end, since the dot shows it now. The clock screen keeps its line under the clock.

## Nothing overlaps

- The pair (the bar and the dot or capsule) is centred. It never comes within 32 px of the right cluster (the round buttons, the AirPods glyph, the sleep timer, Control Center's bubble). On the left, it never passes the Top Shelf logo's column (x 476).
- The capsule's width is what the title needs (up to 380 px of text), but never more than that space allows. The title and line are cut with "…" to what's left.
- All of this is measured when something changes (a song, the focus, the layout), never while drawing.

## Later candidates

Each would be a second activity in the same dot. When two are live, two dots sit side by side, as in Apple's minimal presentation.

- **The sleep timer's countdown:** the dot shows the minutes left, and OK adds 15.
- **AirPods or Beats connecting:** today that's a toast and a glyph.
- **An app update downloading:** a ring of progress.
- **A timer**, once mahitTV has one.

## Where it lives

- **Android:** `HomeView` (`island*` fields, `layoutIsland`, `drawIsland` drawn in the tab bar's layer under the bar, and `tabShift`) and `NowPlaying.Watch` (`mediaArt`).
- **Web app:** `web/home.js` (`#island`, just before `#tabs`) and `home.css`. The preview's sample song makes it show there. LG and Samsung can't tell what's playing, so on those TVs it never appears.
