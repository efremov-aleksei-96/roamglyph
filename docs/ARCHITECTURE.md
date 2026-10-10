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

Fog rendering is serialized on a dedicated worker. MapLibre begins with a
world-covering dark mask rather than an empty GeoJSON source, eliminating
the exposure of unexplored map tiles while the viewport changes. As the map
moves, already rendered cutouts remain geographically anchored until the next
mask arrives.

Explored areas are merged into contiguous H3 outlines and cut as transparent
holes in the dark mask. Two larger H3 neighbourhoods form progressively darker
transition bands around explored territory. There are no individual hex-tile
outlines or uniform green overlays. The renderer keeps exact resolution-13
cells until their projected diameter falls below approximately one map pixel,
then chooses the smallest H3 parent that is still visible. A 0.1-degree indexed
spatial lookup selects explored cells from padded viewport bounds rather than
polyfilling every unknown candidate. A hard per-pass limit may force a coarser
view-only resolution to avoid pathological native triangulation costs. No
rendering operation modifies recorded H3 history.

At polar or antimeridian extremes, failed/incompatible geometry is handled
conservatively by keeping those areas dark rather than exposing unknown places.

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
