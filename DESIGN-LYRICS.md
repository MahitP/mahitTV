# Lyrics

What's playing, its words lit one at a time as they're sung, in the album art's colours. It's an app of its own in All Apps, and the screensaver while a song plays. It's inspired by Verci, a $9.99 Mac menu-bar app by the maker of Lumn that shows the playing song's lyrics on the Mac's lock screen, word by word.

## How Verci works

I studied Verci 1.1.1 (verci.xyz) without running it:
- its bundle, with `plutil`, `otool` and `strings`;
- its Swift reflection metadata;
- its two website pictures;
- parts decompiled with Ghidra 12.1.4 through REA.

Its binary is stripped.

- **App:** a SwiftUI menu-bar app (`LSUIElement`, no Dock icon) with Metal and Sparkle. The bundle id is `Playgroundbranch.Verci`, it needs macOS 15, and it's universal (arm64 and x86_64). It bundles an MIT licence from Lakr Aream, for one of the libraries it uses.
- **Where the lyrics come from:** NetEase Cloud Music's API.
  - It searches with `/api/search/get`, keeping candidates within a duration tolerance (it probes at most a few).
  - It reads the lyrics with `/api/song/lyric/v1`.
  - "word-level via ncm" is NetEase's `yrc`: each line `[start,length]`, each word `(start,length,0)word`, in ms.
  - Line-only lyrics are "interpolated from line-level" into words.
  - It caches what it finds.
  - Spotify and Music give it the track, artist, duration and position through AppleScript. Its clock samples the position and resyncs past a threshold (`sampleInterval`, `resyncThreshold`).
- **Styles:**
  - *Ship*: a wall of words drifting in 3D.
  - *Fisheye*: a convex stack under a Metal lens (`fisheye`: strength, power, velocity, motionBlur).
  - *Visual*: SF Symbols and emoji beside about 770 words. `WordVisualResolver` finds them with CoreGlyphs' `symbol_search.plist` and word embeddings.
  - *Fisheye Visual*.
  - Hidden in the binary, not on the site: *Flashbang* and *Flashbang Visual*, *Branches* (a hub, angles, rank), *Web*, *Constellation* (stars, edges) and *Codestyle Visual*.
- **Fonts:** SF Pro, SF Rounded, New York, Helvetica Neue, Avenir Next, Futura and Didot.
- **Colours:** three of them (lyric, highlight and secondary), set by hand or "Auto Sync" from the album art. Its picture shows pink words with the word being sung in white on a magenta pill.
- **Other settings:** text scale, and which displays it shows on. It shows on the lock screen only, or always, through private SkyLight spaces (`SLSSpaceSetAbsoluteLevel`, ScreenLock level). "Keep display awake" holds the display on while music plays.

## mahitTV's version

**Where it is**
- **All Apps → Lyrics:** a rose-to-violet tile with the lyrics symbol, opening `LyricsActivity`.
- **The screensaver:** when it starts while a music app plays, it shows Lyrics (Settings → Appearance & Startup → Lyrics While Music Plays, on by default). When the music has been gone 25 s, it fades back to the usual screensaver.
- **Music only:** video apps, IPTV and YouTube don't count as music (`GlowRing.isMusic`).

**Where the lyrics come from** (`LyricsSource`), in this order:
1. NetEase's word-timed lyrics (`yrc`), for the song whose title matches, artist matches and length is within 4 s. Up to 3 candidates are tried, best first.
2. Its line-timed LRC.
3. LRCLIB's (lrclib.net) line-timed lyrics.

How they're handled:
- **Line-timed lyrics get word timings:** each word gets a share of its line's time by its letters, the way Verci interpolates them.
- **Credits are dropped:** lines like 作词 : … and "Producer:" aren't sung.
- **Unicode spaces count:** NetEase's LRC puts U+2005 between some words.
- **What's sent:** only the title, artist and length leave the TV. Never audio.
- **Cache:** each song is cached on the TV. A song with no lyrics is tried again after 3 days.

The parser was tested with real NetEase `yrc`, NetEase LRC and LRCLIB data for one song. All three give 35 lines with sensible word timings.

**How it looks** (`LyricsView`): near black, with the song's two colours glowing softly from opposite corners and drifting. That background is painted once, small, and drawn stretched.
- **The colours:** the highlight is the art's vivid colour, the sung words its light tint, the coming ones a dim mauve. They cross-fade over 1.2 s when the song changes.
- **Words** (Verci's Visual, the default): the line being sung as a stack of big, heavy words (Inter 800).
  - **Rows:** one or two words a row. Each row is as wide as its turn says (50 %, 64 %, 44 % … of the screen), so the sizes vary like Verci's. Each is turned a degree or two, and the whole stack drifts slowly in space.
  - **The word being sung** is white on a rounded pill of the highlight colour, which grows in quickly. Sung words take the light tint; coming ones stay dim.
  - **Icons:** an icon sits beside words a picture says plainly. There are about 230 song words on 81 Material Symbols, tinted to the song (`WordIcons`, `assets/symbols/w`).
  - **New lines:** they rise in on a spring, and the old line lifts away.
- **Lens** (Verci's Fisheye):
  - every line in a column under a lens: the line being sung largest in the middle, word by word;
  - the others smaller (1 / (1 + 0.38 d²)), dimmer, and packed closer toward the edges;
  - it glides on, line by line.
- **Before the first line, and in a gap of over 3.5 s:** three dots that fill up as the next line nears, breathing, as Apple Music does.
- **The song, small, bottom left:** its art, title and artist. On the screensaver, the time too, top right.
- **No lyrics, or still looking:** the song itself, large. The art (a 460 px rounded square), the title, the artist, "Finding lyrics…" or "No lyrics for this song", and where the song is.
- **Nothing playing:** the lyrics symbol, "Play a song to see its lyrics", and which apps work.

**The clock:** the player's position, moved on by the time since its last report, at its speed. Each frame it's eased to that: players report coarsely. A jump of over 0.4 s is a seek and is taken at once. A line counts 120 ms early, so it reads as on time.

**The remote** (the app):
- OK: play or pause.
- ← →: the previous or next song.
- ↑ ↓: Words or Lens, with the style's name shown for a moment at the top.
- Back: leave.

The screen stays on while the app is open, as Verci keeps the display awake. On the screensaver, any key wakes the TV.

**Cost:** text and a few rounded rectangles on a Canvas, plus one stretched picture behind. No shaders and no blur. Layout happens when a line or a song changes, never while drawing. 60 fps.

**Not on LG or Samsung:** those TVs can't tell what's playing, so the web app has no Lyrics.
