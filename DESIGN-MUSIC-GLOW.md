# Music Glow

A ring of light around the TV picture, in the colours of the album art. It pulses with the beat while music plays, and breathes out when it stops. It's inspired by Lumn, a $9.99 Mac app that does the same around a Mac's display.

## How Lumn works

I studied Lumn 1.1 (trylumn.xyz) without running it. I read its bundle with `plutil`, `otool` and `strings`, and its Swift reflection metadata. REA's command-line tool ran, but its native analysis needs a decompiler engine (Ghidra, Hopper, IDA or Kuna), which wasn't installed. Its binary is stripped.

- **App:** SwiftUI, AppKit and Metal, with Sparkle for updates. The bundle id is `Playgroundbranch.Lumn`, it needs macOS 15, and it's a universal binary for arm64 and x86_64.
- **Drawing:** a Metal library with a `plasmaRim` shader. Its inputs are `phase`, `bloom`, `chroma`, `thickMul`, `wobble`, `edgeWidth`, `notch`, `cornerRadius`, `amount`, `balance`, `deepC` and `color`. So the rim is a living "plasma" band, not a stroked line:
  - a thin hot core at the edge;
  - a soft bloom inward;
  - two colours flowing around it;
  - a shimmer.
  A `liquidGlass` shader draws its lock-screen player.
- **Colour:** `AlbumArt`, `RimPalette` and `_accent` / `_accentDeep`. That's two colours taken from the artwork, an accent and a deep shade, with a balance between them. "Override album color" lets you pick fixed ones instead.
- **Listening:** a CoreAudio process tap on the whole system's audio mix (`AudioHardwareCreateProcessTap`, an aggregate device, "global system-audio mixdown"). An FFT (`log2n`, `fft`, `window`, `_bands`) runs on it. The tap heals itself when the output device changes.
- **Beat:** `AudioBeatDetector` keeps `bassHistory`, `fullHistory`, `lpState` and `lastBeatT`. A beat is the low-passed bass energy rising above its own recent history, at least a minimum gap after the last one. Its log line reads "beat #n bass … full …".
- **Rhythm:** `MusicSyncClock` keeps `bpm`, `beatPhase`, `phaseCorr`, `anchorPos` and `interval`. Around those, it keeps:
  - `energy`, `peakRef`, `dynEase` and `baseline`: loudness, eased and normalised to a running peak;
  - `bloomAt` and `lastBloom`: the flash on each beat, then its decay;
  - `idlePos` and `idleSpeed`: a slow drift when nothing plays.
  The rim moves with this clock, not with each raw frame, which is why it stays smooth.
- **Settings:** Gradient or 2 Colors, Animation (Music Sync or a steady idle glow), Thickness, Glow, Notch compatibility, which displays, and Override album color.
- **Players:** Music and Spotify, through Apple Events and their distributed notifications.

## mahitTV's version

**Where it shows.** It frames the whole picture, over any app, while a music app plays: Spotify, YouTube Music, Apple Music and the like. Video apps never get it, because a light around a film would fight the film. It draws in the remote key service's own overlay window (`TYPE_ACCESSIBILITY_OVERLAY`, as `KeysOverlay`), so it also frames the home screen and the screensaver.

**How it looks.**
- **The edge:** a thin hot core right at the edge, whiter on a beat, with a soft bloom fading inward (two exponentials). Each of the four edges adds its own light, so the corners blend smoothly and a little brighter, with no crease. (A rounded box's distance field drew faint diagonal lines from each corner in the preview.) The bloom reaches exactly 0 at the band's inner edge, so the middle of the picture is never touched.
- **The colours:** the accent and the deep colour flow around the rim as two slow travelling waves, with a fine shimmer along it. They come from the album art: the most vivid hue, and a second one at least 50° away (else an analogous darker one). Saturation and brightness are lifted so it glows. Grey art gets a soft silver and blue.
- **No banding:** a tiny dither keeps an 8-bit panel's gradients smooth.

**How it moves.** The springs and envelopes copy Lumn's clock:

| | |
|---|---|
| Music starts | the light swells in over about 0.9 s |
| Each beat | the bloom flashes in about 35 ms and decays over about 200 ms. The core whitens and the band widens a little. |
| Loudness | it sets how bright the light is (normalised to the song's own peak, so quiet songs glow too) and how fast the colours flow |
| Pause or stop | it breathes out over about 1.6 s. The colours slow down as it fades, and nothing stops suddenly. |
| A new song | its colours cross-fade over about 1.4 s |
| No audio data | some TVs give none (a protected or tunnelled stream). It holds a steady glow that breathes once every 4.5 s, as Lumn's idle glow does. |

**Listening.** Android's `Visualizer` reads the TV's own output mix (session 0). It gives FFT magnitudes about 20 times a second; that's never the microphone, and nothing is recorded or stored. RECORD_AUDIO is what Android asks for here, and mahitTV already has it for Welly. The rest:
- **Beat:** bass is 40–150 Hz. A beat is bass above 1.35 × its 1.6 s average and rising, at least 260 ms after the last one.
- **Loudness:** 40 Hz–6 kHz, with a fast attack and a slow release, over a slowly falling peak.
- **When it runs:** the Visualizer runs only while a music app plays and the screen is on.

**Settings → Appearance & Startup → Music Glow:**
- **Music Glow:** on or off. It's on by default.
- **Glow Style:**
  - *Subtle*: a fine line and a short glow;
  - *Balanced*;
  - *Vivid*: a broad band that washes the room.

It needs Now Playing's access, the same as the dynamic dot, so it works where the dot shows music.

**Checked.** A WebGL port of the shader was tuned in a browser for each style, with sample palettes and with and without a beat. On the TV (TCL, Android 14) the AGSL compiles: the key service logs "music glow: shader ready".

**Cost.** The first version drew one AGSL shader over every pixel of the edges at 60 fps, on the key service's main thread. On the TCL that meant 77 ms frames (13 fps), the GPU busy 17 ms per edge, and every remote key waiting behind it: the TV felt slow. What it does now:
- **Its own thread:** the light's windows belong to a HandlerThread (`glow-light`), so the key service's thread never waits for a frame.
- **Four thin windows**, one per edge, only as deep as the glow reaches. The middle of the picture isn't composited at all.
- **A tiny picture per edge:** the glow (the colours along it, faded across it) is painted once per colour change at a quarter of the size. A frame only slides it along the rim and stretches it across on the beat, through a repeating, filtered BitmapShader: the cheapest draw a GPU has, and a soft light loses nothing at that size. A new song's colours cross-fade the old picture out.
- **25 frames a second**, and nothing once it's dark: the windows go.

Measured with Spotify playing:

| | Before | Now |
|---|---|---|
| Median frame | 77 ms | 5 ms |
| GPU time | 17 ms per edge | 1 ms |
| Late frames | 72 % | 6 % |

The key thread is idle. It works the same on Android 8 to 14, with no shader to compile.

**Brightness.** About 40 % softer than the first version; Subtle, Balanced and Vivid are 0.42, 0.58 and 0.74 strength.

**Turning it off.** In the dynamic dot, what's playing has a fourth button after ⏮ ⏯ ⏭: a little screen with light around it. OK turns Music Glow off (the icon dims, struck through) or on again, and the light breathes out or swells in at once. It's also in Settings → Appearance & Startup → Music Glow.
