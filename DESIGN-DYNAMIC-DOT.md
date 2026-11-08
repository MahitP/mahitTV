# The dynamic dot

mahitTV's take on Apple's Dynamic Island. While any live activity runs, one Liquid Glass dot sits beside the tab bar at
the top centre, the way the Dynamic Island's circle sits beside the island. In order of importance, the activities are:
1. what's playing;
2. Focus (Do Not Disturb);
3. AirPods connected;
4. the sleep timer.

The dot shows the first one that runs. Selecting it opens it, and ↓ ↑ step through the others.

```
     ( TV · Home · Welly ) (♪)                        (⚙)  ( 9:41 (avatar) )
      the tab bar          the dot (most important)   Control Center   the time and the profile
```

**Why there.**
- **The island analogy.** The tab bar is the screen's centred capsule, the island, and the dot is its detached circle. Apple's minimal presentation is exactly that pair.
- **Reachable.** It's one press → from Welly, and ← from it goes back to Welly. ↑ from the tabs still lands on Control Center.
- **One dot, not one per activity.** On iPhone you swipe the island to change which activity it shows; here ↓ ↑ do that. That keeps the top row to one extra circle however much is going on.
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

**The dot**
- **Look:** the bar's own Liquid Glass, as tall as the bar (57 px), with the activity's white symbol like the round buttons have: ♪ `music`, Focus `moon`, `airpods`, ☾ `bedtime`. There's no colour of its own and no picture at rest.
- **Coming and going:**
  - It comes when the first activity starts. It slides out from under the bar's right end, growing from 0.35× on a liquid spring that carries it a little past its place.
  - It goes with the last one, sliding back under. The bar and the dot stay centred together.
- **Focused (→ from Welly):**
  - It opens on the most important activity, rightward into a 72 px capsule on a bouncy spring. The rim brightens and the shadow deepens.
  - The symbol stays at the left end, on a concentric margin. For a song with album art, the art fades in over it. The words fade in as the shape arrives, and the activity's one control sits at the right end.
- **↓ ↑ step through the activities in order.**
  - The old one slides out the way you pressed as the next slides in from the other side, inside the shape. The capsule springs to the new width.
  - Past the last (↓) or the first (↑), nothing changes. The dot gets the nudge and the knock every other dead end gives.
- **← and →:**
  - On what's playing, ← → move between ⏮ ⏯ ⏭ first; the focus starts on ⏯.
  - Past them (or on any other activity), ← goes back to Welly and → goes on to the round buttons or the bubble.
- **Showing where the focus is:** the focused control is a white circle with an ink glyph (the tvOS focus). The tab bar's lens and its selected name step back, so the focus is plainly in the dot and not on Welly.
- **OK held on what's playing:** its app opens, as tapping the Dynamic Island opens the app. The song keeps playing.
- **When the focus leaves,** it closes, back to showing the most important activity.
- **When an activity stops under the focus,** the focus moves to the next one. If none is left, it moves to the bubble.

**The activities**

| | Runs while | Open words | OK |
|---|---|---|---|
| ♪ What's playing (TV symbol for a video app) | it plays; 30 s after a pause; while focused | the title; the artist · the app | ⏮ ⏯ ⏭ (← → between them); held: open the app |
| Focus | Do Not Disturb is on (`ACTION_INTERRUPTION_FILTER_CHANGED`) | Do Not Disturb; On | turn it off (×). Control Center turns it on |
| AirPods | they're connected. Joining opens the dot on them by itself for 3.5 s (the Dynamic Island's alert) | the name; Connected (· Spatial Audio) | Control Center (›) |
| ☾ The sleep timer | it runs | Sleep Timer; Off in 25 min | − + × (← → between them): the preset below or above (15 min, 30 min, 1 h, 2 h; none: the knock), or off |

- **AirPods** replace the card that used to slide in at the top right. The glyph by the bubble stays.
- **The sleep timer** replaces the "Off in…" text that sat left of the round buttons, and the banner that showed over every app (Settings' Show Time Left and Position are gone).
- **The song's title** no longer shows in the tab row's right end. The clock screen keeps its line under the clock.

## Nothing overlaps

- The pair (the bar and the dot) is centred. It never comes within 32 px of the right cluster (the round buttons, the AirPods glyph, the sleep timer, Control Center's bubble). On the left, it never passes the Top Shelf logo's column (x 476).
- An open capsule's width is what its words need (up to 380 px of text), but never more than that space allows. The words are cut with "…" to what's left.
- All of this is measured when something changes (a song, the focus, the layout), never while drawing.

## Movies and other apps

**Decision:** there's no separate "movies" activity. What's playing covers any app that keeps playing behind the home screen, video or music, and shows the TV symbol for video apps.

**Why:**
- **Android TV pauses a video app when you leave it** for the home screen. Its docs say so, and only an app that asks for background playback keeps going. So Netflix, YouTube and the rest stop the moment you press Home, and never stay "live" here.
- **Apple's island works the same way.** It shows what keeps playing through the Now Playing system: music and podcasts. Video only shows in picture in picture.
- **Video apps that do keep playing** get the TV symbol, with their controls and hold-to-open, as music does. Examples: a live channel, or picture in picture.

## Later candidates

- **An app update downloading:** a ring of progress. Its job runs in the main process, so the home screen would need to be told.
- **A timer**, once mahitTV has one.
- **Live TV recording**, where the TV can say so.

## Where it lives

- **Android:** `HomeView`: `Live` and `lives` (in order of importance), `updateIsland`, `aimIsland` (what it shows, open, width, the step), `layoutIsland`, `drawIsland`/`drawLive` (in the tab bar's layer, under the bar, clipped to the shape while stepping), `tabShift`, `islandOk`, `readDnd` and `toggleDnd`. Also `NowPlaying.Watch.mediaArt`.
- **Web app:** `web/home.js` (`#island`, one face per activity, just before `#tabs`) and `home.css`.
  - The glass is opaque pieces in one translucent layer, so the joins don't show.
  - The web can't clip to the capsule, so a step travels only 12 px: the old face is gone by 40 %, and the new one comes in from 25 %. The preview's sample song, Do Not Disturb and sleep timer make those show there. LG and Samsung can't tell what's playing, so on those TVs only AirPods can appear.
