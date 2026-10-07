# Roamglyph

**Roamglyph** is an Android “fog of war” map: as you move through the real world, nearby map cells become permanently explored.

## MVP

- OpenStreetMap raster map rendered with MapLibre.
- Location tracking via Google Fused Location Provider.
- H3 resolution 13 exploration grid.
- Each accepted GPS point reveals the current H3 cell plus its immediate neighbors, approximating a ~10 m reveal radius.
- Explored cells persist locally between launches.
- GPS fixes worse than ±35 m are ignored.
- GitHub Actions builds a debug APK on every push to `main` and on pull requests.

## Android configuration

- Package: `com.sensareth.roamglyph`
- `minSdk`: 29
- `targetSdk`: 36
- `compileSdk`: 36
- Android Gradle Plugin: 9.4.0
- CI Gradle: 9.6.0
- JDK: 17

## Current limitation

The MVP tracks while the Activity is active. Reliable courier-style tracking with the screen off still requires an Android foreground location service.

## Planned next steps

1. Foreground location service and persistent notification.
2. Room-backed storage instead of `SharedPreferences` for very large explored datasets.
3. Path buffering for a true 10–15 m corridor instead of `gridDisk(1)` approximation.
4. Daily and district statistics.
5. Progress export/import.
6. Offline map support for Yerevan.

## Exploration geometry

At H3 resolution 13, one average cell is roughly 43.9 m². The center cell plus its six immediate neighbors is roughly 307 m², close to the area of a circle with a radius of about 9.9 m.

## Getting the APK

After a successful GitHub Actions run, download the `roamglyph-debug-apk` artifact from the run page and install `app-debug.apk` on Android.
