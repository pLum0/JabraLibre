# JabraLibre

![JabraLibre — ANC, HearThrough, Off, fully offline](fastlane/metadata/android/en-US/images/featureGraphic.png)

A minimal, **fully offline**, open-source replacement for the Jabra Sound+ app
(`com.jabra.moments`) — starting with the feature that matters most:
switching **ANC / HearThrough / Off** on the Jabra Elite 10.

No internet permission. No tracking. No Google Play Services. No account.
Just Bluetooth.

## Why

The earbud button already cycles the modes — as long as you are wearing the
**left** bud. Listening with only the right one (in bed, for instance) leaves
no way to switch. That is what this app is for, which is also why it ships a
**Quick Settings tile**: pull down the shade, one tap cycles
ANC → HearThrough → Off. Works on the lock screen, no need to open the app.

## Status

Working prototype — protocol reverse-engineered from an HCI capture
(Pixel 8 Pro / Android 17, Jabra Elite 10, firmware 4.6.0).

- [x] RFCOMM channel 29 connection (Bluetooth Classic, no GATT involved)
- [x] Session init (ping + subscriptions)
- [x] Read current mode (topic `13be`)
- [x] Write mode: Off `01` / HearThrough `02` / ANC `04`
- [x] Push parsing (mode changes from the earbud button)
- [x] Quick Settings tile, three-mode UI, EN/DE
- [x] Connection state that does not lie: when the buds are not connected to
      the phone at all, the app says so within a second instead of spending
      ~45 s on a retry ladder that cannot succeed, the tile greys itself out,
      and no mode is shown as applied unless the buds confirmed it
- [x] Reconnects by itself while the app is open — put the buds back in and the
      mode and battery refresh without touching anything
- [x] **Battery: all three levels** — left bud, right bud and charging case,
      separately (topic `1202`). Android itself only exposes one combined
      number, which is why a single level would not have been worth showing.
- [ ] **EQ with presets** — planned, low priority. Topic not identified yet.
- ~~ANC strength~~ — dropped: the Elite 10 has no such setting in Sound+ either.
- ~~Firmware update~~ — deliberately out of scope. Flashing belongs in the
  official app, where it is at least guaranteed to work. Even a passive *update
  checker* would require the `INTERNET` permission in the manifest, and that
  permission not existing is the whole point of this app.

## Device support

Everything here was developed and tested against **one** pair of **Jabra
Elite 10** (firmware 4.6.0). It may well work on other Jabra earbuds that speak
the same RFCOMM protocol, and it may equally well not — different generations
use different transports (some are GATT-based) and different topic ids.

**If it does not work with your Jabra buds, I am happy to add support — but I
need your help to diagnose it**, since I only own the Elite 10:

