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
        +--> portable backup v2
```

The foreground service performs tracking while exploration is explicitly active.
Database/H3 work is serialized off the UI thread. The Activity renders map/UI state
and reloads the complete explored set only at lifecycle/restore boundaries. During
active tracking, newly inserted H3 IDs are broadcast incrementally to the visible
coverage index.

Fog rendering is also serialized on a dedicated worker. At street-level zoom it
uses the authoritative resolution-13 cells directly. When zooming out, explored
cells are aggregated to H3 parents and cached lazily by resolution. Only cells in a
padded visible viewport are converted to GeoJSON, with a hard candidate-cell limit
to prevent pathological rendering cost.

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
OpenStreetMap data
        |
   OpenFreeMap
        |
 vector style/tiles
        |
 MapLibre Native
        |
 Roamglyph overlays
```

The default provider is deliberately replaceable.

Future offline support should use local vector packages, with PMTiles currently the
preferred candidate, without changing the exploration database or backup format.

## Backup model

Version 2 backups are streaming JSON and contain all three core data sets: cells,
sessions, and GPS points. Import validates the complete file before restore and uses
stable IDs plus conflict-ignore inserts so repeated restoration is safe.

See `docs/BACKUP_FORMAT.md`.
