package com.sensareth.roamglyph.map;

import androidx.annotation.NonNull;

import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Overlay-worker-owned coverage cache. Persisted resolution-13 cells are always
 * authoritative; coarser H3 parents are only a view-dependent presentation.
 *
 * A lazy 0.1-degree spatial index prevents each camera pan from scanning the
 * complete trip history or filling the entire viewport with candidate cells.
 */
public final class ExplorationCoverageIndex {
    private static final int SOURCE_RESOLUTION = 13;
    private static final int MAX_CACHED_RESOLUTIONS = 4;
    private static final double BUCKET_DEGREES = 0.1;
    private static final int MAX_BUCKET_LOOKUPS = 25_000;

    private final Set<String> exactCells = new HashSet<>();
    private final LinkedHashMap<Integer, Set<String>> parentCache =
            new LinkedHashMap<>(8, 0.75f, true);
    private final LinkedHashMap<Integer, Map<Long, List<CellCenter>>> spatialCache =
            new LinkedHashMap<>(8, 0.75f, true);

    public void replaceAll(@NonNull Collection<String> cells) {
        exactCells.clear();
        exactCells.addAll(cells);
        parentCache.clear();
        spatialCache.clear();
    }

    public void addAll(@NonNull H3Core h3, @NonNull Collection<String> cells) {
        if (cells.isEmpty()) return;

        Set<String> added = new HashSet<>();
        for (String cell : cells) {
            if (exactCells.add(cell)) added.add(cell);
        }
        if (added.isEmpty()) return;

        Map<Long, List<CellCenter>> exactSpatial = spatialCache.get(SOURCE_RESOLUTION);
        if (exactSpatial != null) {
            for (String cell : added) indexCell(h3, exactSpatial, cell);
        }

        for (Map.Entry<Integer, Set<String>> entry : parentCache.entrySet()) {
            int resolution = entry.getKey();
            Set<String> parents = entry.getValue();
            Map<Long, List<CellCenter>> spatial = spatialCache.get(resolution);

            for (String cell : added) {
                try {
                    String parent = h3.cellToParentAddress(cell, resolution);
                    if (parents.add(parent) && spatial != null) {
                        indexCell(h3, spatial, parent);
                    }
                } catch (RuntimeException ignored) {
                    // Corrupt imported cells must not break the map.
                }
            }
        }
    }

    @NonNull
    public Set<String> cellsAtResolution(@NonNull H3Core h3, int resolution) {
        if (resolution == SOURCE_RESOLUTION) return exactCells;

        Set<String> cached = parentCache.get(resolution);
        if (cached != null) return cached;

        Set<String> parents = new HashSet<>();
        for (String cell : exactCells) {
            try {
                parents.add(h3.cellToParentAddress(cell, resolution));
            } catch (RuntimeException ignored) {
                // Corrupt imported cells must not break the map.
            }
        }
        parentCache.put(resolution, parents);
        trimCache();
        return parents;
    }

    /**
     * Returns explored cells intersecting the padded camera bounds, with an
     * additional H3 cell-radius margin to include cells whose centers are
     * outside the viewport. Stops after maxResults + 1 for a safe fallback.
     */
    @NonNull
    public Set<String> cellsInBounds(
            @NonNull H3Core h3,
            int resolution,
            double north,
            double east,
            double south,
            double west,
            int maxResults
    ) {
        Map<Long, List<CellCenter>> spatial = spatialCache.get(resolution);
        if (spatial == null) {
            spatial = new HashMap<>();
            for (String cell : cellsAtResolution(h3, resolution)) {
                indexCell(h3, spatial, cell);
            }
            spatialCache.put(resolution, spatial);
            trimCache();
        }

        // Average H3 res-13 circumradius ~4 m; add ample margin for pentagons
        // and non-equatorial geometry. Source cells are never resampled in Room.
        double radiusDegrees = 0.00013 * Math.pow(Math.sqrt(7), SOURCE_RESOLUTION - resolution);
        double minLat = Math.max(-90.0, south - radiusDegrees);
        double maxLat = Math.min(90.0, north + radiusDegrees);
        double minLng = Math.max(-180.0, west - radiusDegrees);
        double maxLng = Math.min(180.0, east + radiusDegrees);

        int y1 = bucketCoordinate(minLat);
        int y2 = bucketCoordinate(maxLat);
        int x1 = bucketCoordinate(minLng);
        int x2 = bucketCoordinate(maxLng);
        Set<String> result = new HashSet<>();

        long lookups = ((long) y2 - y1 + 1L) * ((long) x2 - x1 + 1L);
        if (lookups > MAX_BUCKET_LOOKUPS) {
            // At continent/world zoom, iterating occupied bins is cheaper.
            for (List<CellCenter> bucket : spatial.values()) {
                collectWithin(bucket, minLat, maxLat, minLng, maxLng, result, maxResults);
                if (result.size() > maxResults) break;
            }
        } else {
            for (int y = y1; y <= y2 && result.size() <= maxResults; y++) {
                for (int x = x1; x <= x2 && result.size() <= maxResults; x++) {
                    List<CellCenter> bucket = spatial.get(bucketKey(y, x));
                    if (bucket != null) {
                        collectWithin(bucket, minLat, maxLat, minLng, maxLng, result, maxResults);
                    }
                }
            }
        }
        return result;
    }

    private static void collectWithin(
            List<CellCenter> bucket,
            double south, double north, double west, double east,
            Set<String> output,
            int limit
    ) {
        for (CellCenter center : bucket) {
            if (center.lat >= south && center.lat <= north
                    && center.lng >= west && center.lng <= east) {
                output.add(center.h3);
                if (output.size() > limit) return;
            }
        }
    }

    private static void indexCell(
            H3Core h3,
            Map<Long, List<CellCenter>> target,
            String cell
    ) {
        try {
            LatLng location = h3.cellToLatLng(cell);
            long key = bucketKey(
                    bucketCoordinate(location.lat),
                    bucketCoordinate(location.lng)
            );
            target.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new CellCenter(cell, location.lat, location.lng));
        } catch (RuntimeException ignored) {
            // Invalid legacy data must not break indexing.
        }
    }

    private static int bucketCoordinate(double degrees) {
        return (int) Math.floor(degrees / BUCKET_DEGREES);
    }

    private static long bucketKey(int latitudeBucket, int longitudeBucket) {
        return ((long) latitudeBucket << 32) | (longitudeBucket & 0xFFFFFFFFL);
    }

    public int exactCellCount() {
        return exactCells.size();
    }

    private void trimCache() {
        while (parentCache.size() > MAX_CACHED_RESOLUTIONS) {
            Iterator<Map.Entry<Integer, Set<String>>> it = parentCache.entrySet().iterator();
            Map.Entry<Integer, Set<String>> oldest = it.next();
            // Evict only an LRU parent entry and its dependent spatial index.
            int resolution = oldest.getKey();
            it.remove();
            spatialCache.remove(resolution);
        }
        while (spatialCache.size() > MAX_CACHED_RESOLUTIONS + 1) {
            Iterator<Map.Entry<Integer, Map<Long, List<CellCenter>>>> it =
                    spatialCache.entrySet().iterator();
            if (!it.hasNext()) break;
            int resolution = it.next().getKey();
            it.remove();
            if (resolution != SOURCE_RESOLUTION) parentCache.remove(resolution);
        }
    }

    private static final class CellCenter {
        final String h3;
        final double lat;
        final double lng;

        CellCenter(String h3, double lat, double lng) {
            this.h3 = h3;
            this.lat = lat;
            this.lng = lng;
        }
    }
}
