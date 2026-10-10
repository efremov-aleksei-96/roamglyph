# Changelog

## 0.5.0 - unreleased

### Added

- Adaptive H3 Fog of War with viewport-aware rendering and a persistent visibility toggle.
- Local-first Discoveries derived from already-loaded OpenMapTiles POI data.
- Persistent discovery history with Room storage and portable backup format v3.
- Discovery progress in the status card and anonymous nearby POI hints before visit.
- Local PMTiles v3 MVT import using Android's system document picker.
- A network-independent offline geometry style for OpenMapTiles-compatible archives.
- Persistent switching between online OpenFreeMap and the local PMTiles basemap.
- Local History & statistics screen with aggregate exploration metrics and recent
  session summaries.

### Changed

- Replaced Google Fused Location Provider with Android's open platform
  `LocationManager`; Roamglyph no longer depends on Google Play Services.
- Switched the default online basemap from direct OpenStreetMap raster tiles to
  OpenFreeMap vector styling rendered by MapLibre.
- Changed all in-app and notification text to English.
- Made location recentering slower and smoother.
- Added a real Fog of War overlay: unexplored map cells are darkened while explored
  territory remains visible.
- Fog rendering uses exact H3 resolution 13 at street-level zoom and progressively
  coarser parent cells only when zooming out.
- Added a persistent Fog of War on/off switch.
- Added viewport-only fog rendering, viewport padding, and incremental cell updates
  so normal panning does not rebuild the complete exploration history.
- Correctly reports when Android location services are disabled.

### Data and portability

- Replaced the cell `SharedPreferences StringSet` with a Room/SQLite data model.
- Added persistent exploration sessions and timestamped GPS-point history.
- Added first-seen timestamps for newly discovered H3 cells.
- Added a high-speed GPS teleport filter while preserving ordinary cycling.
- Added streaming portable backup format v3 containing cells, sessions, GPS points,
  and discoveries, while retaining import support for versions 1 and 2.
- Added idempotent migration of existing pre-Room cells into the database.

### Security and distribution

- Removed the publicly committed debug signing key from the repository head.
- Added protected release-signing configuration using environment/secrets only.
- Added CI checks rejecting proprietary Google/Firebase runtime SDKs and committed
  signing keys.
- Added Apache-2.0 licensing, privacy, third-party notices, and app-store metadata.
- Added a tag-driven signed GitHub release workflow.

### Compatibility

The pre-0.5 development signing certificate is compromised. Export history before
moving from those development APKs to the first official release.
