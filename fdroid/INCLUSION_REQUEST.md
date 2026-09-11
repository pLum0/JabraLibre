<!-- Paste this as the description of the fdroiddata merge request.
     Title the MR exactly: "New app: JabraLibre"
     Remove this comment and the note under "Builds with fdroid build" once
     you have actually run it. -->

## Required

* [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy)
* [x] The original app author has been notified (and does not oppose the inclusion) — I am the author.
* [x] All related [fdroiddata](https://gitlab.com/fdroid/fdroiddata/issues) and [RFP issues](https://gitlab.com/fdroid/rfp/issues) have been referenced in this merge request — none exist for this app.
* [x] Builds with `fdroid build` and all pipelines pass — verified locally against
      `registry.gitlab.com/fdroid/docker-executable-fdroidserver:master`:
      `fdroid lint` clean, `fdroid scanner` clean, and `fdroid build` produced
      the unsigned release APK from the pinned commit (versionCode 8,
      versionName 0.3.1, unsigned as expected). The MR pipeline has also run
      green on all nine jobs.
* [x] There is an issue tracker and contact info of the author so that we can report bugs and contact the author.

## Strongly Recommended

* [x] The upstream app source code repo contains the app metadata _(summary/description/images/changelog/etc)_ in a [Fastlane](https://gitlab.com/snippets/1895688) or [Triple-T](https://gitlab.com/snippets/1901490) folder structure — en-US and de-DE, incl. screenshots, feature graphic and per-release changelogs.
* [x] Releases are tagged and auto update is enabled — tags `vX.Y.Z`, created by CI; `versionCode`/`versionName` live in `app/build.gradle.kts`.

## Suggested

* [ ] External repos are added as git submodules instead of srclibs — not applicable, no external repos or srclibs used.
* [ ] Enable [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds) — No, I don't want this.
* [ ] Multiple apks for native code — not applicable, no native code.

---------------------

/label ~"New App"

<!-- Notes for the reviewer, if asked:

     * The app has no INTERNET permission at all; the release APK declares only
       BLUETOOTH_CONNECT (plus legacy BLUETOOTH up to API 30). No ads, no
       tracking, no non-free dependencies or services, so no AntiFeatures.
     * `debug.keystore` in the repo is a throwaway key with the well-known
       Android debug password. It signs only the debug APK published on GitHub
       for side-loading, so that those updates install over each other. The
       release build F-Droid compiles has no signingConfig and comes out
       unsigned, for F-Droid to sign.
     * The name references the Jabra brand because the app speaks that
       hardware's protocol. No affiliation, no Jabra code; findings come from
       observing one's own device. The README states this.
-->
