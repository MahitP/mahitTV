# TV Hardware Capability Report

**Device:** TCL Google TV (`G08_4K_US`), audited over network debugging
**Date:** 2026-11-01 (the TV's clock read 2026-11-02 02:31 GMT)
**Code compared:** mahitTV `main` at `ea2219a`. File and line references point there, and this `free-my-tv` folder is still behind `main`, so its copies differ
**Method:** read-only. The commands were `getprop`, `dumpsys`, `pm list`, `cmd package query-activities` and `resolve-activity`, `cmd appops query-op`, `settings get`, `getevent -pl`, and `cat`/`tail` on readable files. Nothing was installed, granted, launched or written. After the audit, the default home app, the third-party app count, Do Not Disturb and accessibility all read the same as before.

The TV was asleep (`mWakefulness=Asleep`) for most of the audit, so nothing here shows how it behaves under playback. Serial numbers, MAC addresses, the Wi-Fi name, the TV's IP address and account details are left out on purpose. **The repo is public, so review this file before committing it.**

## Summary

1. **The TV runs Android 14 (SDK 34).** That clears the biggest install risk: mahitTV's key service, Home hold and volume display are only disabled on TCL's Android 11.
2. **mahitTV isn't installed on this TV.** The first install is fresh, so the "survives the upgrade" checks don't apply yet.
3. **Three things to deal with before the first install:** the TV's time zone is GMT, its Wi-Fi was saved by the setup app, and TCL's background-start block applies to new apps.
4. **TCL's native screens are open to other apps.** Inputs, Listen Alone, Game Bar, Sound, Picture and Devices all resolve to exported activities with no permission needed. mahitTV uses only some of them.
5. **TCL's own system services are off limits.** Most TCL permissions I could list are signature-level, and the `tcl_*` binder services weren't called in this read-only pass. "Baked into the firmware" is realistic for the look and navigation. It isn't realistic for changing picture modes, CEC or audio routing directly.
6. **The hardware is modest.** Four Cortex-A73 cores, about 2.45 GB of RAM and a 32-bit Android userland make performance the main constraint.

## 1. Device specifications

| Area | Finding | Source |
|---|---|---|
| Brand and model | TCL "Smart TV Pro", product `G08_4K_US`, board `Q77K`, model year 2024 | `ro.product.*`, `ro.boot.product`, `ro.odm.tcl.model.year` |
| Platform | Google TV (`ro.tcl.product_type=GTV`, `android.software.leanback_only`) | `getprop`, `pm list features` |
| Android | 14 (SDK 34), first API level 31, security patch 2025-12-05 | `ro.build.version.*` |
| Build | `TCL/G08_4K_US/G08:14/UTT2.250416.001/AU16:user/release-keys`, verified boot green | `ro.build.fingerprint` |
| Kernel | 5.15.180-android14-11 | `uname -r` |
| Chip | MediaTek MT9689 (platform `merak`, hardware `mt5896`) | `ro.soc.model`, `ro.board.platform` |
| CPU | 4 × Cortex-A73 (part `0xd09`), cpu0 maximum reported 1.39 GHz | `/proc/cpuinfo`, `cpufreq` |
| **App ABI** | **32-bit only: `armeabi-v7a`, `armeabi`** | `ro.product.cpu.abilist` |
| GPU | Mali (driver `mali.mt5879`), OpenGL ES 3.2, Vulkan 1.3 level 1 | `ro.hardware.egl`, `ro.opengles.version`, `pm list features` |
| NPU | NNAPI services `mtk-mdla_shim` and `mtk-neuron_shim` exist | `service list` |
| RAM | 2.45 GB visible, 918 MB swap. **657 MB available at audit, load average about 19.6 while asleep** | `/proc/meminfo`, `uptime` |
| App heap | 192 MB normal, 512 MB large | `dalvik.vm.heap*` |
| Storage | 21 GB user data, about 16 GB free | `df` |
| Panel | 3840×2160, **one mode only: 60 Hz** | `dumpsys display`, SurfaceFlinger |
| Android UI | Rendered at 1920×1080 (override), density 320 | `wm size`, `wm density` |
| HDR | Dolby Vision, HDR10, HLG and HDR10+ (`supportedHdrTypes=[1,2,3,4]`) | `dumpsys display`, HWC |
| Wide color and ALLM | Wide color not reported to apps. `mAllmSupported=false` at the Android layer | SurfaceFlinger, `dumpsys display` |
| HDMI | 4 ports with CEC, **port 4 has ARC and eARC** | `dumpsys hdmi_control` |
| Other inputs | Tuner (`TunerInputService/HW2`), 4 HDMI passthroughs (`HW15`–`HW18`), AirPlay, TCL Channel | `dumpsys tv_input` |
| Video decoders | AVC, HEVC, AV1, VP8, VP9, Dolby Vision, MPEG-2, AVS, AVS2, VC-1 | `dumpsys media.player` |
| DRM | Widevine, PlayReady, FairPlay, ClearKey | `service list` |
| Audio out | Speaker active. Volume tables also exist for Bluetooth A2DP, HDMI, S/PDIF and IP. Music stream range 0–100 | `dumpsys audio` |
| Audio formats | AC3, E-AC3, E-AC3 JOC, MAT, TrueHD, DTS, DTS-HD, AC4, AAC, MP3, PCM | `dumpsys media.audio_policy` |
| Microphones | Built-in far-field mic (`persist.tcl.far_field_mic_status=1`), `mic_mute` switch, USB mic and headset input types | `getprop`, audio policy, `dumpsys input` |
| Bluetooth | MT76x3. A2DP source and sink, AVRCP, HID host and device, GATT. **No LE Audio, no HFP.** One connected A2DP device at a time | `bluetooth.profile.*`, `dumpsys bluetooth_manager` |
| Wi-Fi | MT7663, Wi-Fi Direct. Ethernet is also supported | `persist.tcl.wlan.*`, `pm list features` |
| Remote | `BT_RC923A_B1` over Bluetooth, battery 56% at last report, plus an IR receiver | `dumpsys input`, `persist.sys.tcl.btrc.*` |
| Other features | Google Cast receiver, AirPlay, Alexa, `GEMINI_TV_EXPERIENCE` flag | `pm list features`, packages |
| Users | 2 Android users exist (user 0 and a secondary user 10) | `pm list users` |

### Things that don't add up

- **Screen size:** the firmware says `ro.odm.tcl.panel_size=55`, and the display reports 59 dpi, which works out to about 75 inches. Your TV is 85 inches. The display's identity reads `MStar Demo`, so these values look like generic placeholders. Software can't confirm the size, so **mahitTV shouldn't rely on any of them**.
- **HDR brightness:** `mMaxLuminance=100` is far too low for an HDR panel, so it's probably a placeholder too.
- **eARC:** port 4 reports eARC and the setting is on, but `ro.feature.hdmi_earc=false`. It needs a test with a real soundbar.
- **Time zone:** see P0-1 below.

## 2. TCL and MediaTek system surface

**325 binder services are registered.** The TCL ones include:

| Service | Interface | Likely role, from its name |
|---|---|---|
| `tcl_tv_display` | `com.tcl.tv.display.ITvDisplayManagerService` | Picture and display |
| `tcl_tvinput` | `com.tcl.dtv.ITService` | TV inputs and tuner |
| `tcl_mode_manager` | `android.mode.ITclModeManager` | Modes such as shop or hotel |
| `tcl_flex` | `tcl.flex.ITclFlexSpeakerService` | Wireless speakers |
| `tcl_connectivity` | `android.net.ITclConnectivityService` | Networking |
| `hdmi_control` | `android.hardware.hdmi.ITclHdmiControlServiceEx` | TCL's HDMI extensions |
| `aio` | `tcl.aio.ITclAioManager` | All-in-one features |
| `lock_manager` | `tcl.os.lock.ITLockManager` | Parental or app lock |
| `tcl_runningtime_manager` | `android.os.ITRunningTimeManager` | Run-time counters |
| `TCameraService`, `cameramotorservice` | `tcl.tvcamera.*`, `tcl.tstreaming.*` | Camera support (no built-in camera is reported as a feature) |
| `TFirmwareManagerService` | `tcl.hardware.ITFirmwareManager` | Firmware |

**Vendor services running:** `tcl_audio_aidl_service`, `tcl_display_service`, `tcl_screencontrol_service`, `tcl_light_aidl_service`, `tcldevmanager_hal_service`, `tcl_component_service`, `cecext-hal`, `hdmi_turbo_service`.

**Android HALs present:** Tuner, TV Input, HDMI CEC, HDMI connection, eARC, Bluetooth audio, Lights, Power, Thermal, Sensors, NNAPI.

**What an app may use:** of the 6 TCL or MediaTek permissions matched in `pm list permissions`, 4 are signature-level. The only two open to any app are `com.tcl.dashboard.ACCESS_MESSAGE_EXT_SERVICE` and `com.tcl.tv.passive.SwitchChangePermission`. No service above was called, since a call could change state, and their method lists aren't public. Treat them as unavailable unless a test on the TV shows otherwise.

**TCL feature flags:** `ro.feature.*` has 253 entries. Those relevant to mahitTV:

| Flags | Meaning for mahitTV |
|---|---|
| `tguard`, `tguard_app_boot`, `tguard_apprecord` | TCL controls background starts (P0-3) |
| `tinput_customization`, `tinput_customizable_button`, `tinput_shortcut_config`, `tinput_key_collection`, `tinput_fifth_back_rule`, `tinput_disable_native_combinationrules` | TCL has its own key handling, so check for key conflicts on the TV |
| `hdmi_turbo`, `hdmi_qms_setting`, `hdmi_earc`=false, `hdmi_cec_control_earc`, `hdmi_cec_auto_switch` | HDMI features belong to the firmware |
| `audio_farvoice`, `audio_wake_farvoice`, `light_far_filed_voice_led` | Far-field voice, with an LED |
| `picture_imax`, `tif_dolby_filmmaker`, `systemui_dolby_logo` | Picture features owned by the firmware |
| `alarm_poweron`, `alarm_wakeup_control`, `power_str`, `power_auto_standby` | Scheduled power-on exists at the firmware level |
| `wifi_flex_connect*` | TCL wireless speakers and subwoofer |
| `hotel_customization`, `factory_grading_machine`, `lock_customization` | Keep away from these |

## 3. Native intent map

"Resolves" was checked with `cmd package resolve-activity`, which doesn't launch anything. The screens haven't been opened, so **some could still refuse to start from another app**.

### Screens that exist

| Purpose | Action | Opens | mahitTV today |
|---|---|---|---|
| All settings | `android.settings.SETTINGS` | `com.android.tv.settings/.MainSettings` | Used |
| **Inputs** | `com.android.tv.action.VIEW_INPUTS` | `com.tcl.suspension/.ui.main.InputActivity` | Not used: builds its own list with `TvInputManager` |
| Picture | `android.settings.DISPLAY_SETTINGS` | TCL `PictureActivity` | Used |
| Sound, TCL's page | `com.android.tv.action.VIEW_SOUND_SETTINGS` | TCL `SoundActivity` | Used in Quick Panel |
| Sound and display | `android.settings.SOUND_SETTINGS` | `DisplaySoundActivity` | Used |
| **Devices and Bluetooth** | `com.android.tv.settings.DEVICES` | TCL `DevicePrefActivity` | Used in Quick Panel only |
| Add a device | `com.google.android.intent.action.CONNECT_INPUT` | `AddAccessoryActivity` | Used |
| Bluetooth picker | `android.bluetooth.devicepicker.action.LAUNCH` | `BluetoothDevicePickerActivity` | Not used |
| Wi-Fi | `android.settings.WIFI_SETTINGS` | `NetworkActivity` | Used |
| Date and time | `android.settings.DATE_SETTINGS` | `DateTimeActivity` | Not used |
| Accessibility | `android.settings.ACCESSIBILITY_SETTINGS` | `AccessibilitySettingsActivity` | Used |
| Notification access | `android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` | `NotificationAccessActivity` | Needed for Now Playing |
| Usage access | `android.settings.USAGE_ACCESS_SETTINGS` | `AppUsageAccessActivity` | Not checked in code |
| Overlay permission | `android.settings.action.MANAGE_OVERLAY_PERMISSION` | `SystemAlertActivity` | Manifest declares the permission |
| Apps | `android.settings.MANAGE_APPLICATIONS_SETTINGS` | `AppsActivity` | Uses a different action (below) |
| Captions | `android.settings.CAPTIONING_SETTINGS` | `CaptionSetupActivity` | Not used |
| About | `android.settings.DEVICE_INFO_SETTINGS` | `AboutActivity` | Used |
| **Default home** | `android.settings.HOME_SETTINGS` | Permission controller's home screen | Not used |
| Assistant | `android.intent.action.ASSIST` | Google TV search (Katniss) | Not checked in code |
| TV tuner setup | `android.media.tv.action.SETUP_INPUTS` | `com.tcl.tv/com.tcl.player.TVActivity` | Not used |

### TCL's own screens (exported)

| Feature | Action | Opens |
|---|---|---|
| **Listen Alone** | `tcl.intent.action.LISTEN_ALONE` | `com.tcl.t_solo/...ListenAloneActivity` |
| **Game Bar** | `com.tcl.gamebar.main` | `com.tcl.gamebar/MainActivity` |
| Sound test and calibration | `com.tcl.hearaid.action.portalmain` | `com.tcl.hearaid/MainActivity` |
| TCL home dashboard | `com.tcl.dashboard.MAIN` | `com.tcl.dashboard/MainActivity` |
| TCL messages | `com.tcl.messsagebox.main` (TCL's spelling) | `com.tcl.messagebox/MainActivity` |
| Channel manager | `com.tcl.tv.action.CHANNEL_MANAGER` | `com.tcl.tv/...ChannelManagerActivity` |
| Media player | `com.tcl.ui_mediaCenter.action.mediacenter` | `com.tcl.ui_mediaCenter/MainActivity` |
| Wireless speakers | `com.tcl.hearaid.wireless_speakers.settings` | Requires a TCL permission, so **another app can't open it** |

### Actions that don't exist on this TV

| Action | Effect on mahitTV |
|---|---|
| `android.settings.BLUETOOTH_SETTINGS` | **mahitTV's Bluetooth shortcut and Settings' Remotes & Accessories land on general Settings** ([HomeApps.java:1020](app/src/io/freemytv/HomeApps.java:1020), [SettingsActivity.java:1921](app/src/io/freemytv/SettingsActivity.java:1921)). They have safe fallbacks but miss the right page |
| `android.settings.WIRELESS_SETTINGS`, `ADD_ACCOUNT` | Safe: later entries in `tries` take over |
| `android.settings.ZEN_MODE_SETTINGS` | No Do Not Disturb page from this action |
| `android.settings.DREAM_SETTINGS` | No screensaver page from this action |
| `android.settings.NIGHT_DISPLAY_SETTINGS`, `android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL`, `android.media.tv.action.WATCH_NEXT` | Not available |
| `com.android.systemui/.Somnambulator` | The Screensaver shortcut falls back to mahitTV's preview ([HomeApps.java:1030](app/src/io/freemytv/HomeApps.java:1030)) |
| `android.settings.APPLICATION_SETTINGS`, `SYNC_SETTINGS` | Resolve to Android's chooser, not a direct page, so **the Apps row may show a chooser** ([SettingsActivity.java:1918](app/src/io/freemytv/SettingsActivity.java:1918)). `MANAGE_APPLICATIONS_SETTINGS` goes straight there |

## 4. Remote and key maps

| Device | Layout file | Notes |
|---|---|---|
| `BT_RC923A_B1` (Bluetooth) | `/system/usr/keylayout/Vendor_0417_Product_0304.kl` | 277 mapped keys, including colored buttons, channel, captions, guide and search |
| MTK Smart TV IR Receiver | `/vendor/usr/keylayout/Vendor_3697_Product_0001.kl` | **Not readable**: permission denied |
| MTK PMU WOBT (wake) | Same IR file | Wake-up path |
| MTK TV KEYPAD (buttons on the TV) | `/vendor/usr/keylayout/Vendor_3697_Product_0002.kl` | Not readable |
| `virtual-remote`, `virtual-search` | `/product/usr/keylayout/virtual-remote.kl`, `Generic.kl` | Google TV remote app and voice |
| `gIrTouch_Mouse` | None | A touch and mouse device |

**Not verified:** mahitTV's TCL YouTube key, `KEY_YOUTUBE_TCL = 4065` ([RemoteKeys.java:50](app/src/io/freemytv/RemoteKeys.java:50)). Neither readable layout confirms that code, or which codes the Netflix, Prime and other app buttons send. A layout file can't settle this, because TCL also has its own key handling. It can be settled while staying read-only, by logging key events with `getevent -lt` while you press each button.

`sys.tcl.volume_key_repeat_delay=150` also sets TCL's volume repeat, which is useful when checking "one step per press".

## 5. Media, notifications, accessibility and home

| Capability | State on this TV | Available to mahitTV? |
|---|---|---|
| Notification listener | Only Google's launcher is enabled | Yes, once you allow it in Notification Access |
| Media sessions | Netflix, Spotify and Bluetooth have registered sessions; none was playing | Yes, needs notification access. **Welly can already control playback; Control Center can't** ([WellyTools.java:828](app/src/io/freemytv/WellyTools.java:828)) |
| Accessibility service | None enabled, screen reader off | Yes, once you enable it. mahitTV's key service depends on this |
| Do Not Disturb | Off | Reading works. Changing it needs policy access, and this audit found no settings page for it |
| Audio routing | Speaker active, system-controlled | Read and link only. Apps can't move system media output between devices |
| HDMI CEC | Enabled, `mIsCecAvailable=false` while asleep | No: `HdmiControlManager` isn't open to normal apps |
| TV inputs | 4 HDMI and tuner listed | Yes: already used by Inputs |
| Screensaver | Google's `dreamx`; on sleep and after a timeout | mahitTV's would appear after install. Setting it takes the existing mode switch |
| Picture modes | TCL-owned | Link only |
| AirPlay | `com.tcl.airplay2`, MediaTek input | Can link to it. Can't start a session |
| Voice | Recognizer is Google TTS's, assistant is Katniss | Yes on a button press. Welly already uses `SpeechRecognizer` |
| Default home | `role.HOME` held by `launcherx` | **Unknown: see P2-1** |

## 6. Installed apps

**This TV has 32 launchable apps, not 27.** It has 172 packages in total: 157 system and 15 third-party, with none disabled. I couldn't find what the figure 27 refers to, so this table covers all 32.

The deep links are the `VIEW` schemes and hosts from each app's resolver table. **Certificates are SHA-256 hashes read from each APK's signing block on the TV.** They record what's installed; I had no publisher reference to compare them with. Apps using Play App Signing show a Google subject, which is normal.

| App | Version | Target SDK | Launcher activity | Deep links | Certificate | From |
|---|---|---|---|---|---|---|
| `com.amazon.amazonvideo.livingroom` | 6.24.13+v16.0.1.93-a | 35 | `com.amazon.ignition.IgnitionActivity` | http, https → app.primevideo.com, watch.amazon.co.jp, watch.amazon.co.uk, watch.amazon.com, watch.amazon.de | `2f19adeb284eb36f` Amazon Services LLC L=Las Vegas | Play |
| `com.android.tv.settings` | 3.44.02_fdaa0c3_2603 | 31 | `.MainSettings` | None declared | `25606cd9fadf3da6` TCL | System |
| `com.android.vending` | 53.4.17-34 | 37 | `com.google.android.finsky.tvmainactivity.TvMainActivity` | google-orchestration, http, https, market → details, market.android.com, play.google.com, return, search +1 more | `7ce83c1b71f3d572` Google Inc. | Play |
| `com.apple.atve.androidtv.appletv` | 17.0.0 | 36 | `.MainActivity` | https → tv.apple.com | `771d8674d3d9837c` Google Inc. | Play |
| `com.cbs.ott` | 17.2.0 | 36 | `com.paramount.android.pplus.features.splash.tv.SplashMediatorActivity` | http, https, pplus, viacom → com.cbs.ott, www.paramountplus.com | `6623d6eff75750d5` CBS Interactive | Play |
| `com.crunchyroll.crunchyroid` | 3.76.0 | 35 | `.splash.ui.SplashActivity` | crunchyroll | `1c2dbdd136487d30` Crunchyroll | Play |
| `com.disney.disneyplus` | 26.19.0+rc3-2026.10. | 36 | `com.bamtechmedia.dominguez.main.MainActivity` | disneyplus, https → click.mail.disneyplus.com, disneyplus.com, eu-central-1.mail2.disneyplus.com, eu-west-1.mail2.disneyplus.com, links.messaging.disneyplus.com +4 more | `b4d251e979ea974d` Google Inc. | Play |
| `com.google.android.play.games` | 2026.10.70836 | 37 | `com.google.android.apps.play.games.app.atv.features.home.HomeActivity` | None declared | `7ce83c1b71f3d572` Google Inc. | Play |
| `com.google.android.videos` | 4.40.15.23-tv | 33 | `.tv.presenter.activity.TvLauncherActivity` | http, https → .youtube.com, play.google.com, youtube.com | `5aad2bee6db95d17` Google Inc. | Play |
| `com.google.android.youtube.tv` | 7.28.301 | 36 | `com.google.android.apps.youtube.tv.activity.ShellActivity` | http, https, vnd.youtube, vnd.youtube.launch → m.youtube.com, www.youtube.com, youtu.be, youtube.com | `21199d112cece428` Google Inc. | Play |
| `com.google.android.youtube.tvmusic` | 1.12.07 | 36 | `com.google.android.apps.youtube.tvmusic.activity.MainActivity` | http, https → music.youtube.com | `2e1de65f238682a3` Google Inc. | Play |
| `com.google.android.youtube.tvunplugged` | 3.83.03 | 34 | `com.google.android.apps.youtube.tvunplugged.activity.MainActivity` | http, https → tv.youtube.com | `e1fdaf1b3246fb59` Google Inc. | Play |
| `com.hulu.livingroomplus` | C59F39CFP3.10.33 | 33 | `.WKFactivity` | android-app, http, https, hulu → com.hulu.plus, video, www.hulu.com | `cfbdf8dcd9646924` Hulu | Play |
| `com.instagram.airwave` | 66.1.0.11.110 | 35 | `.AirwaveMainActivity` | None declared | `efbba5eeb93079cb` Meta Platforms Inc. | Play |
| `com.mvas.stb.emu.free` | v2.0.24 | 36 | `com.mvas.stbemu.activities.MainActivity` | None declared | `ba6890eafdce463b` Maxim Vasilchuk | Play |
| `com.netflix.ninja` | 13.2.2 | 36 | `.MainActivity` | http, https, netflix, nflx → www.netflix.com | `363863596ea99241` Netflix | Play |
| `com.peacocktv.peacockandroid` | 7.10.200 | 35 | `com.peacock.peacocktv.GoogleMainActivity` | https → www.peacocktv.com | `e5efd56fa3246e48` Peacock TV LLC | Play |
| `com.roblox.client` | 2.745.1137 | 35 | `.startup.ActivitySplash` | https, roblox, robloxglobal, robloxmobile → connections.roblox.com, ro.blox.com, roblox.com, www.roblox.com | `2bebd189e8d31064` Roblox Corporation | Play |
| `com.spotify.tv.android` | 9.1.94.1926 | 35 | `com.spotify.app.androidtv.MainActivity` | content, http, https, spotify → media, open.spotify.com | `fd450f5f94b9dba0` Spotify | Play |
| `com.tcl.browser` | 8.25.012_533599f_261 | 36 | `.portal.home.activity.HomePageActivity` | asset, browsehere, content, file, http, httplive, https, mms, mmsh, mmst, rtmp, rtmpe, rtmps, rtp, rtsp, ssai, udp → com.tcl.browser | `af0f74e05565b6c4` Google Inc. | Play |
| `com.tcl.dashboard` | 1.684_d1c7732_250526 | 34 | `.MainActivity` | tcl → com.tcl.dashboard.overseas | `25606cd9fadf3da6` TCL | Play |
| `com.tcl.exhibit` | 2.70.05_42a6051_2509 | 33 | `.portal.view.activity.MainActivity` | None declared | `25606cd9fadf3da6` TCL | TCL |
| `com.tcl.ocean.instructions` | 3.01.01_42c1b2d_2601 | 30 | `.portal.home.HomeActivity` | None declared | `25606cd9fadf3da6` TCL | System |
| `com.tcl.smartalexa` | 6.13.25_d9fcb51_2601 | 31 | `.IconShow` | None declared | `25606cd9fadf3da6` TCL | System |
| `com.tcl.suspension` | 3.4.9.af04a96.nps | 30 | `.ui.main.MainActivity` | None declared | `25606cd9fadf3da6` TCL | System |
| `com.tcl.t_solo` | 3.0.58 | 30 | `com.tcl.bluetoothlistenalone.ListenAloneActivity` | None declared | `25606cd9fadf3da6` TCL | System |
| `com.tcl.tv.plus` | 3.5.18.918_23edaeb | 35 | `com.tcl.waterfall.overseas.main.MainActivity` | tcl, tvplus → com.tcl.tv.plus.home, com.tcl.tvplus, com.tcl.waterfall.overseas.detail | `f8d272f7f57cee1b` TCL Research America | TCL |
| `com.tcl.tv` | 3.15.00_2adf9be_2603 | 31 | `com.tcl.player.TVActivity` | app → com.tcl.tv | `25606cd9fadf3da6` TCL | System |
| `com.tcl.ui_mediaCenter` | 8.30.63 | 30 | `.main.MainActivity` | file, ftp, http, https | `25606cd9fadf3da6` TCL | System |
| `com.tubitv` | 10.40.5000 | 36 | `.activities.MainActivity` | http, https, tubitv → account, activate, add-account, collection, creators +22 more | `aac5ab52f6b789dc` Adrise | Play |
| `com.wbd.stream` | 7.14.0.69 | 36 | `com.wbd.beam.BeamActivity` | https → play.hbomax.com, play.max.com | `619cbb027b715560` MLB Advanced Media | Play |
| `tv.twitch.android.app` | 13.0.0.2 | 34 | `tv.twitch.starshot64.app.StarshotActivity` | twitch → browse, channel, clip, game, home +4 more | `1338f9b049893cc7` Twitch | Play |

### Signing certificates

These groups cover the 32 launchable apps, the third-party apps and the main TCL, MediaTek and Google TV system packages: 83 in total, of 172.

| SHA-256 | Subject organization | Packages |
|---|---|---|
| `25606cd9fadf3da6a16e741db6d70c3c5c5ebf1ec58bfc327517dec430ceba98` | TCL | `com.android.tv.feedbackconsent`, `com.android.tv.frameworkpackagestubs`, `com.android.tv.mdnsoffloadmanager`, `com.android.tv.overlay.networkstack`, `com.android.tv.overlay.wifi.resources`, `com.android.tv.settings` and 44 more |
| `7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053` | Google Inc. | `com.android.vending`, `com.google.android.gms`, `com.google.android.gsf`, `com.google.android.play.games`, `com.google.android.tts` |
| `1338f9b049893cc70b78432a177582f90bd4bc6296ea4ed35bcc7df59687ac53` | Twitch | `tv.twitch.android.app` |
| `14560ff8041d3f85e7aefd14c8c4002d6dacdc0b06f3a013bd111a7ed2e8d30a` | Google Inc. | `com.google.android.apps.tv.dreamx` |
| `1c2dbdd136487d30a37ee0ee380e615e8a2341c0f2ac91165199e54b744c7ec7` | Crunchyroll | `com.crunchyroll.crunchyroid` |
| `21199d112cece428c82cb0df221da24ff8674c7a982a83a17a970eec43305b4a` | Google Inc. | `com.google.android.youtube.tv` |
| `2bebd189e8d3106401347056c93d045b61e20e22d0c3cbed85474aeb00a3d12a` | Roblox Corporation | `com.roblox.client` |
| `2e1de65f238682a338f8a893f8f453cae8c136308e5ae4ef5730f48bbd9a1802` | Google Inc. | `com.google.android.youtube.tvmusic` |
| `2f19adeb284eb36f7f07786152b9a1d14b21653203ad0b04ebbf9c73ab6d7625` | Amazon Services LLC L=Las Vegas | `com.amazon.amazonvideo.livingroom` |
| `32a2fc74d731105859e5a85df16d95f102d85b22099b8064c5d8915c61dad1e0` | Unknown | `com.tcl.android.webview` |
| `363863596ea99241eb71b1a985553aa604de3ea3c5f0c546742390e682164e6b` | Netflix | `com.netflix.ninja` |
| `456edbc33222d20ff158d42e9fab0252dbe0514d6e1c39588d6b1982cc189137` | Google Inc. | `com.google.android.tv.remote.service` |
| `5202f9072a0f75c831b2296428755df2548296947d11804e7579a934a5f700ae` | Google Inc. | `com.google.android.apps.tv.launcherx` |
| `53d41b6808d060367088e309893686a8d53674fb69b90ae408c5af0f0be29e91` | Google Inc. | `com.google.android.katniss` |
| `5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6` | Google Inc. | `com.google.android.videos` |
| `619cbb027b715560b843d15a5599f900485af1cd4294097811a77609bc8273a8` | MLB Advanced Media | `com.wbd.stream` |
| `6623d6eff75750d58302c39ac460a1d3da4f3dae6ae0f1569d3f7b42a7527c2b` | CBS Interactive | `com.cbs.ott` |
| `771d8674d3d9837c9edf11b11873443998f19105abcecab425ed9b8e6fefff9b` | Google Inc. | `com.apple.atve.androidtv.appletv` |
| `aac5ab52f6b789dc6f238910b6c530bea1bdbc36d6a7ede618a92329aa3a2fd0` | Adrise | `com.tubitv` |
| `af0f74e05565b6c464e014f15911da90416562c0e0d9d74d30a474dceb29935a` | Google Inc. | `com.tcl.browser` |
| `b4d251e979ea974d8f070feb5ab69fc4c2873f4e8add421ff63ad5f2789d63d4` | Google Inc. | `com.disney.disneyplus` |
| `ba6890eafdce463b5813789bc0949ae7cd3ea600d78a78a362a3507dc073e8b3` | Maxim Vasilchuk | `com.mvas.stb.emu.free` |
| `c0a20db233dd94e171d055da5ac5560acafb540bfed532136caa600aeb9fc006` | TCL | `com.mediatek.support.webview` |
| `cfbdf8dcd96469243dde618ad6a12fa37685ba98f5c6f4c778ba9fbf75a8b145` | Hulu | `com.hulu.livingroomplus` |
| `e1fdaf1b3246fb5939f99c9e6d85a581f7700c19e5533e170440b64c8d5ec849` | Google Inc. | `com.google.android.youtube.tvunplugged` |
| `e5efd56fa3246e480546ecac4d7928051422a26c5676190e459791fafa8c8093` | Peacock TV LLC | `com.peacocktv.peacockandroid` |
| `efbba5eeb93079cb0202cae84821fceb19a41944a43d376e5e4a76bba9032f86` | Meta Platforms Inc. | `com.instagram.airwave` |
| `f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83` | Google Inc. | `com.google.android.tungsten.setupwraith` |
| `f8d272f7f57cee1bec8c3a6d4569b2c41a4aa88211362624521ee891068709c0` | TCL Research America | `com.tcl.tv.plus` |
| `fd450f5f94b9dba0ae7f6b19e0b512e041ea7fc2cc291b71737b319ba7f4b3d4` | Spotify | `com.spotify.tv.android` |

## 7. Findings to act on

### P0: before the first install

| # | Finding | Action |
|---|---|---|
| P0-1 | **The TV's time zone is GMT** (`persist.sys.timezone=GMT`, automatic time zone on). mahitTV uses the system time zone for its clock and sunset theme, so **it would run about 5 hours ahead of New Jersey** | Check the TV's clock. If it's wrong, set the zone in Settings > System > Date & time. Consider a one-line warning in mahitTV when the zone is GMT but the city isn't |
| P0-2 | **Wi-Fi was saved by the setup app:** `dumpsys wifi` lists 4 `cname=com.google.android.tungsten.setupwraith` entries. mahitTV's mode switch stops when it sees this, because turning that app off would delete the networks | Before switching to mahitTV mode, forget and re-add your Wi-Fi in Settings. This isn't a bug: `HomeMode` is protecting your connection |
| P0-3 | **TCL's TGuard blocks background starts.** `AUTO_START` is a real app-op here, and 10 apps are already set to `ignore`. `APP_AUTO_START` doesn't exist on this firmware | After installing, run `adb shell appops set io.freemytv AUTO_START allow`, as the README's fallback command already covers |
| P0-4 | **Fresh install.** No older mahitTV is on the TV, so there's nothing to upgrade or roll back | Use the real `freemytv.keystore` from the start. Skip the PR's "survives the upgrade" checks for now |
| P0-5 | **A second Android user exists.** Installing and home settings may behave differently for it | Confirm which user is active and what `adb install` does for user 10 before the mode switch |

### P1: small changes, high value

| # | Feature | Why now | Work | Test on the TV |
|---|---|---|---|---|
| P1-1 | **Fix Bluetooth and Remotes links** | Both land on general Settings here | Try `com.android.tv.settings.DEVICES` first, as `QuickPanelActivity` already does. Small | Opens TCL's device page |
| P1-2 | **Fix the Apps row** | `APPLICATION_SETTINGS` resolves to a chooser | Try `MANAGE_APPLICATIONS_SETTINGS` first. Small | Opens the app list directly |
| P1-3 | **Playback controls in Control Center** | Sessions are available, and Welly already has the code | Reuse Welly's `TransportControls`, shown only with notification access. Small to medium | Play or pause Spotify and Netflix |
| P1-4 | **Listen Alone tile** | Exported with no permission, and mahitTV doesn't offer it yet | One Control Center or Settings tile. Small | Opens and returns cleanly |
| P1-5 | **Date & time shortcut with a GMT warning** | Fixes P0-1 for good | One setting row plus the check. Small | Shows only when the zone looks wrong |
| P1-6 | **Tubi in the picks** | Installed, and `HomeApps` knows it, but the picks' service list doesn't include it | Add the mapping and test its deep links. Small | A pick opens the Tubi title |
| P1-7 | **Log every remote button** | Settles `4065` and the app buttons | No code: `getevent -lt` while you press them. About 5 minutes | Fills the gaps in section 4 |

### P2: larger features

| # | Feature | Evidence | Unknown or risk |
|---|---|---|---|
| P2-1 | **Become home through Android's role screen** | `android.settings.HOME_SETTINGS` resolves here, and mahitTV doesn't use it. It could be cleaner than disabling Google's launcher | Google TV may hide the choice or take home back. **Must be tested, and must not replace the existing way back to Original** |
| P2-2 | **HDR badge on picks** | Dolby Vision, HDR10, HDR10+ and HLG support, via the public `Display.getHdrCapabilities()` | Picks need a per-title HDR source; TMDB doesn't reliably provide one |
| P2-3 | **Remote battery in Control Center** | `persist.sys.tcl.btrc.battery=56`, and Android 12+ has the public `InputDevice.getBatteryState()` | Unknown whether either is readable by a normal app here |
| P2-4 | **Current audio output and format in Control Center** | `AudioManager.getDevices()` plus the eARC port and format list | Display and link only; it can't switch outputs |
| P2-5 | **Game Bar, sound calibration and TCL messages shortcuts** | All exported | Check each one returns to mahitTV cleanly |
| P2-6 | **Lighter performance tier** | 2.45 GB RAM, about 657 MB free, four 1.39 GHz cores. Android draws at 1080p | Needs real measurement during playback. Hand-drawn Canvas, shaders and aerial video are the first suspects |
| P2-7 | **Native Inputs fallback** | `VIEW_INPUTS` opens TCL's real picker | mahitTV's own list is nicer; keep this as a fallback or "All inputs" link |

### P3: needs your decision

| # | Idea | Trade-off |
|---|---|---|
| P3-1 | **Hands-free "Hey Welly"** using the far-field mic | Means always listening, with privacy implications and CPU use. It may also conflict with Google's own hotword. Press-to-talk already works, so I'd keep that unless you want this |
| P3-2 | **Wake to weather** or scheduled power-on | The firmware has it, but through TCL's private system. Android alarms during TCL's deep sleep need testing and may not wake the panel |
| P3-3 | **On-device Welly on the NPU** | NNAPI exists, but 657 MB free and 32-bit apps leave little room. Your existing research assumed a roughly 400 MB model, which looks too tight here |

### Still not possible, based on this audit

| Idea | Why |
|---|---|
| Changing picture modes, Dolby or IMAX settings directly | TCL's display services are private. Link to TCL's Picture page instead |
| CEC control of devices, or switching eARC | `HdmiControlManager` and TCL's HDMI extension aren't available to normal apps |
| Routing system audio to a chosen output | Android doesn't let a normal app do this |
| Starting AirPlay from mahitTV | The receiver is present, but it has no public way to start a session |
| LE Audio, or AirPods as a headset with mic | `bap.*` and `hfp.*` are disabled on this firmware. A2DP listening still works |
| 120 Hz or frame-rate matching in mahitTV | Android sees a single 60 Hz mode |
| Native 64-bit code | The userland is 32-bit only |

## 8. What this audit didn't cover

- **Nothing was launched**, so an action that resolves here could still fail in practice.
- **The TV was asleep.** There's no data on playback, HDMI handshake, HDR switching or the load average in normal use.
- **Vendor key layouts and vendor config files** weren't readable.
- **TCL's private services weren't called**, so what they'd allow is inferred from names and permissions.
- **The Android-side behavior of mahitTV itself is still unseen**, because it isn't installed.
- **Certificates** weren't compared against publisher references.