1. Open JabraLibre, expand **Diagnostics**.
2. Tap **Channel scan** and let it finish (~30 s).
3. Tap **Topic scan**.
4. Tap **Export log** and attach the log to a
   [GitHub issue](https://github.com/pLum0/JabraLibre/issues), together with the
   exact model name and the firmware version from the Sound+ app.

The log contains the Bluetooth address and serial of your buds — strip those
lines if you would rather not publish them.

## Protocol notes

The Elite 10 talks to Sound+ over **Bluetooth Classic RFCOMM (server channel 29)**,
not BLE/GATT. Frames carry no length header:

```
phone → buds   04 09 <seq> <type> <topic:2> <payload…>
buds → phone   09 04 <seq> <type> <topic:2> <payload…>
```

Command types seen so far: `47` read, `88` write, `8a` session ping.
Reply types: `c7` single-byte value, `c9` index list, `d3` length-prefixed
string, `09` unsolicited push.

Command types seen so far: `47` read, `88` write, `8a` subscribe.
Reply types: `c7` single byte, `c8` two bytes, `c9` index list, `d1`/`d3`/`d4`/`d5`
length-prefixed records and strings, `cb` **error** (`fe fa` followed by an echo
of the rejected request), `09`/`11` unsolicited push.

`cb` is what makes the protocol explorable: reading a topic/index that does not
exist answers with an error instead of silence, so a sweep cleanly separates
real topics from noise. That is what the **Topic scan** in *Diagnostics* does.

### Mode — topic `13be`

Index `01`: `01` Off, `02` HearThrough, `04` ANC. Valid indices are `00`-`04`
and `07`. A write is answered by a *transition* push carrying the old value, so
the app reads the topic back ~600 ms later — same as Sound+ does.

### Battery — topic `1202`

One record holds all three levels; the index is ignored. Read with `47`
(reply type `d1`), and pushed unsolicited as type `11` whenever something
changes:

```
80 5a 00 00 02 | 15 00 50 | 05 00 55
^  ^        ^    ^^ ^^ ^^
|  |        |    one entry per component: id, flags, percent
|  |        number of entries that follow
|  percent of the left bud
flags
```

Component ids are the ones topic `0228` enumerates (`04 05 15`): `04` left bud,
`05` right bud (the address the phone is bonded to), `15` the charging case
(index `15` of `0228` returns all zeros — the case has no Bluetooth address).
The left bud's level sits in the record header rather than in an entry.

Verified against Sound+ with three different levels (85 / 90 / 80), and by
putting the right bud into the case: only the `05` entry rose while it charged.

### Identity — topic `02xx`

`0200` device name, `0201` serial, `0203` firmware version (`4.6.0`),
`0228` the component directory described above.

### Subscriptions — topic `0d4c`

Session init writes the mask `00000296` with command `8a`; mode changes then
arrive as pushes `09 04 00 09 0d4c <class> 01 <value>` with class `09`.
Widening the mask to `ffffffff` adds no new classes on this firmware.

Capture methodology and further details: see the commit history.

Inspiration & prior art: [mormegil6/jabra-elite10-re](https://github.com/mormegil6/jabra-elite10-re)
(Gen 2 GATT/head-tracking writeup — different generation, different transport,
but excellent methodology).

## Build

```sh
./gradlew assembleDebug      # signed with the committed debug keystore
./gradlew assembleRelease    # unsigned, this is what F-Droid builds
```

Needs JDK 17. GitHub Actions builds a debug APK on every push (see *Actions*
tab → artifact `JabraLibre-debug-apk`) and publishes it as a GitHub Release.

## F-Droid

Everything F-Droid needs is in the repository:

| What | Where |
|---|---|
| Listing texts, icon, screenshots (EN + DE) | `fastlane/metadata/android/` |
| Build recipe to submit | `fdroid/com.plum0.jabralibre.yml` |
| Filled-in MR checklist | `fdroid/INCLUSION_REQUEST.md` |
| Gradle version for their build server | `gradle/wrapper/gradle-wrapper.properties` |

F-Droid builds `assembleRelease`, which is deliberately left **unsigned** here
so that they can sign it with their own key, and `dependenciesInfo` is disabled
so the APK carries no opaque Google-signed dependency blob. The listing texts
are read from `fastlane/` at the commit being built, so those files have to be
committed *before* the tag F-Droid builds.

To submit: fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata), copy
`fdroid/com.plum0.jabralibre.yml` to `metadata/com.plum0.jabralibre.yml`, check
it with `fdroid lint` and `fdroid build`, then open a merge request titled
*"New app: JabraLibre"* with `fdroid/INCLUSION_REQUEST.md` as its description.

Two things to expect from review:

* **The F-Droid build and the Obtainium build are signed with different keys.**
  They are therefore not interchangeable: anyone switching from one to the other
  has to uninstall first. Nothing is lost but the app's own settings.
* **The name contains a trademark.** F-Droid reviewers do sometimes ask about
  that. The disclaimer above is the honest answer — no affiliation, no Jabra
  code, findings from observing one's own hardware — but be prepared for the
  question, and for the possibility of being asked to rename.

## Install via Obtainium

Add **`pLum0/JabraLibre`** as a GitHub source in
[Obtainium](https://github.com/ImranR98/Obtainium). Every CI build publishes a
GitHub Release containing the signed debug APK, so Obtainium picks up updates
automatically. A committed debug keystore keeps the signature stable — updates
install right over the previous version.

## Disclaimer

Not affiliated with Jabra / GN Audio. Protocol findings are the result of
observing one's own devices. Use at your own risk.

## License

GPL-3.0-or-later
