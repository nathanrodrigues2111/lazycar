# lazycar — Research: one-tap dual Bluetooth audio + music launcher (Android)

Research only, no code yet. Target: OnePlus (OxygenOS 16, Android 15/16 base). All findings below note
Android API levels and hidden-API status where relevant.

## 0. Local environment (section 4 answered first, it's short)

No Android toolchain on this machine and no phone attached:
- `which adb sdkmanager gradle` → all empty (adb: command not found, exit 127).
- `$ANDROID_HOME` unset; `~/Android` does not exist.
- `adb devices` → command not found, so none of the requested on-device `dumpsys`/`settings` greps could run.

Consequence: we could not read live OxygenOS `settings list global/secure` keys for the audio-sharing feature.
To do that later: install platform-tools (`sudo apt install adb` or Google's zip), enable USB debugging on the
phone, then run the four commands from the task. Until then this report is from public sources only.

## 1. Programmatically connecting a specific bonded A2DP device (Android 14/15/16)

No public API exists. `BluetoothA2dp` (obtained via `BluetoothAdapter.getProfileProxy(ctx, listener,
BluetoothProfile.A2DP)`) publicly exposes only `getConnectedDevices()`, `getConnectionState()`,
`getDevicesMatchingConnectionStates()`, `isA2dpPlaying()` — no `connect()`. To trigger a connect you must
reflect the hidden `connect(BluetoothDevice)` method (target device fetched from `getBondedDevices()` matched
by MAC), then watch `ACTION_CONNECTION_STATE_CHANGED`. This is exactly what "Bluetooth Auto Connect" and the
open-source **A2DP Volume** (`a2dp.Vol`, GitHub jroal/a2dpvolume — has a home-screen widget that fires the
connect) do. Both are non-root and rely on this hidden method.

Hidden-API status: `BluetoothA2dp.connect` has historically been on the greylist / `@UnsupportedAppUsage`
(reachable via reflection). It is NOT confirmed blocklisted on 14/15, but Google keeps tightening non-SDK
access and the whole BT stack became a mainline (updatable) module in Android 13+, so the flag can shift per
build/OEM. Sibling `BluetoothLeAudio.getGroupId` is already `api=blocked` in a real logcat dump — evidence the
BT namespace is being locked down. So treat reflection as "works today, may break silently." Authoritative
check requires the phone: `adb shell settings put global hidden_api_policy 1` then watch logcat for
`hiddenapi:` denials when the app calls `connect`.

Does CompanionDeviceManager / LE Audio / Auracast help?
- **CompanionDeviceManager** (API 26+, `associate`) does NOT connect A2DP. It grants an association that
  relaxes some background/BLE restrictions; it's a permission convenience, not a connect API. Not useful here.
- **BluetoothLeAudio / BluetoothLeBroadcast** (`BluetoothLeBroadcast`, API 33+): the *proper* public way to
  push one stream to many receivers (Auracast). But it only works with LE-Audio sink devices, and — see §next —
  OnePlus's feature is NOT LE Audio. So this API cannot drive the two headphones the user actually owns.

OxygenOS "dual audio" nature: it is **classic Bluetooth A2DP dual-connection**, two independent A2DP streams,
each with its own volume — NOT LE Audio / Auracast broadcast. Rolled out in OxygenOS 16.0.8.300/301 to OnePlus
12, 13, 13R, 13s, 15, 15R (June 2026 patch). Auracast is still only a user *feature request* on OnePlus forums;
not present. There is **no documented public intent or Settings.Secure key** to toggle sharing. The only
exposed UI is: connect both devices normally, then in the media player tap the **audio-output icon** (the
Android system Output Switcher / MediaRouter panel) and enable sharing there. That output-switcher panel is
reachable programmatically via `Intent("com.android.settings.panel.action.MEDIA_OUTPUT")` (or
`MediaRouter2`), but it still requires a user tap to pick both sinks — it is not a silent toggle.

Net: the reliable primitive we control from a 3rd-party app is "reflect `A2dp.connect` on device A, then on
device B." Whether the OS then keeps both streams alive (true dual output) depends on the OxygenOS dual-audio
mode being on; we cannot flip that flag programmatically, only the two connects.

## 2. Starting playback in an arbitrary music player

Three options, from most to least reliable:
- **`AudioManager.dispatchMediaKeyEvent(KEYCODE_MEDIA_PLAY)`** (send ACTION_DOWN then ACTION_UP): best generic
  option. Routes through the same MediaSession dispatch path as hardware buttons; observed to work for Spotify
  play/pause. Weakness: it targets the *system's current priority session*, so if the target app isn't the
  most-recently-active session (or was killed), the key goes elsewhere or nowhere. Mitigation: launch the
  player app first (so it becomes the active session), brief delay, then dispatch play.
- **`ACTION_MEDIA_BUTTON` broadcast aimed at a specific component**: can target one app, but relies on that
  app's undocumented, version-specific receiver name; deprecated as a general mechanism since API 21. Fragile.
- **`MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`**: good for "play <query>" via a chosen player, but not
  all players implement it and it's search-driven, not resume-current.

App-specific reliable paths: Spotify → `spotify:` URI / App Remote SDK. Poweramp → its documented AImp/PowerampAPI.
YouTube Music has no clean public play API; media-key dispatch after foregrounding it is the practical route.
Recommended lazy pattern for "arbitrary player": launch the player's launch intent, then
`dispatchMediaKeyEvent(PLAY)` shortly after. Works acceptably for Spotify / YT Music / Poweramp.

## 3. Permissions & the widget → action path

- **`BLUETOOTH_CONNECT`** (API 31+, runtime, dangerous) is required to talk to A2DP proxy / bonded devices.
  Older `BLUETOOTH` + `BLUETOOTH_ADMIN` for <=30. No location perm needed if you don't scan.
- **Package visibility (API 30+):** to launch/see other players + Open Headunit you need a `<queries>` block
  (list the packages or the intent actions), NOT `QUERY_ALL_PACKAGES`. Play Store restricts
  QUERY_ALL_PACKAGES, so declare the specific packages: `com.spotify.music`, `com.google.android.apps.youtube.music`,
  `com.maxmpz.audioplayer`, `com.andrerinas.headunitrevived`. Only add QUERY_ALL_PACKAGES if the player must be
  user-selectable from the full installed set (has policy cost).
- **Widget tap → what runs:** a widget click PendingIntent that **starts an Activity is a user-initiated
  foreground action and is exempt from background-activity-start restrictions** — this is the clean path on
  Android 14+. So: widget tap → trivial transparent/`NoDisplay` Activity → do the two A2DP connects (fast) →
  launch player → dispatch PLAY → optionally launch Open Headunit → `finish()`. A foreground *service* started
  from the widget is also allowed (widget click counts as a valid FGS start trigger) but on Android 14 an FGS
  needs a declared `foregroundServiceType` and matching special-use/`connectedDevice` permission — more
  ceremony. The Activity route is lazier and sufficient; the BT connect + key dispatch finish in well under
  the time a short-lived Activity can stay up.
- **Open Headunit:** just `startActivity(Intent(ACTION_VIEW, Uri.parse("headunit://connect?ip=<ip>")))`,
  gated by its package being in `<queries>`.

## Recommendation

**Most reliable minimal path (one Activity, no service):**
1. Widget tap → launch a `Theme.Translucent.NoDisplay` Activity (BAL-exempt because user-initiated).
2. Get A2DP proxy; reflect `connect(BluetoothDevice)` for the two hard-coded bonded MACs. Requires
   `BLUETOOTH_CONNECT`. (Both connects are what enable OxygenOS dual output; we can't toggle the dual-audio
   mode flag, so document "turn dual audio on once in Settings" as a one-time user step.)
3. `startActivity` the chosen player's launch intent; after a short delay `dispatchMediaKeyEvent(PLAY)`
   (DOWN+UP). Prefer app-native URI for Spotify/Poweramp when the player is known.
4. If enabled, `startActivity(headunit://connect?ip=...)`.
5. `finish()`.

**Fallback if reflection `connect` is blocked on the user's build:** drop straight to UI deep-links — launch
the system Output Switcher (`Intent("com.android.settings.panel.action.MEDIA_OUTPUT")`) or Bluetooth settings
(`Settings.ACTION_BLUETOOTH_SETTINGS`) so the user taps the two sinks manually, then still do the
player-launch + PLAY dispatch automatically. Detect the block at runtime (connect throws / no
STATE_CONNECTED within a timeout) and fall back gracefully.

## Sources
- OnePlus dual audio is classic A2DP dual-stream, not Auracast: https://www.notebookcheck.net/OnePlus-phones-gain-dual-Bluetooth-audio-streaming-in-the-latest-OxygenOS-update.1325112.0.html ; https://droidwin.com/oneplus-now-has-dual-bluetooth-audio-here-how-to-use-it/ ; Auracast still only requested: https://community.oneplus.com/thread/2063823139934044166
- Reflection connect / how auto-connect apps work: https://dev.to/olise/connecting-to-bluetooth-audio-devices-in-android-1fm7 ; https://medium.com/mindful-engineering/connecting-to-a-bluetooth-a2dp-device-from-android-db9450ba3ecc ; A2DP Volume (widget, open source): https://play.google.com/store/apps/details?id=a2dp.Vol
- BluetoothA2dp public API surface: https://developer.android.com/reference/android/bluetooth/BluetoothA2dp
- Media key dispatch reliability / Spotify: https://developer.android.com/reference/android/media/AudioManager ; https://community.spotify.com/t5/Android/Is-it-intented-that-Spotify-app-does-not-respect-keyevent-quot/td-p/4729673
- FGS / background-start / widget exemption: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start ; https://developer.android.com/guide/components/activities/background-starts ; https://developer.android.com/about/versions/14/changes/fgs-types-required
