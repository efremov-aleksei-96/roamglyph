# Offline maps

Roamglyph supports a local basemap using **PMTiles version 3**.

## Requirements

The archive must:

- be PMTiles specification version 3;
- contain vector MVT tiles;
- use the OpenMapTiles layer schema, because Roamglyph's bundled offline style and
  Discoveries feature expect layers such as `building`, `transportation`,
  `water`, `park`, `boundary`, and `poi`.

Roamglyph validates the PMTiles magic number, specification version, and vector tile
type before replacing the active local map.

## Import

Use **Menu → Import local PMTiles…** and select a file through Android's system
document picker.

Roamglyph does not retain a broad storage permission. It streams the selected file
into:

```text
<app-private files>/maps/basemap.pmtiles
```

The previous local map is replaced only after the new temporary copy passes header
validation.

MapLibre reads the archive through:

```text
pmtiles://file://<absolute app-private path>
```

This follows MapLibre Native Android's built-in local PMTiles support.

## Offline style

The first bundled offline style intentionally has no `glyphs` or `sprite` URL.
This makes its geometry independent of network services.

It currently renders:

- landuse;
- parks;
- water and waterways;
- building footprints;
- transportation geometry;
- administrative boundaries.

Text labels and ordinary basemap POI icons are intentionally omitted for now.
Roamglyph's own Discoveries markers remain available because they query the
OpenMapTiles `poi` source layer directly.

## Data ownership

The PMTiles file is presentation data and is not part of Roamglyph's portable
backup. Exploration cells, GPS history, sessions, and discoveries remain independent
of the basemap and survive switching, replacing, or deleting the local map.

## Removal

**Remove local PMTiles** first switches the active style back to online OpenFreeMap,
then deletes the app-private PMTiles copy. It does not touch the Room database or
portable history.
