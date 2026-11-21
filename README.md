<p align="center">
  <img src="docs/images/icon.svg" width="112" alt="Viewfinder icon">
</p>

<h1 align="center">Viewfinder</h1>

<p align="center">
  An Android app for the Nikon D5600 that replaces SnapBridge.<br>
  It talks to the camera directly over its own Wi-Fi. No cloud, no account.
</p>

---

I wrote it for my own camera and phone (a D5600 and a Galaxy A35), and it isn't on Google Play. Only one driver knows about Nikon, so other PTP/IP cameras could be added later.

## Features

| | |
|---|---|
| **Connect** | Joins the camera's Wi-Fi from the app and reconnects to the last camera on launch. A Quick Settings tile opens the remote. |
| **Gallery** | Lists the photos on the card. Saves JPEG and NEF (RAW) at full size. |
| **Share** | Original files, JPEG/HEIF/WebP copies with size, crop and an optional photo card, a ZIP, or a contact-sheet PDF. Location can be removed first. |
| **Remote** | Live view, shooting with or without autofocus, touch and manual focus, bulb, movies, exposure settings. |
| **Inspector** | Every property the camera reports, with readable names and values. |
| **Learn** | Beginner lessons that work without the camera. See below. |

### Learn

- Bundled photos change as you move aperture, shutter speed, ISO, exposure, white balance and framing.
- A drawing of the D5600 turns to each control and shows what it does.
- **Explain a photo** reads the settings out of any photo on the phone.
- **Recipes** show how to get a given look on the D5600, with a before/after preview.

## Building

You need the Android SDK and JDK 17 or newer.

```bash
./gradlew :app:installDebug          # build and install on a connected phone
./gradlew :ptp:test                  # protocol and driver tests against the fake camera
./gradlew :app:testDebugUnitTest     # app unit tests
```

> `gradle.properties` points Gradle at Android Studio's bundled JDK on macOS. If yours is elsewhere, change `org.gradle.java.home` or pass `-Dorg.gradle.java.home=/path/to/jdk`.

### Without a camera

```bash
./gradlew :ptp:runFakeCamera         # a fake D5600 on port 15740
```

The emulator reaches it at `10.0.2.2`. Add `-Pkbps=150` to slow transfers down when testing progress and cancelling.

[AGENTS.md](AGENTS.md) covers the architecture, the quirks found on the real D5600, and the design and testing rules.

## Releasing

Bump `viewfinder.version` in `gradle.properties`, add its section to [CHANGELOG.md](CHANGELOG.md), and push a `vX.Y.Z` tag. GitHub Actions builds the signed APK and publishes the release. Details and signing: [docs/RELEASING.md](docs/RELEASING.md).

## Credits

- Nikon property names in `NikonCatalog.kt` are generated from [libgphoto2](https://github.com/gphoto/libgphoto2) (LGPL-2.1).
- Learn photos are from [Unsplash](https://unsplash.com) under the Unsplash License. Each photographer is credited in `app/src/main/assets/learn/credits.json` and under the photo in the app.
- Nikon and D5600 are trademarks of Nikon Corporation. This project isn't affiliated with Nikon.
