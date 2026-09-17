# Accessibility service is referenced from the manifest + res/xml (R8 keeps manifest components,
# this makes the intent explicit) and must not be renamed.
-keep class com.lazyneil.lazycar.TapService { *; }
-keep class com.lazyneil.lazycar.GoService { *; }
# We reflect connect(BluetoothDevice)/disconnect(BluetoothDevice) on the profile proxy; those are
# framework classes (not shrunk), so no app keep is needed beyond the default.
