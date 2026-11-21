# Viewfinder

A companion app for the Nikon D5600 on Android, built as a personal replacement for Nikon's SnapBridge. It talks to the camera directly over the camera's own Wi-Fi using PTP/IP, with no cloud and no account.

It's built for one camera and one phone (a D5600 and a Galaxy A35), and it isn't on Google Play. The camera code is brand-neutral, so other PTP/IP cameras can be added as drivers later.

## What it does

- **Connect** to the camera's Wi-Fi from inside the app, and reconnect to it automatically next time.
- **Gallery and viewer** for the photos on the card: browse, save full-size JPEG and RAW files to the phone, and view saved photos at full resolution.
- **Quick sharing:** share one photo or a selection as JPEG, HEIF or WebP, a photo card, a ZIP or a contact-sheet PDF. Location data can be stripped before anything leaves the phone.
- **Remote:** live view from the camera, shooting with or without autofocus, and exposure controls.
- **Settings inspector:** every property the camera reports, with readable names and values.
- **Learn photography:** a calm path of short lessons for beginners that works with no camera linked:
  - real bundled photos you can change by touch (aperture, shutter speed, ISO, exposure, white balance, framing, steady hands);
  - an interactive drawing of the D5600 that turns to each button and shows what it does;
  - "Explain a photo", which reads the settings out of any photo on the phone;
  - a Recipes chapter of looks and situations ("do this, get that"), each with a before/after preview and the exact steps on the camera.

## Layout

| Path | What's there |
|------|--------------|
| `ptp/` | A plain Kotlin/JVM library with no Android dependency: the PTP and PTP/IP protocol, the brand-neutral `Camera` interface, the generic driver and the Nikon driver. Tested on the desktop against an in-process fake camera. |
| `app/` | The Android app (Jetpack Compose, minSdk 33). |
| `app/src/main/assets/learn/` | The Learn photos and their credits. |
| `tools/` | Generators for the Nikon property catalog, the Learn photo layers and the launcher icon. |
| `docs/` | Design docs and plans. |

[AGENTS.md](AGENTS.md) has the architecture, the camera quirks found on the real D5600, and the project's design and testing rules.

## Building

You need the Android SDK and JDK 17 or newer. `gradle.properties` points Gradle at Android Studio's bundled JDK on macOS (`org.gradle.java.home`); change or remove that line if yours lives elsewhere.

```bash
./gradlew :app:installDebug          # build and install on a connected phone
./gradlew :ptp:test                  # protocol and driver tests against the fake camera
./gradlew :app:testDebugUnitTest     # app unit tests
```

### No camera? Use the fake one

```bash
./gradlew :ptp:runFakeCamera         # a pretend D5600 on port 15740
```

The Android emulator reaches it at `10.0.2.2`. Add `-Pkbps=150` to slow its transfers down, for testing progress and cancelling.

## Credits

- The Nikon property names in `NikonCatalog.kt` are generated from [libgphoto2](https://github.com/gphoto/libgphoto2) (LGPL-2.1).
- The Learn photos come from [Unsplash](https://unsplash.com) under the Unsplash License; each photographer is credited in `app/src/main/assets/learn/credits.json` and under the photo in the app.
- Nikon and D5600 are trademarks of Nikon Corporation. This project isn't affiliated with or endorsed by Nikon.
