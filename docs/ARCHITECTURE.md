# Roamglyph architecture

## Principle

User exploration data is independent from map presentation. A map provider can be
replaced without invalidating exploration history.

## Current data flow

```text
Android LocationManager
        |
        v
raw GPS point
        |
        +--> local Room gps_points
        |
accuracy + teleport filter
        |
        v
accepted point
        |
        +--> session distance / point statistics
        |
        v
H3 resolution 13
        |
current cell + gridDisk(1)
        |
        v
Room visited_cells
        |
        +--> adaptive H3 coverage index
        |        |
        |        +--> viewport fog cells
        |        +--> viewport explored cells
        |
        +--> MapLibre Fog of War overlay
        +--> discovery matching against active OpenMapTiles POIs
        +--> portable backup v3
```

The foreground service performs tracking while exploration is explicitly active.
Database/H3 work is serialized off the UI thread. The Activity renders map/UI state
and reloads the complete explored set only at lifecycle/restore boundaries. During
active tracking, newly inserted H3 IDs are broadcast incrementally to the visible
coverage index.

Explored polygon contours are projected only on the viewport worker's
geometry updates. Closed-ring Douglas-Peucker removes minor H3 staircase
zigzags from the DISPLAY path while keeping longer straight edges; short
quadratic arcs round major bends. A solitary regular hexagon is presented
as a fully inscribed circular island rather than as a pseudo-hexagon, and
the gradient width is bounded so a one-cell trail keeps a clear center. Crucially, both the smoothed silhouette and
the five progressively darker inward border bands are clipped to the
original exact resolution-13 H3 footprint. No simplification changes stored
visited cells, and no unknown region becomes transparent.

Discoveries use category-appropriate emoji rendered from the Android system
font fallback, above the fog overlay. The emoji mapping comes from existing
OpenMapTiles attributes; no network call or new POI service is introduced.
The installed version and a bundled text changelog are available in the
on-map menu without connectivity.

### Exact-coverage, screen-space Fog of War

Fog is composited by a full-screen transparent Android View above the
MapLibre map, not by a viewport-clipped global GeoJSON fill. The View draws
a dark screen-sized mask every frame and cuts out only actual explored
resolution-13 H3 geographic polygons, reprojected using the *current* map
camera. The opaque baseline exists before history loads, during map motion,
and while the overlay worker is busy: no uncovered strip can arise at a
viewport or vector-tile boundary.

The overlay worker queries a 0.1-degree spatial index for exact res-13 cells
near the viewport, then merges their edges into connected H3 multipolygons.
**No parent aggregation or zoom-based area enlargement is allowed.** A
subpixel visited footprint is drawn with ordinary Android anti-aliasing
rather than expanded to an entire parent hexagon. Visible gradients are
screen-space strokes that darken the edge of visited territory; they never
subtract more area from the black mask. Rounded line joins visually soften
tiny H3 corners. During fast motion an uncached visited area may briefly
remain dark until its exact geometry arrives, but unknown land stays dark.

The overlay records its vector cutouts and five border shades once per
viewport into an Android API 29+ hardware RenderNode display list, not a
screen-quantized bitmap. During camera movement MapLibre supplies a
four-corner projective transform; a single cached display-list draw
replaces five full-path redraws on every camera frame. The native
renderer rasterizes the vector commands at the current zoom, so the
old bitmap safety policy cannot hide a visited trail. A new viewport
may still be dark until its precise H3 geometry loads; the previous traced
path stays spatially anchored through the camera transform.

Each dev test build is explicitly labelled `0.5.0-dev.16+g<sha>` using
the GitHub commit SHA, with an incremented Android versionCode. The
uploaded APK artifact name contains the same commit identity.

The renderer caps its native H3 polygon union at 20,000 exact cells per
viewport. Exceeding that safety limit preserves the previously loaded,
geographically anchored exact vector paths instead of blinking existing
visited routes dark. Unknown map pixels stay covered by the base fog;
no larger synthetic H3 parents are ever substituted. This high-density fallback
requires physical performance testing; future optimization can use exact
tile-based raster masks without changing the permanent user history.
The old nested H3-gradient-band GeoJSON sources have been removed.

## Storage model

### `visited_cells`

- H3 cell ID (primary key)
- first-seen timestamp when known
- provenance/source

Legacy cells from versions before the Room migration are preserved with an unknown
first-seen timestamp because that information did not previously exist.

### `sessions`

- stable UUID
- start/end timestamps
- accumulated distance
- accepted-point count
- newly discovered cell count
- provenance/source

An active session UUID is retained in lightweight app state so an Android service
restart can continue the same session.

### `gps_points`

- stable UUID
- parent session UUID
- timestamp
- latitude/longitude
- accuracy
- optional speed/altitude/provider
- accepted/rejected state
- accepted H3 cell
- rejection reason

Raw points are kept locally so routes and statistics can be reconstructed without
depending on a server.

## Migration from the prototype

The old `visited_h3` SharedPreferences StringSet is treated as a one-time migration
source. Migration uses conflict-ignore inserts, so it is idempotent across crashes
or retries. The legacy set is removed only after the Room copy succeeds.

## Map stack

```text
                         +--> OpenFreeMap online style/tiles
OpenStreetMap data ------|
                         +--> local PMTiles v3 (OpenMapTiles schema)
                                      |
                                      v
                               MapLibre Native
                                      |
             +------------------------+----------------------+
             |                        |                      |
        Fog of War              explored cells        Discoveries
```

The map source is deliberately replaceable. User exploration data is never keyed to
a provider. The local PMTiles archive is copied into app-private storage and exposed
to MapLibre through `pmtiles://file://`.

The bundled offline style intentionally contains only geometry layers and therefore
needs no remote glyph or sprite requests. The source ID remains `openmaptiles`, so
the same discovery scanner can query the `poi` source layer online or offline.

## Backup model

Version 3 backups are streaming JSON and contain cells, sessions, GPS points, and
persistent discoveries. Import validates the complete file before restore and uses
stable IDs plus conflict-ignore inserts so repeated restoration is safe.

The PMTiles basemap itself is intentionally not included in a Roamglyph backup:
maps can be very large and are replaceable presentation data, while exploration
history is the portable user-owned data.

See `docs/BACKUP_FORMAT.md`.
