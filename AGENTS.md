# Viewfinder

A personal Android replacement for Nikon SnapBridge. It talks to the camera over its own Wi-Fi using PTP/IP, and is first built for the Nikon D5600 on a Samsung Galaxy A35 (Android 16). It isn't published to Google Play. The code is structured so more cameras and brands can be added later.

## Layout

| Path | What it is |
|------|------------|
| `ptp/` | Plain Kotlin/JVM library (JVM 17, coroutines). It has no Android dependency, so it can be tested on the desktop. |
| `ptp/.../ptp/` | The PTP layer: the byte reader and writer (`PtpBytes.kt`), standard codes (`PtpCodes.kt`), datasets such as DeviceInfo and ObjectInfo (`Datasets.kt`), and device property descriptors and values (`Properties.kt`). |
| `ptp/.../ptpip/` | The PTP/IP transport (TCP 15740) with a command channel and an event channel. Transactions run under a mutex. |
| `ptp/.../camera/` | The brand-neutral `Camera` and `LiveView` interfaces, the `Capability` flags, the generic `PtpCamera` driver, `CameraDrivers` (opens the session and picks a driver), and `PropertyCatalog` (names and formatting for standard properties). |
| `ptp/.../vendor/nikon/` | The Nikon driver (`NikonCamera.kt`) and `NikonCatalog.kt`, which is **generated**; don't edit it by hand. |
| `ptp/src/test/` | JUnit tests, plus `FakePtpIpCamera`, an in-process fake PTP/IP camera. `FakeCameraMain` runs it as a standalone server. |
| `app/` | The Android app (Jetpack Compose, minSdk 33, compileSdk 37, targetSdk 36). |
| `app/.../` | `CameraViewModel` holds all UI state and actions. `CameraNetwork` binds to the camera Wi-Fi, `WifiScanner` lists nearby networks, `MediaSaver` saves through MediaStore, and `Settings` holds SharedPreferences, including the saved-photo URIs. |
| `app/.../Share.kt`, `Sharer.kt` | Sharing. `Share.kt` holds the formats, options and the pure rules (which files go out, crop and caption maths, JPEG metadata stripping), unit-tested in `app/src/test`. `Sharer` does the Android side: re-encoding (JPEG, HEIF, WebP), photo cards, ZIP, contact-sheet PDF, location removal, FileProvider URIs and intents. |
| `app/.../ui/` | The screens: Connect, Gallery, Viewer, Remote (live view and shooting), Settings (every camera property) and Log. `Theme.kt` and `Components.kt` hold the design system. |
| `tools/gen_nikon_catalog.py` | Regenerates `NikonCatalog.kt` from libgphoto2's `ptp.h` and `config.c` (LGPL-2.1). |
| `tools/launcher_icon_svg.py` | Renders the launcher icon drawables to `docs/images/icon.svg` for the README. Rerun after `gen_launcher_icon.py`. |

## Commands

