# Testing the Quick Settings tile

The tile is the hardest part of this app to test, because it lives in a
process that SystemUI binds and Android kills at will. Most tile bugs only
appear once the app's own process is gone, which never happens while you are
sitting in front of the app.

## Getting a genuinely dead process

`adb shell am force-stop` is the wrong tool: it marks the app *stopped*, and
Android then refuses to bind the tile service at all, so SystemUI draws a
placeholder that looks like a bug but is not one. Use `am kill` instead, which
kills without the stopped flag — and expect it to fail while the app is still
warm:

```sh
adb shell input keyevent KEYCODE_HOME
for i in 1 2 3 4 5; do
  adb shell am kill-all; adb shell am kill com.plum0.jabralibre
  sleep 3
  [ -z "$(adb shell pidof com.plum0.jabralibre)" ] && break
  adb shell am start -a android.settings.SETTINGS; sleep 2
  adb shell input keyevent KEYCODE_HOME; sleep 2
done
adb shell pidof com.plum0.jabralibre      # must print nothing
adb shell cmd statusbar expand-settings   # binds the tile into a fresh process
```

Then read what the tile decided:

```sh
adb logcat -d -s JabraLibre | grep availability
```

## What the states look like

`ModeTileService` can render three things, and two of them look similar enough
to be confused in a bug report:

| Tile state | Cause | Looks like |
|---|---|---|
| `STATE_ACTIVE` | a mode is known and is not Off | filled, highlighted |
| `STATE_INACTIVE` | mode unknown, or Off | plain, not highlighted |
| `STATE_UNAVAILABLE` | no device, no permission, or Bluetooth off | washed out, disabled |

## Forcing the unavailable path

`STATE_UNAVAILABLE` was a real bug: `BluetoothDevice.getName()` can return
null in a fresh process, the name-based device lookup then found nothing, and
the tile disabled itself while the earbuds were connected. That is hard to hit
on purpose, so inject the fault instead — temporarily, never committed:

```kotlin
fun bondedJabras(ctx: Context): List<BluetoothDevice> {
    if (true) return emptyList()   // FAULT INJECTION
    ...
```

With that patch in place the log should still find the device by its remembered
address, and the tile should stay usable:

```
availability: device=50:C2:…:53:71 byName=0 mode=HEARTHROUGH permission=true bluetooth=true link=true
```

Before the address fallback existed, the same injection produced the bug
report verbatim — a disabled tile with the earbuds connected:

```
availability: device=none byName=0 mode=HEARTHROUGH permission=true bluetooth=true link=false
```

## Don't log from updateTile()

`JabraManager.log()` notifies listeners, the tile is a listener, and its
listener calls `updateTile()`. Logging from inside `updateTile()` therefore
feeds itself: one shade opening produced 744 log lines. Use `android.util.Log`
directly if a tile-local trace is ever needed.
