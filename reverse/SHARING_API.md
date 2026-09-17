# OnePlus "Dual Bluetooth Audio" (A2DP Sharing) — API reverse-engineering

Phone: OnePlus 15 (CPH2745), Android 16. Jars/apk pulled to this dir:
`oplus-framework.jar`, `oplus_bluetooth_common_ext.jar`, `Bluetooth.apk` (all had intact `classes*.dex`).
Signatures dumped with a minimal Python dex parser (`/tmp/dexmethods.py`).

## 1. Client-side classes (in `/system/framework/oplus-framework.jar`)

### `com.oplus.bluetooth.OplusA2dpSharingManager` (public wrapper app would use)
```
static OplusA2dpSharingManager getOplusA2dpSharingManager()      // singleton accessor
boolean  startSharing(android.bluetooth.BluetoothDevice device, int type)
boolean  stopSharing()
boolean  isA2dpSharingEnabled()
boolean  isA2dpSharingAvailable()
boolean  inSharingMode()
int      getSharingType()
java.util.List getSharingDevices()
int      getDeviceSharingRole(BluetoothDevice)
boolean  setSharingDeviceAbsoluteVolume(BluetoothDevice, int)     // (via AIDL)
boolean  registerStateCallback(Executor, OplusA2dpSharingStateCallback)
boolean  registerVolumeCallback(Executor, OplusA2dpSharingVolumeCallback)
private  IOplusA2dpSharing getA2dpSharing(AttributionSource)      // internal
```
`startSharing(device, type)`: `device` = the SECONDARY device to add to the group;
`type` = sharing type, observed value **1** (broadcast/dual). Primary is the currently
active A2DP device (state machine picks it). Manager builds its own `AttributionSource`.

### Binder chain (how the instance reaches the server)
`getOplusA2dpSharingManager()` holds a static `mOplusBluetooth` (`IOplusBluetooth` binder,
fetched from `BluetoothAdapter`'s oplus ext impl on `onBluetoothServiceUp`). It calls
`mOplusBluetooth.getA2dpSharing(attributionSource)` → returns the `IOplusA2dpSharing` proxy.
NOT a `ServiceManager.getService` name and NOT a standard `getProfileProxy` id.

## 2. AIDL `com.oplus.bluetooth.IOplusA2dpSharing` (server: `com.android.bluetooth`)
```
boolean startSharing(BluetoothDevice device, int type, AttributionSource src)   // THE start call
boolean stopSharing(AttributionSource)
boolean isA2dpSharingEnabled(AttributionSource)
boolean isA2dpSharingAvailable(AttributionSource)
boolean inSharingMode(AttributionSource)
int     getSharingType(AttributionSource)
List    getSharingDevices(AttributionSource)
int     getDeviceSharingRole(BluetoothDevice, AttributionSource)
boolean setSharingDeviceAbsoluteVolume(BluetoothDevice, int, AttributionSource)
boolean registerStateCallback(IOplusA2dpSharingStateCallback, AttributionSource)
boolean unregisterStateCallback(...)  / register/unregisterVolumeCallback(...)
```
Server impl: `com.oplus.bluetooth.feature.a2dpshare.OplusA2dpSharingService` +
`OplusA2dpSharingStateMachine` (invokes `A2DP_SHARING_SUBEVENT_INVOKE_START`).
State callback: `IOplusA2dpSharingStateCallback.onSharingStarted(int status, int errCode,
int sharingType, BluetoothDevice primary, BluetoothDevice secondary)`.

## 3. Permission — third-party apps are BLOCKED
Server enforces **`android.permission.BLUETOOTH_PRIVILEGED`**
(`enforceCallingOrSelfPermission`, string `"Need BLUETOOTH_PRIVILEGED permission"` in
`Bluetooth.apk`). That permission is `signature|privileged` — a normal side-loaded /
Play-store app CANNOT hold it. So the reflection path below only works for a system,
privileged, or platform-signed app.

## 4. Reflection snippet (needs BLUETOOTH_PRIVILEGED — system/privileged app only)
```kotlin
// Requires the app to hold android.permission.BLUETOOTH_PRIVILEGED (signature|privileged).
// primaryMac must be the currently-active A2DP device; secondaryMac is the one being added.
fun startDualAudio(secondaryMac: String): Boolean {
    val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
    val secondary = adapter.getRemoteDevice(secondaryMac)          // add this device
    val mgrCls = Class.forName("com.oplus.bluetooth.OplusA2dpSharingManager")
    val mgr = mgrCls.getMethod("getOplusA2dpSharingManager").invoke(null)
    val start = mgrCls.getMethod(
        "startSharing", android.bluetooth.BluetoothDevice::class.java, Int::class.javaPrimitiveType)
    return start.invoke(mgr, secondary, 1) as Boolean   // type = 1 (observed sharingType)
}
// stop:  mgrCls.getMethod("stopSharing").invoke(mgr)
// state: registerStateCallback(Executor, OplusA2dpSharingStateCallback) -> onSharingStarted(...)
```
If `getDefaultAdapter` is off/hidden, `startSharing` returns false ("bluetooth is not ON").

## 5. Fallbacks for a NON-privileged third-party app
Direct API is gated, so the only legitimate paths are:

1. **Deep-link to the system output switcher** (the same dialog the user used; SystemUI
   `OplusMediaOutputPageController`). Public AOSP panel intent — user then taps the two
   devices to enable sharing (one tap, no privileged perm):
   ```kotlin
   startActivity(Intent("android.settings.panel.action.MEDIA_OUTPUT")
       .putExtra("android.media.extra.PACKAGE_NAME", packageName))
   ```
   (`Settings.Panel.ACTION_MEDIA_OUTPUT` + `MediaOutputConstants.EXTRA_PACKAGE_NAME`.)
   No intent string was logged during the manual run — SystemUI opened the QS controller
   in-process — but this is the standard action that surfaces that panel.

2. **MediaRouter2 (public `android.media.MediaRouter2`)** — `dumpsys media_router` shows the
   OnePlus `SystemMediaRoute2Provider` treating the two BT speakers as ONE dynamic group:
   `mSelectedRoutes=[F7:..5D:43, 68:..59:DC]`, `mDeselectableRoutes=[both]`,
   `mTransferableRoutes=[builtin speaker]`. So while sharing is active it is exposed as a
   selectable/deselectable dynamic-group session. In principle an app can
   `registerRouteCallback(feature LIVE_AUDIO)`, `transferTo(primaryBtRoute)`, then on the
   returned `RoutingController` call `selectRoute(secondaryBtRoute)` IF the provider lists it
   in `getSelectableRoutes()`. This is the only path that could *start* sharing without
   BLUETOOTH_PRIVILEGED, but the provider only advertised the group AFTER sharing was on
   (selectable list was empty in the captured dump), so it is unconfirmed and may still
   require the app to have BT routing permission. Deep-link (#1) is the reliable fallback.
