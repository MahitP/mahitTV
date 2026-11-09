# Rooting the TCL (Q77K) — research, verdict and plan

Written 2026-11-09 for the owner's 85-inch TCL Google TV. Its facts come from the read-only audit in
TVOS-HARDWARE-CAPABILITY-REPORT.md:

- **Model:** product `G08_4K_US`, board `Q77K`, model year 2024.
- **Chip:** MediaTek MT9689 (platform `merak`), 32-bit apps, 2.45 GB RAM.
- **Software:** Android 14 / Google TV, build `UTT2.250416.001/AU16`, `user/release-keys`, kernel 5.15 (GKI).
- **Lock state:** verified boot green, so the bootloader is locked.

## Can it be rooted?

**Probably yes, but nobody has confirmed it on this exact model.** The closest proof is a sibling: a 2024 TCL MediaTek Google TV, the C855 (platform `T653T01`). It was rooted in January 2026 ([XDA guide](https://xdaforums.com/t/guide-rooting-tcl-t653t01-models-eg-c855.4774098/)):

1. Install Magisk on the TV. Patch the `boot.img` from the official update (OTA) zip for *exactly* the installed build. The guide's source is TCL's Telegram channel.
2. Run `adb reboot bootloader`. Or use recovery: unplug the TV, hold its power button while plugging it in, and choose *Reboot to bootloader*.
3. **Over USB** from a computer, run `fastboot flashing unlock`. This wipes the TV.
4. Run `fastboot flash boot magisk_patched-….img`, then `fastboot reboot`. The TV warns that it's unlocked, then sets up again from scratch.

The same author's [unbricking guide](https://xdaforums.com/t/guide-unbricking-tcl-tvs.4776759/) is the safety net:
- **What you need:** a 3.3 V USB-UART adapter on the TV's service port (on the C855 an HDMI-shaped port: RX pin 2, TX pin 14, GND pin 11), through a breakout board, at 115200 baud.
- **How it works:** pressing Enter during start-up gives U-Boot's `=>` console.
- **What it restores:** `usb start`, then `mtkupgrade usb` restores a full `.pkg` from a FAT32 stick, or `usb_partial_upgrade_to_emmc boot.img boot_a` restores one partition.

Other routes, for completeness:
- **TCL's test firmware:** it has root built in (`userdebug` / `userroot` builds, then `adb root`). It circulates for some C-series models ([XDA](https://xdaforums.com/t/about-root-rights-on-tcl-tvs.4690567/)), but none is known for the Q77K.
- **Community firmware with Magisk already in it** exists for older Realtek TCLs ([blog](https://blog.waqasmohammad.com/tcl-55c641k-root-debloat/)), not this chip.
- **MediaTek boot-ROM exploits** (mtkclient) aren't documented for the MT96xx TV chips.

**What has to be true for the C855 route to work here:**
- The Q77K's bootloader must answer `fastboot flashing unlock`. TCL can turn that off per model.
- A USB link to the TV must work in fastboot. The guide doesn't say which cable; a TV's USB-A ports usually need a USB-A to USB-A cable for that.
- The exact `boot.img` for AU16 must be found.

There's a test that changes nothing (step 0 below).

## What it would cost

- **Netflix, Prime Video and Disney+ drop to standard definition, or refuse to play.** Unlocking drops Widevine from L1 to **L3** ("Widevine will be L3", per the guide's author). On an 85-inch 4K set that's the biggest cost. Relocking with the stock images sometimes brings L1 back and sometimes doesn't; it varies by maker.
- **The TV is wiped.** Every app, sign-in, Wi-Fi network, mahitTV profile, App Lock and Welly memory goes. mahitTV goes back on from this repo, but its settings live in the app's private files, which can't be copied out without root.
- **Brick risk.** A wrong `boot.img` means a boot loop: fastboot fixes that with the stock image. A bad flash of anything else needs the UART route and the matching full `.pkg`.
- **Updates.** Each TCL update replaces the patched boot (root goes) and may relock or close the hole. Updates would have to be held back or re-rooted by hand each time.
- **Google's checks.** Play Integrity fails on an unlocked TV, so some apps refuse to run or vanish from the Play Store. Modules can hide this for some apps, not reliably.
- **Warranty:** void.

## What root would add (mahitTV today vs rooted)

mahitTV already drives the TV through its own ADB connection (the `shell` user). That covers a great deal without root:
- force-stopping apps (Prime Video, TV Settings and the Play Store included, since PR 26);
- turning apps off;
- system settings, roles and app permissions;
- key input and screenshots.

So most of the wishes on the list **don't need root, and are done**:

| Wish | Needs root? | Where it stands |
|---|---|---|
| Mic button opens Welly, not Gemini | No | Done and tested on this TV (PRs 29–30). Welly holds the assistant role, so the remote sends the key to mahitTV. |
| Gemini gone everywhere | No for the rest | Gemini no longer runs from the mic. `katniss` itself can be turned off with `pm disable-user` (no root), which Original would turn back on. That's next, tested on the TV first. |
| Close Prime Video, TV Settings, Play Store | No | Allowed since PR 26 (`am force-stop` over ADB). Needs a check on the TV. |
| App-open zoom, no "long wait then pop" | No | **Fixed in PR 37.** The launch layer was never laid out on Android, so the zoom never showed. |
| Move mode's white line, outline and small app | No | **Fixed in PR 37.** The same bug, for move mode's layer. |
| Welly does everything | Mostly no | Done over ADB (PR 29). Root adds a few (below). |
| **mahitTV's own volume bar instead of TCL's** | **Yes** | TCL takes the volume keys inside the system before any app. See the plan below. |
| **Remove the TCL and Google TV start-up logos** | **Yes** | The Google TV animation can be replaced without touching the system (a Magisk module). The TCL logo comes from the bootloader: possible, but the riskiest change of all. |

**What only root adds:**
- **Volume:** mahitTV's iPhone-style volume HUD everywhere, with TCL's bar gone. The safest way is a Magisk module that overlays the remote's key layout file, so the volume buttons arrive as keys TCL doesn't claim, and mahitTV sets the volume itself. An LSPosed hook into TCL's volume controller is the heavier alternative.
- **Start-up:** the boot animation replaced with mahitTV's Liquid Light intro, and possibly no TCL logo.
- **Speed:**
  - stop TCL's background services for good (TGuard, telemetry, promo services that can't be turned off over ADB);
  - keep mahitTV resident in memory (it's often killed on a 2.45 GB TV, so it starts cold);
  - tune the low-memory killer.
  On this TV that's the most noticeable gain, and it shortens every app open.
- **Privileged app:** mahitTV as a system app gets the permissions apps can't get otherwise:
  - setting secure settings with no ADB prompt ever;
  - injecting keys;
  - live window thumbnails for Recent Apps;
  - starting at boot with no TCL limits.
- **Every button:** the remote's buttons remapped at the key-layout level (Input, the app buttons, the gear).
- **Ads:** a system-wide ad and tracking blocker (hosts file).
- **Welly with `su`:** changing TCL picture and sound modes through TCL's own services, which are signature-only today, and rebooting to recovery.

## Verdict

**Not yet, and maybe never on the TV itself.**
- **What's left for root:** after PR 37 and the next `katniss` step, root mainly buys two things you'd see (TCL's volume bar, the start-up logos), plus speed.
- **What it costs:** losing 4K and HD from Netflix, Prime and Disney+ on an 85-inch family TV, a full wipe, and a brick risk on a model nobody has proven.

**If you still want it** (it *is* fun), do it in an order that keeps the risk small:

0. **Prove it's unlockable, with nothing changed.** Over USB, run `adb reboot bootloader`, then `fastboot getvar unlocked` and `fastboot flashing get_unlock_ability`. Then `fastboot reboot`. These read only; nothing is written. If it says no, the question is closed.
1. **Find the matching `boot.img`** for `UTT2.250416.001/AU16`, and the full `.pkg` for the UART fallback.
2. **Get a 3.3 V USB-UART adapter** and an HDMI breakout. Check you can reach the `=>` console *before* unlocking.
3. **Keep 4K streaming:** plug a Google TV Streamer, Chromecast with Google TV or Apple TV into HDMI for Netflix, Prime and Disney+, since the rooted TV would be at L3.
4. **Unlock and root** as above, then set mahitTV up again from this repo.
5. **mahitTV's root features**, each as its own reversible Magisk module or setting, in this order:
   - the volume key layout and HUD;
   - mahitTV kept in memory and TCL's background services off;
   - mahitTV as a privileged app;
   - the boot animation;
   - the TCL logo last, if ever.
