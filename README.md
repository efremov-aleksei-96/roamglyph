# Roamglyph

**Roamglyph** is an Android “fog of war” map: as you move through the real world, nearby map cells become permanently explored.

## Version 0.2.0

The app now supports continuous exploration with the screen off using an Android location foreground service.

### Current features

- OpenStreetMap raster map rendered with MapLibre.
- Location tracking via Google Fused Location Provider.
- Foreground location service for screen-off/background tracking.
- Persistent notification while exploration is active, with an **Остановить** action.
- H3 resolution 13 exploration grid.
- Each accepted GPS point reveals the current H3 cell plus its immediate neighbors, approximating a ~10 m reveal radius.
- Explored cells persist locally between launches.
- GPS fixes worse than ±35 m are ignored.
- The map reloads cells accumulated while the screen was off.
- GitHub Actions builds a debug APK on every push to `main` and on pull requests.

## Android configuration

- Package: `com.sensareth.roamglyph`
- `minSdk`: 29
- `targetSdk`: 36
- `compileSdk`: 36
- Android Gradle Plugin: 9.4.0
- CI Gradle: 9.6.0
- JDK: 17

## Background tracking model

Tracking is started explicitly by the user while Roamglyph is visible. Android then keeps a location foreground service active with a persistent notification. Closing the Activity or turning off the screen does not intentionally stop exploration.

No `ACCESS_BACKGROUND_LOCATION` permission is requested in this version. The foreground location service is started while the app is visible and already has coarse/fine location permission.

## Exploration geometry

At H3 resolution 13, one average cell is roughly 43.9 m². The center cell plus its six immediate neighbors is roughly 307 m², close to the area of a circle with a radius of about 9.9 m.

## Getting the APK

Open the latest successful **Android CI** run under GitHub Actions and download the `roamglyph-debug-apk` artifact. The ZIP contains `app-debug.apk`.

## Next steps

1. Replace `SharedPreferences` with Room for large exploration histories.
2. Buffer the actual traveled path for a smoother true 10–15 m exploration corridor.
3. Add daily/session statistics and traveled distance.
4. Add district progress for Yerevan.
5. Export/import and backup.
6. Offline map support.
7. Signed release APKs and automatic GitHub Releases.
