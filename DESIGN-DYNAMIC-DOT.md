# The dynamic dot

mahitTV's take on Apple's Dynamic Island. The top right of the home screen is, left to right:

```
[ dynamic dot ]  [ Control Center dot ]  [ 9:41  (avatar) ]
   only while       always there            the time and the
   something plays                          profile pill
```

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

## The behaviour

- **Nothing playing:** there is no dot. The Control Center dot and everything left of it close up against the pill.
- **A song starts:** this means a media session is playing and the *Now Playing widget* is on.
  - The dot grows out from under the Control Center dot on a liquid spring.
  - Everything left of it (the top buttons, the AirPods glyph, the sleep timer) slides left by the dot's width.
- **The dot at rest:** a black circle (48 px, the Control Center dot's size). The album art sits inside it as a circle, on an even 6 px margin. Where an app gives no art, its icon is used instead.
- **Focused:**
  - The dot stretches left into a capsule, 72 px tall, on a bouncy spring.
  - The art moves to the left end and grows to 56 px, keeping the same concentric 8 px margin.
  - The title and artist fade in once the shape has nearly arrived.
  - A play or pause glyph sits at the right end.
  - It grows toward the left, so it never moves the two dots right of it.
- **OK:** play or pause.
- **Paused:** the dot stays for 30 seconds, so a pause from the dot can be undone from the dot. Then it shrinks back under the Control Center dot. It also stays while it has focus.
- **Stopped:** the session ends or Now Playing is off. The dot leaves at once, and if it had focus, the focus moves to the Control Center dot.
- **The title row:** the song's title no longer shows in the tab row's right end, since the dot shows it now. The clock screen keeps its line under the clock.

## Nothing overlaps

The dot reflows the row instead of covering it. When the capsule needs room:

1. The things left of it (the top buttons, the AirPods glyph, the sleep timer text) slide left by its width, as it grows.
2. If they would reach the tab bar, the tab bar slides left too, keeping a 32 px gap. It stops at the screen's 80 px margin.
3. If there is still not enough room, the capsule takes what there is, and the title is cut to fit with "…".

When the focus leaves, everything springs back. All of this is measured when something changes (a song, the focus, the layout), never while drawing.

## Later candidates

Each would be a second activity in the same dot. When two are live, two dots sit side by side, as in Apple's minimal presentation.

- **The sleep timer's countdown:** the dot shows the minutes left, and OK adds 15.
- **AirPods or Beats connecting:** today that's a toast and a glyph.
- **An app update downloading:** a ring of progress.
- **A timer**, once mahitTV has one.

## Where it lives

- **Android:** `HomeView` (`island*` fields, `drawIsland`, `layoutPill`) and `NowPlaying.Watch` (`mediaArt`, `mediaPkg`).
- **Web app:** `web/home.js` (`#island`) and `home.css`. The preview's sample song makes it show there. LG and Samsung can't tell what's playing, so on those TVs it never appears.
