# Welly, Home and the Remote: research and architecture spec

Three additions to the tvOS edition (DESIGN-TVOS.md): a top **tab bar** (`TV | Home | Welly`), a **Home** tab with a
3D model of the house and its Kasa lights, and **Welly**, an AI assistant for the TV. A fourth piece, a companion
**remote app**, is specified here and built later. Everything keeps the apple-design rules (DESIGN-TVOS.md §0b):
restraint, Apple's continuous corners, flat resting surfaces, one focus lift, never glass on glass.

Confidence tags as in the apple-design skills: **[documented]** (vendor docs), **[observed]** (seen in a product or
reported by several independent users), **[inferred]** (our reasoning), **[unverified]** (single or conflicting
sources).

---

## 1. Research findings

### 1.1 The AI brain: Gemini free tier

- Google's models page lists, for **new** projects, **`gemini-3.8-flash`** (recommended) and **`gemini-3.5-flash-lite`**;
  2.5 models are "not deprecated" but limited to projects that used them before. [documented — ai.google.dev/gemini-api/docs/models]
- Google no longer publishes free-tier numbers per model; limits are **per project** and shown in AI Studio; RPD
  resets at midnight Pacific; an exhausted quota answers **HTTP 429 `RESOURCE_EXHAUSTED`**. [documented — ai.google.dev/gemini-api/docs/rate-limits]
- Third-party trackers disagree on the free allowance: one reports **~20 requests/day** for 3.8 Flash and **~500/day**
  for 3.5 Flash-Lite; another reports Google cutting free access to Flash-Lite only from 9 Oct 2026. [unverified — laozhang.ai, klymentiev.com, a Medium post]
- Free-tier prompts may be used to improve Google's products and seen by reviewers (outside the EEA/CH/UK). [unverified — aifreeapi.com; check Google's terms]
- Gemini exposes an **OpenAI-compatible** Chat Completions endpoint with tool calling
  (`https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`, `Authorization: Bearer <key>`). [documented, Gemini OpenAI compatibility]

**Consequence:** the model can't be hard-coded as "the free one". Welly uses a **chain** and treats 429/403/404 as
"next brain" (§3.2), and the model IDs live in `config.properties`.

### 1.2 The local brain: Ollama on the Mac

- Ollama serves an OpenAI-compatible **`/v1/chat/completions`** with `tools`; from 0.32.6 (Aug 2026) its streaming
  matches OpenAI's wire format and truncation reports `finish_reason: "length"`. [observed — release notes as reported]
- For tool calling at 8B, the consensus pick is **Qwen3 8B** (≈ 5.2 GB at Q4_K_M; lowest dropped-call rate in one
  benchmark); Llama 3.1 8B is the common alternative. Gemma 3 has no tool calling; Gemma 4 does. [unverified — localaimaster.com, morphllm.com, lumadock.com]
- **Recommendation for this Mac (≈ 16 GB free after the emulator cleanup):** `qwen3:8b` (≈ 5.2 GB) — leaves > 10 GB
  free. If space gets tight, `qwen3:4b` (≈ 2.6 GB). Not `qwen3:30b-a3b` (≈ 18 GB): it would fill the disk.
