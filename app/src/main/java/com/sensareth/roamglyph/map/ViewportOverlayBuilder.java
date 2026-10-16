package com.sensareth.roamglyph.map;

import androidx.annotation.NonNull;

import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts only authoritative res-13 H3 cells into merged geographic polygons.
 * Zoom MUST NOT select parent cells: a single partially explored parent would
 * falsely reveal as much as seven times the user's actual coverage.
 *
 * Expensive native polygon work runs on the existing serialized overlay worker.
 * Dense explored regions are merged in spatially ordered batches so a single
 * overview tile never disappears solely because more than 20,000 exact H3
 * cells overlap its padded geographic bounds. No H3 parent expansion occurs.
 */
public final class ViewportOverlayBuilder {
    public static final int SOURCE_RESOLUTION = 13;
    public static final int MAX_RENDERED_CELLS = 120_000;
    static final int MAX_MERGE_BATCH = 4_096;
    private static final double VIEWPORT_PADDING = 0.6;

    private ViewportOverlayBuilder() {
    }

    public static int resolutionForZoom(double zoom) {
        // Canonical stored H3 accuracy is invariant under camera zoom.
        return SOURCE_RESOLUTION;
    }

    public static boolean isExactCoverage(int resolution) {
        return resolution == SOURCE_RESOLUTION;
    }

    @NonNull
    public static Result build(
            @NonNull H3Core h3,
            @NonNull ExplorationCoverageIndex coverage,
            double north,
            double east,
            double south,
            double west,
            double zoom
    ) {
        if (!Double.isFinite(zoom) || !validBounds(north, east, south, west)) {
            return Result.dark();
        }

        double latPadding = (north - south) * VIEWPORT_PADDING;
        double lngPadding = (east - west) * VIEWPORT_PADDING;
        Set<String> cells = coverage.cellsInBounds(
                h3,
                SOURCE_RESOLUTION,
                Math.min(85.0, north + latPadding),
                Math.min(180.0, east + lngPadding),
                Math.max(-85.0, south - latPadding),
                Math.max(-180.0, west - lngPadding),
                MAX_RENDERED_CELLS
        );

        if (!withinGeometryBudget(cells.size())) return Result.overBudget();
        if (cells.isEmpty()) return new Result(Collections.emptyList(), 0);

        try {
            // Split large regions into bounded, geographically coherent batches.
            // H3's hierarchical hexadecimal IDs cluster neighbouring cells:
            // sorting reduces repeated internal boundaries within each merge.
            // Batches are disjoint sets of exact source cells. Combining their
            // polygon rings under Path.FillType.EVEN_ODD preserves the union
            // without filling ANY unvisited H3 parent or polygon interior.
            List<List<List<LatLng>>> polygons = new ArrayList<>();
            for (Set<String> batch : spatialBatches(cells)) {
                polygons.addAll(h3.cellAddressesToMultiPolygon(batch, true));
            }
            return new Result(polygons, cells.size());
        } catch (RuntimeException error) {
            // A transient native merge failure is NOT a valid empty tile.
            // Keep cached geography and retry on the next tile request.
            return Result.overBudget();
        }
    }

    /** A pure-Java, exactly-once partition of the authoritative H3 cells. */
    static List<Set<String>> spatialBatches(@NonNull Set<String> cells) {
        List<String> ordered = new ArrayList<>(cells);
        Collections.sort(ordered);
        List<Set<String>> batches = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i += MAX_MERGE_BATCH) {
            batches.add(new HashSet<>(
                    ordered.subList(i, Math.min(ordered.size(), i + MAX_MERGE_BATCH))));
        }
        return batches;
    }

    public static boolean withinGeometryBudget(int exactCellCount) {
        return exactCellCount >= 0 && exactCellCount <= MAX_RENDERED_CELLS;
    }

    private static boolean validBounds(
            double north, double east, double south, double west
    ) {
        return Double.isFinite(north) && Double.isFinite(east)
                && Double.isFinite(south) && Double.isFinite(west)
                && north > south && north <= 85.0 && south >= -85.0
                && east > west && east <= 180.0 && west >= -180.0
                && east - west <= 180.0;
    }

    public static final class Result {
        @NonNull public final List<List<List<LatLng>>> polygons;
        public final int exactCellCount;
        public final boolean preservePreviousGeometry;

        Result(@NonNull List<List<List<LatLng>>> polygons, int exactCellCount) {
            this(polygons, exactCellCount, false);
        }

        Result(@NonNull List<List<List<LatLng>>> polygons,
               int exactCellCount, boolean preservePreviousGeometry) {
            this.polygons = polygons;
            this.exactCellCount = exactCellCount;
            this.preservePreviousGeometry = preservePreviousGeometry;
        }

        public static Result dark() {
            return new Result(Collections.emptyList(), 0);
        }

        public static Result overBudget() {
            // Retain older exact geographically-anchored cutouts; replacing
            // them with an empty set would blink entire routes dark on zoom-out.
            return new Result(Collections.emptyList(), 0, true);
        }
    }
}
