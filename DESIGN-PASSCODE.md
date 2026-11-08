# Passcodes and App Lock

## The problem

Every passcode in mahitTV used to be typed on a row of ten digits. You walked the focus along the row and pressed OK on
each digit. That had two problems:
- **Slow:** up to nine presses to reach one digit.
- **Easy to read over your shoulder:** the focus showed which digit you were on, and the click showed when you chose it.

## What other TVs do

| TV | How a PIN is typed | Visible? |
|---|---|---|
| Apple TV (Siri Remote) | A horizontal row of digits, walked with the touch surface. With the old silver remote it's a grid. | Yes: the focused digit shows |
| Netflix and Plex profiles | A digit row or a phone-style grid. | Yes. Plex users keep asking for hidden entry. |
| **Fire TV** (parental and profile PIN) | The **arrow keys** type it, and the screen shows only dots. | **No** |
| **Xbox** (passkey) | Six presses of the controller's buttons. | **No** |
| Phones | A number pad, or a pattern. | The finger shows |

Fire TV and Xbox solve both problems the same way. The keys around OK are the alphabet, so the screen never needs a
cursor.

## mahitTV's remote passcode

- **What it is:** six presses of the ring around OK, using ↑ ↓ ← →. That makes 4,096 codes, and five wrong tries still mean a 30-second wait.
- **What the screen shows:** six short lines. Each press turns the next line into a dot, drawing in from its middle with a small spring pop. The whole pad gives one soft "breath", the same for every direction, so nothing on screen says which arrow was pressed.
- **The rest of the remote:**
  - Back erases the last press, then leaves.
  - The remote's number keys do nothing on a remote passcode.
  - OK is free for the screen's one button, "Forgot Passcode?".
- **Wrong:** the iOS head-shake (right, left, right on a bouncy spring), the error knock, and the dots go back to lines.
- **Right:** the dots light up (gold on "Who's Watching?", green in App Lock).
- **Older PINs:** a four-digit PIN keeps its 0–9 row until it's changed. Every new passcode, set in Settings → Profiles & PINs, is a remote passcode. Which kind a profile has is stored beside its hash (`pinKind`).

It's one component, `PasscodePad`, used by "Who's Watching?" (`LockView`), Settings' passcode screen and App Lock.

## App Lock

Ideas from Glassio's app lock, built mahitTV's way.

- **Settings → Profiles & PINs → App Lock** (each profile has its own; not in Guest):
  - **Locked Apps:** every app on the TV, each with a switch.
  - **Unlock With:**
    - *Profile Passcode* (the default when the profile has one), or
    - *App Passcode*: one code for all the locked apps.
  - **Set / Change App Passcode.**
  - Changing any of these asks for the lock's code first, once per visit to Settings, while apps are locked.
- **Opening a locked app** from anywhere in mahitTV asks for its code first. That covers:
  - the Dock, All Apps, Control Center and Recent Apps;
  - Welly and the screensaver's picks;
  - the remote's app buttons, through `:keys` and `locked-apps.txt`.
- **The sheet (`AppLockView`):** a Liquid Glass card over the dimmed home screen, not a full page. From top to bottom:
  - the app's card with a lock badge;
  - "Enter Passcode" and "{app} is locked.";
  - the six lines;
  - "Use ↑ ↓ ← → on your remote" (a mistake shows here in red);
  - "Forgot Passcode?" in system blue, which OK opens.
- **The sheet's motion:**
  - It comes in from 0.92× and 30 px low on the sheet spring.
  - Right: it swells to 1.06× and fades as the app grows out of it.
  - Closed: it sinks back.
- **Forgot Passcode?:**
  - The App Passcode is reset with the profile's passcode, then the new one is entered twice.
  - A profile's own passcode is let through with the manager's.
  - When nobody could reset it, the link isn't shown.

## Glassio, the rest of it

Glassio is GPL-3.0 and mahitTV is MIT, so only ideas came across, never its code.

| Glassio idea | mahitTV |
|---|---|
| App lock | **Added** (above), with the remote passcode in place of its digit wheel |
| TV info in the menu (IP, RAM, storage) | **Added** to Welly's TV status ("how much space is left?") |
| Dynamic island, Now Playing, music controls | Already here (the dynamic dot) |
| Recent apps, closing apps | Already here (Recent Apps) |
| Remote button mapping, All Apps on a button | Already here (Remote Buttons) |
| Trakt, TMDB picks, Continue Watching | Already here |
| Home Assistant buttons | Already here (Smart Home) |
| HDMI inputs in the menu | Already here (Control Center) |
| Aerial 4K screensaver | Already here (Apple Aerials) |
| Settings backup and restore to a file | A candidate |
| A "What's new" tour after an update | A candidate |
| Doorbell camera pop-up over any app | A candidate (needs cameras in Home Assistant) |
| Philips Hue lights following the screen | A candidate (needs a Hue bridge) |
| Trailers playing on the Top Shelf | A candidate (needs a YouTube player of our own) |
| Icon packs, changing an app's icon | A candidate |
| Phone page to manage settings | A candidate (KeyPage already serves one-time pages) |