- The Mac must listen on the LAN: `OLLAMA_HOST=0.0.0.0` (launchctl or the app's setting), and the TV reaches it at
  `http://<mac-ip>:11434/v1/chat/completions`. [documented — Ollama FAQ]

### 1.3 Smart home: Kasa

- **Legacy protocol:** TCP **9999**, JSON encrypted with an XOR autokey (key starts at 171), no authentication. Plugs
  and switches: `{"system":{"set_relay_state":{"state":1}}}`; bulbs:
  `{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":1,"transition_period":300}}}`;
  state: `{"system":{"get_sysinfo":{}}}` (`relay_state`, or `light_state.on_off` for bulbs). [documented — python-kasa, kasa-core]
- **KLAP:** firmware since late 2024 moves devices to encrypted HTTP on **port 80** with a TP-Link account handshake;
  migration is per device. Turning on **Third-Party Compatibility** in the Kasa app has helped some users; some newer
  plugs still fail local auth. [observed — finitelabs/control4-tplink, python-kasa issues #1436/#1752]
- **Consequence:** `KasaClient` speaks legacy 9999 now, probes it, and reports "needs KLAP" per device instead of
  silently failing; KLAP is the next step (§5.2). A `SmartHomeBridge` interface keeps Google Home / Matter for later.

### 1.4 Data sources for Welly's answers

| Need | Source | Notes |
|---|---|---|
| Titles, search, cast | TMDB (already used; your key) | `/search/multi`, `/discover`, `/{id}/credits`, `/person/{id}/combined_credits` [documented] |
| Open a title in its app | Watchmode (already used; optional key) | deep links per service [documented] |
| What's on tonight | **TVmaze** `/schedule?country=US&date=` and `/schedule/web?country=US&date=` | no key; global streamers (Netflix) are not in the country schedule [documented — tvmaze.com/api] |
| Live scores | **ESPN** `site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard` | **unofficial**, no key, may change without notice [observed — pseudo-r/Public-ESPN-API] |
| Weather | Open-Meteo (already used) | South Brunswick, NJ ≈ 40.38 N, 74.53 W |
| Continue Watching | Trakt | OAuth device flow; **specified, not built** (§5.1) |
| What's playing | Android media sessions (`NowPlaying`, already used) | needs the Now Playing widget's listener on |
| Music | media session transport controls | Apple Music: **no official Android TV app** on most devices [observed — several guides; megarohas/apple-music-android-tv]; Welly controls whatever player has the session |

### 1.5 Voice on the TCL remote

- On TCL Android TV the remote's voice button sends **`KEYCODE_SEARCH`** to the app in front; others send
  `KEYCODE_VOICE_ASSIST`/`KEYCODE_ASSIST`; the BLE remote mic turns on for the **`VOICE_RECOGNITION`** audio source. [observed — cleanyourtv #1869, sanju.sh]
- The **assistant** key is system-owned (only the preinstalled assistant gets it). [unverified — one developer]
- Google TV help: the voice button on Home starts a general search; inside an app, it searches in that app. [documented — Android TV Help]
- **Consequence:** on the Welly tab, `KEYCODE_SEARCH` (and the on-screen mic) start Android's `SpeechRecognizer`
  (needs `RECORD_AUDIO`, asked once); the web preview uses the Web Speech API where it exists.

### 1.6 What TV assistants do today (and don't)

- **Siri on tvOS:** search across apps, "what did she say?" (rewind + captions), playback control, launch apps,
  HomeKit scenes, sports scores. [documented — Apple Support]
- **Google Assistant (Google TV):** search/launch, playback, smart home, weather, Q&A; being replaced by Gemini. [documented]
- **Alexa (Fire TV):** search/launch, smart home, routines, Q&A. [documented]
- **Gaps none does well [inferred]:** combining *mood + room + content* in one request; spoiler-safe recaps; cross-
  referencing the cast of what's on screen with your history; banter that remembers you; acting on *local* devices
  without a cloud account.

### 1.7 Personality design

- A defined persona keeps tone consistent; write what it would **never** say; give do/don't examples per trait;
  personality shows in pacing and in how it admits limits; tell users it's an AI. [documented — Conversation Design Institute, IBM, Twilio]
- No source endorses sarcasm in service contexts. [observed gap] → our rule: **wit lands after the action, never
  instead of it**, and never at the user's expense.

### 1.8 The remote app

- Apple TV Remote (Control Center): touch surface + D-pad, playback, Siri, the phone keyboard whenever a text field is
  focused, same-Wi-Fi pairing. [documented — Apple Support, MacRumors]
- Universal remote apps: Wi-Fi control, touchpad, keyboard, voice; free tiers often hide keyboard/touchpad. [observed — airbeam.tv, Google Play]

---

## 2. The tab bar and the three tabs (built now)

- A **Liquid Glass segmented capsule**, top centre, height **57**, top **48** (aligned with the status pill): `TV`,
  `Home`, `Welly`, 28 semibold. The selected tab is a lighter lens inside the capsule; the **focused** one is a white
  pill with ink (1.06×, snappy spring). Focusing a tab selects it (tvOS behaviour).
- Navigation: ↑ from a tab's content reaches the bar (on the TV tab, ↑ from the Dock still opens full screen when a
  pick shows; ↑ again reaches the bar); ← → walk `TV · Home · Welly · [pill]`; ↓ enters the tab. Back from a tab's
  content returns to the bar; Back on the bar returns to TV.
- The bar hides in the TV tab's full screen and grid (they own the screen), like the title logo.
- **TV tab:** unchanged — pure Apple TV (Top Shelf, Dock, peek, grid). **No** Continue Watching or TVmaze rows.
- **Apple Music replaces Spotify** in the Dock defaults (`com.apple.android.music`); Spotify stays an ordinary app.
- **Home** and **Welly** draw over the current wallpaper, **frosted and dimmed** like the grid (one background, no
  new glass), so the content reads.

---

## 3. Welly

### 3.1 Personality

**Who:** a sharp, perceptive roommate on the couch — warm, curious, dry. Knows film and TV deeply, notices things,
teases a little, never lectures.

| Trait | Do | Don't |
|---|---|---|
| Fast first | "Done — living room's at 20%, *Neon Meridian* is up. Popcorn's on you." | "Sure! I'd be happy to help you with that. Let me…" |
| Dry wit | "Third rewatch this month. Bold. I respect it." | Jokes when you're frustrated or something failed |
| Informative | "Tidebreak's director also made *Quiet Peak* — same lighthouse color grade." | Walls of text; Wikipedia recaps |
| Honest | "Your bedroom lamp didn't answer — it may be on newer Kasa firmware." | Pretending an action worked |
| Remembers | "You bailed on episode 3 last week. Want the recap first?" | Bringing up things you asked it to forget |

- **Never says:** anything mean about the user, anything about other household members' habits, spoilers unless
  asked, "As an AI…", made-up facts (it says "I don't know" with a quip).
- **Moods** (shown only as a tiny status word under its name, never decoration): *Curious*, *Smug* (after a good
  pick), *Sleepy* (late), *On it* (while acting), *Embarrassed* (after an error).
- **Length:** one or two sentences, then the cards. Long answers only when asked ("tell me more").

### 3.2 Two brains

```
user (voice / pill / typing)
      │
      ▼
 WellyBrain ──► 1. Gemini  gemini-3.8-flash       (OpenAI-compatible endpoint, tools)
            ├─► 2. Gemini  gemini-3.5-flash-lite  (on 429 / 403 / 404 / timeout)
            └─► 3. Ollama  qwen3:8b on the Mac    (on 429 or offline; LAN only)
      │
      ▼ tool calls (JSON)
 WellyTools ──► TMDB · Watchmode · TVmaze · ESPN · Open-Meteo · NowPlaying · HomeApps · SleepTimer · SmartHome
      │
      ▼
 reply text (≤ 2 sentences) + cards
```

- One wire format (OpenAI Chat Completions with `tools`) for all three; only base URL, key and model differ.
- **Fallback is per request and sticky for 10 minutes** after a 429 (no retry storm); a brain answering in > 6 s is
  skipped for that turn. **[inferred]**
- Keys: `gemini_key` (yours, secret, only in config.properties), `welly_model`, `welly_lite_model`, `ollama_url`,
  `ollama_model`. Without a Gemini key Welly goes straight to Ollama; without both it says so, kindly.
- **Memory:** the last 20 turns and a small fact list ("watched Orbital S1E3", "likes thrillers under 2 h") in the app's
  private files, per profile; **"Welly, forget that"** deletes the last fact; never leaves the TV except inside the
  prompt sent to the brain in use.

### 3.3 Tools (both builds share these names)

| Tool | Arguments | Does |
|---|---|---|
| `search_titles` | `query`, `kind?`, `max_runtime?`, `genre?` | TMDB search/discover, filtered to the TV's apps |
| `open_title` | `title_id`, `kind` | opens in its app (Watchmode link, else the app) |
| `whats_playing` | — | the media session's title/app |
| `whats_on` | `date?`, `streaming?` | TVmaze schedule (US) |
| `scores` | `league` (nfl, nba, mlb, nhl, mls, ncaaf…) | ESPN scoreboard |
| `weather` | `place?` | Open-Meteo (default South Brunswick) |
| `cast_crossref` | `title_id`, `kind` | cast of the title × your history ("why do I know that actor?") |
| `recap` | `title_id`, `season` | spoiler-free season summary from TMDB overviews (stops before the next season) |
| `lights` | `room` (parents, mine, living, all), `fixture?` (lamp, bulb), `on` | SmartHomeBridge |
| `scene` | `name` (movie, bright, off, sleep) | lights + optional title (Movie Mode dims living 20 %… legacy plugs: off) |
| `launch_app` | `name` | HomeApps |
| `input` | `name` (HDMI 1…, Live TV) | HomeApps inputs |
| `sleep_timer` | `minutes` | SleepTimer |
| `music` | `action` (play, pause, next, previous) | media session transport controls |
| `settings` | — | TV Settings |

**Breakthrough features** (built on the tools above):
- **Vibe Match** — "rough day": asks one question (cozy, distract me, cry) then `scene` + `search_titles` → 3 picks.
- **Spoiler-Free Recap** — before pressing play on a show you paused, offers `recap` for the last season you finished.
- **Why Do I Know That Actor?** — `whats_playing` → `cast_crossref` → "That's the detective from *Orbital* — you
  watched it in June."
- **Routines** — "Movie mode", "Bedtime" (lights off + sleep timer 30 min), multi-step and confirmable.

### 3.4 Cards (both builds render the same types)

| Card | Content | Action on OK |
|---|---|---|
| `media` | backdrop, title, year · runtime · app | opens the title |
| `score` | two teams, logos as monograms, score, clock/status | — |
| `weather` | place, now, H/L, next 4 hours | — |
| `schedule` | time · show · network list (≤ 5) | opens the show search |
| `home` | room · fixture · on/off confirmation | toggles back |
| `music` | title · artist · app, transport | play/pause |

Cards are **plain fills** (no glass), Apple's corners, one focus lift. Text reply above them in **31 regular** (Callout),
the user's words above that, right-aligned, dimmer.

### 3.5 The Welly screen

- Centre column 1180 wide: **"Hey Mahit."** (Title2 57 semibold) on first open, the mood word under Welly's name; the
  last exchange; cards in a row.
- Bottom: **prompt pills** (glass on the background, the one glass layer here) in a scrolling row — *What am I
  watching?* · *Dim my room & pick a movie* · *Live sports scores* · *South Brunswick weather* · *What's on tonight?* ·
  *Roast my watchlist*; under them the **input capsule** "Ask Welly…" with the **mic** at its left.
- While listening: the input capsule's rim carries the Apple Intelligence-style moving colour edge — the one moment
  of colour. **[inferred]** restraint: only while the mic is open.
- **Preview builds** (no keys): the pills answer from a fixed sample conversation, labelled *Preview*.

---

## 4. Home

### 4.1 The model

- A **single-floor house, roof off**, seen from above at an angle (isometric, 30°), centred; walls 2.6 m, the front
  walls cut low (a dollhouse cutaway) so the rooms read. Geometry is data: **`app/assets/house.json`**, shared by
  Android and the web.
- Modelled on the apartment's **blueprint** and photos, written by `tools/make_house.py` (edit that, not house.json),
  in the blueprint's orientation (balcony at the back, bedrooms in front), seen from above at 60°; the back (north)
  and left (west) walls stand full height, the rest are cut low:
  - **Living room** (centre right): L sectional along the east wall with its return in front of the curtained
    sliding door to the **balcony** (railing), a chaise, the big patterned rug, the **Smart TV** on the bathroom wall
    over a low stand with plants (visual only), the keyboard by the hallway, the corner **floor lamp** (its bulb is the
    living room's light).
  - **Dining room** (top left, window), **galley kitchen**, **laundry closet**, the **hallway** (rack, basket, linen
    closet), the **half bath** and the **bathroom** (tub), the **entry** with its stairs down to the front door.
  - **My room** (front, beside the entry): closet by the door, black cube shelf under the **tube light** on the west
    wall, the bed along that wall, desk and gaming chair under the window, the white bookshelf across from the tube
    light with the **lamp** just behind it.
  - **Parents' room** (front-right corner): closet, black cart and the **tube light** on the west wall by the door,
    the wall TV, the bed against the window wall, desk and chair under the window, the silver armoire.
- Light kinds in house.json: `bulb` (hanging), `lamp` (floor lamp, `height` = shade bottom), `tube` (wall bar,
  `along` x|y, `length`); `glow` is where each light pools on the floor. Ids stay `parents.bulb`, `mine.lamp`,
  `mine.bulb`, `living.bulb`, so `kasa=` keys don't change.
- Colours: walls and floors in a **cool** architectural grey-blue (off); a lit fixture spreads a **warm** amber glow
  over its room's floor and furniture (additive, eased by a smooth spring). No glass cards over the model.
- **Focus:** the D-pad moves between the four fixtures (by their position on screen);
  the focused fixture grows 1.25× on the snappy spring with a soft white halo; **OK toggles it** (optimistic: the glow
  changes at once, reverts with a note if the device doesn't answer). The **TV is not focusable**.
- One caption line at the bottom (type, not a card): *My Room · Lamp · On*, or *Not connected — add its IP in
  config.properties* when the fixture has no device.
- **Photos hook:** `app/assets/rooms/<room>.jpg` (optional) shows dimmed behind the model when that room's fixture is
  focused — off by default, no card.

### 4.2 SmartHomeBridge

```
interface SmartHomeBridge {            // SmartHome.java
  List<Device> devices();              // fixtures bound to devices
  void set(Device d, boolean on, Callback cb);
  void refresh(Callback cb);           // reads states
}
KasaBridge implements SmartHomeBridge  // legacy TCP 9999 now; KLAP later
GoogleHomeBridge (future)              // OAuth + Google Home APIs / Matter
```

- `kasa=` in config.properties maps fixtures to devices:
  `kasa=parents.bulb=192.168.1.21:bulb,mine.lamp=192.168.1.22:plug,mine.bulb=192.168.1.23:bulb,living.bulb=192.168.1.24:bulb`
- All network work off the UI thread, 1.5 s timeout, results posted back; the web build can't open TCP sockets, so
  its Home tab is a simulation (and says so in the caption on a real TV).

---

## 5. Later (specified, not built now)

### 5.1 Trakt Continue Watching
OAuth **device flow** (code shown on the TV, entered on the phone), tokens in app-private storage, `sync/playback` for
resume points; surfaces only inside Welly ("what was I watching?"), never as a TV-tab row.

### 5.2 Kasa KLAP
Handshake (`/app/handshake1`, `/app/handshake2`), SHA-256 auth hash from the TP-Link e-mail + password (typed once on
the phone app, never in chat), AES-CBC session; per-device protocol detection (9999 first, then 80).

### 5.3 The companion remote app
- **Pairing:** the TV shows a 6-digit code; the phone finds the TV by mDNS (`_freemytv._tcp`) and pairs over a
  **WebSocket on the LAN** (TLS-PSK from the code). *Opt-in*: Free My TV has never listened on the network, so the
  server is off until you turn on "Remote app" in Home Settings.
- **Controls:** glass touchpad with haptic detents + D-pad; Home/Back/Play; volume via the TV's own ADB keyevents.
- **Keyboard:** when the TV focuses a text field (Welly's input, search), the phone's keyboard opens by itself.
- **Hold to Talk to Welly:** the phone's mic streams text (on-device speech-to-text) to Welly.
- **Live sync:** the 3D house's lights (tap a room), Now Playing with transport, Control Center items.
- **New:** *Find my remote* (the TV plays a chime through the remote's speaker where it has one, else on screen);
  *private listening* (audio routed to the phone's headphones via a local stream, where the app allows); one-tap
  **macros** (the same routines as Welly).

---

## 6. Config keys (config.properties)

| Key | Default | |
|---|---|---|
| `gemini_key` | empty | yours, secret; Google AI Studio |
| `welly_model` | `gemini-3.8-flash` | primary |
| `welly_lite_model` | `gemini-3.5-flash-lite` | on 429 |
| `ollama_url` | empty | e.g. `http://192.168.1.10:11434` |
| `ollama_model` | `qwen3:8b` | |
| `kasa` | empty | fixture → IP:type map (§4.2) |
| `latitude` / `longitude` / `city` | South Brunswick, NJ (40.3807, −74.5321) | |

## Sources

- Gemini models and limits: [ai.google.dev/gemini-api/docs/models](https://ai.google.dev/gemini-api/docs/models),
  [ai.google.dev/gemini-api/docs/rate-limits](https://ai.google.dev/gemini-api/docs/rate-limits),
  [LaoZhang AI](https://blog.laozhang.ai/en/posts/gemini-api-free-tier), [klymentiev.com](https://klymentiev.com/blog/gemini-api-free-tier),
  [Medium, Oct 2026](https://medium.com/@andy.a.g/google-is-cutting-free-gemini-down-to-flash-lite-on-october-9-bc6635a45bc4)
- Ollama: [docs.ollama.com tool calling](https://docs.ollama.com/capabilities/tool-calling), [localaimaster.com](https://localaimaster.com/blog/best-ollama-models-tool-calling),
  [morphllm.com](https://www.morphllm.com/best-ollama-models), [lumadock.com](https://lumadock.com/tutorials/best-ollama-models)
- Kasa: [python-kasa docs](https://python-kasa.readthedocs.io/en/latest/topics.html), [kasa-core](https://docs.rs/kasa-core),
  [finitelabs/control4-tplink](https://github.com/finitelabs/control4-tplink), [python-kasa #1436](https://github.com/python-kasa/python-kasa/discussions/1436),
  [#1752](https://github.com/python-kasa/python-kasa/issues/1752)
- TVmaze: [tvmaze.com/api](https://www.tvmaze.com/api) · ESPN: [pseudo-r/Public-ESPN-API](https://github.com/pseudo-r/Public-ESPN-API), [sportsapis.dev](https://sportsapis.dev/espn-api)
- Apple Music on Android TV: [viwizard](https://www.viwizard.com/apple-music-tips/play-apple-music-on-android-tv.html), [megarohas/apple-music-android-tv](https://github.com/megarohas/apple-music-android-tv)
- Voice on Android TV: [cleanyourtv #1869](https://github.com/Gathin23/cleanyourtv/issues/1869), [sanju.sh](https://sanju.sh/tv-voice), [Android TV Help](https://support.google.com/androidtv/answer/6121377?hl=en)
- Remotes: [Apple Support](https://support.apple.com/en-us/102337), [MacRumors](https://www.macrumors.com/how-to/apple-tv-remote-ios-11-control-center/), [AirBeamTV](https://www.airbeam.tv/what-is-the-best-universal-tv-remote-app-in-2026/)
- Persona: [Conversation Design Institute](https://www.conversationdesigninstitute.com/topics/best-practices), [IBM](https://medium.com/ibm-watson/best-practices-designing-a-persona-for-your-assistant-c2a58666f3c), [Twilio](https://www.twilio.com/en-us/blog/insights/conversational-ai-best-practices)
