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
- Streaming GPX 1.1 export for individual sessions from the history screen.

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
- Replaced the sparse hex-tile fog with persistent, world-covering inverted
  masks and three graduated darkness bands around explored areas.
- Kept fine H3 detail down to roughly one screen pixel before aggregating,
  and introduced spatially indexed viewport coverage lookups.
- Added accessible on-map +/− zoom controls.
- Added curved, conservatively clipped explored-area silhouettes so H3
  cell corners do not remain visible after edge feathering.
- Display meaningful emoji icons on Discoveries and hints, derived from
  local POI categories without external icon or font dependencies.
- Added an in-app Version & Changelog entry accessible from the map menu;
  release notes are bundled for offline reading.
- Replaced the geospatial GeoJSON fog with a screen-space mask tied to
  the live MapLibre camera, to eliminate rapid-pan edge flashes.
- Removed zoom-based parent aggregation entirely: only true res-13
  visited territory is ever transparent, including at distant zoom.
- Added anti-aliased, round-joined dark edge feathering that does not
  artificially enlarge explored coverage.
- Added viewport-only fog rendering, viewport padding, and incremental cell updates
  so normal panning does not rebuild the complete exploration history.
- Correctly reports when Android location services are disabled.

### Data and portability

- Replaced the cell `SharedPreferences StringSet` with a Room/SQLite data model.
- Added persistent exploration sessions and timestamped GPS-point history.
- Added first-seen timestamps for newly discovered H3 cells.
- Added a high-speed GPS teleport filter while preserving ordinary cycling.
- Reject missing/invalid GPS accuracy and expired or duplicate location fixes
  using Android's monotonic clock, avoiding false exploration after time changes.
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
