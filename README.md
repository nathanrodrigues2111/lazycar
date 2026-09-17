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
2. Pick your **music player**, **Bluetooth device 1** and **device 2** (both
   must already be paired in Android's Bluetooth settings).
3. Optionally tick **Also launch Open Headunit** and enter its IP.
4. Tap **Save settings**.
5. Add the **LazyCar** 1x1 widget to your home screen.

Now one tap on the widget (or the big **GO** button in the app) will:
connect both Bluetooth devices, wait ~2s, open the player, try to enable
dual-audio sharing across both speakers, press Play, and launch the head unit
if enabled.

Two extra switches:

- **Dual audio (audio sharing)** (default on): after both speakers connect,
  LazyCar tries to route audio to both via `MediaRouter2`. The privileged
  OnePlus "dual audio" API can't be called by a side-loaded app (see
  `reverse/SHARING_API.md`), so if the automatic path doesn't take, it opens the
  system output switcher for a one-tap enable.
- **AMOLED black** (default on): pure-black surfaces for OLED screens, keeping
  the dynamic-color accents. Toggling recreates the screen; the widget tile
  follows the same black.

## Notes

Bluetooth connect uses the A2DP/Headset profile proxy via reflection, which is
best-effort — failures show a Toast, they never crash the app. If a device
won't connect on your ROM, that's the piece to revisit (`GoAction.connectA2dp`).

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
