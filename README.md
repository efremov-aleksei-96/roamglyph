# Roamglyph

**Roamglyph** is a local-first, open-source Android exploration map. As you move
through the real world, nearby H3 cells become permanently explored.

Core functionality does not depend on a Roamglyph account or a
Roamglyph-operated server.

## Status

Current development version: **0.5.0**

This is still an early project. The core GPS exploration flow, background tracking,
history persistence, and legacy cell export/import have been tested on a physical
Android device. The current development branch adds Room/SQLite sessions, timestamped
GPS points, first-seen cell metadata, and full portable backups; these changes still
require physical-device regression testing before the first public 0.5.0 release.
The current development branch also adds adaptive Fog of War; these changes still
require physical-device regression testing before the first public 0.5.0 release.
Offline vector-map packages, richer statistics, and discoverable POIs remain future
work.

## Current features

- MapLibre Native map rendering.
- OpenFreeMap vector basemap based on OpenStreetMap.
- Android platform `LocationManager` — no Google Play Services dependency.
- Explicit foreground location service for screen-off/background exploration.
- Persistent foreground notification with a Stop action.
- H3 resolution 13 exploration grid.
- Each accepted fix reveals the current H3 cell plus its immediate neighbours,
  approximating a roughly 10 m exploration radius.
- GPS fixes worse than ±35 m are ignored for exploration.
- Room/SQLite storage for explored cells, exploration sessions, and GPS points.
- First-seen timestamps for newly explored cells.
- GPS teleport rejection in addition to the ±35 m accuracy filter.
- Portable streaming JSON backup/restore for cells, sessions, and GPS points.
- Backward-compatible import of legacy 0.4.x cell-only JSON exports.
- Adaptive Fog of War with exact res-13 reveal geometry at normal street zoom.
- Coarser H3 overview rendering only when zooming out, bounded to the visible
  viewport for predictable performance.
- Persistent Fog of War on/off switch.
- Incremental on-screen H3 updates while tracking instead of reloading the entire
  visited-cell table for each newly explored cell.
- Smooth manual recentering without forced camera-follow while browsing.
- No account, ads, analytics, Firebase, or mandatory backend.

## Privacy

Location history and explored cells remain in the app's private local storage.
Roamglyph does not upload them to a Roamglyph server. Android cloud backup is
disabled. See [PRIVACY.md](PRIVACY.md).

The default map is online in 0.5.0 and is served by OpenFreeMap over HTTPS.
Discoveries are derived locally from POI features already present in the loaded
OpenMapTiles vector source; Roamglyph does not contact a separate POI service.
Offline PMTiles support is planned.

## Open-source distribution

Roamglyph is licensed under **Apache-2.0**.

The runtime is kept compatible with F-Droid's FLOSS requirements: proprietary
Google Play Services, Firebase, ad, and analytics SDKs are forbidden by CI.

Upstream Fastlane/F-Droid listing text lives under
`fastlane/metadata/android/`.

See [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md) for release, signing, F-Droid,
and IzzyOnDroid details.

## Android configuration

- Application ID: `com.sensareth.roamglyph`
- minSdk: 29
- targetSdk / compileSdk: 36
- JDK: 17
- Gradle: 9.6.0
- Android Gradle Plugin: 9.4.0

## Build

With JDK 17, Android SDK platform 36, and Gradle 9.6.0 installed:

```bash
gradle :app:assembleDebug
```

Verification:

```bash
gradle :app:testDebugUnitTest
gradle :app:lintDebug
gradle :app:assembleRelease
```

A release build without signing environment variables is intentionally unsigned so
source-based repositories such as F-Droid can rebuild and sign it themselves.

## Signing warning for early testers

The debug key used by development versions through 0.4.0 was accidentally committed
to this public repository and is permanently untrusted.

Before installing the first official securely signed release, export your history.
A one-time uninstall/reinstall may be required because Android will reject a new
certificate as an in-place update.

## Attribution

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Map data © OpenStreetMap contributors. The default basemap is served by OpenFreeMap
and rendered using MapLibre.
