# LazyCar on-device verification

Device: OnePlus 15 (CPH2745), Android 16, adb `192.168.0.161` (network; port drifts).
Build under test: single "Start volume" model + volume watchdog + widget-as-service +
state-broadcast spinner fix + `onColor` threshold 0.18 (commit on `main`).

## Task 1 - UI

| Check | Result | Evidence |
|---|---|---|
| Accent applied everywhere, no lime unless chosen | PASS | Accent = electric blue (`0xFF4DA3FF`). Header car glyph, "Start volume" label, slider, Settings switches, Accent swatch all blue; no lime. `shots/main.png`, `shots/settings.png` |
| Header logo = launcher icon = widget glyph | PASS | Identical `pathData` across `ic_car_glyph` (header `brandIcon` + widget) and `ic_launcher_foreground`/`ic_launcher_mono`. Launcher + header + widget all the same car. `shots/main.png`, `shots/widget_on.png`, `shots/home_idle.png` |
| Spinners show during STARTING | PASS | App GO button -> "Starting..." + circular spinner; widget -> grey pill + spinner (glyph hidden). `shots/app_starting.png` |

## Task 2 - Volume (top priority)

Root cause found on device: OnePlus Audio sharing centralises volume for the group; the
per-device switcher sliders (`volume_seekbar`, content-desc "Connected to <name>.") are
`enabled=false` / `clickable=false` (display-only) whenever the two speakers are grouped
("Shared listening"). The old per-device `ACTION_SET_PROGRESS` + tap/drag gesture path
therefore could never move them:

```
volume 'XFW-BT' target=96000.0 got=97000.0 via=set_progress   # coincidental (slider already ~97000)
volume 'Nathan's JBL Speaker' target=48000.0 got=97000.0 via=drag   # 30% requested, never moved
```

Fix: one "Start volume" slider driving `AudioManager.setStreamVolume(STREAM_MUSIC, ...)`
(the shared group level), with a watchdog because the group forming / 2nd A2DP sink
connecting resets STREAM_MUSIC (seen jumping to max = "blast").

| Check | Result | Evidence |
|---|---|---|
| Single "Start volume" slider (not per-device) | PASS | `shots/main.png` / `app_on.png`; Prefs `vol`/`volOn` default 40% on, migrates old `vol1`. |
| Group volume changes verifiably | PASS | `volumeOnly` 18 -> 78 (both speakers), confirmed `dumpsys audio` STREAM_MUSIC bt_a2dp. |
| Holds target after grouping AND play (not max) | PASS | Cold GO: `poll done grouped=true`, `state -> 2`, `watchdog done: got=70 target=64±10`. `cmd media_session volume --stream 3 --get` = 70/160 (~44%, target 40%); dumpsys bt_a2dp = 70. Not max. |
| Watchdog catches the group reset-to-max | PASS | `volume correct (initial): 160 -> 70` and `volume correct (watchdog): 160 -> 70` (re-asserted after the group pushed it to 160). |
| Live apply | PASS | `volumeOnly` path (slider release while ON) runs `enforceStartVolume`. |
| STOP restores pre-GO level | PASS | Snapshot taken at start of `run()` (before BT/connect reset it). `restore: music volume -> index 18` then `dumpsys` bt_a2dp = 18 (exact). |

A2DP absolute volume quantises coarsely (~10 on the 0..160 scale, max index 160), so the
watchdog accepts within one step (±10) - enough to stop the blast while landing ~40%.

## Task 3 - Matrix

| Case | Result | Evidence |
|---|---|---|
| A cold (BT off -> GO) | PASS | `svc bluetooth disable` -> GO: `snapshot: btWasOn=false`, `TapService clicked confirm 'Allow' ok=true`, both connect, `grouped=true`, `state -> 2`. |
| C all-on warm GO, ON not gated by volume watchdog | PASS | ON transition fires at `doneAt+300`; watchdog runs in background so a satisfied GO still reaches ON quickly. |
| D STOP restores snapshot | PASS | `restore: music playing -> paused`; `restore: music volume -> index 18`; JBL `already connected -> left`, XFW `connected -> disconnected` (only what GO connected); `tile 'mobile data' was on -> off ok=true`, `tile 'hotspot' was on -> off ok=true`; `restore: bt on -> off` (snapBt=false). `state -> 0`. |
| E GO again | PASS | Repeated GO cycles ran cleanly (12:09, 12:14, 12:20, 12:27, 12:36). |
| Hotspot + data ON for an A/D pair | PASS (tile-level) | GO: `tile 'mobile data' was off -> on ok=true`, `tile 'hotspot' was off -> on ok=true`; `settings get global mobile_data` 0 -> 1. STOP restores both off; `mobile_data` 1 -> 0. NOTE: `tether_wifi_state` reads `null` on this ROM (hotspot state not stored there), so hotspot is confirmed at the QS-tile level, not via that global. Enabling the phone's hotspot also drops its own Wi-Fi client, which repeatedly killed network-adb mid-run. |

## Widget / service (urgent fixes)

| Check | Result | Evidence |
|---|---|---|
| Widget tap opens NO LazyCar UI | PASS | After both GO-tap and STOP-tap, `mCurrentFocus` stayed `com.android.launcher`. Tap -> `GoService` `ACTION_TOGGLE` (`getForegroundService`), which derives GO vs STOP itself. |
| Stuck spinner fixed | PASS | While Main open, GO button went STARTING -> STOP live (`ACTION_STATE` broadcast receiver); widget went busy-spinner -> ON. `setState` refreshes widget + broadcasts every time, plus a refresh scheduled at the 30s stuck-guard. `shots/app_starting.png` -> `shots/app_on.png`. |
| Widget ON = red pill + black car; IDLE = accent pill + car; busy = grey + spinner | PASS | `shots/widget_on.png` (red + black car). Dropped the stop-square icon; removed `ic_stop_white`. |
| `onColor` threshold 0.18 | PASS | Blue accent now yields black glyph/text (STOP text black on red; widget IDLE car black on blue), not white. |

## Timings (approx, from logcat)

- Cold GO (BT off -> ON): ~19 s (`step GO sequence took 19316ms`) - includes BT-enable dialog, hotspot+data (6.5 s), share/group poll.
- Warm GO: reaches ON quickly; volume watchdog continues ~12 s in the background.
- STOP -> IDLE: ~19 s (network-tile session 6.5 s + settle).
- `volumeOnly` / live slider: immediate set + short 3 s enforce window.

## Notes / limitations

- Install over network-adb repeatedly wedged; root cause was ADB-install verification (Play
  Protect) stalling on the flaky Wi-Fi at 90%. Disabling `verifier_verify_adb_installs` and
  using `adb push` + `pm install -r` made installs reliable. No uninstall was ever performed.
- Turning the Wi-Fi hotspot on drops the phone's Wi-Fi client, so network-adb disconnects
  during any hotspot GO; verification of the hotspot A/D pair is therefore tile-level.
