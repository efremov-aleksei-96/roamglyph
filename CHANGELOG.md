# Changelog

## 0.5.0-dev.20 — 2026-10-10 (experimental test build)

- Based on direct Pixel screenshots of dev.18/dev.19: soften the
  UNDERLYING H3 silhouette, not simply a rounded hexagonal outline.
- Compute a continuous 2D occupancy field using two separable box-blur
  passes (approximately Gaussian) over the exact H3-13 source stencil.
  Spatial occupancy gates a broad visible INNER gradient, suppressing
  isolated hexagonal teeth rather than retaining their hard edges.
- A separate low-opacity center channel preserves narrow real routes;
  city-scale subpixel traces survive without showing their H3 borders.
- Preserve exact geographical correctness: output alpha never exceeds
  the underlying visited mask; the GPU additionally clips to the H3
  vector geometry during pan and zoom.
- Add pure JVM synthetic-image tests for a broad-area gradient, narrow
  routes, protruding H3-like tips, and no outward mask expansion.
- Filter into an off-screen padded work mask (support of both blur passes)
  before cropping the viewport, eliminating false seams during panning.
- Cancel superseded mask jobs during distance and filter passes instead of
  finishing stale multi-megapixel computations. Work area including the
  padding remains under the bounded memory budget.
- No history, JSON backup, discoveries, permissions or storage migration.
- Requires physical-device visual verification before release.


## 0.5.0-dev.19 — 2026-10-10 (experimental test build)

- On-device dev.18 feedback confirmed visible H3 sawteeth and almost no
  readable fog gradient on wide explored corridors.
- Increase exact-mask low-pass radius while removing the old minimum 48%
  transparency on individual H3 protrusions: opacity now follows the local
  density of visited pixels, suppressing sawtooth peaks instead of merely
  blurring their hard outline.
- Slightly broaden continuous INWARD feather and blend short-range and
  long-range ramps to preserve the visibility of thin explored routes;
  keep full
  transparency in wide confidently explored interiors and preserve narrow
  trails as subtle hints.
- Hard safety invariants unchanged: the exact source mask bounds
  transparency, and the live vector clip cannot reveal unexplored land.
- No data/schema migration. Screenshot verification on real Pixel required.


## 0.5.0-dev.18 — 2026-10-10 (test build, not released)

- Add `⋮ → Check for updates` directly inside the Android application.
  Shows the installed source-specific version and offers two website links:
  official GitHub Releases and development APKs in the main-branch Android
  CI workflow. Public releases are not available yet.
- Add the same action to the offline About / Version & Changelog dialog.
  No automatic update checking, APK installation, new permissions or
  remote telemetry. The user explicitly opens the website.
- Distinct build identity: versionCode 18, versionName
  `0.5.0-dev.18+g<commit>` and named GitHub Actions APK artifact.

## 0.5.0-dev.17 — 2026-10-10 (test build, not released)

- Replace visibly hexagonal vector contour strokes with a continuous,
  anti-aliased raster alpha field, sampled up to 2x screen resolution.
- Blur exact H3-13 coverage and apply a smoothly graduated INNER feather:
  source-alpha gating plus exact vector clipping ensure that no unvisited
  cell opens, including while the cached mask is zoomed or panned.
- Build masks off the main thread with one coalesced latest-viewport task.
  Keep the previous validated fog snapshot until the new mask is ready;
  cancel detached-view work and cap raster pixels even on huge displays.
- Precompute area-filtered 2× mip levels so thin explored traces do not
  flicker or vanish from bilinear-only sampling during rapid zoom-out.
  At wide zoom, adapt feather width down to the scale of real H3 pixels.
- Add unit tests for boundary safety, interior gradation, and narrow paths.
- Still requires visual and performance validation on a physical phone.
- Exploration data, history and JSON backup format are unchanged.


## 0.5.0-dev.16 — 2026-10-10 (test build, not released)

- Development APK gets versionCode 16 and a source-specific versionName
  such as `0.5.0-dev.16+g12345678`; the downloadable APK is named with
  the revision, so installed test builds can be identified and compared.
- Removed the raster zoom threshold that caused the explored path to
  blink completely dark and then reappear while pinching/zooming.
  The screen-space fog now caches VECTOR draw commands in a hardware
  RenderNode display list and projects them at the actual camera zoom,
  without enlarging pixels or replaying five full paths on every frame.
- Straightens short H3 zigzags on long explored borders using closed-ring
  line simplification, then rounds corners without falsely revealing
  any unknown area outside the original exact res-13 coverage.
  Single isolated H3 cells appear as inscribed circles rather than hexagons.
- Restored five visible inward fog gradation levels of progressively
  greater darkness along curved contours.
- Wide-zoom H3 budget overflow now preserves already rendered exact routes
  instead of blanking them until the next viewport update.
- No migration or change to persisted GPS, H3, discoveries or backups.

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
- The early renderer used coarse zoom-dependent H3 parents; later fixes
  removed them. Current Fog of War always uses exact H3 resolution 13.
- Added a persistent Fog of War on/off switch.
- Replaced the sparse hex-tile fog with persistent, world-covering inverted
  masks and three graduated darkness bands around explored areas.
- Preserved exact H3 res-13 coverage at every zoom, with spatially
  indexed viewport lookup (no parent-cell display aggregation).
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
