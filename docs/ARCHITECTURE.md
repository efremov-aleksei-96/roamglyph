# Roamglyph architecture

## Principle

User exploration data is independent from map presentation. A map provider can be
replaced without invalidating exploration history.

## Current data flow

```text
Android LocationManager
        |
accuracy filter (<= 35 m)
        |
H3 resolution 13
        |
current cell + gridDisk(1)
        |
VisitedStore
        |
        +--> MapLibre explored-cell overlay
        +--> portable JSON export/import
```

The foreground service performs tracking while exploration is explicitly active.
The Activity renders map/UI state and does not need to remain visible.

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
preferred candidate, without changing the exploration data or export format.

## Planned storage evolution

`VisitedStore` currently retains the early SharedPreferences history format.
Before large-scale public use it should migrate to SQLite/Room with at least:

- sessions;
- timestamped raw/filtered GPS points;
- unique visited H3 cells with first-seen timestamps;
- discovered POIs.

The existing H3-only JSON remains a valid migration source.
