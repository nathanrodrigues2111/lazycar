# LazyCar

One tap to set up your phone for the car: connect two paired Bluetooth audio
devices, launch your music player and start playback, and optionally launch
Open Headunit.

## Build

Requires JDK 17 and the Android SDK (platform 35, build-tools 35).

```
./gradlew assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Install

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Use

1. Open **LazyCar**. Grant the Bluetooth permission when asked.
2. On the main screen pick your **music player** and the two **outputs**
   (both must already be paired in Android's Bluetooth settings). Settings save
   themselves; there is no Save button.
3. Open **Settings** (cog, top right) for the switches: Dual audio, head unit
   plus tablet and IP, Wi-Fi hotspot, Mobile data, accent colour, and the
   accessibility setup. **Help** (top right) has a short Q and A.
4. Add the **LazyCar** 1x1 widget to your home screen.

Now one tap on the widget (or the big **GO** button in the app) runs, in order:
turn Bluetooth on, connect both outputs, turn on mobile data and the hotspot if
enabled, open the player, enable dual-audio sharing across both speakers, set
each output's volume if enabled, and press Play. The head unit launches too if
enabled.

**GO / STOP snapshot.** At GO, LazyCar records what was already on (Bluetooth,
mobile data, hotspot, which outputs were connected, whether music was playing).
STOP restores exactly that: it only turns off / disconnects / pauses the things
GO itself turned on, and leaves anything that was already on untouched.

Feature switches (in Settings):

- **Dual audio** (default on): after both outputs connect, LazyCar opens the
  system output switcher and (via the accessibility service) ticks every
  "Add device to group." row so both speakers share audio. The privileged
  OnePlus sharing API can't be called by a side-loaded app (see
  `reverse/SHARING_API.md`), hence the switcher automation. Once grouped, a
  repeat GO skips the switcher entirely.
- **Wi-Fi hotspot** / **Mobile data** (default off): there is no public API to
  toggle either, so LazyCar flips their Quick Settings tiles through the
  accessibility service, only when they are in the wrong state.
- **Per-output volume** (default off): a slider under each output. When on, GO
  sets that output's slider in the switcher. See "Testing volume" below.

## Notes

Bluetooth connect uses the A2DP/Headset profile proxy via reflection, which is
best-effort: failures are logged, they never crash the app. If a device won't
connect on your ROM, that's the piece to revisit (`GoAction.connectA2dp`).

The accessibility service (`TapService`) is required for dual audio, the
Bluetooth enable/disable dialogs, the hotspot/data tiles, and volume. Note that
`adb install -r` and `am force-stop` both unbind it on OnePlus; re-enable it in
Settings > Accessibility (or via `settings put secure
enabled_accessibility_services ...`) after either.

## Testing volume

Per-output volume drives the switcher's `volume_seekbar` sliders through the
accessibility service. On some ROMs (OnePlus 15 seen here) those sliders ACK
`ACTION_SET_PROGRESS` but ignore it, so per slider LazyCar tries
`ACTION_SET_PROGRESS` first, verifies (re-reads `rangeInfo.current` after 350ms),
and if the value is still more than 4% off it moves the slider with a real touch
gesture (`dispatchGesture`, needs `android:canPerformGestures`): first a tap at
the target position, then, if that misses, a drag from the current thumb to the
target. The panel is held open until every slider verifies or a 3s budget
elapses, so it never backs out mid-gesture. `AudioManager.setStreamVolume` is
used only when the slider node is absent entirely.

To test on a device:

1. Set volumes in the app (a slider under each output; toggle "Volume" on).
2. `adb shell am start -n com.lazyneil.lazycar/.GoActivity --ez volumeOnly true`
   opens the switcher and applies volumes only (no full GO).
3. Watch `adb logcat -v time -s LazyCar:V`. Each slider logs a
   `volume '<name>' set_progress -> <v> ...` line, then exactly one resolution
   line naming the method that landed it:
   `volume '<name>' target=<v> got=<actual> via=set_progress|tap|drag`
   (`got` should be within 4% of `target`). A missing slider instead logs
   `volume '<name>' slider absent; stream fallback`.
4. Dragging a slider in the app while state is ON also applies it live.

Pure mapping logic lives in `VolumeMath`; run `./gradlew testDebugUnitTest`.

## Head unit / Android Auto (tablet setup)

Open Headunit runs on the **tablet** as the Android Auto receiver; the phone is
the source. To make one tap wake it:

1. Install **Open Headunit** (`com.andrerinas.headunitrevived`) on the tablet.
2. In Open Headunit, enable **auto-start on Bluetooth connect**.
3. **Pair the tablet with the phone** over Bluetooth, then pick it as the
   "Head unit tablet" in LazyCar so GO sends it a BT link to trigger auto-start.
4. For wireless Android Auto, enable AA developer settings > **Start head unit
   server** on the tablet.

On GO the phone connects the tablet over Bluetooth, then launches the first
installed phone-side path: Open Headunit Wireless Helper, else Open Headunit
(self mode; uses the IP field if set), else Android Auto.