The Gradle JDK is pinned in `gradle.properties` (Android Studio's JBR).

```bash
./gradlew :ptp:test                 # protocol + driver tests against the fake camera
./gradlew :app:assembleDebug        # build the APK
./gradlew :app:installDebug         # install on the connected device
./gradlew :app:testDebugUnitTest    # app unit tests (sharing rules)
./gradlew :ptp:runFakeCamera        # fake D5600 on port 15740 (emulator reaches it at 10.0.2.2)
./gradlew :ptp:runFakeCamera -Pkbps=150   # the same, with transfers slowed to test progress and cancelling
```

Releases: bump `viewfinder.version` in `gradle.properties` and add its `CHANGELOG.md` section, then push a `vX.Y.Z` tag; `.github/workflows/release.yml` does the rest (README > Releasing). CI passes `-Dorg.gradle.java.home="$JAVA_HOME"` because the pinned JDK path is macOS-only.

To regenerate the Nikon catalog:

```bash
python3 -I tools/gen_nikon_catalog.py /path/to/ptp.h /path/to/config.c > ptp/src/main/kotlin/app/viewfinder/ptp/vendor/nikon/NikonCatalog.kt
```

To work with the phone, set `export ANDROID_SERIAL=RKCY20099BR` and then use plain `adb`. In zsh, a variable holding a whole command isn't word-split. The USB link drops sometimes; `adb kill-server && adb start-server` usually brings it back. The app logs under the tag `Viewfinder`:

```bash
adb logcat -s Viewfinder:D AndroidRuntime:E
```

## Rules

- **Keep `ptp/` brand-neutral and Android-free.** Anything brand-specific goes in `vendor/<brand>/` as a driver that subclasses `PtpCamera` and is registered in `CameraDrivers`. The app only sees `Camera`, `LiveView` and `Capability`, and shows or hides controls based on capabilities, never based on the brand.
- **Adding a camera feature:**
  1. Add a `Capability` if the app needs to know about it.
  2. Add the method to `Camera` or `LiveView`.
  3. Implement it in the driver.
  4. Teach `FakePtpIpCamera` the operation and add a test.
  5. Wire it through `CameraViewModel` into the UI.
- **The Nikon D5600 reports vendor extension 0x00000006 (Microsoft),** not Nikon's. `NikonDriver` therefore matches on the manufacturer name.
- **Gotchas proven on the real D5600:**
  - In `InitiateCaptureRecInMedia` (0x9207), `0xFFFFFFFF` means *no AF* and `0xFFFFFFFE` means *AF first*.
  - Live view may refuse at first; the driver retries and reports the reason from `0xD1A4`.
  - Object handles of deleted files get reused (with File Number Sequencing off, file names too), so never key anything stored by handle alone. Saved files are keyed by serial, name, size and capture date.
  - Codes that `StandardProps` already formats (aperture, exposure, ISO, …) must keep the standard formatter, not the catalog's name or table.
- **Wi-Fi scanning on Android 16 needs `ACCESS_FINE_LOCATION`.** `NEARBY_WIFI_DEVICES` alone returns nothing.
- **Viewer:** if a photo is already saved on the phone, it decodes the local full-resolution file (`CameraViewModel.fullImage`) and doesn't fetch the camera preview. If that file is gone, it quietly falls back to the camera preview.
- **Design:**
  - Palette is "jade on obsidian", defined in `Theme.kt` (background `#121614`, accent `#4FC28E` with near-black text, a faint gold only in the connect screen's glow). Use the tokens, not raw hex, in screens.
  - Glass surfaces use Haze 1.7.3 through `Modifier.glass`.
  - Type is Inter, with Source Serif 4 for titles.
  - All motion comes from `Motion.current` (`ui/Motion.kt`): springs for anything that moves or resizes, fades that come in slower than they go out, and `swap()` when content replaces content in place. Never write durations or easings in screens. With Android's "remove animations" setting on, Android itself skips animations, and the reduced path also drops every movement and loop, so nothing depends on motion. The setting is followed live.
  - Reuse the shared pieces in `Components.kt`: `pressScale` on anything pressable (down at once, back with a little spring), `SegmentedControl` for every either/or switch (its labels are drawn twice and clipped to the sliding pill), and `fadingEdges` on every horizontally scrolling strip, so nothing is ever cut mid-word.
  - Also in `Components.kt`: `rememberLast` (keep showing content while it animates out), `rememberPulse` (loading placeholders), and `Motion.morph()` for one small icon becoming another. Numbers that change while you watch use `TextStyle.tabular()` from `Theme.kt`.
  - Nothing may jump: anything that loads in reserves its space first (placeholders, fixed-size slots), and containers resize on a spring.
  - Touch targets are at least 48dp, contrast at least 4.5:1, and reduced motion is respected. Every icon-only button has a content description.
- **Sharing:** photos reach the phone through the save queue (so shared photos are saved too); copies made for a share live in `cache/share/` and are cleaned after a day. With "Remove location" on, location must never leave the phone: JPEGs are cleaned, and RAW, HEIF or video files that carry a location are left out with a note. The fake camera's JPEGs carry real-looking EXIF, every third one with GPS, to test this.
- **Tests:**
  - Each test must fail for the real reason before the fix.
  - Never assert only emptiness or "doesn't throw": pair it with a positive check on the same seeded fake data.
  - Hardcode the expected values.
- **Commit only when asked.**
