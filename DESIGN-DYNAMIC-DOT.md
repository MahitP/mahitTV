# The dynamic dot

mahitTV's take on Apple's Dynamic Island. Each live activity is a Liquid Glass dot beside the tab bar at the top centre,
the way the Dynamic Island's detached circles sit beside the island. Today there are three activities:
- a song playing;
- the sleep timer;
- AirPods joining.

```
     ( TV · Home · Welly ) (♪) (☾)                    (⚙)  ( 9:41 (avatar) )
      the tab bar          live activities            Control Center   the time and the profile
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

**Every dot**
- **Look:** the bar's own Liquid Glass, as tall as the bar (57 px), with a white symbol like the round buttons have: ♪ (`music`), ☾ (`bedtime`), AirPods (`airpods`). There's no colour of its own and no picture at rest.
- **Coming:** it slides out from under whatever is left of it (the bar, or the dot before), growing from 0.35× on a liquid spring that carries it a little past its place. The bar and the dots stay centred as one group.
- **Focused:** → from Welly, then ← → between the dots.
  - It opens rightward into a 72 px capsule on a bouncy spring. The rim brightens and the shadow deepens.
  - The symbol stays at the left end, on a concentric margin. For a song with album art, the art fades in over it.
  - The words fade in as the shape arrives, and its one control sits at the right end.
  - The other dots move along to make room.
- **Going:** it slides back under its neighbour, and the group centres again. If it had the focus, the focus moves to its neighbour, else to the bubble.

**The activities**

| | There while | Open words | OK |
|---|---|---|---|
| ♪ The song | it plays; 30 s after a pause; while focused | the title; the artist · the app | play or pause (‖ / ▶) |
| ☾ The sleep timer | it runs (when its overlay doesn't show it) | Sleep Timer; Off in 25 min | the next preset (15 min, 30 min, 1 h, 2 h, off; + or ×), as Control Center's tile |
| AirPods | 3.5 s after they join, open by itself (the alert), then 0.9 s as a dot; while focused | the name; Connected (· Spatial Audio) | Control Center |

- **AirPods** replace the card that used to slide in at the top right. The glyph by the bubble stays while they're connected.
- **The sleep timer** replaces the "Off in…" text that sat left of the round buttons.
- **The song's title** no longer shows in the tab row's right end. The clock screen keeps its line under the clock.

## Nothing overlaps

- The group (the bar and its dots) is centred. It never comes within 32 px of the right cluster (the round buttons, the AirPods glyph, the sleep timer, Control Center's bubble). On the left, it never passes the Top Shelf logo's column (x 476).
- An open capsule's width is what its words need (up to 380 px of text), but never more than that space allows once the other dots have their room. The words are cut with "…" to what's left.
- All of this is measured when something changes (a song, the focus, the layout), never while drawing.

## Later candidates

- **An app update downloading:** a ring of progress. Its job runs in the main process, so the home screen would need to be told.
- **A timer**, once mahitTV has one.
- **Live TV recording**, where the TV can say so.

## Where it lives

- **Android:** `HomeView`: `Live` and `lives`, `updateIsland`, `layoutIsland`, `drawIsland`/`drawLive` (in the tab bar's layer, under the bar), `tabShift` and `islandOk`. Also `NowPlaying.Watch.mediaArt`.
- **Web app:** `web/home.js` (`.live`, one per activity, just before `#tabs`) and `home.css`. The glass is opaque pieces in one translucent layer, so the joins don't show. The preview's sample song and sleep timer make those show there. LG and Samsung can't tell what's playing, so on those TVs only AirPods can appear.
