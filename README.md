# Viewfinder

An Android app for the Nikon D5600, written to replace SnapBridge for my own use. It connects to the camera's own Wi-Fi and talks PTP/IP to it directly. No cloud, no account.

It's built for one camera and one phone (a D5600 and a Galaxy A35) and isn't on Google Play. The camera code doesn't know about Nikon outside one driver, so other PTP/IP cameras could be added later.

## What it does

- Joins the camera's Wi-Fi from inside the app and reconnects to the last camera on the next launch. A Quick Settings tile opens the remote and reconnects.
- Lists the photos on the card. You can save JPEGs and NEF (RAW) files to the phone at full size, and saved photos open from the local file at full resolution.
- Shares one photo or a selection: the original files, JPEG, HEIF or WebP copies with size, crop and an optional photo card, a ZIP, or a contact-sheet PDF. Location can be removed before anything leaves the phone.
- Remote: live view, shooting with or without autofocus, touch and manual focus, bulb, movies and exposure settings.
- Shows every property the camera reports, with readable names and values.
- Learn: short lessons for beginners that work without the camera. Bundled photos change as you move aperture, shutter speed, ISO, exposure, white balance and framing. A drawing of the D5600 turns to each control and shows what it does. "Explain a photo" reads the settings out of any photo on the phone, and a Recipes chapter shows how to get a given look on the D5600, with a before/after preview.

## Layout

| Path | What's there |
|------|--------------|
| `ptp/` | Kotlin/JVM library with no Android dependency: PTP and PTP/IP, the brand-neutral `Camera` interface, the generic driver and the Nikon driver. Tested on the desktop against an in-process fake camera. |
| `app/` | The Android app (Jetpack Compose, minSdk 33). |
| `app/src/main/assets/learn/` | The Learn photos and their credits. |
| `tools/` | Generators for the Nikon property catalog, the Learn photo layers and the launcher icon. |
| `docs/` | Design docs and plans. |

[AGENTS.md](AGENTS.md) covers the architecture, the camera quirks found on the real D5600, and the design and testing rules.

## Building

You need the Android SDK and JDK 17 or newer. `gradle.properties` points Gradle at Android Studio's bundled JDK on macOS (`org.gradle.java.home`). If yours is elsewhere, change that line or pass `-Dorg.gradle.java.home=/path/to/jdk`.

```bash
./gradlew :app:installDebug          # build and install on a connected phone
./gradlew :ptp:test                  # protocol and driver tests against the fake camera
./gradlew :app:testDebugUnitTest     # app unit tests
```

### Without a camera

```bash
./gradlew :ptp:runFakeCamera         # a fake D5600 on port 15740
```

The Android emulator reaches it at `10.0.2.2`. Add `-Pkbps=150` to slow its transfers down when testing progress and cancelling.

## Releasing

The version lives in one place, `viewfinder.version` in `gradle.properties`. The version code is derived from it (1.2.3 → 10203).

1. Move the notes under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) into a new `## [X.Y.Z] - date` section.
2. Set `viewfinder.version=X.Y.Z` and commit.
3. `git tag vX.Y.Z && git push origin vX.Y.Z`

The release workflow then runs the tests, checks the tag against the version, builds a signed APK and publishes a GitHub release with that CHANGELOG section as its notes. It needs four repository secrets: `VIEWFINDER_KEYSTORE_BASE64` (the keystore, base64-encoded), `VIEWFINDER_KEYSTORE_PASSWORD`, `VIEWFINDER_KEY_ALIAS` and `VIEWFINDER_KEY_PASSWORD`. Locally, `./gradlew :app:assembleRelease` with the same variables set (`VIEWFINDER_KEYSTORE` as a path) builds a signed APK; without them it builds an unsigned one.

A release APK is signed with a different key from a debug install, so Android won't update one with the other. Uninstall first (this deletes the app's data).

## Credits

- The Nikon property names in `NikonCatalog.kt` are generated from [libgphoto2](https://github.com/gphoto/libgphoto2) (LGPL-2.1).
- The Learn photos are from [Unsplash](https://unsplash.com) under the Unsplash License. Each photographer is credited in `app/src/main/assets/learn/credits.json` and under the photo in the app.
- Nikon and D5600 are trademarks of Nikon Corporation. This project isn't affiliated with Nikon.
