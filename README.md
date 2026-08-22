# JabraLibre

A minimal, **fully offline**, open-source replacement for the Jabra Sound+ app
(`com.jabra.moments`) — starting with the feature that matters most:
switching **ANC / HearThrough / Off** on the Jabra Elite 10.

No internet permission. No tracking. No Google Play Services. No account.
Just Bluetooth.

## Status

Early prototype — protocol reverse-engineered from an HCI capture
(Pixel 8 Pro / Android 17, Jabra Elite 10, firmware 4.6.0).

- [x] RFCOMM channel 29 connection (Bluetooth Classic, no GATT involved)
- [x] Session init (ping + subscriptions)
- [x] Read current mode (topic `13be`)
- [x] Write mode: Off `01` / HearThrough `02` / ANC `04`
- [x] Push parsing (mode changes from the earbud button)
- [ ] Battery level, EQ, ANC strength (follow-up captures needed)

## Protocol notes

The Elite 10 talks to Sound+ over **Bluetooth Classic RFCOMM (server channel 29)**,
not BLE/GATT. Frames: `04 09 <seq> <type> <topic:2> <payload>` (phone→buds),
`09 04 <seq> …` (buds→phone). Mode topic is `13be`, index `01`, written with
command type `88`. Details and capture methodology: see `docs/` (WIP) and the
commit history.

Inspiration & prior art: [mormegil6/jabra-elite10-re](https://github.com/mormegil6/jabra-elite10-re)
(Gen 2 GATT/head-tracking writeup — different generation, different transport,
but excellent methodology).

## Build

GitHub Actions builds a debug APK on every push (see *Actions* tab → artifact
`JabraLibre-debug-apk`). Locally: open in Android Studio, or `gradle assembleDebug`.

## Disclaimer

Not affiliated with Jabra / GN Audio. Protocol findings are the result of
observing one's own devices. Use at your own risk.

## License

GPL-3.0-or-later
